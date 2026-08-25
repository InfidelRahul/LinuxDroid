/**
 * Process manager native helper functions.
 *
 * Provides low-level process inspection functions that complement
 * the Kotlin ProcessManager. These are NOT JNI entry points —
 * they are internal helpers called from linuxdroid_bridge.cpp.
 */

#include "process_manager.h"

#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

#define TAG "LinuxDroid/ProcessMgr"
#define LOGD(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace linuxdroid {

bool processExists(pid_t pid) {
    if (pid <= 0) return false;
    // kill(pid, 0) checks if the process exists without sending a signal
    if (kill(pid, 0) == 0) return true;
    // EPERM means process exists but we don't have permission to signal it
    return (errno == EPERM);
}

std::string getProcessName(pid_t pid) {
    std::string path = "/proc/" + std::to_string(pid) + "/comm";
    std::ifstream f(path);
    if (!f.is_open()) return "";
    std::string name;
    std::getline(f, name);
    return name;
}

long getProcessRssKb(pid_t pid) {
    std::string path = "/proc/" + std::to_string(pid) + "/status";
    std::ifstream f(path);
    if (!f.is_open()) return -1;
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind("VmRSS:", 0) == 0) {
            std::istringstream iss(line);
            std::string label;
            long kb = 0;
            iss >> label >> kb;
            return kb;
        }
    }
    return -1;
}

} // namespace linuxdroid
