package kz.glasswave.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

object GwPlaybackState {

    private const val PREFS = "gw-playback-service"

    @Volatile var active = false
    @Volatile var trackId = ""
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var playing = false
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var heartbeat = 0L

    @Synchronized
    fun update(
        context: Context,
        isActive: Boolean,
        id: String,
        position: Long,
        duration: Long,
        isPlaying: Boolean,
        trackTitle: String,
        trackArtist: String
    ) {
        active = isActive
        trackId = id
        positionMs = position.coerceAtLeast(0L)
        durationMs = duration.coerceAtLeast(0L)
        playing = isPlaying
        title = trackTitle
        artist = trackArtist
        heartbeat = System.currentTimeMillis()

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("active", active)
            .putString("trackId", trackId)
            .putLong("positionMs", positionMs)
            .putLong("durationMs", durationMs)
            .putBoolean("playing", playing)
            .putString("title", title)
            .putString("artist", artist)
            .putLong("heartbeat", heartbeat)
            .apply()
    }

    @Synchronized
    fun json(context: Context): String {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val savedHeartbeat = prefs.getLong("heartbeat", 0L)
        val live =
            System.currentTimeMillis() - savedHeartbeat < 10_000L

        return JSONObject()
            .put(
                "active",
                active || (prefs.getBoolean("active", false) && live)
            )
            .put(
                "trackId",
                if (trackId.isNotBlank()) trackId
                else prefs.getString("trackId", "")
            )
            .put(
                "positionMs",
                if (positionMs > 0) positionMs
                else prefs.getLong("positionMs", 0L)
            )
            .put(
                "durationMs",
                if (durationMs > 0) durationMs
                else prefs.getLong("durationMs", 0L)
            )
            .put(
                "isPlaying",
                if (active) playing
                else prefs.getBoolean("playing", false) && live
            )
            .put(
                "title",
                title.ifBlank { prefs.getString("title", "").orEmpty() }
            )
            .put(
                "artist",
                artist.ifBlank { prefs.getString("artist", "").orEmpty() }
            )
            .toString()
    }
}

class GwPlaybackService : Service() {

    companion object {
        const val ACTION_START = "kz.glasswave.player.background.START"
        const val ACTION_PLAY = "kz.glasswave.player.background.PLAY"
        const val ACTION_PAUSE = "kz.glasswave.player.background.PAUSE"
        const val ACTION_PREVIOUS = "kz.glasswave.player.background.PREVIOUS"
        const val ACTION_NEXT = "kz.glasswave.player.background.NEXT"
        const val ACTION_STOP = "kz.glasswave.player.background.STOP"

        const val EXTRA_TRACK_ID = "trackId"
        const val EXTRA_POSITION_MS = "positionMs"

        private const val CHANNEL_ID = "glass_wave_playback"
        private const val NOTIFICATION_ID = 7412
    }

    private data class Track(
        val id: String,
        val uri: String,
        val title: String,
        val artist: String
    )

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession
    private lateinit var focusRequest: AudioFocusRequest

    private var player: MediaPlayer? = null
    private var queue = emptyList<Track>()
    private var currentIndex = -1
    private var prepared = false
    private var foregroundStarted = false
    private var resumeAfterFocus = false

    private val audioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private val ticker = object : Runnable {
        override fun run() {
            publishState(false)
            if (player != null) handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        createChannel()
        createMediaSession()

        focusRequest = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN
        )
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        player?.setVolume(1f, 1f)
                        if (resumeAfterFocus) {
                            resumeAfterFocus = false
                            play()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        resumeAfterFocus = player?.isPlaying == true
                        pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        player?.setVolume(.25f, .25f)
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeAfterFocus = false
                        pause()
                    }
                }
            }, handler)
            .build()

        handler.post(ticker)
    }

    private fun createMediaSession() {
        mediaSession = MediaSession(this, "GLASS_WAVE").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(position: Long) {
                    player?.seekTo(
                        position.coerceAtLeast(0L),
                        MediaPlayer.SEEK_CLOSEST
                    )
                    publishState(false)
                }
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground()
                loadQueue(
                    intent.getStringExtra(EXTRA_TRACK_ID).orEmpty(),
                    intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                )
            }
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_PREVIOUS -> previous()
            ACTION_NEXT -> next()
            ACTION_STOP -> stopPlayback()
            else -> ensureForeground()
        }
        return START_STICKY
    }

    private fun loadQueue(selectedId: String, positionMs: Long) {
        val source = JSONArray(
            GwLibraryStore(applicationContext).asJson()
        )

        queue = buildList {
            for (index in 0 until source.length()) {
                val item = source.getJSONObject(index)
                add(
                    Track(
                        id = item.optString("id"),
                        uri = item.optString("uri"),
                        title = item.optString(
                            "title",
                            item.optString("name", "Аудиотрек")
                        ),
                        artist = item.optString(
                            "artist",
                            "Неизвестный исполнитель"
                        )
                    )
                )
            }
        }

        if (queue.isEmpty()) {
            stopPlayback()
            return
        }

        currentIndex = queue.indexOfFirst { it.id == selectedId }
            .takeIf { it >= 0 } ?: 0

        openTrack(currentIndex, positionMs, true)
    }

    private fun openTrack(
        index: Int,
        positionMs: Long,
        autoPlay: Boolean
    ) {
        if (queue.isEmpty()) return

        currentIndex = (index + queue.size) % queue.size
        val track = queue[currentIndex]

        player?.release()
        prepared = false

        player = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            setWakeMode(
                applicationContext,
                PowerManager.PARTIAL_WAKE_LOCK
            )
            setDataSource(applicationContext, Uri.parse(track.uri))

            setOnPreparedListener {
                prepared = true
                if (positionMs > 0) {
                    seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
                }
                if (autoPlay) play() else publishState(true)
            }

            setOnCompletionListener { next() }

            setOnErrorListener { _, what, extra ->
                GwRuntimeLog.error(
                    "BACKGROUND_PLAYER",
                    "MediaPlayer error what=$what extra=$extra"
                )
                publishState(true)
                true
            }

            prepareAsync()
        }

        publishMetadata(track)
        publishState(true)
    }

    private fun requestAudioFocus(): Boolean {
        return audioManager.requestAudioFocus(focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun play() {
        val activePlayer = player ?: return
        if (!prepared) return
        if (!requestAudioFocus()) return

        runCatching {
            activePlayer.setVolume(1f, 1f)
            activePlayer.start()
        }.onFailure {
            GwRuntimeLog.error("BACKGROUND_PLAYER", "Play failed", it)
        }

        publishState(true)
    }

    private fun pause() {
        runCatching {
            if (player?.isPlaying == true) player?.pause()
        }
        publishState(true)
    }

    private fun next() {
        if (queue.isNotEmpty()) openTrack(currentIndex + 1, 0L, true)
    }

    private fun previous() {
        if (queue.isNotEmpty()) openTrack(currentIndex - 1, 0L, true)
    }

    private fun publishMetadata(track: Track) {
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(
                    MediaMetadata.METADATA_KEY_TITLE,
                    track.title
                )
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    track.artist
                )
                .build()
        )
    }

    private fun publishState(updateNotification: Boolean) {
        val activePlayer = player
        val isPlaying = runCatching {
            activePlayer?.isPlaying == true
        }.getOrDefault(false)

        val position = runCatching {
            activePlayer?.currentPosition?.toLong()
        }.getOrNull() ?: 0L

        val duration = runCatching {
            activePlayer?.duration?.toLong()
        }.getOrNull()?.takeIf { it > 0L } ?: 0L

        val track = queue.getOrNull(currentIndex)

        GwPlaybackState.update(
            applicationContext,
            isActive = activePlayer != null,
            id = track?.id.orEmpty(),
            position = position,
            duration = duration,
            isPlaying = isPlaying,
            trackTitle = track?.title.orEmpty(),
            trackArtist = track?.artist.orEmpty()
        )

        val state = if (isPlaying) PlaybackState.STATE_PLAYING
                    else PlaybackState.STATE_PAUSED

        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(
                    state,
                    position,
                    if (isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )

        if (updateNotification) ensureForeground()
    }

    private fun serviceIntent(
        action: String,
        requestCode: Int
    ): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, GwPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        val track = queue.getOrNull(currentIndex)
        val isPlaying = runCatching {
            player?.isPlaying == true
        }.getOrDefault(false)

        val openApp = PendingIntent.getActivity(
            this,
            40,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            Notification.Action.Builder(
                android.R.drawable.ic_media_pause,
                "Пауза",
                serviceIntent(ACTION_PAUSE, 42)
            ).build()
        } else {
            Notification.Action.Builder(
                android.R.drawable.ic_media_play,
                "Ойнату",
                serviceIntent(ACTION_PLAY, 42)
            ).build()
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: "GLASS WAVE")
            .setContentText(track?.artist ?: "Музыка")
            .setContentIntent(openApp)
            .setDeleteIntent(serviceIntent(ACTION_STOP, 45))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    "Алдыңғы",
                    serviceIntent(ACTION_PREVIOUS, 41)
                ).build()
            )
            .addAction(playPauseAction)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "Келесі",
                    serviceIntent(ACTION_NEXT, 43)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "GLASS WAVE media",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GLASS WAVE playback controls"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun stopPlayback() {
        handler.removeCallbacks(ticker)
        runCatching { player?.stop() }
        player?.release()
        player = null
        prepared = false
        audioManager.abandonAudioFocusRequest(focusRequest)

        GwPlaybackState.update(
            applicationContext,
            false, "", 0L, 0L, false, "", ""
        )

        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        /*
         * stopWithTask=false: recent apps тізімінен алып тастағанда
         * foreground playback жалғасады.
         */
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        audioManager.abandonAudioFocusRequest(focusRequest)
        mediaSession.release()
        player = null
        super.onDestroy()
    }
}
