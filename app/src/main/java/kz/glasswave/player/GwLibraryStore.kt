package kz.glasswave.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class GwLibraryStore(context: Context) {

    data class Track(
        val id: String,
        val uri: String,
        val name: String,
        val title: String,
        val artist: String,
        val album: String,
        val mime: String,
        val size: Long,
        val durationMs: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("uri", uri)
            .put("name", name)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("mime", mime)
            .put("size", size)
            .put("durationMs", durationMs)
    }

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val atomicFile = AtomicFile(
        File(appContext.filesDir, "gw-library.json")
    )

    private val tracks = mutableListOf<Track>()

    init {
        load()
    }

    @Synchronized
    fun asJson(): String =
        JSONArray(tracks.map { it.toJson() }).toString()

    @Synchronized
    fun find(id: String): Track? =
        tracks.firstOrNull { it.id == id }

    @Synchronized
    fun importBasics(uris: List<Uri>): List<String> {
        val added = mutableListOf<String>()

        uris.distinctBy { it.toString() }.forEach { uri ->
            if (tracks.any { it.uri == uri.toString() }) return@forEach

            var displayName = uri.lastPathSegment ?: "Аудиотрек"
            var size = -1L

            runCatching {
                resolver.query(
                    uri,
                    arrayOf(
                        OpenableColumns.DISPLAY_NAME,
                        OpenableColumns.SIZE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        ).takeIf { it >= 0 }?.let {
                            displayName = cursor.getString(it)
                                ?: displayName
                        }

                        cursor.getColumnIndex(
                            OpenableColumns.SIZE
                        ).takeIf { it >= 0 }?.let {
                            if (!cursor.isNull(it)) {
                                size = cursor.getLong(it)
                            }
                        }
                    }
                }
            }.onFailure {
                GwRuntimeLog.warn(
                    "LIBRARY",
                    "Basic metadata failed: $uri"
                )
            }

            val id = UUID.randomUUID().toString()
            val fallbackTitle = displayName
                .substringBeforeLast('.', displayName)

            tracks += Track(
                id = id,
                uri = uri.toString(),
                name = displayName,
                title = fallbackTitle,
                artist = "Неизвестный исполнитель",
                album = "",
                mime = resolver.getType(uri) ?: "audio/*",
                size = size,
                durationMs = 0L
            )

            added += id
        }

        save()
        return added
    }

    @Synchronized
    fun enrich(id: String): Boolean {
        val index = tracks.indexOfFirst { it.id == id }
        if (index < 0) return false

        val old = tracks[index]
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(
                appContext,
                Uri.parse(old.uri)
            )

            fun meta(key: Int): String? =
                retriever.extractMetadata(key)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            tracks[index] = old.copy(
                title = meta(
                    MediaMetadataRetriever.METADATA_KEY_TITLE
                ) ?: old.title,
                artist = meta(
                    MediaMetadataRetriever.METADATA_KEY_ARTIST
                ) ?: old.artist,
                album = meta(
                    MediaMetadataRetriever.METADATA_KEY_ALBUM
                ) ?: old.album,
                durationMs = meta(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: old.durationMs
            )

            save()
            true
        } catch (error: Throwable) {
            GwRuntimeLog.warn(
                "LIBRARY",
                "Extended metadata failed: ${old.name}"
            )
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val changed = tracks.removeAll { it.id == id }
        if (changed) save()
        return changed
    }

    @Synchronized
    fun clear() {
        tracks.clear()
        save()
    }

    @Synchronized
    private fun load() {
        tracks.clear()
        if (!atomicFile.baseFile.isFile) return

        runCatching {
            val text = atomicFile.openRead()
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val array = JSONArray(text)

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                tracks += Track(
                    id = item.getString("id"),
                    uri = item.getString("uri"),
                    name = item.optString("name", "Аудиотрек"),
                    title = item.optString("title", "Аудиотрек"),
                    artist = item.optString(
                        "artist",
                        "Неизвестный исполнитель"
                    ),
                    album = item.optString("album", ""),
                    mime = item.optString("mime", "audio/*"),
                    size = item.optLong("size", -1L),
                    durationMs = item.optLong("durationMs", 0L)
                )
            }
        }.onFailure {
            GwRuntimeLog.error(
                "LIBRARY",
                "Library index load failed",
                it
            )
        }
    }

    @Synchronized
    private fun save() {
        var output: java.io.FileOutputStream? = null

        try {
            output = atomicFile.startWrite()
            output.write(
                JSONArray(tracks.map { it.toJson() })
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            )
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let { atomicFile.failWrite(it) }
            GwRuntimeLog.error(
                "LIBRARY",
                "Library index save failed",
                error
            )
        }
    }
}
