#include "WaveZExporter.h"

#include <FLAC/stream_encoder.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <unistd.h>

namespace WaveZExporter {

namespace {

inline void writeU32LE(FILE* f, uint32_t v) {
    uint8_t b[4] = {
        static_cast<uint8_t>(v & 0xFFu),
        static_cast<uint8_t>((v >> 8) & 0xFFu),
        static_cast<uint8_t>((v >> 16) & 0xFFu),
        static_cast<uint8_t>((v >> 24) & 0xFFu)
    };
    std::fwrite(b, 1, 4, f);
}

inline void writeU16LE(FILE* f, uint16_t v) {
    uint8_t b[2] = {
        static_cast<uint8_t>(v & 0xFFu),
        static_cast<uint8_t>((v >> 8) & 0xFFu)
    };
    std::fwrite(b, 1, 2, f);
}

} // namespace

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
) {
    if (!srcSamples || totalFrames <= 0 || channels <= 0) return {};

    if (!rotationEnabled) {
        const int64_t s = std::clamp<int64_t>(startSample, 0, totalFrames - 1);
        const int64_t e = std::clamp<int64_t>(endSample, s + 1, totalFrames);
        const int64_t frames = e - s;
        std::vector<int16_t> out(static_cast<size_t>(frames * channels));
        std::memcpy(
            out.data(),
            srcSamples + s * channels,
            static_cast<size_t>(frames * channels) * sizeof(int16_t)
        );
        return out;
    }

    const int64_t cm2 = std::clamp<int64_t>(m2, 0, totalFrames);
    const int64_t cm3 = std::clamp<int64_t>(m3, cm2, totalFrames);
    const int64_t cm4 = std::clamp<int64_t>(m4, cm3, totalFrames);

    const int64_t headFrames = cm4 - cm3;
    const int64_t bodyFrames = cm3 - cm2;
    const int64_t outFrames = headFrames + bodyFrames;
    if (outFrames <= 0) return {};

    std::vector<int16_t> out(static_cast<size_t>(outFrames * channels));
    if (headFrames > 0) {
        std::memcpy(
            out.data(),
            srcSamples + cm3 * channels,
            static_cast<size_t>(headFrames * channels) * sizeof(int16_t)
        );
    }
    if (bodyFrames > 0) {
        std::memcpy(
            out.data() + headFrames * channels,
            srcSamples + cm2 * channels,
            static_cast<size_t>(bodyFrames * channels) * sizeof(int16_t)
        );
    }

    // 5 ms equal-power crossfade around internal seam (cm4 -> cm2) if margin frames exist
    const int64_t xf = std::min<int64_t>(
        std::max<int64_t>(0, (sampleRate * 5) / 1000),
        std::min(headFrames, bodyFrames)
    );
    const int64_t half = xf / 2;
    if (half > 4 && cm4 + half <= totalFrames && cm2 - half >= 0) {
        const double piHalf = 1.5707963267948966;
        for (int64_t i = 0; i < xf; ++i) {
            const double t = static_cast<double>(i) / static_cast<double>(xf - 1);
            const double wOut = std::cos(t * piHalf);
            const double wIn = std::sin(t * piHalf);
            const int64_t dstFrame = headFrames - half + i;
            const int64_t aFrame = cm4 - half + i;
            const int64_t bFrame = cm2 - half + i;
            for (int32_t c = 0; c < channels; ++c) {
                const double mixed =
                    static_cast<double>(srcSamples[aFrame * channels + c]) * wOut +
                    static_cast<double>(srcSamples[bFrame * channels + c]) * wIn;
                out[dstFrame * channels + c] = static_cast<int16_t>(
                    std::clamp<double>(std::llround(mixed), -32768.0, 32767.0)
                );
            }
        }
    }

    return out;
}

static void writeU32BE(FILE* f, uint32_t v) {
    uint8_t b[4] = {
        static_cast<uint8_t>((v >> 24) & 0xFF),
        static_cast<uint8_t>((v >> 16) & 0xFF),
        static_cast<uint8_t>((v >> 8) & 0xFF),
        static_cast<uint8_t>(v & 0xFF)
    };
    std::fwrite(b, 1, 4, f);
}

static void writeU16BE(FILE* f, uint16_t v) {
    uint8_t b[2] = {
        static_cast<uint8_t>((v >> 8) & 0xFF),
        static_cast<uint8_t>(v & 0xFF)
    };
    std::fwrite(b, 1, 2, f);
}

static void writeF80BE(FILE* f, uint32_t sampleRate) {
    // Convert integer sampleRate to 80-bit IEEE 754 extended float (big-endian)
    uint8_t buf[10] = {0};
    if (sampleRate > 0) {
        int exp = 16383 + 31;
        uint32_t mant = sampleRate;
        while ((mant & 0x80000000u) == 0) {
            mant <<= 1;
            exp--;
        }
        buf[0] = static_cast<uint8_t>((exp >> 8) & 0xFF);
        buf[1] = static_cast<uint8_t>(exp & 0xFF);
        buf[2] = static_cast<uint8_t>((mant >> 24) & 0xFF);
        buf[3] = static_cast<uint8_t>((mant >> 16) & 0xFF);
        buf[4] = static_cast<uint8_t>((mant >> 8) & 0xFF);
        buf[5] = static_cast<uint8_t>(mant & 0xFF);
    }
    std::fwrite(buf, 1, 10, f);
}

bool exportWavToFd(
    int fd,
    const int16_t* samples,
    int64_t totalFrames,
    int32_t sampleRate,
    int32_t channels,
    bool writeSmplChunk,
    uint32_t loopStartFrame,
    uint32_t loopEndFrame,
    int32_t bitsPerSample
) {
    if (fd < 0 || !samples || totalFrames <= 0 || sampleRate <= 0 || channels <= 0) return false;
    const int32_t bps = (bitsPerSample == 24 || bitsPerSample == 32) ? bitsPerSample : 16;
    const uint16_t bytesPerSample = static_cast<uint16_t>(bps / 8);
    const uint16_t fmtTag = (bps == 32) ? 3u : 1u; // 3 = IEEE Float, 1 = PCM

    int dupFd = ::dup(fd);
    if (dupFd < 0) return false;
    FILE* f = ::fdopen(dupFd, "wb");
    if (!f) {
        ::close(dupFd);
        return false;
    }

    const uint32_t dataBytes = static_cast<uint32_t>(totalFrames * channels * bytesPerSample);
    const uint32_t smplSize = writeSmplChunk ? (36u + 24u) : 0u;
    const uint32_t smplTotalChunk = writeSmplChunk ? (8u + smplSize) : 0u;
    const uint32_t riffSize = 4u + (8u + 16u) + (8u + dataBytes) + smplTotalChunk;

    std::fwrite("RIFF", 1, 4, f);
    writeU32LE(f, riffSize);
    std::fwrite("WAVE", 1, 4, f);

    std::fwrite("fmt ", 1, 4, f);
    writeU32LE(f, 16u);
    writeU16LE(f, fmtTag);
    writeU16LE(f, static_cast<uint16_t>(channels));
    writeU32LE(f, static_cast<uint32_t>(sampleRate));
    const uint32_t byteRate = static_cast<uint32_t>(sampleRate * channels * bytesPerSample);
    writeU32LE(f, byteRate);
    writeU16LE(f, static_cast<uint16_t>(channels * bytesPerSample));
    writeU16LE(f, static_cast<uint16_t>(bps));

    std::fwrite("data", 1, 4, f);
    writeU32LE(f, dataBytes);
    const int64_t totalShorts = totalFrames * channels;
    if (bps == 16) {
        std::fwrite(samples, 1, dataBytes, f);
    } else if (bps == 24) {
        std::vector<uint8_t> tmp(static_cast<size_t>(totalShorts * 3));
        for (int64_t i = 0; i < totalShorts; ++i) {
            const int32_t v24 = static_cast<int32_t>(samples[i]) << 8;
            tmp[static_cast<size_t>(i * 3 + 0)] = static_cast<uint8_t>(v24 & 0xFF);
            tmp[static_cast<size_t>(i * 3 + 1)] = static_cast<uint8_t>((v24 >> 8) & 0xFF);
            tmp[static_cast<size_t>(i * 3 + 2)] = static_cast<uint8_t>((v24 >> 16) & 0xFF);
        }
        std::fwrite(tmp.data(), 1, tmp.size(), f);
    } else {
        std::vector<float> tmp(static_cast<size_t>(totalShorts));
        for (int64_t i = 0; i < totalShorts; ++i) {
            tmp[static_cast<size_t>(i)] = static_cast<float>(samples[i]) / 32768.0f;
        }
        std::fwrite(tmp.data(), sizeof(float), tmp.size(), f);
    }

    // smpl chunk (standard SamplerChunk + 1 forward SampleLoop for game engines & vgmstream)
    if (writeSmplChunk) {
        const uint32_t safeStart = std::min<uint32_t>(loopStartFrame, static_cast<uint32_t>(totalFrames - 1));
        const uint32_t safeEnd = std::clamp<uint32_t>(loopEndFrame, safeStart + 1u, static_cast<uint32_t>(totalFrames));
        const uint32_t samplePeriodNs = static_cast<uint32_t>(1000000000.0 / static_cast<double>(sampleRate));

        std::fwrite("smpl", 1, 4, f);
        writeU32LE(f, smplSize);
        writeU32LE(f, 0u);              // manufacturer
        writeU32LE(f, 0u);              // product
        writeU32LE(f, samplePeriodNs);  // sample period (ns)
        writeU32LE(f, 60u);             // MIDI unity note (C4)
        writeU32LE(f, 0u);              // MIDI pitch fraction
        writeU32LE(f, 0u);              // SMPTE format
        writeU32LE(f, 0u);              // SMPTE offset
        writeU32LE(f, 1u);              // num sample loops
        writeU32LE(f, 0u);              // sampler data

        // SampleLoop struct (24 bytes)
        writeU32LE(f, 0u);              // cue point ID
        writeU32LE(f, 0u);              // type = 0 (forward loop)
        writeU32LE(f, safeStart);       // start sample
        writeU32LE(f, safeEnd - 1u);    // end sample (inclusive in WAV smpl spec)
        writeU32LE(f, 0u);              // fraction
        writeU32LE(f, 0u);              // play count (0 = infinite)
    }

    std::fflush(f);
    std::fclose(f);
    return true;
}

bool exportFlacToFd(
    int fd,
    const int16_t* samples,
    int64_t totalFrames,
    int32_t sampleRate,
    int32_t channels,
    int32_t bitsPerSample
) {
    if (fd < 0 || !samples || totalFrames <= 0 || sampleRate <= 0 || channels <= 0) return false;
    const int32_t bps = (bitsPerSample == 24) ? 24 : 16;

    int dupFd = ::dup(fd);
    if (dupFd < 0) return false;
    FILE* f = ::fdopen(dupFd, "wb");
    if (!f) {
        ::close(dupFd);
        return false;
    }

    FLAC__StreamEncoder* encoder = FLAC__stream_encoder_new();
    if (!encoder) {
        std::fclose(f);
        return false;
    }

    FLAC__bool ok = true;
    ok &= FLAC__stream_encoder_set_verify(encoder, false);
    ok &= FLAC__stream_encoder_set_compression_level(encoder, 8);
    ok &= FLAC__stream_encoder_set_channels(encoder, static_cast<uint32_t>(channels));
    ok &= FLAC__stream_encoder_set_bits_per_sample(encoder, static_cast<uint32_t>(bps));
    ok &= FLAC__stream_encoder_set_sample_rate(encoder, static_cast<uint32_t>(sampleRate));
    ok &= FLAC__stream_encoder_set_total_samples_estimate(encoder, static_cast<FLAC__uint64>(totalFrames));

    if (!ok || FLAC__stream_encoder_init_FILE(encoder, f, nullptr, nullptr) != FLAC__STREAM_ENCODER_INIT_STATUS_OK) {
        FLAC__stream_encoder_delete(encoder);
        std::fclose(f);
        return false;
    }

    constexpr int64_t CHUNK_FRAMES = 4096;
    std::vector<FLAC__int32> pcm32(static_cast<size_t>(CHUNK_FRAMES * channels));

    int64_t frameOffset = 0;
    while (frameOffset < totalFrames && ok) {
        const int64_t todo = std::min<int64_t>(CHUNK_FRAMES, totalFrames - frameOffset);
        const int64_t count = todo * channels;
        const int16_t* src = samples + frameOffset * channels;
        for (int64_t i = 0; i < count; ++i) {
            pcm32[static_cast<size_t>(i)] = (bps == 24)
                ? (static_cast<FLAC__int32>(src[i]) << 8)
                : static_cast<FLAC__int32>(src[i]);
        }
        ok = FLAC__stream_encoder_process_interleaved(encoder, pcm32.data(), static_cast<uint32_t>(todo));
        frameOffset += todo;
    }

    ok &= FLAC__stream_encoder_finish(encoder);
    FLAC__stream_encoder_delete(encoder);
    // Note: FLAC__stream_encoder_finish closes the FILE* opened via init_FILE
    return ok != 0;
}

bool exportAiffToFd(
    int fd,
    const int16_t* samples,
    int64_t totalFrames,
    int32_t sampleRate,
    int32_t channels
) {
    if (fd < 0 || !samples || totalFrames <= 0 || sampleRate <= 0 || channels <= 0) return false;
    int dupFd = ::dup(fd);
    if (dupFd < 0) return false;
    FILE* f = ::fdopen(dupFd, "wb");
    if (!f) { ::close(dupFd); return false; }

    const uint32_t dataBytes = static_cast<uint32_t>(totalFrames * channels * 2);
    const uint32_t formSize = 4u + (8u + 18u) + (8u + 8u + dataBytes);

    std::fwrite("FORM", 1, 4, f);
    writeU32BE(f, formSize);
    std::fwrite("AIFF", 1, 4, f);

    // COMM chunk (18 bytes)
    std::fwrite("COMM", 1, 4, f);
    writeU32BE(f, 18u);
    writeU16BE(f, static_cast<uint16_t>(channels));
    writeU32BE(f, static_cast<uint32_t>(totalFrames));
    writeU16BE(f, 16u);
    writeF80BE(f, static_cast<uint32_t>(sampleRate));

    // SSND chunk (8 + dataBytes)
    std::fwrite("SSND", 1, 4, f);
    writeU32BE(f, 8u + dataBytes);
    writeU32BE(f, 0u); // offset
    writeU32BE(f, 0u); // blockSize

    const int64_t totalShorts = totalFrames * channels;
    std::vector<uint8_t> beBuf(static_cast<size_t>(dataBytes));
    for (int64_t i = 0; i < totalShorts; ++i) {
        const uint16_t u = static_cast<uint16_t>(samples[i]);
        beBuf[static_cast<size_t>(i * 2 + 0)] = static_cast<uint8_t>((u >> 8) & 0xFF);
        beBuf[static_cast<size_t>(i * 2 + 1)] = static_cast<uint8_t>(u & 0xFF);
    }
    std::fwrite(beBuf.data(), 1, beBuf.size(), f);
    std::fflush(f);
    std::fclose(f);
    return true;
}

} // namespace WaveZExporter
