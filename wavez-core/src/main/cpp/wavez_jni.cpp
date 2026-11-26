#include <jni.h>
#include "WaveZPlayer.h"

static WaveZPlayer gPlayer;

extern "C" {

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeLoadPcmBuffer(
    JNIEnv* env,
    jobject /*thiz*/,
    jshortArray samples,
    jint sampleRate,
    jint channels) {
    if (!samples) return;
    jsize len = env->GetArrayLength(samples);
    jshort* ptr = env->GetShortArrayElements(samples, nullptr);
    if (ptr) {
        gPlayer.loadPcmBuffer(reinterpret_cast<const int16_t*>(ptr), static_cast<int64_t>(len), sampleRate, channels);
        env->ReleaseShortArrayElements(samples, ptr, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSetLoopPoints(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong startSample,
    jlong endSample) {
    gPlayer.setLoopPoints(static_cast<int64_t>(startSample), static_cast<int64_t>(endSample));
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSetLoopEnabled(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    gPlayer.setLoopEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSetRotationMode(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean enabled,
    jlong m1,
    jlong m2,
    jlong m3,
    jlong m4) {
    gPlayer.setRotationMode(enabled == JNI_TRUE, m1, m2, m3, m4);
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSeek(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong sampleFrame) {
    gPlayer.seek(static_cast<int64_t>(sampleFrame));
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativePlay(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.play() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativePause(JNIEnv* /*env*/, jobject /*thiz*/) {
    gPlayer.pause();
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/) {
    gPlayer.release();
}

JNIEXPORT jlong JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetPlayheadSample(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(gPlayer.getPlayheadSample());
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeIsPlaying(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeExportWavToFd(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint fd,
    jlong startSample,
    jlong endSample,
    jboolean rotationEnabled,
    jlong m2,
    jlong m3,
    jlong m4,
    jboolean writeSmplChunk,
    jint bitsPerSample) {
    return gPlayer.exportWav(
        static_cast<int>(fd),
        static_cast<int64_t>(startSample),
        static_cast<int64_t>(endSample),
        rotationEnabled == JNI_TRUE,
        static_cast<int64_t>(m2),
        static_cast<int64_t>(m3),
        static_cast<int64_t>(m4),
        writeSmplChunk == JNI_TRUE,
        static_cast<int32_t>(bitsPerSample)
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeExportAiffToFd(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint fd,
    jlong startSample,
    jlong endSample,
    jboolean rotationEnabled,
    jlong m2,
    jlong m3,
    jlong m4) {
    return gPlayer.exportAiff(
        static_cast<int>(fd),
        static_cast<int64_t>(startSample),
        static_cast<int64_t>(endSample),
        rotationEnabled == JNI_TRUE,
        static_cast<int64_t>(m2),
        static_cast<int64_t>(m3),
        static_cast<int64_t>(m4)
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jshortArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeRenderOutputShorts(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong startSample,
    jlong endSample,
    jboolean rotationEnabled,
    jlong m2,
    jlong m3,
    jlong m4) {
    auto v = gPlayer.renderOutputShorts(startSample, endSample, rotationEnabled == JNI_TRUE, m2, m3, m4);
    jshortArray arr = env->NewShortArray(static_cast<jsize>(v.size()));
    if (arr && !v.empty()) env->SetShortArrayRegion(arr, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jshort*>(v.data()));
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeExportFlacToFd(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint fd,
    jlong startSample,
    jlong endSample,
    jboolean rotationEnabled,
    jlong m2,
    jlong m3,
    jlong m4,
    jint bitsPerSample) {
    return gPlayer.exportFlac(
        static_cast<int>(fd),
        static_cast<int64_t>(startSample),
        static_cast<int64_t>(endSample),
        rotationEnabled == JNI_TRUE,
        static_cast<int64_t>(m2),
        static_cast<int64_t>(m3),
        static_cast<int64_t>(m4),
        static_cast<int32_t>(bitsPerSample)
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetTotalFrames(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(gPlayer.getTotalFrames());
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeTrim(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.trimToSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeDelete(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.deleteSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSplit(JNIEnv* /*env*/, jobject /*thiz*/, jlong pos) {
    return gPlayer.splitAt(pos) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeDuplicate(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.duplicateSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeRotate(JNIEnv* /*env*/, jobject /*thiz*/, jlong pos) {
    return gPlayer.rotateAt(pos) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFadeIn(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.fadeInSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFadeOut(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.fadeOutSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeInvert(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.invertSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeReverse(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.reverseSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeUndo(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.undo() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeRedo(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.redo() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeCanUndo(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.canUndo() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeCanRedo(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.canRedo() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeComputePeaks(JNIEnv* env, jobject /*thiz*/, jlong vStart, jlong vEnd, jint bins) {
    auto peaks = gPlayer.computeWaveformPeaks(vStart, vEnd, bins);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(peaks.size()));
    if (arr && !peaks.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(peaks.size()), peaks.data());
    }
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeComputeStereoPeaks(JNIEnv* env, jobject /*thiz*/, jlong vStart, jlong vEnd, jint bins) {
    auto peaks = gPlayer.computeStereoPeaks(vStart, vEnd, bins);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(peaks.size()));
    if (arr && !peaks.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(peaks.size()), peaks.data());
    }
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetRawSamples(JNIEnv* env, jobject /*thiz*/, jlong vStart, jlong vEnd) {
    auto smp = gPlayer.getRawSamples(vStart, vEnd);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(smp.size()));
    if (arr && !smp.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(smp.size()), smp.data());
    }
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetMeterLevelsDb(JNIEnv* env, jobject /*thiz*/) {
    auto lv = gPlayer.getMeterLevelsDb();
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(lv.size()));
    if (arr && !lv.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(lv.size()), lv.data());
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetSampleRate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(gPlayer.getSampleRate());
}

JNIEXPORT jint JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetChannels(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(gPlayer.getChannels());
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeRepair(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.applyRepair(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeNormalize(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat targetDb, jboolean removeDc) {
    return gPlayer.applyNormalize(s, e, targetDb, removeDc == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeAmplify(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat gainDb) {
    return gPlayer.applyAmplify(s, e, gainDb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSilence(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.applySilence(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeChangeSpeed(JNIEnv* /*env*/, jobject /*thiz*/, jdouble speedRatio) {
    return gPlayer.applyChangeSpeed(speedRatio) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSoundTouch(JNIEnv* /*env*/, jobject /*thiz*/, jdouble tempoRatio, jdouble pitchSemitones) {
    return gPlayer.applySoundTouch(tempoRatio, pitchSemitones) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGenerateSignal(JNIEnv* /*env*/, jobject /*thiz*/, jint type, jdouble freqHz, jfloat amp, jdouble durSec, jint sr, jint ch) {
    return gPlayer.generateSignal(type, freqHz, amp, durSec, sr, ch) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFindZeroCrossing(JNIEnv* /*env*/, jobject /*thiz*/, jlong targetFrame, jint requiredSlope) {
    return static_cast<jlong>(gPlayer.findNearestZeroCrossing(targetFrame, requiredSlope));
}

JNIEXPORT jint JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetZeroCrossingSlope(JNIEnv* /*env*/, jobject /*thiz*/, jlong frame) {
    return static_cast<jint>(gPlayer.getZeroCrossingSlope(frame));
}

JNIEXPORT jlongArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeExportEdlState(JNIEnv* env, jobject /*thiz*/) {
    auto v = gPlayer.exportEdlState();
    jlongArray arr = env->NewLongArray(static_cast<jsize>(v.size()));
    if (arr && !v.empty()) env->SetLongArrayRegion(arr, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jlong*>(v.data()));
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeImportEdlState(JNIEnv* env, jobject /*thiz*/, jlongArray data) {
    if (!data) return JNI_FALSE;
    jsize len = env->GetArrayLength(data);
    jlong* ptr = env->GetLongArrayElements(data, nullptr);
    bool ok = false;
    if (ptr) {
        std::vector<int64_t> v(ptr, ptr + len);
        ok = gPlayer.importEdlState(v);
        env->ReleaseLongArrayElements(data, ptr, JNI_ABORT);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeAnalyzeSelection(JNIEnv* env, jobject /*thiz*/, jlong s, jlong e) {
    auto st = gPlayer.analyzeSelection(s, e);
    float out[6] = {
        st.peakLeftDb,
        st.peakRightDb,
        st.rmsLeftDb,
        st.rmsRightDb,
        static_cast<float>(st.clippedSamples),
        st.seamDeltaPercent
    };
    jfloatArray arr = env->NewFloatArray(6);
    if (arr) {
        env->SetFloatArrayRegion(arr, 0, 6, out);
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGenerateChirp(JNIEnv* /*env*/, jobject /*thiz*/, jdouble f1, jdouble f2, jfloat amp, jdouble durSec, jint sr, jint ch) {
    return gPlayer.generateChirp(f1, f2, amp, durSec, sr, ch) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGenerateRhythm(JNIEnv* /*env*/, jobject /*thiz*/, jdouble bpm, jint beatsPerBar, jdouble durSec, jint sr, jint ch) {
    return gPlayer.generateRhythm(bpm, beatsPerBar, durSec, sr, ch) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeCompressor(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat threshDb, jfloat ratio) {
    return gPlayer.applyCompressor(s, e, threshDb, ratio) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeLimiter(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat ceilingDb) {
    return gPlayer.applyLimiter(s, e, ceilingDb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFilter(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint type, jdouble cutoffHz) {
    return gPlayer.applyFilter(s, e, type, cutoffHz) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeBassTreble(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat bassDb, jfloat trebleDb) {
    return gPlayer.applyBassTreble(s, e, bassDb, trebleDb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeEcho(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat delayMs, jfloat decay, jboolean wrapTail) {
    return gPlayer.applyEcho(s, e, delayMs, decay, wrapTail == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeReverb(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat roomSize, jfloat wet, jboolean wrapTail) {
    return gPlayer.applyReverb(s, e, roomSize, wet, wrapTail == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeNoiseGate(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat threshDb, jfloat attackMs, jfloat releaseMs) {
    return gPlayer.applyNoiseGate(s, e, threshDb, attackMs, releaseMs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativePaulstretch(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jdouble factor, jdouble timeRes) {
    return gPlayer.applyPaulstretch(s, e, factor, timeRes) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeTrimSilence(JNIEnv* /*env*/, jobject /*thiz*/, jfloat threshDb) {
    return gPlayer.applyTrimDigitalSilence(threshDb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFindBestLoopSeam(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jlong radius) {
    return static_cast<jlong>(gPlayer.findBestLoopSeam(s, e, radius));
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativePreviewEffect(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint id, jdouble p1, jdouble p2, jdouble p3, jdouble p4) {
    return gPlayer.previewEffect(s, e, id, p1, p2, p3, p4) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeStopPreview(JNIEnv* /*env*/, jobject /*thiz*/) {
    gPlayer.stopPreview();
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeEq5(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat g60, jfloat g250, jfloat g1k, jfloat g4k, jfloat g12k) {
    return gPlayer.applyEqualizer5Band(s, e, g60, g250, g1k, g4k, g12k) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativePhaser(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat rate, jfloat depth, jfloat fb) {
    return gPlayer.applyPhaser(s, e, rate, depth, fb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeWahwah(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat freq, jfloat depth, jfloat res) {
    return gPlayer.applyWahwah(s, e, freq, depth, res) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeDistortion(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint mode, jfloat drive, jfloat mix) {
    return gPlayer.applyDistortion(s, e, mode, drive, mix) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeLoudnessNorm(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat targetDb) {
    return gPlayer.applyLoudnessNormalize(s, e, targetDb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeClickRemoval(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat thresh) {
    return gPlayer.applyClickRemoval(s, e, thresh) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeTruncateSilence(JNIEnv* /*env*/, jobject /*thiz*/, jfloat threshDb, jfloat minSec) {
    return gPlayer.applyTruncateSilence(threshDb, minSec) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeRepeatSelection(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint count) {
    return gPlayer.applyRepeatSelection(s, e, count) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGenerateDtmf(JNIEnv* env, jobject /*thiz*/, jstring digits, jdouble toneSec, jfloat amp, jint sr, jint ch) {
    if (!digits) return JNI_FALSE;
    const char* cstr = env->GetStringUTFChars(digits, nullptr);
    bool ok = gPlayer.generateDtmf(cstr, toneSec, amp, sr, ch);
    env->ReleaseStringUTFChars(digits, cstr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeComputeSpectrogram(JNIEnv* env, jobject /*thiz*/, jint idx, jlong vStart, jlong vEnd, jint tBins, jint fBins) {
    auto grid = gPlayer.computeSpectrogramForTrack(idx, vStart, vEnd, tBins, fBins);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(grid.size()));
    if (arr && !grid.empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(grid.size()), grid.data());
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeCopy(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.copySelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeCut(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.cutSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativePaste(JNIEnv* /*env*/, jobject /*thiz*/, jlong pos) {
    return gPlayer.pasteAt(pos) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeHasClipboard(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.hasClipboard() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSetTrackMix(JNIEnv* /*env*/, jobject /*thiz*/, jfloat gain, jfloat pan, jboolean muted) {
    gPlayer.setTrackMix(gain, pan, muted == JNI_TRUE);
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeComputeSpectrum(JNIEnv* env, jobject /*thiz*/, jlong s, jlong e, jint bins) {
    auto spec = gPlayer.computeSpectrum(s, e, bins);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(spec.size()));
    if (arr && !spec.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(spec.size()), spec.data());
    }
    return arr;
}

JNIEXPORT jdouble JNICALL
Java_com_frightg_wavez_WaveZNative_nativeEstimateBpm(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return static_cast<jdouble>(gPlayer.estimateBpm(s, e));
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSmoothBoundaries(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat ms) {
    return gPlayer.applySmoothBoundaries(s, e, ms) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeCrossfadeLoopSeam(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat ms) {
    return gPlayer.applyCrossfadeLoopSeam(s, e, ms) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetTrackCount(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(gPlayer.getTrackCount());
}

JNIEXPORT jint JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetActiveTrackIndex(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(gPlayer.getActiveTrackIndex());
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSelectTrack(JNIEnv* /*env*/, jobject /*thiz*/, jint idx) {
    return gPlayer.selectTrack(idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeAddTrackPcm(JNIEnv* env, jobject /*thiz*/, jshortArray samples, jint sr, jint ch) {
    if (!samples) return JNI_FALSE;
    jsize len = env->GetArrayLength(samples);
    jshort* ptr = env->GetShortArrayElements(samples, nullptr);
    bool ok = false;
    if (ptr) {
        ok = gPlayer.addTrackPcmBuffer(reinterpret_cast<const int16_t*>(ptr), static_cast<int64_t>(len), sr, ch);
        env->ReleaseShortArrayElements(samples, ptr, JNI_ABORT);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeDuplicateTrack(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.duplicateActiveTrack() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeRemoveTrack(JNIEnv* /*env*/, jobject /*thiz*/, jint idx) {
    return gPlayer.removeTrack(idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeMixDownTracks(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.mixDownTracks() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSetTrackMixAt(JNIEnv* /*env*/, jobject /*thiz*/, jint idx, jfloat gain, jfloat pan, jboolean muted, jboolean solo) {
    gPlayer.setTrackMixAt(idx, gain, pan, muted == JNI_TRUE, solo == JNI_TRUE);
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeComputeStereoPeaksForTrack(JNIEnv* env, jobject /*thiz*/, jint idx, jlong vStart, jlong vEnd, jint bins) {
    auto peaks = gPlayer.computeStereoPeaksForTrack(idx, vStart, vEnd, bins);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(peaks.size()));
    if (arr && !peaks.empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(peaks.size()), peaks.data());
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetRawSamplesForTrack(JNIEnv* env, jobject /*thiz*/, jint idx, jlong vStart, jlong vEnd) {
    auto smp = gPlayer.getRawSamplesForTrack(idx, vStart, vEnd);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(smp.size()));
    if (arr && !smp.empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(smp.size()), smp.data());
    return arr;
}

JNIEXPORT void JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSetPlaybackSpeed(JNIEnv* /*env*/, jobject /*thiz*/, jfloat speed) {
    gPlayer.setPlaybackSpeed(speed);
}

JNIEXPORT jfloat JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetPlaybackSpeed(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.getPlaybackSpeed();
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSetTrackOffset(JNIEnv* /*env*/, jobject /*thiz*/, jint idx, jlong offsetFrames, jboolean commit) {
    return gPlayer.setTrackOffset(idx, offsetFrames, commit == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetTrackOffset(JNIEnv* /*env*/, jobject /*thiz*/, jint idx) {
    return gPlayer.getTrackOffset(idx);
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSetTrackEnvelope(JNIEnv* env, jobject /*thiz*/, jint idx, jlongArray framesArr, jfloatArray gainsArr, jboolean commit) {
    if (!framesArr || !gainsArr) return JNI_FALSE;
    jsize n = env->GetArrayLength(framesArr);
    if (n != env->GetArrayLength(gainsArr)) return JNI_FALSE;
    std::vector<int64_t> frames(static_cast<size_t>(n));
    std::vector<float> gains(static_cast<size_t>(n));
    if (n > 0) {
        std::vector<jlong> jf(static_cast<size_t>(n));
        env->GetLongArrayRegion(framesArr, 0, n, jf.data());
        for (jsize i = 0; i < n; ++i) frames[static_cast<size_t>(i)] = static_cast<int64_t>(jf[static_cast<size_t>(i)]);
        env->GetFloatArrayRegion(gainsArr, 0, n, gains.data());
    }
    return gPlayer.setTrackEnvelope(idx, frames, gains, commit == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGetTrackEnvelope(JNIEnv* env, jobject /*thiz*/, jint idx) {
    auto envPts = gPlayer.getTrackEnvelope(idx);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(envPts.size()));
    if (arr && !envPts.empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(envPts.size()), envPts.data());
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSplitStereoToMono(JNIEnv* /*env*/, jobject /*thiz*/, jint idx) {
    return gPlayer.splitStereoToMono(idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSwapStereoChannels(JNIEnv* /*env*/, jobject /*thiz*/, jint idx) {
    return gPlayer.swapStereoChannels(idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSplitCut(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.splitCutSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSplitDelete(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.splitDeleteSelection(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeCaptureNoiseProfile(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.captureNoiseProfile(s, e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeHasNoiseProfile(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gPlayer.hasNoiseProfile() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeNoiseReduction(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat redDb, jfloat sens, jint smooth, jboolean residue) {
    return gPlayer.applyNoiseReduction(s, e, redDb, sens, smooth, residue == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeNotchFilter(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jdouble freqHz, jdouble q) {
    return gPlayer.applyNotchFilter(s, e, freqHz, q) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeVocalReduction(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint mode, jfloat strength, jdouble lowHz, jdouble highHz) {
    return gPlayer.applyVocalReduction(s, e, mode, strength, lowHz, highHz) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeTremolo(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint wf, jfloat phase, jfloat wet, jfloat freqHz) {
    return gPlayer.applyTremolo(s, e, wf, phase, wet, freqHz) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSlidingStretch(JNIEnv* /*env*/, jobject /*thiz*/, jdouble it, jdouble ft, jdouble ip, jdouble fp) {
    return gPlayer.applySlidingStretch(it, ft, ip, fp) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeAdjustableFade(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint ft, jfloat mid, jfloat sp, jfloat ep, jint curve) {
    return gPlayer.applyAdjustableFade(s, e, ft, mid, sp, ep, curve) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeAutoDuck(JNIEnv* /*env*/, jobject /*thiz*/, jint ctrlIdx, jfloat duckDb, jfloat threshDb, jfloat outerSec, jfloat innerSec) {
    return gPlayer.applyAutoDuck(ctrlIdx, duckDb, threshDb, outerSec, innerSec) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGeneratePluck(JNIEnv* /*env*/, jobject /*thiz*/, jdouble freqHz, jdouble durSec, jint sr, jint ch) {
    return gPlayer.generatePluck(freqHz, durSec, sr, ch) ? JNI_TRUE : JNI_FALSE;
}

static jlongArray toJLongArray(JNIEnv* env, const std::vector<int64_t>& v) {
    jlongArray arr = env->NewLongArray(static_cast<jsize>(v.size()));
    if (arr && !v.empty()) {
        std::vector<jlong> tmp(v.begin(), v.end());
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(tmp.size()), tmp.data());
    }
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFindSilences(JNIEnv* env, jobject /*thiz*/, jfloat threshDb, jfloat minSec) {
    return toJLongArray(env, gPlayer.findSilences(threshDb, minSec));
}

JNIEXPORT jlongArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFindSounds(JNIEnv* env, jobject /*thiz*/, jfloat threshDb, jfloat minSil, jfloat minSnd) {
    return toJLongArray(env, gPlayer.findSounds(threshDb, minSil, minSnd));
}

JNIEXPORT jlongArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFindBeats(JNIEnv* env, jobject /*thiz*/, jfloat threshPct) {
    return toJLongArray(env, gPlayer.findBeats(threshPct));
}

JNIEXPORT jlongArray JNICALL
Java_com_frightg_wavez_WaveZNative_nativeFindClippings(JNIEnv* env, jobject /*thiz*/, jint startSmp, jint stopSmp) {
    return toJLongArray(env, gPlayer.findClippings(startSmp, stopSmp));
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativePhantomCenter(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jdouble hpfHz, jdouble lpfHz, jfloat mixPct, jfloat outDb) {
    return gPlayer.applyPhantomCenter(s, e, hpfHz, lpfHz, mixPct, outDb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeClipFix(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat threshPct, jfloat reduceDb) {
    return gPlayer.applyClipFix(s, e, threshPct, reduceDb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeVocoder(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint bands, jdouble carHz, jfloat mixPct) {
    return gPlayer.applyVocoder(s, e, bands, carHz, mixPct) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeDeEsser(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jdouble freqHz, jfloat threshDb, jfloat ratio) {
    return gPlayer.applyDeEsser(s, e, freqHz, threshDb, ratio) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeMultibandComp(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jdouble loHz, jdouble hiHz, jfloat threshDb, jfloat ratio, jfloat makeupDb) {
    return gPlayer.applyMultibandCompressor(s, e, loHz, hiHz, threshDb, ratio, makeupDb) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeSaturator(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jint mode, jfloat driveDb, jfloat warmth, jfloat mixPct) {
    return gPlayer.applySaturator(s, e, mode, driveDb, warmth, mixPct) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeGenerateRissetDrum(JNIEnv* /*env*/, jobject /*thiz*/, jdouble freqHz, jdouble decSec, jdouble noiseHz, jdouble durSec, jint sr, jint ch) {
    return gPlayer.generateRissetDrum(freqHz, decSec, noiseHz, durSec, sr, ch) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_frightg_wavez_WaveZNative_nativeEstimateKey(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e) {
    return gPlayer.estimateKey(s, e);
}

JNIEXPORT jboolean JNICALL
Java_com_frightg_wavez_WaveZNative_nativeBitcrusher(JNIEnv* /*env*/, jobject /*thiz*/, jlong s, jlong e, jfloat bitDepth, jint downsample, jfloat mixPct) {
    return gPlayer.applyBitcrusher(s, e, bitDepth, downsample, mixPct) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
