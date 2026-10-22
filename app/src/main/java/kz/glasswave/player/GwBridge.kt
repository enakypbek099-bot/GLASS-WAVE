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

class GwBridge(context: Context) {
    private val appContext = context.applicationContext

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE)
            as AudioManager

    private val journal = ArrayDeque<String>()

    @Synchronized
    private fun log(message: String) {
        val line = "${Instant.now()} $message"

        journal.addFirst(line)
        while (journal.size > 100) journal.removeLast()

        GwRuntimeLog.info("GW_BRIDGE", message)
    }

    private fun unavailable(operation: String): String {
        val result = JSONObject()
            .put("ok", false)
            .put("operation", operation)
            .put("error", "GW_NATIVE_NOT_LOADED")
            .put("details", GwNativeAudio.loadError())

        log("$operation rejected: native library unavailable")
        return result.toString()
    }

    private inline fun nativeCall(
        operation: String,
        call: () -> String
    ): String {
        if (!GwNativeAudio.isLoaded()) {
            return unavailable(operation)
        }

        return try {
            val result = call()
            log("$operation result=$result")
            result
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "GW_BRIDGE",
                "$operation failed",
                error
            )

            JSONObject()
                .put("ok", false)
                .put("operation", operation)
                .put("error", error.javaClass.simpleName)
                .put("details", error.message ?: "")
                .toString()
        }
    }

    @JavascriptInterface
    fun openStream(
        format: String,
        sampleRate: Int,
        deviceId: Int,
        exclusive: Boolean
    ): String = nativeCall("OPEN") {
        GwNativeAudio.nativeOpen(
            format.uppercase(),
            sampleRate,
            deviceId,
            exclusive
        )
    }

    @JavascriptInterface
    fun startStream(): String =
        nativeCall("START") {
            GwNativeAudio.nativeStart()
        }

    @JavascriptInterface
    fun stopStream(): String =
        nativeCall("STOP") {
            GwNativeAudio.nativeStop()
        }

    @JavascriptInterface
    fun closeStream(): String =
        nativeCall("CLOSE") {
            GwNativeAudio.nativeClose()
        }

    @JavascriptInterface
    fun status(): String =
        nativeCall("STATUS") {
            GwNativeAudio.nativeStatus()
        }

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
    fun runtimeLogs(): String =
        GwRuntimeLog.snapshot()

    @JavascriptInterface
    fun copyRuntimeLogs(): Boolean {
        val text = GwRuntimeLog.snapshot()

        Handler(Looper.getMainLooper()).post {
            val clipboard =
                appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "GLASS WAVE runtime diagnostics",
                    text
                )
            )
        }

        return true
    }

    @JavascriptInterface
    fun logs(): String = synchronized(this) {
        JSONArray(journal.toList()).toString()
    }

    @JavascriptInterface
    fun listOutputDevices(): String {
        return try {
            val result = JSONArray()

            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink }
                .forEach { device ->
                    result.put(
                        JSONObject()
                            .put("id", device.id)
                            .put(
                                "name",
                                device.productName.toString()
                            )
                            .put("type", typeName(device.type))
                            .put(
                                "sampleRates",
                                JSONArray(device.sampleRates.toList())
                            )
                            .put(
                                "channelCounts",
                                JSONArray(device.channelCounts.toList())
                            )
                    )
                }

            log("DEVICES count=${result.length()}")
            result.toString()
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "AUDIO_DEVICES",
                "Device enumeration failed",
                error
            )

            JSONArray().toString()
        }
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
            "BUILTIN_SPEAKER"

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
            "WIRED_HEADPHONES"

        AudioDeviceInfo.TYPE_WIRED_HEADSET ->
            "WIRED_HEADSET"

        AudioDeviceInfo.TYPE_USB_DEVICE ->
            "USB_DEVICE"

        AudioDeviceInfo.TYPE_USB_HEADSET ->
            "USB_HEADSET"

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
            "BLUETOOTH_A2DP"

        AudioDeviceInfo.TYPE_BLE_HEADSET ->
            "BLE_HEADSET"

        AudioDeviceInfo.TYPE_BLE_SPEAKER ->
            "BLE_SPEAKER"

        else -> "TYPE_$type"
    }
}
