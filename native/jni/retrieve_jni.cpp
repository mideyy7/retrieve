// JNI glue: the only file that knows about Java types. Everything else in native/ is plain C++
// so it can be built and tested on the desktop.
//
// Function names follow the JNI convention Java_<package>_<class>_<method>, with '.' replaced
// by '_'. If the Kotlin class or package is renamed (or R8 renames it in a release build),
// the lookup fails at runtime with UnsatisfiedLinkError. That is why proguard-rules.pro keeps
// the nativebridge package.
//
// Rules followed throughout:
//  - No C++ exception may cross into Java: every entry point catches and rethrows as a Java
//    exception.
//  - Local references created in loops are deleted each iteration (Android caps live local refs
//    at a few hundred per native call, and the corpus has more chunks than that).
//  - Strings are converted from UTF-16 to *standard* UTF-8 here. JNI's GetStringUTFChars returns
//    "modified UTF-8", which encodes supplementary characters (e.g. math italic 𝑥 from PDFs) as
//    surrogate pairs; the tokenizer would then see different bytes than the CLI does.

#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "core/version.h"
#include "index/bm25.h"
#include "index/builder.h"
#include "index/index.h"

namespace {

jclass gHitsClass = nullptr;     // global ref to NativeHits
jmethodID gHitsCtor = nullptr;   // NativeHits(String[], float[])
jclass gStringClass = nullptr;   // global ref to java.lang.String

void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// Java String (UTF-16) -> standard UTF-8. Unpaired surrogates become U+FFFD.
std::string toUtf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const jsize len = env->GetStringLength(s);
    std::vector<jchar> units(static_cast<size_t>(len));
    env->GetStringRegion(s, 0, len, units.data());
    std::string out;
    out.reserve(units.size());
    for (size_t i = 0; i < units.size(); ++i) {
        uint32_t u = units[i];
        if (u >= 0xD800 && u <= 0xDBFF && i + 1 < units.size() && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
            appendUtf8(out, 0x10000 + ((u - 0xD800) << 10) + (units[i + 1] - 0xDC00u));
            ++i;
        } else if (u >= 0xD800 && u <= 0xDFFF) {
            appendUtf8(out, 0xFFFD);
        } else {
            appendUtf8(out, u);
        }
    }
    return out;
}

std::string elementUtf8(JNIEnv* env, jobjectArray array, jsize i) {
    auto s = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    std::string out = toUtf8(env, s);
    env->DeleteLocalRef(s);
    return out;
}

void throwJava(JNIEnv* env, const char* cls, const std::string& msg) {
    if (env->ExceptionCheck()) return;  // keep the first pending exception
    jclass c = env->FindClass(cls);
    if (c) env->ThrowNew(c, msg.c_str());
}

retrieve::Index* fromHandle(JNIEnv* env, jlong handle) {
    auto* index = reinterpret_cast<retrieve::Index*>(handle);
    if (!index) throwJava(env, "java/lang/IllegalStateException", "index is closed");
    return index;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    // Cache class and constructor lookups once. FindClass from a native worker thread would use
    // the system class loader and fail to find app classes, so it must happen here.
    jclass hits = env->FindClass("com/mideyy7/retrieve/nativebridge/NativeHits");
    jclass str = env->FindClass("java/lang/String");
    if (!hits || !str) return JNI_ERR;
    gHitsClass = static_cast<jclass>(env->NewGlobalRef(hits));
    gStringClass = static_cast<jclass>(env->NewGlobalRef(str));
    env->DeleteLocalRef(hits);
    env->DeleteLocalRef(str);
    gHitsCtor = env->GetMethodID(gHitsClass, "<init>", "([Ljava/lang/String;[F)V");
    return gHitsCtor ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mideyy7_retrieve_nativebridge_NativeBridge_nativeVersion(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF(retrieve::version().c_str());
}

// Returns null on success, or an error message.
extern "C" JNIEXPORT jstring JNICALL Java_com_mideyy7_retrieve_nativebridge_NativeBridge_buildIndex(
    JNIEnv* env, jobject, jstring jpath, jobjectArray keys, jobjectArray modules, jintArray weeks, jintArray types,
    jintArray pages, jlongArray startMs, jobjectArray texts) {
    try {
        const jsize n = env->GetArrayLength(keys);
        if (env->GetArrayLength(modules) != n || env->GetArrayLength(weeks) != n || env->GetArrayLength(types) != n ||
            env->GetArrayLength(pages) != n || env->GetArrayLength(startMs) != n || env->GetArrayLength(texts) != n) {
            return env->NewStringUTF("array lengths differ");
        }
        std::vector<jint> w(static_cast<size_t>(n)), t(static_cast<size_t>(n)), p(static_cast<size_t>(n));
        std::vector<jlong> s(static_cast<size_t>(n));
        env->GetIntArrayRegion(weeks, 0, n, w.data());
        env->GetIntArrayRegion(types, 0, n, t.data());
        env->GetIntArrayRegion(pages, 0, n, p.data());
        env->GetLongArrayRegion(startMs, 0, n, s.data());

        retrieve::IndexBuilder builder;
        for (jsize i = 0; i < n; ++i) {
            const auto u = static_cast<size_t>(i);
            retrieve::DocInput d;
            d.stableKey = elementUtf8(env, keys, i);
            d.module = elementUtf8(env, modules, i);
            d.week = static_cast<uint16_t>(w[u]);
            d.type = static_cast<uint8_t>(t[u]);
            d.page = p[u];
            d.startMs = s[u];
            d.text = elementUtf8(env, texts, i);
            builder.add(std::move(d));
        }
        std::string err;
        if (!builder.writeTo(toUtf8(env, jpath), &err)) return env->NewStringUTF(err.c_str());
        return nullptr;
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/RuntimeException", std::string("native buildIndex: ") + e.what());
        return nullptr;
    }
}

// Returns an opaque handle (a pointer) that must be passed to closeIndex exactly once.
extern "C" JNIEXPORT jlong JNICALL Java_com_mideyy7_retrieve_nativebridge_NativeBridge_openIndex(JNIEnv* env, jobject,
                                                                                                    jstring jpath) {
    try {
        std::string err;
        auto index = retrieve::Index::open(toUtf8(env, jpath), &err);
        if (!index) {
            throwJava(env, "java/io/IOException", err);
            return 0;
        }
        return reinterpret_cast<jlong>(index.release());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/RuntimeException", std::string("native openIndex: ") + e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_mideyy7_retrieve_nativebridge_NativeBridge_closeIndex(JNIEnv*, jobject,
                                                                                                   jlong handle) {
    delete reinterpret_cast<retrieve::Index*>(handle);
}

// [numDocs, numTerms, fileSizeBytes]
extern "C" JNIEXPORT jlongArray JNICALL Java_com_mideyy7_retrieve_nativebridge_NativeBridge_indexStats(JNIEnv* env,
                                                                                                         jobject,
                                                                                                         jlong handle) {
    auto* index = fromHandle(env, handle);
    if (!index) return nullptr;
    const jlong values[3] = {index->numDocs(), index->numTerms(), static_cast<jlong>(index->fileSize())};
    jlongArray out = env->NewLongArray(3);
    if (out) env->SetLongArrayRegion(out, 0, 3, values);
    return out;
}

extern "C" JNIEXPORT jobject JNICALL Java_com_mideyy7_retrieve_nativebridge_NativeBridge_search(JNIEnv* env, jobject,
                                                                                                  jlong handle,
                                                                                                  jstring jquery,
                                                                                                  jint k) {
    try {
        auto* index = fromHandle(env, handle);
        if (!index) return nullptr;
        const auto hits = retrieve::Searcher(*index).search(toUtf8(env, jquery), static_cast<size_t>(k < 0 ? 0 : k));

        const auto n = static_cast<jsize>(hits.size());
        jobjectArray keys = env->NewObjectArray(n, gStringClass, nullptr);
        jfloatArray scores = env->NewFloatArray(n);
        if (!keys || !scores) return nullptr;  // OutOfMemoryError already pending
        std::vector<jfloat> s(hits.size());
        for (jsize i = 0; i < n; ++i) {
            const auto u = static_cast<size_t>(i);
            const std::string key(index->doc(hits[u].doc).stableKey);  // stable keys are ASCII
            jstring jkey = env->NewStringUTF(key.c_str());
            env->SetObjectArrayElement(keys, i, jkey);
            env->DeleteLocalRef(jkey);
            s[u] = static_cast<jfloat>(hits[u].score);
        }
        env->SetFloatArrayRegion(scores, 0, n, s.data());
        jobject result = env->NewObject(gHitsClass, gHitsCtor, keys, scores);
        env->DeleteLocalRef(keys);
        env->DeleteLocalRef(scores);
        return result;
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/RuntimeException", std::string("native search: ") + e.what());
        return nullptr;
    }
}
