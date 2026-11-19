package com.mideyy7.retrieve.nativebridge

/**
 * Kotlin side of the JNI boundary. Each `external fun` is implemented in native/jni/.
 * Loading the library happens once, the first time this object is touched.
 */
object NativeBridge {
    init {
        System.loadLibrary("retrieve")
    }

    external fun nativeVersion(): String
}
