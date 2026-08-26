#include "audio_bridge.h"

#include <android/log.h>

#define TAG "LinuxDroid/AudioBridge"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

AudioBridge& AudioBridge::getInstance() {
    static AudioBridge instance;
    return instance;
}

AudioBridge::AudioBridge() = default;

AudioBridge::~AudioBridge() {
    stop();
}

bool AudioBridge::start(int sampleRate, int channels, int bufferSizeFrames) {
    std::lock_guard<std::mutex> lock(mutex_);
    sampleRate_ = sampleRate;
    channels_ = channels;
    bufferSizeFrames_ = bufferSizeFrames;
    active_ = true;
    latencyMs_ = static_cast<int>((bufferSizeFrames_ * 1000.0) / sampleRate_);
    LOGI("AudioBridge started (rate=%d, ch=%d, latency=%dms)", sampleRate_, channels_, latencyMs_);
    return true;
}

void AudioBridge::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    active_ = false;
    LOGI("AudioBridge stopped");
}

int AudioBridge::writePcm(const uint8_t* data, size_t size) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!active_ || data == nullptr) return -1;
    // In low-latency native pipeline, buffer would be consumed by AAudio stream callback.
    return static_cast<int>(size);
}

bool AudioBridge::isActive() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return active_;
}

int AudioBridge::getLatencyMs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return latencyMs_;
}

} // namespace linuxdroid

