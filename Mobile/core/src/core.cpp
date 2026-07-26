#include "core.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

#include <fcntl.h>
#include <unistd.h>

#include "sodium_compat.h"

namespace mineg {
namespace {

constexpr std::array<unsigned char, 8> kFileMagic = {'M', 'I', 'N', 'E', 'G', '0', '1', 0};
constexpr size_t kChunkBytes = 64U * 1024U;

std::string json_escape(const std::string &value) {
  std::string result;
  result.reserve(value.size() + 8);
  for (const char character : value) {
    switch (character) {
      case '\\': result += "\\\\"; break;
      case '"': result += "\\\""; break;
      case '\n': result += "\\n"; break;
      case '\r': result += "\\r"; break;
      case '\t': result += "\\t"; break;
      default:
        if (static_cast<unsigned char>(character) < 0x20) result += '?';
        else result += character;
    }
  }
  return result;
}

std::string extract_json_string(const std::string &json, const std::string &field) {
  const std::string marker = "\"" + field + "\"";
  size_t position = json.find(marker);
  if (position == std::string::npos) return {};
  position = json.find(':', position + marker.size());
  if (position == std::string::npos) return {};
  position = json.find('"', position + 1);
  if (position == std::string::npos) return {};
  const size_t end = json.find('"', position + 1);
  if (end == std::string::npos) return {};
  return json.substr(position + 1, end - position - 1);
}

bool write_all(std::ofstream &output, const unsigned char *bytes, size_t size) {
  output.write(reinterpret_cast<const char *>(bytes), static_cast<std::streamsize>(size));
  return output.good();
}

void write_u32(std::ofstream &output, uint32_t value) {
  unsigned char encoded[4] = {
      static_cast<unsigned char>((value >> 24U) & 0xffU),
      static_cast<unsigned char>((value >> 16U) & 0xffU),
      static_cast<unsigned char>((value >> 8U) & 0xffU),
      static_cast<unsigned char>(value & 0xffU),
  };
  output.write(reinterpret_cast<const char *>(encoded), 4);
}

bool read_u32(std::ifstream &input, uint32_t &value) {
  unsigned char encoded[4];
  input.read(reinterpret_cast<char *>(encoded), 4);
  if (input.gcount() == 0 && input.eof()) return false;
  if (input.gcount() != 4) throw std::runtime_error("truncated frame length");
  value = (static_cast<uint32_t>(encoded[0]) << 24U) |
          (static_cast<uint32_t>(encoded[1]) << 16U) |
          (static_cast<uint32_t>(encoded[2]) << 8U) |
          static_cast<uint32_t>(encoded[3]);
  return true;
}

ssize_t read_retry(int fd, unsigned char *buffer, size_t size) {
  for (;;) {
    const ssize_t count = ::read(fd, buffer, size);
    if (count < 0 && errno == EINTR) continue;
    return count;
  }
}

class SensitiveState final {
 public:
  crypto_secretstream_xchacha20poly1305_state value{};
  ~SensitiveState() { sodium_memzero(&value, sizeof(value)); }
};

}  // namespace

Core::Core(const std::string &database_path) { open_and_migrate(database_path); }

Core::~Core() {
  std::lock_guard<std::mutex> lock(mutex_);
  subscribers_.clear();
  cancelled_operations_.clear();
  if (database_ != nullptr) {
    sqlite3_close_v2(database_);
    database_ = nullptr;
  }
}

void Core::open_and_migrate(const std::string &database_path) {
  if (database_path.empty()) throw std::invalid_argument("database path is empty");
  const int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX;
  if (sqlite3_open_v2(database_path.c_str(), &database_, flags, nullptr) != SQLITE_OK) {
    const std::string message = database_ == nullptr ? "sqlite open failed" : sqlite3_errmsg(database_);
    if (database_ != nullptr) sqlite3_close_v2(database_);
    database_ = nullptr;
    throw std::runtime_error(message);
  }
  sqlite3_busy_timeout(database_, 5000);
  exec_sql("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;");
  exec_sql(
      "BEGIN IMMEDIATE;"
      "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY NOT NULL);"
      "CREATE TABLE IF NOT EXISTS foundation_probe("
      "  singleton INTEGER PRIMARY KEY CHECK(singleton = 1),"
      "  value TEXT NOT NULL"
      ");"
      "INSERT OR IGNORE INTO schema_migrations(version) VALUES(1);"
      "INSERT OR IGNORE INTO foundation_probe(singleton, value) VALUES(1, 'initialized');"
      "PRAGMA user_version=1;"
      "COMMIT;");
}

void Core::exec_sql(const char *sql) {
  char *error = nullptr;
  if (sqlite3_exec(database_, sql, nullptr, nullptr, &error) != SQLITE_OK) {
    std::string message = error == nullptr ? sqlite3_errmsg(database_) : error;
    if (error != nullptr) sqlite3_free(error);
    sqlite3_exec(database_, "ROLLBACK;", nullptr, nullptr, nullptr);
    throw std::runtime_error(message);
  }
}

mineg_error_code_t Core::execute(uint64_t operation_id, const std::string &command, std::string &result) {
  if (operation_id == 0) return MINEG_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(mutex_);
  if (cancelled_operations_.erase(operation_id) > 0) return MINEG_CANCELLED;
  if (extract_json_string(command, "type") != "FoundationWriteProbe") return MINEG_NOT_FOUND;
  const std::string value = extract_json_string(command, "value");
  if (value.empty() || value.size() > 256) return MINEG_INVALID_ARGUMENT;

  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, "UPDATE foundation_probe SET value=? WHERE singleton=1", -1,
                         &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  const int bind = sqlite3_bind_text(statement, 1, value.c_str(), static_cast<int>(value.size()), SQLITE_TRANSIENT);
  const int step = bind == SQLITE_OK ? sqlite3_step(statement) : bind;
  sqlite3_finalize(statement);
  if (step != SQLITE_DONE) return MINEG_DATABASE_ERROR;

  result = "{\"version\":1,\"status\":\"SUCCESS\",\"value\":\"" + json_escape(value) + "\"}";
  ++event_sequence_;
  emit_locked("{\"version\":1,\"type\":\"FoundationProbeChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"value\":\"" + json_escape(value) + "\"}");
  return MINEG_OK;
}

std::string Core::read_probe_locked() {
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, "SELECT value FROM foundation_probe WHERE singleton=1", -1,
                         &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  std::string value;
  if (sqlite3_step(statement) == SQLITE_ROW) {
    const auto *text = sqlite3_column_text(statement, 0);
    if (text != nullptr) value = reinterpret_cast<const char *>(text);
  }
  sqlite3_finalize(statement);
  return value;
}

mineg_error_code_t Core::query(const std::string &query, std::string &result) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (extract_json_string(query, "type") != "FoundationReadProbe") return MINEG_NOT_FOUND;
  try {
    const std::string value = read_probe_locked();
    result = "{\"version\":1,\"value\":\"" + json_escape(value) + "\"}";
    return MINEG_OK;
  } catch (...) {
    return MINEG_DATABASE_ERROR;
  }
}

mineg_error_code_t Core::subscribe(std::function<void(const std::string &)> callback, uint64_t &token) {
  if (!callback) return MINEG_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(mutex_);
  token = next_subscription_++;
  subscribers_.emplace(token, std::move(callback));
  return MINEG_OK;
}

mineg_error_code_t Core::unsubscribe(uint64_t token) {
  std::lock_guard<std::mutex> lock(mutex_);
  return subscribers_.erase(token) == 0 ? MINEG_NOT_FOUND : MINEG_OK;
}

mineg_error_code_t Core::cancel(uint64_t operation_id) {
  if (operation_id == 0) return MINEG_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(mutex_);
  cancelled_operations_.insert(operation_id);
  return MINEG_OK;
}

void Core::emit_locked(const std::string &event) {
  for (const auto &entry : subscribers_) entry.second(event);
}

mineg_error_code_t Core::encrypt_fd(int input_fd, const std::string &ciphertext_path,
                                    const unsigned char key[MINEG_KEY_BYTES]) {
  if (input_fd < 0 || ciphertext_path.empty() || key == nullptr) return MINEG_INVALID_ARGUMENT;
  SensitiveState state;
  std::array<unsigned char, crypto_secretstream_xchacha20poly1305_HEADERBYTES> header{};
  if (crypto_secretstream_xchacha20poly1305_init_push(&state.value, header.data(), key) != 0) {
    return MINEG_CRYPTO_ERROR;
  }

  std::ofstream output(ciphertext_path, std::ios::binary | std::ios::trunc);
  if (!output) return MINEG_CRYPTO_ERROR;
  if (!write_all(output, kFileMagic.data(), kFileMagic.size()) ||
      !write_all(output, header.data(), header.size())) {
    output.close();
    std::filesystem::remove(ciphertext_path);
    return MINEG_CRYPTO_ERROR;
  }

  std::vector<unsigned char> current(kChunkBytes);
  std::vector<unsigned char> next(kChunkBytes);
  std::vector<unsigned char> encrypted(kChunkBytes + crypto_secretstream_xchacha20poly1305_ABYTES);
  ssize_t current_size = read_retry(input_fd, current.data(), current.size());
  if (current_size < 0) {
    output.close();
    std::filesystem::remove(ciphertext_path);
    return MINEG_CRYPTO_ERROR;
  }
  for (;;) {
    ssize_t next_size = 0;
    if (current_size > 0) {
      next_size = read_retry(input_fd, next.data(), next.size());
      if (next_size < 0) {
        output.close();
        std::filesystem::remove(ciphertext_path);
        return MINEG_CRYPTO_ERROR;
      }
    }
    const bool final_chunk = current_size == 0 || next_size == 0;
    unsigned long long encrypted_size = 0;
    const unsigned char tag = final_chunk ? crypto_secretstream_xchacha20poly1305_TAG_FINAL
                                          : crypto_secretstream_xchacha20poly1305_TAG_MESSAGE;
    if (crypto_secretstream_xchacha20poly1305_push(
            &state.value, encrypted.data(), &encrypted_size, current.data(),
            static_cast<unsigned long long>(current_size), nullptr, 0, tag) != 0) {
      output.close();
      std::filesystem::remove(ciphertext_path);
      return MINEG_CRYPTO_ERROR;
    }
    write_u32(output, static_cast<uint32_t>(encrypted_size));
    if (!write_all(output, encrypted.data(), static_cast<size_t>(encrypted_size))) {
      output.close();
      std::filesystem::remove(ciphertext_path);
      return MINEG_CRYPTO_ERROR;
    }
    if (final_chunk) break;
    current.swap(next);
    current_size = next_size;
  }
  output.flush();
  if (!output.good()) {
    output.close();
    std::filesystem::remove(ciphertext_path);
    return MINEG_CRYPTO_ERROR;
  }
  return MINEG_OK;
}

mineg_error_code_t Core::decrypt_file(const std::string &ciphertext_path,
                                      const std::string &plaintext_path,
                                      const unsigned char key[MINEG_KEY_BYTES]) {
  if (ciphertext_path.empty() || plaintext_path.empty() || key == nullptr) return MINEG_INVALID_ARGUMENT;
  const std::string partial_path = plaintext_path + ".partial";
  std::filesystem::remove(partial_path);
  std::ifstream input(ciphertext_path, std::ios::binary);
  if (!input) return MINEG_CRYPTO_ERROR;
  std::array<unsigned char, 8> magic{};
  std::array<unsigned char, crypto_secretstream_xchacha20poly1305_HEADERBYTES> header{};
  input.read(reinterpret_cast<char *>(magic.data()), magic.size());
  input.read(reinterpret_cast<char *>(header.data()), header.size());
  if (!input || magic != kFileMagic) return MINEG_INTEGRITY_ERROR;

  SensitiveState state;
  if (crypto_secretstream_xchacha20poly1305_init_pull(&state.value, header.data(), key) != 0) {
    return MINEG_CRYPTO_ERROR;
  }
  std::ofstream output(partial_path, std::ios::binary | std::ios::trunc);
  if (!output) return MINEG_CRYPTO_ERROR;

  mineg_error_code_t result = MINEG_INTEGRITY_ERROR;
  try {
    bool saw_final = false;
    uint32_t frame_size = 0;
    while (read_u32(input, frame_size)) {
      if (saw_final || frame_size < crypto_secretstream_xchacha20poly1305_ABYTES ||
          frame_size > kChunkBytes + crypto_secretstream_xchacha20poly1305_ABYTES) {
        throw std::runtime_error("invalid frame");
      }
      std::vector<unsigned char> encrypted(frame_size);
      input.read(reinterpret_cast<char *>(encrypted.data()), static_cast<std::streamsize>(frame_size));
      if (input.gcount() != static_cast<std::streamsize>(frame_size)) throw std::runtime_error("truncated frame");
      std::vector<unsigned char> plaintext(frame_size);
      unsigned long long plaintext_size = 0;
      unsigned char tag = 0;
      if (crypto_secretstream_xchacha20poly1305_pull(
              &state.value, plaintext.data(), &plaintext_size, &tag, encrypted.data(), frame_size,
              nullptr, 0) != 0) {
        sodium_memzero(plaintext.data(), plaintext.size());
        throw std::runtime_error("authentication failed");
      }
      if (!write_all(output, plaintext.data(), static_cast<size_t>(plaintext_size))) {
        sodium_memzero(plaintext.data(), plaintext.size());
        throw std::runtime_error("write failed");
      }
      sodium_memzero(plaintext.data(), plaintext.size());
      saw_final = tag == crypto_secretstream_xchacha20poly1305_TAG_FINAL;
    }
    if (!saw_final || !input.eof()) throw std::runtime_error("missing final frame");
    output.flush();
    if (!output.good()) throw std::runtime_error("flush failed");
    output.close();
    std::filesystem::remove(plaintext_path);
    std::filesystem::rename(partial_path, plaintext_path);
    result = MINEG_OK;
  } catch (...) {
    output.close();
    std::filesystem::remove(partial_path);
    result = MINEG_INTEGRITY_ERROR;
  }
  return result;
}

}  // namespace mineg
