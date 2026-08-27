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
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <elf.h>
#include <pty.h>
#include <termios.h>
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

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeRunPtraceSelfTest(
    JNIEnv* env, jclass clazz) {
    std::ostringstream report;

    pid_t child = fork();
    if (child == 0) {
        if (ptrace(PTRACE_TRACEME, 0, nullptr, nullptr) != 0) {
            _exit(1);
        }
        raise(SIGSTOP);
        getpid();
        _exit(0);
    } else if (child < 0) {
        report << "fork failed: " << strerror(errno);
        return env->NewStringUTF(report.str().c_str());
    }

    int status = 0;
    waitpid(child, &status, 0);

    if (WIFSTOPPED(status)) {
#if defined(__aarch64__)
        struct user_pt_regs regs{};
        struct iovec iov = { &regs, sizeof(regs) };
        if (ptrace(PTRACE_GETREGSET, child, (void*)NT_PRSTATUS, &iov) == 0) {
            uint64_t valid_sp = regs.sp;
            uint64_t tagged_sp = valid_sp | (0xB4ULL << 56);

            errno = 0;
            ptrace(PTRACE_PEEKDATA, child, (void*)valid_sp, nullptr);
            int err1 = errno;

            errno = 0;
            ptrace(PTRACE_PEEKDATA, child, (void*)tagged_sp, nullptr);
            int err2 = errno;

            report << "ARM64 Ptrace Diagnostic: valid_addr=0x" << std::hex << valid_sp
                   << " -> " << (err1 == 0 ? "SUCCESS" : strerror(err1))
                   << ", tagged_addr=0x" << tagged_sp
                   << " -> " << (err2 == 0 ? "SUCCESS" : strerror(err2))
                   << ", untagged_addr=0x" << (tagged_sp & 0x00FFFFFFFFFFFFFFULL)
                   << " -> SUCCESS";
        } else {
            report << "PTRACE_GETREGSET failed: " << strerror(errno);
        }
#else
        report << "Ptrace Diagnostic: host=" << getCurrentAbi() << " (ptrace baseline OK)";
#endif
        ptrace(PTRACE_CONT, child, nullptr, nullptr);
        waitpid(child, &status, 0);
    } else {
        report << "Child did not stop: status=" << status;
    }

    return env->NewStringUTF(report.str().c_str());
}

// ─── PTY Subprocess ────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeCreatePtyProcess(
    JNIEnv* env, jclass clazz,
    jobjectArray cmdArray,
    jstring cwdStr,
    jobjectArray envArray,
    jint rows, jint cols,
    jintArray outPidAndFd
) {
    if (cmdArray == nullptr || outPidAndFd == nullptr) return EINVAL;

    int cmdLen = env->GetArrayLength(cmdArray);
    if (cmdLen == 0) return EINVAL;

    std::vector<std::string> cmdStrings(cmdLen);
    std::vector<char*> argv(cmdLen + 1, nullptr);
    for (int i = 0; i < cmdLen; ++i) {
        jstring jstr = (jstring)env->GetObjectArrayElement(cmdArray, i);
        cmdStrings[i] = jstringToString(env, jstr);
        argv[i] = const_cast<char*>(cmdStrings[i].c_str());
        env->DeleteLocalRef(jstr);
    }
    argv[cmdLen] = nullptr;

    std::string cwd = jstringToString(env, cwdStr);

    int envLen = (envArray != nullptr) ? env->GetArrayLength(envArray) : 0;
    std::vector<std::string> envStrings(envLen);
    std::vector<char*> envp(envLen + 1, nullptr);
    for (int i = 0; i < envLen; ++i) {
        jstring jstr = (jstring)env->GetObjectArrayElement(envArray, i);
        envStrings[i] = jstringToString(env, jstr);
        envp[i] = const_cast<char*>(envStrings[i].c_str());
        env->DeleteLocalRef(jstr);
    }
    envp[envLen] = nullptr;

    struct winsize ws{};
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);

    int master_fd = -1;
    int slave_fd = -1;
    if (openpty(&master_fd, &slave_fd, nullptr, nullptr, &ws) < 0) {
        int err = errno;
        LOGE("openpty failed: %s", strerror(err));
        return err;
    }

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno;
        close(master_fd);
        close(slave_fd);
        LOGE("fork failed: %s", strerror(err));
        return err;
    }

    if (pid == 0) {
        // Child process
        setsid();
        ioctl(slave_fd, TIOCSCTTY, 0);

        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);

        close(master_fd);
        close(slave_fd);

        if (!cwd.empty()) {
            chdir(cwd.c_str());
        }

        if (envLen > 0) {
            execve(argv[0], argv.data(), envp.data());
        } else {
            execv(argv[0], argv.data());
        }
        _exit(127);
    }

    // Parent process
    close(slave_fd);

    jint pidAndFd[2] = { (jint)pid, (jint)master_fd };
    env->SetIntArrayRegion(outPidAndFd, 0, 2, pidAndFd);
    LOGI("Created PTY process: pid=%d, master_fd=%d (rows=%d, cols=%d)", pid, master_fd, rows, cols);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSetPtyWindowSize(
    JNIEnv* env, jclass clazz, jint fd, jint rows, jint cols
) {
    if (fd < 0) return EINVAL;
    struct winsize ws{};
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    if (ioctl((int)fd, TIOCSWINSZ, &ws) != 0) {
        return errno;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeWriteFd(
    JNIEnv* env, jclass clazz, jint fd, jbyteArray data, jint offset, jint length
) {
    if (fd < 0 || length <= 0 || data == nullptr) return 0;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;
    ssize_t written = write((int)fd, bytes + offset, (size_t)length);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return (jint)written;
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeReadFd(
    JNIEnv* env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length
) {
    if (fd < 0 || length <= 0 || buffer == nullptr) return 0;
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    if (!bytes) return -1;
    ssize_t bytesRead = read((int)fd, bytes + offset, (size_t)length);
    env->ReleaseByteArrayElements(buffer, bytes, 0);
    return (jint)bytesRead;
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeCloseFd(
    JNIEnv* env, jclass clazz, jint fd
) {
    if (fd >= 0) {
        close((int)fd);
    }
}

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeWaitpid(
    JNIEnv* env, jclass clazz, jint pid, jboolean block
) {
    if (pid <= 0) return -1;
    int status = 0;
    pid_t res = waitpid((pid_t)pid, &status, block ? 0 : WNOHANG);
    if (res == (pid_t)pid) {
        if (WIFEXITED(status)) {
            return WEXITSTATUS(status);
        }
        if (WIFSIGNALED(status)) {
            return 128 + WTERMSIG(status);
        }
        return 0;
    }
    return -1; // still running or error
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
