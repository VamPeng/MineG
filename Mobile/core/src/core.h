#ifndef MINEG_CORE_IMPLEMENTATION_H
#define MINEG_CORE_IMPLEMENTATION_H

#include <cstdint>
#include <array>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>

#include "mineg/mineg_core.h"
#include "sqlite_compat.h"

namespace mineg {

class Core final {
 public:
  explicit Core(const std::string &database_path);
  ~Core();

  Core(const Core &) = delete;
  Core &operator=(const Core &) = delete;

  mineg_error_code_t execute(uint64_t operation_id, const std::string &command, std::string &result);
  mineg_error_code_t start_operation(uint64_t operation_id, const std::string &command,
                                     std::string &result);
  mineg_error_code_t resume_operation(uint64_t operation_id, const std::string &effect_result,
                                      std::string &result);
  mineg_error_code_t recover_operations(std::string &result);
  mineg_error_code_t query(const std::string &query, std::string &result);
  mineg_error_code_t subscribe(std::function<void(const std::string &)> callback, uint64_t &token);
  mineg_error_code_t unsubscribe(uint64_t token);
  mineg_error_code_t cancel(uint64_t operation_id);
  mineg_error_code_t encrypt_fd(int input_fd, const std::string &ciphertext_path,
                                const unsigned char key[MINEG_KEY_BYTES]);
  mineg_error_code_t decrypt_file(const std::string &ciphertext_path, const std::string &plaintext_path,
                                  const unsigned char key[MINEG_KEY_BYTES]);
  mineg_error_code_t unlock_user_key_bundle(
      const uint8_t *password, size_t password_size, const uint8_t public_key[MINEG_KEY_BYTES],
      const uint8_t *encrypted_bundle, size_t encrypted_bundle_size,
      const uint8_t device_wrap_key[MINEG_KEY_BYTES], std::string &device_unlock_blob);
  mineg_error_code_t restore_user_key_bundle(
      const uint8_t public_key[MINEG_KEY_BYTES], const uint8_t device_wrap_key[MINEG_KEY_BYTES],
      const uint8_t *device_unlock_blob, size_t device_unlock_blob_size);
  mineg_error_code_t unlock_family_key_envelope(const uint8_t *encrypted_envelope,
                                                 size_t encrypted_envelope_size);
  mineg_error_code_t create_family_key_envelope(
      const uint8_t recipient_public_key[MINEG_KEY_BYTES], bool bootstrap_if_needed,
      std::string &encrypted_envelope);
  void lock_keys();
  mineg_error_code_t create_media_key_envelope(const std::string &media_id,
                                                std::string &encrypted_media_key);
  mineg_error_code_t compute_dedupe_fingerprint(int input_fd, const std::string &media_type,
                                                std::string &fingerprint);
  mineg_error_code_t encrypt_media_resource(
      int input_fd, const std::string &ciphertext_path, const std::string &media_id,
      const std::string &resource_id, const std::string &resource_type,
      const uint8_t *encrypted_media_key, size_t encrypted_media_key_size,
      std::string &resource_manifest_json);
  mineg_error_code_t encrypt_media_manifest(
      const std::string &media_id, const uint8_t *manifest_json, size_t manifest_json_size,
      const uint8_t *encrypted_media_key, size_t encrypted_media_key_size,
      std::string &encrypted_manifest);
  mineg_error_code_t decrypt_media_resource(
      const std::string &ciphertext_path, const std::string &plaintext_path,
      const std::string &media_id, const std::string &resource_id,
      const std::string &resource_type, uint64_t plaintext_size,
      const uint8_t nonce_prefix[MINEG_MEDIA_NONCE_PREFIX_BYTES],
      const uint8_t *encrypted_media_key, size_t encrypted_media_key_size);

 private:
  struct AccountOperation;
  struct ActiveAccountSession;

  void open_and_migrate(const std::string &database_path);
  void exec_sql(const char *sql);
  std::string read_probe_locked();
  std::string read_account_state_locked();
  std::string read_backup_settings_locked(const std::string &query);
  std::string read_scan_state_locked(const std::string &query);
  std::string list_local_albums_locked(const std::string &query);
  std::string list_local_media_locked(const std::string &query);
  mineg_error_code_t apply_local_media_batch_locked(const std::string &command);
  mineg_error_code_t create_single_media_backup_locked(const std::string &command);
  mineg_error_code_t record_prepared_media_locked(const std::string &command);
  mineg_error_code_t update_single_media_backup_locked(const std::string &command,
                                                       const std::string &type);
  std::string read_single_media_backup_locked(const std::string &query);
  mineg_error_code_t update_backup_settings_locked(const std::string &command);
  mineg_error_code_t read_operation_step_locked(uint64_t operation_id, std::string &result,
                                                std::string *command_json = nullptr,
                                                std::string *effect_result_json = nullptr);
  mineg_error_code_t start_account_operation_locked(uint64_t operation_id,
                                                    const std::string &command,
                                                    std::string &result);
  mineg_error_code_t resume_account_operation_locked(uint64_t operation_id,
                                                     const std::string &effect_result,
                                                     std::string &result);
  mineg_error_code_t account_operation_step_locked(AccountOperation &operation,
                                                   std::string &result);
  void set_account_effect_locked(AccountOperation &operation, const std::string &effect_type,
                                 const std::string &payload, const std::string &stage);
  void finish_account_error_locked(AccountOperation &operation, const std::string &code,
                                   bool retryable, const std::string &request_id = {});
  mineg_error_code_t issue_account_request_locked(AccountOperation &operation,
                                                  const std::string &purpose);
  void issue_session_read_locked(AccountOperation &operation);
  void issue_session_write_locked(AccountOperation &operation,
                                  const std::string &continuation);
  void issue_session_cleanup_locked(AccountOperation &operation,
                                    const std::string &completion);
  bool activate_account_session_locked(AccountOperation &operation);
  std::string read_current_profile_snapshot_locked();
  bool persist_current_profile_locked(const std::string &profile_json);
  std::string read_private_media_snapshot_locked(int limit);
  bool has_private_media_cache_locked();
  bool persist_private_media_locked(const std::string &page_json);
  mineg_error_code_t unlock_user_key_bundle_locked(
      const uint8_t *password, size_t password_size, const uint8_t public_key[MINEG_KEY_BYTES],
      const uint8_t *encrypted_bundle, size_t encrypted_bundle_size,
      const uint8_t device_wrap_key[MINEG_KEY_BYTES], std::string &device_unlock_blob);
  mineg_error_code_t restore_user_key_bundle_locked(
      const uint8_t public_key[MINEG_KEY_BYTES], const uint8_t device_wrap_key[MINEG_KEY_BYTES],
      const uint8_t *device_unlock_blob, size_t device_unlock_blob_size);
  mineg_error_code_t unlock_family_key_envelope_locked(const uint8_t *encrypted_envelope,
                                                        size_t encrypted_envelope_size);
  mineg_error_code_t create_family_key_envelope_locked(
      const uint8_t recipient_public_key[MINEG_KEY_BYTES], bool bootstrap_if_needed,
      std::string &encrypted_envelope);
  void clear_account_session_locked();
  bool execute_json_statement_locked(const char *sql, const std::string &json);
  bool execute_json_update_locked(const char *sql, const std::string &json);
  void lock_keys_locked();
  void emit_locked(const std::string &event);

  sqlite3 *database_ = nullptr;
  std::mutex mutex_;
  uint64_t next_subscription_ = 1;
  uint64_t event_sequence_ = 0;
  std::unordered_map<uint64_t, std::function<void(const std::string &)>> subscribers_;
  std::unordered_set<uint64_t> cancelled_operations_;
  std::unordered_map<uint64_t, std::unique_ptr<AccountOperation>> account_operations_;
  std::unique_ptr<ActiveAccountSession> active_account_session_;
  std::array<uint8_t, MINEG_KEY_BYTES> user_public_key_{};
  std::array<uint8_t, MINEG_KEY_BYTES> user_private_key_{};
  std::array<uint8_t, MINEG_KEY_BYTES> user_master_key_{};
  std::array<uint8_t, MINEG_KEY_BYTES> family_key_{};
  bool user_keys_unlocked_ = false;
  bool family_key_unlocked_ = false;
};

}  // namespace mineg

#endif
