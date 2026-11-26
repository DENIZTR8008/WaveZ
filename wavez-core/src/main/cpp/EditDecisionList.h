#pragma once

#include <cstdint>
#include <vector>

struct AudioRegion {
    int64_t sourceStart = 0;
    int64_t sourceEnd = 0;
    float gain = 1.0f;
    int64_t fadeInSamples = 0;
    int64_t fadeOutSamples = 0;
    bool invert = false;
    bool reverse = false;

    int64_t length() const { return (sourceEnd > sourceStart) ? (sourceEnd - sourceStart) : 0; }
};

class EditDecisionList {
public:
    void reset(int64_t totalSourceFrames);
    int64_t getTotalTimelineFrames() const;
    const std::vector<AudioRegion>& getRegions() const { return regions_; }
    void setRegions(std::vector<AudioRegion> regions) { regions_ = std::move(regions); }

    // Non-destructive editing operations (each pushes an Undo state)
    bool trimToSelection(int64_t selStart, int64_t selEnd);
    bool deleteSelection(int64_t selStart, int64_t selEnd);
    bool splitAt(int64_t pos);
    bool duplicateSelection(int64_t selStart, int64_t selEnd);
    bool rotateAt(int64_t splitPos);

    // Region-level non-destructive DSP attributes
    bool setGainOnSelection(int64_t selStart, int64_t selEnd, float gain);
    bool setFadeInOnSelection(int64_t selStart, int64_t selEnd);
    bool setFadeOutOnSelection(int64_t selStart, int64_t selEnd);
    bool toggleInvertOnSelection(int64_t selStart, int64_t selEnd);
    bool toggleReverseOnSelection(int64_t selStart, int64_t selEnd);

    bool canUndo() const { return !undoStack_.empty(); }
    bool canRedo() const { return !redoStack_.empty(); }
    bool undo();
    bool redo();

    /**
     * Рендерит последовательность фреймов [timelineStart .. timelineStart + numFrames)
     * из активных регионов EDL, применяя gain / fadeIn / fadeOut / invert / reverse.
     */
    void renderFrames(
        const int16_t* srcSamples,
        int64_t srcTotalFrames,
        int32_t channels,
        int64_t timelineStart,
        int16_t* outSamples,
        int32_t numFrames
    ) const;

    /**
     * Строит пирамиду пиков (min/max) для заданного окна таймлайна [viewStart .. viewEnd).
     */
    std::vector<float> computeWaveformPeaks(
        const int16_t* srcSamples,
        int64_t srcTotalFrames,
        int32_t channels,
        int64_t viewStart,
        int64_t viewEnd,
        int32_t targetBins
    ) const;

private:
    void pushUndo();
    void isolateSelection(int64_t selStart, int64_t selEnd);

    std::vector<AudioRegion> regions_;
    std::vector<std::vector<AudioRegion>> undoStack_;
    std::vector<std::vector<AudioRegion>> redoStack_;
};
