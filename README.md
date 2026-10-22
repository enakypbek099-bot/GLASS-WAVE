# GLASS WAVE

GLASS WAVE — офлайн Hi-Res аудиоплеер для Android 14+.

## GW Native

**GW Native** — нативный аудиодвижок приложения.

Целевая архитектура:

```text
GLASS WAVE UI
    → Android WebView
    → GW Bridge
    → Kotlin / JNI
    → GW Native
    → Decoder
    → QUALITY или DSP
    → Google Oboe
    → AAudio
    → Audio Device
```

## Платформа

- Android 14+
- `minSdk 34`
- `targetSdk 35`
- `compileSdk 35`
- ARM64 (`arm64-v8a`)
- Kotlin
- C++20
- Офлайн-режим
- Разрешение `INTERNET` не используется

## Режим QUALITY

Основной целевой выход:

```text
PCM 32-bit Integer
```

Float32 существует как отдельный вариант.

Float32 не должен использоваться как скрытый fallback для строгого режима I32.

В режиме QUALITY обработка DSP отключается от аудиотракта, но настройки и элементы интерфейса GLASS WAVE сохраняются.

## Режим DSP

Планируемый тракт:

```text
Decoder
    → 32-band EQ
    → Tone / Bass / Balance
    → DJ / Spatial
    → Loudness / Limiter
    → libsoxr или libswresample
    → Dither / Quantizer
    → GW Native Output
    → Google Oboe
    → AAudio
```

## Частоты дискретизации

```text
SOURCE / AUTO
44.1 kHz
48 kHz
88.2 kHz
96 kHz
176.4 kHz
192 kHz
352.8 kHz
384 kHz
```

## Форматы выхода

```text
PCM 16-bit
PCM 24-bit
PCM 32-bit Integer
PCM 32-bit Float
```

## Интерфейс

Исходный интерфейс GLASS WAVE должен сохраняться.

Исходный пользовательский asset временно хранится под исходным именем:

```text
NEON_v29.html
```

Это имя используется только для сохранения исходного файла без потери дизайна.

Новые классы, API, функции и идентификаторы используют названия:

```text
GLASS WAVE
GW Native
GW Bridge
```

Web Audio удаляется только из аудиотракта.

Должны сохраниться:

- главный экран;
- библиотека;
- поиск;
- полноэкранный плеер;
- 32-band EQ;
- Tone;
- Bass;
- Balance;
- DJ Mix;
- 3D Scene;
- 8D;
- Sound Lab;
- Audio Microscope;
- GLASS CORE;
- анализ качества;
- визуальные индикаторы;
- существующие стили и расположение элементов.

Существующая Hi-Res область будет подключена к панели **GW Native**.

## Нативный стек

- Google Oboe
- AAudio
- FFmpeg
- libswresample
- libsoxr
- libebur128

## Сборка

Проект планируется собирать через GitHub Actions.

Версии Gradle, Android Gradle Plugin, Android NDK и CMake закрепляются в репозитории.

> Наличие файла в дереве проекта не означает, что весь нативный аудиотракт уже реализован или протестирован.
