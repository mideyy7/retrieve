// JNI glue: the only file that knows about Java types. Everything else in native/ is plain C++
// so it can be built and tested on the desktop.
//
// Function names follow the JNI convention Java_<package>_<class>_<method>, with '.' replaced
// by '_'. If the Kotlin class or package is renamed (or R8 renames it in a release build),
// the lookup fails at runtime with UnsatisfiedLinkError. That is why proguard-rules.pro keeps
// the nativebridge package.

#include <jni.h>

#include "core/version.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_mideyy7_retrieve_nativebridge_NativeBridge_nativeVersion(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF(retrieve::version().c_str());
}
