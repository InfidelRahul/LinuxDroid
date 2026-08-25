#pragma once

#include <jni.h>
#include <string>
#include <vector>

/**
 * LinuxDroid Native Bridge — C++ header
 *
 * This header defines the C++ side of the JNI interface.
 * All JNI entry points are declared here.
 *
 * Ownership rules:
 * - JNI parameters (jobject, jstring, etc.) are owned by the JVM.
 * - Native resources created here are tracked in NativeBridgeState.
 * - All native resources MUST be released before the JVM frees them.
 */

// Version of the native bridge API
#define LINUXDROID_BRIDGE_VERSION 1

/**
 * JNI function declarations for the bridge.
 * These match the Kotlin native declarations in NativeBridge.kt.
 */
extern "C" {

/**
 * Returns the native bridge version.
 */
JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetBridgeVersion(
    JNIEnv* env, jclass clazz);

/**
 * Checks if a file path is executable.
 * Returns JNI_TRUE if the file exists and is executable.
 */
JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeIsExecutable(
    JNIEnv* env, jclass clazz, jstring path);

/**
 * Sets a file as executable (chmod +x).
 * Returns 0 on success, errno on failure.
 */
JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSetExecutable(
    JNIEnv* env, jclass clazz, jstring path);

/**
 * Returns the Android ABI string (e.g. "arm64-v8a").
 */
JNIEXPORT jstring JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetAbi(
    JNIEnv* env, jclass clazz);

/**
 * Sends a signal to a process by PID.
 * Returns 0 on success, errno on failure.
 */
JNIEXPORT jint JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeSendSignal(
    JNIEnv* env, jclass clazz, jint pid, jint signal);

/**
 * Returns the available memory in bytes from /proc/meminfo.
 */
JNIEXPORT jlong JNICALL
Java_com_linuxdroid_native_1bridge_NativeBridge_nativeGetAvailableMemoryBytes(
    JNIEnv* env, jclass clazz);

} // extern "C"
