#pragma once

#include "EditDecisionList.h"
#include "WaveZDsp.h"
#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

class WaveZPlayer : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    struct EnvelopePoint {
        int64_t frame = 0;
        float gain = 1.0f;
    };

    WaveZPlayer();
    ~WaveZPlayer() override;

    void loadPcmBuffer(const int16_t* samples, int64_t totalShorts, int32_t sampleRate, int32_t channels);
    void setLoopPoints(int64_t startSample, int64_t endSample);
    void setLoopEnabled(bool enabled);
    bool isLoopEnabled() const { return loopEnabled_.load(std::memory_order_relaxed); }
    void setPlaybackSpeed(float speed);
    float getPlaybackSpeed() const { return playbackSpeed_.load(std::memory_order_relaxed); }
    void setRotationMode(bool enabled, int64_t m1, int64_t m2, int64_t m3, int64_t m4);
    void seek(int64_t sampleFrame);
    bool play();
    void pause();
    void release();

    int64_t getPlayheadSample() const { return cursor_.load(std::memory_order_relaxed); }
    int64_t getTotalFrames() const { return totalFrames_.load(std::memory_order_relaxed); }
    int32_t getSampleRate() const { return sampleRate_.load(std::memory_order_relaxed); }
    int32_t getChannels() const { return channels_.load(std::memory_order_relaxed); }
    bool isPlaying() const { return playing_.load(std::memory_order_relaxed); }
    std::vector<float> getMeterLevelsDb() const;

    // Non-destructive EDL editing operations
    bool trimToSelection(int64_t selStart, int64_t selEnd);
    bool deleteSelection(int64_t selStart, int64_t selEnd);
    bool splitAt(int64_t pos);
    bool duplicateSelection(int64_t selStart, int64_t selEnd);
    bool rotateAt(int64_t splitPos);
    bool fadeInSelection(int64_t selStart, int64_t selEnd);
    bool fadeOutSelection(int64_t selStart, int64_t selEnd);
    bool invertSelection(int64_t selStart, int64_t selEnd);
    bool reverseSelection(int64_t selStart, int64_t selEnd);

    // Audacity DSP & SoundTouch operations (with unified Undo/Redo)
    bool applyRepair(int64_t selStart, int64_t selEnd);
    bool applyNormalize(int64_t selStart, int64_t selEnd, float targetDb, bool removeDc);
    bool applyAmplify(int64_t selStart, int64_t selEnd, float gainDb);
    bool applySilence(int64_t selStart, int64_t selEnd);
    bool applyChangeSpeed(double speedRatio);
    bool applySoundTouch(double tempoRatio, double pitchSemitones);
    bool generateSignal(int32_t type, double freqHz, float amplitude, double durationSec, int32_t sampleRate, int32_t channels);
    bool generateChirp(double freqStartHz, double freqEndHz, float amplitude, double durationSec, int32_t sampleRate, int32_t channels);
    bool generateRhythm(double bpm, int32_t beatsPerBar, double durationSec, int32_t sampleRate, int32_t channels);

    bool applyCompressor(int64_t selStart, int64_t selEnd, float thresholdDb, float ratio);
    bool applyLimiter(int64_t selStart, int64_t selEnd, float ceilingDb);
    bool applyFilter(int64_t selStart, int64_t selEnd, int32_t type, double cutoffHz);
    bool applyBassTreble(int64_t selStart, int64_t selEnd, float bassDb, float trebleDb);
    bool applyEcho(int64_t selStart, int64_t selEnd, float delayMs, float decay, bool wrapTail = false);
    bool applyReverb(int64_t selStart, int64_t selEnd, float roomSize, float wet, bool wrapTail = false);
    bool applyNoiseGate(int64_t selStart, int64_t selEnd, float thresholdDb, float attackMs = 5.0f, float releaseMs = 150.0f);
    bool applyPaulstretch(int64_t selStart, int64_t selEnd, double stretchFactor, double timeResolutionSec = 0.25);
    bool applyTrimDigitalSilence(float thresholdDb = -90.0f);
    int64_t findBestLoopSeam(int64_t selStart, int64_t selEnd, int64_t searchRadius);

    bool applyEqualizer5Band(int64_t selStart, int64_t selEnd, float g60, float g250, float g1k, float g4k, float g12k);
    bool applyPhaser(int64_t selStart, int64_t selEnd, float rateHz, float depth, float feedback);
    bool applyWahwah(int64_t selStart, int64_t selEnd, float lfoFreqHz, float depth, float resonance);
    bool applyDistortion(int64_t selStart, int64_t selEnd, int32_t mode, float drive, float mix);
    bool applyLoudnessNormalize(int64_t selStart, int64_t selEnd, float targetRmsDb);
    bool applyClickRemoval(int64_t selStart, int64_t selEnd, float threshold = 0.25f);
    bool applyTruncateSilence(float thresholdDb = -50.0f, float minSilenceSec = 0.25f);
    bool applyRepeatSelection(int64_t selStart, int64_t selEnd, int32_t count);
    bool generateDtmf(const char* digits, double toneSec, float amplitude, int32_t sampleRate, int32_t channels);
    std::vector<float> computeSpectrogramForTrack(int32_t trackIdx, int64_t viewStart, int64_t viewEnd, int32_t timeBins, int32_t freqBins);

    // Live 4-second Preview without touching Undo history
    bool previewEffect(int64_t selStart, int64_t selEnd, int32_t effectId, double p1, double p2, double p3, double p4);
    void stopPreview();
    bool applySmoothBoundaries(int64_t selStart, int64_t selEnd, float smoothMs);
    bool applyCrossfadeLoopSeam(int64_t selStart, int64_t selEnd, float crossfadeMs);

    bool captureNoiseProfile(int64_t selStart, int64_t selEnd);
    bool hasNoiseProfile() const;
    bool applyNoiseReduction(int64_t selStart, int64_t selEnd, float reductionDb, float sensitivity, int32_t smoothingBands, bool residueOnly);
    bool applyNotchFilter(int64_t selStart, int64_t selEnd, double freqHz, double q);
    bool applyVocalReduction(int64_t selStart, int64_t selEnd, int32_t mode, float strength, double lowCutHz, double highCutHz);
    bool applyTremolo(int64_t selStart, int64_t selEnd, int32_t waveform, float startPhaseDeg, float wetPercent, float freqHz);
    bool applySlidingStretch(double initTempoPct, double finalTempoPct, double initPitchSemi, double finalPitchSemi);
    bool applyAdjustableFade(int64_t selStart, int64_t selEnd, int32_t fadeType, float midBoostPct, float startPct, float endPct, int32_t curveType);
    bool applyAutoDuck(int32_t controlTrackIdx, float duckDb, float thresholdDb, float outerFadeSec, float innerFadeSec);
    bool generatePluck(double freqHz, double durationSec, int32_t sampleRate, int32_t channels);
    std::vector<int64_t> findSilences(float thresholdDb, float minSilenceSec);
    std::vector<int64_t> findSounds(float thresholdDb, float minSilenceSec, float minSoundSec);
    std::vector<int64_t> findBeats(float thresholdPct);
    std::vector<int64_t> findClippings(int32_t startSamples, int32_t stopSamples);
    bool applyPhantomCenter(int64_t selStart, int64_t selEnd, double hpfHz, double lpfHz, float mixPct, float outputDb);
    bool applyClipFix(int64_t selStart, int64_t selEnd, float thresholdPct, float reduceDb);
    bool applyVocoder(int64_t selStart, int64_t selEnd, int32_t bands, double carrierHz, float mixPct);
    bool applyDeEsser(int64_t selStart, int64_t selEnd, double freqHz, float thresholdDb, float ratio);
    bool applyMultibandCompressor(int64_t selStart, int64_t selEnd, double lowSplitHz, double highSplitHz, float thresholdDb, float ratio, float makeupDb);
    bool applySaturator(int64_t selStart, int64_t selEnd, int32_t mode, float driveDb, float warmth, float mixPct);
    bool generateRissetDrum(double freqHz, double decaySec, double centerNoiseHz, double durationSec, int32_t sampleRate, int32_t channels);
    int32_t estimateKey(int64_t selStart, int64_t selEnd);
    bool applyBitcrusher(int64_t selStart, int64_t selEnd, float bitDepth, int32_t downsampleFactor, float mixPct);

    // Multi-track management
    int32_t getTrackCount() const;
    int32_t getActiveTrackIndex() const;
    bool selectTrack(int32_t index);
    bool addTrackPcmBuffer(const int16_t* samples, int64_t totalShorts, int32_t sampleRate, int32_t channels);
    bool duplicateActiveTrack();
    bool removeTrack(int32_t index);
    bool mixDownTracks();
    void setTrackMixAt(int32_t index, float gain, float pan, bool muted, bool solo);
    bool setTrackOffset(int32_t index, int64_t offsetFrames, bool commit = true);
    int64_t getTrackOffset(int32_t index) const;
    bool setTrackEnvelope(int32_t index, const std::vector<int64_t>& frames, const std::vector<float>& gains, bool commit = true);
    std::vector<float> getTrackEnvelope(int32_t index) const;
    bool splitStereoToMono(int32_t index);
    bool swapStereoChannels(int32_t index);
    bool splitCutSelection(int64_t selStart, int64_t selEnd);
    bool splitDeleteSelection(int64_t selStart, int64_t selEnd);
    std::vector<float> computeStereoPeaksForTrack(int32_t trackIdx, int64_t viewStart, int64_t viewEnd, int32_t bins);
    std::vector<float> getRawSamplesForTrack(int32_t trackIdx, int64_t viewStart, int64_t viewEnd);
    int64_t getTrackTotalFrames(int32_t trackIdx) const;

    bool copySelection(int64_t selStart, int64_t selEnd);
    bool cutSelection(int64_t selStart, int64_t selEnd);
    bool pasteAt(int64_t pos);
    bool hasClipboard() const;

    void setTrackMix(float gain, float pan, bool muted);
    std::vector<float> computeSpectrum(int64_t selStart, int64_t selEnd, int32_t bins);
    double estimateBpm(int64_t selStart, int64_t selEnd);

    int64_t findNearestZeroCrossing(int64_t targetFrame, int32_t requiredSlope = 0);
    int32_t getZeroCrossingSlope(int64_t frame);
    AudioStats analyzeSelection(int64_t selStart, int64_t selEnd);
    std::vector<int64_t> exportEdlState() const;
    bool importEdlState(const std::vector<int64_t>& data);

    bool undo();
    bool redo();
    bool canUndo() const;
    bool canRedo() const;
    std::vector<float> computeWaveformPeaks(int64_t viewStart, int64_t viewEnd, int32_t bins);
    std::vector<float> computeStereoPeaks(int64_t viewStart, int64_t viewEnd, int32_t bins);
    std::vector<float> getRawSamples(int64_t viewStart, int64_t viewEnd);

    bool exportWav(int fd, int64_t startSample, int64_t endSample, bool rotationEnabled, int64_t m2, int64_t m3, int64_t m4, bool writeSmplChunk, int32_t bitsPerSample = 16);
    bool exportFlac(int fd, int64_t startSample, int64_t endSample, bool rotationEnabled, int64_t m2, int64_t m3, int64_t m4, int32_t bitsPerSample = 16);
    bool exportAiff(int fd, int64_t startSample, int64_t endSample, bool rotationEnabled, int64_t m2, int64_t m3, int64_t m4);
    std::vector<int16_t> renderOutputShorts(int64_t startSample, int64_t endSample, bool rotationEnabled, int64_t m2, int64_t m3, int64_t m4);

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result error) override;

private:
    struct PcmBuffer {
        std::vector<int16_t> samples;
        int32_t sampleRate = 44100;
        int32_t channels = 2;
        int64_t totalFrames = 0;
    };

    struct TrackState {
        std::shared_ptr<const PcmBuffer> buffer;
        EditDecisionList edl;
        float gain = 1.0f;
        float pan = 0.0f;
        bool muted = false;
        bool solo = false;
        int64_t offsetFrames = 0;
        std::vector<EnvelopePoint> envelope;
    };

    struct HistorySnapshot {
        std::vector<TrackState> tracks;
        int32_t activeTrack = 0;
    };

    TrackState* activeTrackLocked();
    const TrackState* activeTrackLocked() const;
    void pushHistoryLocked();
    std::vector<int16_t> flattenTrackLocked(const TrackState& trk, int64_t& outFrames, int32_t& outSampleRate, int32_t& outChannels) const;
    std::vector<int16_t> flattenTimelineLocked(int64_t& outFrames, int32_t& outSampleRate, int32_t& outChannels) const;
    std::vector<int16_t> mixAllTracksLocked(int64_t& outFrames, int32_t& outSampleRate, int32_t& outChannels) const;
    void syncTimelineBoundsLocked();
    bool openStream(int32_t sampleRate, int32_t channels);
    void closeStream();

    std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;

    mutable std::mutex bufferMutex_;
    std::vector<TrackState> tracks_;
    int32_t activeTrackIndex_{0};
    std::vector<HistorySnapshot> historyUndo_;
    std::vector<HistorySnapshot> historyRedo_;

    std::atomic<bool> playing_{false};
    std::atomic<int64_t> cursor_{0};
    std::atomic<int64_t> totalFrames_{0};
    std::atomic<int32_t> sampleRate_{44100};
    std::atomic<int32_t> channels_{2};
    std::atomic<float> meterLeftDb_{-96.0f};
    std::atomic<float> meterRightDb_{-96.0f};
    std::atomic<float> trackGain_{1.0f};
    std::atomic<float> trackPan_{0.0f};
    std::atomic<bool> trackMuted_{false};

    std::vector<int16_t> clipboardSamples_;
    int32_t clipboardChannels_{2};
    std::vector<float> noiseProfile_;

    std::atomic<bool> previewActive_{false};
    std::vector<int16_t> previewSamples_;
    int32_t previewChannels_{2};
    int64_t previewCursor_{0};

    // Standard loop boundaries (in frames)
    std::atomic<bool> loopEnabled_{false};
    std::atomic<int64_t> loopStart_{0};
    std::atomic<int64_t> loopEnd_{0};
    std::atomic<float> playbackSpeed_{1.0f};
    double speedFrac_{0.0};

    // Rotation mode: plays [m3..m4) (head) then [m2..m3) (body) seamlessly
    std::atomic<bool> rotationEnabled_{false};
    std::atomic<int64_t> m1_{0};
    std::atomic<int64_t> m2_{0};
    std::atomic<int64_t> m3_{0};
    std::atomic<int64_t> m4_{0};
};
