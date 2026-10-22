#pragma once

#include <oboe/Oboe.h>
#include <memory>
#include <mutex>
#include <string>

class GwAudioEngine final
        : public oboe::AudioStreamDataCallback {
public:
    static GwAudioEngine& instance();

    std::string open(
        const std::string& format,
        int32_t sampleRate,
        int32_t deviceId,
        bool exclusive
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

    std::string statusLocked() const;
    void closeLocked();

    mutable std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;

    oboe::AudioFormat requestedFormat_ = oboe::AudioFormat::I32;
    int32_t requestedRate_ = 48000;
    int32_t requestedDevice_ = -1;
    bool requestedExclusive_ = true;
    bool started_ = false;
    std::string error_;
};
