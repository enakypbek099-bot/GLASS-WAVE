# GLASS WAVE

GLASS WAVE — офлайн Hi-Res аудиоплеер для Android.

## GW Native

Аудиодвижок приложения называется **GW Native**.

Основная архитектура:

```text
GLASS WAVE UI
      │
      ▼
Android WebView
      │
      ▼
GW Bridge
      │
      ▼
Kotlin / JNI
      │
      ▼
GW Native
      │
      ├── QUALITY
      │     └── Integer Direct Path
      │
      └── DSP
            ├── 32 Band EQ
            ├── Tone
            ├── Bass
            ├── DJ
            ├── Spatial
            ├── Loudness
            ├── Resampling
            └── Dither
      │
      ▼
Google Oboe
      │
      ▼
AAudio
      │
      ▼
Android Audio Device
