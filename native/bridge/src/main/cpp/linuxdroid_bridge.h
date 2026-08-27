#pragma once

#include <jni.h>
#include <string>
#include <vector>

#define LINUXDROID_BRIDGE_VERSION 2

extern "C" {

// Base System Info & Process
JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetBridgeVersion(
    JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeIsExecutable(
    JNIEnv* env, jclass clazz, jstring path);

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSetExecutable(
    JNIEnv* env, jclass clazz, jstring path);

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetAbi(
    JNIEnv* env, jclass clazz);

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendSignal(
    JNIEnv* env, jclass clazz, jint pid, jint signal);

JNIEXPORT jlong JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetAvailableMemoryBytes(
    JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeRunPtraceSelfTest(
    JNIEnv* env, jclass clazz);

// PTY Subprocess
JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeCreatePtyProcess(
    JNIEnv* env, jclass clazz,
    jobjectArray cmdArray,
    jstring cwdStr,
    jobjectArray envArray,
    jint rows, jint cols,
    jintArray outPidAndFd);

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSetPtyWindowSize(
    JNIEnv* env, jclass clazz, jint fd, jint rows, jint cols);

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeWriteFd(
    JNIEnv* env, jclass clazz, jint fd, jbyteArray data, jint offset, jint length);

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeReadFd(
    JNIEnv* env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length);

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeCloseFd(
    JNIEnv* env, jclass clazz, jint fd);

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeWaitpid(
    JNIEnv* env, jclass clazz, jint pid, jboolean block);

// Display & Surface
JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeOnSurfaceCreated(
    JNIEnv* env, jclass clazz, jobject surface, jint width, jint height);

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeOnSurfaceChanged(
    JNIEnv* env, jclass clazz, jobject surface, jint width, jint height, jint format);

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeOnSurfaceDestroyed(
    JNIEnv* env, jclass clazz);

// GPU Detection
JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetGpuVendor(
    JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetGpuRenderer(
    JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetGpuVersion(
    JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeIsVulkanSupported(
    JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeIsHardwareAccelerated(
    JNIEnv* env, jclass clazz);

// Input Routing
JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendTouchEvent(
    JNIEnv* env, jclass clazz, jint action, jint pointerId, jfloat x, jfloat y, jfloat pressure);

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendMouseEvent(
    JNIEnv* env, jclass clazz, jint action, jint buttonState, jfloat x, jfloat y, jfloat scrollX, jfloat scrollY);

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendKeyEvent(
    JNIEnv* env, jclass clazz, jint keyCode, jboolean isDown, jint metaState, jint unicodeChar);

// Audio
JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeAudioStart(
    JNIEnv* env, jclass clazz, jint sampleRate, jint channels, jint bufferSizeFrames);

JNIEXPORT void JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeAudioStop(
    JNIEnv* env, jclass clazz);

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeAudioWritePcm(
    JNIEnv* env, jclass clazz, jbyteArray data, jint offset, jint length);

JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeAudioGetLatencyMs(
    JNIEnv* env, jclass clazz);

} // extern "C"
