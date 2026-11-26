# WaveZ — Архитектура и Мастер-план разработки

> Мобильный **полноценный неразрушающий Wave-редактор** уровня Audacity / Sound Forge (без VST-плагинов и DAW-микшеров) с сэмпл-точным монтажом, встроенными студийными DSP-инструментами и нативным C++20 ядром (`:wavez-core` / `libwavez.so`).

---

## 1. Окружение сборки (подтверждено на ПК)

- **Android SDK (основной):** `D:\Program Files\Android\THIS`
- **NDK:** `30.0.14904198` (полная поддержка 16 KB page size)
- **CMake:** `3.22.1`
- **Platforms:** до `android-37.2`
- **Gradle / AGP / Kotlin:** `compileSdk = 37`, `minSdk = 24`, `targetSdk = 36`, `Java 17`
- **STL:** `ANDROID_STL=c++_shared`
- **16 KB Page Size (Android 15+):** `-Wl,-z,max-page-size=16384` в `target_link_options` + `google/oboe` `>= 1.9.3 / 1.11.0`

---

## 2. Реестр зависимостей и лицензий

| Компонент в WaveZ | Источник | Назначение | Лицензия |
|---|---|---|---|
| **Google Oboe** | `github.com/google/oboe` | Low-latency AAudio/OpenSL аудио-поток для бесшовного PCM-лупа | Apache 2.0 |
| **Xiph libFLAC** | `github.com/xiph/flac` | Нативное декодирование и экспорт 16/24-bit FLAC до 0-го сэмпла | BSD-like |
| **dr_wav / dr_libs** | `github.com/mackron/dr_libs` | Однофайловое чтение/запись WAV + бинарный чанк `smpl` | Public Domain / MIT-0 |
| **SoX Resampler (`libsoxr`)** | `github.com/chirlu/soxr` | Эталонный ресемплинг строя (420.1 Гц → 440 Гц, 59/60 и т.д.) | LGPLv2.1 |
| **SoundTouch** | `github.com/stenzek/soundtouch` | Независимый time-stretch и pitch-shift (BPM без смены ключа и наоборот) | LGPLv2.1 |
| **EDL / DSP (по мотивам Audacity 3)** | Clean-room C++20 / `au3-wave-track`, `au3-effects` | `EditDecisionList`, MipMap пирамида пиков, `Repair` (±64 сэмпла), `Fade`, `Normalize` | GPLv3 (при прямом копировании `au3`) / MIT (clean-room) |

> **Юридическая заметка:** При прямом копировании кода из `audacity/au3` проект наследует GPLv3. Для закрытого релиза алгоритмы EDL, MipMap и авторегрессионного `Repair` реализуются clean-room в C++20 поверх Oboe + libFLAC + libsoxr + SoundTouch.

---

## 3. Трёхслойная архитектура

1. **Аналитика (Kotlin — `:app`, пакет `com.frightg.bpmkey`)**
   - `BpmAnalyzer`, `KeyAnalyzer`, `SigmaRegressionEngine`, `LoopPcmEngine`, `TrackPairAnalyzer`.
   - Формирует единую спецификацию сетки **`GridSpec`**: `bpm`, `beatsPerBar`, `gridOffsetSample`, `sampleRate`, `tuningCents`, `keyName`.
2. **UI Редактора (Jetpack Compose / Canvas — `:app`, пакет `com.frightg.bpmkey.wavez`)**
   - `WaveZEditorScreen.kt`: мультимасштабная стерео-осциллограмма (вплоть до точек отдельных сэмплов), динамическая сетка тактов, маркеры ротации `[M1..M4]`, осциллограф шва (`Δ %`), панель эффектов и undo/redo.
3. **Нативное ядро (C++20 / JNI — модуль `:wavez-core`, `libwavez.so`)**
   - `WaveZEngine.h / .cpp` — единый фасад ядра и хранилище PCM/EDL;
   - `WaveZPlayer.h / .cpp` — `oboe::AudioStreamDataCallback` (бесшовный переход `endSample -> startSample` и режим ротации в реальном времени);
   - `WaveZExporter.h / .cpp` — прямой экспорт в WAV (`RIFF` + `smpl` чанк) и FLAC (`libFLAC`) / OGG (`LOOPSTART`/`LOOPLENGTH`);
   - `EditDecisionList.h / .cpp` — неразрушающий список регионов (`AudioRegion`), Split/Trim/Delete/Duplicate/Rotate, стек Undo/Redo, MipMap пиков (`1:256`, `1:64`, `1:16`);
   - `WaveZDsp.h / .cpp` — `SeamlessRepair` (±64 сэмпла OLS-авторегрессия), `TrueResample` (`libsoxr`), `TimePitch` (`SoundTouch`), `Normalize`, `Fade`, `Invert`, `Reverse`;
   - `wavez_jni.cpp` — JNI-мост к `WaveZNative.kt`.

---

## 4. Структура файлов модуля `:wavez-core`

```text
wavez-core/
├── build.gradle.kts
└── src/main/
    ├── java/com/frightg/wavez/
    │   ├── WaveZNative.kt
    │   └── GridSpec.kt
    └── cpp/
        ├── CMakeLists.txt
        ├── wavez_jni.cpp
        ├── WaveZEngine.h / .cpp
        ├── WaveZPlayer.h / .cpp
        ├── WaveZExporter.h / .cpp
        ├── EditDecisionList.h / .cpp
        └── WaveZDsp.h / .cpp
```

---

## 5. План по спринтам

1. **Спринт 1 — Каркас NDK и бесшовный луп-плеер (Google Oboe):**
   - Модуль `:wavez-core`, `CMakeLists.txt` (`c++_shared`, 16 KB alignment, `FetchContent` для `google/oboe`).
   - `WaveZPlayer`: lock-free/atomic границы лупа (`atomicLoopStart`, `atomicLoopEnd`) и режим ротации (`m1..m4`), мгновенный playback прямо с телефона без рендера WAV.
2. **Спринт 2 — Сэмпл-точный декодер и прямой экспорт (`WAV + smpl` и `FLAC`):**
   - `xiph/flac` + `dr_wav.h`, 0-sample точное чтение и экспорт Обычного лупа / Ротации / 1.5x–2x микса.
   - Генератор бинарного чанка `smpl` для игровых движков и `vgmstream`.
3. **Спринт 3 — Неразрушающий граф регионов (`EDL` + Undo/Redo + MipMap):**
   - `AudioRegion`, `Split`, `Trim`, `Delete`, `Duplicate`, `Rotate`, стек `Undo/Redo`, MipMap-пирамида пиков.
4. **Спринт 4 — Полноэкранный Compose UI (`WaveZTimelineCanvas`):**
   - Кнопка `ОТКРЫТЬ В WAVEZ` в карточке трека и в Билдере.
   - Пинч-зум до точек PCM-сэмплов, линейка тактов `4/4`, `3/4`, `4:3`, магнитные маркеры + Slope-Matched Zero-Crossing.
5. **Спринт 5 — DSP-инструменты:**
   - `Seamless Repair` (±64 сэмпла), `True Resample` (`libsoxr`), `Time & Pitch` (`SoundTouch`), `Normalize`, `Fade In/Out`, `Invert`, `Reverse`.
6. **Спринт 6 — Проекты `.wzproj` и пакетный экспорт:**
   - JSON-проекты `.wzproj` и экспорт `.ogg` с `LOOPSTART` / `LOOPLENGTH`.
