#pragma once

#include <string>
#include <vector>

namespace linuxdroid {

struct NativeGpuInfo {
    std::string vendor;
    std::string renderer;
    std::string version;
    std::string extensions;
    bool vulkanSupported = false;
    bool hardwareAccelerated = false;
};

class GpuDetector {
public:
    static NativeGpuInfo detect();
};

} // namespace linuxdroid

