#include "GwAudioEngine.h"

#include <algorithm>
#include <cstring>
#include <sstream>

namespace {
const char* formatName(oboe::AudioFormat format) {
    switch (format) {
        case oboe::AudioFormat::I16:   return "I16";
        case oboe::AudioFormat::I24:   return "I24";
        case oboe::AudioFormat::I32:   return "I32";
        case oboe::AudioFormat::Float: return "FLOAT";
        default: return "UNSPECIFIED";
    }
}

oboe::AudioFormat parseFormat(const std::string& name) {
    if (name == "I16")   return oboe::AudioFormat::I16;
    if (name == "I24")   return oboe::AudioFormat::I24;
    if (name == "FLOAT") return oboe::AudioFormat::Float;
    return oboe::AudioFormat::I32;
}

const char* sharingName(oboe::SharingMode mode) {
    return mode == oboe::SharingMode::Exclusive
        ? "EXCLUSIVE"
        : "SHARED";
}

const char* apiName(oboe::AudioApi api) {
    return api == oboe::AudioApi::AAudio
        ? "AAUDIO"
        : "OTHER";
}

std::string jsonEscape(const std::string& value) {
    std::string output;
    for (char c : value) {
        if (c == '"' || c == '\\') output.push_back('\\');
        if (c == '\n') output += "\\n";
        else output.push_back(c);
    }
    return output;
}
}

GwAudioEngine& GwAudioEngine::instance() {
    static GwAudioEngine engine;
    return engine;
}

std::string GwAudioEngine::open(
        const std::string& format,
        int32_t sampleRate,
        int32_t deviceId,
        bool exclusive) {
    std::lock_guard<std::mutex> lock(mutex_);

    closeLocked();
    error_.clear();

    requestedFormat_   = parseFormat(format);
    requestedRate_     = std::clamp(sampleRate, 8000, 384000);
    requestedDevice_   = deviceId;
    requestedExclusive_ = exclusive;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setAudioApi(oboe::AudioApi::AAudio);
    builder.setFormat(requestedFormat_);
    builder.setSampleRate(requestedRate_);
    builder.setChannelCount(2);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setUsage(oboe::Usage::Media);
    builder.setContentType(oboe::ContentType::Music);
    builder.setSharingMode(
        exclusive
            ? oboe::SharingMode::Exclusive
            : oboe::SharingMode::Shared
    );
    builder.setDataCallback(this);

    if (deviceId >= 0) builder.setDeviceId(deviceId);

    oboe::Result result = builder.openStream(stream_);

    if (result != oboe::Result::OK && exclusive) {
        stream_.reset();
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
    }

    if (result != oboe::Result::OK || !stream_) {
        error_ = oboe::convertToText(result);
        stream_.reset();
        return statusLocked();
    }

    /*
     * STRICT I32:
     * I32 сұралса, нақты формат I32 болмаса stream жабылады.
     * Float32-ге жасырын ауысу жоқ.
     */
    if (requestedFormat_ == oboe::AudioFormat::I32 &&
        stream_->getFormat() != oboe::AudioFormat::I32) {
        error_ = std::string("STRICT_I32_REJECTED: actual format is ") +
                 formatName(stream_->getFormat());
        closeLocked();
    }

    return statusLocked();
}

std::string GwAudioEngine::start() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!stream_) {
        error_ = "STREAM_NOT_OPEN";
        return statusLocked();
    }

    const oboe::Result result = stream_->requestStart();
    started_ = result == oboe::Result::OK;

    if (!started_) error_ = oboe::convertToText(result);
    return statusLocked();
}

std::string GwAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (stream_) stream_->requestStop();
    started_ = false;
    return statusLocked();
}

std::string GwAudioEngine::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    closeLocked();
    return statusLocked();
}

std::string GwAudioEngine::status() {
    std::lock_guard<std::mutex> lock(mutex_);
    return statusLocked();
}

void GwAudioEngine::closeLocked() {
    started_ = false;

    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult GwAudioEngine::onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) {
    const int32_t channels = stream->getChannelCount();
    int32_t bytesPerSample = 4;

    if (stream->getFormat() == oboe::AudioFormat::I16) {
        bytesPerSample = 2;
    } else if (stream->getFormat() == oboe::AudioFormat::I24) {
        bytesPerSample = 3;
    }

    const size_t byteCount =
        static_cast<size_t>(numFrames) *
        static_cast<size_t>(channels) *
        static_cast<size_t>(bytesPerSample);

    std::memset(audioData, 0, byteCount);
    return oboe::DataCallbackResult::Continue;
}

std::string GwAudioEngine::statusLocked() const {
    const bool open = static_cast<bool>(stream_);

    const oboe::AudioFormat actualFormat =
        open ? stream_->getFormat()
             : oboe::AudioFormat::Unspecified;

    const int32_t actualRate =
        open ? stream_->getSampleRate() : 0;

    const int32_t actualDevice =
        open ? stream_->getDeviceId() : -1;

    const int32_t framesPerBurst =
        open ? stream_->getFramesPerBurst() : 0;

    const int32_t bufferSize =
        open ? stream_->getBufferSizeInFrames() : 0;

    int32_t xRun = -1;
    if (open) {
        const auto value = stream_->getXRunCount();
        if (value) xRun = value.value();
    }

    std::ostringstream json;
    json
        << "{"
        << "\"ok\":" << (open && error_.empty() ? "true" : "false")
        << ",\"requestedFormat\":\"" << formatName(requestedFormat_) << "\""
        << ",\"actualFormat\":\"" << formatName(actualFormat) << "\""
        << ",\"requestedRate\":" << requestedRate_
        << ",\"actualRate\":" << actualRate
        << ",\"requestedDevice\":" << requestedDevice_
        << ",\"deviceId\":" << actualDevice
        << ",\"requestedSharing\":\""
        << (requestedExclusive_ ? "EXCLUSIVE" : "SHARED") << "\""
        << ",\"actualSharing\":\""
        << (open ? sharingName(stream_->getSharingMode()) : "NONE") << "\""
        << ",\"api\":\""
        << (open ? apiName(stream_->getAudioApi()) : "NONE") << "\""
        << ",\"state\":\""
        << (started_ ? "STARTED" : (open ? "OPEN" : "CLOSED")) << "\""
        << ",\"framesPerBurst\":" << framesPerBurst
        << ",\"bufferSizeFrames\":" << bufferSize
        << ",\"xRun\":" << xRun
        << ",\"error\":\"" << jsonEscape(error_) << "\""
        << "}";

    return json.str();
}
