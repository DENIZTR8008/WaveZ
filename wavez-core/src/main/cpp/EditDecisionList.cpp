#include "EditDecisionList.h"
#include <algorithm>
#include <cmath>
#include <cstring>

void EditDecisionList::reset(int64_t totalSourceFrames) {
    undoStack_.clear();
    redoStack_.clear();
    regions_.clear();
    if (totalSourceFrames > 0) {
        AudioRegion r;
        r.sourceStart = 0;
        r.sourceEnd = totalSourceFrames;
        regions_.push_back(r);
    }
}

int64_t EditDecisionList::getTotalTimelineFrames() const {
    int64_t total = 0;
    for (const auto& r : regions_) {
        total += r.length();
    }
    return total;
}

void EditDecisionList::pushUndo() {
    undoStack_.push_back(regions_);
    if (undoStack_.size() > 64) {
        undoStack_.erase(undoStack_.begin());
    }
    redoStack_.clear();
}

bool EditDecisionList::undo() {
    if (undoStack_.empty()) return false;
    redoStack_.push_back(regions_);
    regions_ = std::move(undoStack_.back());
    undoStack_.pop_back();
    return true;
}

bool EditDecisionList::redo() {
    if (redoStack_.empty()) return false;
    undoStack_.push_back(regions_);
    regions_ = std::move(redoStack_.back());
    redoStack_.pop_back();
    return true;
}

void EditDecisionList::isolateSelection(int64_t selStart, int64_t selEnd) {
    if (selEnd <= selStart) return;
    std::vector<AudioRegion> next;
    next.reserve(regions_.size() + 2);

    int64_t cur = 0;
    for (const auto& r : regions_) {
        const int64_t len = r.length();
        const int64_t rStart = cur;
        const int64_t rEnd = cur + len;
        cur = rEnd;

        if (rEnd <= selStart || rStart >= selEnd) {
            next.push_back(r);
            continue;
        }

        auto makeSub = [&](int64_t localA, int64_t localB) {
            AudioRegion sub = r;
            if (!r.reverse) {
                sub.sourceStart = r.sourceStart + localA;
                sub.sourceEnd = r.sourceStart + localB;
            } else {
                sub.sourceStart = r.sourceEnd - localB;
                sub.sourceEnd = r.sourceEnd - localA;
            }
            sub.fadeInSamples = (localA == 0) ? std::min(r.fadeInSamples, sub.length()) : 0;
            sub.fadeOutSamples = (localB == len) ? std::min(r.fadeOutSamples, sub.length()) : 0;
            return sub;
        };

        const int64_t cutA = std::max<int64_t>(0, selStart - rStart);
        const int64_t cutB = std::min<int64_t>(len, selEnd - rStart);

        if (cutA > 0) next.push_back(makeSub(0, cutA));
        if (cutB > cutA) next.push_back(makeSub(cutA, cutB));
        if (cutB < len) next.push_back(makeSub(cutB, len));
    }

    regions_ = std::move(next);
}

bool EditDecisionList::splitAt(int64_t pos) {
    const int64_t total = getTotalTimelineFrames();
    if (pos <= 0 || pos >= total) return false;
    pushUndo();
    isolateSelection(pos, total);
    return true;
}

bool EditDecisionList::trimToSelection(int64_t selStart, int64_t selEnd) {
    const int64_t total = getTotalTimelineFrames();
    selStart = std::clamp<int64_t>(selStart, 0, total);
    selEnd = std::clamp<int64_t>(selEnd, selStart, total);
    if (selEnd <= selStart || (selStart == 0 && selEnd == total)) return false;

    pushUndo();
    isolateSelection(selStart, selEnd);

    std::vector<AudioRegion> kept;
    int64_t cur = 0;
    for (const auto& r : regions_) {
        const int64_t len = r.length();
        const int64_t rStart = cur;
        const int64_t rEnd = cur + len;
        cur = rEnd;
        if (rStart >= selStart && rEnd <= selEnd) {
            kept.push_back(r);
        }
    }
    regions_ = std::move(kept);
    return true;
}

bool EditDecisionList::deleteSelection(int64_t selStart, int64_t selEnd) {
    const int64_t total = getTotalTimelineFrames();
    selStart = std::clamp<int64_t>(selStart, 0, total);
    selEnd = std::clamp<int64_t>(selEnd, selStart, total);
    if (selEnd <= selStart || (selStart == 0 && selEnd == total)) return false;

    pushUndo();
    isolateSelection(selStart, selEnd);

    std::vector<AudioRegion> kept;
    int64_t cur = 0;
    for (const auto& r : regions_) {
        const int64_t len = r.length();
        const int64_t rStart = cur;
        const int64_t rEnd = cur + len;
        cur = rEnd;
        if (rEnd <= selStart || rStart >= selEnd) {
            kept.push_back(r);
        }
    }
    regions_ = std::move(kept);
    return true;
}

bool EditDecisionList::duplicateSelection(int64_t selStart, int64_t selEnd) {
    const int64_t total = getTotalTimelineFrames();
    selStart = std::clamp<int64_t>(selStart, 0, total);
    selEnd = std::clamp<int64_t>(selEnd, selStart, total);
    if (selEnd <= selStart) return false;

    pushUndo();
    isolateSelection(selStart, selEnd);

    std::vector<AudioRegion> selected;
    std::vector<AudioRegion> next;
    int64_t cur = 0;
    bool inserted = false;
    for (const auto& r : regions_) {
        const int64_t len = r.length();
        const int64_t rStart = cur;
        const int64_t rEnd = cur + len;
        cur = rEnd;
        next.push_back(r);
        if (rStart >= selStart && rEnd <= selEnd) {
            selected.push_back(r);
        } else if (!inserted && rStart >= selEnd) {
            next.insert(next.end() - 1, selected.begin(), selected.end());
            inserted = true;
        }
    }
    if (!inserted && !selected.empty()) {
        next.insert(next.end(), selected.begin(), selected.end());
    }
    regions_ = std::move(next);
    return true;
}

bool EditDecisionList::rotateAt(int64_t splitPos) {
    const int64_t total = getTotalTimelineFrames();
    if (splitPos <= 0 || splitPos >= total) return false;

    pushUndo();
    isolateSelection(splitPos, total);

    std::vector<AudioRegion> head;
    std::vector<AudioRegion> tail;
    int64_t cur = 0;
    for (const auto& r : regions_) {
        const int64_t len = r.length();
        if (cur >= splitPos) {
            head.push_back(r);
        } else {
            tail.push_back(r);
        }
        cur += len;
    }
    head.insert(head.end(), tail.begin(), tail.end());
    regions_ = std::move(head);
    return true;
}

bool EditDecisionList::setGainOnSelection(int64_t selStart, int64_t selEnd, float gain) {
    const int64_t total = getTotalTimelineFrames();
    selStart = std::clamp<int64_t>(selStart, 0, total);
    selEnd = std::clamp<int64_t>(selEnd, selStart, total);
    if (selEnd <= selStart) return false;

    pushUndo();
    isolateSelection(selStart, selEnd);
    int64_t cur = 0;
    for (auto& r : regions_) {
        const int64_t len = r.length();
        if (cur >= selStart && cur + len <= selEnd) {
            r.gain = std::clamp(r.gain * gain, 0.0f, 8.0f);
        }
        cur += len;
    }
    return true;
}

bool EditDecisionList::setFadeInOnSelection(int64_t selStart, int64_t selEnd) {
    const int64_t total = getTotalTimelineFrames();
    selStart = std::clamp<int64_t>(selStart, 0, total);
    selEnd = std::clamp<int64_t>(selEnd, selStart, total);
    if (selEnd <= selStart) return false;

    pushUndo();
    isolateSelection(selStart, selEnd);
    int64_t cur = 0;
    for (auto& r : regions_) {
        const int64_t len = r.length();
        if (cur >= selStart && cur + len <= selEnd) {
            r.fadeInSamples = len;
        }
        cur += len;
    }
    return true;
}

bool EditDecisionList::setFadeOutOnSelection(int64_t selStart, int64_t selEnd) {
    const int64_t total = getTotalTimelineFrames();
    selStart = std::clamp<int64_t>(selStart, 0, total);
    selEnd = std::clamp<int64_t>(selEnd, selStart, total);
    if (selEnd <= selStart) return false;

    pushUndo();
    isolateSelection(selStart, selEnd);
    int64_t cur = 0;
    for (auto& r : regions_) {
        const int64_t len = r.length();
        if (cur >= selStart && cur + len <= selEnd) {
            r.fadeOutSamples = len;
        }
        cur += len;
    }
    return true;
}

bool EditDecisionList::toggleInvertOnSelection(int64_t selStart, int64_t selEnd) {
    const int64_t total = getTotalTimelineFrames();
    selStart = std::clamp<int64_t>(selStart, 0, total);
    selEnd = std::clamp<int64_t>(selEnd, selStart, total);
    if (selEnd <= selStart) return false;

    pushUndo();
    isolateSelection(selStart, selEnd);
    int64_t cur = 0;
    for (auto& r : regions_) {
        const int64_t len = r.length();
        if (cur >= selStart && cur + len <= selEnd) {
            r.invert = !r.invert;
        }
        cur += len;
    }
    return true;
}

bool EditDecisionList::toggleReverseOnSelection(int64_t selStart, int64_t selEnd) {
    const int64_t total = getTotalTimelineFrames();
    selStart = std::clamp<int64_t>(selStart, 0, total);
    selEnd = std::clamp<int64_t>(selEnd, selStart, total);
    if (selEnd <= selStart) return false;

    pushUndo();
    isolateSelection(selStart, selEnd);

    // Reverse order of regions inside selection and flip each region's reverse flag
    size_t firstIdx = regions_.size();
    size_t lastIdx = 0;
    int64_t cur = 0;
    for (size_t i = 0; i < regions_.size(); ++i) {
        const int64_t len = regions_[i].length();
        if (cur >= selStart && cur + len <= selEnd) {
            if (firstIdx == regions_.size()) firstIdx = i;
            lastIdx = i;
            regions_[i].reverse = !regions_[i].reverse;
            std::swap(regions_[i].fadeInSamples, regions_[i].fadeOutSamples);
        }
        cur += len;
    }
    if (firstIdx < regions_.size() && lastIdx >= firstIdx) {
        std::reverse(regions_.begin() + firstIdx, regions_.begin() + lastIdx + 1);
    }
    return true;
}

void EditDecisionList::renderFrames(
    const int16_t* srcSamples,
    int64_t srcTotalFrames,
    int32_t channels,
    int64_t timelineStart,
    int16_t* outSamples,
    int32_t numFrames
) const {
    if (!outSamples || numFrames <= 0 || channels <= 0) return;
    std::memset(outSamples, 0, static_cast<size_t>(numFrames * channels) * sizeof(int16_t));
    if (!srcSamples || srcTotalFrames <= 0 || regions_.empty()) return;

    const int64_t reqStart = timelineStart;
    const int64_t reqEnd = timelineStart + numFrames;

    int64_t cur = 0;
    for (const auto& r : regions_) {
        const int64_t len = r.length();
        const int64_t rStart = cur;
        const int64_t rEnd = cur + len;
        cur = rEnd;
        if (len <= 0 || rEnd <= reqStart || rStart >= reqEnd) continue;

        const int64_t overlapStart = std::max(reqStart, rStart);
        const int64_t overlapEnd = std::min(reqEnd, rEnd);

        for (int64_t t = overlapStart; t < overlapEnd; ++t) {
            const int64_t localPos = t - rStart;
            const int64_t srcFrame = r.reverse ? (r.sourceEnd - 1 - localPos) : (r.sourceStart + localPos);
            if (srcFrame < 0 || srcFrame >= srcTotalFrames) continue;

            float env = r.gain;
            if (r.fadeInSamples > 0 && localPos < r.fadeInSamples) {
                env *= static_cast<float>(localPos) / static_cast<float>(r.fadeInSamples);
            }
            if (r.fadeOutSamples > 0 && (len - 1 - localPos) < r.fadeOutSamples) {
                env *= static_cast<float>(len - 1 - localPos) / static_cast<float>(r.fadeOutSamples);
            }
            if (r.invert) env = -env;

            const int64_t outIdx = (t - reqStart) * channels;
            const int64_t srcIdx = srcFrame * channels;
            for (int32_t c = 0; c < channels; ++c) {
                const float v = static_cast<float>(srcSamples[srcIdx + c]) * env;
                outSamples[outIdx + c] = static_cast<int16_t>(std::clamp(std::lround(v), -32768L, 32767L));
            }
        }
    }
}

std::vector<float> EditDecisionList::computeWaveformPeaks(
    const int16_t* srcSamples,
    int64_t srcTotalFrames,
    int32_t channels,
    int64_t viewStart,
    int64_t viewEnd,
    int32_t targetBins
) const {
    if (!srcSamples || srcTotalFrames <= 0 || channels <= 0 || targetBins <= 0) return {};
    const int64_t total = getTotalTimelineFrames();
    if (total <= 0) return {};

    viewStart = std::clamp<int64_t>(viewStart, 0, total);
    viewEnd = std::clamp<int64_t>(viewEnd, viewStart + 1, total);
    const int64_t viewLen = viewEnd - viewStart;
    const int32_t bins = std::min<int32_t>(targetBins, static_cast<int32_t>(viewLen));

    std::vector<float> peaks(static_cast<size_t>(bins), 0.0f);
    std::vector<int16_t> tmp(static_cast<size_t>(channels));

    for (int32_t b = 0; b < bins; ++b) {
        const int64_t bStart = viewStart + (static_cast<int64_t>(b) * viewLen) / bins;
        const int64_t bEnd = viewStart + (static_cast<int64_t>(b + 1) * viewLen) / bins;
        const int64_t step = std::max<int64_t>(1, (bEnd - bStart) / 32);
        float maxPeak = 0.0f;
        for (int64_t f = bStart; f < bEnd; f += step) {
            renderFrames(srcSamples, srcTotalFrames, channels, f, tmp.data(), 1);
            const float amp = std::abs(static_cast<float>(tmp[0])) / 32768.0f;
            if (amp > maxPeak) maxPeak = amp;
        }
        peaks[static_cast<size_t>(b)] = maxPeak;
    }
    return peaks;
}
