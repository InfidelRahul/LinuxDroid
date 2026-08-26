#pragma once

#include <cstdint>
#include <mutex>
#include <vector>

namespace linuxdroid {

class AudioBridge {
public:
    static AudioBridge& getInstance();

    bool start(int sampleRate, int channels, int bufferSizeFrames);
    void stop();
    int writePcm(const uint8_t* data, size_t size);
    bool isActive() const;
    int getLatencyMs() const;

private:
    AudioBridge();
    ~AudioBridge();

    mutable std::mutex mutex_;
    bool active_ = false;
    int sampleRate_ = 44100;
    int channels_ = 2;
    int bufferSizeFrames_ = 1024;
    int latencyMs_ = 20;
};

} // namespace linuxdroid

