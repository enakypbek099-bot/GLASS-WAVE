package kz.glasswave.player

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class GwLibraryStore(context: Context) {
    data class Track(
        val id: String,
        val uri: String,
        var name: String,
        var title: String,
        var artist: String,
        var album: String,
        var mime: String,
        var size: Long,
        var durationMs: Long,
        val addedAt: Long
    )

    private val file = AtomicFile(context.filesDir.resolve("gw-library.json"))
    private val tracks = mutableListOf<Track>()

    init { load() }

    @Synchronized
    fun importBasics(resolver: ContentResolver, uris: List<Uri>): Int {
        var changed = 0
        uris.distinctBy(Uri::toString).forEach { uri ->
            val info = basicInfo(resolver, uri)
            val existing = tracks.firstOrNull { it.uri == uri.toString() }
            if (existing == null) {
                tracks += Track(
                    id = UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    name = info.name,
                    title = info.name.substringBeforeLast('.', info.name),
                    artist = "Неизвестный исполнитель",
                    album = "",
                    mime = info.mime,
                    size = info.size,
                    durationMs = 0L,
                    addedAt = System.currentTimeMillis()
                )
                changed++
            } else {
                existing.name = info.name
                existing.mime = info.mime
                existing.size = info.size
            }
        }
        if (changed > 0) save()
        return changed
    }

    fun enrich(resolver: ContentResolver, uri: Uri) {
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: return
        val metadata = descriptor.use { afd ->
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(afd.fileDescriptor)
                    Metadata(
                        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    )
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        } ?: return

        synchronized(this) {
            val track = tracks.firstOrNull { it.uri == uri.toString() } ?: return
            metadata.title?.takeIf(String::isNotBlank)?.let { track.title = it }
            metadata.artist?.takeIf(String::isNotBlank)?.let { track.artist = it }
            metadata.album?.takeIf(String::isNotBlank)?.let { track.album = it }
            if (metadata.durationMs > 0) track.durationMs = metadata.durationMs
            save()
        }
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val removed = tracks.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    @Synchronized
    fun clear() {
        tracks.clear()
        save()
    }

    @Synchronized
    fun asJson(): String {
        val array = JSONArray()
        tracks.sortedBy { it.addedAt }.forEach { array.put(toJson(it)) }
        return JSONObject().put("ok", true).put("tracks", array).toString()
    }

    private data class BasicInfo(val name: String, val mime: String, val size: Long)
    private data class Metadata(
        val title: String?, val artist: String?, val album: String?, val durationMs: Long
    )

    private fun basicInfo(resolver: ContentResolver, uri: Uri): BasicInfo {
        var name = uri.lastPathSegment ?: "Аудиофайл"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it) ?: name }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.let { size = if (cursor.isNull(it)) 0L else cursor.getLong(it) }
                }
            }
        return BasicInfo(name, resolver.getType(uri) ?: "audio/*", size)
    }

    private fun toJson(track: Track) = JSONObject()
        .put("id", track.id)
        .put("uri", track.uri)
        .put("name", track.name)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album)
        .put("mime", track.mime)
        .put("size", track.size)
        .put("durationMs", track.durationMs)
        .put("addedAt", track.addedAt)

    @Synchronized
    private fun load() {
        tracks.clear()
        val text = runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull()
            ?: return
        runCatching {
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                tracks += Track(
                    id = item.getString("id"),
                    uri = item.getString("uri"),
                    name = item.optString("name", "Аудиофайл"),
                    title = item.optString("title", "Аудиофайл"),
                    artist = item.optString("artist", "Неизвестный исполнитель"),
                    album = item.optString("album", ""),
                    mime = item.optString("mime", "audio/*"),
                    size = item.optLong("size", 0L),
                    durationMs = item.optLong("durationMs", 0L),
                    addedAt = item.optLong("addedAt", 0L)
                )
            }
        }.onFailure { GwRuntimeLog.error("LIBRARY", "Library index load failed", it) }
    }

    @Synchronized
    private fun save() {
        val array = JSONArray()
        tracks.forEach { array.put(toJson(it)) }
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(file::failWrite)
            GwRuntimeLog.error("LIBRARY", "Library index save failed", error)
        }
    }
}
