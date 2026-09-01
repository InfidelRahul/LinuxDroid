#pragma once
#include <cstddef>
#include <cstdint>
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_ABORT 2
typedef int jint; typedef long long jlong; typedef unsigned char jboolean;
typedef signed char jbyte; typedef int jsize;
typedef void* jobject; typedef jobject jclass; typedef jobject jstring;
typedef jobject jarray; typedef jarray jbyteArray; typedef jarray jintArray; typedef jarray jobjectArray;
typedef float jfloat; typedef double jdouble; typedef short jshort; typedef unsigned short jchar;
struct JNIEnv {
    jsize GetArrayLength(jarray);
    void* GetPrimitiveArrayCritical(jarray, jboolean*);
    void ReleasePrimitiveArrayCritical(jarray, void*, jint);
    jstring NewStringUTF(const char*);
    const char* GetStringUTFChars(jstring, jboolean*);
    void ReleaseStringUTFChars(jstring, const char*);
    jbyte* GetByteArrayElements(jbyteArray, jboolean*);
    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint);
    void SetByteArrayRegion(jbyteArray, jsize, jsize, const jbyte*);
    jbyteArray NewByteArray(jsize);
    void DeleteLocalRef(jobject);
    void SetIntArrayRegion(jintArray, jsize, jsize, const jint*);
    jobject GetObjectArrayElement(jobjectArray, jsize);
    void SetObjectArrayElement(jobjectArray, jsize, jobject);
    jint* GetIntArrayElements(jintArray, jboolean*);
    void ReleaseIntArrayElements(jintArray, jint*, jint);
};
