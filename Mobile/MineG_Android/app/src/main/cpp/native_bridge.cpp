#include <jni.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "mineg/mineg_core.h"
#include "sodium_compat.h"

namespace {

struct CallbackContext {
  JavaVM *vm = nullptr;
  jobject listener = nullptr;
};

struct NativeSession {
  mineg_core_t *core = nullptr;
  JavaVM *vm = nullptr;
  std::mutex mutex;
  std::unordered_map<uint64_t, std::unique_ptr<CallbackContext>> callbacks;
};

NativeSession *session_from(jlong handle) { return reinterpret_cast<NativeSession *>(handle); }

void throw_error(JNIEnv *env, mineg_error_code_t code, const char *operation) {
  jclass exception = env->FindClass("java/lang/IllegalStateException");
  const std::string message = std::string(operation) + " failed with MineG error " + std::to_string(code);
  env->ThrowNew(exception, message.c_str());
}

std::string from_jstring(JNIEnv *env, jstring value) {
  if (value == nullptr) return {};
  const char *characters = env->GetStringUTFChars(value, nullptr);
  if (characters == nullptr) return {};
  std::string result(characters);
  env->ReleaseStringUTFChars(value, characters);
  return result;
}

void on_event(const uint8_t *bytes, size_t size, void *user_data) {
  auto *context = static_cast<CallbackContext *>(user_data);
  if (context == nullptr || context->listener == nullptr) return;
  JNIEnv *env = nullptr;
  bool attached = false;
  if (context->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    if (context->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    attached = true;
  }
  jclass listener_class = env->GetObjectClass(context->listener);
  jmethodID method = env->GetMethodID(listener_class, "onEvent", "(Ljava/lang/String;)V");
  std::string event(reinterpret_cast<const char *>(bytes), size);
  jstring event_string = env->NewStringUTF(event.c_str());
  env->CallVoidMethod(context->listener, method, event_string);
  env->DeleteLocalRef(event_string);
  env->DeleteLocalRef(listener_class);
  if (attached) context->vm->DetachCurrentThread();
}

jstring call_json(JNIEnv *env, NativeSession *session, uint64_t operation_id, jstring json,
                  bool execute) {
  if (session == nullptr || session->core == nullptr || json == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, execute ? "execute" : "query");
    return nullptr;
  }
  const std::string input = from_jstring(env, json);
  mineg_buffer_t output{};
  const mineg_error_code_t code = execute
      ? mineg_core_execute(session->core, operation_id,
                           reinterpret_cast<const uint8_t *>(input.data()), input.size(), &output)
      : mineg_core_query(session->core, reinterpret_cast<const uint8_t *>(input.data()), input.size(),
                         &output);
  if (code != MINEG_OK) {
    throw_error(env, code, execute ? "execute" : "query");
    return nullptr;
  }
  std::string result(reinterpret_cast<const char *>(output.data), output.size);
  mineg_buffer_free(&output);
  return env->NewStringUTF(result.c_str());
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeCreate(JNIEnv *env, jobject, jstring database_path) {
  const std::string path = from_jstring(env, database_path);
  auto session = std::make_unique<NativeSession>();
  env->GetJavaVM(&session->vm);
  const mineg_error_code_t code = mineg_core_create(path.c_str(), &session->core);
  if (code != MINEG_OK) {
    throw_error(env, code, "initialize");
    return 0;
  }
  return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeExecute(JNIEnv *env, jobject, jlong handle,
                                                       jlong operation_id, jstring command) {
  return call_json(env, session_from(handle), static_cast<uint64_t>(operation_id), command, true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeQuery(JNIEnv *env, jobject, jlong handle,
                                                     jstring query) {
  return call_json(env, session_from(handle), 0, query, false);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeSubscribe(JNIEnv *env, jobject, jlong handle,
                                                         jobject listener) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || listener == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "subscribe");
    return 0;
  }
  auto callback = std::make_unique<CallbackContext>();
  callback->vm = session->vm;
  callback->listener = env->NewGlobalRef(listener);
  uint64_t token = 0;
  const mineg_error_code_t code = mineg_core_subscribe(session->core, on_event, callback.get(), &token);
  if (code != MINEG_OK) {
    env->DeleteGlobalRef(callback->listener);
    throw_error(env, code, "subscribe");
    return 0;
  }
  std::lock_guard<std::mutex> lock(session->mutex);
  session->callbacks.emplace(token, std::move(callback));
  return static_cast<jlong>(token);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeUnsubscribe(JNIEnv *env, jobject, jlong handle,
                                                           jlong subscription_token) {
  NativeSession *session = session_from(handle);
  if (session == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "unsubscribe");
    return;
  }
  const auto token = static_cast<uint64_t>(subscription_token);
  const mineg_error_code_t code = mineg_core_unsubscribe(session->core, token);
  if (code != MINEG_OK) {
    throw_error(env, code, "unsubscribe");
    return;
  }
  std::lock_guard<std::mutex> lock(session->mutex);
  auto found = session->callbacks.find(token);
  if (found != session->callbacks.end()) {
    env->DeleteGlobalRef(found->second->listener);
    session->callbacks.erase(found);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeCancel(JNIEnv *env, jobject, jlong handle,
                                                      jlong operation_id) {
  NativeSession *session = session_from(handle);
  const mineg_error_code_t code = session == nullptr
      ? MINEG_INVALID_ARGUMENT
      : mineg_core_cancel(session->core, static_cast<uint64_t>(operation_id));
  if (code != MINEG_OK) throw_error(env, code, "cancel");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeRandomKey(JNIEnv *env, jobject) {
  mineg_buffer_t key{};
  const mineg_error_code_t code = mineg_core_random_key(&key);
  if (code != MINEG_OK) {
    throw_error(env, code, "randomKey");
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(key.size));
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(key.size),
                          reinterpret_cast<const jbyte *>(key.data));
  mineg_buffer_free(&key);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeEncryptFd(JNIEnv *env, jobject, jlong handle,
                                                         jint descriptor, jstring output_path,
                                                         jbyteArray key_array) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || key_array == nullptr || env->GetArrayLength(key_array) != MINEG_KEY_BYTES) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "encryptResource");
    return;
  }
  std::vector<uint8_t> key(MINEG_KEY_BYTES);
  env->GetByteArrayRegion(key_array, 0, MINEG_KEY_BYTES, reinterpret_cast<jbyte *>(key.data()));
  const std::string path = from_jstring(env, output_path);
  const mineg_error_code_t code = mineg_core_encrypt_fd(session->core, descriptor, path.c_str(), key.data());
  sodium_memzero(key.data(), key.size());
  if (code != MINEG_OK) throw_error(env, code, "encryptResource");
}

extern "C" JNIEXPORT void JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeClose(JNIEnv *env, jobject, jlong handle) {
  std::unique_ptr<NativeSession> session(session_from(handle));
  if (!session) return;
  mineg_core_close(session->core);
  session->core = nullptr;
  std::lock_guard<std::mutex> lock(session->mutex);
  for (auto &entry : session->callbacks) env->DeleteGlobalRef(entry.second->listener);
  session->callbacks.clear();
}
