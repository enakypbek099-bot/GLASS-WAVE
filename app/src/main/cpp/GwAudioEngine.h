#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

class GwAudioEngine final :
    public oboe::AudioStreamDataCallback {

public:
    static GwAudioEngine& instance();

    std::string loadWav(
        int fd,
        int64_t assetOffset,
        int64_t assetLength,
        int32_t deviceId,
        bool exclusive,
        int64_t startMs,
        bool autoStart
    );

    std::string seek(
        int64_t targetMs,
        bool resume
    );

    std::string start();
    std::string stop();
    std::string close();
    std::string status();

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames
    ) override;

private:
    GwAudioEngine() = default;
    ~GwAudioEngine();

    GwAudioEngine(
        const GwAudioEngine&
    ) = delete;

    GwAudioEngine& operator=(
        const GwAudioEngine&
    ) = delete;

    struct WavInfo {
        uint16_t format = 0;
        uint16_t channels = 0;
        uint32_t sampleRate = 0;
        uint16_t blockAlign = 0;
        uint16_t bits = 0;

        int64_t dataOffset = 0;
        int64_t dataSize = 0;
        int64_t totalFrames = 0;
    };

    bool parseWav(
        int fd,
        int64_t base,
        int64_t length,
        WavInfo& out,
        std::string& error
    );

    bool openStreamLocked(
        int32_t deviceId,
        bool exclusive
    );

    void decoderLoop();

    void startDecoderLocked(
        int64_t startFrame
    );

    void stopDecoderLocked();

    void waitStreamState(
        oboe::StreamState wanted,
        int64_t timeoutMs
    );

    void closeLocked();

    std::string statusLocked() const;

    mutable std::mutex mutex_;

    std::shared_ptr<oboe::AudioStream>
        stream_;

    int sourceFd_ = -1;

    WavInfo wav_;

    std::thread decoderThread_;

    std::vector<int32_t> ring_;

    size_t ringFrames_ = 0;

    std::atomic<uint64_t>
        readFrame_{0};

    std::atomic<uint64_t>
        writeFrame_{0};

    std::atomic<int64_t>
        positionFrames_{0};

    std::atomic<bool>
        stopDecoder_{false};

    std::atomic<bool>
        decoderEof_{false};

    std::atomic<bool>
        ended_{false};

    std::atomic<bool>
        started_{false};

    bool requestedExclusive_ = true;

    int32_t requestedDeviceId_ = -1;

    std::string error_;
};
