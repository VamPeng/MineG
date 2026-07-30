#include "mineg/mineg_core.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <new>
#include <string>

#include "core.h"
#include "sodium_compat.h"

struct mineg_core {
  explicit mineg_core(const std::string &path) : implementation(path) {}
  mineg::Core implementation;
};

namespace {

mineg_error_code_t copy_result(const std::string &result, mineg_buffer_t *output) {
  if (output == nullptr) return MINEG_INVALID_ARGUMENT;
  output->data = nullptr;
  output->size = 0;
  if (result.empty()) return MINEG_OK;
  output->data = new (std::nothrow) uint8_t[result.size()];
  if (output->data == nullptr) return MINEG_INTERNAL_ERROR;
  std::memcpy(output->data, result.data(), result.size());
  output->size = result.size();
  return MINEG_OK;
}

mineg_error_code_t copy_bytes(const uint8_t *bytes, size_t size, mineg_buffer_t *output) {
  if (bytes == nullptr || size == 0 || output == nullptr) return MINEG_INVALID_ARGUMENT;
  output->data = new (std::nothrow) uint8_t[size];
  if (output->data == nullptr) return MINEG_INTERNAL_ERROR;
  std::memcpy(output->data, bytes, size);
  output->size = size;
  return MINEG_OK;
}

std::string borrowed_string(const uint8_t *bytes, size_t size) {
  if (bytes == nullptr || size == 0) return {};
  return {reinterpret_cast<const char *>(bytes), size};
}

}  // namespace

uint32_t mineg_abi_version(void) { return MINEG_ABI_VERSION; }

mineg_error_code_t mineg_core_create(const char *database_path, mineg_core_t **out_core) {
  if (database_path == nullptr || out_core == nullptr) return MINEG_INVALID_ARGUMENT;
  *out_core = nullptr;
  if (sodium_init() < 0) return MINEG_CRYPTO_ERROR;
  try {
    *out_core = new mineg_core(database_path);
    return MINEG_OK;
  } catch (const std::invalid_argument &) {
    return MINEG_INVALID_ARGUMENT;
  } catch (...) {
    return MINEG_DATABASE_ERROR;
  }
}

mineg_error_code_t mineg_core_execute(mineg_core_t *core, uint64_t operation_id,
                                      const uint8_t *command_json, size_t command_size,
                                      mineg_buffer_t *out_result_json) {
  if (core == nullptr || command_json == nullptr || command_size == 0 || out_result_json == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    std::string result;
    const mineg_error_code_t code = core->implementation.execute(
        operation_id, borrowed_string(command_json, command_size), result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_result_json);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_start_operation(
    mineg_core_t *core, uint64_t operation_id, const uint8_t *command_json, size_t command_size,
    mineg_buffer_t *out_operation_step_json) {
  if (core == nullptr || operation_id == 0 || command_json == nullptr || command_size == 0 ||
      out_operation_step_json == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    std::string result;
    const mineg_error_code_t code = core->implementation.start_operation(
        operation_id, borrowed_string(command_json, command_size), result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_operation_step_json);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_resume_operation(
    mineg_core_t *core, uint64_t operation_id, const uint8_t *effect_result_json,
    size_t effect_result_size, mineg_buffer_t *out_operation_step_json) {
  if (core == nullptr || operation_id == 0 || effect_result_json == nullptr ||
      effect_result_size == 0 || out_operation_step_json == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    std::string result;
    const mineg_error_code_t code = core->implementation.resume_operation(
        operation_id, borrowed_string(effect_result_json, effect_result_size), result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_operation_step_json);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_recover_operations(mineg_core_t *core,
                                                  mineg_buffer_t *out_operations_json) {
  if (core == nullptr || out_operations_json == nullptr) return MINEG_INVALID_ARGUMENT;
  try {
    std::string result;
    const mineg_error_code_t code = core->implementation.recover_operations(result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_operations_json);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_query(mineg_core_t *core, const uint8_t *query_json,
                                    size_t query_size, mineg_buffer_t *out_result_json) {
  if (core == nullptr || query_json == nullptr || query_size == 0 || out_result_json == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    std::string result;
    const mineg_error_code_t code = core->implementation.query(
        borrowed_string(query_json, query_size), result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_result_json);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_subscribe(mineg_core_t *core, mineg_event_callback_t callback,
                                        void *user_data, uint64_t *out_subscription_token) {
  if (core == nullptr || callback == nullptr || out_subscription_token == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    return core->implementation.subscribe(
        [callback, user_data](const std::string &event) {
          callback(reinterpret_cast<const uint8_t *>(event.data()), event.size(), user_data);
        },
        *out_subscription_token);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_unsubscribe(mineg_core_t *core, uint64_t subscription_token) {
  if (core == nullptr || subscription_token == 0) return MINEG_INVALID_ARGUMENT;
  try {
    return core->implementation.unsubscribe(subscription_token);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_cancel(mineg_core_t *core, uint64_t operation_id) {
  if (core == nullptr) return MINEG_INVALID_ARGUMENT;
  try {
    return core->implementation.cancel(operation_id);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_random_key(mineg_buffer_t *out_key) {
  if (out_key == nullptr) return MINEG_INVALID_ARGUMENT;
  if (sodium_init() < 0) return MINEG_CRYPTO_ERROR;
  out_key->data = new (std::nothrow) uint8_t[MINEG_KEY_BYTES];
  if (out_key->data == nullptr) return MINEG_INTERNAL_ERROR;
  out_key->size = MINEG_KEY_BYTES;
  randombytes_buf(out_key->data, out_key->size);
  return MINEG_OK;
}

mineg_error_code_t mineg_core_create_user_key_bundle(
    const uint8_t *password, size_t password_size, mineg_buffer_t *out_public_key,
    mineg_buffer_t *out_encrypted_bundle, mineg_buffer_t *out_kdf_parameters_json) {
  if (password == nullptr || password_size < 8 || password_size > 256 || out_public_key == nullptr ||
      out_encrypted_bundle == nullptr || out_kdf_parameters_json == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  *out_public_key = {};
  *out_encrypted_bundle = {};
  *out_kdf_parameters_json = {};
  if (sodium_init() < 0) return MINEG_CRYPTO_ERROR;

  constexpr unsigned long long kOpsLimit = 2;
  constexpr size_t kMemoryLimit = 64U * 1024U * 1024U;
  constexpr std::array<uint8_t, 8> kMagic = {'M', 'K', 'B', '0', '1', 0, 0, 0};
  std::array<uint8_t, crypto_box_PUBLICKEYBYTES> public_key{};
  std::array<uint8_t, crypto_box_SECRETKEYBYTES> private_key{};
  std::array<uint8_t, MINEG_KEY_BYTES> user_master_key{};
  std::array<uint8_t, crypto_pwhash_SALTBYTES> salt{};
  std::array<uint8_t, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES> nonce{};
  std::array<uint8_t, MINEG_KEY_BYTES> password_key{};
  std::array<uint8_t, crypto_box_SECRETKEYBYTES + MINEG_KEY_BYTES> plaintext{};
  std::array<uint8_t, kMagic.size() + salt.size() + nonce.size() + plaintext.size() +
                          crypto_aead_xchacha20poly1305_ietf_ABYTES>
      packet{};
  mineg_error_code_t result = MINEG_CRYPTO_ERROR;
  do {
    if (crypto_box_keypair(public_key.data(), private_key.data()) != 0) break;
    randombytes_buf(user_master_key.data(), user_master_key.size());
    randombytes_buf(salt.data(), salt.size());
    randombytes_buf(nonce.data(), nonce.size());
    if (crypto_pwhash(password_key.data(), password_key.size(),
                      reinterpret_cast<const char *>(password), password_size, salt.data(),
                      kOpsLimit, kMemoryLimit, crypto_pwhash_ALG_ARGON2ID13) != 0) {
      break;
    }
    std::copy(private_key.begin(), private_key.end(), plaintext.begin());
    std::copy(user_master_key.begin(), user_master_key.end(), plaintext.begin() + private_key.size());
    std::copy(kMagic.begin(), kMagic.end(), packet.begin());
    std::copy(salt.begin(), salt.end(), packet.begin() + kMagic.size());
    std::copy(nonce.begin(), nonce.end(), packet.begin() + kMagic.size() + salt.size());
    unsigned long long encrypted_size = 0;
    uint8_t *ciphertext = packet.data() + kMagic.size() + salt.size() + nonce.size();
    if (crypto_aead_xchacha20poly1305_ietf_encrypt(
            ciphertext, &encrypted_size, plaintext.data(), plaintext.size(), kMagic.data(),
            kMagic.size(), nullptr, nonce.data(), password_key.data()) != 0 ||
        encrypted_size != plaintext.size() + crypto_aead_xchacha20poly1305_ietf_ABYTES) {
      break;
    }
    const std::string kdf =
        "{\"algorithm\":\"ARGON2ID13\",\"opslimit\":2,\"memlimit_bytes\":67108864,"
        "\"salt_location\":\"MKB01\"}";
    if (copy_bytes(public_key.data(), public_key.size(), out_public_key) != MINEG_OK ||
        copy_bytes(packet.data(), packet.size(), out_encrypted_bundle) != MINEG_OK ||
        copy_bytes(reinterpret_cast<const uint8_t *>(kdf.data()), kdf.size(),
                   out_kdf_parameters_json) != MINEG_OK) {
      mineg_buffer_free(out_public_key);
      mineg_buffer_free(out_encrypted_bundle);
      mineg_buffer_free(out_kdf_parameters_json);
      result = MINEG_INTERNAL_ERROR;
      break;
    }
    result = MINEG_OK;
  } while (false);
  sodium_memzero(private_key.data(), private_key.size());
  sodium_memzero(user_master_key.data(), user_master_key.size());
  sodium_memzero(password_key.data(), password_key.size());
  sodium_memzero(plaintext.data(), plaintext.size());
  return result;
}

mineg_error_code_t mineg_core_unlock_user_key_bundle(
    mineg_core_t *core, const uint8_t *password, size_t password_size,
    const uint8_t public_key[MINEG_KEY_BYTES], const uint8_t *encrypted_bundle,
    size_t encrypted_bundle_size, const uint8_t device_wrap_key[MINEG_KEY_BYTES],
    mineg_buffer_t *out_device_unlock_blob) {
  if (core == nullptr || password == nullptr || public_key == nullptr || encrypted_bundle == nullptr ||
      device_wrap_key == nullptr || out_device_unlock_blob == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  *out_device_unlock_blob = {};
  try {
    std::string blob;
    const mineg_error_code_t code = core->implementation.unlock_user_key_bundle(
        password, password_size, public_key, encrypted_bundle, encrypted_bundle_size,
        device_wrap_key, blob);
    if (code != MINEG_OK) return code;
    return copy_bytes(reinterpret_cast<const uint8_t *>(blob.data()), blob.size(),
                      out_device_unlock_blob);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_restore_user_key_bundle(
    mineg_core_t *core, const uint8_t public_key[MINEG_KEY_BYTES],
    const uint8_t device_wrap_key[MINEG_KEY_BYTES], const uint8_t *device_unlock_blob,
    size_t device_unlock_blob_size) {
  if (core == nullptr || public_key == nullptr || device_wrap_key == nullptr ||
      device_unlock_blob == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    return core->implementation.restore_user_key_bundle(
        public_key, device_wrap_key, device_unlock_blob, device_unlock_blob_size);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_unlock_family_key_envelope(
    mineg_core_t *core, const uint8_t *encrypted_envelope, size_t encrypted_envelope_size) {
  if (core == nullptr || encrypted_envelope == nullptr) return MINEG_INVALID_ARGUMENT;
  try {
    return core->implementation.unlock_family_key_envelope(encrypted_envelope,
                                                            encrypted_envelope_size);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_create_family_key_envelope(
    mineg_core_t *core, const uint8_t recipient_public_key[MINEG_KEY_BYTES],
    uint8_t bootstrap_if_needed, mineg_buffer_t *out_encrypted_envelope) {
  if (core == nullptr || recipient_public_key == nullptr || out_encrypted_envelope == nullptr ||
      bootstrap_if_needed > 1) {
    return MINEG_INVALID_ARGUMENT;
  }
  *out_encrypted_envelope = {};
  try {
    std::string envelope;
    const mineg_error_code_t code = core->implementation.create_family_key_envelope(
        recipient_public_key, bootstrap_if_needed == 1, envelope);
    if (code != MINEG_OK) return code;
    return copy_bytes(reinterpret_cast<const uint8_t *>(envelope.data()), envelope.size(),
                      out_encrypted_envelope);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

void mineg_core_lock_keys(mineg_core_t *core) {
  if (core == nullptr) return;
  try {
    core->implementation.lock_keys();
  } catch (...) {
  }
}

mineg_error_code_t mineg_core_create_media_key_envelope(
    mineg_core_t *core, const char *media_id, mineg_buffer_t *out_encrypted_media_key) {
  if (core == nullptr || media_id == nullptr || out_encrypted_media_key == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    std::string result;
    const mineg_error_code_t code = core->implementation.create_media_key_envelope(media_id, result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_encrypted_media_key);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_compute_dedupe_fingerprint(
    mineg_core_t *core, int32_t input_fd, const char *media_type,
    mineg_buffer_t *out_fingerprint) {
  if (core == nullptr || media_type == nullptr || out_fingerprint == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    std::string result;
    const mineg_error_code_t code =
        core->implementation.compute_dedupe_fingerprint(input_fd, media_type, result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_fingerprint);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_encrypt_media_resource(
    mineg_core_t *core, int32_t input_fd, const char *ciphertext_path, const char *media_id,
    const char *resource_id, const char *resource_type, const uint8_t *encrypted_media_key,
    size_t encrypted_media_key_size, mineg_buffer_t *out_resource_manifest_json) {
  if (core == nullptr || ciphertext_path == nullptr || media_id == nullptr ||
      resource_id == nullptr || resource_type == nullptr || encrypted_media_key == nullptr ||
      out_resource_manifest_json == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    std::string result;
    const mineg_error_code_t code = core->implementation.encrypt_media_resource(
        input_fd, ciphertext_path, media_id, resource_id, resource_type, encrypted_media_key,
        encrypted_media_key_size, result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_resource_manifest_json);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_encrypt_media_manifest(
    mineg_core_t *core, const char *media_id, const uint8_t *manifest_json,
    size_t manifest_json_size, const uint8_t *encrypted_media_key,
    size_t encrypted_media_key_size, mineg_buffer_t *out_encrypted_manifest) {
  if (core == nullptr || media_id == nullptr || manifest_json == nullptr ||
      encrypted_media_key == nullptr || out_encrypted_manifest == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    std::string result;
    const mineg_error_code_t code = core->implementation.encrypt_media_manifest(
        media_id, manifest_json, manifest_json_size, encrypted_media_key,
        encrypted_media_key_size, result);
    if (code != MINEG_OK) return code;
    return copy_result(result, out_encrypted_manifest);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_decrypt_media_resource(
    mineg_core_t *core, const char *ciphertext_path, const char *plaintext_path,
    const char *media_id, const char *resource_id, const char *resource_type,
    uint64_t plaintext_size, const uint8_t nonce_prefix[MINEG_MEDIA_NONCE_PREFIX_BYTES],
    const uint8_t *encrypted_media_key, size_t encrypted_media_key_size) {
  if (core == nullptr || ciphertext_path == nullptr || plaintext_path == nullptr ||
      media_id == nullptr || resource_id == nullptr || resource_type == nullptr ||
      nonce_prefix == nullptr || encrypted_media_key == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    return core->implementation.decrypt_media_resource(
        ciphertext_path, plaintext_path, media_id, resource_id, resource_type, plaintext_size,
        nonce_prefix, encrypted_media_key, encrypted_media_key_size);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_encrypt_fd(mineg_core_t *core, int32_t input_fd,
                                         const char *ciphertext_path,
                                         const uint8_t key[MINEG_KEY_BYTES]) {
  if (core == nullptr || ciphertext_path == nullptr || key == nullptr) return MINEG_INVALID_ARGUMENT;
  try {
    return core->implementation.encrypt_fd(input_fd, ciphertext_path, key);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

mineg_error_code_t mineg_core_decrypt_file(mineg_core_t *core, const char *ciphertext_path,
                                           const char *plaintext_path,
                                           const uint8_t key[MINEG_KEY_BYTES]) {
  if (core == nullptr || ciphertext_path == nullptr || plaintext_path == nullptr || key == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  try {
    return core->implementation.decrypt_file(ciphertext_path, plaintext_path, key);
  } catch (...) {
    return MINEG_INTERNAL_ERROR;
  }
}

void mineg_buffer_free(mineg_buffer_t *buffer) {
  if (buffer == nullptr) return;
  if (buffer->data != nullptr) {
    sodium_memzero(buffer->data, buffer->size);
    delete[] buffer->data;
  }
  buffer->data = nullptr;
  buffer->size = 0;
}

void mineg_core_close(mineg_core_t *core) { delete core; }
