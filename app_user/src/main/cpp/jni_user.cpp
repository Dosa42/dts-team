// libuser_core.so JNI: sadece init + connect. Kotlin'e geri cagri YOK.
#include <jni.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>

#include "client_session.h"

namespace {

std::mutex g_mu;
std::unique_ptr<client::ClientSession> g_session;

void logToAndroid(const std::string& s) {
    __android_log_print(ANDROID_LOG_INFO, "user_core", "%s", s.c_str());
}

std::string toStd(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    if (!c) return {};
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_hostuser_UserCore_nativeInit(JNIEnv* env, jclass, jstring caFilePath) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_session) return;   // surec basina bir kez
    g_session = std::make_unique<client::ClientSession>(toStd(env, caFilePath), logToAndroid);
}

JNIEXPORT void JNICALL
Java_com_example_hostuser_UserCore_nativeConnect(JNIEnv* env, jclass, jstring url) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_session) return;
    g_session->connect(toStd(env, url));
}

}  // extern "C"
