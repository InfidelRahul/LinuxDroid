/**
 * LinuxDroid Native Bridge — Implementation
 *
 * JNI implementations for the LinuxDroid native bridge.
 *
 * Thread safety: Each JNI function is independent.
 * Stateful operations use synchronized Kotlin-side management.
 *
 * Resource ownership:
 * - jstring parameters are converted to std::string immediately.
 * - No JNI local references are stored beyond function scope.
 */

#include "linuxdroid_bridge.h"

#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <csignal>

#define TAG "LinuxDroid/Bridge"
#define LOGD(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, TAG, fmt, ##__VA_ARGS__)
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

namespace {

/**
 * Converts a jstring to a std::string.
 * Caller must NOT release the returned string through JNI.
 */
std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

/**
 * Returns the ABI string for the current device.
 */
const char* getCurrentAbi() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

} // anonymous namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetBridgeVersion(
    JNIEnv* env, jclass clazz) {
    LOGI("nativeGetBridgeVersion() -> %d", LINUXDROID_BRIDGE_VERSION);
    return LINUXDROID_BRIDGE_VERSION;
}

JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeIsExecutable(
    JNIEnv* env, jclass clazz, jstring path) {
    const std::string pathStr = jstringToString(env, path);
    if (pathStr.empty()) return JNI_FALSE;

    struct stat st{};
    if (stat(pathStr.c_str(), &st) != 0) {
        LOGD("nativeIsExecutable: stat failed for %s: %s", pathStr.c_str(), strerror(errno));
        return JNI_FALSE;
    }

    const bool isExec = (st.st_mode & (S_IXUSR | S_IXGRP | S_IXOTH)) != 0;
    LOGD("nativeIsExecutable: %s -> %s", pathStr.c_str(), isExec ? "true" : "false");
    return isExec ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSetExecutable(
    JNIEnv* env, jclass clazz, jstring path) {
    const std::string pathStr = jstringToString(env, path);
    if (pathStr.empty()) return EINVAL;

    struct stat st{};
    if (stat(pathStr.c_str(), &st) != 0) {
        const int err = errno;
        LOGE("nativeSetExecutable: stat failed for %s: %s", pathStr.c_str(), strerror(err));
        return err;
    }

    const mode_t newMode = st.st_mode | S_IXUSR | S_IXGRP | S_IXOTH;
    if (chmod(pathStr.c_str(), newMode) != 0) {
        const int err = errno;
        LOGE("nativeSetExecutable: chmod failed for %s: %s", pathStr.c_str(), strerror(err));
        return err;
    }

    LOGD("nativeSetExecutable: %s -> ok", pathStr.c_str());
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetAbi(
    JNIEnv* env, jclass clazz) {
    const char* abi = getCurrentAbi();
    LOGD("nativeGetAbi() -> %s", abi);
    return env->NewStringUTF(abi);
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendSignal(
    JNIEnv* env, jclass clazz, jint pid, jint signal) {
    if (pid <= 0) {
        LOGE("nativeSendSignal: invalid PID %d", (int)pid);
        return EINVAL;
    }

    LOGD("nativeSendSignal: kill(%d, %d)", (int)pid, (int)signal);
    if (kill((pid_t)pid, (int)signal) != 0) {
        const int err = errno;
        LOGE("nativeSendSignal: kill(%d, %d) failed: %s", (int)pid, (int)signal, strerror(err));
        return err;
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetAvailableMemoryBytes(
    JNIEnv* env, jclass clazz) {
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) {
        LOGE("nativeGetAvailableMemoryBytes: cannot open /proc/meminfo");
        return -1L;
    }

    std::string line;
    while (std::getline(meminfo, line)) {
        if (line.rfind("MemAvailable:", 0) == 0) {
            std::istringstream iss(line);
            std::string label;
            long kbytes = 0;
            iss >> label >> kbytes;
            const long bytes = kbytes * 1024L;
            LOGD("nativeGetAvailableMemoryBytes -> %ld bytes", bytes);
            return (jlong)bytes;
        }
    }
    return -1L;
}

} // extern "C"
