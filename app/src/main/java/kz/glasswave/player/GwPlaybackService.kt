package kz.glasswave.player

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject

object GwPlaybackState {
    @Volatile var active = false
    @Volatile var trackId = ""
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var isPlaying = false
    @Volatile var title = ""
    @Volatile var artist = ""

    @Synchronized
    fun json(): String = JSONObject()
        .put("active", active)
        .put("trackId", trackId)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("isPlaying", isPlaying)
        .put("title", title)
        .put("artist", artist)
        .toString()
}

class GwPlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_PLAY_LIBRARY = "kz.glasswave.player.PLAY_LIBRARY"
        const val ACTION_STOP_SERVICE = "kz.glasswave.player.STOP_PLAYBACK"
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_POSITION_MS = "position_ms"
    }

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private val stateTicker = object : Runnable {
        override fun run() {
            updateState()
            if (::player.isInitialized && player.isPlaying) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
            setAudioAttributes(attrs, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    updateState()
                    handler.removeCallbacks(stateTicker)
                    if (player.isPlaying) handler.post(stateTicker)
                }
            })
        }
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY_LIBRARY -> loadLibrary(
                intent.getStringExtra(EXTRA_TRACK_ID).orEmpty(),
                intent.getLongExtra(EXTRA_POSITION_MS, 0L)
            )
            ACTION_STOP_SERVICE -> {
                player.pause()
                player.clearMediaItems()
                updateState()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun loadLibrary(requestedId: String, positionMs: Long) {
        val source = JSONArray(GwLibraryStore(applicationContext).asJson())
        val items = ArrayList<MediaItem>()
        var selected = 0
        for (i in 0 until source.length()) {
            val t = source.getJSONObject(i)
            val id = t.optString("id")
            if (id == requestedId) selected = i
            val metadata = MediaMetadata.Builder()
                .setTitle(t.optString("title", t.optString("name", "Аудиотрек")))
                .setArtist(t.optString("artist", "Неизвестный исполнитель"))
                .setAlbumTitle(t.optString("album", ""))
                .build()
            items += MediaItem.Builder()
                .setMediaId(id)
                .setUri(t.getString("uri"))
                .setMediaMetadata(metadata)
                .build()
        }
        if (items.isEmpty()) return
        player.setMediaItems(items, selected.coerceIn(0, items.lastIndex), positionMs.coerceAtLeast(0L))
        player.prepare()
        player.play()
        updateState()
    }

    private fun updateState() {
        val item = player.currentMediaItem
        GwPlaybackState.active = player.mediaItemCount > 0
        GwPlaybackState.trackId = item?.mediaId.orEmpty()
        GwPlaybackState.positionMs = player.currentPosition.coerceAtLeast(0L)
        GwPlaybackState.durationMs = player.duration.takeIf { it > 0 } ?: 0L
        GwPlaybackState.isPlaying = player.isPlaying
        GwPlaybackState.title = item?.mediaMetadata?.title?.toString().orEmpty()
        GwPlaybackState.artist = item?.mediaMetadata?.artist?.toString().orEmpty()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        GwPlaybackState.active = false
        GwPlaybackState.isPlaying = false
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }
}
