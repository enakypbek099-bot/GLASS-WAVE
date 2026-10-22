package kz.glasswave.player

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
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
    fun importUris(uris: List<Uri>): List<String> {
        val added = mutableListOf<String>()

        uris.distinctBy { it.toString() }.forEach { uri ->
            if (tracks.any { it.uri == uri.toString() }) {
                return@forEach
            }

            var name = uri.lastPathSegment ?: "Аудиотрек"
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
                            name = cursor.getString(it) ?: name
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
            }

            val id = UUID.randomUUID().toString()

            tracks += Track(
                id = id,
                uri = uri.toString(),
                name = name,
                title = name.substringBeforeLast('.', name),
                artist = "Неизвестный исполнитель",
                album = "",
                mime = mimeFor(name, resolver.getType(uri)),
                size = size,
                durationMs = 0L
            )

            added += id
        }

        save()
        return added
    }

    fun scanMediaStore(): Int {
        val found = mutableListOf<Track>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Audio.Media.DURATION
        )

        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media._ID
            )

            val nameColumn = cursor.getColumnIndex(
                MediaStore.MediaColumns.DISPLAY_NAME
            )

            val titleColumn = cursor.getColumnIndex(
                MediaStore.Audio.Media.TITLE
            )

            val artistColumn = cursor.getColumnIndex(
                MediaStore.Audio.Media.ARTIST
            )

            val albumColumn = cursor.getColumnIndex(
                MediaStore.Audio.Media.ALBUM
            )

            val mimeColumn = cursor.getColumnIndex(
                MediaStore.MediaColumns.MIME_TYPE
            )

            val sizeColumn = cursor.getColumnIndex(
                MediaStore.MediaColumns.SIZE
            )

            val durationColumn = cursor.getColumnIndex(
                MediaStore.Audio.Media.DURATION
            )

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)

                val name = cursor.stringOrNull(nameColumn)
                    ?: "Аудиотрек"

                val mime = cursor.stringOrNull(mimeColumn)

                if (!isSupportedAudio(name, mime)) {
                    continue
                }

                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    mediaId
                )

                found += Track(
                    id = UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    name = name,
                    title = cursor.stringOrNull(titleColumn)
                        ?: name.substringBeforeLast('.', name),
                    artist = cursor.stringOrNull(artistColumn)
                        ?: "Неизвестный исполнитель",
                    album = cursor.stringOrNull(albumColumn) ?: "",
                    mime = mimeFor(name, mime),
                    size = cursor.longOrDefault(sizeColumn, -1L),
                    durationMs = cursor.longOrDefault(
                        durationColumn,
                        0L
                    )
                )
            }
        }

        synchronized(this) {
            var added = 0

            found.forEach { track ->
                if (tracks.none { it.uri == track.uri }) {
                    tracks += track
                    added++
                }
            }

            if (added > 0) {
                save()
            }

            return added
        }
    }

    fun enrich(id: String): Boolean {
        val old = synchronized(this) {
            tracks.firstOrNull { it.id == id }
        } ?: return false

        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(
                appContext,
                Uri.parse(old.uri)
            )

            fun metadata(key: Int): String? =
                retriever.extractMetadata(key)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            val updated = old.copy(
                title = metadata(
                    MediaMetadataRetriever.METADATA_KEY_TITLE
                ) ?: old.title,
                artist = metadata(
                    MediaMetadataRetriever.METADATA_KEY_ARTIST
                ) ?: old.artist,
                album = metadata(
                    MediaMetadataRetriever.METADATA_KEY_ALBUM
                ) ?: old.album,
                durationMs = metadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: old.durationMs
            )

            synchronized(this) {
                val index = tracks.indexOfFirst { it.id == id }

                if (index >= 0) {
                    tracks[index] = updated
                    save()
                }
            }

            true
        } catch (error: Throwable) {
            GwRuntimeLog.warn(
                "LIBRARY",
                "Metadata оқу мүмкін болмады: ${old.name}"
            )
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val changed = tracks.removeAll { it.id == id }

        if (changed) {
            save()
        }

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

        if (!atomicFile.baseFile.isFile) {
            return
        }

        runCatching {
            val json = atomicFile.openRead()
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val array = JSONArray(json)

            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)

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
                    mime = item.optString("mime", "audio/mpeg"),
                    size = item.optLong("size", -1L),
                    durationMs = item.optLong("durationMs", 0L)
                )
            }
        }.onFailure {
            GwRuntimeLog.error(
                "LIBRARY",
                "Кітапхананы оқу қатесі",
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
                "Кітапхананы сақтау қатесі",
                error
            )
        }
    }

    private fun mimeFor(
        name: String,
        providerMime: String?
    ): String {
        val extension = name
            .substringAfterLast('.', "")
            .lowercase()

        return when (extension) {
            "flac" -> "audio/flac"
            "wav", "wave" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "m4b", "mp4", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "aif", "aiff" -> "audio/aiff"
            "ape" -> "audio/ape"
            "wv" -> "audio/wavpack"
            else -> providerMime
                ?.takeUnless { it == "audio/*" }
                ?: "application/octet-stream"
        }
    }

    private fun isSupportedAudio(
        name: String,
        mime: String?
    ): Boolean {
        if (mime?.startsWith("audio/") == true) {
            return true
        }

        val extension = name
            .substringAfterLast('.', "")
            .lowercase()

        return extension in setOf(
            "flac", "wav", "wave", "mp3",
            "m4a", "m4b", "aac", "alac",
            "ogg", "oga", "opus", "aif",
            "aiff", "ape", "wv", "dsf", "dff"
        )
    }

    private fun android.database.Cursor.stringOrNull(
        column: Int
    ): String? {
        if (column < 0 || isNull(column)) {
            return null
        }

        return getString(column)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun android.database.Cursor.longOrDefault(
        column: Int,
        fallback: Long
    ): Long {
        return if (column >= 0 && !isNull(column)) {
            getLong(column)
        } else {
            fallback
        }
    }
}
