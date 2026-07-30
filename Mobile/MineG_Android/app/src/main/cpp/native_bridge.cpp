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
Java_com_mineg_mobile_core_NativeBridge_nativeStartOperation(
    JNIEnv *env, jobject, jlong handle, jlong operation_id, jstring command) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || session->core == nullptr || command == nullptr || operation_id <= 0) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "startOperation");
    return nullptr;
  }
  const std::string input = from_jstring(env, command);
  mineg_buffer_t output{};
  const mineg_error_code_t code = mineg_core_start_operation(
      session->core, static_cast<uint64_t>(operation_id),
      reinterpret_cast<const uint8_t *>(input.data()), input.size(), &output);
  if (code != MINEG_OK) {
    throw_error(env, code, "startOperation");
    return nullptr;
  }
  const std::string result(reinterpret_cast<const char *>(output.data), output.size);
  mineg_buffer_free(&output);
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeResumeOperation(
    JNIEnv *env, jobject, jlong handle, jlong operation_id, jstring effect_result) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || session->core == nullptr || effect_result == nullptr ||
      operation_id <= 0) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "resumeOperation");
    return nullptr;
  }
  const std::string input = from_jstring(env, effect_result);
  mineg_buffer_t output{};
  const mineg_error_code_t code = mineg_core_resume_operation(
      session->core, static_cast<uint64_t>(operation_id),
      reinterpret_cast<const uint8_t *>(input.data()), input.size(), &output);
  if (code != MINEG_OK) {
    throw_error(env, code, "resumeOperation");
    return nullptr;
  }
  const std::string result(reinterpret_cast<const char *>(output.data), output.size);
  mineg_buffer_free(&output);
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeRecoverOperations(
    JNIEnv *env, jobject, jlong handle) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || session->core == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "recoverOperations");
    return nullptr;
  }
  mineg_buffer_t output{};
  const mineg_error_code_t code = mineg_core_recover_operations(session->core, &output);
  if (code != MINEG_OK) {
    throw_error(env, code, "recoverOperations");
    return nullptr;
  }
  const std::string result(reinterpret_cast<const char *>(output.data), output.size);
  mineg_buffer_free(&output);
  return env->NewStringUTF(result.c_str());
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

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeCreateUserKeyBundle(JNIEnv *env, jobject,
                                                                   jbyteArray password_array) {
  if (password_array == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "createUserKeyBundle");
    return nullptr;
  }
  const jsize password_size = env->GetArrayLength(password_array);
  std::vector<uint8_t> password(static_cast<size_t>(password_size));
  env->GetByteArrayRegion(password_array, 0, password_size,
                          reinterpret_cast<jbyte *>(password.data()));
  mineg_buffer_t public_key{};
  mineg_buffer_t encrypted_bundle{};
  mineg_buffer_t kdf{};
  const mineg_error_code_t code = mineg_core_create_user_key_bundle(
      password.data(), password.size(), &public_key, &encrypted_bundle, &kdf);
  sodium_memzero(password.data(), password.size());
  if (code != MINEG_OK) {
    throw_error(env, code, "createUserKeyBundle");
    return nullptr;
  }
  jclass byte_array_class = env->FindClass("[B");
  jobjectArray result = env->NewObjectArray(3, byte_array_class, nullptr);
  const auto set_buffer = [env, result](jsize index, const mineg_buffer_t &buffer) {
    jbyteArray value = env->NewByteArray(static_cast<jsize>(buffer.size));
    env->SetByteArrayRegion(value, 0, static_cast<jsize>(buffer.size),
                            reinterpret_cast<const jbyte *>(buffer.data));
    env->SetObjectArrayElement(result, index, value);
    env->DeleteLocalRef(value);
  };
  set_buffer(0, public_key);
  set_buffer(1, encrypted_bundle);
  set_buffer(2, kdf);
  mineg_buffer_free(&public_key);
  mineg_buffer_free(&encrypted_bundle);
  mineg_buffer_free(&kdf);
  env->DeleteLocalRef(byte_array_class);
  return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeUnlockUserKeyBundle(
    JNIEnv *env, jobject, jlong handle, jbyteArray password_array, jbyteArray public_key_array,
    jbyteArray encrypted_bundle_array, jbyteArray device_wrap_key_array) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || password_array == nullptr || public_key_array == nullptr ||
      encrypted_bundle_array == nullptr || device_wrap_key_array == nullptr ||
      env->GetArrayLength(public_key_array) != MINEG_KEY_BYTES ||
      env->GetArrayLength(device_wrap_key_array) != MINEG_KEY_BYTES) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "unlockUserKeyBundle");
    return nullptr;
  }
  std::vector<uint8_t> password(static_cast<size_t>(env->GetArrayLength(password_array)));
  std::vector<uint8_t> public_key(MINEG_KEY_BYTES);
  std::vector<uint8_t> encrypted_bundle(static_cast<size_t>(env->GetArrayLength(encrypted_bundle_array)));
  std::vector<uint8_t> device_wrap_key(MINEG_KEY_BYTES);
  env->GetByteArrayRegion(password_array, 0, static_cast<jsize>(password.size()),
                          reinterpret_cast<jbyte *>(password.data()));
  env->GetByteArrayRegion(public_key_array, 0, MINEG_KEY_BYTES,
                          reinterpret_cast<jbyte *>(public_key.data()));
  env->GetByteArrayRegion(encrypted_bundle_array, 0, static_cast<jsize>(encrypted_bundle.size()),
                          reinterpret_cast<jbyte *>(encrypted_bundle.data()));
  env->GetByteArrayRegion(device_wrap_key_array, 0, MINEG_KEY_BYTES,
                          reinterpret_cast<jbyte *>(device_wrap_key.data()));
  mineg_buffer_t blob{};
  const mineg_error_code_t code = mineg_core_unlock_user_key_bundle(
      session->core, password.data(), password.size(), public_key.data(), encrypted_bundle.data(),
      encrypted_bundle.size(), device_wrap_key.data(), &blob);
  sodium_memzero(password.data(), password.size());
  sodium_memzero(device_wrap_key.data(), device_wrap_key.size());
  if (code != MINEG_OK) {
    throw_error(env, code, "unlockUserKeyBundle");
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(blob.size));
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(blob.size),
                          reinterpret_cast<const jbyte *>(blob.data));
  mineg_buffer_free(&blob);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeRestoreUserKeyBundle(
    JNIEnv *env, jobject, jlong handle, jbyteArray public_key_array,
    jbyteArray device_wrap_key_array, jbyteArray unlock_blob_array) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || public_key_array == nullptr || device_wrap_key_array == nullptr ||
      unlock_blob_array == nullptr || env->GetArrayLength(public_key_array) != MINEG_KEY_BYTES ||
      env->GetArrayLength(device_wrap_key_array) != MINEG_KEY_BYTES) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "restoreUserKeyBundle");
    return;
  }
  std::vector<uint8_t> public_key(MINEG_KEY_BYTES);
  std::vector<uint8_t> device_wrap_key(MINEG_KEY_BYTES);
  std::vector<uint8_t> blob(static_cast<size_t>(env->GetArrayLength(unlock_blob_array)));
  env->GetByteArrayRegion(public_key_array, 0, MINEG_KEY_BYTES,
                          reinterpret_cast<jbyte *>(public_key.data()));
  env->GetByteArrayRegion(device_wrap_key_array, 0, MINEG_KEY_BYTES,
                          reinterpret_cast<jbyte *>(device_wrap_key.data()));
  env->GetByteArrayRegion(unlock_blob_array, 0, static_cast<jsize>(blob.size()),
                          reinterpret_cast<jbyte *>(blob.data()));
  const mineg_error_code_t code = mineg_core_restore_user_key_bundle(
      session->core, public_key.data(), device_wrap_key.data(), blob.data(), blob.size());
  sodium_memzero(device_wrap_key.data(), device_wrap_key.size());
  sodium_memzero(blob.data(), blob.size());
  if (code != MINEG_OK) throw_error(env, code, "restoreUserKeyBundle");
}

extern "C" JNIEXPORT void JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeUnlockFamilyKeyEnvelope(
    JNIEnv *env, jobject, jlong handle, jbyteArray envelope_array) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || envelope_array == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "unlockFamilyKeyEnvelope");
    return;
  }
  std::vector<uint8_t> envelope(static_cast<size_t>(env->GetArrayLength(envelope_array)));
  env->GetByteArrayRegion(envelope_array, 0, static_cast<jsize>(envelope.size()),
                          reinterpret_cast<jbyte *>(envelope.data()));
  const mineg_error_code_t code = mineg_core_unlock_family_key_envelope(
      session->core, envelope.data(), envelope.size());
  sodium_memzero(envelope.data(), envelope.size());
  if (code != MINEG_OK) throw_error(env, code, "unlockFamilyKeyEnvelope");
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeCreateFamilyKeyEnvelope(
    JNIEnv *env, jobject, jlong handle, jbyteArray recipient_array, jboolean bootstrap_if_needed) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || recipient_array == nullptr ||
      env->GetArrayLength(recipient_array) != MINEG_KEY_BYTES) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "createFamilyKeyEnvelope");
    return nullptr;
  }
  std::vector<uint8_t> recipient(MINEG_KEY_BYTES);
  env->GetByteArrayRegion(recipient_array, 0, MINEG_KEY_BYTES,
                          reinterpret_cast<jbyte *>(recipient.data()));
  mineg_buffer_t envelope{};
  const mineg_error_code_t code = mineg_core_create_family_key_envelope(
      session->core, recipient.data(), bootstrap_if_needed == JNI_TRUE ? 1 : 0, &envelope);
  if (code != MINEG_OK) {
    throw_error(env, code, "createFamilyKeyEnvelope");
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(envelope.size));
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(envelope.size),
                          reinterpret_cast<const jbyte *>(envelope.data));
  mineg_buffer_free(&envelope);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeLockKeys(JNIEnv *, jobject, jlong handle) {
  NativeSession *session = session_from(handle);
  if (session != nullptr) mineg_core_lock_keys(session->core);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeCreateMediaKeyEnvelope(
    JNIEnv *env, jobject, jlong handle, jstring media_id_value) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || media_id_value == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "createMediaKeyEnvelope");
    return nullptr;
  }
  const std::string media_id = from_jstring(env, media_id_value);
  mineg_buffer_t envelope{};
  const mineg_error_code_t code =
      mineg_core_create_media_key_envelope(session->core, media_id.c_str(), &envelope);
  if (code != MINEG_OK) {
    throw_error(env, code, "createMediaKeyEnvelope");
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(envelope.size));
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(envelope.size),
                          reinterpret_cast<const jbyte *>(envelope.data));
  mineg_buffer_free(&envelope);
  return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeComputeDedupeFingerprint(
    JNIEnv *env, jobject, jlong handle, jint descriptor, jstring media_type_value) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || media_type_value == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "computeDedupeFingerprint");
    return nullptr;
  }
  const std::string media_type = from_jstring(env, media_type_value);
  mineg_buffer_t fingerprint{};
  const mineg_error_code_t code = mineg_core_compute_dedupe_fingerprint(
      session->core, descriptor, media_type.c_str(), &fingerprint);
  if (code != MINEG_OK) {
    throw_error(env, code, "computeDedupeFingerprint");
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(fingerprint.size));
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(fingerprint.size),
                          reinterpret_cast<const jbyte *>(fingerprint.data));
  mineg_buffer_free(&fingerprint);
  return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeEncryptMediaResource(
    JNIEnv *env, jobject, jlong handle, jint descriptor, jstring ciphertext_path_value,
    jstring media_id_value, jstring resource_id_value, jstring resource_type_value,
    jbyteArray encrypted_media_key_value) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || ciphertext_path_value == nullptr || media_id_value == nullptr ||
      resource_id_value == nullptr || resource_type_value == nullptr ||
      encrypted_media_key_value == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "encryptMediaResource");
    return nullptr;
  }
  std::vector<uint8_t> encrypted_media_key(
      static_cast<size_t>(env->GetArrayLength(encrypted_media_key_value)));
  env->GetByteArrayRegion(encrypted_media_key_value, 0,
                          static_cast<jsize>(encrypted_media_key.size()),
                          reinterpret_cast<jbyte *>(encrypted_media_key.data()));
  const std::string ciphertext_path = from_jstring(env, ciphertext_path_value);
  const std::string media_id = from_jstring(env, media_id_value);
  const std::string resource_id = from_jstring(env, resource_id_value);
  const std::string resource_type = from_jstring(env, resource_type_value);
  mineg_buffer_t manifest{};
  const mineg_error_code_t code = mineg_core_encrypt_media_resource(
      session->core, descriptor, ciphertext_path.c_str(), media_id.c_str(), resource_id.c_str(),
      resource_type.c_str(), encrypted_media_key.data(), encrypted_media_key.size(), &manifest);
  sodium_memzero(encrypted_media_key.data(), encrypted_media_key.size());
  if (code != MINEG_OK) {
    throw_error(env, code, "encryptMediaResource");
    return nullptr;
  }
  std::string result(reinterpret_cast<const char *>(manifest.data), manifest.size);
  mineg_buffer_free(&manifest);
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mineg_mobile_core_NativeBridge_nativeEncryptMediaManifest(
    JNIEnv *env, jobject, jlong handle, jstring media_id_value, jbyteArray manifest_value,
    jbyteArray encrypted_media_key_value) {
  NativeSession *session = session_from(handle);
  if (session == nullptr || media_id_value == nullptr || manifest_value == nullptr ||
      encrypted_media_key_value == nullptr) {
    throw_error(env, MINEG_INVALID_ARGUMENT, "encryptMediaManifest");
    return nullptr;
  }
  std::vector<uint8_t> manifest(static_cast<size_t>(env->GetArrayLength(manifest_value)));
  std::vector<uint8_t> encrypted_media_key(
      static_cast<size_t>(env->GetArrayLength(encrypted_media_key_value)));
  env->GetByteArrayRegion(manifest_value, 0, static_cast<jsize>(manifest.size()),
                          reinterpret_cast<jbyte *>(manifest.data()));
  env->GetByteArrayRegion(encrypted_media_key_value, 0,
                          static_cast<jsize>(encrypted_media_key.size()),
                          reinterpret_cast<jbyte *>(encrypted_media_key.data()));
  const std::string media_id = from_jstring(env, media_id_value);
  mineg_buffer_t encrypted{};
  const mineg_error_code_t code = mineg_core_encrypt_media_manifest(
      session->core, media_id.c_str(), manifest.data(), manifest.size(), encrypted_media_key.data(),
      encrypted_media_key.size(), &encrypted);
  sodium_memzero(manifest.data(), manifest.size());
  sodium_memzero(encrypted_media_key.data(), encrypted_media_key.size());
  if (code != MINEG_OK) {
    throw_error(env, code, "encryptMediaManifest");
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(static_cast<jsize>(encrypted.size));
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(encrypted.size),
                          reinterpret_cast<const jbyte *>(encrypted.data));
  mineg_buffer_free(&encrypted);
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
