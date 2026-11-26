#include "WaveZPlayer.h"
#include "WaveZExporter.h"
#include <algorithm>
#include <cmath>
#include <cstring>

WaveZPlayer::WaveZPlayer() = default;

WaveZPlayer::~WaveZPlayer() {
    release();
}

static float evaluateEnvelopeAt(const std::vector<WaveZPlayer::EnvelopePoint>& env, int64_t localFrame) {
    if (env.empty()) return 1.0f;
    if (localFrame <= env.front().frame) return env.front().gain;
    if (localFrame >= env.back().frame) return env.back().gain;
    size_t lo = 0, hi = env.size() - 1;
    while (lo + 1 < hi) {
        size_t mid = lo + (hi - lo) / 2;
        if (env[mid].frame <= localFrame) lo = mid; else hi = mid;
    }
    const int64_t f0 = env[lo].frame;
    const int64_t f1 = env[hi].frame;
    if (f1 <= f0) return env[lo].gain;
    const float t = static_cast<float>(localFrame - f0) / static_cast<float>(f1 - f0);
    return env[lo].gain + t * (env[hi].gain - env[lo].gain);
}

WaveZPlayer::TrackState* WaveZPlayer::activeTrackLocked() {
    if (tracks_.empty()) return nullptr;
    activeTrackIndex_ = std::clamp<int32_t>(activeTrackIndex_, 0, static_cast<int32_t>(tracks_.size()) - 1);
    return &tracks_[static_cast<size_t>(activeTrackIndex_)];
}

const WaveZPlayer::TrackState* WaveZPlayer::activeTrackLocked() const {
    if (tracks_.empty()) return nullptr;
    int32_t idx = std::clamp<int32_t>(activeTrackIndex_, 0, static_cast<int32_t>(tracks_.size()) - 1);
    return &tracks_[static_cast<size_t>(idx)];
}

void WaveZPlayer::loadPcmBuffer(const int16_t* samples, int64_t totalShorts, int32_t sampleRate, int32_t channels) {
    if (!samples || totalShorts <= 0 || sampleRate <= 0 || channels <= 0) return;

    auto newBuf = std::make_shared<PcmBuffer>();
    newBuf->samples.assign(samples, samples + totalShorts);
    newBuf->sampleRate = sampleRate;
    newBuf->channels = channels;
    newBuf->totalFrames = totalShorts / channels;

    const bool wasPlaying = playing_.load(std::memory_order_relaxed);
    pause();

    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        tracks_.clear();
        TrackState trk;
        trk.buffer = newBuf;
        trk.edl.reset(newBuf->totalFrames);
        tracks_.push_back(std::move(trk));
        activeTrackIndex_ = 0;
        historyUndo_.clear();
        historyRedo_.clear();
    }

    totalFrames_.store(newBuf->totalFrames, std::memory_order_relaxed);
    sampleRate_.store(sampleRate, std::memory_order_relaxed);
    channels_.store(channels, std::memory_order_relaxed);
    meterLeftDb_.store(-96.0f, std::memory_order_relaxed);
    meterRightDb_.store(-96.0f, std::memory_order_relaxed);
    loopStart_.store(0, std::memory_order_relaxed);
    loopEnd_.store(newBuf->totalFrames, std::memory_order_relaxed);
    cursor_.store(0, std::memory_order_relaxed);

    closeStream();
    openStream(sampleRate, channels);

    if (wasPlaying) play();
}

bool WaveZPlayer::addTrackPcmBuffer(const int16_t* samples, int64_t totalShorts, int32_t sampleRate, int32_t channels) {
    if (!samples || totalShorts <= 0 || sampleRate <= 0 || channels <= 0) return false;

    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (tracks_.empty()) {
        // Unlock and load as primary track
        // Better: construct directly here
        auto newBuf = std::make_shared<PcmBuffer>();
        newBuf->samples.assign(samples, samples + totalShorts);
        newBuf->sampleRate = sampleRate;
        newBuf->channels = channels;
        newBuf->totalFrames = totalShorts / channels;
        TrackState trk;
        trk.buffer = newBuf;
        trk.edl.reset(newBuf->totalFrames);
        tracks_.push_back(std::move(trk));
        activeTrackIndex_ = 0;
        syncTimelineBoundsLocked();
        return true;
    }

    pushHistoryLocked();
    auto newBuf = std::make_shared<PcmBuffer>();
    newBuf->samples.assign(samples, samples + totalShorts);
    newBuf->sampleRate = sampleRate;
    newBuf->channels = channels;
    newBuf->totalFrames = totalShorts / channels;

    TrackState trk;
    trk.buffer = newBuf;
    trk.edl.reset(newBuf->totalFrames);
    tracks_.push_back(std::move(trk));
    activeTrackIndex_ = static_cast<int32_t>(tracks_.size()) - 1;
    syncTimelineBoundsLocked();
    return true;
}

int32_t WaveZPlayer::getTrackCount() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    return static_cast<int32_t>(tracks_.size());
}

int32_t WaveZPlayer::getActiveTrackIndex() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    return activeTrackIndex_;
}

bool WaveZPlayer::selectTrack(int32_t index) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (index < 0 || index >= static_cast<int32_t>(tracks_.size())) return false;
    activeTrackIndex_ = index;
    return true;
}

bool WaveZPlayer::duplicateActiveTrack() {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    const auto* trk = activeTrackLocked();
    if (!trk) return false;
    pushHistoryLocked();
    TrackState copy = *trk;
    tracks_.push_back(std::move(copy));
    activeTrackIndex_ = static_cast<int32_t>(tracks_.size()) - 1;
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::removeTrack(int32_t index) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (tracks_.size() <= 1 || index < 0 || index >= static_cast<int32_t>(tracks_.size())) return false;
    pushHistoryLocked();
    tracks_.erase(tracks_.begin() + index);
    activeTrackIndex_ = std::clamp<int32_t>(activeTrackIndex_, 0, static_cast<int32_t>(tracks_.size()) - 1);
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::mixDownTracks() {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (tracks_.size() <= 1) return false;
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto mixed = mixAllTracksLocked(frames, sr, ch);
    if (mixed.empty() || frames <= 0) return false;

    pushHistoryLocked();
    auto newBuf = std::make_shared<PcmBuffer>();
    newBuf->samples = std::move(mixed);
    newBuf->sampleRate = sr;
    newBuf->channels = ch;
    newBuf->totalFrames = frames;

    tracks_.clear();
    TrackState trk;
    trk.buffer = newBuf;
    trk.edl.reset(frames);
    tracks_.push_back(std::move(trk));
    activeTrackIndex_ = 0;
    syncTimelineBoundsLocked();
    return true;
}

void WaveZPlayer::setTrackMixAt(int32_t index, float gain, float pan, bool muted, bool solo) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (index < 0 || index >= static_cast<int32_t>(tracks_.size())) return;
    auto& t = tracks_[static_cast<size_t>(index)];
    t.gain = std::clamp(gain, 0.0f, 2.0f);
    t.pan = std::clamp(pan, -1.0f, 1.0f);
    t.muted = muted;
    t.solo = solo;
}

void WaveZPlayer::setTrackMix(float gain, float pan, bool muted) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (auto* t = activeTrackLocked()) {
        t->gain = std::clamp(gain, 0.0f, 2.0f);
        t->pan = std::clamp(pan, -1.0f, 1.0f);
        t->muted = muted;
    }
}

bool WaveZPlayer::setTrackOffset(int32_t index, int64_t offsetFrames, bool commit) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (index < 0 || index >= static_cast<int32_t>(tracks_.size())) return false;
    if (commit) pushHistoryLocked();
    tracks_[static_cast<size_t>(index)].offsetFrames = std::max<int64_t>(0, offsetFrames);
    int64_t maxTl = 0;
    for (const auto& t : tracks_) {
        maxTl = std::max(maxTl, t.offsetFrames + t.edl.getTotalTimelineFrames());
    }
    totalFrames_.store(maxTl, std::memory_order_relaxed);
    return true;
}

int64_t WaveZPlayer::getTrackOffset(int32_t index) const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (index < 0 || index >= static_cast<int32_t>(tracks_.size())) return 0;
    return tracks_[static_cast<size_t>(index)].offsetFrames;
}

bool WaveZPlayer::setTrackEnvelope(int32_t index, const std::vector<int64_t>& frames, const std::vector<float>& gains, bool commit) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (index < 0 || index >= static_cast<int32_t>(tracks_.size())) return false;
    if (frames.size() != gains.size()) return false;
    if (commit) pushHistoryLocked();
    std::vector<EnvelopePoint> pts;
    pts.reserve(frames.size());
    for (size_t i = 0; i < frames.size(); ++i) {
        pts.push_back(EnvelopePoint{std::max<int64_t>(0, frames[i]), std::clamp(gains[i], 0.0f, 2.0f)});
    }
    std::sort(pts.begin(), pts.end(), [](const EnvelopePoint& a, const EnvelopePoint& b) {
        return a.frame < b.frame;
    });
    tracks_[static_cast<size_t>(index)].envelope = std::move(pts);
    return true;
}

std::vector<float> WaveZPlayer::getTrackEnvelope(int32_t index) const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (index < 0 || index >= static_cast<int32_t>(tracks_.size())) return {};
    const auto& env = tracks_[static_cast<size_t>(index)].envelope;
    std::vector<float> out;
    out.reserve(env.size() * 2);
    for (const auto& p : env) {
        out.push_back(static_cast<float>(p.frame));
        out.push_back(p.gain);
    }
    return out;
}

bool WaveZPlayer::splitStereoToMono(int32_t index) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (index < 0 || index >= static_cast<int32_t>(tracks_.size())) return false;
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTrackLocked(tracks_[static_cast<size_t>(index)], frames, sr, ch);
    if (flat.empty() || frames <= 0 || ch != 2) return false;

    pushHistoryLocked();
    auto bufL = std::make_shared<PcmBuffer>();
    bufL->sampleRate = sr; bufL->channels = 1; bufL->totalFrames = frames;
    bufL->samples.resize(static_cast<size_t>(frames));
    auto bufR = std::make_shared<PcmBuffer>();
    bufR->sampleRate = sr; bufR->channels = 1; bufR->totalFrames = frames;
    bufR->samples.resize(static_cast<size_t>(frames));
    for (int64_t i = 0; i < frames; ++i) {
        bufL->samples[static_cast<size_t>(i)] = flat[static_cast<size_t>(i * 2)];
        bufR->samples[static_cast<size_t>(i)] = flat[static_cast<size_t>(i * 2 + 1)];
    }

    TrackState trkL;
    trkL.buffer = bufL;
    trkL.edl.reset(frames);
    trkL.gain = tracks_[static_cast<size_t>(index)].gain;
    trkL.pan = -0.5f;
    trkL.offsetFrames = tracks_[static_cast<size_t>(index)].offsetFrames;

    TrackState trkR;
    trkR.buffer = bufR;
    trkR.edl.reset(frames);
    trkR.gain = tracks_[static_cast<size_t>(index)].gain;
    trkR.pan = 0.5f;
    trkR.offsetFrames = tracks_[static_cast<size_t>(index)].offsetFrames;

    tracks_[static_cast<size_t>(index)] = std::move(trkL);
    tracks_.insert(tracks_.begin() + index + 1, std::move(trkR));
    activeTrackIndex_ = index;
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::swapStereoChannels(int32_t index) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (index < 0 || index >= static_cast<int32_t>(tracks_.size())) return false;
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTrackLocked(tracks_[static_cast<size_t>(index)], frames, sr, ch);
    if (flat.empty() || frames <= 0 || ch != 2) return false;

    pushHistoryLocked();
    for (int64_t i = 0; i < frames; ++i) {
        std::swap(flat[static_cast<size_t>(i * 2)], flat[static_cast<size_t>(i * 2 + 1)]);
    }
    auto newBuf = std::make_shared<PcmBuffer>();
    newBuf->samples = std::move(flat);
    newBuf->sampleRate = sr;
    newBuf->channels = 2;
    newBuf->totalFrames = frames;
    tracks_[static_cast<size_t>(index)].buffer = newBuf;
    tracks_[static_cast<size_t>(index)].edl.reset(frames);
    syncTimelineBoundsLocked();
    return true;
}

void WaveZPlayer::setPlaybackSpeed(float speed) {
    playbackSpeed_.store(std::max(0.01f, speed), std::memory_order_relaxed);
}

int64_t WaveZPlayer::getTrackTotalFrames(int32_t trackIdx) const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (trackIdx < 0 || trackIdx >= static_cast<int32_t>(tracks_.size())) return 0;
    return tracks_[static_cast<size_t>(trackIdx)].edl.getTotalTimelineFrames();
}

void WaveZPlayer::setLoopEnabled(bool enabled) {
    loopEnabled_.store(enabled, std::memory_order_relaxed);
}

void WaveZPlayer::setLoopPoints(int64_t startSample, int64_t endSample) {
    const int64_t total = totalFrames_.load(std::memory_order_relaxed);
    if (total <= 0) return;

    int64_t s = std::clamp<int64_t>(startSample, 0, total - 1);
    int64_t e = std::clamp<int64_t>(endSample, s + 1, total);
    loopStart_.store(s, std::memory_order_relaxed);
    loopEnd_.store(e, std::memory_order_relaxed);

    const int64_t cur = cursor_.load(std::memory_order_relaxed);
    if (loopEnabled_.load(std::memory_order_relaxed) && !rotationEnabled_.load(std::memory_order_relaxed) && (cur < s || cur >= e)) {
        cursor_.store(s, std::memory_order_relaxed);
    }
}

void WaveZPlayer::setRotationMode(bool enabled, int64_t m1, int64_t m2, int64_t m3, int64_t m4) {
    const int64_t total = totalFrames_.load(std::memory_order_relaxed);
    if (total <= 0) {
        rotationEnabled_.store(enabled, std::memory_order_relaxed);
        return;
    }
    int64_t cm1 = std::clamp<int64_t>(m1, 0, total);
    int64_t cm2 = std::clamp<int64_t>(m2, 0, total);
    int64_t cm3 = std::clamp<int64_t>(m3, cm2, total);
    int64_t cm4 = std::clamp<int64_t>(m4, cm3, total);

    m1_.store(cm1, std::memory_order_relaxed);
    m2_.store(cm2, std::memory_order_relaxed);
    m3_.store(cm3, std::memory_order_relaxed);
    m4_.store(cm4, std::memory_order_relaxed);
    rotationEnabled_.store(enabled, std::memory_order_relaxed);

    if (enabled && cm4 > cm3) cursor_.store(cm3, std::memory_order_relaxed);
    else if (enabled && cm3 > cm2) cursor_.store(cm2, std::memory_order_relaxed);
}

void WaveZPlayer::seek(int64_t sampleFrame) {
    const int64_t total = totalFrames_.load(std::memory_order_relaxed);
    if (total <= 0) return;
    cursor_.store(std::clamp<int64_t>(sampleFrame, 0, total - 1), std::memory_order_relaxed);
    speedFrac_ = 0.0;
}

bool WaveZPlayer::openStream(int32_t sampleRate, int32_t channels) {
    std::lock_guard<std::mutex> lock(streamMutex_);
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::I16)
        ->setSampleRate(sampleRate)
        ->setChannelCount(channels)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result res = builder.openStream(stream_);
    return res == oboe::Result::OK && stream_ != nullptr;
}

void WaveZPlayer::closeStream() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

bool WaveZPlayer::play() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_) {
        int32_t sr = 44100, ch = 2;
        bool hasTrack = false;
        {
            std::lock_guard<std::mutex> bLock(bufferMutex_);
            if (const auto* t = activeTrackLocked(); t && t->buffer) {
                sr = t->buffer->sampleRate;
                ch = t->buffer->channels;
                hasTrack = true;
            }
        }
        if (!hasTrack) return false;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::I16)
            ->setSampleRate(sr)
            ->setChannelCount(ch)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (builder.openStream(stream_) != oboe::Result::OK || !stream_) return false;
    }
    playing_.store(true, std::memory_order_relaxed);
    return stream_->requestStart() == oboe::Result::OK;
}

void WaveZPlayer::pause() {
    playing_.store(false, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) stream_->requestPause();
}

void WaveZPlayer::release() {
    playing_.store(false, std::memory_order_relaxed);
    closeStream();
    std::lock_guard<std::mutex> lock(bufferMutex_);
    tracks_.clear();
    historyUndo_.clear();
    historyRedo_.clear();
    totalFrames_.store(0, std::memory_order_relaxed);
}

oboe::DataCallbackResult WaveZPlayer::onAudioReady(
    oboe::AudioStream* audioStream,
    void* audioData,
    int32_t numFrames) {

    auto* out = static_cast<int16_t*>(audioData);
    const int32_t outCh = audioStream ? audioStream->getChannelCount() : 2;

    std::unique_lock<std::mutex> lock(bufferMutex_, std::try_to_lock);
    if (!lock.owns_lock()) {
        std::memset(out, 0, static_cast<size_t>(numFrames) * outCh * sizeof(int16_t));
        return oboe::DataCallbackResult::Continue;
    }

    if (previewActive_.load(std::memory_order_relaxed) && !previewSamples_.empty()) {
        const int64_t pTotal = static_cast<int64_t>(previewSamples_.size()) / std::max(1, previewChannels_);
        const int32_t avail = static_cast<int32_t>(std::min<int64_t>(numFrames, pTotal - previewCursor_));
        std::memset(out, 0, static_cast<size_t>(numFrames) * outCh * sizeof(int16_t));
        for (int32_t i = 0; i < avail; ++i) {
            const int16_t l = previewSamples_[static_cast<size_t>((previewCursor_ + i) * previewChannels_)];
            const int16_t r = (previewChannels_ > 1) ? previewSamples_[static_cast<size_t>((previewCursor_ + i) * previewChannels_ + 1)] : l;
            out[static_cast<size_t>(i * outCh + 0)] = l;
            if (outCh > 1) out[static_cast<size_t>(i * outCh + 1)] = r;
        }
        previewCursor_ += avail;
        if (previewCursor_ >= pTotal) {
            previewActive_.store(false, std::memory_order_relaxed);
            playing_.store(false, std::memory_order_relaxed);
        }
        return oboe::DataCallbackResult::Continue;
    }

    if (tracks_.empty() || !playing_.load(std::memory_order_relaxed)) {
        std::memset(out, 0, static_cast<size_t>(numFrames) * outCh * sizeof(int16_t));
        meterLeftDb_.store(-96.0f, std::memory_order_relaxed);
        meterRightDb_.store(-96.0f, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }

    const int64_t total = totalFrames_.load(std::memory_order_relaxed);
    if (total <= 0) {
        std::memset(out, 0, static_cast<size_t>(numFrames) * outCh * sizeof(int16_t));
        return oboe::DataCallbackResult::Continue;
    }

    bool anySolo = false;
    for (const auto& t : tracks_) {
        if (t.solo) { anySolo = true; break; }
    }

    std::vector<float> mix(static_cast<size_t>(numFrames * outCh), 0.0f);
    std::vector<int16_t> tmp;

    const bool rot = rotationEnabled_.load(std::memory_order_relaxed);
    int64_t cur = cursor_.load(std::memory_order_relaxed);

    auto renderSegment = [&](int64_t startPos, int32_t count, int32_t outOffset, float* dstMix) {
        for (const auto& t : tracks_) {
            if (!t.buffer || t.buffer->totalFrames <= 0) continue;
            if ((anySolo && !t.solo) || (!anySolo && t.muted)) continue;
            const int64_t tLen = t.edl.getTotalTimelineFrames();
            const int64_t tEnd = t.offsetFrames + tLen;
            if (startPos + count <= t.offsetFrames || startPos >= tEnd) continue;

            const int64_t segStart = std::max(startPos, t.offsetFrames);
            const int64_t segEnd = std::min(startPos + count, tEnd);
            const int32_t segCount = static_cast<int32_t>(segEnd - segStart);
            if (segCount <= 0) continue;
            const int32_t dstSkip = static_cast<int32_t>(segStart - startPos);
            const int64_t localStart = segStart - t.offsetFrames;

            const int32_t tCh = t.buffer->channels;
            tmp.resize(static_cast<size_t>(segCount * tCh));
            t.edl.renderFrames(t.buffer->samples.data(), t.buffer->totalFrames, tCh, localStart, tmp.data(), segCount);

            const float gainL = t.gain * ((t.pan > 0.0f) ? (1.0f - t.pan) : 1.0f);
            const float gainR = t.gain * ((t.pan < 0.0f) ? (1.0f + t.pan) : 1.0f);
            const bool hasEnv = !t.envelope.empty();

            for (int32_t i = 0; i < segCount; ++i) {
                const float envG = hasEnv ? evaluateEnvelopeAt(t.envelope, localStart + i) : 1.0f;
                const float l = tmp[static_cast<size_t>(i * tCh)] * gainL * envG;
                const float r = ((tCh > 1) ? tmp[static_cast<size_t>(i * tCh + 1)] : tmp[static_cast<size_t>(i)]) * gainR * envG;
                const int32_t outIdx = outOffset + dstSkip + i;
                if (outCh >= 2) {
                    dstMix[static_cast<size_t>(outIdx * outCh + 0)] += l;
                    dstMix[static_cast<size_t>(outIdx * outCh + 1)] += r;
                } else {
                    dstMix[static_cast<size_t>(outIdx)] += 0.5f * (l + r);
                }
            }
        }
    };

    const float speed = playbackSpeed_.load(std::memory_order_relaxed);
    if (std::abs(speed - 1.0f) < 0.005f) {
        if (!rot) {
            if (loopEnabled_.load(std::memory_order_relaxed)) {
                const int64_t s = std::clamp<int64_t>(loopStart_.load(std::memory_order_relaxed), 0, total - 1);
                const int64_t e = std::clamp<int64_t>(loopEnd_.load(std::memory_order_relaxed), s + 1, total);
                if (cur < s || cur >= e) cur = s;
                int32_t filled = 0;
                while (filled < numFrames) {
                    const int32_t avail = static_cast<int32_t>(std::min<int64_t>(numFrames - filled, e - cur));
                    renderSegment(cur, avail, filled, mix.data());
                    cur += avail;
                    filled += avail;
                    if (cur >= e) cur = s;
                }
            } else {
                if (cur < 0 || cur >= total) cur = 0;
                const int32_t avail = static_cast<int32_t>(std::min<int64_t>(numFrames, total - cur));
                if (avail > 0) {
                    renderSegment(cur, avail, 0, mix.data());
                    cur += avail;
                }
                if (cur >= total) {
                    playing_.store(false, std::memory_order_relaxed);
                    cur = 0;
                }
            }
        } else {
            const int64_t m2 = std::clamp<int64_t>(m2_.load(std::memory_order_relaxed), 0, total);
            const int64_t m3 = std::clamp<int64_t>(m3_.load(std::memory_order_relaxed), m2, total);
            const int64_t m4 = std::clamp<int64_t>(m4_.load(std::memory_order_relaxed), m3, total);
            const bool hasHead = m4 > m3;
            const bool hasBody = m3 > m2;
            if (hasHead || hasBody) {
                int32_t filled = 0;
                while (filled < numFrames) {
                    if (cur < m2 || cur >= m4) cur = hasHead ? m3 : m2;
                    const int64_t segEnd = (cur >= m3) ? m4 : m3;
                    const int32_t avail = static_cast<int32_t>(std::min<int64_t>(numFrames - filled, std::max<int64_t>(1, segEnd - cur)));
                    renderSegment(cur, avail, filled, mix.data());
                    cur += avail;
                    filled += avail;
                    if (cur >= m4) cur = hasBody ? m2 : m3;
                    else if (cur == m3) cur = hasHead ? m3 : m2;
                }
            }
        }
    } else {
        const bool isLoop = !rot && loopEnabled_.load(std::memory_order_relaxed);
        const int64_t rStart = isLoop ? std::clamp<int64_t>(loopStart_.load(std::memory_order_relaxed), 0, total - 1) : 0;
        const int64_t rEnd = isLoop ? std::clamp<int64_t>(loopEnd_.load(std::memory_order_relaxed), rStart + 1, total) : total;
        const int64_t rLen = std::max<int64_t>(1, rEnd - rStart);
        if (cur < rStart || cur >= rEnd) { cur = rStart; speedFrac_ = 0.0; }

        double pos = static_cast<double>(cur) + speedFrac_;
        const int32_t needSrc = static_cast<int32_t>(std::ceil(numFrames * static_cast<double>(speed))) + 4;
        std::vector<float> srcMix(static_cast<size_t>(needSrc * outCh), 0.0f);
        const int64_t basePos = static_cast<int64_t>(std::floor(pos));
        if (isLoop) {
            int32_t srcFilled = 0;
            int64_t p = basePos;
            while (srcFilled < needSrc) {
                const int32_t avail = static_cast<int32_t>(std::min<int64_t>(needSrc - srcFilled, rEnd - p));
                if (avail > 0) {
                    renderSegment(p, avail, srcFilled, srcMix.data());
                    srcFilled += avail;
                    p += avail;
                }
                if (p >= rEnd) p = rStart;
            }
        } else {
            const int32_t availSrc = static_cast<int32_t>(std::min<int64_t>(needSrc, rEnd - basePos));
            if (availSrc > 0) {
                renderSegment(basePos, availSrc, 0, srcMix.data());
            }
        }

        double relPos = pos - static_cast<double>(basePos);
        for (int32_t i = 0; i < numFrames; ++i) {
            if (!isLoop && (static_cast<double>(basePos) + relPos) >= static_cast<double>(rEnd)) {
                playing_.store(false, std::memory_order_relaxed);
                pos = 0.0;
                break;
            }
            const int32_t idx0 = static_cast<int32_t>(std::floor(relPos));
            const double frac = relPos - std::floor(relPos);
            if (idx0 >= 0 && idx0 < needSrc) {
                const int32_t idx1 = std::min(idx0 + 1, needSrc - 1);
                for (int32_t c = 0; c < outCh; ++c) {
                    const float s0 = srcMix[static_cast<size_t>(idx0 * outCh + c)];
                    const float s1 = srcMix[static_cast<size_t>(idx1 * outCh + c)];
                    mix[static_cast<size_t>(i * outCh + c)] = static_cast<float>(s0 + frac * (s1 - s0));
                }
            }
            relPos += static_cast<double>(speed);
        }
        if (playing_.load(std::memory_order_relaxed)) {
            double finalPos = static_cast<double>(basePos) + relPos;
            if (isLoop && finalPos >= static_cast<double>(rEnd)) {
                finalPos = static_cast<double>(rStart) + std::fmod(finalPos - static_cast<double>(rStart), static_cast<double>(rLen));
            }
            cur = static_cast<int64_t>(std::floor(finalPos));
            speedFrac_ = finalPos - static_cast<double>(cur);
        } else {
            cur = 0;
            speedFrac_ = 0.0;
        }
    }

    double peakL = 0.0, peakR = 0.0;
    for (int32_t i = 0; i < numFrames; ++i) {
        if (outCh >= 2) {
            const float l = std::clamp(mix[static_cast<size_t>(i * outCh + 0)], -32768.0f, 32767.0f);
            const float r = std::clamp(mix[static_cast<size_t>(i * outCh + 1)], -32768.0f, 32767.0f);
            out[static_cast<size_t>(i * outCh + 0)] = static_cast<int16_t>(l);
            out[static_cast<size_t>(i * outCh + 1)] = static_cast<int16_t>(r);
            peakL = std::max(peakL, std::abs(l) / 32768.0);
            peakR = std::max(peakR, std::abs(r) / 32768.0);
        } else {
            const float m = std::clamp(mix[static_cast<size_t>(i)], -32768.0f, 32767.0f);
            out[static_cast<size_t>(i)] = static_cast<int16_t>(m);
            peakL = std::max(peakL, std::abs(m) / 32768.0);
            peakR = peakL;
        }
    }

    meterLeftDb_.store((peakL > 1e-5) ? static_cast<float>(std::max(-96.0, 20.0 * std::log10(peakL))) : -96.0f, std::memory_order_relaxed);
    meterRightDb_.store((peakR > 1e-5) ? static_cast<float>(std::max(-96.0, 20.0 * std::log10(peakR))) : -96.0f, std::memory_order_relaxed);
    cursor_.store(cur, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void WaveZPlayer::onErrorAfterClose(oboe::AudioStream* /*audioStream*/, oboe::Result error) {
    if (error == oboe::Result::ErrorDisconnected && playing_.load(std::memory_order_relaxed)) {
        int32_t sr = 44100, ch = 2;
        bool ok = false;
        {
            std::lock_guard<std::mutex> lock(bufferMutex_);
            if (const auto* t = activeTrackLocked(); t && t->buffer) {
                sr = t->buffer->sampleRate; ch = t->buffer->channels; ok = true;
            }
        }
        if (ok && openStream(sr, ch)) play();
    }
}

std::vector<int16_t> WaveZPlayer::mixAllTracksLocked(int64_t& outFrames, int32_t& outSampleRate, int32_t& outChannels) const {
    outFrames = 0; outSampleRate = 44100; outChannels = 2;
    if (tracks_.empty()) return {};

    bool anySolo = false;
    for (const auto& t : tracks_) {
        if (t.solo) { anySolo = true; break; }
        if (t.buffer) {
            outSampleRate = t.buffer->sampleRate;
            outChannels = std::max(outChannels, t.buffer->channels);
        }
        outFrames = std::max(outFrames, t.edl.getTotalTimelineFrames());
    }
    for (const auto& t : tracks_) {
        outFrames = std::max(outFrames, t.offsetFrames + t.edl.getTotalTimelineFrames());
    }
    if (outFrames <= 0) return {};

    std::vector<float> accum(static_cast<size_t>(outFrames * outChannels), 0.0f);
    for (const auto& t : tracks_) {
        if (!t.buffer || t.buffer->totalFrames <= 0) continue;
        if ((anySolo && !t.solo) || (!anySolo && t.muted)) continue;
        const int64_t tLen = t.edl.getTotalTimelineFrames();
        if (tLen <= 0) continue;
        const int32_t tCh = t.buffer->channels;
        std::vector<int16_t> rendered(static_cast<size_t>(tLen * tCh));
        t.edl.renderFrames(t.buffer->samples.data(), t.buffer->totalFrames, tCh, 0, rendered.data(), static_cast<int32_t>(tLen));

        const float gainL = t.gain * ((t.pan > 0.0f) ? (1.0f - t.pan) : 1.0f);
        const float gainR = t.gain * ((t.pan < 0.0f) ? (1.0f + t.pan) : 1.0f);
        const bool hasEnv = !t.envelope.empty();
        for (int64_t f = 0; f < tLen; ++f) {
            const int64_t outF = t.offsetFrames + f;
            if (outF < 0 || outF >= outFrames) continue;
            const float envG = hasEnv ? evaluateEnvelopeAt(t.envelope, f) : 1.0f;
            const float l = rendered[static_cast<size_t>(f * tCh)] * gainL * envG;
            const float r = ((tCh > 1) ? rendered[static_cast<size_t>(f * tCh + 1)] : rendered[static_cast<size_t>(f)]) * gainR * envG;
            accum[static_cast<size_t>(outF * outChannels + 0)] += l;
            if (outChannels > 1) accum[static_cast<size_t>(outF * outChannels + 1)] += r;
        }
    }

    std::vector<int16_t> out(static_cast<size_t>(outFrames * outChannels));
    for (size_t i = 0; i < out.size(); ++i) {
        out[i] = static_cast<int16_t>(std::clamp(accum[i], -32768.0f, 32767.0f));
    }
    return out;
}

bool WaveZPlayer::exportWav(int fd, int64_t startSample, int64_t endSample, bool rotationEnabled, int64_t m2, int64_t m3, int64_t m4, bool writeSmplChunk, int32_t bitsPerSample) {
    int64_t tlFrames = 0; int32_t sr = 44100, ch = 2;
    std::vector<int16_t> rendered;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        rendered = mixAllTracksLocked(tlFrames, sr, ch);
    }
    if (rendered.empty() || tlFrames <= 0) return false;
    auto out = WaveZExporter::buildOutputSamples(rendered.data(), tlFrames, ch, sr, startSample, endSample, rotationEnabled, m2, m3, m4);
    if (out.empty()) return false;
    const int64_t outFrames = static_cast<int64_t>(out.size()) / ch;
    return WaveZExporter::exportWavToFd(fd, out.data(), outFrames, sr, ch, writeSmplChunk, 0u, static_cast<uint32_t>(outFrames), bitsPerSample);
}

bool WaveZPlayer::exportAiff(int fd, int64_t startSample, int64_t endSample, bool rotationEnabled, int64_t m2, int64_t m3, int64_t m4) {
    int64_t tlFrames = 0; int32_t sr = 44100, ch = 2;
    std::vector<int16_t> rendered;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        rendered = mixAllTracksLocked(tlFrames, sr, ch);
    }
    if (rendered.empty() || tlFrames <= 0) return false;
    auto out = WaveZExporter::buildOutputSamples(rendered.data(), tlFrames, ch, sr, startSample, endSample, rotationEnabled, m2, m3, m4);
    if (out.empty()) return false;
    const int64_t outFrames = static_cast<int64_t>(out.size()) / ch;
    return WaveZExporter::exportAiffToFd(fd, out.data(), outFrames, sr, ch);
}

std::vector<int16_t> WaveZPlayer::renderOutputShorts(int64_t startSample, int64_t endSample, bool rotationEnabled, int64_t m2, int64_t m3, int64_t m4) {
    int64_t tlFrames = 0; int32_t sr = 44100, ch = 2;
    std::vector<int16_t> rendered;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        rendered = mixAllTracksLocked(tlFrames, sr, ch);
    }
    if (rendered.empty() || tlFrames <= 0) return {};
    return WaveZExporter::buildOutputSamples(rendered.data(), tlFrames, ch, sr, startSample, endSample, rotationEnabled, m2, m3, m4);
}

bool WaveZPlayer::exportFlac(int fd, int64_t startSample, int64_t endSample, bool rotationEnabled, int64_t m2, int64_t m3, int64_t m4, int32_t bitsPerSample) {
    int64_t tlFrames = 0; int32_t sr = 44100, ch = 2;
    std::vector<int16_t> rendered;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        rendered = mixAllTracksLocked(tlFrames, sr, ch);
    }
    if (rendered.empty() || tlFrames <= 0) return false;
    auto out = WaveZExporter::buildOutputSamples(rendered.data(), tlFrames, ch, sr, startSample, endSample, rotationEnabled, m2, m3, m4);
    if (out.empty()) return false;
    const int64_t outFrames = static_cast<int64_t>(out.size()) / ch;
    return WaveZExporter::exportFlacToFd(fd, out.data(), outFrames, sr, ch, bitsPerSample);
}

std::vector<float> WaveZPlayer::getMeterLevelsDb() const {
    return { meterLeftDb_.load(std::memory_order_relaxed), meterRightDb_.load(std::memory_order_relaxed) };
}

void WaveZPlayer::pushHistoryLocked() {
    if (tracks_.empty()) return;
    historyUndo_.push_back(HistorySnapshot{tracks_, activeTrackIndex_});
    if (historyUndo_.size() > 32) historyUndo_.erase(historyUndo_.begin());
    historyRedo_.clear();
}

std::vector<int16_t> WaveZPlayer::flattenTrackLocked(const TrackState& trk, int64_t& outFrames, int32_t& outSampleRate, int32_t& outChannels) const {
    outFrames = 0; outSampleRate = 44100; outChannels = 2;
    if (!trk.buffer) return {};
    outFrames = trk.edl.getTotalTimelineFrames();
    outSampleRate = trk.buffer->sampleRate;
    outChannels = trk.buffer->channels;
    if (outFrames <= 0) return {};
    std::vector<int16_t> flat(static_cast<size_t>(outFrames * outChannels));
    trk.edl.renderFrames(trk.buffer->samples.data(), trk.buffer->totalFrames, outChannels, 0, flat.data(), static_cast<int32_t>(outFrames));
    return flat;
}

std::vector<int16_t> WaveZPlayer::flattenTimelineLocked(int64_t& outFrames, int32_t& outSampleRate, int32_t& outChannels) const {
    const auto* trk = activeTrackLocked();
    if (!trk) { outFrames = 0; outSampleRate = 44100; outChannels = 2; return {}; }
    return flattenTrackLocked(*trk, outFrames, outSampleRate, outChannels);
}

void WaveZPlayer::syncTimelineBoundsLocked() {
    int64_t maxTl = 0;
    for (const auto& t : tracks_) {
        maxTl = std::max(maxTl, t.offsetFrames + t.edl.getTotalTimelineFrames());
    }
    totalFrames_.store(maxTl, std::memory_order_relaxed);
    if (const auto* t = activeTrackLocked(); t && t->buffer) {
        sampleRate_.store(t->buffer->sampleRate, std::memory_order_relaxed);
        channels_.store(t->buffer->channels, std::memory_order_relaxed);
    }
    loopStart_.store(0, std::memory_order_relaxed);
    loopEnd_.store(maxTl, std::memory_order_relaxed);
    cursor_.store(0, std::memory_order_relaxed);
}

bool WaveZPlayer::trimToSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.trimToSelection(selStart - t->offsetFrames, selEnd - t->offsetFrames)) { historyUndo_.pop_back(); return false; }
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::deleteSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.deleteSelection(selStart - t->offsetFrames, selEnd - t->offsetFrames)) { historyUndo_.pop_back(); return false; }
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::splitAt(int64_t pos) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.splitAt(pos - t->offsetFrames)) { historyUndo_.pop_back(); return false; }
    return true;
}

bool WaveZPlayer::duplicateSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.duplicateSelection(selStart, selEnd)) { historyUndo_.pop_back(); return false; }
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::rotateAt(int64_t splitPos) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.rotateAt(splitPos)) { historyUndo_.pop_back(); return false; }
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::fadeInSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.setFadeInOnSelection(selStart, selEnd)) { historyUndo_.pop_back(); return false; }
    return true;
}

bool WaveZPlayer::fadeOutSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.setFadeOutOnSelection(selStart, selEnd)) { historyUndo_.pop_back(); return false; }
    return true;
}

bool WaveZPlayer::invertSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.toggleInvertOnSelection(selStart, selEnd)) { historyUndo_.pop_back(); return false; }
    return true;
}

bool WaveZPlayer::reverseSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    pushHistoryLocked();
    if (!t->edl.toggleReverseOnSelection(selStart, selEnd)) { historyUndo_.pop_back(); return false; }
    return true;
}

#define WAVEZ_DSP_FLATTEN_BEGIN \
    std::lock_guard<std::mutex> lock(bufferMutex_); \
    auto* trk = activeTrackLocked(); \
    if (!trk) return false; \
    int64_t frames = 0; int32_t sr = 44100, ch = 2; \
    auto flat = flattenTrackLocked(*trk, frames, sr, ch); \
    if (flat.empty()) return false; \
    pushHistoryLocked();

#define WAVEZ_DSP_FLATTEN_END \
    auto nextBuf = std::make_shared<PcmBuffer>(); \
    nextBuf->samples = std::move(flat); \
    nextBuf->sampleRate = sr; \
    nextBuf->channels = ch; \
    nextBuf->totalFrames = frames; \
    trk->buffer = nextBuf; \
    trk->edl.reset(frames); \
    syncTimelineBoundsLocked(); \
    return true;

bool WaveZPlayer::applyRepair(int64_t selStart, int64_t selEnd) {
    WAVEZ_DSP_FLATTEN_BEGIN
    if (frames < 16) { historyUndo_.pop_back(); return false; }
    int64_t len = std::clamp<int64_t>(selEnd - selStart, 1, 128);
    int64_t start = std::clamp<int64_t>(selStart, 0, frames - len);
    if (!WaveZDsp::repairSamplesLSAR(flat, ch, frames, start, len)) { historyUndo_.pop_back(); return false; }
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyNormalize(int64_t selStart, int64_t selEnd, float targetDb, bool removeDc) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::normalize(flat, ch, selStart, selEnd, targetDb, removeDc);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyAmplify(int64_t selStart, int64_t selEnd, float gainDb) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::amplify(flat, ch, selStart, selEnd, gainDb);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applySilence(int64_t selStart, int64_t selEnd) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::silence(flat, ch, selStart, selEnd);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyChangeSpeed(double speedRatio) {
    WAVEZ_DSP_FLATTEN_BEGIN
    auto resampled = WaveZDsp::changeSpeed(flat.data(), frames, ch, speedRatio);
    if (resampled.empty()) { historyUndo_.pop_back(); return false; }
    flat = std::move(resampled);
    frames = static_cast<int64_t>(flat.size()) / ch;
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applySoundTouch(double tempoRatio, double pitchSemitones) {
    WAVEZ_DSP_FLATTEN_BEGIN
    auto processed = WaveZDsp::processSoundTouch(flat.data(), frames, sr, ch, tempoRatio, pitchSemitones);
    if (processed.empty()) { historyUndo_.pop_back(); return false; }
    flat = std::move(processed);
    frames = static_cast<int64_t>(flat.size()) / ch;
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyCompressor(int64_t selStart, int64_t selEnd, float thresholdDb, float ratio) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::compressor(flat, ch, selStart, selEnd, thresholdDb, ratio, sr);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyLimiter(int64_t selStart, int64_t selEnd, float ceilingDb) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::limiter(flat, ch, selStart, selEnd, ceilingDb);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyFilter(int64_t selStart, int64_t selEnd, int32_t type, double cutoffHz) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::biquadFilter(flat, ch, selStart, selEnd, sr, type, cutoffHz);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyBassTreble(int64_t selStart, int64_t selEnd, float bassDb, float trebleDb) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::bassTreble(flat, ch, selStart, selEnd, sr, bassDb, trebleDb);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyEcho(int64_t selStart, int64_t selEnd, float delayMs, float decay, bool wrapTail) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::echo(flat, ch, selStart, selEnd, sr, delayMs, decay, wrapTail);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyReverb(int64_t selStart, int64_t selEnd, float roomSize, float wet, bool wrapTail) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::reverb(flat, ch, selStart, selEnd, sr, roomSize, wet, wrapTail);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyNoiseGate(int64_t selStart, int64_t selEnd, float thresholdDb, float attackMs, float releaseMs) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::noiseGate(flat, ch, selStart, selEnd, sr, thresholdDb, attackMs, releaseMs);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyPaulstretch(int64_t selStart, int64_t selEnd, double stretchFactor, double timeResolutionSec) {
    WAVEZ_DSP_FLATTEN_BEGIN
    selStart = std::clamp<int64_t>(selStart, 0, frames - 1);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 1, frames);
    auto stretched = WaveZDsp::paulstretch(flat.data() + selStart * ch, selEnd - selStart, sr, ch, stretchFactor, timeResolutionSec);
    if (stretched.empty()) { historyUndo_.pop_back(); return false; }
    std::vector<int16_t> out;
    out.insert(out.end(), flat.begin(), flat.begin() + static_cast<size_t>(selStart * ch));
    out.insert(out.end(), stretched.begin(), stretched.end());
    out.insert(out.end(), flat.begin() + static_cast<size_t>(selEnd * ch), flat.end());
    flat = std::move(out);
    frames = static_cast<int64_t>(flat.size()) / ch;
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyTrimDigitalSilence(float thresholdDb) {
    WAVEZ_DSP_FLATTEN_BEGIN
    const double threshAmp = std::pow(10.0, thresholdDb / 20.0);
    int64_t first = 0, last = frames;
    while (first < frames) {
        double p = 0.0;
        for (int32_t c = 0; c < ch; ++c) p = std::max(p, std::abs(flat[static_cast<size_t>(first * ch + c)] / 32768.0));
        if (p >= threshAmp) break;
        first++;
    }
    while (last > first) {
        double p = 0.0;
        for (int32_t c = 0; c < ch; ++c) p = std::max(p, std::abs(flat[static_cast<size_t>((last - 1) * ch + c)] / 32768.0));
        if (p >= threshAmp) break;
        last--;
    }
    if (last <= first || (first == 0 && last == frames)) { historyUndo_.pop_back(); return false; }
    std::vector<int16_t> trimmed(flat.begin() + static_cast<size_t>(first * ch), flat.begin() + static_cast<size_t>(last * ch));
    flat = std::move(trimmed);
    frames = static_cast<int64_t>(flat.size()) / ch;
    WAVEZ_DSP_FLATTEN_END
}

int64_t WaveZPlayer::findBestLoopSeam(int64_t selStart, int64_t selEnd, int64_t searchRadius) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    if (flat.empty()) return selEnd;
    return WaveZDsp::findBestLoopSeam(flat.data(), frames, ch, selStart, selEnd, searchRadius);
}

bool WaveZPlayer::previewEffect(int64_t selStart, int64_t selEnd, int32_t effectId, double p1, double p2, double p3, double p4) {
    std::vector<int16_t> snippet;
    int32_t sr = 44100, ch = 2;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        int64_t frames = 0;
        auto flat = flattenTimelineLocked(frames, sr, ch);
        if (flat.empty() || frames <= 0) return false;
        selStart = std::clamp<int64_t>(selStart, 0, frames - 1);
        selEnd = std::clamp<int64_t>(selEnd, selStart + 1, frames);
        const int64_t maxPrev = std::min<int64_t>(selEnd - selStart, static_cast<int64_t>(sr) * 4);
        snippet.assign(flat.begin() + static_cast<size_t>(selStart * ch), flat.begin() + static_cast<size_t>((selStart + maxPrev) * ch));
    }
    const int64_t sFrames = static_cast<int64_t>(snippet.size()) / ch;
    switch (effectId) {
        case 1: WaveZDsp::amplify(snippet, ch, 0, sFrames, static_cast<float>(p1)); break;
        case 2: WaveZDsp::compressor(snippet, ch, 0, sFrames, static_cast<float>(p1), static_cast<float>(p2), sr); break;
        case 3: WaveZDsp::biquadFilter(snippet, ch, 0, sFrames, sr, static_cast<int32_t>(p1), p2); break;
        case 4: WaveZDsp::bassTreble(snippet, ch, 0, sFrames, sr, static_cast<float>(p1), static_cast<float>(p2)); break;
        case 5: WaveZDsp::echo(snippet, ch, 0, sFrames, sr, static_cast<float>(p1), static_cast<float>(p2), p3 > 0.5); break;
        case 6: WaveZDsp::reverb(snippet, ch, 0, sFrames, sr, static_cast<float>(p1), static_cast<float>(p2), p3 > 0.5); break;
        case 7: WaveZDsp::noiseGate(snippet, ch, 0, sFrames, sr, static_cast<float>(p1), static_cast<float>(p2), static_cast<float>(p3)); break;
        case 8: snippet = WaveZDsp::changeSpeed(snippet.data(), sFrames, ch, p1); break;
        case 9: snippet = WaveZDsp::processSoundTouch(snippet.data(), sFrames, sr, ch, p1, p2); break;
        case 10: snippet = WaveZDsp::paulstretch(snippet.data(), sFrames, sr, ch, p1, p2 > 0.0 ? p2 : 0.25); break;
        case 11: {
            float g[5] = {static_cast<float>(p1), static_cast<float>(p2), static_cast<float>(p3), static_cast<float>(p4), 0.0f};
            WaveZDsp::equalizer5Band(snippet, ch, 0, sFrames, sr, g);
            break;
        }
        case 12: WaveZDsp::phaser(snippet, ch, 0, sFrames, sr, static_cast<float>(p1), static_cast<float>(p2), static_cast<float>(p3)); break;
        case 13: WaveZDsp::wahwah(snippet, ch, 0, sFrames, sr, static_cast<float>(p1), static_cast<float>(p2), static_cast<float>(p3)); break;
        case 14: WaveZDsp::distortion(snippet, ch, 0, sFrames, static_cast<int32_t>(p1), static_cast<float>(p2), static_cast<float>(p3)); break;
        case 15: WaveZDsp::loudnessNormalize(snippet, ch, 0, sFrames, static_cast<float>(p1)); break;
        case 16: WaveZDsp::notchFilter(snippet, ch, 0, sFrames, sr, p1, p2); break;
        case 17: WaveZDsp::vocalReduction(snippet, ch, 0, sFrames, sr, static_cast<int32_t>(p1), static_cast<float>(p2), p3, p4); break;
        case 18: WaveZDsp::tremolo(snippet, ch, 0, sFrames, sr, static_cast<int32_t>(p1), static_cast<float>(p2), static_cast<float>(p3), static_cast<float>(p4)); break;
        case 19: WaveZDsp::adjustableFade(snippet, ch, 0, sFrames, static_cast<int32_t>(p1), static_cast<float>(p2), static_cast<float>(p3), static_cast<float>(p4), 2); break;
        case 20: {
            std::vector<float> prof;
            { std::lock_guard<std::mutex> lock(bufferMutex_); prof = noiseProfile_; }
            if (!prof.empty()) WaveZDsp::applyNoiseReduction(snippet, ch, 0, sFrames, prof, static_cast<float>(p1), static_cast<float>(p2), static_cast<int32_t>(p3), p4 > 0.5);
            break;
        }
        case 21: snippet = WaveZDsp::slidingStretch(snippet.data(), sFrames, sr, ch, p1, p2, p3, p4); break;
        case 22: WaveZDsp::phantomCenter(snippet, ch, 0, sFrames, sr, p1, p2, static_cast<float>(p3), static_cast<float>(p4)); break;
        case 23: WaveZDsp::clipFix(snippet, ch, 0, sFrames, static_cast<float>(p1), static_cast<float>(p2)); break;
        case 24: WaveZDsp::deEsser(snippet, ch, 0, sFrames, sr, p1, static_cast<float>(p2), static_cast<float>(p3)); break;
        case 25: WaveZDsp::multibandCompressor(snippet, ch, 0, sFrames, sr, p1, p2, static_cast<float>(p3), static_cast<float>(p4), 2.0f); break;
        case 26: WaveZDsp::saturator(snippet, ch, 0, sFrames, sr, static_cast<int32_t>(p1), static_cast<float>(p2), static_cast<float>(p3), static_cast<float>(p4)); break;
        case 27: WaveZDsp::vocoder(snippet, ch, 0, sFrames, sr, static_cast<int32_t>(p1), p2, static_cast<float>(p3)); break;
        case 28: WaveZDsp::bitcrusher(snippet, ch, 0, sFrames, static_cast<float>(p1), static_cast<int32_t>(p2), static_cast<float>(p3)); break;
        default: break;
    }
    if (snippet.empty()) return false;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        previewSamples_ = std::move(snippet);
        previewChannels_ = ch;
        previewCursor_ = 0;
        previewActive_.store(true, std::memory_order_relaxed);
    }
    return play();
}

void WaveZPlayer::stopPreview() {
    previewActive_.store(false, std::memory_order_relaxed);
    pause();
}

bool WaveZPlayer::applyEqualizer5Band(int64_t selStart, int64_t selEnd, float g60, float g250, float g1k, float g4k, float g12k) {
    WAVEZ_DSP_FLATTEN_BEGIN
    float g[5] = {g60, g250, g1k, g4k, g12k};
    WaveZDsp::equalizer5Band(flat, ch, selStart, selEnd, sr, g);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyPhaser(int64_t selStart, int64_t selEnd, float rateHz, float depth, float feedback) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::phaser(flat, ch, selStart, selEnd, sr, rateHz, depth, feedback);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyWahwah(int64_t selStart, int64_t selEnd, float lfoFreqHz, float depth, float resonance) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::wahwah(flat, ch, selStart, selEnd, sr, lfoFreqHz, depth, resonance);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyDistortion(int64_t selStart, int64_t selEnd, int32_t mode, float drive, float mix) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::distortion(flat, ch, selStart, selEnd, mode, drive, mix);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyLoudnessNormalize(int64_t selStart, int64_t selEnd, float targetRmsDb) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::loudnessNormalize(flat, ch, selStart, selEnd, targetRmsDb);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyClickRemoval(int64_t selStart, int64_t selEnd, float threshold) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::clickRemoval(flat, ch, selStart, selEnd, threshold);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyTruncateSilence(float thresholdDb, float minSilenceSec) {
    WAVEZ_DSP_FLATTEN_BEGIN
    auto out = WaveZDsp::truncateSilence(flat.data(), frames, sr, ch, thresholdDb, minSilenceSec);
    if (out.empty()) { historyUndo_.pop_back(); return false; }
    flat = std::move(out);
    frames = static_cast<int64_t>(flat.size()) / ch;
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyRepeatSelection(int64_t selStart, int64_t selEnd, int32_t count) {
    if (count < 1 || count > 16) return false;
    WAVEZ_DSP_FLATTEN_BEGIN
    selStart = std::clamp<int64_t>(selStart, 0, frames - 1);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 1, frames);
    std::vector<int16_t> seg(flat.begin() + static_cast<size_t>(selStart * ch), flat.begin() + static_cast<size_t>(selEnd * ch));
    std::vector<int16_t> out;
    out.reserve(flat.size() + seg.size() * count);
    out.insert(out.end(), flat.begin(), flat.begin() + static_cast<size_t>(selEnd * ch));
    for (int32_t i = 0; i < count; ++i) out.insert(out.end(), seg.begin(), seg.end());
    out.insert(out.end(), flat.begin() + static_cast<size_t>(selEnd * ch), flat.end());
    flat = std::move(out);
    frames = static_cast<int64_t>(flat.size()) / ch;
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::generateDtmf(const char* digits, double toneSec, float amplitude, int32_t sampleRate, int32_t channels) {
    auto pcm = WaveZDsp::generateDtmf(digits, toneSec, amplitude, sampleRate, channels);
    if (pcm.empty()) return false;
    loadPcmBuffer(pcm.data(), static_cast<int64_t>(pcm.size()), sampleRate, channels);
    return true;
}

std::vector<float> WaveZPlayer::computeSpectrogramForTrack(int32_t trackIdx, int64_t viewStart, int64_t viewEnd, int32_t timeBins, int32_t freqBins) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (trackIdx < 0 || trackIdx >= static_cast<int32_t>(tracks_.size())) return {};
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTrackLocked(tracks_[static_cast<size_t>(trackIdx)], frames, sr, ch);
    if (flat.empty()) return {};
    return WaveZDsp::computeSpectrogram(flat.data(), frames, ch, viewStart, viewEnd, timeBins, freqBins);
}

bool WaveZPlayer::applySmoothBoundaries(int64_t selStart, int64_t selEnd, float smoothMs) {
    WAVEZ_DSP_FLATTEN_BEGIN
    const int64_t n = std::max<int64_t>(1, static_cast<int64_t>(smoothMs * 0.001 * sr));
    WaveZDsp::smoothBoundaries(flat, ch, selStart, selEnd, n);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyCrossfadeLoopSeam(int64_t selStart, int64_t selEnd, float crossfadeMs) {
    WAVEZ_DSP_FLATTEN_BEGIN
    const int64_t n = std::max<int64_t>(2, static_cast<int64_t>(crossfadeMs * 0.001 * sr));
    WaveZDsp::crossfadeLoopSeam(flat, ch, selStart, selEnd, n);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::captureNoiseProfile(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    if (flat.empty() || frames <= 0) return false;
    noiseProfile_ = WaveZDsp::captureNoiseProfile(flat.data(), frames, ch, selStart, selEnd);
    return !noiseProfile_.empty();
}

bool WaveZPlayer::hasNoiseProfile() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    return !noiseProfile_.empty();
}

bool WaveZPlayer::applyNoiseReduction(int64_t selStart, int64_t selEnd, float reductionDb, float sensitivity, int32_t smoothingBands, bool residueOnly) {
    std::vector<float> prof;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        prof = noiseProfile_;
    }
    if (prof.empty()) return false;
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::applyNoiseReduction(flat, ch, selStart, selEnd, prof, reductionDb, sensitivity, smoothingBands, residueOnly);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyNotchFilter(int64_t selStart, int64_t selEnd, double freqHz, double q) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::notchFilter(flat, ch, selStart, selEnd, sr, freqHz, q);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyVocalReduction(int64_t selStart, int64_t selEnd, int32_t mode, float strength, double lowCutHz, double highCutHz) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::vocalReduction(flat, ch, selStart, selEnd, sr, mode, strength, lowCutHz, highCutHz);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyTremolo(int64_t selStart, int64_t selEnd, int32_t waveform, float startPhaseDeg, float wetPercent, float freqHz) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::tremolo(flat, ch, selStart, selEnd, sr, waveform, startPhaseDeg, wetPercent, freqHz);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applySlidingStretch(double initTempoPct, double finalTempoPct, double initPitchSemi, double finalPitchSemi) {
    WAVEZ_DSP_FLATTEN_BEGIN
    auto res = WaveZDsp::slidingStretch(flat.data(), frames, sr, ch, initTempoPct, finalTempoPct, initPitchSemi, finalPitchSemi);
    if (res.empty()) { historyUndo_.pop_back(); return false; }
    flat = std::move(res);
    frames = static_cast<int64_t>(flat.size()) / ch;
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyAdjustableFade(int64_t selStart, int64_t selEnd, int32_t fadeType, float midBoostPct, float startPct, float endPct, int32_t curveType) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::adjustableFade(flat, ch, selStart, selEnd, fadeType, midBoostPct, startPct, endPct, curveType);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyAutoDuck(int32_t controlTrackIdx, float duckDb, float thresholdDb, float outerFadeSec, float innerFadeSec) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* trk = activeTrackLocked();
    if (!trk || controlTrackIdx < 0 || controlTrackIdx >= static_cast<int32_t>(tracks_.size()) || controlTrackIdx == activeTrackIndex_) return false;
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTrackLocked(*trk, frames, sr, ch);
    int64_t cFrames = 0; int32_t cSr = 44100, cCh = 2;
    auto ctrl = flattenTrackLocked(tracks_[static_cast<size_t>(controlTrackIdx)], cFrames, cSr, cCh);
    if (flat.empty() || ctrl.empty()) return false;
    pushHistoryLocked();
    WaveZDsp::autoDuck(flat, ch, ctrl.data(), cFrames, cCh, sr, duckDb, thresholdDb, outerFadeSec, innerFadeSec);
    auto nextBuf = std::make_shared<PcmBuffer>();
    nextBuf->samples = std::move(flat);
    nextBuf->sampleRate = sr;
    nextBuf->channels = ch;
    nextBuf->totalFrames = frames;
    trk->buffer = nextBuf;
    trk->edl.reset(frames);
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::generatePluck(double freqHz, double durationSec, int32_t sampleRate, int32_t channels) {
    auto pcm = WaveZDsp::generatePluck(freqHz, durationSec, sampleRate, channels);
    if (pcm.empty()) return false;
    loadPcmBuffer(pcm.data(), static_cast<int64_t>(pcm.size()), sampleRate, channels);
    return true;
}

std::vector<int64_t> WaveZPlayer::findSilences(float thresholdDb, float minSilenceSec) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    return WaveZDsp::findSilences(flat.data(), frames, ch, sr, thresholdDb, minSilenceSec);
}

std::vector<int64_t> WaveZPlayer::findSounds(float thresholdDb, float minSilenceSec, float minSoundSec) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    return WaveZDsp::findSounds(flat.data(), frames, ch, sr, thresholdDb, minSilenceSec, minSoundSec);
}

std::vector<int64_t> WaveZPlayer::findBeats(float thresholdPct) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    return WaveZDsp::findBeats(flat.data(), frames, ch, sr, thresholdPct);
}

std::vector<int64_t> WaveZPlayer::findClippings(int32_t startSamples, int32_t stopSamples) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    return WaveZDsp::findClippings(flat.data(), frames, ch, startSamples, stopSamples);
}

bool WaveZPlayer::applyPhantomCenter(int64_t selStart, int64_t selEnd, double hpfHz, double lpfHz, float mixPct, float outputDb) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::phantomCenter(flat, ch, selStart, selEnd, sr, hpfHz, lpfHz, mixPct, outputDb);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyClipFix(int64_t selStart, int64_t selEnd, float thresholdPct, float reduceDb) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::clipFix(flat, ch, selStart, selEnd, thresholdPct, reduceDb);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyVocoder(int64_t selStart, int64_t selEnd, int32_t bands, double carrierHz, float mixPct) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::vocoder(flat, ch, selStart, selEnd, sr, bands, carrierHz, mixPct);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyDeEsser(int64_t selStart, int64_t selEnd, double freqHz, float thresholdDb, float ratio) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::deEsser(flat, ch, selStart, selEnd, sr, freqHz, thresholdDb, ratio);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applyMultibandCompressor(int64_t selStart, int64_t selEnd, double lowSplitHz, double highSplitHz, float thresholdDb, float ratio, float makeupDb) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::multibandCompressor(flat, ch, selStart, selEnd, sr, lowSplitHz, highSplitHz, thresholdDb, ratio, makeupDb);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::applySaturator(int64_t selStart, int64_t selEnd, int32_t mode, float driveDb, float warmth, float mixPct) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::saturator(flat, ch, selStart, selEnd, sr, mode, driveDb, warmth, mixPct);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::generateRissetDrum(double freqHz, double decaySec, double centerNoiseHz, double durationSec, int32_t sampleRate, int32_t channels) {
    auto pcm = WaveZDsp::generateRissetDrum(freqHz, decaySec, centerNoiseHz, durationSec, sampleRate, channels);
    if (pcm.empty()) return false;
    loadPcmBuffer(pcm.data(), static_cast<int64_t>(pcm.size()), sampleRate, channels);
    return true;
}

int32_t WaveZPlayer::estimateKey(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    return WaveZDsp::estimateKey(flat.data(), frames, ch, selStart, selEnd, sr);
}

bool WaveZPlayer::applyBitcrusher(int64_t selStart, int64_t selEnd, float bitDepth, int32_t downsampleFactor, float mixPct) {
    WAVEZ_DSP_FLATTEN_BEGIN
    WaveZDsp::bitcrusher(flat, ch, selStart, selEnd, bitDepth, downsampleFactor, mixPct);
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::generateSignal(int32_t type, double freqHz, float amplitude, double durationSec, int32_t sampleRate, int32_t channels) {
    auto pcm = WaveZDsp::generateSignal(type, freqHz, amplitude, durationSec, sampleRate, channels);
    if (pcm.empty()) return false;
    loadPcmBuffer(pcm.data(), static_cast<int64_t>(pcm.size()), sampleRate, channels);
    return true;
}

bool WaveZPlayer::generateChirp(double freqStartHz, double freqEndHz, float amplitude, double durationSec, int32_t sampleRate, int32_t channels) {
    auto pcm = WaveZDsp::generateChirp(freqStartHz, freqEndHz, amplitude, durationSec, sampleRate, channels);
    if (pcm.empty()) return false;
    loadPcmBuffer(pcm.data(), static_cast<int64_t>(pcm.size()), sampleRate, channels);
    return true;
}

bool WaveZPlayer::generateRhythm(double bpm, int32_t beatsPerBar, double durationSec, int32_t sampleRate, int32_t channels) {
    auto pcm = WaveZDsp::generateRhythmTrack(bpm, beatsPerBar, durationSec, sampleRate, channels);
    if (pcm.empty()) return false;
    loadPcmBuffer(pcm.data(), static_cast<int64_t>(pcm.size()), sampleRate, channels);
    return true;
}

int64_t WaveZPlayer::findNearestZeroCrossing(int64_t targetFrame, int32_t requiredSlope) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    if (flat.empty()) return targetFrame;
    return WaveZDsp::findNearestZeroCrossing(flat.data(), frames, ch, targetFrame, requiredSlope);
}

int32_t WaveZPlayer::getZeroCrossingSlope(int64_t frame) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    if (flat.empty()) return 0;
    return WaveZDsp::getZeroCrossingSlope(flat.data(), frames, ch, frame);
}

std::vector<int64_t> WaveZPlayer::exportEdlState() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    const auto* trk = activeTrackLocked();
    if (!trk) return {};
    const auto& regs = trk->edl.getRegions();
    std::vector<int64_t> out;
    out.reserve(regs.size() * 7);
    for (const auto& r : regs) {
        out.push_back(r.sourceStart);
        out.push_back(r.sourceEnd);
        out.push_back(static_cast<int64_t>(std::llround(r.gain * 10000.0)));
        out.push_back(r.fadeInSamples);
        out.push_back(r.fadeOutSamples);
        out.push_back(r.invert ? 1 : 0);
        out.push_back(r.reverse ? 1 : 0);
    }
    return out;
}

bool WaveZPlayer::importEdlState(const std::vector<int64_t>& data) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* trk = activeTrackLocked();
    if (!trk || !trk->buffer || data.empty() || (data.size() % 7) != 0) return false;
    std::vector<AudioRegion> regs;
    for (size_t i = 0; i < data.size(); i += 7) {
        AudioRegion r;
        r.sourceStart = std::clamp<int64_t>(data[i + 0], 0, trk->buffer->totalFrames);
        r.sourceEnd = std::clamp<int64_t>(data[i + 1], r.sourceStart, trk->buffer->totalFrames);
        r.gain = static_cast<float>(data[i + 2] / 10000.0);
        r.fadeInSamples = data[i + 3];
        r.fadeOutSamples = data[i + 4];
        r.invert = (data[i + 5] != 0);
        r.reverse = (data[i + 6] != 0);
        if (r.length() > 0) regs.push_back(r);
    }
    if (regs.empty()) return false;
    pushHistoryLocked();
    trk->edl.setRegions(std::move(regs));
    syncTimelineBoundsLocked();
    return true;
}

AudioStats WaveZPlayer::analyzeSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    if (flat.empty()) return AudioStats{};
    return WaveZDsp::analyzeRange(flat.data(), frames, ch, selStart, selEnd);
}

bool WaveZPlayer::copySelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    if (flat.empty() || selEnd <= selStart) return false;
    selStart = std::clamp<int64_t>(selStart, 0, frames - 1);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 1, frames);
    clipboardSamples_.assign(
        flat.begin() + static_cast<size_t>(selStart * ch),
        flat.begin() + static_cast<size_t>(selEnd * ch)
    );
    clipboardChannels_ = ch;
    return !clipboardSamples_.empty();
}

bool WaveZPlayer::cutSelection(int64_t selStart, int64_t selEnd) {
    if (!copySelection(selStart, selEnd)) return false;
    return deleteSelection(selStart, selEnd);
}

bool WaveZPlayer::splitDeleteSelection(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    auto* t = activeTrackLocked();
    if (!t) return false;
    const int64_t tl = t->edl.getTotalTimelineFrames();
    const int64_t localS = std::clamp<int64_t>(selStart - t->offsetFrames, 0, tl);
    const int64_t localE = std::clamp<int64_t>(selEnd - t->offsetFrames, localS, tl);
    if (localE <= localS) return false;
    pushHistoryLocked();
    if (!t->edl.setGainOnSelection(localS, localE, 0.0f)) { historyUndo_.pop_back(); return false; }
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::splitCutSelection(int64_t selStart, int64_t selEnd) {
    if (!copySelection(selStart, selEnd)) return false;
    return splitDeleteSelection(selStart, selEnd);
}

bool WaveZPlayer::pasteAt(int64_t pos) {
    WAVEZ_DSP_FLATTEN_BEGIN
    if (clipboardSamples_.empty() || ch != clipboardChannels_) { historyUndo_.pop_back(); return false; }
    pos = std::clamp<int64_t>(pos, 0, frames);
    flat.insert(flat.begin() + static_cast<size_t>(pos * ch), clipboardSamples_.begin(), clipboardSamples_.end());
    frames = static_cast<int64_t>(flat.size()) / ch;
    WAVEZ_DSP_FLATTEN_END
}

bool WaveZPlayer::hasClipboard() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    return !clipboardSamples_.empty();
}

std::vector<float> WaveZPlayer::computeSpectrum(int64_t selStart, int64_t selEnd, int32_t bins) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    if (flat.empty()) return {};
    return WaveZDsp::computeSpectrumDb(flat.data(), frames, ch, selStart, selEnd, bins);
}

double WaveZPlayer::estimateBpm(int64_t selStart, int64_t selEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    int64_t frames = 0; int32_t sr = 44100, ch = 2;
    auto flat = flattenTimelineLocked(frames, sr, ch);
    if (flat.empty()) return 120.0;
    return WaveZDsp::estimateBpm(flat.data(), frames, ch, selStart, selEnd, sr);
}

bool WaveZPlayer::undo() {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (historyUndo_.empty()) return false;
    historyRedo_.push_back(HistorySnapshot{tracks_, activeTrackIndex_});
    auto prev = std::move(historyUndo_.back());
    historyUndo_.pop_back();
    tracks_ = std::move(prev.tracks);
    activeTrackIndex_ = prev.activeTrack;
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::redo() {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (historyRedo_.empty()) return false;
    historyUndo_.push_back(HistorySnapshot{tracks_, activeTrackIndex_});
    auto next = std::move(historyRedo_.back());
    historyRedo_.pop_back();
    tracks_ = std::move(next.tracks);
    activeTrackIndex_ = next.activeTrack;
    syncTimelineBoundsLocked();
    return true;
}

bool WaveZPlayer::canUndo() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    return !historyUndo_.empty();
}

bool WaveZPlayer::canRedo() const {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    return !historyRedo_.empty();
}

std::vector<float> WaveZPlayer::computeWaveformPeaks(int64_t viewStart, int64_t viewEnd, int32_t bins) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    const auto* t = activeTrackLocked();
    if (!t || !t->buffer) return {};
    return t->edl.computeWaveformPeaks(t->buffer->samples.data(), t->buffer->totalFrames, t->buffer->channels, viewStart, viewEnd, bins);
}

std::vector<float> WaveZPlayer::computeStereoPeaks(int64_t viewStart, int64_t viewEnd, int32_t bins) {
    return computeStereoPeaksForTrack(activeTrackIndex_, viewStart, viewEnd, bins);
}

std::vector<float> WaveZPlayer::computeStereoPeaksForTrack(int32_t trackIdx, int64_t viewStart, int64_t viewEnd, int32_t bins) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (trackIdx < 0 || trackIdx >= static_cast<int32_t>(tracks_.size()) || bins <= 0) return {};
    const auto& trk = tracks_[static_cast<size_t>(trackIdx)];
    if (!trk.buffer) return {};
    const int64_t tl = trk.edl.getTotalTimelineFrames();
    const int64_t trkEnd = trk.offsetFrames + tl;
    if (tl <= 0 || viewEnd <= viewStart) return std::vector<float>(static_cast<size_t>(bins * 6), 0.0f);

    const int64_t maxTl = std::max<int64_t>(totalFrames_.load(std::memory_order_relaxed), trkEnd);
    viewStart = std::clamp<int64_t>(viewStart, 0, std::max<int64_t>(0, maxTl - 1));
    viewEnd = std::clamp<int64_t>(viewEnd, viewStart + 1, std::max<int64_t>(viewStart + 1, maxTl));
    const int64_t span = viewEnd - viewStart;
    const int32_t ch = trk.buffer->channels;
    const int64_t maxRender = std::min<int64_t>(span, 4000000);
    std::vector<float> out(static_cast<size_t>(bins * 6), 0.0f);

    if (span <= maxRender) {
        const int64_t ovStart = std::max(viewStart, trk.offsetFrames);
        const int64_t ovEnd = std::min(viewEnd, trkEnd);
        std::vector<int16_t> win;
        if (ovEnd > ovStart) {
            const int64_t ovSpan = ovEnd - ovStart;
            win.resize(static_cast<size_t>(ovSpan * ch));
            trk.edl.renderFrames(trk.buffer->samples.data(), trk.buffer->totalFrames, ch, ovStart - trk.offsetFrames, win.data(), static_cast<int32_t>(ovSpan));
        }
        for (int32_t b = 0; b < bins; ++b) {
            const int64_t f0 = viewStart + (static_cast<int64_t>(b) * span) / bins;
            const int64_t f1 = std::max(f0 + 1, viewStart + (static_cast<int64_t>(b + 1) * span) / bins);
            float minL = 1.0f, maxL = -1.0f, minR = 1.0f, maxR = -1.0f;
            double sqL = 0.0, sqR = 0.0; int64_t cnt = 0;
            for (int64_t f = f0; f < f1 && f < viewEnd; ++f) {
                float l = 0.0f, r = 0.0f;
                if (f >= ovStart && f < ovEnd) {
                    const int64_t idx = f - ovStart;
                    l = win[static_cast<size_t>(idx * ch)] / 32768.0f;
                    r = (ch > 1) ? (win[static_cast<size_t>(idx * ch + 1)] / 32768.0f) : l;
                }
                minL = std::min(minL, l); maxL = std::max(maxL, l);
                minR = std::min(minR, r); maxR = std::max(maxR, r);
                sqL += l * l; sqR += r * r; cnt++;
            }
            if (cnt == 0) { minL = maxL = minR = maxR = 0.0f; }
            out[static_cast<size_t>(b * 6 + 0)] = minL;
            out[static_cast<size_t>(b * 6 + 1)] = maxL;
            out[static_cast<size_t>(b * 6 + 2)] = cnt > 0 ? static_cast<float>(std::sqrt(sqL / cnt)) : 0.0f;
            out[static_cast<size_t>(b * 6 + 3)] = minR;
            out[static_cast<size_t>(b * 6 + 4)] = maxR;
            out[static_cast<size_t>(b * 6 + 5)] = cnt > 0 ? static_cast<float>(std::sqrt(sqR / cnt)) : 0.0f;
        }
    } else {
        constexpr int32_t kSmpPerBin = 24;
        std::vector<int16_t> tmp(static_cast<size_t>(ch));
        for (int32_t b = 0; b < bins; ++b) {
            const int64_t f0 = viewStart + (static_cast<int64_t>(b) * span) / bins;
            const int64_t f1 = viewStart + (static_cast<int64_t>(b + 1) * span) / bins;
            const int64_t step = std::max<int64_t>(1, (f1 - f0) / kSmpPerBin);
            float minL = 1.0f, maxL = -1.0f, minR = 1.0f, maxR = -1.0f;
            double sqL = 0.0, sqR = 0.0; int64_t cnt = 0;
            for (int64_t f = f0; f < f1; f += step) {
                float l = 0.0f, r = 0.0f;
                if (f >= trk.offsetFrames && f < trkEnd) {
                    trk.edl.renderFrames(trk.buffer->samples.data(), trk.buffer->totalFrames, ch, f - trk.offsetFrames, tmp.data(), 1);
                    l = tmp[0] / 32768.0f;
                    r = (ch > 1) ? (tmp[1] / 32768.0f) : l;
                }
                minL = std::min(minL, l); maxL = std::max(maxL, l);
                minR = std::min(minR, r); maxR = std::max(maxR, r);
                sqL += l * l; sqR += r * r; cnt++;
            }
            if (cnt == 0) { minL = maxL = minR = maxR = 0.0f; }
            out[static_cast<size_t>(b * 6 + 0)] = minL;
            out[static_cast<size_t>(b * 6 + 1)] = maxL;
            out[static_cast<size_t>(b * 6 + 2)] = cnt > 0 ? static_cast<float>(std::sqrt(sqL / cnt)) : 0.0f;
            out[static_cast<size_t>(b * 6 + 3)] = minR;
            out[static_cast<size_t>(b * 6 + 4)] = maxR;
            out[static_cast<size_t>(b * 6 + 5)] = cnt > 0 ? static_cast<float>(std::sqrt(sqR / cnt)) : 0.0f;
        }
    }
    return out;
}

std::vector<float> WaveZPlayer::getRawSamples(int64_t viewStart, int64_t viewEnd) {
    return getRawSamplesForTrack(activeTrackIndex_, viewStart, viewEnd);
}

std::vector<float> WaveZPlayer::getRawSamplesForTrack(int32_t trackIdx, int64_t viewStart, int64_t viewEnd) {
    std::lock_guard<std::mutex> lock(bufferMutex_);
    if (trackIdx < 0 || trackIdx >= static_cast<int32_t>(tracks_.size())) return {};
    const auto& trk = tracks_[static_cast<size_t>(trackIdx)];
    if (!trk.buffer) return {};
    const int64_t tl = trk.edl.getTotalTimelineFrames();
    const int64_t trkEnd = trk.offsetFrames + tl;
    if (tl <= 0 || viewEnd <= viewStart) return {};

    const int64_t maxTl = std::max<int64_t>(totalFrames_.load(std::memory_order_relaxed), trkEnd);
    viewStart = std::clamp<int64_t>(viewStart, 0, std::max<int64_t>(0, maxTl - 1));
    viewEnd = std::clamp<int64_t>(viewEnd, viewStart + 1, std::max<int64_t>(viewStart + 1, maxTl));
    const int64_t count = std::min<int64_t>(viewEnd - viewStart, 2048);
    const int32_t ch = trk.buffer->channels;

    std::vector<float> out(static_cast<size_t>(count * 2), 0.0f);
    const int64_t ovStart = std::max(viewStart, trk.offsetFrames);
    const int64_t ovEnd = std::min(viewStart + count, trkEnd);
    if (ovEnd > ovStart) {
        const int64_t ovCount = ovEnd - ovStart;
        std::vector<int16_t> raw(static_cast<size_t>(ovCount * ch));
        trk.edl.renderFrames(trk.buffer->samples.data(), trk.buffer->totalFrames, ch, ovStart - trk.offsetFrames, raw.data(), static_cast<int32_t>(ovCount));
        for (int64_t i = 0; i < ovCount; ++i) {
            const int64_t outIdx = (ovStart - viewStart) + i;
            out[static_cast<size_t>(outIdx * 2 + 0)] = raw[static_cast<size_t>(i * ch)] / 32768.0f;
            out[static_cast<size_t>(outIdx * 2 + 1)] = (ch > 1) ? (raw[static_cast<size_t>(i * ch + 1)] / 32768.0f) : out[static_cast<size_t>(outIdx * 2 + 0)];
        }
    }
    return out;
}
