#ifndef MINEG_CORE_H
#define MINEG_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define MINEG_API __declspec(dllexport)
#else
#define MINEG_API __attribute__((visibility("default")))
#endif

#define MINEG_ABI_VERSION 5U
#define MINEG_KEY_BYTES 32U
#define MINEG_FAMILY_KEY_ENVELOPE_BYTES 80U
#define MINEG_MEDIA_KEY_ENVELOPE_BYTES 80U
#define MINEG_MEDIA_NONCE_PREFIX_BYTES 16U

typedef struct mineg_core mineg_core_t;

typedef enum mineg_error_code {
  MINEG_OK = 0,
  MINEG_INVALID_ARGUMENT = 1,
  MINEG_CLOSED = 2,
  MINEG_DATABASE_ERROR = 3,
  MINEG_CRYPTO_ERROR = 4,
  MINEG_INTEGRITY_ERROR = 5,
  MINEG_CANCELLED = 6,
  MINEG_NOT_FOUND = 7,
  MINEG_INTERNAL_ERROR = 8
} mineg_error_code_t;

typedef struct mineg_buffer {
  uint8_t *data;
  size_t size;
} mineg_buffer_t;

typedef void (*mineg_event_callback_t)(const uint8_t *event_json, size_t event_size, void *user_data);

/*
 * Ownership and threading contract (M0 FROZEN):
 * - database_path and all input byte spans are borrowed only for the call.
 * - output buffers are caller-owned and must be released with mineg_buffer_free.
 * - callbacks run synchronously on the thread that executes the emitting command.
 * - callbacks must not re-enter unsubscribe or close. Once unsubscribe returns,
 *   that token will never receive another callback.
 * - mineg_core_t is thread-safe. mineg_core_close is the sole release operation;
 *   callers must stop concurrent calls before closing the handle.
 */
MINEG_API uint32_t mineg_abi_version(void);
MINEG_API mineg_error_code_t mineg_core_create(const char *database_path, mineg_core_t **out_core);
MINEG_API mineg_error_code_t mineg_core_execute(mineg_core_t *core, uint64_t operation_id,
                                                const uint8_t *command_json, size_t command_size,
                                                mineg_buffer_t *out_result_json);
MINEG_API mineg_error_code_t mineg_core_start_operation(
    mineg_core_t *core, uint64_t operation_id, const uint8_t *command_json, size_t command_size,
    mineg_buffer_t *out_operation_step_json);
MINEG_API mineg_error_code_t mineg_core_resume_operation(
    mineg_core_t *core, uint64_t operation_id, const uint8_t *effect_result_json,
    size_t effect_result_size, mineg_buffer_t *out_operation_step_json);
MINEG_API mineg_error_code_t mineg_core_recover_operations(
    mineg_core_t *core, mineg_buffer_t *out_operations_json);
MINEG_API mineg_error_code_t mineg_core_query(mineg_core_t *core, const uint8_t *query_json,
                                              size_t query_size, mineg_buffer_t *out_result_json);
MINEG_API mineg_error_code_t mineg_core_subscribe(mineg_core_t *core, mineg_event_callback_t callback,
                                                  void *user_data, uint64_t *out_subscription_token);
MINEG_API mineg_error_code_t mineg_core_unsubscribe(mineg_core_t *core, uint64_t subscription_token);
MINEG_API mineg_error_code_t mineg_core_cancel(mineg_core_t *core, uint64_t operation_id);
MINEG_API mineg_error_code_t mineg_core_random_key(mineg_buffer_t *out_key);
MINEG_API mineg_error_code_t mineg_core_create_user_key_bundle(
    const uint8_t *password, size_t password_size, mineg_buffer_t *out_public_key,
    mineg_buffer_t *out_encrypted_bundle, mineg_buffer_t *out_kdf_parameters_json);
MINEG_API mineg_error_code_t mineg_core_unlock_user_key_bundle(
    mineg_core_t *core, const uint8_t *password, size_t password_size,
    const uint8_t public_key[MINEG_KEY_BYTES], const uint8_t *encrypted_bundle,
    size_t encrypted_bundle_size, const uint8_t device_wrap_key[MINEG_KEY_BYTES],
    mineg_buffer_t *out_device_unlock_blob);
MINEG_API mineg_error_code_t mineg_core_restore_user_key_bundle(
    mineg_core_t *core, const uint8_t public_key[MINEG_KEY_BYTES],
    const uint8_t device_wrap_key[MINEG_KEY_BYTES], const uint8_t *device_unlock_blob,
    size_t device_unlock_blob_size);
MINEG_API mineg_error_code_t mineg_core_unlock_family_key_envelope(
    mineg_core_t *core, const uint8_t *encrypted_envelope, size_t encrypted_envelope_size);
MINEG_API mineg_error_code_t mineg_core_create_family_key_envelope(
    mineg_core_t *core, const uint8_t recipient_public_key[MINEG_KEY_BYTES],
    uint8_t bootstrap_if_needed, mineg_buffer_t *out_encrypted_envelope);
MINEG_API void mineg_core_lock_keys(mineg_core_t *core);
MINEG_API mineg_error_code_t mineg_core_create_media_key_envelope(
    mineg_core_t *core, const char *media_id, mineg_buffer_t *out_encrypted_media_key);
MINEG_API mineg_error_code_t mineg_core_compute_dedupe_fingerprint(
    mineg_core_t *core, int32_t input_fd, const char *media_type,
    mineg_buffer_t *out_fingerprint);
MINEG_API mineg_error_code_t mineg_core_encrypt_media_resource(
    mineg_core_t *core, int32_t input_fd, const char *ciphertext_path, const char *media_id,
    const char *resource_id, const char *resource_type, const uint8_t *encrypted_media_key,
    size_t encrypted_media_key_size, mineg_buffer_t *out_resource_manifest_json);
MINEG_API mineg_error_code_t mineg_core_encrypt_media_manifest(
    mineg_core_t *core, const char *media_id, const uint8_t *manifest_json,
    size_t manifest_json_size, const uint8_t *encrypted_media_key,
    size_t encrypted_media_key_size, mineg_buffer_t *out_encrypted_manifest);
MINEG_API mineg_error_code_t mineg_core_decrypt_media_resource(
    mineg_core_t *core, const char *ciphertext_path, const char *plaintext_path,
    const char *media_id, const char *resource_id, const char *resource_type,
    uint64_t plaintext_size, const uint8_t nonce_prefix[MINEG_MEDIA_NONCE_PREFIX_BYTES],
    const uint8_t *encrypted_media_key, size_t encrypted_media_key_size);
MINEG_API mineg_error_code_t mineg_core_encrypt_fd(mineg_core_t *core, int32_t input_fd,
                                                   const char *ciphertext_path,
                                                   const uint8_t key[MINEG_KEY_BYTES]);
MINEG_API mineg_error_code_t mineg_core_decrypt_file(mineg_core_t *core, const char *ciphertext_path,
                                                     const char *plaintext_path,
                                                     const uint8_t key[MINEG_KEY_BYTES]);
MINEG_API void mineg_buffer_free(mineg_buffer_t *buffer);
MINEG_API void mineg_core_close(mineg_core_t *core);

#ifdef __cplusplus
}
#endif

#endif
