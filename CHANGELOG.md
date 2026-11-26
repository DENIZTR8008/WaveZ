# Changelog — WaveZ Audio Editor

Хронологическая история версий **WaveZ** (`com.frightg.wavez`), восстановленная по фактическим итерациям разработки.
*(В `app/build.gradle.kts` на текущий момент зафиксирован общий `versionName = "0.1.0-alpha"`, `versionCode = 1`.)*

---

## Итерация 7 (текущая) — Брендинг, интро-анимация и чистый старт (2026-11-26, вечер)
- **Иконка приложения:** перегенерированы адаптивные векторные (`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` для Material You) и растровые `mipmap-*` иконки в стиле скруглённой спектральной **Z**.
- **Стартовая заставка (`WaveZIntroOverlay`):** единоразовая за сессию анимация из 23 скруглённых спектральных полос в форме **Z** без курсора-синусоиды, берущая цвета из текущей темы (`MaterialTheme.colorScheme` / `LocalWaveZPalette`), длительность `2600` мс.
- **Чистый старт и Справка:** убрана центральная стартовая карточка при пустом проекте; краткий гайд по жестам и инструментам перенесён в диалог **«Справка»** (`AboutSummaryDialog`).

## Итерация 6 — Clean-Room плагины, Bitcrusher и Справка (2026-11-26, день)
- **Новые DSP-эффекты (Clean-Room C++20):** `Phantom Center` (LR4 Mid/Side кроссовер + Output Gain), `Clip Fix`, `Vocoder`, `De-Esser`, `3-Band Multiband Comp`, `Tape/Tube Saturator`.
- **Отдельный Bitcrusher:** вынесен самостоятельный диалог `BitcrusherDialog` (`Bit Depth + Downsample + Mix`).
- **Скорость воспроизведения:** подтверждён/открыт свободный ввод скорости от `0.01x+`.
- **Справка (`AboutSummaryDialog`):** добавлена сводка по использованному стеку, библиотекам и алгоритмам.

## Итерация 5 — Material 3 Expressive редизайн (2026-11-26, утро)
- Переработан визуальный стиль под **Material 3 Expressive**: `ElevatedCard`, `SegmentedButton`, плавающий транспорт-док, морфинг-кнопка Play/Pause, более плавная мобильная компоновка.

## Итерация 4 — Полный функционал Audacity и свободный ввод чисел (2026-11-26, ночь–утро)
- **Многодорожечный монтаж и запись:** запись с микрофона + **Overdub**, **Метки (`LabelTrackBar`) + `Export Multiple`**, **Time Shift**, **Envelope**, вертикальный зум, **Show Clipping**, **dB-шкала** волны.
- **Расширенный DSP и анализ:** `Noise Reduction` (STFT по профилю шума), `Notch`, `Vocal Reduction`, `Tremolo`, `Sliding Stretch`, `Adjustable Fade`, `Auto Duck`, `Pluck`, `Pink/Brown Noise`, `Risset Drum`, `Silence/Sound/Beat/Clipping/Key Finders`, `Effect Rack`.
- **Свободный ввод без границ:** внедрён `FreeNumberSlider` во всех диалогах (ввод любых чисел вручную, расширенный сдвиг тона до `±1200` центов и выше).

## Итерация 3 — Исправление DSP, кастомный снап и темы (2026-11-25, вечер)
- Исправлены **Инверсия** и **Bitcrusher** (`Bit Depth + Downsample`).
- Убраны захардкоженные пресеты (`59/60` и др.), добавлен кастомный **Snap**, улучшена мобильная компоновка с быстрыми кнопками **Undo / Redo**.
- Добавлены переключаемые темы: **Тёмная / Светлая** и **Studio / Material Design 3**.

## Итерация 2 — Интерфейс в стиле Audacity, жесты и руководство (2026-11-25, день)
- Исправлено сохранение состояния при повороте экрана.
- Добавлены границы сглаживания (микро-фейд, кроссфейд шва), многодорожечный TCP (`M`, `S`, `R`, Gain, Pan), горячие клавиши ПК.
- Исправлен курсор по спектру, `Shift+L`, горизонтальный скролл кнопок в диалогах и выбор лупа пальцем.
- Добавлена настраиваемая сетка (BPM, размер такта), расширены форматы импорта, исправлена контрастность текста темы.
- Написано руководство пользователя [WAVEZ_GUIDE.md](WAVEZ_GUIDE.md).

## Итерация 1 — Запуск проекта WaveZ и NDK C++20 ядро (2026-11-25, старт)
- Создан модуль `:wavez-core` (`libwavez.so`) на C++20 с 16 KB Page Size alignment и JNI-мостом (`wavez_jni.cpp` ↔ `WaveZNative.kt`).
- Реализованы: low-latency плеер на **Google Oboe**, декодер/экспорт (`WAV + smpl`, `libFLAC`, `MediaCodec`), `EditDecisionList` (`EDL` + MipMap + `Undo/Redo`) и базовый набор эффектов (`Repair LSAR`, `Normalize`, `Amplify`, `Compressor`, `EQ`, `Echo`, `Reverb`, `Kaiser Sinc` / `SoundTouch`).
