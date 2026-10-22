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
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val journal = ArrayDeque<String>()

    @Synchronized private fun log(message: String) {
        journal.addFirst(message)
        while (journal.size > 100) journal.removeLast()
        GwRuntimeLog.info("GW_BRIDGE", message)
    }

    @JavascriptInterface fun pickTracks(): Boolean { mainHandler.post(requestTrackPicker); return true }
    @JavascriptInterface fun scanDeviceMusic(): Boolean { mainHandler.post(requestDeviceScan); return true }
    @JavascriptInterface fun library(): String = libraryStore.asJson()

    @JavascriptInterface
    fun removeTrack(id: String): String {
        val ok = libraryStore.remove(id)
        if (ok) mainHandler.post(libraryChanged)
        return JSONObject().put("ok", ok).toString()
    }

    @JavascriptInterface
    fun clearLibrary(): String {
        libraryStore.clear()
        mainHandler.post(libraryChanged)
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun prepareBackgroundPlayback(): Boolean {
        requestNotificationPermission()
        return true
    }

    @JavascriptInterface
    fun startBackgroundPlayback(trackId: String, positionMs: Long): Boolean {
        if (trackId.isBlank()) return false
        requestNotificationPermission()
        val intent = Intent(appContext, GwPlaybackService::class.java)
            .setAction(GwPlaybackService.ACTION_PLAY_LIBRARY)
            .putExtra(GwPlaybackService.EXTRA_TRACK_ID, trackId)
            .putExtra(GwPlaybackService.EXTRA_POSITION_MS, positionMs.coerceAtLeast(0L))
        return try {
            ContextCompat.startForegroundService(appContext, intent)
            true
        } catch (error: Throwable) {
            GwRuntimeLog.error("BACKGROUND", "Background playback failed", error)
            false
        }
    }

    @JavascriptInterface fun backgroundState(): String = GwPlaybackState.json()

    @JavascriptInterface
    fun claimForegroundPlayback(): String {
        val snapshot = GwPlaybackState.json()
        if (GwPlaybackState.active) {
            appContext.startService(
                Intent(appContext, GwPlaybackService::class.java)
                    .setAction(GwPlaybackService.ACTION_STOP_SERVICE)
            )
        }
        return snapshot
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && activity != null &&
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
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

    @JavascriptInterface
    fun startNativeWav(trackId: String, startMs: Long): String {
        val track = libraryStore.find(trackId)
            ?: return JSONObject().put("ok", false).put("error", "TRACK_NOT_FOUND").toString()
        val ext = track.name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("wav", "wave")) {
            return JSONObject().put("ok", false).put("error", "WAV_ONLY").toString()
        }
        if (!GwNativeAudio.isLoaded()) {
            return JSONObject().put("ok", false).put("error", "GW_NATIVE_NOT_LOADED").toString()
        }
        return try {
            appContext.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(track.uri), "r")?.use { afd ->
                val deviceId = selectedOutputDevice()?.id ?: -1
                GwNativeAudio.nativeLoadWav(
                    afd.parcelFileDescriptor.fd,
                    afd.startOffset,
                    afd.length,
                    deviceId,
                    true,
                    startMs.coerceAtLeast(0L)
                )
            } ?: JSONObject().put("ok", false).put("error", "TRACK_OPEN_FAILED").toString()
        } catch (error: Throwable) {
            GwRuntimeLog.error("GW_NATIVE", "WAV start failed", error)
            JSONObject().put("ok", false).put("error", error.javaClass.simpleName)
                .put("details", error.message ?: "").toString()
        }
    }

    @JavascriptInterface fun closeStream(): String = GwNativeAudio.closeSafely()
    @JavascriptInterface fun status(): String = if (GwNativeAudio.isLoaded()) runCatching { GwNativeAudio.nativeStatus() }.getOrElse { JSONObject().put("ok", false).put("error", it.javaClass.simpleName).toString() } else JSONObject().put("ok", false).toString()

    @JavascriptInterface fun jsLog(level: String, message: String) {
        when (level.uppercase()) {
            "ERROR" -> GwRuntimeLog.error("JAVASCRIPT", message)
            "WARN" -> GwRuntimeLog.warn("JAVASCRIPT", message)
            else -> GwRuntimeLog.info("JAVASCRIPT", message)
        }
    }
    @JavascriptInterface fun jsError(message: String) = GwRuntimeLog.error("JS_ERROR", message)
    @JavascriptInterface fun runtimeLogs(): String = GwRuntimeLog.snapshot()
    @JavascriptInterface fun copyRuntimeLogs(): Boolean {
        mainHandler.post {
            val cb = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("GLASS WAVE diagnostics", GwRuntimeLog.snapshot()))
        }
        return true
    }
    @JavascriptInterface @Synchronized fun logs(): String = JSONArray(journal.toList()).toString()

    @JavascriptInterface
    fun currentOutputDevice(): String {
        val d = selectedOutputDevice() ?: return JSONObject().put("ok", false).toString()
        return deviceToJson(d).put("ok", true).toString()
    }

    @JavascriptInterface
    fun listOutputDevices(): String {
        val result = JSONArray()
        outputDevices().forEach { result.put(deviceToJson(it)) }
        return result.toString()
    }

    private fun outputDevices(): List<AudioDeviceInfo> = audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
    private fun selectedOutputDevice(): AudioDeviceInfo? = outputDevices().maxByOrNull { outputPriority(it.type) }

    private fun deviceToJson(d: AudioDeviceInfo): JSONObject = JSONObject()
        .put("id", d.id).put("name", d.productName.toString())
        .put("type", typeName(d.type)).put("address", d.address)
        .put("sampleRates", JSONArray(d.sampleRates.toList()))
        .put("channelCounts", JSONArray(d.channelCounts.toList()))

    private fun outputPriority(type: Int): Int = when (type) {
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
        AudioDeviceInfo.TYPE_AUX_LINE -> 870
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 100
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
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        else -> "TYPE_$type"
    }
}
