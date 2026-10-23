#include "GwAudioEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

namespace {

uint16_t le16(const uint8_t* p) {
    return uint16_t(p[0]) |
           (uint16_t(p[1]) << 8);
}

uint32_t le32(const uint8_t* p) {
    return uint32_t(p[0]) |
           (uint32_t(p[1]) << 8) |
           (uint32_t(p[2]) << 16) |
           (uint32_t(p[3]) << 24);
}

bool readAt(
    int fd,
    int64_t off,
    void* dst,
    size_t n
) {
    auto* p =
        static_cast<uint8_t*>(dst);

    size_t done = 0;

    while (done < n) {
        const ssize_t r =
            pread(
                fd,
                p + done,
                n - done,
                off + int64_t(done)
            );

        if (r <= 0) {
            return false;
        }

        done +=
            size_t(r);
    }

    return true;
}

const char* sharingName(
    oboe::SharingMode mode
) {
    return mode ==
        oboe::SharingMode::Exclusive
        ? "EXCLUSIVE"
        : "SHARED";
}

std::string esc(
    const std::string& value
) {
    std::string out;

    for (char c : value) {
        if (
            c == '"' ||
            c == '\\'
        ) {
            out += '\\';
        }

        if (c == '\n') {
            out += "\\n";
        } else {
            out += c;
        }
    }

    return out;
}

}

GwAudioEngine&
GwAudioEngine::instance() {
    static GwAudioEngine engine;
    return engine;
}

GwAudioEngine::~GwAudioEngine() {
    std::lock_guard<std::mutex>
        lock(mutex_);

    closeLocked();
}

bool GwAudioEngine::parseWav(
    int fd,
    int64_t base,
    int64_t length,
    WavInfo& out,
    std::string& error
) {
    uint8_t head[12];

    if (
        !readAt(
            fd,
            base,
            head,
            sizeof(head)
        ) ||
        std::memcmp(
            head,
            "RIFF",
            4
        ) != 0 ||
        std::memcmp(
            head + 8,
            "WAVE",
            4
        ) != 0
    ) {
        error =
            "WAV_RIFF_REQUIRED";

        return false;
    }

    struct stat st{};

    if (
        length <= 0 &&
        fstat(
            fd,
            &st
        ) == 0
    ) {
        length =
            st.st_size -
            base;
    }

    const int64_t limit =
        length > 0
        ? base + length
        : base +
            int64_t(
                le32(
                    head + 4
                )
            ) +
            8;

    int64_t pos =
        base + 12;

    bool fmt =
        false;

    bool data =
        false;

    while (
        pos + 8 <=
        limit
    ) {
        uint8_t chunk[8];

        if (
            !readAt(
                fd,
                pos,
                chunk,
                8
            )
        ) {
            break;
        }

        const uint32_t size =
            le32(
                chunk + 4
            );

        const int64_t payload =
            pos + 8;

        if (
            std::memcmp(
                chunk,
                "fmt ",
                4
            ) == 0 &&
            size >= 16
        ) {
            uint8_t f[40]{};

            const size_t take =
                std::min<size_t>(
                    size,
                    sizeof(f)
                );

            if (
                !readAt(
                    fd,
                    payload,
                    f,
                    take
                )
            ) {
                error =
                    "WAV_FMT_READ";

                return false;
            }

            out.format =
                le16(f);

            out.channels =
                le16(
                    f + 2
                );

            out.sampleRate =
                le32(
                    f + 4
                );

            out.blockAlign =
                le16(
                    f + 12
                );

            out.bits =
                le16(
                    f + 14
                );

            fmt =
                true;

        } else if (
            std::memcmp(
                chunk,
                "data",
                4
            ) == 0
        ) {
            out.dataOffset =
                payload;

            out.dataSize =
                size;

            data =
                true;
        }

        if (
            fmt &&
            data
        ) {
            break;
        }

        pos =
            payload +
            size +
            (size & 1u);
    }

    if (
        !fmt ||
        !data
    ) {
        error =
            "WAV_CHUNKS_MISSING";

        return false;
    }

    if (
        out.channels < 1 ||
        out.channels > 2
    ) {
        error =
            "WAV_CHANNELS_UNSUPPORTED";

        return false;
    }

    const bool pcm =
        out.format == 1 &&
        (
            out.bits == 16 ||
            out.bits == 24 ||
            out.bits == 32
        );

    const bool float32 =
        out.format == 3 &&
        out.bits == 32;

    if (
        !pcm &&
        !float32
    ) {
        error =
            "WAV_FORMAT_UNSUPPORTED";

        return false;
    }

    if (
        out.sampleRate < 8000 ||
        out.sampleRate > 384000 ||
        out.blockAlign == 0
    ) {
        error =
            "WAV_LAYOUT_INVALID";

        return false;
    }

    out.totalFrames =
        out.dataSize /
        out.blockAlign;

    return true;
}

bool GwAudioEngine::openStreamLocked(
    int32_t deviceId,
    bool exclusive
) {
    requestedExclusive_ =
        exclusive;

    requestedDeviceId_ =
        deviceId;

    oboe::AudioStreamBuilder builder;

    builder.setDirection(
        oboe::Direction::Output
    );

    builder.setAudioApi(
        oboe::AudioApi::AAudio
    );

    builder.setFormat(
        oboe::AudioFormat::I32
    );

    builder.setSampleRate(
        int32_t(
            wav_.sampleRate
        )
    );

    builder.setChannelCount(
        2
    );

    builder.setPerformanceMode(
        oboe::PerformanceMode::LowLatency
    );

    builder.setUsage(
        oboe::Usage::Media
    );

    builder.setContentType(
        oboe::ContentType::Music
    );

    builder.setSharingMode(
        exclusive
        ? oboe::SharingMode::Exclusive
        : oboe::SharingMode::Shared
    );

    builder.setDataCallback(
        this
    );

    if (
        deviceId >= 0
    ) {
        builder.setDeviceId(
            deviceId
        );
    }

    oboe::Result result =
        builder.openStream(
            stream_
        );

    if (
        result !=
            oboe::Result::OK &&
        exclusive
    ) {
        stream_.reset();

        builder.setSharingMode(
            oboe::SharingMode::Shared
        );

        result =
            builder.openStream(
                stream_
            );
    }

    if (
        result !=
            oboe::Result::OK ||
        !stream_
    ) {
        error_ =
            oboe::convertToText(
                result
            );

        stream_.reset();

        return false;
    }

    if (
        stream_->getFormat() !=
        oboe::AudioFormat::I32
    ) {
        error_ =
            "STRICT_I32_REJECTED";

        stream_->close();
        stream_.reset();

        return false;
    }

    if (
        stream_->getSampleRate() !=
        int32_t(
            wav_.sampleRate
        )
    ) {
        error_ =
            "SOURCE_RATE_REJECTED";

        stream_->close();
        stream_.reset();

        return false;
    }

    return true;
}

std::string GwAudioEngine::loadWav(
    int fd,
    int64_t assetOffset,
    int64_t assetLength,
    int32_t deviceId,
    bool exclusive,
    int64_t startMs,
    bool autoStart
) {
    std::lock_guard<std::mutex>
        lock(mutex_);

    closeLocked();

    error_.clear();

    wav_ =
        WavInfo{};

    ended_ =
        false;

    sourceFd_ =
        dup(fd);

    if (
        sourceFd_ < 0
    ) {
        error_ =
            "FD_DUP_FAILED";

        return statusLocked();
    }

    if (
        !parseWav(
            sourceFd_,
            assetOffset,
            assetLength,
            wav_,
            error_
        )
    ) {
        closeLocked();

        return statusLocked();
    }

    const int64_t startFrame =
        std::clamp<int64_t>(
            startMs *
                int64_t(
                    wav_.sampleRate
                ) /
                1000,
            0,
            wav_.totalFrames
        );

    ringFrames_ =
        std::max<size_t>(
            4096,
            size_t(
                wav_.sampleRate
            )
        );

    ring_.assign(
        ringFrames_ * 2,
        0
    );

    if (
        !openStreamLocked(
            deviceId,
            exclusive
        )
    ) {
        closeLocked();

        return statusLocked();
    }

    startDecoderLocked(
        startFrame
    );

    for (
        int i = 0;
        i < 100 &&
        writeFrame_.load() == 0 &&
        !decoderEof_.load();
        ++i
    ) {
        usleep(
            1000
        );
    }

    if (
        autoStart
    ) {
        const oboe::Result result =
            stream_->requestStart();

        started_ =
            result ==
            oboe::Result::OK;

        if (
            !started_.load()
        ) {
            error_ =
                oboe::convertToText(
                    result
                );
        }
    } else {
        started_ =
            false;
    }

    return statusLocked();
}

void GwAudioEngine::startDecoderLocked(
    int64_t startFrame
) {
    positionFrames_ =
        startFrame;

    readFrame_ =
        0;

    writeFrame_ =
        0;

    decoderEof_ =
        false;

    ended_ =
        false;

    stopDecoder_ =
        false;

    decoderThread_ =
        std::thread(
            &GwAudioEngine::decoderLoop,
            this
        );
}

void GwAudioEngine::stopDecoderLocked() {
    stopDecoder_ =
        true;

    if (
        decoderThread_.joinable()
    ) {
        decoderThread_.join();
    }
}

void GwAudioEngine::waitStreamState(
    oboe::StreamState wanted,
    int64_t timeoutMs
) {
    if (
        !stream_
    ) {
        return;
    }

    const auto until =
        std::chrono::
            steady_clock::now() +
        std::chrono::
            milliseconds(
                timeoutMs
            );

    while (
        std::chrono::
            steady_clock::now() <
        until
    ) {
        const oboe::StreamState state =
            stream_->getState();

        if (
            state == wanted ||
            state ==
                oboe::StreamState::Closed ||
            state ==
                oboe::StreamState::Disconnected
        ) {
            return;
        }

        usleep(
            2000
        );
    }
}

std::string GwAudioEngine::seek(
    int64_t targetMs,
    bool resume
) {
    std::lock_guard<std::mutex>
        lock(mutex_);

    if (
        !stream_ ||
        wav_.sampleRate == 0
    ) {
        error_ =
            "STREAM_NOT_OPEN";

        return statusLocked();
    }

    error_.clear();

    const int64_t target =
        std::clamp<int64_t>(
            targetMs *
                int64_t(
                    wav_.sampleRate
                ) /
                1000,
            0,
            wav_.totalFrames
        );

    oboe::StreamState state =
        stream_->getState();

    /*
     * Қалыпты pause енді requestPause()
     * қолданады. Сондықтан seek кезінде
     * stream-ді Paused күйіне әкелеміз.
     */
    if (
        state ==
            oboe::StreamState::Started ||
        state ==
            oboe::StreamState::Starting
    ) {
        const oboe::Result pauseResult =
            stream_->requestPause();

        if (
            pauseResult !=
            oboe::Result::OK
        ) {
            error_ =
                oboe::convertToText(
                    pauseResult
                );

            return statusLocked();
        }

        waitStreamState(
            oboe::StreamState::Paused,
            500
        );
    }

    state =
        stream_->getState();

    if (
        state !=
            oboe::StreamState::Paused &&
        state !=
            oboe::StreamState::Flushed
    ) {
        error_ =
            "SEEK_STREAM_NOT_PAUSED";

        return statusLocked();
    }

    if (
        state !=
        oboe::StreamState::Flushed
    ) {
        const oboe::Result flushResult =
            stream_->requestFlush();

        if (
            flushResult !=
            oboe::Result::OK
        ) {
            error_ =
                oboe::convertToText(
                    flushResult
                );

            return statusLocked();
        }

        waitStreamState(
            oboe::StreamState::Flushed,
            500
        );
    }

    stopDecoderLocked();

    if (
        !ring_.empty()
    ) {
        std::fill(
            ring_.begin(),
            ring_.end(),
            0
        );
    }

    startDecoderLocked(
        target
    );

    for (
        int i = 0;
        i < 100 &&
        writeFrame_.load() == 0 &&
        !decoderEof_.load();
        ++i
    ) {
        usleep(
            1000
        );
    }

    if (
        resume
    ) {
        const oboe::Result startResult =
            stream_->requestStart();

        started_ =
            startResult ==
            oboe::Result::OK;

        if (
            !started_.load()
        ) {
            error_ =
                oboe::convertToText(
                    startResult
                );
        }
    } else {
        started_ =
            false;
    }

    return statusLocked();
}

void GwAudioEngine::decoderLoop() {
    const size_t chunkFrames =
        512;

    std::vector<uint8_t> raw(
        chunkFrames *
        wav_.blockAlign
    );

    int64_t frame =
        positionFrames_.load();

    while (
        !stopDecoder_.load() &&
        frame <
            wav_.totalFrames
    ) {
        const uint64_t read =
            readFrame_.load(
                std::memory_order_acquire
            );

        const uint64_t write =
            writeFrame_.load(
                std::memory_order_relaxed
            );

        const size_t free =
            ringFrames_ -
            size_t(
                write -
                read
            );

        if (
            free <
            chunkFrames
        ) {
            usleep(
                2000
            );

            continue;
        }

        const size_t frames =
            std::min<size_t>(
                chunkFrames,
                size_t(
                    wav_.totalFrames -
                    frame
                )
            );

        const size_t bytes =
            frames *
            wav_.blockAlign;

        if (
            !readAt(
                sourceFd_,
                wav_.dataOffset +
                    frame *
                    wav_.blockAlign,
                raw.data(),
                bytes
            )
        ) {
            break;
        }

        const int bytesPerSample =
            wav_.bits /
            8;

        for (
            size_t i = 0;
            i < frames;
            ++i
        ) {
            auto sample =
                [&](int channel)
                    -> int32_t {

                const uint8_t* p =
                    raw.data() +
                    i *
                        wav_.blockAlign +
                    std::min<int>(
                        channel,
                        wav_.channels - 1
                    ) *
                        bytesPerSample;

                if (
                    wav_.format == 3
                ) {
                    float value;

                    std::memcpy(
                        &value,
                        p,
                        4
                    );

                    value =
                        std::clamp(
                            value,
                            -1.0f,
                            1.0f
                        );

                    return int32_t(
                        std::lrint(
                            double(value) *
                            2147483647.0
                        )
                    );
                }

                if (
                    wav_.bits == 16
                ) {
                    return int32_t(
                        int16_t(
                            le16(p)
                        )
                    ) *
                    65536;
                }

                if (
                    wav_.bits == 24
                ) {
                    int32_t value =
                        int32_t(p[0]) |
                        (
                            int32_t(p[1])
                            << 8
                        ) |
                        (
                            int32_t(p[2])
                            << 16
                        );

                    if (
                        value &
                        0x800000
                    ) {
                        value |=
                            ~0xffffff;
                    }

                    return int32_t(
                        int64_t(value) *
                        256
                    );
                }

                return int32_t(
                    le32(p)
                );
            };

            const size_t at =
                size_t(
                    (
                        write +
                        i
                    ) %
                    ringFrames_
                ) *
                2;

            ring_[at] =
                sample(0);

            ring_[at + 1] =
                sample(
                    wav_.channels > 1
                    ? 1
                    : 0
                );
        }

        writeFrame_.store(
            write + frames,
            std::memory_order_release
        );

        frame +=
            frames;
    }

    decoderEof_ =
        true;
}

oboe::DataCallbackResult
GwAudioEngine::onAudioReady(
    oboe::AudioStream*,
    void* audioData,
    int32_t numFrames
) {
    auto* out =
        static_cast<int32_t*>(
            audioData
        );

    const uint64_t read =
        readFrame_.load(
            std::memory_order_relaxed
        );

    const uint64_t write =
        writeFrame_.load(
            std::memory_order_acquire
        );

    const size_t take =
        std::min<size_t>(
            numFrames,
            size_t(
                write -
                read
            )
        );

    for (
        size_t i = 0;
        i < take;
        ++i
    ) {
        const size_t at =
            size_t(
                (
                    read +
                    i
                ) %
                ringFrames_
            ) *
            2;

        out[i * 2] =
            ring_[at];

        out[i * 2 + 1] =
            ring_[at + 1];
    }

    if (
        take <
        size_t(numFrames)
    ) {
        std::memset(
            out +
                take *
                2,
            0,
            (
                size_t(numFrames) -
                take
            ) *
                2 *
                sizeof(int32_t)
        );
    }

    readFrame_.store(
        read + take,
        std::memory_order_release
    );

    positionFrames_.fetch_add(
        int64_t(take)
    );

    if (
        decoderEof_.load() &&
        read + take >=
            write
    ) {
        ended_ =
            true;

        started_ =
            false;

        return
            oboe::
                DataCallbackResult::
                Stop;
    }

    return stopDecoder_.load()
        ? oboe::
            DataCallbackResult::
            Stop
        : oboe::
            DataCallbackResult::
            Continue;
}

std::string GwAudioEngine::start() {
    std::lock_guard<std::mutex>
        lock(mutex_);

    if (
        !stream_
    ) {
        error_ =
            "STREAM_NOT_OPEN";

        return statusLocked();
    }

    error_.clear();

    const oboe::Result result =
        stream_->requestStart();

    started_ =
        result ==
        oboe::Result::OK;

    if (
        !started_.load()
    ) {
        error_ =
            oboe::convertToText(
                result
            );
    }

    return statusLocked();
}

std::string GwAudioEngine::stop() {
    std::lock_guard<std::mutex>
        lock(mutex_);

    if (
        !stream_
    ) {
        started_ =
            false;

        return statusLocked();
    }

    error_.clear();

    const oboe::StreamState state =
        stream_->getState();

    if (
        state ==
            oboe::StreamState::Started ||
        state ==
            oboe::StreamState::Starting
    ) {
        const oboe::Result result =
            stream_->requestPause();

        if (
            result !=
            oboe::Result::OK
        ) {
            error_ =
                oboe::convertToText(
                    result
                );
        } else {
            waitStreamState(
                oboe::StreamState::Paused,
                500
            );
        }
    }

    started_ =
        false;

    return statusLocked();
}

std::string GwAudioEngine::close() {
    std::lock_guard<std::mutex>
        lock(mutex_);

    closeLocked();

    return statusLocked();
}

std::string GwAudioEngine::status() {
    std::lock_guard<std::mutex>
        lock(mutex_);

    return statusLocked();
}

void GwAudioEngine::closeLocked() {
    stopDecoder_ =
        true;

    if (
        stream_
    ) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }

    if (
        decoderThread_.joinable()
    ) {
        decoderThread_.join();
    }

    if (
        sourceFd_ >= 0
    ) {
        ::close(
            sourceFd_
        );

        sourceFd_ =
            -1;
    }

    ring_.clear();

    ringFrames_ =
        0;

    started_ =
        false;

    ended_ =
        false;
}

std::string
GwAudioEngine::statusLocked() const {
    const bool open =
        bool(stream_);

    const int32_t actualRate =
        open
        ? stream_->getSampleRate()
        : 0;

    const int32_t actualChannels =
        open
        ? stream_->getChannelCount()
        : 0;

    const int32_t actualDevice =
        open
        ? stream_->getDeviceId()
        : -1;

    const bool isI32 =
        open &&
        stream_->getFormat() ==
            oboe::AudioFormat::I32;

    const bool verified =
        open &&
        isI32 &&
        actualRate ==
            int32_t(
                wav_.sampleRate
            ) &&
        error_.empty();

    const char* sharing =
        open
        ? sharingName(
            stream_->
                getSharingMode()
        )
        : "NONE";

    const char* api =
        "NONE";

    if (
        open
    ) {
        const auto value =
            stream_->getAudioApi();

        api =
            value ==
                oboe::AudioApi::AAudio
            ? "AAUDIO"
            : (
                value ==
                    oboe::AudioApi::
                        OpenSLES
                ? "OPENSL_ES"
                : "UNSPECIFIED"
            );
    }

    const char* sourceFormat =
        "NONE";

    if (
        wav_.sampleRate > 0
    ) {
        sourceFormat =
            wav_.format == 3
            ? "WAV_FLOAT32"
            : "WAV_PCM_INTEGER";
    }

    const char* state =
        ended_.load()
        ? "ENDED"
        : (
            started_.load()
            ? "STARTED"
            : (
                open
                ? "PAUSED"
                : "CLOSED"
            )
        );

    const int64_t position =
        positionFrames_.load();

    const int64_t positionMs =
        wav_.sampleRate > 0
        ? (
            position *
            1000 /
            int64_t(
                wav_.sampleRate
            )
        )
        : 0;

    const int64_t durationMs =
        wav_.sampleRate > 0
        ? (
            wav_.totalFrames *
            1000 /
            int64_t(
                wav_.sampleRate
            )
        )
        : 0;

    std::ostringstream json;

    json
        << "{"
        << "\"ok\":"
        << (
            open &&
            error_.empty()
            ? "true"
            : "false"
        )
        << ",\"nativeWav\":"
        << (
            wav_.sampleRate > 0
            ? "true"
            : "false"
        )
        << ",\"streamVerified\":"
        << (
            verified
            ? "true"
            : "false"
        )
        << ",\"actualFormat\":\""
        << (
            open
            ? (
                isI32
                ? "I32"
                : "OTHER"
            )
            : "NONE"
        )
        << "\""
        << ",\"sourceFormat\":\""
        << sourceFormat
        << "\""
        << ",\"sourceRate\":"
        << wav_.sampleRate
        << ",\"actualRate\":"
        << actualRate
        << ",\"sourceBits\":"
        << wav_.bits
        << ",\"sourceChannels\":"
        << wav_.channels
        << ",\"actualChannels\":"
        << actualChannels
        << ",\"audioApi\":\""
        << api
        << "\""
        << ",\"actualSharing\":\""
        << sharing
        << "\""
        << ",\"actualDeviceId\":"
        << actualDevice
        << ",\"requestedExclusive\":"
        << (
            requestedExclusive_
            ? "true"
            : "false"
        )
        << ",\"requestedDeviceId\":"
        << requestedDeviceId_
        << ",\"state\":\""
        << state
        << "\""
        << ",\"positionFrames\":"
        << position
        << ",\"durationFrames\":"
        << wav_.totalFrames
        << ",\"positionMs\":"
        << positionMs
        << ",\"durationMs\":"
        << durationMs
        << ",\"error\":\""
        << esc(error_)
        << "\""
        << "}";

    return json.str();
}
