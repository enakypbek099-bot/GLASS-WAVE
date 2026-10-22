package kz.glasswave.player

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject

object GwPlaybackState {

    @Volatile
    var active = false

    @Volatile
    var trackId = ""

    @Volatile
    var positionMs = 0L

    @Volatile
    var durationMs = 0L

    @Volatile
    var isPlaying = false

    @Volatile
    var title = ""

    @Volatile
    var artist = ""

    @Synchronized
    fun json(): String =
        JSONObject()
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
        const val ACTION_PLAY_LIBRARY =
            "kz.glasswave.player.PLAY_LIBRARY"

        const val ACTION_STOP_SERVICE =
            "kz.glasswave.player.STOP_PLAYBACK"

        const val EXTRA_TRACK_ID =
            "track_id"

        const val EXTRA_POSITION_MS =
            "position_ms"
    }

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setWakeMode(
                androidx.media3.common.C.WAKE_MODE_LOCAL
            )
            .build()

        session = MediaSession.Builder(
            this,
            player
        ).build()

        player.addListener(
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events
                ) {
                    updateState()
                }
            }
        )
    }

    override fun onGetSession(
        controllerInfo:
            MediaSession.ControllerInfo
    ): MediaSession? = session

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        super.onStartCommand(
            intent,
            flags,
            startId
        )

        when (intent?.action) {
            ACTION_PLAY_LIBRARY -> {
                loadLibrary(
                    intent.getStringExtra(
                        EXTRA_TRACK_ID
                    ).orEmpty(),
                    intent.getLongExtra(
                        EXTRA_POSITION_MS,
                        0L
                    )
                )
            }

            ACTION_STOP_SERVICE -> {
                player.pause()
                GwPlaybackState.active = false
                GwPlaybackState.isPlaying = false
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun loadLibrary(
        requestedTrackId: String,
        positionMs: Long
    ) {
        val store =
            GwLibraryStore(applicationContext)

        val source =
            JSONArray(store.asJson())

        val items =
            ArrayList<MediaItem>()

        var selectedIndex = 0

        for (index in 0 until source.length()) {
            val track =
                source.getJSONObject(index)

            val id =
                track.optString("id")

            if (id == requestedTrackId) {
                selectedIndex = index
            }

            val metadata =
                MediaMetadata.Builder()
                    .setTitle(
                        track.optString(
                            "title",
                            track.optString(
                                "name",
                                "Аудиотрек"
                            )
                        )
                    )
                    .setArtist(
                        track.optString(
                            "artist",
                            "Неизвестный исполнитель"
                        )
                    )
                    .setAlbumTitle(
                        track.optString(
                            "album",
                            ""
                        )
                    )
                    .build()

            items += MediaItem.Builder()
                .setMediaId(id)
                .setUri(track.getString("uri"))
                .setMediaMetadata(metadata)
                .build()
        }

        if (items.isEmpty()) {
            return
        }

        player.setMediaItems(
            items,
            selectedIndex.coerceIn(
                0,
                items.lastIndex
            ),
            positionMs.coerceAtLeast(0L)
        )

        player.prepare()
        player.play()

        GwPlaybackState.active = true
        updateState()
    }

    private fun updateState() {
        val item = player.currentMediaItem

        GwPlaybackState.active =
            player.mediaItemCount > 0

        GwPlaybackState.trackId =
            item?.mediaId.orEmpty()

        GwPlaybackState.positionMs =
            player.currentPosition
                .coerceAtLeast(0L)

        GwPlaybackState.durationMs =
            player.duration
                .coerceAtLeast(0L)

        GwPlaybackState.isPlaying =
            player.isPlaying

        GwPlaybackState.title =
            item?.mediaMetadata
                ?.title
                ?.toString()
                .orEmpty()

        GwPlaybackState.artist =
            item?.mediaMetadata
                ?.artist
                ?.toString()
                .orEmpty()
    }

    override fun onDestroy() {
        GwPlaybackState.active = false
        GwPlaybackState.isPlaying = false

        session?.release()
        session = null

        player.release()

        super.onDestroy()
    }
}
