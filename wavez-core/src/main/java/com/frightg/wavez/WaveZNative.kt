package com.frightg.wavez

object WaveZNative {
    init {
        System.loadLibrary("wavez")
    }

    fun loadPcmBuffer(samples: ShortArray, sampleRate: Int, channels: Int) {
        nativeLoadPcmBuffer(samples, sampleRate, channels)
    }

    fun setLoopPoints(startSample: Long, endSample: Long) {
        nativeSetLoopPoints(startSample, endSample)
    }

    fun setLoopEnabled(enabled: Boolean) {
        nativeSetLoopEnabled(enabled)
    }

    fun setRotationMode(enabled: Boolean, m1: Long, m2: Long, m3: Long, m4: Long) {
        nativeSetRotationMode(enabled, m1, m2, m3, m4)
    }

    fun seek(sampleFrame: Long) {
        nativeSeek(sampleFrame)
    }

    fun play(): Boolean = nativePlay()

    fun pause() {
        nativePause()
    }

    fun release() {
        nativeRelease()
    }

    fun getPlayheadSample(): Long = nativeGetPlayheadSample()

    fun isPlaying(): Boolean = nativeIsPlaying()

    fun exportWavToFd(
        fd: Int,
        startSample: Long,
        endSample: Long,
        rotationEnabled: Boolean,
        m2: Long,
        m3: Long,
        m4: Long,
        writeSmplChunk: Boolean = true,
        bitsPerSample: Int = 16
    ): Boolean = nativeExportWavToFd(fd, startSample, endSample, rotationEnabled, m2, m3, m4, writeSmplChunk, bitsPerSample)

    fun exportAiffToFd(
        fd: Int,
        startSample: Long,
        endSample: Long,
        rotationEnabled: Boolean,
        m2: Long,
        m3: Long,
        m4: Long
    ): Boolean = nativeExportAiffToFd(fd, startSample, endSample, rotationEnabled, m2, m3, m4)

    fun renderOutputShorts(
        startSample: Long,
        endSample: Long,
        rotationEnabled: Boolean,
        m2: Long,
        m3: Long,
        m4: Long
    ): ShortArray = nativeRenderOutputShorts(startSample, endSample, rotationEnabled, m2, m3, m4)

    fun exportFlacToFd(
        fd: Int,
        startSample: Long,
        endSample: Long,
        rotationEnabled: Boolean,
        m2: Long,
        m3: Long,
        m4: Long,
        bitsPerSample: Int = 16
    ): Boolean = nativeExportFlacToFd(fd, startSample, endSample, rotationEnabled, m2, m3, m4, bitsPerSample)

    fun getTotalFrames(): Long = nativeGetTotalFrames()
    fun trimToSelection(startSample: Long, endSample: Long): Boolean = nativeTrim(startSample, endSample)
    fun deleteSelection(startSample: Long, endSample: Long): Boolean = nativeDelete(startSample, endSample)
    fun splitAt(pos: Long): Boolean = nativeSplit(pos)
    fun duplicateSelection(startSample: Long, endSample: Long): Boolean = nativeDuplicate(startSample, endSample)
    fun rotateAt(pos: Long): Boolean = nativeRotate(pos)
    fun fadeInSelection(startSample: Long, endSample: Long): Boolean = nativeFadeIn(startSample, endSample)
    fun fadeOutSelection(startSample: Long, endSample: Long): Boolean = nativeFadeOut(startSample, endSample)
    fun invertSelection(startSample: Long, endSample: Long): Boolean = nativeInvert(startSample, endSample)
    fun reverseSelection(startSample: Long, endSample: Long): Boolean = nativeReverse(startSample, endSample)
    fun undo(): Boolean = nativeUndo()
    fun redo(): Boolean = nativeRedo()
    fun canUndo(): Boolean = nativeCanUndo()
    fun canRedo(): Boolean = nativeCanRedo()
    fun computePeaks(viewStart: Long, viewEnd: Long, bins: Int = 240): FloatArray = nativeComputePeaks(viewStart, viewEnd, bins)
    fun computeStereoPeaks(viewStart: Long, viewEnd: Long, bins: Int = 360): FloatArray = nativeComputeStereoPeaks(viewStart, viewEnd, bins)
    fun getRawSamples(viewStart: Long, viewEnd: Long): FloatArray = nativeGetRawSamples(viewStart, viewEnd)
    fun getMeterLevelsDb(): FloatArray = nativeGetMeterLevelsDb()
    fun getSampleRate(): Int = nativeGetSampleRate()
    fun getChannels(): Int = nativeGetChannels()

    fun repairSelection(startSample: Long, endSample: Long): Boolean = nativeRepair(startSample, endSample)
    fun normalizeSelection(startSample: Long, endSample: Long, targetDb: Float = -1.0f, removeDc: Boolean = true): Boolean =
        nativeNormalize(startSample, endSample, targetDb, removeDc)
    fun amplifySelection(startSample: Long, endSample: Long, gainDb: Float): Boolean = nativeAmplify(startSample, endSample, gainDb)
    fun silenceSelection(startSample: Long, endSample: Long): Boolean = nativeSilence(startSample, endSample)
    fun changeSpeed(speedRatio: Double): Boolean = nativeChangeSpeed(speedRatio)
    fun soundTouch(tempoRatio: Double, pitchSemitones: Double): Boolean = nativeSoundTouch(tempoRatio, pitchSemitones)
    fun generateSignal(type: Int, freqHz: Double, amplitude: Float, durationSec: Double, sampleRate: Int = 44100, channels: Int = 2): Boolean =
        nativeGenerateSignal(type, freqHz, amplitude, durationSec, sampleRate, channels)
    fun findZeroCrossing(targetFrame: Long, requiredSlope: Int = 0): Long = nativeFindZeroCrossing(targetFrame, requiredSlope)
    fun getZeroCrossingSlope(frame: Long): Int = nativeGetZeroCrossingSlope(frame)
    fun exportEdlState(): LongArray = nativeExportEdlState()
    fun importEdlState(data: LongArray): Boolean = nativeImportEdlState(data)
    fun analyzeSelection(startSample: Long, endSample: Long): FloatArray = nativeAnalyzeSelection(startSample, endSample)

    fun generateChirp(f1: Double, f2: Double, amp: Float, durSec: Double, sr: Int = 44100, ch: Int = 2): Boolean =
        nativeGenerateChirp(f1, f2, amp, durSec, sr, ch)
    fun generateRhythm(bpm: Double, beatsPerBar: Int, durSec: Double, sr: Int = 44100, ch: Int = 2): Boolean =
        nativeGenerateRhythm(bpm, beatsPerBar, durSec, sr, ch)

    fun compressor(startSample: Long, endSample: Long, thresholdDb: Float = -14f, ratio: Float = 4f): Boolean =
        nativeCompressor(startSample, endSample, thresholdDb, ratio)
    fun limiter(startSample: Long, endSample: Long, ceilingDb: Float = -0.5f): Boolean =
        nativeLimiter(startSample, endSample, ceilingDb)
    fun filter(startSample: Long, endSample: Long, type: Int, cutoffHz: Double): Boolean =
        nativeFilter(startSample, endSample, type, cutoffHz)
    fun bassTreble(startSample: Long, endSample: Long, bassDb: Float, trebleDb: Float): Boolean =
        nativeBassTreble(startSample, endSample, bassDb, trebleDb)
    fun echo(startSample: Long, endSample: Long, delayMs: Float = 250f, decay: Float = 0.45f, wrapTail: Boolean = false): Boolean =
        nativeEcho(startSample, endSample, delayMs, decay, wrapTail)
    fun reverb(startSample: Long, endSample: Long, roomSize: Float = 0.55f, wet: Float = 0.35f, wrapTail: Boolean = false): Boolean =
        nativeReverb(startSample, endSample, roomSize, wet, wrapTail)
    fun noiseGate(startSample: Long, endSample: Long, thresholdDb: Float = -42f, attackMs: Float = 5f, releaseMs: Float = 150f): Boolean =
        nativeNoiseGate(startSample, endSample, thresholdDb, attackMs, releaseMs)
    fun paulstretch(startSample: Long, endSample: Long, stretchFactor: Double = 4.0, timeResolutionSec: Double = 0.25): Boolean =
        nativePaulstretch(startSample, endSample, stretchFactor, timeResolutionSec)
    fun trimSilence(thresholdDb: Float = -90f): Boolean = nativeTrimSilence(thresholdDb)
    fun findBestLoopSeam(startSample: Long, endSample: Long, searchRadius: Long = 8192L): Long =
        nativeFindBestLoopSeam(startSample, endSample, searchRadius)
    fun previewEffect(startSample: Long, endSample: Long, effectId: Int, p1: Double = 0.0, p2: Double = 0.0, p3: Double = 0.0, p4: Double = 0.0): Boolean =
        nativePreviewEffect(startSample, endSample, effectId, p1, p2, p3, p4)
    fun stopPreview() = nativeStopPreview()
    fun equalizer5Band(startSample: Long, endSample: Long, g60: Float, g250: Float, g1k: Float, g4k: Float, g12k: Float): Boolean =
        nativeEq5(startSample, endSample, g60, g250, g1k, g4k, g12k)
    fun phaser(startSample: Long, endSample: Long, rateHz: Float, depth: Float, feedback: Float): Boolean =
        nativePhaser(startSample, endSample, rateHz, depth, feedback)
    fun wahwah(startSample: Long, endSample: Long, lfoFreqHz: Float, depth: Float, resonance: Float): Boolean =
        nativeWahwah(startSample, endSample, lfoFreqHz, depth, resonance)
    fun distortion(startSample: Long, endSample: Long, mode: Int, drive: Float, mix: Float): Boolean =
        nativeDistortion(startSample, endSample, mode, drive, mix)
    fun loudnessNormalize(startSample: Long, endSample: Long, targetRmsDb: Float = -16f): Boolean =
        nativeLoudnessNorm(startSample, endSample, targetRmsDb)
    fun clickRemoval(startSample: Long, endSample: Long, threshold: Float = 0.25f): Boolean =
        nativeClickRemoval(startSample, endSample, threshold)
    fun truncateSilence(thresholdDb: Float = -50f, minSilenceSec: Float = 0.25f): Boolean =
        nativeTruncateSilence(thresholdDb, minSilenceSec)
    fun repeatSelection(startSample: Long, endSample: Long, count: Int): Boolean =
        nativeRepeatSelection(startSample, endSample, count)
    fun generateDtmf(digits: String, toneSec: Double = 0.15, amplitude: Float = 0.6f, sr: Int = 44100, ch: Int = 2): Boolean =
        nativeGenerateDtmf(digits, toneSec, amplitude, sr, ch)
    fun computeSpectrogram(trackIdx: Int, viewStart: Long, viewEnd: Long, timeBins: Int = 96, freqBins: Int = 24): FloatArray =
        nativeComputeSpectrogram(trackIdx, viewStart, viewEnd, timeBins, freqBins)

    fun copySelection(startSample: Long, endSample: Long): Boolean = nativeCopy(startSample, endSample)
    fun cutSelection(startSample: Long, endSample: Long): Boolean = nativeCut(startSample, endSample)
    fun pasteAt(pos: Long): Boolean = nativePaste(pos)
    fun hasClipboard(): Boolean = nativeHasClipboard()
    fun setTrackMix(gain: Float, pan: Float, muted: Boolean) = nativeSetTrackMix(gain, pan, muted)
    fun computeSpectrum(startSample: Long, endSample: Long, bins: Int = 64): FloatArray = nativeComputeSpectrum(startSample, endSample, bins)
    fun estimateBpm(startSample: Long, endSample: Long): Double = nativeEstimateBpm(startSample, endSample)
    fun smoothBoundaries(startSample: Long, endSample: Long, smoothMs: Float = 15f): Boolean = nativeSmoothBoundaries(startSample, endSample, smoothMs)
    fun crossfadeLoopSeam(startSample: Long, endSample: Long, crossfadeMs: Float = 25f): Boolean = nativeCrossfadeLoopSeam(startSample, endSample, crossfadeMs)

    fun getTrackCount(): Int = nativeGetTrackCount()
    fun getActiveTrackIndex(): Int = nativeGetActiveTrackIndex()
    fun selectTrack(index: Int): Boolean = nativeSelectTrack(index)
    fun addTrackPcm(samples: ShortArray, sampleRate: Int, channels: Int): Boolean = nativeAddTrackPcm(samples, sampleRate, channels)
    fun duplicateTrack(): Boolean = nativeDuplicateTrack()
    fun removeTrack(index: Int): Boolean = nativeRemoveTrack(index)
    fun mixDownTracks(): Boolean = nativeMixDownTracks()
    fun setTrackMixAt(index: Int, gain: Float, pan: Float, muted: Boolean, solo: Boolean) = nativeSetTrackMixAt(index, gain, pan, muted, solo)
    fun computeStereoPeaksForTrack(trackIdx: Int, viewStart: Long, viewEnd: Long, bins: Int = 360): FloatArray =
        nativeComputeStereoPeaksForTrack(trackIdx, viewStart, viewEnd, bins)
    fun getRawSamplesForTrack(trackIdx: Int, viewStart: Long, viewEnd: Long): FloatArray =
        nativeGetRawSamplesForTrack(trackIdx, viewStart, viewEnd)
    fun setPlaybackSpeed(speed: Float) = nativeSetPlaybackSpeed(speed)
    fun getPlaybackSpeed(): Float = nativeGetPlaybackSpeed()
    fun setTrackOffset(index: Int, offsetFrames: Long, commit: Boolean = true): Boolean = nativeSetTrackOffset(index, offsetFrames, commit)
    fun getTrackOffset(index: Int): Long = nativeGetTrackOffset(index)
    fun setTrackEnvelope(index: Int, frames: LongArray, gains: FloatArray, commit: Boolean = true): Boolean = nativeSetTrackEnvelope(index, frames, gains, commit)
    fun getTrackEnvelope(index: Int): List<Pair<Long, Float>> {
        val raw = nativeGetTrackEnvelope(index)
        val out = ArrayList<Pair<Long, Float>>(raw.size / 2)
        var i = 0
        while (i + 1 < raw.size) {
            out.add(raw[i].toLong() to raw[i + 1])
            i += 2
        }
        return out
    }
    fun splitStereoToMono(index: Int): Boolean = nativeSplitStereoToMono(index)
    fun swapStereoChannels(index: Int): Boolean = nativeSwapStereoChannels(index)
    fun splitCutSelection(startSample: Long, endSample: Long): Boolean = nativeSplitCut(startSample, endSample)
    fun splitDeleteSelection(startSample: Long, endSample: Long): Boolean = nativeSplitDelete(startSample, endSample)

    fun captureNoiseProfile(startSample: Long, endSample: Long): Boolean = nativeCaptureNoiseProfile(startSample, endSample)
    fun hasNoiseProfile(): Boolean = nativeHasNoiseProfile()
    fun noiseReduction(startSample: Long, endSample: Long, reductionDb: Float, sensitivity: Float, smoothingBands: Int, residueOnly: Boolean): Boolean =
        nativeNoiseReduction(startSample, endSample, reductionDb, sensitivity, smoothingBands, residueOnly)
    fun notchFilter(startSample: Long, endSample: Long, freqHz: Double, q: Double): Boolean = nativeNotchFilter(startSample, endSample, freqHz, q)
    fun vocalReduction(startSample: Long, endSample: Long, mode: Int, strength: Float, lowCutHz: Double, highCutHz: Double): Boolean =
        nativeVocalReduction(startSample, endSample, mode, strength, lowCutHz, highCutHz)
    fun tremolo(startSample: Long, endSample: Long, waveform: Int, startPhaseDeg: Float, wetPercent: Float, freqHz: Float): Boolean =
        nativeTremolo(startSample, endSample, waveform, startPhaseDeg, wetPercent, freqHz)
    fun slidingStretch(initTempoPct: Double, finalTempoPct: Double, initPitchSemi: Double, finalPitchSemi: Double): Boolean =
        nativeSlidingStretch(initTempoPct, finalTempoPct, initPitchSemi, finalPitchSemi)
    fun adjustableFade(startSample: Long, endSample: Long, fadeType: Int, midBoostPct: Float, startPct: Float, endPct: Float, curveType: Int): Boolean =
        nativeAdjustableFade(startSample, endSample, fadeType, midBoostPct, startPct, endPct, curveType)
    fun autoDuck(controlTrackIdx: Int, duckDb: Float, thresholdDb: Float, outerFadeSec: Float, innerFadeSec: Float): Boolean =
        nativeAutoDuck(controlTrackIdx, duckDb, thresholdDb, outerFadeSec, innerFadeSec)
    fun generatePluck(freqHz: Double, durationSec: Double, sampleRate: Int = 44100, channels: Int = 2): Boolean =
        nativeGeneratePluck(freqHz, durationSec, sampleRate, channels)
    fun findSilences(thresholdDb: Float = -30f, minSilenceSec: Float = 0.5f): LongArray = nativeFindSilences(thresholdDb, minSilenceSec)
    fun findSounds(thresholdDb: Float = -30f, minSilenceSec: Float = 0.5f, minSoundSec: Float = 0.25f): LongArray = nativeFindSounds(thresholdDb, minSilenceSec, minSoundSec)
    fun findBeats(thresholdPct: Float = 65f): LongArray = nativeFindBeats(thresholdPct)
    fun findClippings(startSamples: Int = 3, stopSamples: Int = 3): LongArray = nativeFindClippings(startSamples, stopSamples)
    fun phantomCenter(startSample: Long, endSample: Long, hpfHz: Double, lpfHz: Double, mixPct: Float, outputDb: Float): Boolean =
        nativePhantomCenter(startSample, endSample, hpfHz, lpfHz, mixPct, outputDb)
    fun clipFix(startSample: Long, endSample: Long, thresholdPct: Float = 95f, reduceDb: Float = -3f): Boolean =
        nativeClipFix(startSample, endSample, thresholdPct, reduceDb)
    fun vocoder(startSample: Long, endSample: Long, bands: Int = 16, carrierHz: Double = 120.0, mixPct: Float = 100f): Boolean =
        nativeVocoder(startSample, endSample, bands, carrierHz, mixPct)
    fun deEsser(startSample: Long, endSample: Long, freqHz: Double = 6500.0, thresholdDb: Float = -22f, ratio: Float = 4f): Boolean =
        nativeDeEsser(startSample, endSample, freqHz, thresholdDb, ratio)
    fun multibandCompressor(startSample: Long, endSample: Long, lowSplitHz: Double = 200.0, highSplitHz: Double = 3000.0, thresholdDb: Float = -18f, ratio: Float = 3.5f, makeupDb: Float = 2f): Boolean =
        nativeMultibandComp(startSample, endSample, lowSplitHz, highSplitHz, thresholdDb, ratio, makeupDb)
    fun saturator(startSample: Long, endSample: Long, mode: Int = 0, driveDb: Float = 6f, warmth: Float = 0.5f, mixPct: Float = 100f): Boolean =
        nativeSaturator(startSample, endSample, mode, driveDb, warmth, mixPct)
    fun generateRissetDrum(freqHz: Double = 100.0, decaySec: Double = 0.4, centerNoiseHz: Double = 1500.0, durationSec: Double = 1.0, sr: Int = 44100, ch: Int = 2): Boolean =
        nativeGenerateRissetDrum(freqHz, decaySec, centerNoiseHz, durationSec, sr, ch)
    fun estimateKey(startSample: Long, endSample: Long): Int = nativeEstimateKey(startSample, endSample)
    fun bitcrusher(startSample: Long, endSample: Long, bitDepth: Float = 8f, downsampleFactor: Int = 4, mixPct: Float = 100f): Boolean =
        nativeBitcrusher(startSample, endSample, bitDepth, downsampleFactor, mixPct)

    private external fun nativeLoadPcmBuffer(samples: ShortArray, sampleRate: Int, channels: Int)
    private external fun nativeSetLoopPoints(startSample: Long, endSample: Long)
    private external fun nativeSetLoopEnabled(enabled: Boolean)
    private external fun nativeSetRotationMode(enabled: Boolean, m1: Long, m2: Long, m3: Long, m4: Long)
    private external fun nativeSeek(sampleFrame: Long)
    private external fun nativePlay(): Boolean
    private external fun nativePause()
    private external fun nativeRelease()
    private external fun nativeGetPlayheadSample(): Long
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeExportWavToFd(
        fd: Int, startSample: Long, endSample: Long, rotationEnabled: Boolean,
        m2: Long, m3: Long, m4: Long, writeSmplChunk: Boolean, bitsPerSample: Int
    ): Boolean
    private external fun nativeExportAiffToFd(
        fd: Int, startSample: Long, endSample: Long, rotationEnabled: Boolean,
        m2: Long, m3: Long, m4: Long
    ): Boolean
    private external fun nativeRenderOutputShorts(
        startSample: Long, endSample: Long, rotationEnabled: Boolean,
        m2: Long, m3: Long, m4: Long
    ): ShortArray
    private external fun nativeExportFlacToFd(
        fd: Int, startSample: Long, endSample: Long, rotationEnabled: Boolean,
        m2: Long, m3: Long, m4: Long, bitsPerSample: Int
    ): Boolean
    private external fun nativeGetTotalFrames(): Long
    private external fun nativeTrim(s: Long, e: Long): Boolean
    private external fun nativeDelete(s: Long, e: Long): Boolean
    private external fun nativeSplit(pos: Long): Boolean
    private external fun nativeDuplicate(s: Long, e: Long): Boolean
    private external fun nativeRotate(pos: Long): Boolean
    private external fun nativeFadeIn(s: Long, e: Long): Boolean
    private external fun nativeFadeOut(s: Long, e: Long): Boolean
    private external fun nativeInvert(s: Long, e: Long): Boolean
    private external fun nativeReverse(s: Long, e: Long): Boolean
    private external fun nativeUndo(): Boolean
    private external fun nativeRedo(): Boolean
    private external fun nativeCanUndo(): Boolean
    private external fun nativeCanRedo(): Boolean
    private external fun nativeComputePeaks(vStart: Long, vEnd: Long, bins: Int): FloatArray
    private external fun nativeComputeStereoPeaks(vStart: Long, vEnd: Long, bins: Int): FloatArray
    private external fun nativeGetRawSamples(vStart: Long, vEnd: Long): FloatArray
    private external fun nativeGetMeterLevelsDb(): FloatArray
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetChannels(): Int
    private external fun nativeRepair(s: Long, e: Long): Boolean
    private external fun nativeNormalize(s: Long, e: Long, targetDb: Float, removeDc: Boolean): Boolean
    private external fun nativeAmplify(s: Long, e: Long, gainDb: Float): Boolean
    private external fun nativeSilence(s: Long, e: Long): Boolean
    private external fun nativeChangeSpeed(speedRatio: Double): Boolean
    private external fun nativeSoundTouch(tempoRatio: Double, pitchSemitones: Double): Boolean
    private external fun nativeGenerateSignal(type: Int, freqHz: Double, amp: Float, durSec: Double, sr: Int, ch: Int): Boolean
    private external fun nativeFindZeroCrossing(targetFrame: Long, requiredSlope: Int): Long
    private external fun nativeGetZeroCrossingSlope(frame: Long): Int
    private external fun nativeExportEdlState(): LongArray
    private external fun nativeImportEdlState(data: LongArray): Boolean
    private external fun nativeAnalyzeSelection(s: Long, e: Long): FloatArray
    private external fun nativeGenerateChirp(f1: Double, f2: Double, amp: Float, durSec: Double, sr: Int, ch: Int): Boolean
    private external fun nativeGenerateRhythm(bpm: Double, beatsPerBar: Int, durSec: Double, sr: Int, ch: Int): Boolean
    private external fun nativeCompressor(s: Long, e: Long, threshDb: Float, ratio: Float): Boolean
    private external fun nativeLimiter(s: Long, e: Long, ceilingDb: Float): Boolean
    private external fun nativeFilter(s: Long, e: Long, type: Int, cutoffHz: Double): Boolean
    private external fun nativeBassTreble(s: Long, e: Long, bassDb: Float, trebleDb: Float): Boolean
    private external fun nativeEcho(s: Long, e: Long, delayMs: Float, decay: Float, wrapTail: Boolean): Boolean
    private external fun nativeReverb(s: Long, e: Long, roomSize: Float, wet: Float, wrapTail: Boolean): Boolean
    private external fun nativeNoiseGate(s: Long, e: Long, threshDb: Float, attackMs: Float, releaseMs: Float): Boolean
    private external fun nativePaulstretch(s: Long, e: Long, factor: Double, timeRes: Double): Boolean
    private external fun nativeTrimSilence(threshDb: Float): Boolean
    private external fun nativeFindBestLoopSeam(s: Long, e: Long, radius: Long): Long
    private external fun nativePreviewEffect(s: Long, e: Long, id: Int, p1: Double, p2: Double, p3: Double, p4: Double): Boolean
    private external fun nativeStopPreview()
    private external fun nativeCopy(s: Long, e: Long): Boolean
    private external fun nativeCut(s: Long, e: Long): Boolean
    private external fun nativePaste(pos: Long): Boolean
    private external fun nativeHasClipboard(): Boolean
    private external fun nativeSetTrackMix(gain: Float, pan: Float, muted: Boolean)
    private external fun nativeComputeSpectrum(s: Long, e: Long, bins: Int): FloatArray
    private external fun nativeEstimateBpm(s: Long, e: Long): Double
    private external fun nativeSmoothBoundaries(s: Long, e: Long, ms: Float): Boolean
    private external fun nativeCrossfadeLoopSeam(s: Long, e: Long, ms: Float): Boolean
    private external fun nativeGetTrackCount(): Int
    private external fun nativeGetActiveTrackIndex(): Int
    private external fun nativeSelectTrack(idx: Int): Boolean
    private external fun nativeAddTrackPcm(samples: ShortArray, sr: Int, ch: Int): Boolean
    private external fun nativeDuplicateTrack(): Boolean
    private external fun nativeRemoveTrack(idx: Int): Boolean
    private external fun nativeMixDownTracks(): Boolean
    private external fun nativeSetTrackMixAt(idx: Int, gain: Float, pan: Float, muted: Boolean, solo: Boolean)
    private external fun nativeComputeStereoPeaksForTrack(idx: Int, vStart: Long, vEnd: Long, bins: Int): FloatArray
    private external fun nativeGetRawSamplesForTrack(idx: Int, vStart: Long, vEnd: Long): FloatArray
    private external fun nativeEq5(s: Long, e: Long, g60: Float, g250: Float, g1k: Float, g4k: Float, g12k: Float): Boolean
    private external fun nativePhaser(s: Long, e: Long, rate: Float, depth: Float, fb: Float): Boolean
    private external fun nativeWahwah(s: Long, e: Long, freq: Float, depth: Float, res: Float): Boolean
    private external fun nativeDistortion(s: Long, e: Long, mode: Int, drive: Float, mix: Float): Boolean
    private external fun nativeLoudnessNorm(s: Long, e: Long, targetDb: Float): Boolean
    private external fun nativeClickRemoval(s: Long, e: Long, thresh: Float): Boolean
    private external fun nativeTruncateSilence(threshDb: Float, minSec: Float): Boolean
    private external fun nativeRepeatSelection(s: Long, e: Long, count: Int): Boolean
    private external fun nativeGenerateDtmf(digits: String, toneSec: Double, amp: Float, sr: Int, ch: Int): Boolean
    private external fun nativeComputeSpectrogram(idx: Int, vStart: Long, vEnd: Long, tBins: Int, fBins: Int): FloatArray
    private external fun nativeSetPlaybackSpeed(speed: Float)
    private external fun nativeGetPlaybackSpeed(): Float
    private external fun nativeSetTrackOffset(idx: Int, offsetFrames: Long, commit: Boolean): Boolean
    private external fun nativeGetTrackOffset(idx: Int): Long
    private external fun nativeSetTrackEnvelope(idx: Int, framesArr: LongArray, gainsArr: FloatArray, commit: Boolean): Boolean
    private external fun nativeGetTrackEnvelope(idx: Int): FloatArray
    private external fun nativeSplitStereoToMono(idx: Int): Boolean
    private external fun nativeSwapStereoChannels(idx: Int): Boolean
    private external fun nativeSplitCut(s: Long, e: Long): Boolean
    private external fun nativeSplitDelete(s: Long, e: Long): Boolean
    private external fun nativeCaptureNoiseProfile(s: Long, e: Long): Boolean
    private external fun nativeHasNoiseProfile(): Boolean
    private external fun nativeNoiseReduction(s: Long, e: Long, redDb: Float, sens: Float, smooth: Int, residue: Boolean): Boolean
    private external fun nativeNotchFilter(s: Long, e: Long, freqHz: Double, q: Double): Boolean
    private external fun nativeVocalReduction(s: Long, e: Long, mode: Int, strength: Float, lowHz: Double, highHz: Double): Boolean
    private external fun nativeTremolo(s: Long, e: Long, wf: Int, phase: Float, wet: Float, freqHz: Float): Boolean
    private external fun nativeSlidingStretch(it: Double, ft: Double, ip: Double, fp: Double): Boolean
    private external fun nativeAdjustableFade(s: Long, e: Long, ft: Int, mid: Float, sp: Float, ep: Float, curve: Int): Boolean
    private external fun nativeAutoDuck(ctrlIdx: Int, duckDb: Float, threshDb: Float, outerSec: Float, innerSec: Float): Boolean
    private external fun nativeGeneratePluck(freqHz: Double, durSec: Double, sr: Int, ch: Int): Boolean
    private external fun nativeFindSilences(threshDb: Float, minSec: Float): LongArray
    private external fun nativeFindSounds(threshDb: Float, minSil: Float, minSnd: Float): LongArray
    private external fun nativeFindBeats(threshPct: Float): LongArray
    private external fun nativeFindClippings(startSmp: Int, stopSmp: Int): LongArray
    private external fun nativePhantomCenter(s: Long, e: Long, hpfHz: Double, lpfHz: Double, mixPct: Float, outDb: Float): Boolean
    private external fun nativeClipFix(s: Long, e: Long, threshPct: Float, reduceDb: Float): Boolean
    private external fun nativeVocoder(s: Long, e: Long, bands: Int, carHz: Double, mixPct: Float): Boolean
    private external fun nativeDeEsser(s: Long, e: Long, freqHz: Double, threshDb: Float, ratio: Float): Boolean
    private external fun nativeMultibandComp(s: Long, e: Long, loHz: Double, hiHz: Double, threshDb: Float, ratio: Float, makeupDb: Float): Boolean
    private external fun nativeSaturator(s: Long, e: Long, mode: Int, driveDb: Float, warmth: Float, mixPct: Float): Boolean
    private external fun nativeGenerateRissetDrum(freqHz: Double, decSec: Double, noiseHz: Double, durSec: Double, sr: Int, ch: Int): Boolean
    private external fun nativeEstimateKey(s: Long, e: Long): Int
    private external fun nativeBitcrusher(s: Long, e: Long, bitDepth: Float, downsample: Int, mixPct: Float): Boolean
}
