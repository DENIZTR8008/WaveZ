#pragma once

#include <cstdint>
#include <vector>

struct AudioStats {
    float peakLeftDb = -96.0f;
    float peakRightDb = -96.0f;
    float rmsLeftDb = -96.0f;
    float rmsRightDb = -96.0f;
    int64_t clippedSamples = 0;
    float seamDeltaPercent = 0.0f;
};

namespace WaveZDsp {

/**
 * Audacity 3.7.9 InterpolateAudio (Least Squares AutoRegression) Repair.
 * Interpolates [badStart .. badStart + badLen) in-place per channel using
 * surrounding context. Clamps badLen to [1 .. 128].
 */
bool repairSamplesLSAR(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t totalFrames,
    int64_t badStart,
    int64_t badLen
);

/**
 * Normalizes [selStart .. selEnd) to targetPeakDb (e.g. -1.0f dB),
 * optionally removing DC offset first.
 */
void normalize(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float targetPeakDb,
    bool removeDcOffset
);

/**
 * Applies constant gain in dB to [selStart .. selEnd).
 */
void amplify(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float gainDb
);

/**
 * Silences [selStart .. selEnd).
 */
void silence(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd
);

/**
 * High-quality cubic Hermite resampling for Change Speed & Pitch.
 * speedRatio > 1.0 speeds up (shorter, higher pitch); < 1.0 slows down.
 */
std::vector<int16_t> changeSpeed(
    const int16_t* src,
    int64_t srcFrames,
    int32_t channels,
    double speedRatio
);

/**
 * Independent Time-Stretch & Pitch-Shift via SoundTouch.
 * tempoRatio: e.g. 1.0 = unchanged, 1.2 = +20% faster without pitch change.
 * pitchSemitones: e.g. 0.0 = unchanged, -2.0 = 2 semitones down without tempo change.
 */
std::vector<int16_t> processSoundTouch(
    const int16_t* src,
    int64_t srcFrames,
    int32_t sampleRate,
    int32_t channels,
    double tempoRatio,
    double pitchSemitones
);

/**
 * Generates waveform: type 0 = Sine, 1 = Square, 2 = Sawtooth, 3 = White Noise.
 */
std::vector<int16_t> generateSignal(
    int32_t type,
    double freqHz,
    float amplitude,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
);

/**
 * Finds nearest zero-crossing frame within +/- maxRadius frames.
 * requiredSlope: 0 = any slope, +1 = rising (- -> +), -1 = falling (+ -> -).
 * Checks both stereo channels for zero-crossing alignment.
 */
int64_t findNearestZeroCrossing(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t targetFrame,
    int32_t requiredSlope = 0,
    int64_t maxRadius = 2048
);

/**
 * Returns +1 if rising at frame, -1 if falling, 0 otherwise.
 */
int32_t getZeroCrossingSlope(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t frame
);

/**
 * Computes Peak/RMS in dB, clipped sample count, and seam discontinuity (%).
 */
AudioStats analyzeRange(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd
);

void compressor(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float thresholdDb,
    float ratio,
    int32_t sampleRate
);

void limiter(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float ceilingDb
);

void biquadFilter(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    int32_t type, // 0 = LowPass, 1 = HighPass
    double cutoffHz,
    double q = 0.707
);

void bassTreble(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float bassDb,
    float trebleDb
);

void echo(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float delayMs,
    float decay,
    bool wrapTail = false
);

void reverb(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float roomSize,
    float wet,
    bool wrapTail = false
);

void noiseGate(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float thresholdDb,
    float attackMs = 5.0f,
    float releaseMs = 150.0f
);

/**
 * Paulstretch ambient time-stretching using Hann-windowed spectral phase randomization.
 */
std::vector<int16_t> paulstretch(
    const int16_t* src,
    int64_t srcFrames,
    int32_t sampleRate,
    int32_t channels,
    double stretchFactor,
    double timeResolutionSec = 0.25
);

/**
 * Finds the best loop end frame near selEnd by cross-correlating the tail
 * with the head at selStart (minimizing discontinuity).
 */
int64_t findBestLoopSeam(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int64_t searchRadius
);

std::vector<int16_t> generateChirp(
    double freqStartHz,
    double freqEndHz,
    float amplitude,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
);

std::vector<int16_t> generateRhythmTrack(
    double bpm,
    int32_t beatsPerBar,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
);

std::vector<float> computeSpectrumDb(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t bins = 64
);

double estimateBpm(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate
);

/**
 * Smooths start and end boundaries of [selStart .. selEnd) with a cosine micro-fade
 * of smoothFrames samples to eliminate clicks at cut/loop edges.
 */
void smoothBoundaries(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int64_t smoothFrames
);

/**
 * Equal-power cosine crossfade across the loop seam between end of [selStart .. selEnd)
 * and start of [selStart .. selEnd) over crossfadeFrames samples.
 */
void crossfadeLoopSeam(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int64_t crossfadeFrames
);

void equalizer5Band(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    const float gainsDb[5]
);

void phaser(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float rateHz,
    float depth,
    float feedback
);

void wahwah(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float lfoFreqHz,
    float depth,
    float resonance
);

void distortion(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t mode,
    float drive,
    float mix
);

void loudnessNormalize(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float targetRmsDb
);

void clickRemoval(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float threshold
);

std::vector<int16_t> truncateSilence(
    const int16_t* src,
    int64_t srcFrames,
    int32_t sampleRate,
    int32_t channels,
    float thresholdDb,
    float minSilenceSec
);

std::vector<int16_t> generateDtmf(
    const char* digits,
    double toneSec,
    float amplitude,
    int32_t sampleRate,
    int32_t channels
);

std::vector<float> computeSpectrogram(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t viewStart,
    int64_t viewEnd,
    int32_t timeBins = 96,
    int32_t freqBins = 24
);

std::vector<float> captureNoiseProfile(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd
);

void applyNoiseReduction(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    const std::vector<float>& profile,
    float reductionDb,
    float sensitivity,
    int32_t smoothingBands,
    bool residueOnly
);

void notchFilter(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    double freqHz,
    double q
);

void vocalReduction(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    int32_t mode,
    float strength,
    double lowCutHz,
    double highCutHz
);

void tremolo(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    int32_t waveform,
    float startPhaseDeg,
    float wetPercent,
    float freqHz
);

std::vector<int16_t> slidingStretch(
    const int16_t* src,
    int64_t srcFrames,
    int32_t sampleRate,
    int32_t channels,
    double initTempoPct,
    double finalTempoPct,
    double initPitchSemi,
    double finalPitchSemi
);

void adjustableFade(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t fadeType,
    float midBoostPct,
    float startPct,
    float endPct,
    int32_t curveType
);

void autoDuck(
    std::vector<int16_t>& target,
    int32_t targetCh,
    const int16_t* control,
    int64_t controlFrames,
    int32_t controlCh,
    int32_t sampleRate,
    float duckDb,
    float thresholdDb,
    float outerFadeSec,
    float innerFadeSec
);

std::vector<int16_t> generatePluck(
    double freqHz,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
);

std::vector<int64_t> findSilences(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int32_t sampleRate,
    float thresholdDb,
    float minSilenceSec
);

std::vector<int64_t> findSounds(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int32_t sampleRate,
    float thresholdDb,
    float minSilenceSec,
    float minSoundSec
);

std::vector<int64_t> findBeats(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int32_t sampleRate,
    float thresholdPct
);

std::vector<int64_t> findClippings(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int32_t startSamples,
    int32_t stopSamples
);

void phantomCenter(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    double hpfHz,
    double lpfHz,
    float mixPct,
    float outputDb
);

void clipFix(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float thresholdPct = 95.0f,
    float reduceDb = -3.0f
);

void vocoder(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    int32_t bands = 16,
    double carrierHz = 120.0,
    float mixPct = 100.0f
);

void deEsser(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    double freqHz = 6500.0,
    float thresholdDb = -22.0f,
    float ratio = 4.0f
);

void multibandCompressor(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    double lowSplitHz = 200.0,
    double highSplitHz = 3000.0,
    float thresholdDb = -18.0f,
    float ratio = 3.5f,
    float makeupDb = 2.0f
);

void saturator(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    int32_t mode = 0, // 0=Tape, 1=Tube
    float driveDb = 6.0f,
    float warmth = 0.5f,
    float mixPct = 100.0f
);

std::vector<int16_t> generateRissetDrum(
    double freqHz,
    double decaySec,
    double centerNoiseHz,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
);

int32_t estimateKey(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate
);

void bitcrusher(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float bitDepth = 8.0f,
    int32_t downsampleFactor = 4,
    float mixPct = 100.0f
);

} // namespace WaveZDsp
