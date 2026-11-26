#pragma once

#include <cstdint>
#include <vector>

namespace WaveZExporter {

/**
 * Собирает выходной буфер (обычный срез [start..end) или ротация [m3..m4) + [m2..m3) с 5мс кроссфейдом).
 */
std::vector<int16_t> buildOutputSamples(
    const int16_t* srcSamples,
    int64_t totalFrames,
    int32_t channels,
    int32_t sampleRate,
    int64_t startSample,
    int64_t endSample,
    bool rotationEnabled,
    int64_t m2,
    int64_t m3,
    int64_t m4
);

/**
 * Экспорт в 16/24-bit PCM или 32-bit Float WAV по file descriptor (с опциональным чанком smpl).
 */
bool exportWavToFd(
    int fd,
    const int16_t* samples,
    int64_t totalFrames,
    int32_t sampleRate,
    int32_t channels,
    bool writeSmplChunk,
    uint32_t loopStartFrame,
    uint32_t loopEndFrame,
    int32_t bitsPerSample = 16
);

/**
 * Экспорт в несжатый студийный AIFF (16-bit Big-Endian PCM) по file descriptor.
 */
bool exportAiffToFd(
    int fd,
    const int16_t* samples,
    int64_t totalFrames,
    int32_t sampleRate,
    int32_t channels
);

/**
 * Прямой экспорт в 16-bit FLAC через libFLAC по file descriptor.
 */
bool exportFlacToFd(
    int fd,
    const int16_t* samples,
    int64_t totalFrames,
    int32_t sampleRate,
    int32_t channels,
    int32_t bitsPerSample = 16
);

} // namespace WaveZExporter
