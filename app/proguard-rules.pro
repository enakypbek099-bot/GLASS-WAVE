# GLASS WAVE
# GW Native

-keepclassmembers class kz.glasswave.player.** {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class kz.glasswave.player.GwBridge {
    *;
}

-keep class kz.glasswave.player.GwNativeAudio {
    *;
}
