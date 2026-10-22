package kz.glasswave.player

object GwNativeAudio {
    init {
        System.loadLibrary("gw_native")
    }

    external fun nativeOpen(
        format: String,
        sampleRate: Int,
        deviceId: Int,
        exclusive: Boolean
    ): String

    external fun nativeStart(): String
    external fun nativeStop(): String
    external fun nativeClose(): String
    external fun nativeStatus(): String
}
