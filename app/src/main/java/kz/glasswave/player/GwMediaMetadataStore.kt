package kz.glasswave.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class GwMediaMetadataStore(context: Context) {

    data class Pending(
        val id: String,
        val uri: String,
        val name: String
    )

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    private val coverDir =
        File(appContext.filesDir, "gw-covers").apply { mkdirs() }

    private val indexFile =
        AtomicFile(File(appContext.filesDir, "gw-media-metadata.json"))

    private val records = LinkedHashMap<String, JSONObject>()

    init { load() }

    @Synchronized
    fun decorateLibraryJson(libraryJson: String): String {
        val source = JSONArray(libraryJson)
        val result = JSONArray()

        for (index in 0 until source.length()) {
            val track = source.getJSONObject(index)
            val id = track.optString("id")
            val record = records[id]

            if (record != null) {
                val rate = record.optInt("sampleRate", 0)
                val bits = record.optInt("bitsPerSample", 0)
                val channels = record.optInt("channels", 0)
                val verified = record.optBoolean("sourceVerified", false)

                /*
                 * 16-bit файл ешқашан Hi-Res емес.
                 * Extension жалғыз өзі дәлел емес.
                 */
                val hiRes = verified && bits > 16 && rate >= 44_100

                val coverName = record.optString("coverFile")
                val cover = coverName.takeIf { it.isNotBlank() }
                    ?.let { File(coverDir, it) }

                track.put("sampleRate", rate)
                track.put("bitsPerSample", bits)
                track.put("channels", channels)
                track.put("sourceVerified", verified)
                track.put("hiRes", hiRes)
                track.put(
                    "coverUrl",
                    if (cover?.isFile == true) {
                        "https://appassets.androidplatform.net/" +
                            "covers/$id.jpg?v=" +
                            record.optInt("artworkVersion", 1)
                    } else ""
                )
            } else {
                track.put("sampleRate", 0)
                track.put("bitsPerSample", 0)
                track.put("channels", 0)
                track.put("sourceVerified", false)
                track.put("hiRes", false)
                track.put("coverUrl", "")
            }

            result.put(track)
        }

        return result.toString()
    }

    @Synchronized
    fun pending(libraryJson: String): List<Pending> {
        val source = JSONArray(libraryJson)
        val result = ArrayList<Pending>()

        for (index in 0 until source.length()) {
            val item = source.getJSONObject(index)
            val id = item.optString("id")
            if (id.isBlank()) continue
            if (records[id]?.optBoolean("scanned", false) == true) continue

            result += Pending(
                id = id,
                uri = item.optString("uri"),
                name = item.optString("name", "audio")
            )
        }

        return result
    }

    fun enrich(item: Pending) {
        val uri = Uri.parse(item.uri)
        var rate = 0
        var bits = 0
        var channels = 0
        var picture: ByteArray? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            rate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
            )?.toIntOrNull() ?: 0
            bits = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE
            )?.toIntOrNull() ?: 0
            picture = retriever.embeddedPicture
        } catch (error: Throwable) {
            GwRuntimeLog.warn(
                "MEDIA_META",
                "Retriever failed: ${item.name}"
            )
        } finally {
            runCatching { retriever.release() }
        }

        /*
         * FLAC/WAV үшін exact parser.
         * Retriever берген болжам badge дәлелі емес.
         */
        val exact = probeExact(uri, item.name)
        if (exact != null) {
            rate = exact.first
            bits = exact.second
            channels = exact.third
        }

        val old = synchronized(this) { records[item.id] }
        val oldCover = old?.optString("coverFile").orEmpty()
        val oldVersion = old?.optInt("artworkVersion", 0) ?: 0
        val cover = saveCover(item.id, picture, oldCover, oldVersion)

        val record = JSONObject()
            .put("scanned", true)
            .put("sampleRate", rate)
            .put("bitsPerSample", bits)
            .put("channels", channels)
            .put("sourceVerified", exact != null && rate > 0 && bits > 0)
            .put("coverFile", cover.first)
            .put("artworkVersion", cover.second)

        synchronized(this) {
            records[item.id] = record
            save()
        }
    }

    @Synchronized
    fun coverFileFor(id: String): File? {
        val name = records[id]?.optString("coverFile").orEmpty()
        if (name.isBlank()) return null
        return File(coverDir, name).takeIf { it.isFile }
    }

    private fun probeExact(
        uri: Uri,
        name: String
    ): Triple<Int, Int, Int>? {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("flac", "wav", "wave")) return null

        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1024 * 1024)
                var used = 0
                while (used < buffer.size) {
                    val count = input.read(buffer, used, buffer.size - used)
                    if (count <= 0) break
                    used += count
                }
                buffer.copyOf(used)
            }
        } catch (_: Throwable) { null } ?: return null

        return when (extension) {
            "flac" -> parseFlac(bytes)
            "wav", "wave" -> parseWav(bytes)
            else -> null
        }
    }

    private fun parseFlac(data: ByteArray): Triple<Int, Int, Int>? {
        if (data.size < 42 ||
            data.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "fLaC"
        ) return null

        var position = 4
        while (position + 4 <= data.size) {
            val header = data[position].toInt() and 0xff
            val type = header and 0x7f
            val length =
                ((data[position + 1].toInt() and 0xff) shl 16) or
                    ((data[position + 2].toInt() and 0xff) shl 8) or
                    (data[position + 3].toInt() and 0xff)
            val payload = position + 4

            if (type == 0 && length >= 34 && payload + 18 <= data.size) {
                var packed = 0L
                for (index in 0 until 8) {
                    packed = (packed shl 8) or
                        (data[payload + 10 + index].toLong() and 0xff)
                }
                val rate = (packed ushr 44).toInt()
                val channels = ((packed ushr 41) and 0x7).toInt() + 1
                val bits = ((packed ushr 36) and 0x1f).toInt() + 1
                return if (rate > 0 && bits > 0) {
                    Triple(rate, bits, channels)
                } else null
            }

            position = payload + length
            if (header and 0x80 != 0) break
        }
        return null
    }

    private fun parseWav(data: ByteArray): Triple<Int, Int, Int>? {
        if (data.size < 28) return null
        val riff = data.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        val wave = data.copyOfRange(8, 12).toString(Charsets.US_ASCII)
        if (riff !in setOf("RIFF", "RF64") || wave != "WAVE") return null

        fun le16(offset: Int): Int =
            (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8)

        fun le32(offset: Int): Int =
            (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)

        var position = 12
        while (position + 8 <= data.size) {
            val id = data.copyOfRange(position, position + 4)
                .toString(Charsets.US_ASCII)
            val length = le32(position + 4)
            val payload = position + 8

            if (id == "fmt " && length >= 16 && payload + 16 <= data.size) {
                val channels = le16(payload + 2)
                val rate = le32(payload + 4)
                val bits = le16(payload + 14)
                return if (rate > 0 && bits > 0) {
                    Triple(rate, bits, channels)
                } else null
            }

            if (length < 0) return null
            position = payload + length + (length and 1)
        }
        return null
    }

    private fun saveCover(
        id: String,
        picture: ByteArray?,
        previous: String,
        previousVersion: Int
    ): Pair<String, Int> {
        if (picture == null || picture.isEmpty() ||
            picture.size > 10 * 1024 * 1024
        ) return previous to previousVersion

        return try {
            val bounds = BitmapFactory.Options()
                .apply { inJustDecodeBounds = true }

            BitmapFactory.decodeByteArray(
                picture, 0, picture.size, bounds
            )

            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            if (largest <= 0) return previous to previousVersion

            var sample = 1
            while (largest / sample > 1024) sample *= 2

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeByteArray(
                picture, 0, picture.size, options
            ) ?: return previous to previousVersion

            val scale = minOf(
                1f,
                512f / maxOf(decoded.width, decoded.height)
            )
            val output = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    maxOf(1, (decoded.width * scale).toInt()),
                    maxOf(1, (decoded.height * scale).toInt()),
                    true
                )
            } else decoded

            val name = "$id.jpg"
            FileOutputStream(File(coverDir, name)).use { stream ->
                output.compress(Bitmap.CompressFormat.JPEG, 84, stream)
            }

            if (output !== decoded) output.recycle()
            decoded.recycle()
            name to (previousVersion + 1)
        } catch (_: Throwable) {
            previous to previousVersion
        }
    }

    @Synchronized
    private fun load() {
        if (!indexFile.baseFile.isFile) return
        runCatching {
            val text = indexFile.openRead()
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val root = JSONObject(text)
            root.keys().forEach { id ->
                records[id] = root.getJSONObject(id)
            }
        }.onFailure {
            GwRuntimeLog.error(
                "MEDIA_META",
                "Metadata cache load failed",
                it
            )
        }
    }

    @Synchronized
    private fun save() {
        var output: FileOutputStream? = null
        try {
            val root = JSONObject()
            records.forEach { (id, record) -> root.put(id, record) }
            output = indexFile.startWrite()
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            indexFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(indexFile::failWrite)
            GwRuntimeLog.error(
                "MEDIA_META",
                "Metadata cache save failed",
                error
            )
        }
    }
}
