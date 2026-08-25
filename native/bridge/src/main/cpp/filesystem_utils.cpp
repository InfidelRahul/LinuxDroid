/**
 * Filesystem utility functions for the native bridge.
 * Path validation, existence checks, and attribute queries.
 */

#include "filesystem_utils.h"

#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

#define TAG "LinuxDroid/FSUtils"
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

bool pathExists(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0;
}

bool isDirectory(const std::string& path) {
    struct stat st{};
    if (stat(path.c_str(), &st) != 0) return false;
    return S_ISDIR(st.st_mode);
}

bool isRegularFile(const std::string& path) {
    struct stat st{};
    if (stat(path.c_str(), &st) != 0) return false;
    return S_ISREG(st.st_mode);
}

long long getFileSize(const std::string& path) {
    struct stat st{};
    if (stat(path.c_str(), &st) != 0) return -1LL;
    return static_cast<long long>(st.st_size);
}

bool mkdirp(const std::string& path, mode_t mode) {
    // Recursively create directories
    std::string current;
    for (size_t i = 0; i <= path.size(); ++i) {
        if (i == path.size() || path[i] == '/') {
            if (!current.empty()) {
                if (mkdir(current.c_str(), mode) != 0 && errno != EEXIST) {
                    LOGE("mkdirp: mkdir(%s) failed: %s", current.c_str(), strerror(errno));
                    return false;
                }
            }
        }
        if (i < path.size()) current += path[i];
    }
    return true;
}

} // namespace linuxdroid
