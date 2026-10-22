package kz.glasswave.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.ArrayDeque

class GwBridge(
    context: Context,
    private val library: GwLibraryStore,
    private val requestTrackPicker: () -> Unit,
    private val libraryChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val journal = ArrayDeque<String>()

    @Synchronized
    private fun log(message: String) {
        journal.addFirst("${Instant.now()} $message")
        while (journal.size > 100) journal.removeLast()
        GwRuntimeLog.info("GW_BRIDGE", message)
    }

    private fun unavailable(operation: String) = JSONObject()
        .put("ok", false).put("operation", operation)
        .put("error", "GW_NATIVE_NOT_LOADED").put("details", GwNativeAudio.loadError())
        .toString()

    private inline fun nativeCall(operation: String, call: () -> String): String {
        if (!GwNativeAudio.isLoaded()) return unavailable(operation)
        return try { call().also { log("$operation result=$it") } }
        catch (error: Throwable) {
            GwRuntimeLog.error("GW_BRIDGE", "$operation failed", error)
            JSONObject().put("ok", false).put("operation", operation)
                .put("error", error.javaClass.simpleName).put("details", error.message ?: "")
                .toString()
        }
    }

    @JavascriptInterface
    fun openStream(format: String, sampleRate: Int, deviceId: Int, exclusive: Boolean) =
        nativeCall("OPEN") { GwNativeAudio.nativeOpen(format.uppercase(), sampleRate, deviceId, exclusive) }

    @JavascriptInterface fun startStream() = nativeCall("START") { GwNativeAudio.nativeStart() }
    @JavascriptInterface fun stopStream() = nativeCall("STOP") { GwNativeAudio.nativeStop() }
    @JavascriptInterface fun closeStream() = nativeCall("CLOSE") { GwNativeAudio.nativeClose() }
    @JavascriptInterface fun status() = nativeCall("STATUS") { GwNativeAudio.nativeStatus() }

    @JavascriptInterface
    fun pickTracks(): Boolean = try {
        mainHandler.post(requestTrackPicker)
        true
    } catch (error: Throwable) {
        GwRuntimeLog.error("LIBRARY", "Track picker launch failed", error)
        false
    }

    @JavascriptInterface fun library(): String = library.asJson()

    @JavascriptInterface
    fun removeTrack(id: String): Boolean = library.remove(id).also { if (it) mainHandler.post(libraryChanged) }

    @JavascriptInterface
    fun clearLibrary(): Boolean = try {
        library.clear()
        mainHandler.post(libraryChanged)
        true
    } catch (error: Throwable) {
        GwRuntimeLog.error("LIBRARY", "Clear library failed", error)
        false
    }

    @JavascriptInterface
    fun jsLog(level: String, message: String) {
        when (level.uppercase()) {
            "ERROR" -> GwRuntimeLog.error("JAVASCRIPT", message)
            "WARN" -> GwRuntimeLog.warn("JAVASCRIPT", message)
            else -> GwRuntimeLog.info("JAVASCRIPT", message)
        }
    }

    @JavascriptInterface fun jsError(message: String) = GwRuntimeLog.error("JS_ERROR", message)
    @JavascriptInterface fun runtimeLogs(): String = GwRuntimeLog.snapshot()

    @JavascriptInterface
    fun copyRuntimeLogs(): Boolean = try {
        mainHandler.post {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GLASS WAVE diagnostics", GwRuntimeLog.snapshot()))
        }
        true
    } catch (error: Throwable) {
        GwRuntimeLog.error("CLIPBOARD", "Runtime log copy failed", error)
        false
    }

    @JavascriptInterface @Synchronized
    fun logs(): String = JSONArray(journal.toList()).toString()

    @JavascriptInterface
    fun listOutputDevices(): String = try {
        val result = JSONArray()
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(AudioDeviceInfo::isSink).forEach { result.put(deviceToJson(it)) }
        result.toString()
    } catch (error: Throwable) {
        GwRuntimeLog.error("AUDIO_DEVICES", "Device enumeration failed", error)
        JSONArray().toString()
    }

    @JavascriptInterface
    fun currentOutputDevice(): String = try {
        val selected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(AudioDeviceInfo::isSink).maxByOrNull { outputPriority(it.type) }
        if (selected == null) JSONObject().put("ok", false).put("error", "OUTPUT_DEVICE_NOT_FOUND").toString()
        else deviceToJson(selected).put("ok", true).toString()
    } catch (error: Throwable) {
        GwRuntimeLog.error("AUDIO_ROUTE", "Output device detection failed", error)
        JSONObject().put("ok", false).put("error", error.javaClass.simpleName).toString()
    }

    private fun deviceToJson(device: AudioDeviceInfo) = JSONObject()
        .put("id", device.id).put("name", device.productName.toString())
        .put("type", typeName(device.type)).put("typeId", device.type)
        .put("address", device.address).put("sampleRates", JSONArray(device.sampleRates.toList()))
        .put("channelCounts", JSONArray(device.channelCounts.toList()))

    private fun outputPriority(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> 1000
        AudioDeviceInfo.TYPE_USB_DEVICE -> 990
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 980
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 970
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 960
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 950
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 940
        AudioDeviceInfo.TYPE_HEARING_AID -> 930
        AudioDeviceInfo.TYPE_HDMI_EARC -> 920
        AudioDeviceInfo.TYPE_HDMI_ARC -> 910
        AudioDeviceInfo.TYPE_HDMI -> 900
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 100
        else -> 500
    }

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        else -> "TYPE_$type"
    }
}
