package kz.glasswave.player

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.ArrayDeque

class GwBridge(
    context: Context,
    private val libraryStore: GwLibraryStore,
    private val requestTrackPicker: () -> Unit,
    private val requestDeviceScan: () -> Unit,
    private val libraryChanged: () -> Unit
) {

    private val appContext = context.applicationContext
    private val activity = context as? Activity
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val journal = ArrayDeque<String>()

    @Synchronized
    private fun log(message: String) {
        journal.addFirst("${Instant.now()} $message")
        while (journal.size > 100) journal.removeLast()
        GwRuntimeLog.info("GW_BRIDGE", message)
    }

    // ---- Library ----

    @JavascriptInterface
    fun pickTracks(): Boolean {
        mainHandler.post(requestTrackPicker)
        return true
    }

    @JavascriptInterface
    fun scanDeviceMusic(): Boolean {
        mainHandler.post(requestDeviceScan)
        return true
    }

    @JavascriptInterface
    fun library(): String = libraryStore.asJson()

    @JavascriptInterface
    fun removeTrack(id: String): String {
        val removed = libraryStore.remove(id)
        if (removed) mainHandler.post(libraryChanged)
        return JSONObject()
            .put("ok", removed)
            .put("id", id)
            .toString()
    }

    @JavascriptInterface
    fun clearLibrary(): String {
        libraryStore.clear()
        mainHandler.post(libraryChanged)
        return JSONObject().put("ok", true).toString()
    }

    // ---- Background playback ----

    @JavascriptInterface
    fun prepareBackgroundPlayback(): Boolean {
        requestNotificationPermission()
        return true
    }

    @JavascriptInterface
    fun updatePlaybackSnapshot(
        trackId: String,
        positionMs: Long,
        playing: Boolean
    ) {
        GwPlaybackState.update(
            context = appContext,
            isActive = false,
            id = trackId,
            position = positionMs.coerceAtLeast(0L),
            duration = 0L,
            isPlaying = playing,
            trackTitle = "",
            trackArtist = ""
        )
    }

    @JavascriptInterface
    fun startBackgroundPlayback(trackId: String, positionMs: Long): Boolean {
        if (trackId.isBlank()) return false
        requestNotificationPermission()

        val intent = Intent(appContext, GwPlaybackService::class.java)
            .setAction(GwPlaybackService.ACTION_START)
            .putExtra(GwPlaybackService.EXTRA_TRACK_ID, trackId)
            .putExtra(
                GwPlaybackService.EXTRA_POSITION_MS,
                positionMs.coerceAtLeast(0L)
            )

        return try {
            ContextCompat.startForegroundService(appContext, intent)
            log("Background start: $trackId at $positionMs")
            true
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "BACKGROUND",
                "Foreground service start failed",
                error
            )
            false
        }
    }

    @JavascriptInterface
    fun backgroundState(): String = GwPlaybackState.json(appContext)

    @JavascriptInterface
    fun claimForegroundPlayback(): String {
        val snapshot = GwPlaybackState.json(appContext)
        runCatching {
            appContext.startService(
                Intent(appContext, GwPlaybackService::class.java)
                    .setAction(GwPlaybackService.ACTION_STOP)
            )
        }.onFailure { error ->
            GwRuntimeLog.error(
                "BACKGROUND",
                "Unable to stop background service",
                error
            )
        }
        return snapshot
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            activity != null &&
            ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            mainHandler.post {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    4103
                )
            }
        }
    }

    // ---- Native bridge (compile-safe) ----

    /*
     * COMPATIBILITY STUB.
     * Қазіргі GwNativeAudio API-де nativeOpen() жоқ.
     * Compile қатесін жабу үшін өтірік JNI symbol
     * қоспай, анық JSON жауап қайтарады.
     */
    @JavascriptInterface
    fun openStream(
        format: String,
        sampleRate: Int,
        deviceId: Int,
        exclusive: Boolean
    ): String {
        log(
            "OPEN unavailable: format=$format, " +
                "sampleRate=$sampleRate, " +
                "deviceId=$deviceId, " +
                "exclusive=$exclusive"
        )
        return JSONObject()
            .put("ok", false)
            .put("operation", "OPEN")
            .put("error", "NATIVE_OPEN_UNAVAILABLE")
            .put(
                "details",
                "Current GwNativeAudio API does not expose nativeOpen"
            )
            .toString()
    }

    private inline fun nativeCall(
        operation: String,
        call: () -> String
    ): String {
        if (!GwNativeAudio.isLoaded()) {
            return JSONObject()
                .put("ok", false)
                .put("operation", operation)
                .put("error", "GW_NATIVE_NOT_LOADED")
                .put("details", GwNativeAudio.loadError())
                .toString()
        }
        return try {
            val result = call()
            log("$operation result=$result")
            result
        } catch (error: Throwable) {
            GwRuntimeLog.error("GW_BRIDGE", "$operation failed", error)
            JSONObject()
                .put("ok", false)
                .put("operation", operation)
                .put("error", error.javaClass.simpleName)
                .put("details", error.message ?: "")
                .toString()
        }
    }

    @JavascriptInterface
    fun startStream(): String =
        nativeCall("START") { GwNativeAudio.nativeStart() }

    @JavascriptInterface
    fun stopStream(): String =
        nativeCall("STOP") { GwNativeAudio.nativeStop() }

    @JavascriptInterface
    fun closeStream(): String =
        nativeCall("CLOSE") { GwNativeAudio.nativeClose() }

    @JavascriptInterface
    fun status(): String =
        nativeCall("STATUS") { GwNativeAudio.nativeStatus() }

    // ---- Output route ----

    @JavascriptInterface
    fun currentOutputDevice(): String {
        return try {
            val selected = outputDevices()
                .maxByOrNull { outputPriority(it.type) }

            if (selected == null) {
                JSONObject()
                    .put("ok", false)
                    .put("error", "OUTPUT_DEVICE_NOT_FOUND")
                    .toString()
            } else {
                deviceToJson(selected)
                    .put("ok", true)
                    .put("spatialCapable", spatialCapable(selected.type))
                    .toString()
            }
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "AUDIO_ROUTE",
                "Output device detection failed",
                error
            )
            JSONObject()
                .put("ok", false)
                .put("error", error.javaClass.simpleName)
                .put("details", error.message ?: "")
                .toString()
        }
    }

    @JavascriptInterface
    fun outputSnapshot(): String {
        return try {
            val devices = outputDevices()
            val selected = devices.maxByOrNull { outputPriority(it.type) }
            val array = JSONArray()

            devices.forEach { device ->
                array.put(
                    deviceToJson(device)
                        .put("spatialCapable", spatialCapable(device.type))
                )
            }

            val result = JSONObject()
                .put("ok", selected != null)
                .put("devices", array)

            if (selected != null) {
                result.put(
                    "candidate",
                    deviceToJson(selected)
                        .put("spatialCapable", spatialCapable(selected.type))
                )
            }
            result.toString()
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "AUDIO_ROUTE",
                "Output snapshot failed",
                error
            )
            JSONObject()
                .put("ok", false)
                .put("error", error.javaClass.simpleName)
                .put("devices", JSONArray())
                .toString()
        }
    }

    @JavascriptInterface
    fun listOutputDevices(): String {
        return try {
            val result = JSONArray()
            outputDevices().forEach { device ->
                result.put(
                    deviceToJson(device)
                        .put("spatialCapable", spatialCapable(device.type))
                )
            }
            result.toString()
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "AUDIO_DEVICES",
                "Output enumeration failed",
                error
            )
            JSONArray().toString()
        }
    }

    private fun outputDevices(): List<AudioDeviceInfo> =
        audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }

    private fun deviceToJson(device: AudioDeviceInfo): JSONObject =
        JSONObject()
            .put("id", device.id)
            .put("name", device.productName.toString())
            .put("type", typeName(device.type))
            .put("typeId", device.type)
            .put("address", device.address)
            .put("sampleRates", JSONArray(device.sampleRates.toList()))
            .put("channelCounts", JSONArray(device.channelCounts.toList()))

    private fun spatialCapable(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
        else -> false
    }

    private fun outputPriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> 1000
        AudioDeviceInfo.TYPE_USB_DEVICE -> 990
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 980
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 970
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 960
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 950
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 940
        AudioDeviceInfo.TYPE_HEARING_AID -> 930
        AudioDeviceInfo.TYPE_AUX_LINE -> 880
        AudioDeviceInfo.TYPE_LINE_ANALOG -> 870
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> 860
        AudioDeviceInfo.TYPE_HDMI_EARC -> 850
        AudioDeviceInfo.TYPE_HDMI_ARC -> 840
        AudioDeviceInfo.TYPE_HDMI -> 830
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 100
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 90
        else -> 500
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        else -> "TYPE_$type"
    }

    // ---- JS logging ----

    @JavascriptInterface
    fun jsLog(level: String, message: String) {
        when (level.uppercase()) {
            "ERROR" -> GwRuntimeLog.error("JAVASCRIPT", message)
            "WARN" -> GwRuntimeLog.warn("JAVASCRIPT", message)
            else -> GwRuntimeLog.info("JAVASCRIPT", message)
        }
    }

    @JavascriptInterface
    fun jsError(message: String) {
        GwRuntimeLog.error("JS_ERROR", message)
    }

    @JavascriptInterface
    fun runtimeLogs(): String = GwRuntimeLog.snapshot()

    @JavascriptInterface
    fun copyRuntimeLogs(): Boolean {
        mainHandler.post {
            try {
                val clipboard = appContext.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "GLASS WAVE diagnostics",
                        GwRuntimeLog.snapshot()
                    )
                )
            } catch (error: Throwable) {
                GwRuntimeLog.error(
                    "CLIPBOARD",
                    "Runtime log copy failed",
                    error
                )
            }
        }
        return true
    }

    @JavascriptInterface
    @Synchronized
    fun logs(): String = JSONArray(journal.toList()).toString()
}
