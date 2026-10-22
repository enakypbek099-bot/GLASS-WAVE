package kz.glasswave.player

object GwNativeAudio {
    @Volatile private var loaded = false
    @Volatile private var loadingError: String? = null

    init {
        try {
            System.loadLibrary("gw_native")
            loaded = true
            GwRuntimeLog.info("NATIVE_LOAD", "libgw_native.so loaded")
        } catch (error: Throwable) {
            loadingError = error.stackTraceToString()
            GwRuntimeLog.error("NATIVE_LOAD", "libgw_native.so load failed", error)
        }
    }

    fun isLoaded(): Boolean = loaded
    fun loadError(): String = loadingError ?: ""

    external fun nativeLoadWav(
        fd: Int,
        assetOffset: Long,
        assetLength: Long,
        deviceId: Int,
        exclusive: Boolean,
        startMs: Long
    ): String

    external fun nativeStart(): String
    external fun nativeStop(): String
    external fun nativeClose(): String
    external fun nativeStatus(): String

    fun closeSafely(): String {
        if (!loaded) return """{"ok":false,"error":"GW_NATIVE_NOT_LOADED"}"""
        return try {
            nativeClose()
        } catch (error: Throwable) {
            GwRuntimeLog.error("NATIVE_CLOSE", "nativeClose failed", error)
            """{"ok":false,"error":"NATIVE_CLOSE_FAILED"}"""
        }
    }
}
