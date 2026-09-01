#pragma once
enum { ANDROID_LOG_INFO=4, ANDROID_LOG_WARN=5, ANDROID_LOG_ERROR=6 };
extern "C" int __android_log_print(int, const char*, const char*, ...);
