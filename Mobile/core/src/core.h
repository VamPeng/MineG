#ifndef MINEG_CORE_IMPLEMENTATION_H
#define MINEG_CORE_IMPLEMENTATION_H

#include <cstdint>
#include <functional>
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
  mineg_error_code_t query(const std::string &query, std::string &result);
  mineg_error_code_t subscribe(std::function<void(const std::string &)> callback, uint64_t &token);
  mineg_error_code_t unsubscribe(uint64_t token);
  mineg_error_code_t cancel(uint64_t operation_id);
  mineg_error_code_t encrypt_fd(int input_fd, const std::string &ciphertext_path,
                                const unsigned char key[MINEG_KEY_BYTES]);
  mineg_error_code_t decrypt_file(const std::string &ciphertext_path, const std::string &plaintext_path,
                                  const unsigned char key[MINEG_KEY_BYTES]);

 private:
  void open_and_migrate(const std::string &database_path);
  void exec_sql(const char *sql);
  std::string read_probe_locked();
  void emit_locked(const std::string &event);

  sqlite3 *database_ = nullptr;
  std::mutex mutex_;
  uint64_t next_subscription_ = 1;
  uint64_t event_sequence_ = 0;
  std::unordered_map<uint64_t, std::function<void(const std::string &)>> subscribers_;
  std::unordered_set<uint64_t> cancelled_operations_;
};

}  // namespace mineg

#endif
