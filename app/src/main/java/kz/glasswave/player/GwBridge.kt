package kz.glasswave.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.ArrayDeque

class GwBridge(context: Context) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val journal = ArrayDeque<String>()

    @Synchronized
    private fun log(message: String) {
        journal.addFirst("${Instant.now()} $message")
        while (journal.size > 100) journal.removeLast()
    }

    @JavascriptInterface
    fun openStream(
        format: String,
        sampleRate: Int,
        deviceId: Int,
        exclusive: Boolean
    ): String {
        log("OPEN format=$format rate=$sampleRate device=$deviceId exclusive=$exclusive")
        return GwNativeAudio.nativeOpen(
            format.uppercase(),
            sampleRate,
            deviceId,
            exclusive
        )
    }

    @JavascriptInterface
    fun startStream(): String {
        log("START")
        return GwNativeAudio.nativeStart()
    }

    @JavascriptInterface
    fun stopStream(): String {
        log("STOP")
        return GwNativeAudio.nativeStop()
    }

    @JavascriptInterface
    fun closeStream(): String {
        log("CLOSE")
        return GwNativeAudio.nativeClose()
    }

    @JavascriptInterface
    fun status(): String = GwNativeAudio.nativeStatus()

    @JavascriptInterface
    fun logs(): String = synchronized(this) {
        JSONArray(journal.toList()).toString()
    }

    @JavascriptInterface
    fun listOutputDevices(): String {
        val result = JSONArray()

        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .forEach { device ->
                result.put(
                    JSONObject()
                        .put("id", device.id)
                        .put("name", device.productName.toString())
                        .put("type", typeName(device.type))
                        .put("sampleRates", JSONArray(device.sampleRates.toList()))
                        .put("channelCounts", JSONArray(device.channelCounts.toList()))
                )
            }

        log("DEVICES count=${result.length()}")
        return result.toString()
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        else -> "TYPE_$type"
    }
}
