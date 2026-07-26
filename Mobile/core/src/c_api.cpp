#include "mineg/mineg_core.h"

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
