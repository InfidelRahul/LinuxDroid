#include "linuxdroid_bridge.h"
#include "display_bridge.h"
#include "gpu_detector.h"
#include "input_bridge.h"
#include "audio_bridge.h"

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

std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

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
    return LINUXDROID_BRIDGE_VERSION;
}

JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeIsExecutable(
    JNIEnv* env, jclass clazz, jstring path) {
    const std::string pathStr = jstringToString(env, path);
    if (pathStr.empty()) return JNI_FALSE;

    struct stat st{};
    if (stat(pathStr.c_str(), &st) != 0) {
        return JNI_FALSE;
    }
    const bool isExec = (st.st_mode & (S_IXUSR | S_IXGRP | S_IXOTH)) != 0;
    return isExec ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSetExecutable(
    JNIEnv* env, jclass clazz, jstring path) {
    const std::string pathStr = jstringToString(env, path);
    if (pathStr.empty()) return EINVAL;

    struct stat st{};
    if (stat(pathStr.c_str(), &st) != 0) {
        return errno;
    }
    const mode_t newMode = st.st_mode | S_IXUSR | S_IXGRP | S_IXOTH;
    if (chmod(pathStr.c_str(), newMode) != 0) {
        return errno;
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetAbi(
    JNIEnv* env, jclass clazz) {
    return env->NewStringUTF(getCurrentAbi());
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendSignal(
    JNIEnv* env, jclass clazz, jint pid, jint signal) {
    if (pid <= 0) return EINVAL;
    if (kill((pid_t)pid, (int)signal) != 0) {
        return errno;
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetAvailableMemoryBytes(
    JNIEnv* env, jclass clazz) {
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) return -1L;

    std::string line;
    while (std::getline(meminfo, line)) {
        if (line.rfind("MemAvailable:", 0) == 0) {
            std::istringstream iss(line);
            std::string label;
            long kbytes = 0;
            iss >> label >> kbytes;
            return (jlong)(kbytes * 1024L);
        }
    }
    return -1L;
}

// ─── Display & Surface ──────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeOnSurfaceCreated(
    JNIEnv* env, jclass clazz, jobject surface, jint width, jint height) {
    linuxdroid::DisplayBridge::getInstance().onSurfaceCreated(env, surface, width, height);
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeOnSurfaceChanged(
    JNIEnv* env, jclass clazz, jobject surface, jint width, jint height, jint format) {
    linuxdroid::DisplayBridge::getInstance().onSurfaceChanged(env, surface, width, height, format);
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeOnSurfaceDestroyed(
    JNIEnv* env, jclass clazz) {
    linuxdroid::DisplayBridge::getInstance().onSurfaceDestroyed();
}

// ─── GPU ──────────────────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetGpuVendor(
    JNIEnv* env, jclass clazz) {
    auto info = linuxdroid::GpuDetector::detect();
    return env->NewStringUTF(info.vendor.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetGpuRenderer(
    JNIEnv* env, jclass clazz) {
    auto info = linuxdroid::GpuDetector::detect();
    return env->NewStringUTF(info.renderer.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetGpuVersion(
    JNIEnv* env, jclass clazz) {
    auto info = linuxdroid::GpuDetector::detect();
    return env->NewStringUTF(info.version.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeIsVulkanSupported(
    JNIEnv* env, jclass clazz) {
    auto info = linuxdroid::GpuDetector::detect();
    return info.vulkanSupported ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeIsHardwareAccelerated(
    JNIEnv* env, jclass clazz) {
    auto info = linuxdroid::GpuDetector::detect();
    return info.hardwareAccelerated ? JNI_TRUE : JNI_FALSE;
}

// ─── Input ────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendTouchEvent(
    JNIEnv* env, jclass clazz, jint action, jint pointerId, jfloat x, jfloat y, jfloat pressure) {
    linuxdroid::InputBridge::getInstance().sendTouchEvent(action, pointerId, x, y, pressure);
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendMouseEvent(
    JNIEnv* env, jclass clazz, jint action, jint buttonState, jfloat x, jfloat y, jfloat scrollX, jfloat scrollY) {
    linuxdroid::InputBridge::getInstance().sendMouseEvent(action, buttonState, x, y, scrollX, scrollY);
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendKeyEvent(
    JNIEnv* env, jclass clazz, jint keyCode, jboolean isDown, jint metaState, jint unicodeChar) {
    linuxdroid::InputBridge::getInstance().sendKeyEvent(keyCode, isDown, metaState, unicodeChar);
}

// ─── Audio ────────────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeAudioStart(
    JNIEnv* env, jclass clazz, jint sampleRate, jint channels, jint bufferSizeFrames) {
    return linuxdroid::AudioBridge::getInstance().start(sampleRate, channels, bufferSizeFrames) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeAudioStop(
    JNIEnv* env, jclass clazz) {
    linuxdroid::AudioBridge::getInstance().stop();
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeAudioWritePcm(
    JNIEnv* env, jclass clazz, jbyteArray data, jint offset, jint length) {
    if (data == nullptr || length <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return -1;
    int written = linuxdroid::AudioBridge::getInstance().writePcm(
        reinterpret_cast<const uint8_t*>(bytes + offset), length);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeAudioGetLatencyMs(
    JNIEnv* env, jclass clazz) {
    return linuxdroid::AudioBridge::getInstance().getLatencyMs();
}

} // extern "C"
