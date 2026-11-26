# WaveZ Audio Editor

**WaveZ** (`com.frightg.wavez`) — мобильный многодорожечный аудиоредактор для Android в духе Audacity с нативным C++20/NDK аудиоядром (`:wavez-core`).

> Текущая версия: **`0.1.0-alpha`** (`versionCode = 1`) · См. [CHANGELOG.md](CHANGELOG.md), [WAVEZ_GUIDE.md](WAVEZ_GUIDE.md), [WAVEZ_ARCHITECTURE.md](WAVEZ_ARCHITECTURE.md)

---

## Возможности

- **Нативное аудиоядро (`:wavez-core` / C++20):**
  - Low-latency воспроизведение и бесшовный луп на базе **Google Oboe** (`1.9.3`) с живым стерео dB-метром и режимом ротации `[M1..M4]`.
  - Неразрушающий монтаж (`EditDecisionList` + MipMap пиков + `Undo / Redo`): `Cut / Copy / Paste / Trim / Delete / Duplicate / Split / Silence / Zero-Crossing`.
  - Запись с микрофона и **Overdub** поверх проекта.
- **Многодорожечность и таймлайн:**
  - Панель дорожки (`Mute`, `Solo`, `Gain 0–200%`, `Pan L/R`, `Rotation`), `Time Shift`, `Envelope` (огибающая громкости).
  - Метки (`LabelTrackBar`) и `Export Multiple`, вертикальный зум, `Show Clipping`, dB-шкала волны, настраиваемая сетка BPM.
- **Студийный DSP и анализ (Clean-Room + Open Source):**
  - **Динамика:** `Amplify`, `Normalize`, `Loudness (-16 LUFS)`, `Compressor`, `3-Band Multiband Comp`, `Limiter`, `De-Esser`, `Noise Gate`, `Auto Duck`, `Fade In/Out`, `Adjustable Fade`.
  - **Тон и время:** `Kaiser Sinc` Speed/Pitch, `SoundTouch` (независимые темп и тональность), `Sliding Stretch`, `PaulStretch`, скорость воспроизведения от `0.01x+`.
  - **Частоты и пространство:** `5-Band EQ`, `HPF/LPF`, `Notch`, `Bass & Treble`, `Phantom Center` (LR4 Mid/Side кроссовер), `Vocal Reduction`, `Tape/Tube Saturator`, `Echo` (BPM Sync + Wrap Tail), `Reverb` (Wrap Tail), `Vocoder`, `Phaser`, `Wahwah`, `Tremolo`, `Distortion`, `Bitcrusher`.
  - **Реставрация и анализ:** `Noise Reduction` (STFT), `Clip Fix`, `LSAR Repair`, `Auto-Seam`, `Crossfade Loop Seam`, `Click Removal`, `Truncate Silence`, `FFT Спектр`, `Спектрограмма`, `Silence/Sound/Beat/Clipping/Key/BPM Finders`, `Effect Rack` и свободный ввод любых чисел (`FreeNumberSlider`).
- **Интерфейс:**
  - **Material Design 3 Expressive** и классическая **Studio**-тема (тёмная/светлая).
  - Адаптивная и монохромная иконка (Material You), а также стартовая заставка в цветах темы.

---

## Стек и зависимости

- **UI:** Kotlin, Jetpack Compose, Material 3
- **Нативное ядро:** C++20, Android NDK (`30.0.14904198`), CMake (`16 KB` page size alignment)
- **Библиотеки:** Google Oboe (`Apache-2.0`), SoundTouch (`LGPL`), libFLAC (`BSD`), Android `MediaCodec` / `MediaExtractor`

---

## Сборка

```powershell
.\gradlew.bat assembleRelease --console=plain
```

- `minSdk = 24`, `targetSdk = 36`, `compileSdk = 37`
- ABI: `arm64-v8a`, `armeabi-v7a`, `x86_64`
