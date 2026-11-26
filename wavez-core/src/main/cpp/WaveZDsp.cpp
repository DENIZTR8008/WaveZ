#include "WaveZDsp.h"
#include <SoundTouch.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <vector>

namespace {

constexpr double kPi = 3.14159265358979323846;

inline int16_t floatToInt16(double v) {
    const double scaled = std::clamp(v * 32767.0, -32768.0, 32767.0);
    return static_cast<int16_t>(std::llround(scaled));
}

inline float ampToDb(double amp) {
    if (amp <= 1e-6) return -96.0f;
    return static_cast<float>(std::max(-96.0, 20.0 * std::log10(amp)));
}

// Solves symmetric positive-definite / linear system A * x = b via Gaussian elimination with partial pivoting
bool solveLinearSystem(std::vector<double>& A, std::vector<double>& b, int n, std::vector<double>& x) {
    x.assign(n, 0.0);
    for (int col = 0; col < n; ++col) {
        int pivot = col;
        double maxVal = std::abs(A[col * n + col]);
        for (int row = col + 1; row < n; ++row) {
            double v = std::abs(A[row * n + col]);
            if (v > maxVal) {
                maxVal = v;
                pivot = row;
            }
        }
        if (maxVal < 1e-12) {
            return false;
        }
        if (pivot != col) {
            for (int k = col; k < n; ++k) {
                std::swap(A[col * n + k], A[pivot * n + k]);
            }
            std::swap(b[col], b[pivot]);
        }
        const double diag = A[col * n + col];
        for (int row = col + 1; row < n; ++row) {
            const double factor = A[row * n + col] / diag;
            for (int k = col; k < n; ++k) {
                A[row * n + k] -= factor * A[col * n + k];
            }
            b[row] -= factor * b[col];
        }
    }
    for (int row = n - 1; row >= 0; --row) {
        double sum = b[row];
        for (int k = row + 1; k < n; ++k) {
            sum -= A[row * n + k] * x[k];
        }
        x[row] = sum / A[row * n + row];
    }
    return true;
}

} // namespace

namespace WaveZDsp {

bool repairSamplesLSAR(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t totalFrames,
    int64_t badStart,
    int64_t badLen
) {
    if (totalFrames < 16 || channels <= 0) return false;
    badLen = std::clamp<int64_t>(badLen, 1, 128);
    badStart = std::clamp<int64_t>(badStart, 0, totalFrames - badLen);

    const int64_t leftContext = std::min<int64_t>(badStart, std::max<int64_t>(badLen * 4, 256));
    const int64_t rightContext = std::min<int64_t>(totalFrames - (badStart + badLen), std::max<int64_t>(badLen * 4, 256));
    const int64_t winStart = badStart - leftContext;
    const int64_t winLen = leftContext + badLen + rightContext;
    if (winLen <= badLen + 8) return false;

    const int P = static_cast<int>(std::clamp<int64_t>(badLen * 2 + 1, 8, std::min<int64_t>(48, winLen / 4)));
    const int badOffset = static_cast<int>(leftContext);
    const int badCount = static_cast<int>(badLen);

    for (int32_t ch = 0; ch < channels; ++ch) {
        std::vector<double> y(static_cast<size_t>(winLen), 0.0);
        for (int64_t i = 0; i < winLen; ++i) {
            y[static_cast<size_t>(i)] = interleaved[static_cast<size_t>((winStart + i) * channels + ch)] / 32768.0;
        }

        // 1. Estimate AR coefficients a[1..P] from good samples outside [badOffset .. badOffset + badCount)
        std::vector<double> r(static_cast<size_t>(P + 1), 0.0);
        for (int lag = 0; lag <= P; ++lag) {
            double sum = 0.0;
            int count = 0;
            for (int t = P; t < static_cast<int>(winLen); ++t) {
                const bool tBad = (t >= badOffset && t < badOffset + badCount);
                const bool lagBad = ((t - lag) >= badOffset && (t - lag) < badOffset + badCount);
                if (!tBad && !lagBad) {
                    sum += y[t] * y[t - lag];
                    count++;
                }
            }
            r[lag] = (count > 0) ? (sum / count) : 0.0;
        }
        r[0] += 1e-6; // Tikhonov regularization for stability

        std::vector<double> A(static_cast<size_t>(P * P), 0.0);
        std::vector<double> b(static_cast<size_t>(P), 0.0);
        for (int i = 0; i < P; ++i) {
            b[i] = r[i + 1];
            for (int j = 0; j < P; ++j) {
                A[i * P + j] = r[std::abs(i - j)];
            }
        }
        std::vector<double> ar(static_cast<size_t>(P), 0.0);
        if (!solveLinearSystem(A, b, P, ar)) {
            // Fallback: linear crossfade across gap
            const double y0 = (badOffset > 0) ? y[badOffset - 1] : 0.0;
            const double y1 = (badOffset + badCount < winLen) ? y[badOffset + badCount] : 0.0;
            for (int k = 0; k < badCount; ++k) {
                const double t = static_cast<double>(k + 1) / (badCount + 1);
                const double val = y0 * (1.0 - t) + y1 * t;
                interleaved[static_cast<size_t>((badStart + k) * channels + ch)] = floatToInt16(val);
            }
            continue;
        }

        // 2. Solve LSAR quadratic minimization for unknown samples u[0..badCount-1]:
        // Minimize sum_t (e_t)^2 where e_t = y[t] - sum_{k=1..P} ar[k-1] * y[t-k]
        std::vector<double> coeffs(static_cast<size_t>(P + 1), 0.0);
        coeffs[0] = 1.0;
        for (int k = 1; k <= P; ++k) coeffs[k] = -ar[k - 1];

        std::vector<double> H(static_cast<size_t>(badCount * badCount), 0.0);
        std::vector<double> g(static_cast<size_t>(badCount), 0.0);

        const int tStart = std::max(P, badOffset);
        const int tEnd = std::min(static_cast<int>(winLen), badOffset + badCount + P);

        for (int t = tStart; t < tEnd; ++t) {
            double knownSum = 0.0;
            for (int k = 0; k <= P; ++k) {
                const int idx = t - k;
                const int uIdx = idx - badOffset;
                if (uIdx < 0 || uIdx >= badCount) {
                    knownSum += coeffs[k] * y[idx];
                }
            }
            for (int i = 0; i <= P; ++i) {
                const int ui = (t - i) - badOffset;
                if (ui < 0 || ui >= badCount) continue;
                g[ui] -= coeffs[i] * knownSum;
                for (int j = 0; j <= P; ++j) {
                    const int uj = (t - j) - badOffset;
                    if (uj < 0 || uj >= badCount) continue;
                    H[ui * badCount + uj] += coeffs[i] * coeffs[j];
                }
            }
        }
        for (int i = 0; i < badCount; ++i) {
            H[i * badCount + i] += 1e-9;
        }

        std::vector<double> repaired;
        if (solveLinearSystem(H, g, badCount, repaired)) {
            for (int k = 0; k < badCount; ++k) {
                interleaved[static_cast<size_t>((badStart + k) * channels + ch)] = floatToInt16(repaired[k]);
            }
        }
    }
    return true;
}

void normalize(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float targetPeakDb,
    bool removeDcOffset
) {
    if (channels <= 0 || selEnd <= selStart) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const int64_t count = selEnd - selStart;
    std::vector<double> dc(static_cast<size_t>(channels), 0.0);

    if (removeDcOffset) {
        for (int64_t f = selStart; f < selEnd; ++f) {
            for (int32_t ch = 0; ch < channels; ++ch) {
                dc[ch] += interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            }
        }
        for (int32_t ch = 0; ch < channels; ++ch) {
            dc[ch] /= static_cast<double>(count);
        }
    }

    double maxPeak = 0.0;
    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            double v = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0 - dc[ch];
            maxPeak = std::max(maxPeak, std::abs(v));
        }
    }
    if (maxPeak < 1e-6) return;

    const double targetAmp = std::pow(10.0, targetPeakDb / 20.0);
    const double gain = targetAmp / maxPeak;

    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            double v = (interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0 - dc[ch]) * gain;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(v);
        }
    }
}

void amplify(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float gainDb
) {
    if (channels <= 0 || selEnd <= selStart) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const double gain = std::pow(10.0, gainDb / 20.0);

    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            double v = (interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0) * gain;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(v);
        }
    }
}

void silence(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd
) {
    if (channels <= 0 || selEnd <= selStart) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    std::fill(
        interleaved.begin() + static_cast<size_t>(selStart * channels),
        interleaved.begin() + static_cast<size_t>(selEnd * channels),
        static_cast<int16_t>(0)
    );
}

static double besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    const double halfX = 0.5 * x;
    for (int k = 1; k <= 20; ++k) {
        term *= (halfX / k);
        sum += term * term;
    }
    return sum;
}

std::vector<int16_t> changeSpeed(
    const int16_t* src,
    int64_t srcFrames,
    int32_t channels,
    double speedRatio
) {
    if (!src || srcFrames <= 2 || channels <= 0 || speedRatio <= 0.05) return {};
    const int64_t dstFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(srcFrames / speedRatio)));
    std::vector<int16_t> out(static_cast<size_t>(dstFrames * channels));

    // High-quality 32-tap (half-width 16) Kaiser-windowed Sinc Resampler (beta = 7.0)
    constexpr int32_t kHalfTaps = 16;
    constexpr double kBeta = 7.0;
    const double invI0Beta = 1.0 / besselI0(kBeta);
    const double cutoff = (speedRatio > 1.0) ? (0.96 / speedRatio) : 0.96;

    for (int64_t i = 0; i < dstFrames; ++i) {
        const double pos = i * speedRatio;
        const int64_t center = static_cast<int64_t>(std::floor(pos));
        for (int32_t ch = 0; ch < channels; ++ch) {
            double acc = 0.0;
            double wSum = 0.0;
            for (int32_t k = -kHalfTaps + 1; k <= kHalfTaps; ++k) {
                const int64_t idx = std::clamp<int64_t>(center + k, 0, srcFrames - 1);
                const double dx = pos - static_cast<double>(center + k);
                const double normPos = dx / kHalfTaps;
                if (std::abs(normPos) >= 1.0) continue;

                const double arg = kPi * cutoff * dx;
                const double sinc = (std::abs(arg) < 1e-8) ? cutoff : (std::sin(arg) / (kPi * dx));
                const double win = besselI0(kBeta * std::sqrt(1.0 - normPos * normPos)) * invI0Beta;
                const double w = sinc * win;
                acc += (src[static_cast<size_t>(idx * channels + ch)] / 32768.0) * w;
                wSum += w;
            }
            const double val = (wSum > 1e-9) ? (acc / wSum) : 0.0;
            out[static_cast<size_t>(i * channels + ch)] = floatToInt16(val);
        }
    }
    return out;
}

std::vector<int16_t> processSoundTouch(
    const int16_t* src,
    int64_t srcFrames,
    int32_t sampleRate,
    int32_t channels,
    double tempoRatio,
    double pitchSemitones
) {
    if (!src || srcFrames <= 0 || channels <= 0 || sampleRate <= 0) return {};

    soundtouch::SoundTouch st;
    st.setSampleRate(static_cast<uint32_t>(sampleRate));
    st.setChannels(static_cast<uint32_t>(channels));
    st.setTempo(tempoRatio);
    st.setPitchSemiTones(pitchSemitones);
    st.setSetting(SETTING_USE_AA_FILTER, 1);
    st.setSetting(SETTING_USE_QUICKSEEK, 0);

    constexpr int64_t kChunk = 4096;
    std::vector<float> inBuf(static_cast<size_t>(kChunk * channels));
    std::vector<float> outBuf(static_cast<size_t>(kChunk * channels));
    std::vector<int16_t> result;
    result.reserve(static_cast<size_t>((srcFrames / std::max(0.25, tempoRatio)) * channels));

    auto drain = [&]() {
        uint32_t n = 0;
        do {
            n = st.receiveSamples(outBuf.data(), static_cast<uint32_t>(kChunk));
            for (uint32_t i = 0; i < n * static_cast<uint32_t>(channels); ++i) {
                result.push_back(floatToInt16(outBuf[i]));
            }
        } while (n != 0);
    };

    for (int64_t offset = 0; offset < srcFrames; offset += kChunk) {
        const int64_t todo = std::min(kChunk, srcFrames - offset);
        for (int64_t i = 0; i < todo * channels; ++i) {
            inBuf[static_cast<size_t>(i)] = src[static_cast<size_t>(offset * channels + i)] / 32768.0f;
        }
        st.putSamples(inBuf.data(), static_cast<uint32_t>(todo));
        drain();
    }
    st.flush();
    drain();
    return result;
}

std::vector<int16_t> generateSignal(
    int32_t type,
    double freqHz,
    float amplitude,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
) {
    const int64_t totalFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(durationSec * sampleRate)));
    std::vector<int16_t> out(static_cast<size_t>(totalFrames * channels), 0);
    const double amp = std::clamp<double>(amplitude, 0.0, 1.0);
    uint32_t rng = 0x12345678u;
    double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
    double brown = 0.0;

    for (int64_t f = 0; f < totalFrames; ++f) {
        const double t = static_cast<double>(f) / sampleRate;
        const double phase = std::fmod(t * freqHz, 1.0);
        double sample = 0.0;
        auto nextWhite = [&]() -> double {
            rng = rng * 1664525u + 1013904223u;
            return (static_cast<double>(rng) / 2147483648.0) - 1.0;
        };
        switch (type) {
            case 0: // Sine
                sample = std::sin(2.0 * kPi * freqHz * t);
                break;
            case 1: // Square
                sample = (phase < 0.5) ? 1.0 : -1.0;
                break;
            case 2: // Sawtooth
                sample = 2.0 * phase - 1.0;
                break;
            case 4: { // Pink noise (Paul Kellet)
                const double w = nextWhite();
                b0 = 0.99886 * b0 + w * 0.0555179;
                b1 = 0.99332 * b1 + w * 0.0750759;
                b2 = 0.96900 * b2 + w * 0.1538520;
                b3 = 0.86650 * b3 + w * 0.3104856;
                b4 = 0.55000 * b4 + w * 0.5329522;
                b5 = -0.7616 * b5 - w * 0.0168980;
                sample = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
                b6 = w * 0.115926;
                break;
            }
            case 5: { // Brownian noise
                brown = std::clamp(brown + nextWhite() * 0.04, -1.0, 1.0);
                sample = brown * 1.5;
                break;
            }
            case 3: // White noise
            default:
                sample = nextWhite();
                break;
        }
        const int16_t s16 = floatToInt16(sample * amp);
        for (int32_t ch = 0; ch < channels; ++ch) {
            out[static_cast<size_t>(f * channels + ch)] = s16;
        }
    }
    return out;
}

int32_t getZeroCrossingSlope(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t frame
) {
    if (!src || totalFrames < 2 || channels <= 0) return 0;
    frame = std::clamp<int64_t>(frame, 1, totalFrames - 1);
    double prev = 0.0, cur = 0.0;
    for (int32_t ch = 0; ch < channels; ++ch) {
        prev += src[static_cast<size_t>((frame - 1) * channels + ch)];
        cur += src[static_cast<size_t>(frame * channels + ch)];
    }
    return (cur >= prev) ? 1 : -1;
}

int64_t findNearestZeroCrossing(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t targetFrame,
    int32_t requiredSlope,
    int64_t maxRadius
) {
    if (!src || totalFrames < 2 || channels <= 0) return targetFrame;
    targetFrame = std::clamp<int64_t>(targetFrame, 1, totalFrames - 1);

    auto isZeroCross = [&](int64_t f) -> bool {
        if (f <= 0 || f >= totalFrames) return false;
        const double l0 = src[static_cast<size_t>((f - 1) * channels)];
        const double l1 = src[static_cast<size_t>(f * channels)];
        const bool lRise = (l0 <= 0.0 && l1 >= 0.0);
        const bool lFall = (l0 >= 0.0 && l1 <= 0.0);
        if (channels == 1) {
            if (requiredSlope > 0) return lRise;
            if (requiredSlope < 0) return lFall;
            return lRise || lFall;
        }
        const double r0 = src[static_cast<size_t>((f - 1) * channels + 1)];
        const double r1 = src[static_cast<size_t>(f * channels + 1)];
        const bool rRise = (r0 <= 0.0 && r1 >= 0.0);
        const bool rFall = (r0 >= 0.0 && r1 <= 0.0);
        // Prefer both channels crossing with matching slope; fallback to mono sum crossing
        if (requiredSlope > 0) return (lRise && rRise) || ((l0 + r0) <= 0.0 && (l1 + r1) >= 0.0 && lRise);
        if (requiredSlope < 0) return (lFall && rFall) || ((l0 + r0) >= 0.0 && (l1 + r1) <= 0.0 && lFall);
        return (lRise && rRise) || (lFall && rFall) || (((l0 + r0) <= 0.0 && (l1 + r1) >= 0.0) || ((l0 + r0) >= 0.0 && (l1 + r1) <= 0.0));
    };

    for (int64_t d = 0; d <= maxRadius; ++d) {
        const int64_t right = targetFrame + d;
        if (isZeroCross(right)) return right;
        const int64_t left = targetFrame - d;
        if (isZeroCross(left)) return left;
    }
    return targetFrame;
}

AudioStats analyzeRange(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd
) {
    AudioStats stats;
    if (!src || totalFrames <= 0 || channels <= 0) return stats;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames - 1);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 1, totalFrames);

    double peakL = 0.0, peakR = 0.0;
    double sumSqL = 0.0, sumSqR = 0.0;
    int64_t clips = 0;
    const int64_t count = selEnd - selStart;

    for (int64_t f = selStart; f < selEnd; ++f) {
        const int16_t sL = src[static_cast<size_t>(f * channels)];
        const int16_t sR = (channels > 1) ? src[static_cast<size_t>(f * channels + 1)] : sL;
        if (sL >= 32767 || sL <= -32768 || (channels > 1 && (sR >= 32767 || sR <= -32768))) {
            clips++;
        }
        const double aL = std::abs(sL) / 32768.0;
        const double aR = std::abs(sR) / 32768.0;
        peakL = std::max(peakL, aL);
        peakR = std::max(peakR, aR);
        sumSqL += aL * aL;
        sumSqR += aR * aR;
    }

    stats.peakLeftDb = ampToDb(peakL);
    stats.peakRightDb = ampToDb(peakR);
    stats.rmsLeftDb = ampToDb(std::sqrt(sumSqL / count));
    stats.rmsRightDb = ampToDb(std::sqrt(sumSqR / count));
    stats.clippedSamples = clips;

    // Seam discontinuity between selEnd - 1 and selStart
    const double endVal = src[static_cast<size_t>((selEnd - 1) * channels)] / 32768.0;
    const double startVal = src[static_cast<size_t>(selStart * channels)] / 32768.0;
    stats.seamDeltaPercent = static_cast<float>(std::abs(endVal - startVal) * 50.0); // % of full scale
    return stats;
}

void compressor(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float thresholdDb,
    float ratio,
    int32_t sampleRate
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const double att = std::exp(-1.0 / (0.010 * sampleRate));
    const double rel = std::exp(-1.0 / (0.120 * sampleRate));
    double env = 0.0;

    for (int64_t f = selStart; f < selEnd; ++f) {
        double peak = 0.0;
        for (int32_t ch = 0; ch < channels; ++ch) {
            peak = std::max(peak, std::abs(interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0));
        }
        env = (peak > env) ? (att * env + (1.0 - att) * peak) : (rel * env + (1.0 - rel) * peak);
        const double envDb = (env > 1e-6) ? (20.0 * std::log10(env)) : -96.0;
        double gain = 1.0;
        if (envDb > thresholdDb && ratio > 1.0f) {
            const double over = envDb - thresholdDb;
            const double targetDb = thresholdDb + over / ratio;
            gain = std::pow(10.0, (targetDb - envDb) / 20.0);
        }
        for (int32_t ch = 0; ch < channels; ++ch) {
            double v = (interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0) * gain;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(v);
        }
    }
}

void limiter(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float ceilingDb
) {
    if (channels <= 0 || selEnd <= selStart) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const double ceilAmp = std::pow(10.0, ceilingDb / 20.0);
    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            double v = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            if (std::abs(v) > ceilAmp) {
                v = (v > 0.0 ? 1.0 : -1.0) * ceilAmp;
            }
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(v);
        }
    }
}

void biquadFilter(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    int32_t type,
    double cutoffHz,
    double q
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);

    const double w0 = 2.0 * kPi * std::clamp(cutoffHz, 20.0, sampleRate * 0.45) / sampleRate;
    const double cosW = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * std::max(0.1, q));

    double b0, b1, b2, a0, a1, a2;
    if (type == 0) { // LowPass
        b0 = (1.0 - cosW) / 2.0; b1 = 1.0 - cosW; b2 = (1.0 - cosW) / 2.0;
        a0 = 1.0 + alpha; a1 = -2.0 * cosW; a2 = 1.0 - alpha;
    } else { // HighPass
        b0 = (1.0 + cosW) / 2.0; b1 = -(1.0 + cosW); b2 = (1.0 + cosW) / 2.0;
        a0 = 1.0 + alpha; a1 = -2.0 * cosW; a2 = 1.0 - alpha;
    }
    b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;

    for (int32_t ch = 0; ch < channels; ++ch) {
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        for (int64_t f = selStart; f < selEnd; ++f) {
            const double x0 = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            const double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x0; y2 = y1; y1 = y0;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(y0);
        }
    }
}

void bassTreble(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float bassDb,
    float trebleDb
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);

    const double bassGain = std::pow(10.0, bassDb / 20.0) - 1.0;
    const double trebGain = std::pow(10.0, trebleDb / 20.0) - 1.0;
    const double lpCoeff = std::exp(-2.0 * kPi * 250.0 / sampleRate);
    const double hpCoeff = std::exp(-2.0 * kPi * 4000.0 / sampleRate);

    for (int32_t ch = 0; ch < channels; ++ch) {
        double lp = 0.0, hpLp = 0.0;
        for (int64_t f = selStart; f < selEnd; ++f) {
            const double x = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            lp = lpCoeff * lp + (1.0 - lpCoeff) * x;
            hpLp = hpCoeff * hpLp + (1.0 - hpCoeff) * x;
            const double hp = x - hpLp;
            const double y = x + bassGain * lp + trebGain * hp;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(y);
        }
    }
}

void echo(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float delayMs,
    float decay,
    bool wrapTail
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const int64_t delayFrames = std::max<int64_t>(1, static_cast<int64_t>(delayMs * 0.001 * sampleRate));
    const double d = std::clamp<double>(decay, 0.0, 0.9);

    std::vector<double> buf(static_cast<size_t>(delayFrames * channels), 0.0);
    int64_t writeIdx = 0;
    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double x = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            const size_t idx = static_cast<size_t>(writeIdx * channels + ch);
            const double delayed = buf[idx];
            const double y = x + delayed * d;
            buf[idx] = y;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(y);
        }
        writeIdx = (writeIdx + 1) % delayFrames;
    }

    if (wrapTail) {
        const int64_t selLen = selEnd - selStart;
        const int64_t tailFrames = std::min<int64_t>(selLen, static_cast<int64_t>(sampleRate * 1.5));
        for (int64_t i = 0; i < tailFrames; ++i) {
            const double fade = 1.0 - static_cast<double>(i) / tailFrames;
            for (int32_t ch = 0; ch < channels; ++ch) {
                const size_t idx = static_cast<size_t>(writeIdx * channels + ch);
                const double delayed = buf[idx] * d * fade;
                buf[idx] = delayed;
                const size_t dstIdx = static_cast<size_t>((selStart + i) * channels + ch);
                const double cur = interleaved[dstIdx] / 32768.0;
                interleaved[dstIdx] = floatToInt16(cur + delayed);
            }
            writeIdx = (writeIdx + 1) % delayFrames;
        }
    }
}

void reverb(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float roomSize,
    float wet,
    bool wrapTail
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const int64_t selLen = selEnd - selStart;

    const int delaysMs[4] = {29, 37, 41, 53};
    const double fb = 0.45 + 0.40 * std::clamp<double>(roomSize, 0.0, 1.0);
    const double w = std::clamp<double>(wet, 0.0, 1.0);

    std::vector<int64_t> lens(4);
    for (int i = 0; i < 4; ++i) {
        lens[i] = std::max<int64_t>(8, static_cast<int64_t>(delaysMs[i] * 0.001 * sampleRate));
    }

    const int64_t tailFrames = wrapTail ? std::min<int64_t>(selLen, static_cast<int64_t>(sampleRate * 1.5)) : 0;

    for (int32_t ch = 0; ch < channels; ++ch) {
        std::vector<std::vector<double>> combs(4);
        std::vector<int64_t> pos(4, 0);
        for (int i = 0; i < 4; ++i) combs[i].assign(static_cast<size_t>(lens[i] + ch * 17), 0.0);

        for (int64_t f = selStart; f < selEnd; ++f) {
            const double x = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            double wetSum = 0.0;
            for (int i = 0; i < 4; ++i) {
                const size_t sz = combs[i].size();
                const size_t p = static_cast<size_t>(pos[i]);
                const double out = combs[i][p];
                combs[i][p] = x + out * fb;
                pos[i] = (pos[i] + 1) % static_cast<int64_t>(sz);
                wetSum += out * 0.25;
            }
            const double y = x * (1.0 - w * 0.5) + wetSum * w;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(y);
        }

        if (wrapTail && tailFrames > 0) {
            for (int64_t i = 0; i < tailFrames; ++i) {
                const double fade = 1.0 - static_cast<double>(i) / tailFrames;
                double wetSum = 0.0;
                for (int c = 0; c < 4; ++c) {
                    const size_t sz = combs[c].size();
                    const size_t p = static_cast<size_t>(pos[c]);
                    const double out = combs[c][p];
                    combs[c][p] = out * fb;
                    pos[c] = (pos[c] + 1) % static_cast<int64_t>(sz);
                    wetSum += out * 0.25;
                }
                const size_t dstIdx = static_cast<size_t>((selStart + i) * channels + ch);
                const double cur = interleaved[dstIdx] / 32768.0;
                interleaved[dstIdx] = floatToInt16(cur + wetSum * w * fade);
            }
        }
    }
}

void noiseGate(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float thresholdDb,
    float attackMs,
    float releaseMs
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const double threshAmp = std::pow(10.0, thresholdDb / 20.0);
    const double attCoeff = std::exp(-1.0 / (std::max(0.5f, attackMs) * 0.001 * sampleRate));
    const double relCoeff = std::exp(-1.0 / (std::max(5.0f, releaseMs) * 0.001 * sampleRate));
    double gain = 1.0;

    for (int64_t f = selStart; f < selEnd; ++f) {
        double peak = 0.0;
        for (int32_t ch = 0; ch < channels; ++ch) {
            peak = std::max(peak, std::abs(interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0));
        }
        const double target = (peak >= threshAmp) ? 1.0 : 0.0;
        const double coeff = (target > gain) ? attCoeff : relCoeff;
        gain = gain * coeff + target * (1.0 - coeff);
        for (int32_t ch = 0; ch < channels; ++ch) {
            double v = (interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0) * gain;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(v);
        }
    }
}

std::vector<int16_t> paulstretch(
    const int16_t* src,
    int64_t srcFrames,
    int32_t sampleRate,
    int32_t channels,
    double stretchFactor,
    double timeResolutionSec
) {
    if (!src || srcFrames < 64 || channels <= 0 || sampleRate <= 0) return {};
    stretchFactor = std::max(0.1, stretchFactor);
    const int32_t targetWin = static_cast<int32_t>(std::llround(std::max(0.01, timeResolutionSec) * sampleRate));
    const int32_t winSize = std::clamp<int32_t>(targetWin & ~1, 128, static_cast<int32_t>(std::min<int64_t>(srcFrames, 32768)));
    const int32_t halfWin = std::max(64, winSize / 2);
    const int64_t outFrames = static_cast<int64_t>(std::llround(srcFrames * stretchFactor));
    std::vector<double> accum(static_cast<size_t>(outFrames * channels), 0.0);
    std::vector<double> norm(static_cast<size_t>(outFrames), 0.0);

    std::vector<double> hann(winSize);
    for (int32_t i = 0; i < winSize; ++i) {
        hann[i] = 0.5 * (1.0 - std::cos(2.0 * kPi * i / (winSize - 1)));
    }

    uint32_t rng = 0x9E3779B9u;
    for (int64_t outPos = 0; outPos + winSize <= outFrames; outPos += halfWin) {
        const double srcPosD = static_cast<double>(outPos) / stretchFactor;
        const int64_t srcPos = std::clamp<int64_t>(static_cast<int64_t>(srcPosD), 0, srcFrames - winSize);
        rng = rng * 1664525u + 1013904223u;
        const int32_t jitter = static_cast<int32_t>((rng >> 16) % 128) - 64;
        const int64_t readStart = std::clamp<int64_t>(srcPos + jitter, 0, srcFrames - winSize);

        for (int32_t i = 0; i < winSize; ++i) {
            const double w = hann[i];
            norm[static_cast<size_t>(outPos + i)] += w * w;
            for (int32_t ch = 0; ch < channels; ++ch) {
                const double s = src[static_cast<size_t>((readStart + i) * channels + ch)] / 32768.0;
                accum[static_cast<size_t>((outPos + i) * channels + ch)] += s * w * w;
            }
        }
    }

    std::vector<int16_t> out(static_cast<size_t>(outFrames * channels), 0);
    for (int64_t f = 0; f < outFrames; ++f) {
        const double inv = (norm[static_cast<size_t>(f)] > 1e-6) ? (1.0 / norm[static_cast<size_t>(f)]) : 1.0;
        for (int32_t ch = 0; ch < channels; ++ch) {
            out[static_cast<size_t>(f * channels + ch)] = floatToInt16(accum[static_cast<size_t>(f * channels + ch)] * inv);
        }
    }
    return out;
}

int64_t findBestLoopSeam(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int64_t searchRadius
) {
    if (!src || totalFrames < 64 || channels <= 0) return selEnd;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames - 32);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 32, totalFrames);
    constexpr int64_t kCorrWin = 64;
    const int64_t minCand = std::max<int64_t>(selStart + kCorrWin + 1, selEnd - searchRadius);
    const int64_t maxCand = std::min<int64_t>(totalFrames, selEnd + searchRadius);

    double bestScore = 1e18;
    int64_t bestEnd = selEnd;
    for (int64_t cand = minCand; cand <= maxCand; ++cand) {
        double err = 0.0;
        for (int64_t k = 0; k < kCorrWin && (selStart + k) < totalFrames; ++k) {
            const double a = src[static_cast<size_t>((cand - kCorrWin + k) * channels)] / 32768.0;
            const double b = src[static_cast<size_t>((selStart + k) * channels)] / 32768.0;
            const double d = a - b;
            err += d * d;
        }
        // Also penalize sample discontinuity right at the seam
        const double seamD = (src[static_cast<size_t>((cand - 1) * channels)] - src[static_cast<size_t>(selStart * channels)]) / 32768.0;
        const double score = err + 4.0 * seamD * seamD;
        if (score < bestScore) {
            bestScore = score;
            bestEnd = cand;
        }
    }
    return bestEnd;
}

void equalizer5Band(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    const float gainsDb[5]
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);

    const double freqs[5] = {60.0, 250.0, 1000.0, 4000.0, 12000.0};
    for (int b = 0; b < 5; ++b) {
        if (std::abs(gainsDb[b]) < 0.1f) continue;
        const double A = std::pow(10.0, gainsDb[b] / 40.0);
        const double w0 = 2.0 * kPi * std::min(freqs[b], sampleRate * 0.45) / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * 1.0);
        const double cosW = std::cos(w0);

        const double b0 = 1.0 + alpha * A;
        const double b1 = -2.0 * cosW;
        const double b2 = 1.0 - alpha * A;
        const double a0 = 1.0 + alpha / A;
        const double a1 = -2.0 * cosW;
        const double a2 = 1.0 - alpha / A;

        const double nb0 = b0 / a0, nb1 = b1 / a0, nb2 = b2 / a0, na1 = a1 / a0, na2 = a2 / a0;
        for (int32_t ch = 0; ch < channels; ++ch) {
            double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
            for (int64_t f = selStart; f < selEnd; ++f) {
                const double x0 = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
                const double y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2;
                x2 = x1; x1 = x0; y2 = y1; y1 = y0;
                interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(y0);
            }
        }
    }
}

void phaser(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float rateHz,
    float depth,
    float feedback
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const double fb = std::clamp<double>(feedback, -0.85, 0.85);
    const double d = std::clamp<double>(depth, 0.0, 1.0);

    for (int32_t ch = 0; ch < channels; ++ch) {
        double ap[4] = {0.0, 0.0, 0.0, 0.0};
        double lastOut = 0.0;
        for (int64_t f = selStart; f < selEnd; ++f) {
            const double t = static_cast<double>(f - selStart) / sampleRate;
            const double lfo = 0.5 + 0.5 * std::sin(2.0 * kPi * rateHz * t + ch * 0.7);
            const double freq = 200.0 + lfo * 1800.0 * d;
            const double c = (std::tan(kPi * freq / sampleRate) - 1.0) / (std::tan(kPi * freq / sampleRate) + 1.0);
            const double x = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            double v = x + lastOut * fb;
            for (int s = 0; s < 4; ++s) {
                const double y = c * v + ap[s];
                ap[s] = v - c * y;
                v = y;
            }
            lastOut = v;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(0.5 * x + 0.5 * v);
        }
    }
}

void wahwah(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    float lfoFreqHz,
    float depth,
    float resonance
) {
    if (channels <= 0 || selEnd <= selStart || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const double q = std::clamp<double>(resonance, 0.5, 10.0);

    for (int32_t ch = 0; ch < channels; ++ch) {
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        for (int64_t f = selStart; f < selEnd; ++f) {
            const double t = static_cast<double>(f - selStart) / sampleRate;
            const double lfo = 0.5 + 0.5 * std::sin(2.0 * kPi * lfoFreqHz * t);
            const double freq = 300.0 + lfo * 2200.0 * std::clamp<double>(depth, 0.0, 1.0);
            const double w0 = 2.0 * kPi * std::min(freq, sampleRate * 0.45) / sampleRate;
            const double alpha = std::sin(w0) / (2.0 * q);
            const double cosW = std::cos(w0);
            const double a0 = 1.0 + alpha;
            const double b0 = alpha / a0, b2 = -alpha / a0, a1 = (-2.0 * cosW) / a0, a2 = (1.0 - alpha) / a0;
            const double x0 = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            const double y0 = b0 * x0 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x0; y2 = y1; y1 = y0;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(x0 * 0.3 + y0 * 0.9);
        }
    }
}

void distortion(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t mode,
    float drive,
    float mix
) {
    if (channels <= 0 || selEnd <= selStart) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const double drv = std::clamp<double>(drive, 1.0, 20.0);
    const double wet = std::clamp<double>(mix, 0.0, 1.0);

    // Bitcrusher parameters: bit-depth reduction + sample-and-hold downsampling
    const double bits = std::clamp(13.0 - drv * 0.65, 2.0, 12.0);
    const double levels = std::pow(2.0, bits - 1.0);
    const int64_t holdFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(drv)));
    std::vector<double> held(static_cast<size_t>(channels), 0.0);

    for (int64_t f = selStart; f < selEnd; ++f) {
        const bool updateHold = ((f - selStart) % holdFrames) == 0;
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double x = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            double d = x;
            if (mode == 0) {
                d = std::tanh(x * drv);
            } else if (mode == 1) {
                d = std::clamp(x * drv, -0.8, 0.8) / 0.8;
            } else {
                if (updateHold) {
                    held[static_cast<size_t>(ch)] = std::round(x * levels) / levels;
                }
                d = held[static_cast<size_t>(ch)];
            }
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(x * (1.0 - wet) + d * wet);
        }
    }
}

void loudnessNormalize(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float targetRmsDb
) {
    if (channels <= 0 || selEnd <= selStart) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const int64_t count = (selEnd - selStart) * channels;
    if (count <= 0) return;

    double sumSq = 0.0;
    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double v = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            sumSq += v * v;
        }
    }
    const double rms = std::sqrt(sumSq / count);
    if (rms < 1e-6) return;
    const double targetRms = std::pow(10.0, targetRmsDb / 20.0);
    const double gain = std::min(16.0, targetRms / rms);
    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double v = (interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0) * gain;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(v);
        }
    }
}

void clickRemoval(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float threshold
) {
    if (channels <= 0 || selEnd <= selStart + 4) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 2, totalFrames - 2);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 2, totalFrames - 2);

    for (int32_t ch = 0; ch < channels; ++ch) {
        for (int64_t f = selStart + 1; f < selEnd - 1; ++f) {
            const double prev = interleaved[static_cast<size_t>((f - 1) * channels + ch)] / 32768.0;
            const double cur = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            const double next = interleaved[static_cast<size_t>((f + 1) * channels + ch)] / 32768.0;
            const double avg = 0.5 * (prev + next);
            if (std::abs(cur - avg) > threshold && std::abs(prev - next) < threshold * 0.5) {
                interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(avg);
            }
        }
    }
}

std::vector<int16_t> truncateSilence(
    const int16_t* src,
    int64_t srcFrames,
    int32_t sampleRate,
    int32_t channels,
    float thresholdDb,
    float minSilenceSec
) {
    if (!src || srcFrames <= 0 || channels <= 0 || sampleRate <= 0) return {};
    const double threshAmp = std::pow(10.0, thresholdDb / 20.0);
    const int64_t minSilFrames = std::max<int64_t>(1, static_cast<int64_t>(minSilenceSec * sampleRate));
    std::vector<int16_t> out;
    out.reserve(static_cast<size_t>(srcFrames * channels));

    int64_t silRun = 0;
    for (int64_t f = 0; f < srcFrames; ++f) {
        double p = 0.0;
        for (int32_t c = 0; c < channels; ++c) p = std::max(p, std::abs(src[static_cast<size_t>(f * channels + c)] / 32768.0));
        if (p < threshAmp) {
            silRun++;
            if (silRun <= minSilFrames) {
                for (int32_t c = 0; c < channels; ++c) out.push_back(src[static_cast<size_t>(f * channels + c)]);
            }
        } else {
            silRun = 0;
            for (int32_t c = 0; c < channels; ++c) out.push_back(src[static_cast<size_t>(f * channels + c)]);
        }
    }
    return out;
}

std::vector<int16_t> generateDtmf(
    const char* digits,
    double toneSec,
    float amplitude,
    int32_t sampleRate,
    int32_t channels
) {
    if (!digits || sampleRate <= 0 || channels <= 0) return {};
    const int64_t toneFrames = std::max<int64_t>(1, static_cast<int64_t>(toneSec * sampleRate));
    const int64_t gapFrames = std::max<int64_t>(1, static_cast<int64_t>(0.05 * sampleRate));
    std::vector<int16_t> out;

    auto getFreqs = [](char ch, double& f1, double& f2) -> bool {
        const double row[4] = {697, 770, 852, 941};
        const double col[4] = {1209, 1336, 1477, 1633};
        int r = -1, c = -1;
        switch (ch) {
            case '1': r = 0; c = 0; break; case '2': r = 0; c = 1; break; case '3': r = 0; c = 2; break; case 'A': r = 0; c = 3; break;
            case '4': r = 1; c = 0; break; case '5': r = 1; c = 1; break; case '6': r = 1; c = 2; break; case 'B': r = 1; c = 3; break;
            case '7': r = 2; c = 0; break; case '8': r = 2; c = 1; break; case '9': r = 2; c = 2; break; case 'C': r = 2; c = 3; break;
            case '*': r = 3; c = 0; break; case '0': r = 3; c = 1; break; case '#': r = 3; c = 2; break; case 'D': r = 3; c = 3; break;
            default: return false;
        }
        f1 = row[r]; f2 = col[c]; return true;
    };

    for (const char* p = digits; *p != '\0'; ++p) {
        double f1 = 0, f2 = 0;
        if (!getFreqs(*p, f1, f2)) continue;
        for (int64_t i = 0; i < toneFrames; ++i) {
            const double t = static_cast<double>(i) / sampleRate;
            const double val = 0.5 * (std::sin(2.0 * kPi * f1 * t) + std::sin(2.0 * kPi * f2 * t)) * amplitude;
            const int16_t s = floatToInt16(val);
            for (int32_t ch = 0; ch < channels; ++ch) out.push_back(s);
        }
        for (int64_t i = 0; i < gapFrames * channels; ++i) out.push_back(0);
    }
    return out;
}

std::vector<float> computeSpectrogram(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t viewStart,
    int64_t viewEnd,
    int32_t timeBins,
    int32_t freqBins
) {
    std::vector<float> grid(static_cast<size_t>(timeBins * freqBins), 0.0f);
    if (!src || totalFrames <= 0 || channels <= 0 || timeBins <= 0 || freqBins <= 0) return grid;
    viewStart = std::clamp<int64_t>(viewStart, 0, totalFrames - 1);
    viewEnd = std::clamp<int64_t>(viewEnd, viewStart + 1, totalFrames);
    const int64_t span = viewEnd - viewStart;
    constexpr int32_t N = 128;

    for (int32_t tb = 0; tb < timeBins; ++tb) {
        const int64_t center = viewStart + (static_cast<int64_t>(tb) * span) / timeBins;
        const int64_t start = std::clamp<int64_t>(center - N / 2, 0, std::max<int64_t>(0, totalFrames - N));
        double win[N];
        for (int32_t n = 0; n < N; ++n) {
            const int64_t f = std::min(totalFrames - 1, start + n);
            const double h = 0.5 * (1.0 - std::cos(2.0 * kPi * n / (N - 1)));
            win[n] = (src[static_cast<size_t>(f * channels)] / 32768.0) * h;
        }
        for (int32_t fb = 0; fb < freqBins; ++fb) {
            const int32_t k = std::max(1, ((fb + 1) * (N / 2)) / freqBins);
            double re = 0.0, im = 0.0;
            const double step = 2.0 * kPi * k / N;
            for (int32_t n = 0; n < N; ++n) {
                const double ang = step * n;
                re += win[n] * std::cos(ang);
                im -= win[n] * std::sin(ang);
            }
            const double mag = std::sqrt(re * re + im * im) / (N * 0.25);
            const double db = (mag > 1e-5) ? (20.0 * std::log10(mag)) : -96.0;
            grid[static_cast<size_t>(tb * freqBins + fb)] = static_cast<float>(std::clamp((db + 84.0) / 84.0, 0.0, 1.0));
        }
    }
    return grid;
}

std::vector<int16_t> generateChirp(
    double freqStartHz,
    double freqEndHz,
    float amplitude,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
) {
    const int64_t totalFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(durationSec * sampleRate)));
    std::vector<int16_t> out(static_cast<size_t>(totalFrames * channels), 0);
    const double amp = std::clamp<double>(amplitude, 0.0, 1.0);
    double phase = 0.0;
    for (int64_t f = 0; f < totalFrames; ++f) {
        const double frac = static_cast<double>(f) / totalFrames;
        const double freq = freqStartHz + (freqEndHz - freqStartHz) * frac;
        phase += 2.0 * kPi * freq / sampleRate;
        const int16_t s16 = floatToInt16(std::sin(phase) * amp);
        for (int32_t ch = 0; ch < channels; ++ch) {
            out[static_cast<size_t>(f * channels + ch)] = s16;
        }
    }
    return out;
}

std::vector<int16_t> generateRhythmTrack(
    double bpm,
    int32_t beatsPerBar,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
) {
    const int64_t totalFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(durationSec * sampleRate)));
    std::vector<int16_t> out(static_cast<size_t>(totalFrames * channels), 0);
    const double framesPerBeat = sampleRate * 60.0 / std::clamp(bpm, 30.0, 300.0);
    const int64_t clickLen = std::max<int64_t>(1, static_cast<int64_t>(0.025 * sampleRate));

    int64_t beatIdx = 0;
    while (true) {
        const int64_t startF = static_cast<int64_t>(std::llround(beatIdx * framesPerBeat));
        if (startF >= totalFrames) break;
        const bool accent = (beatsPerBar > 0) && (beatIdx % beatsPerBar == 0);
        const double freq = accent ? 1200.0 : 800.0;
        const double amp = accent ? 0.8 : 0.5;
        for (int64_t i = 0; i < clickLen && (startF + i) < totalFrames; ++i) {
            const double env = 1.0 - static_cast<double>(i) / clickLen;
            const int16_t s16 = floatToInt16(std::sin(2.0 * kPi * freq * i / sampleRate) * amp * env);
            for (int32_t ch = 0; ch < channels; ++ch) {
                out[static_cast<size_t>((startF + i) * channels + ch)] = s16;
            }
        }
        beatIdx++;
    }
    return out;
}

std::vector<float> computeSpectrumDb(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t bins
) {
    std::vector<float> spec(static_cast<size_t>(bins), -96.0f);
    if (!src || totalFrames <= 0 || channels <= 0 || bins <= 0) return spec;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames - 1);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 1, totalFrames);
    const int32_t N = static_cast<int32_t>(std::min<int64_t>(1024, selEnd - selStart));
    if (N < 16) return spec;

    std::vector<double> win(N);
    for (int32_t n = 0; n < N; ++n) {
        const double h = 0.5 * (1.0 - std::cos(2.0 * kPi * n / (N - 1)));
        const int64_t f = selStart + n;
        double mono = 0.0;
        for (int32_t ch = 0; ch < channels; ++ch) {
            mono += src[static_cast<size_t>(f * channels + ch)] / 32768.0;
        }
        win[n] = (mono / channels) * h;
    }

    for (int32_t b = 0; b < bins; ++b) {
        const int32_t k = std::max(1, ((b + 1) * (N / 2)) / bins);
        double re = 0.0, im = 0.0;
        const double step = 2.0 * kPi * k / N;
        for (int32_t n = 0; n < N; ++n) {
            const double ang = step * n;
            re += win[n] * std::cos(ang);
            im -= win[n] * std::sin(ang);
        }
        const double mag = std::sqrt(re * re + im * im) / (N * 0.5);
        spec[static_cast<size_t>(b)] = ampToDb(mag);
    }
    return spec;
}

double estimateBpm(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate
) {
    if (!src || totalFrames <= 0 || channels <= 0 || sampleRate <= 0) return 120.0;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames - 1);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 1, totalFrames);
    const int64_t maxAnalyze = std::min<int64_t>(selEnd - selStart, static_cast<int64_t>(sampleRate) * 20);
    const int32_t hop = std::max(1, sampleRate / 100); // 10ms envelope
    const int32_t numEnv = static_cast<int32_t>(maxAnalyze / hop);
    if (numEnv < 40) return 120.0;

    std::vector<double> env(static_cast<size_t>(numEnv), 0.0);
    for (int32_t i = 0; i < numEnv; ++i) {
        double sum = 0.0;
        for (int32_t j = 0; j < hop; ++j) {
            const int64_t f = selStart + static_cast<int64_t>(i) * hop + j;
            sum += std::abs(src[static_cast<size_t>(f * channels)] / 32768.0);
        }
        env[static_cast<size_t>(i)] = sum / hop;
    }

    double bestScore = -1.0;
    double bestBpm = 120.0;
    for (int bpmInt = 60; bpmInt <= 180; ++bpmInt) {
        const double lagF = (60.0 / bpmInt) * 100.0;
        const int lag = static_cast<int>(std::llround(lagF));
        if (lag >= numEnv) continue;
        double corr = 0.0;
        for (int i = 0; i < numEnv - lag; ++i) {
            corr += env[i] * env[i + lag];
        }
        corr /= (numEnv - lag);
        if (corr > bestScore) {
            bestScore = corr;
            bestBpm = static_cast<double>(bpmInt);
        }
    }
    return bestBpm;
}

void smoothBoundaries(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int64_t smoothFrames
) {
    if (channels <= 0 || selEnd <= selStart || smoothFrames <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const int64_t len = selEnd - selStart;
    const int64_t n = std::min<int64_t>(smoothFrames, len / 2);
    if (n <= 0) return;

    for (int64_t i = 0; i < n; ++i) {
        const double t = static_cast<double>(i + 1) / (n + 1);
        const double w = 0.5 * (1.0 - std::cos(kPi * t));
        for (int32_t ch = 0; ch < channels; ++ch) {
            const size_t idxIn = static_cast<size_t>((selStart + i) * channels + ch);
            const size_t idxOut = static_cast<size_t>((selEnd - 1 - i) * channels + ch);
            interleaved[idxIn] = floatToInt16((interleaved[idxIn] / 32768.0) * w);
            interleaved[idxOut] = floatToInt16((interleaved[idxOut] / 32768.0) * w);
        }
    }
}

void crossfadeLoopSeam(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int64_t crossfadeFrames
) {
    if (channels <= 0 || selEnd <= selStart || crossfadeFrames <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const int64_t len = selEnd - selStart;
    const int64_t n = std::min<int64_t>(crossfadeFrames, len / 3);
    if (n <= 1) return;

    std::vector<double> headCopy(static_cast<size_t>(n * channels));
    std::vector<double> tailCopy(static_cast<size_t>(n * channels));
    for (int64_t i = 0; i < n; ++i) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            headCopy[static_cast<size_t>(i * channels + ch)] = interleaved[static_cast<size_t>((selStart + i) * channels + ch)] / 32768.0;
            tailCopy[static_cast<size_t>(i * channels + ch)] = interleaved[static_cast<size_t>((selEnd - n + i) * channels + ch)] / 32768.0;
        }
    }

    for (int64_t i = 0; i < n; ++i) {
        const double frac = static_cast<double>(i) / (n - 1);
        const double gainHead = std::sin(frac * kPi * 0.5);
        const double gainTail = std::cos(frac * kPi * 0.5);
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double blended = headCopy[static_cast<size_t>(i * channels + ch)] * gainHead +
                                   tailCopy[static_cast<size_t>(i * channels + ch)] * gainTail;
            interleaved[static_cast<size_t>((selStart + i) * channels + ch)] = floatToInt16(blended);
            interleaved[static_cast<size_t>((selEnd - n + i) * channels + ch)] = floatToInt16(blended);
        }
    }
}

static void fftRadix2(std::vector<double>& re, std::vector<double>& im, bool inverse) {
    const size_t n = re.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = 2.0 * kPi / static_cast<double>(len) * (inverse ? 1.0 : -1.0);
        const double wlenRe = std::cos(ang);
        const double wlenIm = std::sin(ang);
        for (size_t i = 0; i < n; i += len) {
            double wRe = 1.0, wIm = 0.0;
            for (size_t j = 0; j < len / 2; ++j) {
                const double uRe = re[i + j], uIm = im[i + j];
                const double vRe = re[i + j + len / 2] * wRe - im[i + j + len / 2] * wIm;
                const double vIm = re[i + j + len / 2] * wIm + im[i + j + len / 2] * wRe;
                re[i + j] = uRe + vRe; im[i + j] = uIm + vIm;
                re[i + j + len / 2] = uRe - vRe; im[i + j + len / 2] = uIm - vIm;
                const double nxtRe = wRe * wlenRe - wIm * wlenIm;
                wIm = wRe * wlenIm + wIm * wlenRe;
                wRe = nxtRe;
            }
        }
    }
    if (inverse) {
        const double invN = 1.0 / static_cast<double>(n);
        for (size_t i = 0; i < n; ++i) { re[i] *= invN; im[i] *= invN; }
    }
}

std::vector<float> captureNoiseProfile(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd
) {
    constexpr int32_t kN = 1024;
    constexpr int32_t kHop = 256;
    constexpr int32_t kBins = kN / 2 + 1;
    std::vector<float> profile(kBins, 0.0f);
    if (!src || totalFrames <= 0 || channels <= 0) return profile;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames - 1);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 1, totalFrames);

    std::vector<double> re(kN), im(kN), win(kN);
    for (int32_t i = 0; i < kN; ++i) win[i] = 0.5 * (1.0 - std::cos(2.0 * kPi * i / (kN - 1)));

    int64_t framesCount = 0;
    for (int64_t pos = selStart; pos < selEnd; pos += kHop) {
        for (int32_t i = 0; i < kN; ++i) {
            const int64_t idx = std::min(pos + i, selEnd - 1);
            double s = src[static_cast<size_t>(idx * channels)] / 32768.0;
            if (channels > 1) s = 0.5 * (s + src[static_cast<size_t>(idx * channels + 1)] / 32768.0);
            re[i] = s * win[i];
            im[i] = 0.0;
        }
        fftRadix2(re, im, false);
        for (int32_t b = 0; b < kBins; ++b) {
            const float mag = static_cast<float>(std::sqrt(re[b] * re[b] + im[b] * im[b]));
            profile[b] += mag;
        }
        framesCount++;
    }
    if (framesCount > 0) {
        for (int32_t b = 0; b < kBins; ++b) profile[b] /= static_cast<float>(framesCount);
    }
    return profile;
}

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
) {
    constexpr int32_t kN = 1024;
    constexpr int32_t kHop = 256;
    constexpr int32_t kBins = kN / 2 + 1;
    if (channels <= 0 || profile.size() != static_cast<size_t>(kBins)) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const int64_t len = selEnd - selStart;
    if (len <= 0) return;

    const double floorGain = std::pow(10.0, -std::clamp(reductionDb, 0.0f, 48.0f) / 20.0);
    const double sensScale = std::pow(10.0, std::clamp(sensitivity, 0.0f, 24.0f) / 20.0);
    const int32_t smoothR = std::clamp(smoothingBands, 0, 6);

    std::vector<double> win(kN);
    for (int32_t i = 0; i < kN; ++i) win[i] = 0.5 * (1.0 - std::cos(2.0 * kPi * i / (kN - 1)));

    std::vector<double> re(kN), im(kN);
    std::vector<double> gains(kBins, 1.0), smoothed(kBins, 1.0);

    for (int32_t ch = 0; ch < channels; ++ch) {
        std::vector<double> outAcc(static_cast<size_t>(len + kN), 0.0);
        std::vector<double> winAcc(static_cast<size_t>(len + kN), 0.0);

        for (int64_t pos = 0; pos < len; pos += kHop) {
            for (int32_t i = 0; i < kN; ++i) {
                const int64_t srcIdx = selStart + pos + i;
                const double s = (srcIdx < selEnd) ? (interleaved[static_cast<size_t>(srcIdx * channels + ch)] / 32768.0) : 0.0;
                re[i] = s * win[i];
                im[i] = 0.0;
            }
            fftRadix2(re, im, false);

            for (int32_t b = 0; b < kBins; ++b) {
                const double mag = std::sqrt(re[b] * re[b] + im[b] * im[b]);
                const double thresh = static_cast<double>(profile[b]) * sensScale;
                double g = 1.0;
                if (mag < thresh && thresh > 1e-12) {
                    const double ratio = mag / thresh;
                    g = floorGain + (1.0 - floorGain) * ratio * ratio;
                }
                gains[b] = g;
            }

            if (smoothR > 0) {
                for (int32_t b = 0; b < kBins; ++b) {
                    double sum = 0.0; int32_t cnt = 0;
                    for (int32_t k = -smoothR; k <= smoothR; ++k) {
                        const int32_t idx = b + k;
                        if (idx >= 0 && idx < kBins) { sum += gains[idx]; cnt++; }
                    }
                    smoothed[b] = sum / cnt;
                }
            } else {
                smoothed = gains;
            }

            for (int32_t b = 0; b < kBins; ++b) {
                const double g = residueOnly ? (1.0 - smoothed[b]) : smoothed[b];
                re[b] *= g;
                im[b] *= g;
                if (b > 0 && b < kN / 2) {
                    re[kN - b] *= g;
                    im[kN - b] *= g;
                }
            }
            fftRadix2(re, im, true);

            for (int32_t i = 0; i < kN; ++i) {
                const int64_t dst = pos + i;
                if (dst < len + kN) {
                    outAcc[static_cast<size_t>(dst)] += re[i] * win[i];
                    winAcc[static_cast<size_t>(dst)] += win[i] * win[i];
                }
            }
        }

        for (int64_t i = 0; i < len; ++i) {
            const double w = winAcc[static_cast<size_t>(i)];
            const double v = (w > 1e-9) ? (outAcc[static_cast<size_t>(i)] / w) : 0.0;
            interleaved[static_cast<size_t>((selStart + i) * channels + ch)] = floatToInt16(v);
        }
    }
}

void notchFilter(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    double freqHz,
    double q
) {
    if (channels <= 0 || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const double w0 = 2.0 * kPi * std::clamp(freqHz, 10.0, sampleRate * 0.49) / sampleRate;
    const double alpha = std::sin(w0) / (2.0 * std::clamp(q, 0.1, 50.0));
    const double b0 = 1.0;
    const double b1 = -2.0 * std::cos(w0);
    const double b2 = 1.0;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * std::cos(w0);
    const double a2 = 1.0 - alpha;

    for (int32_t ch = 0; ch < channels; ++ch) {
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int64_t f = selStart; f < selEnd; ++f) {
            const double x0 = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            const double y0 = (b0 / a0) * x0 + (b1 / a0) * x1 + (b2 / a0) * x2 - (a1 / a0) * y1 - (a2 / a0) * y2;
            x2 = x1; x1 = x0; y2 = y1; y1 = y0;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(y0);
        }
    }
}

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
) {
    if (channels < 2 || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    // Band-split vocal center using 1st-order HPF/LPF
    const double dt = 1.0 / sampleRate;
    const double rcLow = 1.0 / (2.0 * kPi * std::clamp(lowCutHz, 20.0, 2000.0));
    const double rcHigh = 1.0 / (2.0 * kPi * std::clamp(highCutHz, 500.0, sampleRate * 0.45));
    const double alphaHp = rcLow / (rcLow + dt);
    const double alphaLp = dt / (rcHigh + dt);

    double hpPrevIn = 0.0, hpPrevOut = 0.0, lpPrev = 0.0;
    const double st = std::clamp(static_cast<double>(strength), 0.0, 2.0);

    for (int64_t f = selStart; f < selEnd; ++f) {
        const double l = interleaved[static_cast<size_t>(f * channels + 0)] / 32768.0;
        const double r = interleaved[static_cast<size_t>(f * channels + 1)] / 32768.0;
        const double mid = 0.5 * (l + r);
        const double side = 0.5 * (l - r);

        // Band-limited center (vocal range)
        const double hp = alphaHp * (hpPrevOut + mid - hpPrevIn);
        hpPrevIn = mid; hpPrevOut = hp;
        lpPrev = lpPrev + alphaLp * (hp - lpPrev);
        const double vocalBand = lpPrev;

        double outL = l, outR = r;
        switch (mode) {
            case 0: // Remove Vocals (band-limited center removal)
                outL = l - st * vocalBand;
                outR = r - st * vocalBand;
                break;
            case 1: // Isolate Vocals
                outL = st * vocalBand;
                outR = st * vocalBand;
                break;
            case 2: // Remove Center (full-band M/S)
                outL = side * st;
                outR = -side * st;
                break;
            case 3: // Isolate Center
            default:
                outL = mid * st;
                outR = mid * st;
                break;
        }
        interleaved[static_cast<size_t>(f * channels + 0)] = floatToInt16(outL);
        interleaved[static_cast<size_t>(f * channels + 1)] = floatToInt16(outR);
    }
}

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
) {
    if (channels <= 0 || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const double wet = std::clamp(wetPercent / 100.0, 0.0, 1.0);
    const double phase0 = std::fmod(startPhaseDeg / 360.0 + 10.0, 1.0);
    const double freq = std::clamp<double>(freqHz, 0.1, 40.0);

    for (int64_t f = selStart; f < selEnd; ++f) {
        const double t = static_cast<double>(f - selStart) / sampleRate;
        const double p = std::fmod(phase0 + t * freq, 1.0);
        double lfo = 0.0; // [0..1]
        switch (waveform) {
            case 0: lfo = 0.5 * (1.0 + std::sin(2.0 * kPi * p)); break; // Sine
            case 1: lfo = 1.0 - std::abs(2.0 * p - 1.0); break;         // Triangle
            case 2: lfo = p; break;                                     // Sawtooth
            case 3: lfo = 1.0 - p; break;                               // Inverse Saw
            case 4: default: lfo = (p < 0.5) ? 1.0 : 0.0; break;        // Square
        }
        const double gain = (1.0 - wet) + wet * lfo;
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double s = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(s * gain);
        }
    }
}

std::vector<int16_t> slidingStretch(
    const int16_t* src,
    int64_t srcFrames,
    int32_t sampleRate,
    int32_t channels,
    double initTempoPct,
    double finalTempoPct,
    double initPitchSemi,
    double finalPitchSemi
) {
    if (!src || srcFrames <= 1 || sampleRate <= 0 || channels <= 0) return {};
    const double r0 = std::clamp((100.0 + initTempoPct) / 100.0, 0.2, 4.0) * std::pow(2.0, initPitchSemi / 12.0);
    const double r1 = std::clamp((100.0 + finalTempoPct) / 100.0, 0.2, 4.0) * std::pow(2.0, finalPitchSemi / 12.0);
    const double avgR = 0.5 * (r0 + r1);
    const int64_t outFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(srcFrames / avgR)));

    std::vector<int16_t> out(static_cast<size_t>(outFrames * channels));
    double srcPos = 0.0;
    for (int64_t f = 0; f < outFrames; ++f) {
        const double u = (outFrames > 1) ? (static_cast<double>(f) / (outFrames - 1)) : 0.0;
        const double instRate = r0 + u * (r1 - r0);
        const int64_t i0 = std::clamp<int64_t>(static_cast<int64_t>(std::floor(srcPos)), 0, srcFrames - 1);
        const int64_t i1 = std::min<int64_t>(i0 + 1, srcFrames - 1);
        const double frac = srcPos - std::floor(srcPos);
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double s0 = src[static_cast<size_t>(i0 * channels + ch)] / 32768.0;
            const double s1 = src[static_cast<size_t>(i1 * channels + ch)] / 32768.0;
            out[static_cast<size_t>(f * channels + ch)] = floatToInt16(s0 + frac * (s1 - s0));
        }
        srcPos += instRate;
        if (srcPos >= srcFrames - 1) srcPos = static_cast<double>(srcFrames - 1);
    }
    return out;
}

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
) {
    if (channels <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    const int64_t len = selEnd - selStart;
    if (len <= 1) return;

    const double gStart = std::clamp(startPct / 100.0, 0.0, 2.0);
    const double gEnd = std::clamp(endPct / 100.0, 0.0, 2.0);
    const double midBoost = std::clamp(midBoostPct / 100.0, -0.5, 0.5);

    for (int64_t i = 0; i < len; ++i) {
        double t = static_cast<double>(i) / (len - 1);
        if (fadeType == 1) t = 1.0 - t; // Fade Down
        double shaped = t;
        if (curveType == 1) { // Log / Exp curve
            shaped = std::pow(t, 2.2);
        } else if (curveType == 2) { // S-Curve (cosine)
            shaped = 0.5 * (1.0 - std::cos(kPi * t));
        }
        const double boost = midBoost * std::sin(kPi * t);
        const double gain = std::max(0.0, gStart + shaped * (gEnd - gStart) + boost);
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double s = interleaved[static_cast<size_t>((selStart + i) * channels + ch)] / 32768.0;
            interleaved[static_cast<size_t>((selStart + i) * channels + ch)] = floatToInt16(s * gain);
        }
    }
}

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
) {
    if (targetCh <= 0 || !control || controlFrames <= 0 || controlCh <= 0 || sampleRate <= 0) return;
    const int64_t targetFrames = static_cast<int64_t>(target.size()) / targetCh;
    const int64_t n = std::min(targetFrames, controlFrames);
    if (n <= 0) return;

    const double threshLin = std::pow(10.0, thresholdDb / 20.0);
    const double duckGain = std::pow(10.0, duckDb / 20.0);
    const double alphaAtt = 1.0 - std::exp(-1.0 / std::max(1.0, outerFadeSec * sampleRate * 0.25));
    const double alphaRel = 1.0 - std::exp(-1.0 / std::max(1.0, innerFadeSec * sampleRate * 0.25));

    double env = 0.0;
    double curGain = 1.0;
    for (int64_t f = 0; f < n; ++f) {
        double c = std::abs(control[static_cast<size_t>(f * controlCh)] / 32768.0);
        if (controlCh > 1) c = std::max(c, std::abs(control[static_cast<size_t>(f * controlCh + 1)] / 32768.0));
        env = 0.995 * env + 0.005 * c;
        const double desired = (env > threshLin) ? duckGain : 1.0;
        const double a = (desired < curGain) ? alphaAtt : alphaRel;
        curGain += a * (desired - curGain);
        for (int32_t ch = 0; ch < targetCh; ++ch) {
            const double s = target[static_cast<size_t>(f * targetCh + ch)] / 32768.0;
            target[static_cast<size_t>(f * targetCh + ch)] = floatToInt16(s * curGain);
        }
    }
}

std::vector<int16_t> generatePluck(
    double freqHz,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
) {
    if (sampleRate <= 0 || channels <= 0) return {};
    const int64_t totalFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(durationSec * sampleRate)));
    const int32_t delayLen = std::clamp<int32_t>(static_cast<int32_t>(std::llround(sampleRate / std::clamp(freqHz, 30.0, 5000.0))), 2, 4096);
    std::vector<double> ring(static_cast<size_t>(delayLen));
    uint32_t rng = 0xCAFEBABEu;
    for (int32_t i = 0; i < delayLen; ++i) {
        rng = rng * 1664525u + 1013904223u;
        ring[i] = (static_cast<double>(rng) / 2147483648.0) - 1.0;
    }

    std::vector<int16_t> out(static_cast<size_t>(totalFrames * channels));
    int32_t ptr = 0;
    for (int64_t f = 0; f < totalFrames; ++f) {
        const double s = ring[ptr];
        const int32_t nextPtr = (ptr + 1) % delayLen;
        ring[ptr] = 0.996 * 0.5 * (ring[ptr] + ring[nextPtr]);
        ptr = nextPtr;
        const int16_t s16 = floatToInt16(s * 0.85);
        for (int32_t ch = 0; ch < channels; ++ch) out[static_cast<size_t>(f * channels + ch)] = s16;
    }
    return out;
}

std::vector<int64_t> findSilences(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int32_t sampleRate,
    float thresholdDb,
    float minSilenceSec
) {
    std::vector<int64_t> out;
    if (!src || totalFrames <= 0 || channels <= 0 || sampleRate <= 0) return out;
    const double thresh = std::pow(10.0, thresholdDb / 20.0);
    const int64_t minFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(minSilenceSec * sampleRate)));
    bool inSil = false;
    int64_t silStart = 0;
    for (int64_t f = 0; f < totalFrames; ++f) {
        double p = std::abs(src[static_cast<size_t>(f * channels)] / 32768.0);
        if (channels > 1) p = std::max(p, std::abs(src[static_cast<size_t>(f * channels + 1)] / 32768.0));
        if (p < thresh) {
            if (!inSil) { inSil = true; silStart = f; }
        } else if (inSil) {
            if (f - silStart >= minFrames) { out.push_back(silStart); out.push_back(f); }
            inSil = false;
        }
    }
    if (inSil && totalFrames - silStart >= minFrames) { out.push_back(silStart); out.push_back(totalFrames); }
    return out;
}

std::vector<int64_t> findSounds(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int32_t sampleRate,
    float thresholdDb,
    float minSilenceSec,
    float minSoundSec
) {
    std::vector<int64_t> out;
    if (!src || totalFrames <= 0 || channels <= 0 || sampleRate <= 0) return out;
    auto sils = findSilences(src, totalFrames, channels, sampleRate, thresholdDb, minSilenceSec);
    const int64_t minSound = std::max<int64_t>(1, static_cast<int64_t>(std::llround(minSoundSec * sampleRate)));
    int64_t cursor = 0;
    for (size_t i = 0; i + 1 < sils.size(); i += 2) {
        if (sils[i] - cursor >= minSound) { out.push_back(cursor); out.push_back(sils[i]); }
        cursor = sils[i + 1];
    }
    if (totalFrames - cursor >= minSound) { out.push_back(cursor); out.push_back(totalFrames); }
    return out;
}

std::vector<int64_t> findBeats(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int32_t sampleRate,
    float thresholdPct
) {
    std::vector<int64_t> out;
    if (!src || totalFrames <= 0 || channels <= 0 || sampleRate <= 0) return out;
    const int64_t hop = std::max<int64_t>(1, sampleRate / 100); // 10 ms
    const int64_t minGap = std::max<int64_t>(1, sampleRate / 5); // 200 ms min between beats
    std::vector<double> energies;
    double maxE = 1e-9;
    for (int64_t pos = 0; pos < totalFrames; pos += hop) {
        double e = 0.0;
        const int64_t end = std::min(totalFrames, pos + hop);
        for (int64_t f = pos; f < end; ++f) {
            const double s = src[static_cast<size_t>(f * channels)] / 32768.0;
            e += s * s;
        }
        e = std::sqrt(e / std::max<int64_t>(1, end - pos));
        energies.push_back(e);
        maxE = std::max(maxE, e);
    }
    const double thresh = maxE * std::clamp(thresholdPct / 100.0, 0.05, 0.99);
    int64_t lastBeat = -minGap;
    for (size_t i = 1; i + 1 < energies.size(); ++i) {
        if (energies[i] >= thresh && energies[i] > energies[i - 1] && energies[i] >= energies[i + 1]) {
            const int64_t f = static_cast<int64_t>(i) * hop;
            if (f - lastBeat >= minGap) { out.push_back(f); lastBeat = f; }
        }
    }
    return out;
}

std::vector<int64_t> findClippings(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int32_t startSamples,
    int32_t stopSamples
) {
    std::vector<int64_t> out;
    if (!src || totalFrames <= 0 || channels <= 0) return out;
    const int32_t reqStart = std::max(1, startSamples);
    const int32_t reqStop = std::max(1, stopSamples);
    int32_t clipRun = 0, okRun = 0;
    bool inClip = false;
    int64_t clipStart = 0;
    for (int64_t f = 0; f < totalFrames; ++f) {
        bool isClip = false;
        for (int32_t ch = 0; ch < channels; ++ch) {
            if (std::abs(static_cast<int32_t>(src[static_cast<size_t>(f * channels + ch)])) >= 32760) {
                isClip = true; break;
            }
        }
        if (isClip) {
            okRun = 0;
            if (!inClip) {
                if (clipRun == 0) clipStart = f;
                clipRun++;
                if (clipRun >= reqStart) inClip = true;
            }
        } else if (inClip) {
            okRun++;
            if (okRun >= reqStop) {
                out.push_back(clipStart);
                out.push_back(f - reqStop + 1);
                inClip = false; clipRun = 0; okRun = 0;
            }
        } else {
            clipRun = 0;
        }
    }
    if (inClip) { out.push_back(clipStart); out.push_back(totalFrames); }
    return out;
}

namespace {
struct Biquad2 {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
    double z1 = 0.0, z2 = 0.0;

    void setButterworth(int32_t sampleRate, double freqHz, bool isHighPass) {
        const double w0 = 2.0 * kPi * std::clamp(freqHz, 5.0, sampleRate * 0.48) / sampleRate;
        const double cosW = std::cos(w0);
        const double sinW = std::sin(w0);
        constexpr double q = 0.7071067811865475; // 1 / sqrt(2)
        const double alpha = sinW / (2.0 * q);
        const double a0 = 1.0 + alpha;
        if (isHighPass) {
            b0 = (1.0 + cosW) * 0.5 / a0;
            b1 = -(1.0 + cosW) / a0;
            b2 = (1.0 + cosW) * 0.5 / a0;
        } else {
            b0 = (1.0 - cosW) * 0.5 / a0;
            b1 = (1.0 - cosW) / a0;
            b2 = (1.0 - cosW) * 0.5 / a0;
        }
        a1 = (-2.0 * cosW) / a0;
        a2 = (1.0 - alpha) / a0;
    }

    inline double process(double x) {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

struct LinkwitzRiley4 {
    Biquad2 s1, s2;
    void setup(int32_t sampleRate, double freqHz, bool isHighPass) {
        s1.setButterworth(sampleRate, freqHz, isHighPass);
        s2.setButterworth(sampleRate, freqHz, isHighPass);
    }
    inline double process(double x) { return s2.process(s1.process(x)); }
};
} // namespace

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
) {
    if (channels < 2 || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const double loHz = std::max(5.0, hpfHz);
    const double hiHz = std::max(loHz + 10.0, lpfHz);

    LinkwitzRiley4 lpLowL, lpLowR, hpLowL, hpLowR;
    LinkwitzRiley4 lpHighL, lpHighR, hpHighL, hpHighR;
    lpLowL.setup(sampleRate, loHz, false);  lpLowR.setup(sampleRate, loHz, false);
    hpLowL.setup(sampleRate, loHz, true);   hpLowR.setup(sampleRate, loHz, true);
    lpHighL.setup(sampleRate, hiHz, false); lpHighR.setup(sampleRate, hiHz, false);
    hpHighL.setup(sampleRate, hiHz, true);  hpHighR.setup(sampleRate, hiHz, true);

    const double m = static_cast<double>(mixPct) / 100.0;
    const double midGain = (m < 0.0) ? std::max(0.0, 1.0 + m) : 1.0;
    const double sideGain = (m > 0.0) ? std::max(0.0, 1.0 - m) : (1.0 + 0.25 * (-m));
    const double outGain = std::pow(10.0, static_cast<double>(outputDb) / 20.0);

    for (int64_t f = selStart; f < selEnd; ++f) {
        const double l = interleaved[static_cast<size_t>(f * channels + 0)] / 32768.0;
        const double r = interleaved[static_cast<size_t>(f * channels + 1)] / 32768.0;

        const double lowL = lpLowL.process(l);
        const double lowR = lpLowR.process(r);
        const double aboveLowL = hpLowL.process(l);
        const double aboveLowR = hpLowR.process(r);

        const double bandL = lpHighL.process(aboveLowL);
        const double bandR = lpHighR.process(aboveLowR);
        const double highL = hpHighL.process(aboveLowL);
        const double highR = hpHighR.process(aboveLowR);

        const double mid = 0.5 * (bandL + bandR) * midGain;
        const double side = 0.5 * (bandL - bandR) * sideGain;

        const double outL = (mid + side + lowL + highL) * outGain;
        const double outR = (mid - side + lowR + highR) * outGain;

        interleaved[static_cast<size_t>(f * channels + 0)] = floatToInt16(outL);
        interleaved[static_cast<size_t>(f * channels + 1)] = floatToInt16(outR);
    }
}

void clipFix(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float thresholdPct,
    float reduceDb
) {
    if (channels <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd - selStart < 8) return;

    const double thresh = std::clamp(thresholdPct / 100.0, 0.5, 0.999);
    const double atten = std::pow(10.0, reduceDb / 20.0);

    for (int32_t ch = 0; ch < channels; ++ch) {
        std::vector<double> buf(static_cast<size_t>(selEnd - selStart));
        for (int64_t f = selStart; f < selEnd; ++f) {
            buf[static_cast<size_t>(f - selStart)] = (interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0) * atten;
        }
        const int64_t n = static_cast<int64_t>(buf.size());
        const double scaledThresh = thresh * atten;
        int64_t i = 0;
        while (i < n) {
            if (std::abs(buf[static_cast<size_t>(i)]) >= scaledThresh) {
                int64_t start = i;
                while (i < n && std::abs(buf[static_cast<size_t>(i)]) >= scaledThresh) i++;
                int64_t end = i;
                if (start > 1 && end < n - 1) {
                    const double y0 = buf[static_cast<size_t>(start - 1)];
                    const double y1 = buf[static_cast<size_t>(end)];
                    const double d0 = y0 - buf[static_cast<size_t>(start - 2)];
                    const double d1 = buf[static_cast<size_t>(end + 1)] - y1;
                    const int64_t span = end - start + 1;
                    for (int64_t k = start; k < end; ++k) {
                        const double t = static_cast<double>(k - start + 1) / span;
                        const double t2 = t * t, t3 = t2 * t;
                        const double h00 = 2 * t3 - 3 * t2 + 1;
                        const double h10 = t3 - 2 * t2 + t;
                        const double h01 = -2 * t3 + 3 * t2;
                        const double h11 = t3 - t2;
                        buf[static_cast<size_t>(k)] = h00 * y0 + h10 * (d0 * span) + h01 * y1 + h11 * (d1 * span);
                    }
                }
            } else {
                i++;
            }
        }
        for (int64_t f = selStart; f < selEnd; ++f) {
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(buf[static_cast<size_t>(f - selStart)]);
        }
    }
}

void vocoder(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    int32_t bands,
    double carrierHz,
    float mixPct
) {
    if (channels <= 0 || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const int32_t nBands = std::clamp(bands, 4, 32);
    const double wet = std::clamp(mixPct / 100.0, 0.0, 1.0);
    const double cHz = std::max(30.0, carrierHz);

    struct BpState {
        double b0 = 0, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        double mz1 = 0, mz2 = 0, cz1 = 0, cz2 = 0, env = 0;
        void setup(int32_t sr, double freq, double q) {
            const double w0 = 2.0 * kPi * std::clamp(freq, 40.0, sr * 0.45) / sr;
            const double alpha = std::sin(w0) / (2.0 * q);
            const double a0 = 1.0 + alpha;
            b0 = alpha / a0; b1 = 0.0; b2 = -alpha / a0;
            a1 = (-2.0 * std::cos(w0)) / a0; a2 = (1.0 - alpha) / a0;
        }
        inline double step(double modIn, double carIn) {
            const double my = b0 * modIn + mz1;
            mz1 = b1 * modIn - a1 * my + mz2;
            mz2 = b2 * modIn - a2 * my;
            env = 0.992 * env + 0.008 * std::abs(my);
            const double cy = b0 * carIn + cz1;
            cz1 = b1 * carIn - a1 * cy + cz2;
            cz2 = b2 * carIn - a2 * cy;
            return cy * env * 6.0;
        }
    };

    std::vector<BpState> bank(static_cast<size_t>(nBands));
    for (int32_t b = 0; b < nBands; ++b) {
        const double f = 100.0 * std::pow(80.0, static_cast<double>(b) / std::max(1, nBands - 1));
        bank[static_cast<size_t>(b)].setup(sampleRate, f, 4.5);
    }

    for (int64_t f = selStart; f < selEnd; ++f) {
        const double l = interleaved[static_cast<size_t>(f * channels + 0)] / 32768.0;
        const double r = (channels > 1) ? (interleaved[static_cast<size_t>(f * channels + 1)] / 32768.0) : l;
        const double mod = 0.5 * (l + r);
        const double phase = std::fmod((static_cast<double>(f - selStart) / sampleRate) * cHz, 1.0);
        const double car = (2.0 * phase - 1.0) * 0.7;
        double sum = 0.0;
        for (int32_t b = 0; b < nBands; ++b) sum += bank[static_cast<size_t>(b)].step(mod, car);
        sum /= std::sqrt(static_cast<double>(nBands));
        interleaved[static_cast<size_t>(f * channels + 0)] = floatToInt16(l * (1.0 - wet) + sum * wet);
        if (channels > 1) interleaved[static_cast<size_t>(f * channels + 1)] = floatToInt16(r * (1.0 - wet) + sum * wet);
    }
}

void deEsser(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    double freqHz,
    float thresholdDb,
    float ratio
) {
    if (channels <= 0 || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const double threshLin = std::pow(10.0, thresholdDb / 20.0);
    const double r = std::max(1.0, static_cast<double>(ratio));
    Biquad2 hpDet[2];
    for (int c = 0; c < std::min(2, channels); ++c) hpDet[c].setButterworth(sampleRate, freqHz, true);

    double env[2] = {0.0, 0.0};
    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            const int32_t idx = std::min(1, ch);
            const double x = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            const double sib = hpDet[idx].process(x);
            env[idx] = 0.985 * env[idx] + 0.015 * std::abs(sib);
            double g = 1.0;
            if (env[idx] > threshLin && threshLin > 1e-9) {
                const double overDb = 20.0 * std::log10(env[idx] / threshLin);
                const double cutDb = overDb * (1.0 - 1.0 / r);
                g = std::pow(10.0, -cutDb / 20.0);
            }
            const double out = (x - sib) + sib * g;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(out);
        }
    }
}

void multibandCompressor(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    double lowSplitHz,
    double highSplitHz,
    float thresholdDb,
    float ratio,
    float makeupDb
) {
    if (channels <= 0 || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const double loHz = std::max(20.0, lowSplitHz);
    const double hiHz = std::max(loHz + 50.0, highSplitHz);
    const double threshLin = std::pow(10.0, thresholdDb / 20.0);
    const double r = std::max(1.0, static_cast<double>(ratio));
    const double mk = std::pow(10.0, makeupDb / 20.0);

    LinkwitzRiley4 lp1[2], hp1[2], lp2[2], hp2[2];
    for (int c = 0; c < 2; ++c) {
        lp1[c].setup(sampleRate, loHz, false); hp1[c].setup(sampleRate, loHz, true);
        lp2[c].setup(sampleRate, hiHz, false); hp2[c].setup(sampleRate, hiHz, true);
    }

    double env[2][3] = {};
    auto compGain = [&](double& e, double s) -> double {
        e = 0.992 * e + 0.008 * std::abs(s);
        if (e <= threshLin || threshLin <= 1e-9) return 1.0;
        const double overDb = 20.0 * std::log10(e / threshLin);
        return std::pow(10.0, -(overDb * (1.0 - 1.0 / r)) / 20.0);
    };

    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            const int32_t idx = std::min(1, ch);
            const double x = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            const double bLow = lp1[idx].process(x);
            const double above = hp1[idx].process(x);
            const double bMid = lp2[idx].process(above);
            const double bHigh = hp2[idx].process(above);

            const double out = (bLow * compGain(env[idx][0], bLow) +
                                bMid * compGain(env[idx][1], bMid) +
                                bHigh * compGain(env[idx][2], bHigh)) * mk;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(out);
        }
    }
}

void saturator(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate,
    int32_t mode,
    float driveDb,
    float warmth,
    float mixPct
) {
    if (channels <= 0 || sampleRate <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const double drive = std::pow(10.0, driveDb / 20.0);
    const double wet = std::clamp(mixPct / 100.0, 0.0, 1.0);
    const double bias = (mode == 1) ? 0.15 * warmth : 0.0;
    double lpPrev[2] = {0.0, 0.0};
    const double lpAlpha = std::clamp(1.0 - warmth * 0.45, 0.25, 1.0);

    for (int64_t f = selStart; f < selEnd; ++f) {
        for (int32_t ch = 0; ch < channels; ++ch) {
            const int32_t idx = std::min(1, ch);
            const double dry = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            const double driven = dry * drive + bias;
            double sat = std::tanh(driven) - std::tanh(bias);
            if (mode == 0) sat = 0.85 * sat + 0.15 * (driven / (1.0 + std::abs(driven)));
            lpPrev[idx] = lpPrev[idx] + lpAlpha * (sat - lpPrev[idx]);
            const double out = dry * (1.0 - wet) + lpPrev[idx] * wet;
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(out);
        }
    }
}

std::vector<int16_t> generateRissetDrum(
    double freqHz,
    double decaySec,
    double centerNoiseHz,
    double durationSec,
    int32_t sampleRate,
    int32_t channels
) {
    if (sampleRate <= 0 || channels <= 0) return {};
    const int64_t totalFrames = std::max<int64_t>(1, static_cast<int64_t>(std::llround(durationSec * sampleRate)));
    std::vector<int16_t> out(static_cast<size_t>(totalFrames * channels));
    const double f0 = std::max(20.0, freqHz);
    const double dec = std::max(0.05, decaySec);
    Biquad2 bp; bp.setButterworth(sampleRate, centerNoiseHz, false);
    uint32_t rng = 0x55AA3311u;
    double phase = 0.0;

    for (int64_t f = 0; f < totalFrames; ++f) {
        const double t = static_cast<double>(f) / sampleRate;
        const double env = std::exp(-t * 4.5 / dec);
        const double instFreq = f0 * (1.0 + 1.8 * std::exp(-t * 25.0));
        phase += instFreq / sampleRate;
        const double tone = std::sin(2.0 * kPi * phase) * env;
        rng = rng * 1664525u + 1013904223u;
        const double noise = bp.process(((static_cast<double>(rng) / 2147483648.0) - 1.0)) * std::exp(-t * 14.0 / dec);
        const int16_t s16 = floatToInt16((0.7 * tone + 0.35 * noise) * 0.9);
        for (int32_t ch = 0; ch < channels; ++ch) out[static_cast<size_t>(f * channels + ch)] = s16;
    }
    return out;
}

int32_t estimateKey(
    const int16_t* src,
    int64_t totalFrames,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    int32_t sampleRate
) {
    if (!src || totalFrames <= 0 || channels <= 0 || sampleRate <= 0) return 0;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames - 1);
    selEnd = std::clamp<int64_t>(selEnd, selStart + 1, totalFrames);
    constexpr int32_t kN = 4096;
    const int64_t len = std::min<int64_t>(selEnd - selStart, static_cast<int64_t>(sampleRate) * 15);
    double chroma[12] = {};
    std::vector<double> re(kN), im(kN), win(kN);
    for (int32_t i = 0; i < kN; ++i) win[i] = 0.5 * (1.0 - std::cos(2.0 * kPi * i / (kN - 1)));

    for (int64_t pos = selStart; pos + kN <= selStart + len; pos += kN) {
        for (int32_t i = 0; i < kN; ++i) {
            double s = src[static_cast<size_t>((pos + i) * channels)] / 32768.0;
            if (channels > 1) s = 0.5 * (s + src[static_cast<size_t>((pos + i) * channels + 1)] / 32768.0);
            re[i] = s * win[i]; im[i] = 0.0;
        }
        fftRadix2(re, im, false);
        for (int32_t b = 4; b < kN / 2; ++b) {
            const double freq = static_cast<double>(b) * sampleRate / kN;
            if (freq < 60.0 || freq > 2000.0) continue;
            const double mag = std::sqrt(re[b] * re[b] + im[b] * im[b]);
            const int32_t midi = static_cast<int32_t>(std::llround(69.0 + 12.0 * std::log2(freq / 440.0)));
            const int32_t pc = ((midi % 12) + 12) % 12;
            chroma[pc] += mag;
        }
    }

    static constexpr double kMaj[12] = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    static constexpr double kMin[12] = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    double bestScore = -1e18;
    int32_t bestKey = 0;
    for (int32_t root = 0; root < 12; ++root) {
        double sMaj = 0.0, sMin = 0.0;
        for (int32_t i = 0; i < 12; ++i) {
            const double c = chroma[(root + i) % 12];
            sMaj += c * kMaj[i];
            sMin += c * kMin[i];
        }
        if (sMaj > bestScore) { bestScore = sMaj; bestKey = root; }
        if (sMin > bestScore) { bestScore = sMin; bestKey = 12 + root; }
    }
    return bestKey;
}

void bitcrusher(
    std::vector<int16_t>& interleaved,
    int32_t channels,
    int64_t selStart,
    int64_t selEnd,
    float bitDepth,
    int32_t downsampleFactor,
    float mixPct
) {
    if (channels <= 0) return;
    const int64_t totalFrames = static_cast<int64_t>(interleaved.size()) / channels;
    selStart = std::clamp<int64_t>(selStart, 0, totalFrames);
    selEnd = std::clamp<int64_t>(selEnd, selStart, totalFrames);
    if (selEnd <= selStart) return;

    const double bits = std::clamp(static_cast<double>(bitDepth), 1.0, 24.0);
    const double levels = std::pow(2.0, bits - 1.0);
    const int32_t holdN = std::max(1, downsampleFactor);
    const double wet = std::clamp(mixPct / 100.0, 0.0, 1.0);

    double held[2] = {0.0, 0.0};
    for (int64_t f = selStart; f < selEnd; ++f) {
        const bool updateHold = ((f - selStart) % holdN) == 0;
        for (int32_t ch = 0; ch < channels; ++ch) {
            const double dry = interleaved[static_cast<size_t>(f * channels + ch)] / 32768.0;
            if (updateHold) {
                const double q = std::round(dry * levels) / levels;
                held[std::min(1, ch)] = std::clamp(q, -1.0, 1.0);
            }
            const double crushed = held[std::min(1, ch)];
            interleaved[static_cast<size_t>(f * channels + ch)] = floatToInt16(dry * (1.0 - wet) + crushed * wet);
        }
    }
}

} // namespace WaveZDsp
