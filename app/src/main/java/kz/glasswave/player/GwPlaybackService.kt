package kz.glasswave.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
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

    @Volatile var active = false
    @Volatile var trackId = ""
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var playing = false
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var heartbeat = 0L
    @Volatile var engine = ""
    @Volatile var qualityError = ""
    @Volatile var nativeStatus = ""

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

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
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
    fun json(
        context: Context
    ): String {
        val prefs =
            context.getSharedPreferences(
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

        val resultActive =
            active ||
                (
                    prefs.getBoolean(
                        "active",
                        false
                    ) &&
                        live
                    )

        val resultPlaying =
            if (active) {
                playing
            } else {
                prefs.getBoolean(
                    "playing",
                    false
                ) && live
            }

        val result =
            JSONObject()
                .put(
                    "active",
                    resultActive
                )
                .put(
                    "trackId",
                    trackId.ifBlank {
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
                .put(
                    "engine",
                    engine
                )
                .put(
                    "qualityError",
                    qualityError
                )

        result.put(
            "native",
            runCatching {
                JSONObject(
                    nativeStatus
                )
            }.getOrNull()
                ?: JSONObject()
        )

        return result.toString()
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

        const val ACTION_SEEK =
            "kz.glasswave.player.background.SEEK"

        const val EXTRA_QUALITY =
            "quality"

        const val EXTRA_AUTOPLAY =
            "autoPlay"

        const val EXTRA_DEVICE_ID =
            "deviceId"

        const val EXTRA_EXCLUSIVE =
            "exclusive"

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
        val name: String,
        val mime: String,
        val title: String,
        val artist: String
    ) {
        fun isWav(): Boolean {
            return mime.contains(
                "wav",
                ignoreCase = true
            ) ||
                name.endsWith(
                    ".wav",
                    ignoreCase = true
                )
        }
    }

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

    private var qualityMode =
        false

    private var qualityLoaded =
        false

    private var qualityDeviceId =
        -1

    private var qualityExclusive =
        true

    private var qualityAutoPlay =
        true

    private var qualityEndedHandled =
        false

    private var nativeCache =
        JSONObject()

    private var wakeLock:
        PowerManager.WakeLock? =
        null

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
                if (
                    qualityMode
                ) {
                    pollQuality()
                }

                publishState(
                    updateNotification =
                        false
                )

                if (
                    hasEngine()
                ) {
                    handler.postDelayed(
                        this,
                        if (
                            qualityMode
                        ) {
                            500L
                        } else {
                            1000L
                        }
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
    }

    private fun startTicker() {
        handler.removeCallbacks(
            ticker
        )

        handler.post(
            ticker
        )
    }

    private fun handleAudioFocusChange(
        change: Int
    ) {
        when (
            change
        ) {
            AudioManager
                .AUDIOFOCUS_GAIN -> {

                if (
                    !qualityMode
                ) {
                    runCatching {
                        player?.setVolume(
                            1f,
                            1f
                        )
                    }
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
                    isPlayingNow()

                pause()
            }

            AudioManager
                .AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                if (
                    qualityMode
                ) {
                    /*
                     * Native volume DSP бұл
                     * кезеңде жоқ.
                     * Қауіпсіз әрекет — pause.
                     */
                    resumeAfterFocus =
                        isPlayingNow()

                    pause()
                } else {
                    runCatching {
                        player?.setVolume(
                            0.25f,
                            0.25f
                        )
                    }
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
                ensureForeground()

                qualityMode =
                    intent.getBooleanExtra(
                        EXTRA_QUALITY,
                        false
                    )

                qualityDeviceId =
                    intent.getIntExtra(
                        EXTRA_DEVICE_ID,
                        -1
                    )

                qualityExclusive =
                    intent.getBooleanExtra(
                        EXTRA_EXCLUSIVE,
                        true
                    )

                qualityAutoPlay =
                    intent.getBooleanExtra(
                        EXTRA_AUTOPLAY,
                        true
                    )

                loadQueue(
                    selectedId =
                        intent
                            .getStringExtra(
                                EXTRA_TRACK_ID
                            )
                            .orEmpty(),

                    positionMs =
                        intent.getLongExtra(
                            EXTRA_POSITION_MS,
                            0L
                        )
                )
            }

            ACTION_SEEK -> {
                seekTo(
                    intent.getLongExtra(
                        EXTRA_POSITION_MS,
                        0L
                    )
                )
            }

            ACTION_PLAY ->
                play()

            ACTION_PAUSE ->
                pause()

            ACTION_PREVIOUS ->
                previous()

            ACTION_NEXT ->
                next()

            ACTION_STOP ->
                stopPlayback()

            else -> {
                /*
                 * START_NOT_STICKY:
                 * process restore кезінде
                 * бос notification жасамаймыз.
                 */
                stopSelf(
                    startId
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun loadQueue(
        selectedId: String,
        positionMs: Long
    ) {
        val source =
            runCatching {
                JSONArray(
                    libraryStore.asJson()
                )
            }.getOrElse {
                GwRuntimeLog.error(
                    "BACKGROUND_PLAYER",
                    "Library JSON failed",
                    it
                )

                JSONArray()
            }

        val allTracks =
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

            allTracks.add(
                Track(
                    id = id,
                    uri = uri,
                    name =
                        item.optString(
                            "name"
                        ),
                    mime =
                        item.optString(
                            "mime"
                        ),
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
            if (
                qualityMode
            ) {
                allTracks.filter {
                    it.isWav()
                }
            } else {
                allTracks
            }

        if (
            queue.isEmpty()
        ) {
            if (
                qualityMode
            ) {
                GwPlaybackState
                    .qualityError =
                    "QUALITY_WAV_QUEUE_EMPTY"
            }

            stopPlayback()

            return
        }

        currentIndex =
            queue.indexOfFirst {
                it.id ==
                    selectedId
            }

        if (
            currentIndex < 0
        ) {
            if (
                qualityMode
            ) {
                GwPlaybackState
                    .qualityError =
                    "QUALITY_TRACK_NOT_WAV"

                stopPlayback()

                return
            }

            currentIndex =
                0
        }

        openTrack(
            index =
                currentIndex,
            positionMs =
                positionMs,
            autoPlay =
                if (
                    qualityMode
                ) {
                    qualityAutoPlay
                } else {
                    true
                }
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

        if (
            qualityMode
        ) {
            openQualityTrack(
                track,
                positionMs,
                autoPlay
            )

            return
        }

        closeQualityEngine()

        GwPlaybackState.engine =
            "MEDIA_PLAYER"

        GwPlaybackState.qualityError =
            ""

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

        clearArtworkReference()

        currentArtwork =
            loadArtwork(
                track.id
            )

        publishMetadata(
            track
        )

        ensureForeground()
        startTicker()

        val newPlayer =
            MediaPlayer()

        player =
            newPlayer

        try {
            newPlayer.setAudioAttributes(
                audioAttributes
            )

            newPlayer.setWakeMode(
                applicationContext,
                PowerManager
                    .PARTIAL_WAKE_LOCK
            )

            newPlayer.setDataSource(
                applicationContext,
                Uri.parse(
                    track.uri
                )
            )

            newPlayer.setOnPreparedListener {
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
                        true
                    )
                }
            }

            newPlayer.setOnSeekCompleteListener {
                publishState(
                    true
                )
            }

            newPlayer.setOnCompletionListener {
                next()
            }

            newPlayer.setOnErrorListener {
                    _,
                    what,
                    extra ->

                GwRuntimeLog.error(
                    "BACKGROUND_PLAYER",
                    "MediaPlayer error " +
                        "what=$what extra=$extra"
                )

                prepared =
                    false

                pendingAutoPlay =
                    false

                publishState(
                    true
                )

                true
            }

            newPlayer.prepareAsync()

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
                true
            )
        }
    }

    private fun hasEngine():
        Boolean =
        if (
            qualityMode
        ) {
            qualityLoaded
        } else {
            player != null
        }

    private fun isPlayingNow():
        Boolean =
        if (
            qualityMode
        ) {
            qualityLoaded &&
                nativeCache
                    .optString(
                        "state"
                    ) ==
                "STARTED"
        } else {
            runCatching {
                player?.isPlaying ==
                    true
            }.getOrDefault(
                false
            )
        }

    private fun positionNow():
        Long =
        if (
            qualityMode
        ) {
            nativeCache.optLong(
                "positionMs",
                0L
            )
        } else {
            runCatching {
                player
                    ?.currentPosition
                    ?.toLong()
            }.getOrNull()
                ?: 0L
        }

    private fun durationNow():
        Long =
        if (
            qualityMode
        ) {
            nativeCache.optLong(
                "durationMs",
                0L
            )
        } else {
            runCatching {
                player
                    ?.duration
                    ?.toLong()
            }.getOrNull()
                ?.takeIf {
                    it > 0L
                }
                ?: 0L
        }

    private fun applyNative(
        json: String
    ) {
        nativeCache =
            runCatching {
                JSONObject(
                    json
                )
            }.getOrDefault(
                JSONObject()
            )

        GwPlaybackState.nativeStatus =
            nativeCache.toString()
    }

    private fun refreshNative() {
        if (
            qualityLoaded
        ) {
            applyNative(
                GwNativeAudio
                    .statusSafely()
            )
        }
    }

    private fun pollQuality() {
        refreshNative()

        if (
            qualityLoaded &&
            !qualityEndedHandled &&
            nativeCache.optString(
                "state"
            ) ==
            "ENDED"
        ) {
            qualityEndedHandled =
                true

            releaseWakeLock()

            next()
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            if (
                wakeLock == null
            ) {
                val manager =
                    getSystemService(
                        POWER_SERVICE
                    ) as PowerManager

                wakeLock =
                    manager.newWakeLock(
                        PowerManager
                            .PARTIAL_WAKE_LOCK,
                        "GlassWave:GwNative"
                    ).apply {
                        setReferenceCounted(
                            false
                        )
                    }
            }

            if (
                wakeLock?.isHeld !=
                true
            ) {
                wakeLock?.acquire(
                    6L *
                        60L *
                        60L *
                        1000L
                )
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (
                wakeLock?.isHeld ==
                true
            ) {
                wakeLock?.release()
            }
        }
    }

    private fun closeQualityEngine() {
        releaseWakeLock()

        if (
            qualityLoaded
        ) {
            GwNativeAudio
                .closeSafely()
        }

        qualityLoaded =
            false

        nativeCache =
            JSONObject()

        GwPlaybackState.nativeStatus =
            ""
    }

    private fun failQuality(
        code: String
    ) {
        GwRuntimeLog.warn(
            "GW_NATIVE",
            "QUALITY open failed: $code"
        )

        GwNativeAudio
            .closeSafely()

        qualityLoaded =
            false

        prepared =
            false

        pendingAutoPlay =
            false

        GwPlaybackState.qualityError =
            code

        publishState(
            true
        )
    }

    private fun openQualityTrack(
        track: Track,
        positionMs: Long,
        autoPlay: Boolean
    ) {
        runCatching {
            player?.reset()
        }

        runCatching {
            player?.release()
        }

        player =
            null

        closeQualityEngine()

        prepared =
            false

        pendingAutoPlay =
            false

        qualityEndedHandled =
            false

        GwPlaybackState.engine =
            "GW_NATIVE"

        GwPlaybackState.qualityError =
            ""

        clearArtworkReference()

        currentArtwork =
            loadArtwork(
                track.id
            )

        publishMetadata(
            track
        )

        ensureForeground()
        startTicker()

        if (
            !track.isWav()
        ) {
            failQuality(
                "QUALITY_TRACK_NOT_WAV"
            )

            return
        }

        if (
            !GwNativeAudio.isLoaded()
        ) {
            failQuality(
                "GW_NATIVE_NOT_LOADED"
            )

            return
        }

        var descriptor:
            AssetFileDescriptor? =
            null

        try {
            descriptor =
                contentResolver
                    .openAssetFileDescriptor(
                        Uri.parse(
                            track.uri
                        ),
                        "r"
                    )

            if (
                descriptor == null
            ) {
                failQuality(
                    "FILE_OPEN_FAILED"
                )

                return
            }

            val result =
                GwNativeAudio
                    .nativeLoadWav(
                        descriptor
                            .parcelFileDescriptor
                            .fd,
                        descriptor
                            .startOffset,
                        descriptor
                            .length,
                        qualityDeviceId,
                        qualityExclusive,
                        positionMs
                            .coerceAtLeast(
                                0L
                            ),
                        false
                    )

            val json =
                JSONObject(
                    result
                )

            val error =
                json.optString(
                    "error"
                )

            if (
                !json.optBoolean(
                    "ok",
                    false
                ) ||
                error.isNotEmpty()
            ) {
                failQuality(
                    error.ifEmpty {
                        "NATIVE_OPEN_FAILED"
                    }
                )

                return
            }

            qualityLoaded =
                true

            prepared =
                true

            applyNative(
                result
            )

        } catch (
            error: Throwable
        ) {
            GwRuntimeLog.error(
                "GW_NATIVE",
                "QUALITY open crashed: " +
                    track.uri,
                error
            )

            failQuality(
                error.javaClass
                    .simpleName
            )

            return

        } finally {
            runCatching {
                descriptor?.close()
            }
        }

        if (
            autoPlay
        ) {
            play()
        } else {
            publishState(
                true
            )
        }
    }

    private fun playQuality() {
        if (
            !qualityLoaded
        ) {
            return
        }

        if (
            !requestAudioFocus()
        ) {
            pendingAutoPlay =
                true

            publishState(
                true
            )

            return
        }

        pendingAutoPlay =
            false

        qualityEndedHandled =
            false

        acquireWakeLock()

        val ended =
            nativeCache.optString(
                "state"
            ) ==
            "ENDED"

        val result =
            runCatching {
                if (
                    ended
                ) {
                    GwNativeAudio
                        .nativeSeek(
                            0L,
                            true
                        )
                } else {
                    GwNativeAudio
                        .nativeStart()
                }
            }.getOrElse {
                GwRuntimeLog.error(
                    "GW_NATIVE",
                    "Play failed",
                    it
                )

                ""
            }

        if (
            result.isNotEmpty()
        ) {
            applyNative(
                result
            )
        }

        publishState(
            true
        )
    }

    private fun pauseQuality() {
        pendingAutoPlay =
            false

        if (
            qualityLoaded
        ) {
            val result =
                runCatching {
                    /*
                     * nativeStop() енді
                     * requestPause() жасайды.
                     */
                    GwNativeAudio
                        .nativeStop()
                }.getOrElse {
                    GwRuntimeLog.error(
                        "GW_NATIVE",
                        "Pause failed",
                        it
                    )

                    ""
                }

            if (
                result.isNotEmpty()
            ) {
                applyNative(
                    result
                )
            }
        }

        releaseWakeLock()

        publishState(
            true
        )
    }

    private fun seekQuality(
        position: Long
    ) {
        if (
            !qualityLoaded
        ) {
            return
        }

        refreshNative()

        val duration =
            nativeCache.optLong(
                "durationMs",
                0L
            )

        val target =
            if (
                duration > 0L
            ) {
                position.coerceIn(
                    0L,
                    duration
                )
            } else {
                position.coerceAtLeast(
                    0L
                )
            }

        val resume =
            isPlayingNow()

        val result =
            runCatching {
                GwNativeAudio
                    .nativeSeek(
                        target,
                        resume
                    )
            }.getOrElse {
                GwRuntimeLog.error(
                    "GW_NATIVE",
                    "Seek failed: $target",
                    it
                )

                ""
            }

        qualityEndedHandled =
            false

        if (
            result.isNotEmpty()
        ) {
            applyNative(
                result
            )
        }

        publishState(
            true
        )
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
        if (
            qualityMode
        ) {
            playQuality()

            return
        }

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
                true
            )

            return
        }

        pendingAutoPlay =
            false

        runCatching {
            activePlayer.setVolume(
                1f,
                1f
            )

            if (
                !activePlayer.isPlaying
            ) {
                activePlayer.start()
            }
        }.onFailure {
            GwRuntimeLog.error(
                "BACKGROUND_PLAYER",
                "Play failed",
                it
            )
        }

        publishState(
            true
        )
    }

    private fun pause() {
        if (
            qualityMode
        ) {
            pauseQuality()

            return
        }

        pendingAutoPlay =
            false

        runCatching {
            if (
                player?.isPlaying ==
                true
            ) {
                player?.pause()
            }
        }

        publishState(
            true
        )
    }

    private fun seekTo(
        position: Long
    ) {
        if (
            qualityMode
        ) {
            seekQuality(
                position
            )

            return
        }

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
                position.coerceIn(
                    0L,
                    duration
                )
            } else {
                position.coerceAtLeast(
                    0L
                )
            }

        runCatching {
            activePlayer.seekTo(
                target,
                MediaPlayer
                    .SEEK_CLOSEST
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
            currentIndex + 1,
            0L,
            true
        )
    }

    private fun previous() {
        if (
            queue.isEmpty()
        ) {
            return
        }

        if (
            positionNow() >
            3000L
        ) {
            seekTo(
                0L
            )

            return
        }

        openTrack(
            currentIndex - 1,
            0L,
            true
        )
    }

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

            BitmapFactory.decodeFile(
                file.absolutePath,
                bounds
            )

            if (
                bounds.outWidth <= 0 ||
                bounds.outHeight <= 0
            ) {
                return null
            }

            var sample =
                1

            val largest =
                maxOf(
                    bounds.outWidth,
                    bounds.outHeight
                )

            while (
                largest /
                    sample >
                512
            ) {
                sample *=
                    2
            }

            BitmapFactory.decodeFile(
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
                "Artwork decode failed: $trackId"
            )

            null
        }
    }

    /*
     * Notification/MediaSession bitmap-ты
     * әлі пайдаланып тұруы мүмкін.
     * Сондықтан recycle() жасамаймыз.
     */
    private fun clearArtworkReference() {
        currentArtwork =
            null
    }

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

        currentArtwork
            ?.takeIf {
                !it.isRecycled
            }
            ?.let {
                builder
                    .putBitmap(
                        MediaMetadata
                            .METADATA_KEY_DISPLAY_ICON,
                        it
                    )
                    .putBitmap(
                        MediaMetadata
                            .METADATA_KEY_ART,
                        it
                    )
                    .putBitmap(
                        MediaMetadata
                            .METADATA_KEY_ALBUM_ART,
                        it
                    )
            }

        mediaSession.setMetadata(
            builder.build()
        )
    }

    private fun publishState(
        updateNotification: Boolean
    ) {
        if (
            qualityMode
        ) {
            refreshNative()
        }

        val engineOpen =
            hasEngine()

        val isPlaying =
            isPlayingNow()

        val position =
            positionNow()

        val duration =
            durationNow()

        val track =
            queue.getOrNull(
                currentIndex
            )

        GwPlaybackState.update(
            applicationContext,
            engineOpen,
            track?.id.orEmpty(),
            position,
            duration,
            isPlaying,
            track?.title.orEmpty(),
            track?.artist.orEmpty()
        )

        val state =
            when {
                isPlaying ->
                    PlaybackState
                        .STATE_PLAYING

                prepared ->
                    PlaybackState
                        .STATE_PAUSED

                engineOpen ->
                    PlaybackState
                        .STATE_BUFFERING

                else ->
                    PlaybackState
                        .STATE_NONE
            }

        mediaSession.setPlaybackState(
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

    private fun serviceIntent(
        action: String,
        requestCode: Int
    ): PendingIntent =
        PendingIntent.getService(
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

    private fun buildNotification():
        Notification {
        val track =
            queue.getOrNull(
                currentIndex
            )

        val isPlaying =
            isPlayingNow()

        val openApp =
            PendingIntent.getActivity(
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

        val previous =
            Notification.Action.Builder(
                android.R.drawable
                    .ic_media_previous,
                "Алдыңғы",
                serviceIntent(
                    ACTION_PREVIOUS,
                    41
                )
            ).build()

        val playPause =
            Notification.Action.Builder(
                if (
                    isPlaying
                ) {
                    android.R.drawable
                        .ic_media_pause
                } else {
                    android.R.drawable
                        .ic_media_play
                },
                if (
                    isPlaying
                ) {
                    "Пауза"
                } else {
                    "Ойнату"
                },
                serviceIntent(
                    if (
                        isPlaying
                    ) {
                        ACTION_PAUSE
                    } else {
                        ACTION_PLAY
                    },
                    42
                )
            ).build()

        val next =
            Notification.Action.Builder(
                android.R.drawable
                    .ic_media_next,
                "Келесі",
                serviceIntent(
                    ACTION_NEXT,
                    43
                )
            ).build()

        val builder =
            Notification.Builder(
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
                    previous
                )
                .addAction(
                    playPause
                )
                .addAction(
                    next
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

        currentArtwork
            ?.takeIf {
                !it.isRecycled
            }
            ?.let {
                builder.setLargeIcon(
                    it
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

        manager.createNotificationChannel(
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
        )
    }

    private fun stopPlayback() {
        handler.removeCallbacks(
            ticker
        )

        closeQualityEngine()

        pendingAutoPlay =
            false

        resumeAfterFocus =
            false

        prepared =
            false

        runCatching {
            player?.stop()
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

        GwPlaybackState.engine =
            ""

        GwPlaybackState.qualityError =
            ""

        GwPlaybackState.nativeStatus =
            ""

        GwPlaybackState.update(
            applicationContext,
            false,
            "",
            0L,
            0L,
            false,
            "",
            ""
        )

        runCatching {
            mediaSession.setPlaybackState(
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

        clearArtworkReference()

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
        handler.removeCallbacksAndMessages(
            null
        )

        closeQualityEngine()

        runCatching {
            player?.stop()
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
            mediaSession.release()
        }

        clearArtworkReference()

        super.onDestroy()
    }
}
