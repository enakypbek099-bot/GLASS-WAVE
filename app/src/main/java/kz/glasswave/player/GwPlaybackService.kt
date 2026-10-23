package kz.glasswave.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    private const val PREFS =
        "gw-playback-service"

    @Volatile
    var active = false

    @Volatile
    var trackId = ""

    @Volatile
    var positionMs = 0L

    @Volatile
    var durationMs = 0L

    @Volatile
    var playing = false

    @Volatile
    var title = ""

    @Volatile
    var artist = ""

    @Volatile
    var heartbeat = 0L

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
        active =
            isActive

        trackId =
            id

        positionMs =
            position.coerceAtLeast(
                0L
            )

        durationMs =
            duration.coerceAtLeast(
                0L
            )

        playing =
            isPlaying

        title =
            trackTitle

        artist =
            trackArtist

        heartbeat =
            System.currentTimeMillis()

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                "active",
                active
            )
            .putString(
                "trackId",
                trackId
            )
            .putLong(
                "positionMs",
                positionMs
            )
            .putLong(
                "durationMs",
                durationMs
            )
            .putBoolean(
                "playing",
                playing
            )
            .putString(
                "title",
                title
            )
            .putString(
                "artist",
                artist
            )
            .putLong(
                "heartbeat",
                heartbeat
            )
            .apply()
    }

    @Synchronized
    fun json(
        context: Context
    ): String {
        val prefs =
            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

        val savedHeartbeat =
            prefs.getLong(
                "heartbeat",
                0L
            )

        val live =
            System.currentTimeMillis() -
                savedHeartbeat <
                10_000L

        val memoryActive =
            active

        val savedActive =
            prefs.getBoolean(
                "active",
                false
            )

        val resultActive =
            memoryActive ||
                (
                    savedActive &&
                        live
                    )

        val resultPlaying =
            if (memoryActive) {
                playing
            } else {
                prefs.getBoolean(
                    "playing",
                    false
                ) && live
            }

        return JSONObject()
            .put(
                "active",
                resultActive
            )
            .put(
                "trackId",
                if (
                    trackId.isNotBlank()
                ) {
                    trackId
                } else {
                    prefs
                        .getString(
                            "trackId",
                            ""
                        )
                        .orEmpty()
                }
            )
            .put(
                "positionMs",
                if (
                    positionMs > 0L
                ) {
                    positionMs
                } else {
                    prefs.getLong(
                        "positionMs",
                        0L
                    )
                }
            )
            .put(
                "durationMs",
                if (
                    durationMs > 0L
                ) {
                    durationMs
                } else {
                    prefs.getLong(
                        "durationMs",
                        0L
                    )
                }
            )
            .put(
                "isPlaying",
                resultPlaying
            )
            .put(
                "title",
                title.ifBlank {
                    prefs
                        .getString(
                            "title",
                            ""
                        )
                        .orEmpty()
                }
            )
            .put(
                "artist",
                artist.ifBlank {
                    prefs
                        .getString(
                            "artist",
                            ""
                        )
                        .orEmpty()
                }
            )
            .put(
                "heartbeat",
                if (
                    heartbeat > 0L
                ) {
                    heartbeat
                } else {
                    savedHeartbeat
                }
            )
            .toString()
    }
}

class GwPlaybackService :
    Service() {

    companion object {

        const val ACTION_START =
            "kz.glasswave.player.background.START"

        const val ACTION_PLAY =
            "kz.glasswave.player.background.PLAY"

        const val ACTION_PAUSE =
            "kz.glasswave.player.background.PAUSE"

        const val ACTION_PREVIOUS =
            "kz.glasswave.player.background.PREVIOUS"

        const val ACTION_NEXT =
            "kz.glasswave.player.background.NEXT"

        const val ACTION_STOP =
            "kz.glasswave.player.background.STOP"

        const val EXTRA_TRACK_ID =
            "trackId"

        const val EXTRA_POSITION_MS =
            "positionMs"

        private const val CHANNEL_ID =
            "glass_wave_playback"

        private const val NOTIFICATION_ID =
            7412
    }

    private data class Track(
        val id: String,
        val uri: String,
        val title: String,
        val artist: String
    )

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private lateinit var audioManager:
        AudioManager

    private lateinit var mediaSession:
        MediaSession

    private lateinit var focusRequest:
        AudioFocusRequest

    private lateinit var libraryStore:
        GwLibraryStore

    private lateinit var metadataStore:
        GwMediaMetadataStore

    private var player:
        MediaPlayer? = null

    private var queue:
        List<Track> =
        emptyList()

    private var currentIndex =
        -1

    private var prepared =
        false

    private var foregroundStarted =
        false

    private var resumeAfterFocus =
        false

    private var pendingAutoPlay =
        false

    private var pendingPositionMs =
        0L

    private var currentArtwork:
        Bitmap? = null

    private val audioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(
                AudioAttributes.USAGE_MEDIA
            )
            .setContentType(
                AudioAttributes.CONTENT_TYPE_MUSIC
            )
            .build()

    private val ticker =
        object : Runnable {

            override fun run() {
                publishState(
                    updateNotification = false
                )

                if (
                    player != null
                ) {
                    handler.postDelayed(
                        this,
                        1000L
                    )
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        audioManager =
            getSystemService(
                AUDIO_SERVICE
            ) as AudioManager

        libraryStore =
            GwLibraryStore(
                applicationContext
            )

        metadataStore =
            GwMediaMetadataStore(
                applicationContext
            )

        createChannel()
        createMediaSession()

        focusRequest =
            AudioFocusRequest
                .Builder(
                    AudioManager
                        .AUDIOFOCUS_GAIN
                )
                .setAudioAttributes(
                    audioAttributes
                )
                /*
                 * WebView audio focus-ты
                 * бірден босатпауы мүмкін.
                 *
                 * Service focus-ты күте алады.
                 */
                .setAcceptsDelayedFocusGain(
                    true
                )
                .setOnAudioFocusChangeListener(
                    { change ->
                        handleAudioFocusChange(
                            change
                        )
                    },
                    handler
                )
                .build()

        handler.post(
            ticker
        )
    }

    private fun handleAudioFocusChange(
        change: Int
    ) {
        when (change) {

            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching {
                    player?.setVolume(
                        1f,
                        1f
                    )
                }

                if (
                    resumeAfterFocus ||
                    pendingAutoPlay
                ) {
                    resumeAfterFocus =
                        false

                    pendingAutoPlay =
                        false

                    play()
                }
            }

            AudioManager
                .AUDIOFOCUS_LOSS_TRANSIENT -> {

                resumeAfterFocus =
                    runCatching {
                        player?.isPlaying ==
                            true
                    }.getOrDefault(
                        false
                    )

                pause()
            }

            AudioManager
                .AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                runCatching {
                    player?.setVolume(
                        0.25f,
                        0.25f
                    )
                }
            }

            AudioManager
                .AUDIOFOCUS_LOSS -> {

                resumeAfterFocus =
                    false

                pendingAutoPlay =
                    false

                pause()
            }
        }
    }

    private fun createMediaSession() {
        mediaSession =
            MediaSession(
                this,
                "GLASS_WAVE"
            ).apply {

                setFlags(
                    MediaSession
                        .FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession
                            .FLAG_HANDLES_TRANSPORT_CONTROLS
                )

                setCallback(
                    object :
                        MediaSession.Callback() {

                        override fun onPlay() {
                            play()
                        }

                        override fun onPause() {
                            pause()
                        }

                        override fun onSkipToNext() {
                            next()
                        }

                        override fun onSkipToPrevious() {
                            previous()
                        }

                        override fun onSeekTo(
                            position: Long
                        ) {
                            seekTo(
                                position
                            )
                        }

                        override fun onStop() {
                            stopPlayback()
                        }
                    }
                )

                isActive =
                    true
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (
            intent?.action
        ) {

            ACTION_START -> {
                /*
                 * Foreground service Android
                 * timeout-қа түспеу үшін
                 * notification бірден шығады.
                 */
                ensureForeground()

                loadQueue(
                    selectedId =
                        intent
                            .getStringExtra(
                                EXTRA_TRACK_ID
                            )
                            .orEmpty(),

                    positionMs =
                        intent
                            .getLongExtra(
                                EXTRA_POSITION_MS,
                                0L
                            )
                )
            }

            ACTION_PLAY -> {
                play()
            }

            ACTION_PAUSE -> {
                pause()
            }

            ACTION_PREVIOUS -> {
                previous()
            }

            ACTION_NEXT -> {
                next()
            }

            ACTION_STOP -> {
                stopPlayback()
            }

            else -> {
                ensureForeground()
            }
        }

        return START_STICKY
    }

    private fun loadQueue(
        selectedId: String,
        positionMs: Long
    ) {
        val source =
            runCatching {
                JSONArray(
                    libraryStore
                        .asJson()
                )
            }.getOrElse {
                GwRuntimeLog.error(
                    "BACKGROUND_PLAYER",
                    "Library JSON failed",
                    it
                )

                JSONArray()
            }

        val tracks =
            ArrayList<Track>()

        for (
            index in
            0 until source.length()
        ) {
            val item =
                source.optJSONObject(
                    index
                ) ?: continue

            val id =
                item.optString(
                    "id"
                )

            val uri =
                item.optString(
                    "uri"
                )

            if (
                id.isBlank() ||
                uri.isBlank()
            ) {
                continue
            }

            tracks.add(
                Track(
                    id = id,

                    uri = uri,

                    title =
                        item.optString(
                            "title"
                        ).ifBlank {
                            item.optString(
                                "name",
                                "Аудиотрек"
                            )
                        },

                    artist =
                        item.optString(
                            "artist"
                        ).ifBlank {
                            "Неизвестный исполнитель"
                        }
                )
            )
        }

        queue =
            tracks

        if (
            queue.isEmpty()
        ) {
            GwRuntimeLog.warn(
                "BACKGROUND_PLAYER",
                "Queue is empty"
            )

            stopPlayback()

            return
        }

        currentIndex =
            queue
                .indexOfFirst {
                    it.id ==
                        selectedId
                }
                .takeIf {
                    it >= 0
                }
                ?: 0

        openTrack(
            index =
                currentIndex,

            positionMs =
                positionMs,

            autoPlay =
                true
        )
    }

    private fun openTrack(
        index: Int,
        positionMs: Long,
        autoPlay: Boolean
    ) {
        if (
            queue.isEmpty()
        ) {
            return
        }

        currentIndex =
            (
                index +
                    queue.size
                ) %
                queue.size

        val track =
            queue[
                currentIndex
            ]

        prepared =
            false

        pendingAutoPlay =
            autoPlay

        pendingPositionMs =
            positionMs
                .coerceAtLeast(
                    0L
                )

        runCatching {
            player?.reset()
        }

        runCatching {
            player?.release()
        }

        player =
            null

        recycleArtwork()

        currentArtwork =
            loadArtwork(
                track.id
            )

        publishMetadata(
            track
        )

        /*
         * Notification title/artwork
         * prepareAsync басталмай тұрып
         * жаңарады.
         */
        ensureForeground()

        val newPlayer =
            MediaPlayer()

        player =
            newPlayer

        try {
            newPlayer
                .setAudioAttributes(
                    audioAttributes
                )

            newPlayer
                .setWakeMode(
                    applicationContext,
                    PowerManager
                        .PARTIAL_WAKE_LOCK
                )

            newPlayer
                .setDataSource(
                    applicationContext,
                    Uri.parse(
                        track.uri
                    )
                )

            newPlayer
                .setOnPreparedListener {
                    prepared =
                        true

                    val wanted =
                        pendingPositionMs

                    pendingPositionMs =
                        0L

                    if (
                        wanted > 0L
                    ) {
                        runCatching {
                            it.seekTo(
                                wanted,
                                MediaPlayer
                                    .SEEK_CLOSEST
                            )
                        }
                    }

                    if (
                        pendingAutoPlay
                    ) {
                        play()
                    } else {
                        publishState(
                            updateNotification =
                                true
                        )
                    }
                }

            newPlayer
                .setOnSeekCompleteListener {
                    publishState(
                        updateNotification =
                            true
                    )
                }

            newPlayer
                .setOnCompletionListener {
                    next()
                }

            newPlayer
                .setOnErrorListener {
                        _,
                        what,
                        extra ->

                    GwRuntimeLog.error(
                        "BACKGROUND_PLAYER",
                        "MediaPlayer error " +
                            "what=$what " +
                            "extra=$extra"
                    )

                    prepared =
                        false

                    pendingAutoPlay =
                        false

                    publishState(
                        updateNotification =
                            true
                    )

                    true
                }

            newPlayer
                .setOnInfoListener {
                        _,
                        what,
                        extra ->

                    GwRuntimeLog.info(
                        "BACKGROUND_PLAYER",
                        "MediaPlayer info " +
                            "what=$what " +
                            "extra=$extra"
                    )

                    false
                }

            newPlayer
                .prepareAsync()

        } catch (
            error: Throwable
        ) {
            GwRuntimeLog.error(
                "BACKGROUND_PLAYER",
                "Track open failed: " +
                    track.uri,
                error
            )

            prepared =
                false

            pendingAutoPlay =
                false

            publishState(
                updateNotification =
                    true
            )
        }
    }

    private fun requestAudioFocus():
        Boolean {

        return when (
            audioManager
                .requestAudioFocus(
                    focusRequest
                )
        ) {

            AudioManager
                .AUDIOFOCUS_REQUEST_GRANTED -> {

                resumeAfterFocus =
                    false

                true
            }

            AudioManager
                .AUDIOFOCUS_REQUEST_DELAYED -> {

                /*
                 * WebView focus-ты әлі
                 * ұстап тұрса service
                 * осында күте алады.
                 */
                resumeAfterFocus =
                    true

                false
            }

            else -> {
                resumeAfterFocus =
                    false

                false
            }
        }
    }

    private fun play() {
        val activePlayer =
            player
                ?: return

        if (
            !prepared
        ) {
            pendingAutoPlay =
                true

            return
        }

        if (
            !requestAudioFocus()
        ) {
            pendingAutoPlay =
                true

            publishState(
                updateNotification =
                    true
            )

            return
        }

        pendingAutoPlay =
            false

        runCatching {
            activePlayer
                .setVolume(
                    1f,
                    1f
                )

            if (
                !activePlayer
                    .isPlaying
            ) {
                activePlayer
                    .start()
            }
        }.onFailure {
            GwRuntimeLog.error(
                "BACKGROUND_PLAYER",
                "Play failed",
                it
            )
        }

        publishState(
            updateNotification =
                true
        )
    }

    private fun pause() {
        pendingAutoPlay =
            false

        runCatching {
            if (
                player?.isPlaying ==
                true
            ) {
                player?.pause()
            }
        }.onFailure {
            GwRuntimeLog.error(
                "BACKGROUND_PLAYER",
                "Pause failed",
                it
            )
        }

        publishState(
            updateNotification =
                true
        )
    }

    private fun seekTo(
        position: Long
    ) {
        val activePlayer =
            player
                ?: return

        if (
            !prepared
        ) {
            pendingPositionMs =
                position
                    .coerceAtLeast(
                        0L
                    )

            return
        }

        val duration =
            runCatching {
                activePlayer
                    .duration
                    .toLong()
            }.getOrDefault(
                0L
            )

        val target =
            if (
                duration > 0L
            ) {
                position
                    .coerceIn(
                        0L,
                        duration
                    )
            } else {
                position
                    .coerceAtLeast(
                        0L
                    )
            }

        runCatching {
            activePlayer
                .seekTo(
                    target,
                    MediaPlayer
                        .SEEK_CLOSEST
                )
        }.onFailure {
            GwRuntimeLog.error(
                "BACKGROUND_PLAYER",
                "Seek failed: $target",
                it
            )
        }
    }

    private fun next() {
        if (
            queue.isEmpty()
        ) {
            return
        }

        openTrack(
            index =
                currentIndex + 1,

            positionMs =
                0L,

            autoPlay =
                true
        )
    }

    private fun previous() {
        if (
            queue.isEmpty()
        ) {
            return
        }

        /*
         * 3 секундтан асып кетсе
         * previous = current track start.
         */
        val currentPosition =
            runCatching {
                player
                    ?.currentPosition
                    ?.toLong()
            }.getOrNull()
                ?: 0L

        if (
            currentPosition >
            3000L
        ) {
            seekTo(
                0L
            )

            return
        }

        openTrack(
            index =
                currentIndex - 1,

            positionMs =
                0L,

            autoPlay =
                true
        )
    }

    /*
     * ------------------------------------------------------------
     * ARTWORK
     * ------------------------------------------------------------
     */

    private fun loadArtwork(
        trackId: String
    ): Bitmap? {
        if (
            trackId.isBlank()
        ) {
            return null
        }

        val file =
            runCatching {
                metadataStore
                    .coverFileFor(
                        trackId
                    )
            }.getOrNull()
                ?: return null

        if (
            !file.isFile
        ) {
            return null
        }

        return try {
            val bounds =
                BitmapFactory
                    .Options()
                    .apply {
                        inJustDecodeBounds =
                            true
                    }

            BitmapFactory
                .decodeFile(
                    file.absolutePath,
                    bounds
                )

            val width =
                bounds.outWidth

            val height =
                bounds.outHeight

            if (
                width <= 0 ||
                height <= 0
            ) {
                return null
            }

            var sample =
                1

            val largest =
                maxOf(
                    width,
                    height
                )

            while (
                largest /
                    sample >
                512
            ) {
                sample *=
                    2
            }

            BitmapFactory
                .decodeFile(
                    file.absolutePath,

                    BitmapFactory
                        .Options()
                        .apply {
                            inSampleSize =
                                sample

                            inPreferredConfig =
                                Bitmap.Config
                                    .RGB_565
                        }
                )

        } catch (
            error: Throwable
        ) {
            GwRuntimeLog.warn(
                "BACKGROUND_ARTWORK",
                "Artwork decode failed: " +
                    trackId
            )

            null
        }
    }

    private fun recycleArtwork() {
        val artwork =
            currentArtwork

        currentArtwork =
            null

        if (
            artwork != null &&
            !artwork.isRecycled
        ) {
            runCatching {
                artwork.recycle()
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * MEDIA SESSION METADATA
     * ------------------------------------------------------------
     */

    private fun publishMetadata(
        track: Track
    ) {
        val builder =
            MediaMetadata
                .Builder()
                .putString(
                    MediaMetadata
                        .METADATA_KEY_TITLE,
                    track.title
                )
                .putString(
                    MediaMetadata
                        .METADATA_KEY_DISPLAY_TITLE,
                    track.title
                )
                .putString(
                    MediaMetadata
                        .METADATA_KEY_ARTIST,
                    track.artist
                )
                .putString(
                    MediaMetadata
                        .METADATA_KEY_DISPLAY_SUBTITLE,
                    track.artist
                )

        val artwork =
            currentArtwork

        if (
            artwork != null &&
            !artwork.isRecycled
        ) {
            builder
                .putBitmap(
                    MediaMetadata
                        .METADATA_KEY_DISPLAY_ICON,
                    artwork
                )
                .putBitmap(
                    MediaMetadata
                        .METADATA_KEY_ART,
                    artwork
                )
                .putBitmap(
                    MediaMetadata
                        .METADATA_KEY_ALBUM_ART,
                    artwork
                )
        }

        mediaSession
            .setMetadata(
                builder.build()
            )
    }

    /*
     * ------------------------------------------------------------
     * PLAYBACK STATE
     * ------------------------------------------------------------
     */

    private fun publishState(
        updateNotification: Boolean
    ) {
        val activePlayer =
            player

        val isPlaying =
            runCatching {
                activePlayer
                    ?.isPlaying ==
                    true
            }.getOrDefault(
                false
            )

        val position =
            runCatching {
                activePlayer
                    ?.currentPosition
                    ?.toLong()
            }.getOrNull()
                ?: 0L

        val duration =
            runCatching {
                activePlayer
                    ?.duration
                    ?.toLong()
            }.getOrNull()
                ?.takeIf {
                    it > 0L
                }
                ?: 0L

        val track =
            queue.getOrNull(
                currentIndex
            )

        GwPlaybackState.update(
            context =
                applicationContext,

            isActive =
                activePlayer != null,

            id =
                track?.id
                    .orEmpty(),

            position =
                position,

            duration =
                duration,

            isPlaying =
                isPlaying,

            trackTitle =
                track?.title
                    .orEmpty(),

            trackArtist =
                track?.artist
                    .orEmpty()
        )

        val state =
            when {
                isPlaying ->
                    PlaybackState
                        .STATE_PLAYING

                prepared ->
                    PlaybackState
                        .STATE_PAUSED

                activePlayer != null ->
                    PlaybackState
                        .STATE_BUFFERING

                else ->
                    PlaybackState
                        .STATE_NONE
            }

        mediaSession
            .setPlaybackState(
                PlaybackState
                    .Builder()
                    .setActions(
                        PlaybackState
                            .ACTION_PLAY or

                            PlaybackState
                                .ACTION_PAUSE or

                            PlaybackState
                                .ACTION_PLAY_PAUSE or

                            PlaybackState
                                .ACTION_SKIP_TO_PREVIOUS or

                            PlaybackState
                                .ACTION_SKIP_TO_NEXT or

                            PlaybackState
                                .ACTION_SEEK_TO or

                            PlaybackState
                                .ACTION_STOP
                    )
                    .setState(
                        state,
                        position,
                        if (
                            isPlaying
                        ) {
                            1f
                        } else {
                            0f
                        },
                        SystemClock
                            .elapsedRealtime()
                    )
                    .build()
            )

        if (
            updateNotification
        ) {
            ensureForeground()
        }
    }

    /*
     * ------------------------------------------------------------
     * NOTIFICATION
     * ------------------------------------------------------------
     */

    private fun serviceIntent(
        action: String,
        requestCode: Int
    ): PendingIntent {
        return PendingIntent
            .getService(
                this,
                requestCode,

                Intent(
                    this,
                    GwPlaybackService::class.java
                ).setAction(
                    action
                ),

                PendingIntent
                    .FLAG_UPDATE_CURRENT or
                    PendingIntent
                        .FLAG_IMMUTABLE
            )
    }

    private fun buildNotification():
        Notification {

        val track =
            queue.getOrNull(
                currentIndex
            )

        val isPlaying =
            runCatching {
                player?.isPlaying ==
                    true
            }.getOrDefault(
                false
            )

        val openApp =
            PendingIntent
                .getActivity(
                    this,
                    40,

                    Intent(
                        this,
                        MainActivity::class.java
                    ).addFlags(
                        Intent
                            .FLAG_ACTIVITY_SINGLE_TOP or
                            Intent
                                .FLAG_ACTIVITY_CLEAR_TOP
                    ),

                    PendingIntent
                        .FLAG_UPDATE_CURRENT or
                        PendingIntent
                            .FLAG_IMMUTABLE
                )

        val previousAction =
            Notification
                .Action
                .Builder(
                    android.R.drawable
                        .ic_media_previous,
                    "Алдыңғы",
                    serviceIntent(
                        ACTION_PREVIOUS,
                        41
                    )
                )
                .build()

        val playPauseAction =
            if (
                isPlaying
            ) {
                Notification
                    .Action
                    .Builder(
                        android.R.drawable
                            .ic_media_pause,
                        "Пауза",
                        serviceIntent(
                            ACTION_PAUSE,
                            42
                        )
                    )
                    .build()
            } else {
                Notification
                    .Action
                    .Builder(
                        android.R.drawable
                            .ic_media_play,
                        "Ойнату",
                        serviceIntent(
                            ACTION_PLAY,
                            42
                        )
                    )
                    .build()
            }

        val nextAction =
            Notification
                .Action
                .Builder(
                    android.R.drawable
                        .ic_media_next,
                    "Келесі",
                    serviceIntent(
                        ACTION_NEXT,
                        43
                    )
                )
                .build()

        val builder =
            Notification
                .Builder(
                    this,
                    CHANNEL_ID
                )
                .setSmallIcon(
                    android.R.drawable
                        .ic_media_play
                )
                .setContentTitle(
                    track?.title
                        ?: "GLASS WAVE"
                )
                .setContentText(
                    track?.artist
                        ?: "Музыка"
                )
                .setContentIntent(
                    openApp
                )
                .setDeleteIntent(
                    serviceIntent(
                        ACTION_STOP,
                        45
                    )
                )
                .setVisibility(
                    Notification
                        .VISIBILITY_PUBLIC
                )
                .setCategory(
                    Notification
                        .CATEGORY_TRANSPORT
                )
                .setOnlyAlertOnce(
                    true
                )
                .setOngoing(
                    isPlaying
                )
                .addAction(
                    previousAction
                )
                .addAction(
                    playPauseAction
                )
                .addAction(
                    nextAction
                )
                .setStyle(
                    Notification
                        .MediaStyle()
                        .setMediaSession(
                            mediaSession
                                .sessionToken
                        )
                        .setShowActionsInCompactView(
                            0,
                            1,
                            2
                        )
                )

        val artwork =
            currentArtwork

        if (
            artwork != null &&
            !artwork.isRecycled
        ) {
            builder.setLargeIcon(
                artwork
            )
        }

        return builder.build()
    }

    private fun ensureForeground() {
        val notification =
            buildNotification()

        if (
            !foregroundStarted
        ) {
            startForeground(
                NOTIFICATION_ID,
                notification
            )

            foregroundStarted =
                true
        } else {
            getSystemService(
                NotificationManager::class.java
            ).notify(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun createChannel() {
        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "GLASS WAVE media",
                NotificationManager
                    .IMPORTANCE_LOW
            ).apply {

                description =
                    "GLASS WAVE playback controls"

                setSound(
                    null,
                    null
                )

                enableVibration(
                    false
                )

                setShowBadge(
                    false
                )
            }

        manager
            .createNotificationChannel(
                channel
            )
    }

    /*
     * ------------------------------------------------------------
     * STOP
     * ------------------------------------------------------------
     */

    private fun stopPlayback() {
        handler
            .removeCallbacks(
                ticker
            )

        pendingAutoPlay =
            false

        resumeAfterFocus =
            false

        prepared =
            false

        runCatching {
            if (
                player?.isPlaying ==
                true
            ) {
                player?.stop()
            }
        }

        runCatching {
            player?.reset()
        }

        runCatching {
            player?.release()
        }

        player =
            null

        runCatching {
            audioManager
                .abandonAudioFocusRequest(
                    focusRequest
                )
        }

        GwPlaybackState.update(
            context =
                applicationContext,

            isActive =
                false,

            id =
                "",

            position =
                0L,

            duration =
                0L,

            isPlaying =
                false,

            trackTitle =
                "",

            trackArtist =
                ""
        )

        runCatching {
            mediaSession
                .setPlaybackState(
                    PlaybackState
                        .Builder()
                        .setState(
                            PlaybackState
                                .STATE_STOPPED,
                            0L,
                            0f
                        )
                        .build()
                )
        }

        runCatching {
            mediaSession
                .isActive =
                false
        }

        recycleArtwork()

        if (
            foregroundStarted
        ) {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

            foregroundStarted =
                false
        }

        stopSelf()
    }

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {
        /*
         * Әдейі stopSelf() жоқ.
         *
         * Recent apps ішінен
         * GLASS WAVE-ты алып тастаса да
         * foreground media playback
         * жалғасуы керек.
         */
        GwRuntimeLog.info(
            "BACKGROUND_PLAYER",
            "Task removed; playback service retained"
        )
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null

    override fun onDestroy() {
        handler
            .removeCallbacksAndMessages(
                null
            )

        runCatching {
            if (
                player?.isPlaying ==
                true
            ) {
                player?.stop()
            }
        }

        runCatching {
            player?.release()
        }

        player =
            null

        runCatching {
            audioManager
                .abandonAudioFocusRequest(
                    focusRequest
                )
        }

        runCatching {
            mediaSession
                .release()
        }

        recycleArtwork()

        super.onDestroy()
    }
}
