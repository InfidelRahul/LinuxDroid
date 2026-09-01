#pragma once
#include <android/native_window.h>
#include <jni.h>
extern "C" ANativeWindow* ANativeWindow_fromSurface(JNIEnv*, jobject);
