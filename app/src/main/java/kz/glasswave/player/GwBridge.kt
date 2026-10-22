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

    private val appContext: Context =
        context.applicationContext

    private val audioManager: AudioManager =
        appContext.getSystemService(
            Context.AUDIO_SERVICE
        ) as AudioManager

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val journal =
        ArrayDeque<String>()

    @Synchronized
    private fun log(message: String) {
        val line =
            "${Instant.now()} $message"

        journal.addFirst(line)

        while (journal.size > 100) {
            journal.removeLast()
        }

        GwRuntimeLog.info(
            "GW_BRIDGE",
            message
        )
    }

    private fun unavailable(
        operation: String
    ): String {
        val result =
            JSONObject()
                .put("ok", false)
                .put(
                    "operation",
                    operation
                )
                .put(
                    "error",
                    "GW_NATIVE_NOT_LOADED"
                )
                .put(
                    "details",
                    GwNativeAudio.loadError()
                )

        log(
            "$operation rejected: " +
                "native library unavailable"
        )

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

            log(
                "$operation result=$result"
            )

            result
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "GW_BRIDGE",
                "$operation failed",
                error
            )

            JSONObject()
                .put("ok", false)
                .put(
                    "operation",
                    operation
                )
                .put(
                    "error",
                    error.javaClass.simpleName
                )
                .put(
                    "details",
                    error.message ?: ""
                )
                .toString()
        }
    }

    @JavascriptInterface
    fun openStream(
        format: String,
        sampleRate: Int,
        deviceId: Int,
        exclusive: Boolean
    ): String {
        return nativeCall("OPEN") {
            GwNativeAudio.nativeOpen(
                format.uppercase(),
                sampleRate,
                deviceId,
                exclusive
            )
        }
    }

    @JavascriptInterface
    fun startStream(): String {
        return nativeCall("START") {
            GwNativeAudio.nativeStart()
        }
    }

    @JavascriptInterface
    fun stopStream(): String {
        return nativeCall("STOP") {
            GwNativeAudio.nativeStop()
        }
    }

    @JavascriptInterface
    fun closeStream(): String {
        return nativeCall("CLOSE") {
            GwNativeAudio.nativeClose()
        }
    }

    @JavascriptInterface
    fun status(): String {
        return nativeCall("STATUS") {
            GwNativeAudio.nativeStatus()
        }
    }

    @JavascriptInterface
    fun jsLog(
        level: String,
        message: String
    ) {
        when (level.uppercase()) {
            "ERROR" -> {
                GwRuntimeLog.error(
                    "JAVASCRIPT",
                    message
                )
            }

            "WARN" -> {
                GwRuntimeLog.warn(
                    "JAVASCRIPT",
                    message
                )
            }

            else -> {
                GwRuntimeLog.info(
                    "JAVASCRIPT",
                    message
                )
            }
        }
    }

    @JavascriptInterface
    fun jsError(message: String) {
        GwRuntimeLog.error(
            "JS_ERROR",
            message
        )
    }

    @JavascriptInterface
    fun runtimeLogs(): String {
        return GwRuntimeLog.snapshot()
    }

    @JavascriptInterface
    fun copyRuntimeLogs(): Boolean {
        val text =
            GwRuntimeLog.snapshot()

        return try {
            mainHandler.post {
                try {
                    val clipboard =
                        appContext.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager

                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "GLASS WAVE runtime diagnostics",
                            text
                        )
                    )

                    GwRuntimeLog.info(
                        "CLIPBOARD",
                        "Runtime log copied"
                    )
                } catch (error: Throwable) {
                    GwRuntimeLog.error(
                        "CLIPBOARD",
                        "Runtime log copy failed",
                        error
                    )
                }
            }

            true
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "CLIPBOARD",
                "Unable to schedule log copy",
                error
            )

            false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun logs(): String {
        return JSONArray(
            journal.toList()
        ).toString()
    }

    @JavascriptInterface
    fun listOutputDevices(): String {
        return try {
            val devices =
                audioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS
                )

            val result = JSONArray()

            devices
                .filter { device ->
                    device.isSink
                }
                .forEach { device ->
                    result.put(
                        deviceToJson(device)
                    )
                }

            log(
                "DEVICES count=${result.length()}"
            )

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

    /*
     * Build-safe output detection.
     *
     * Мұнда getDevicesForAttributes() қолданылмайды.
     * Себебі дәл сол API compileDebugKotlin кезінде
     * Unresolved reference қатесін берген.
     *
     * Оның орнына Android-та тұрақты жұмыс істейтін:
     *
     * AudioManager.getDevices(
     *     AudioManager.GET_DEVICES_OUTPUTS
     * )
     *
     * қолданылады.
     *
     * Егер бірнеше output бар болса:
     * USB / wired / Bluetooth / HDMI сияқты сыртқы
     * құрылғылар built-in speaker-ден жоғары
     * приоритет алады.
     */
    @JavascriptInterface
    fun currentOutputDevice(): String {
        return try {
            val devices =
                audioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS
                )
                    .filter { device ->
                        device.isSink
                    }

            if (devices.isEmpty()) {
                log(
                    "CURRENT_ROUTE no output devices"
                )

                return JSONObject()
                    .put("ok", false)
                    .put(
                        "error",
                        "OUTPUT_DEVICE_NOT_FOUND"
                    )
                    .toString()
            }

            val selected =
                devices.maxByOrNull { device ->
                    outputPriority(
                        device.type
                    )
                }

            if (selected == null) {
                log(
                    "CURRENT_ROUTE selection failed"
                )

                return JSONObject()
                    .put("ok", false)
                    .put(
                        "error",
                        "OUTPUT_DEVICE_NOT_FOUND"
                    )
                    .toString()
            }

            val result =
                deviceToJson(selected)
                    .put("ok", true)

            log(
                "CURRENT_ROUTE " +
                    "id=${selected.id} " +
                    "name=${selected.productName} " +
                    "type=${typeName(selected.type)}"
            )

            result.toString()
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "AUDIO_ROUTE",
                "Output device detection failed",
                error
            )

            JSONObject()
                .put("ok", false)
                .put(
                    "error",
                    error.javaClass.simpleName
                )
                .put(
                    "details",
                    error.message ?: ""
                )
                .toString()
        }
    }

    private fun deviceToJson(
        device: AudioDeviceInfo
    ): JSONObject {
        val sampleRates =
            JSONArray()

        device.sampleRates.forEach { rate ->
            sampleRates.put(rate)
        }

        val channelCounts =
            JSONArray()

        device.channelCounts.forEach { count ->
            channelCounts.put(count)
        }

        val channelMasks =
            JSONArray()

        device.channelMasks.forEach { mask ->
            channelMasks.put(mask)
        }

        val channelIndexMasks =
            JSONArray()

        device.channelIndexMasks.forEach { mask ->
            channelIndexMasks.put(mask)
        }

        val encodings =
            JSONArray()

        device.encodings.forEach { encoding ->
            encodings.put(encoding)
        }

        return JSONObject()
            .put(
                "id",
                device.id
            )
            .put(
                "name",
                device.productName
                    .toString()
            )
            .put(
                "type",
                typeName(device.type)
            )
            .put(
                "typeId",
                device.type
            )
            .put(
                "address",
                device.address
            )
            .put(
                "isSink",
                device.isSink
            )
            .put(
                "isSource",
                device.isSource
            )
            .put(
                "sampleRates",
                sampleRates
            )
            .put(
                "channelCounts",
                channelCounts
            )
            .put(
                "channelMasks",
                channelMasks
            )
            .put(
                "channelIndexMasks",
                channelIndexMasks
            )
            .put(
                "encodings",
                encodings
            )
    }

    /*
     * Бірнеше output бір уақытта Android-та көрінуі мүмкін.
     *
     * Бұл функция нақты active-routing API емес.
     * Ол қолжетімді output-тардың ішінен ең ықтимал
     * media құрылғысын таңдайды.
     */
    private fun outputPriority(
        type: Int
    ): Int {
        return when (type) {

            AudioDeviceInfo.TYPE_USB_HEADSET ->
                1000

            AudioDeviceInfo.TYPE_USB_DEVICE ->
                990

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                980

            AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                970

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                960

            AudioDeviceInfo.TYPE_BLE_HEADSET ->
                950

            AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                940

            AudioDeviceInfo.TYPE_HEARING_AID ->
                930

            AudioDeviceInfo.TYPE_HDMI_EARC ->
                920

            AudioDeviceInfo.TYPE_HDMI_ARC ->
                910

            AudioDeviceInfo.TYPE_HDMI ->
                900

            AudioDeviceInfo.TYPE_LINE_DIGITAL ->
                890

            AudioDeviceInfo.TYPE_LINE_ANALOG ->
                880

            AudioDeviceInfo.TYPE_AUX_LINE ->
                870

            AudioDeviceInfo.TYPE_DOCK ->
                860

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                100

            else ->
                500
        }
    }

    private fun typeName(
        type: Int
    ): String {
        return when (type) {

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

            AudioDeviceInfo.TYPE_HDMI ->
                "HDMI"

            AudioDeviceInfo.TYPE_HDMI_ARC ->
                "HDMI_ARC"

            AudioDeviceInfo.TYPE_HDMI_EARC ->
                "HDMI_EARC"

            AudioDeviceInfo.TYPE_LINE_ANALOG ->
                "LINE_ANALOG"

            AudioDeviceInfo.TYPE_LINE_DIGITAL ->
                "LINE_DIGITAL"

            AudioDeviceInfo.TYPE_AUX_LINE ->
                "AUX_LINE"

            AudioDeviceInfo.TYPE_DOCK ->
                "DOCK"

            AudioDeviceInfo.TYPE_HEARING_AID ->
                "HEARING_AID"

            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                "BUILTIN_EARPIECE"

            AudioDeviceInfo.TYPE_TELEPHONY ->
                "TELEPHONY"

            AudioDeviceInfo.TYPE_IP ->
                "IP"

            AudioDeviceInfo.TYPE_BUS ->
                "BUS"

            AudioDeviceInfo.TYPE_REMOTE_SUBMIX ->
                "REMOTE_SUBMIX"

            else ->
                "TYPE_$type"
        }
    }
}
