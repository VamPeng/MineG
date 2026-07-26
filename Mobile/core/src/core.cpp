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
#include <limits>

#include <fcntl.h>
#include <unistd.h>

#include "sodium_compat.h"

namespace mineg {
namespace {

constexpr std::array<unsigned char, 8> kFileMagic = {'M', 'I', 'N', 'E', 'G', '0', '1', 0};
constexpr size_t kChunkBytes = 64U * 1024U;
constexpr size_t kMediaChunkBytes = 4U * 1024U * 1024U;
constexpr std::array<unsigned char, 8> kMediaKeyMagic = {'M', 'K', 'E', 'Y', '0', '1', 0, 0};
constexpr std::array<unsigned char, 8> kManifestMagic = {'M', 'A', 'N', 'I', '0', '1', 0, 0};

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

int64_t extract_json_integer(const std::string &json, const std::string &field,
                             int64_t fallback = 0) {
  const std::string marker = "\"" + field + "\"";
  size_t position = json.find(marker);
  if (position == std::string::npos) return fallback;
  position = json.find(':', position + marker.size());
  if (position == std::string::npos) return fallback;
  const size_t start = json.find_first_of("-0123456789", position + 1);
  if (start == std::string::npos) return fallback;
  const size_t end = json.find_first_not_of("0123456789", start + (json[start] == '-' ? 1U : 0U));
  try {
    return std::stoll(json.substr(start, end - start));
  } catch (...) {
    return fallback;
  }
}

bool extract_json_boolean(const std::string &json, const std::string &field, bool fallback = false) {
  const std::string marker = "\"" + field + "\"";
  size_t position = json.find(marker);
  if (position == std::string::npos) return fallback;
  position = json.find(':', position + marker.size());
  if (position == std::string::npos) return fallback;
  const size_t value = json.find_first_not_of(" \t\r\n", position + 1);
  if (value == std::string::npos) return fallback;
  if (json.compare(value, 4, "true") == 0) return true;
  if (json.compare(value, 5, "false") == 0) return false;
  return fallback;
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

template <size_t Size>
class SensitiveBytes final {
 public:
  std::array<unsigned char, Size> value{};
  ~SensitiveBytes() { sodium_memzero(value.data(), value.size()); }
};

std::string hex_encode(const unsigned char *bytes, size_t size) {
  static constexpr char kHex[] = "0123456789abcdef";
  std::string result(size * 2U, '0');
  for (size_t index = 0; index < size; ++index) {
    result[index * 2U] = kHex[(bytes[index] >> 4U) & 0x0fU];
    result[index * 2U + 1U] = kHex[bytes[index] & 0x0fU];
  }
  return result;
}

void media_nonce(const unsigned char prefix[MINEG_MEDIA_NONCE_PREFIX_BYTES], uint64_t block_index,
                 unsigned char nonce[crypto_aead_xchacha20poly1305_ietf_NPUBBYTES]) {
  std::memcpy(nonce, prefix, MINEG_MEDIA_NONCE_PREFIX_BYTES);
  for (size_t index = 0; index < 8U; ++index) {
    nonce[MINEG_MEDIA_NONCE_PREFIX_BYTES + index] =
        static_cast<unsigned char>((block_index >> (56U - index * 8U)) & 0xffU);
  }
}

std::string media_aad(const std::string &media_id, const std::string &resource_id,
                      const std::string &resource_type, uint64_t block_index,
                      size_t plaintext_size) {
  return "MINEG_MEDIA_V1\n" + media_id + "\n" + resource_id + "\n" + resource_type + "\n" +
         std::to_string(block_index) + "\n" + std::to_string(plaintext_size);
}

bool derive_hmac_key(const unsigned char source[MINEG_KEY_BYTES], const std::string &domain,
                     unsigned char output[MINEG_KEY_BYTES]) {
  return crypto_auth_hmacsha256(output, reinterpret_cast<const unsigned char *>(domain.data()),
                                domain.size(), source) == 0;
}

bool unwrap_media_key(const unsigned char user_master_key[MINEG_KEY_BYTES],
                      const std::string &media_id, const uint8_t *envelope, size_t envelope_size,
                      unsigned char media_key[MINEG_KEY_BYTES]) {
  if (envelope == nullptr || envelope_size != MINEG_MEDIA_KEY_ENVELOPE_BYTES ||
      std::memcmp(envelope, kMediaKeyMagic.data(), kMediaKeyMagic.size()) != 0) {
    return false;
  }
  const unsigned char *nonce = envelope + kMediaKeyMagic.size();
  const unsigned char *ciphertext = nonce + crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
  const std::string aad = "MINEG_MEDIA_KEY_V1\n" + media_id;
  unsigned long long plaintext_size = 0;
  return crypto_aead_xchacha20poly1305_ietf_decrypt(
             media_key, &plaintext_size, nullptr, ciphertext,
             MINEG_KEY_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES,
             reinterpret_cast<const unsigned char *>(aad.data()), aad.size(), nonce,
             user_master_key) == 0 &&
         plaintext_size == MINEG_KEY_BYTES;
}

}  // namespace

Core::Core(const std::string &database_path) { open_and_migrate(database_path); }

Core::~Core() {
  std::lock_guard<std::mutex> lock(mutex_);
  lock_keys_locked();
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
      "CREATE TABLE IF NOT EXISTS account_state("
      "  singleton INTEGER PRIMARY KEY CHECK(singleton = 1),"
      "  user_id TEXT NOT NULL,"
      "  masked_phone TEXT NOT NULL,"
      "  approval_status TEXT NOT NULL CHECK(approval_status IN ('PENDING', 'APPROVED')) ,"
      "  updated_at TEXT NOT NULL"
      ");"
      "INSERT OR IGNORE INTO schema_migrations(version) VALUES(2);"
      "CREATE TABLE IF NOT EXISTS backup_settings("
      "  user_id TEXT NOT NULL, device_installation_id TEXT NOT NULL,"
      "  auto_backup_enabled INTEGER NOT NULL DEFAULT 1 CHECK(auto_backup_enabled IN (0,1)),"
      "  allow_cellular_backup INTEGER NOT NULL DEFAULT 0 CHECK(allow_cellular_backup IN (0,1)),"
      "  updated_at TEXT NOT NULL, PRIMARY KEY(user_id, device_installation_id)"
      ");"
      "CREATE TABLE IF NOT EXISTS local_albums("
      "  user_id TEXT NOT NULL, platform_album_ref TEXT NOT NULL, name TEXT NOT NULL,"
      "  is_available INTEGER NOT NULL DEFAULT 1 CHECK(is_available IN (0,1)),"
      "  modified_at TEXT NOT NULL, PRIMARY KEY(user_id, platform_album_ref)"
      ");"
      "CREATE TABLE IF NOT EXISTS local_media("
      "  user_id TEXT NOT NULL, platform_asset_ref TEXT NOT NULL, media_type TEXT NOT NULL "
      "    CHECK(media_type IN ('PHOTO','VIDEO','GIF','LIVE_PHOTO','DYNAMIC')),"
      "  mime_type TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,"
      "  duration_ms INTEGER, captured_at TEXT NOT NULL, modified_at TEXT NOT NULL,"
      "  modified_version INTEGER NOT NULL, content_version TEXT NOT NULL,"
      "  availability TEXT NOT NULL CHECK(availability IN ('AVAILABLE','WAITING_LOCAL_RESOURCE','LOCAL_MISSING')),"
      "  thumbnail_uri TEXT, scan_generation TEXT NOT NULL, PRIMARY KEY(user_id, platform_asset_ref)"
      ");"
      "CREATE INDEX IF NOT EXISTS local_media_capture_idx "
      "  ON local_media(user_id, captured_at DESC, platform_asset_ref DESC);"
      "CREATE TABLE IF NOT EXISTS local_media_albums("
      "  user_id TEXT NOT NULL, platform_asset_ref TEXT NOT NULL, platform_album_ref TEXT NOT NULL,"
      "  PRIMARY KEY(user_id, platform_asset_ref, platform_album_ref),"
      "  FOREIGN KEY(user_id, platform_asset_ref) REFERENCES local_media(user_id, platform_asset_ref) ON DELETE CASCADE,"
      "  FOREIGN KEY(user_id, platform_album_ref) REFERENCES local_albums(user_id, platform_album_ref) ON DELETE CASCADE"
      ");"
      "CREATE INDEX IF NOT EXISTS local_media_albums_album_idx "
      "  ON local_media_albums(user_id, platform_album_ref, platform_asset_ref);"
      "CREATE TABLE IF NOT EXISTS local_scan_state("
      "  user_id TEXT PRIMARY KEY, cursor_modified_version INTEGER NOT NULL DEFAULT 0,"
      "  cursor_asset_ref TEXT NOT NULL DEFAULT '', status TEXT NOT NULL "
      "    CHECK(status IN ('IDLE','SCANNING','COMPLETE','BLOCKED_PERMISSION')),"
      "  indexed_count INTEGER NOT NULL DEFAULT 0, scan_generation TEXT NOT NULL DEFAULT '',"
      "  updated_at TEXT NOT NULL"
      ");"
      "CREATE TABLE IF NOT EXISTS download_receipts("
      "  user_id TEXT NOT NULL, cloud_media_id TEXT NOT NULL, platform_asset_ref TEXT NOT NULL,"
      "  created_at TEXT NOT NULL, PRIMARY KEY(user_id, cloud_media_id)"
      ");"
      "INSERT OR IGNORE INTO schema_migrations(version) VALUES(3);"
      "CREATE TABLE IF NOT EXISTS backup_tasks("
      " task_id TEXT PRIMARY KEY,user_id TEXT NOT NULL,platform_asset_ref TEXT NOT NULL,"
      " content_version TEXT NOT NULL,media_type TEXT NOT NULL CHECK(media_type IN "
      " ('PHOTO','VIDEO','GIF','LIVE_PHOTO','DYNAMIC')),state TEXT NOT NULL CHECK(state IN "
      " ('PREPARING','PREPARED','UPLOADING','SERVER_VERIFYING','COMPLETED',"
      " 'RETRYABLE_FAILED','PERMANENT_FAILED')),dedupe_fingerprint TEXT,"
      " encrypted_media_key TEXT,encrypted_manifest TEXT,manifest_digest TEXT,"
      " server_upload_id TEXT,server_media_id TEXT,error_code TEXT,retry_count INTEGER NOT NULL "
      " DEFAULT 0 CHECK(retry_count>=0),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,"
      " UNIQUE(user_id,platform_asset_ref,content_version));"
      "CREATE INDEX IF NOT EXISTS backup_tasks_user_state_idx "
      " ON backup_tasks(user_id,state,updated_at);"
      "CREATE TABLE IF NOT EXISTS backup_resources("
      " resource_id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES backup_tasks(task_id) ON DELETE CASCADE,"
      " resource_type TEXT NOT NULL CHECK(resource_type IN "
      " ('ORIGINAL','THUMBNAIL','VIDEO_COVER','PREVIEW','LIVE_PHOTO_VIDEO','DYNAMIC_PREVIEW')),"
      " ciphertext_path TEXT NOT NULL,ciphertext_size INTEGER NOT NULL CHECK(ciphertext_size>0),"
      " ciphertext_sha256 TEXT NOT NULL,manifest_json TEXT NOT NULL CHECK(json_valid(manifest_json)),"
      " UNIQUE(task_id,resource_type));"
      "CREATE TABLE IF NOT EXISTS backup_parts("
      " resource_id TEXT NOT NULL REFERENCES backup_resources(resource_id) ON DELETE CASCADE,"
      " part_number INTEGER NOT NULL CHECK(part_number BETWEEN 1 AND 10000),"
      " ciphertext_offset INTEGER NOT NULL CHECK(ciphertext_offset>=0),"
      " ciphertext_size INTEGER NOT NULL CHECK(ciphertext_size BETWEEN 1 AND 4194320),"
      " ciphertext_sha256 TEXT NOT NULL,etag TEXT,state TEXT NOT NULL DEFAULT 'PENDING' "
      " CHECK(state IN ('PENDING','UPLOADED')),PRIMARY KEY(resource_id,part_number));"
      "INSERT OR IGNORE INTO schema_migrations(version) VALUES(4);"
      "PRAGMA user_version=4;"
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
  const std::string type = extract_json_string(command, "type");
  if (type == "ClearAccountState") {
    if (sqlite3_exec(database_, "DELETE FROM account_state WHERE singleton=1", nullptr, nullptr, nullptr) != SQLITE_OK) {
      return MINEG_DATABASE_ERROR;
    }
    lock_keys_locked();
    result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    ++event_sequence_;
    emit_locked("{\"version\":1,\"type\":\"AccountStateChanged\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"approvalStatus\":null}");
    return MINEG_OK;
  }
  if (type == "PersistAccountState") {
    const std::string user_id = extract_json_string(command, "userId");
    const std::string masked_phone = extract_json_string(command, "maskedPhone");
    const std::string approval_status = extract_json_string(command, "approvalStatus");
    const std::string updated_at = extract_json_string(command, "updatedAt");
    if (user_id.empty() || user_id.size() > 128 || masked_phone.size() != 11 ||
        (approval_status != "PENDING" && approval_status != "APPROVED") || updated_at.empty()) {
      return MINEG_INVALID_ARGUMENT;
    }
    sqlite3_stmt *statement = nullptr;
    const char *sql =
        "INSERT INTO account_state(singleton,user_id,masked_phone,approval_status,updated_at) "
        "VALUES(1,?,?,?,?) ON CONFLICT(singleton) DO UPDATE SET "
        "user_id=excluded.user_id,masked_phone=excluded.masked_phone,"
        "approval_status=excluded.approval_status,updated_at=excluded.updated_at";
    if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
      return MINEG_DATABASE_ERROR;
    }
    int bind = sqlite3_bind_text(statement, 1, user_id.c_str(), static_cast<int>(user_id.size()), SQLITE_TRANSIENT);
    if (bind == SQLITE_OK) bind = sqlite3_bind_text(statement, 2, masked_phone.c_str(), static_cast<int>(masked_phone.size()), SQLITE_TRANSIENT);
    if (bind == SQLITE_OK) bind = sqlite3_bind_text(statement, 3, approval_status.c_str(), static_cast<int>(approval_status.size()), SQLITE_TRANSIENT);
    if (bind == SQLITE_OK) bind = sqlite3_bind_text(statement, 4, updated_at.c_str(), static_cast<int>(updated_at.size()), SQLITE_TRANSIENT);
    const int step = bind == SQLITE_OK ? sqlite3_step(statement) : bind;
    sqlite3_finalize(statement);
    if (step != SQLITE_DONE) return MINEG_DATABASE_ERROR;
    result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    ++event_sequence_;
    emit_locked("{\"version\":1,\"type\":\"AccountStateChanged\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"approvalStatus\":\"" + approval_status + "\"}");
    return MINEG_OK;
  }
  if (type == "UpdateBackupSettings") {
    const mineg_error_code_t code = update_backup_settings_locked(command);
    if (code == MINEG_OK) result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    return code;
  }
  if (type == "ApplyLocalMediaBatch") {
    const mineg_error_code_t code = apply_local_media_batch_locked(command);
    if (code == MINEG_OK) result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    return code;
  }
  if (type == "CreateSingleMediaBackup") {
    const mineg_error_code_t code = create_single_media_backup_locked(command);
    if (code == MINEG_OK) result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    return code;
  }
  if (type == "RecordPreparedMedia") {
    const mineg_error_code_t code = record_prepared_media_locked(command);
    if (code == MINEG_OK) result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    return code;
  }
  if (type == "RecordUploadSession" || type == "RecordUploadedPart" ||
      type == "MarkServerVerifying" || type == "CompleteSingleMediaBackup" ||
      type == "CompleteDeduplicatedSingleMediaBackup" ||
      type == "MarkSingleMediaBackupFailed") {
    const mineg_error_code_t code = update_single_media_backup_locked(command, type);
    if (code == MINEG_OK) result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    return code;
  }
  if (type == "MarkLocalScanBlocked") {
    const std::string user_id = extract_json_string(command, "userId");
    const std::string updated_at = extract_json_string(command, "updatedAt");
    if (user_id.empty() || updated_at.empty()) return MINEG_INVALID_ARGUMENT;
    sqlite3_stmt *statement = nullptr;
    const char *sql =
        "INSERT INTO local_scan_state(user_id,status,updated_at) VALUES(?, 'BLOCKED_PERMISSION', ?) "
        "ON CONFLICT(user_id) DO UPDATE SET status='BLOCKED_PERMISSION',updated_at=excluded.updated_at";
    if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return MINEG_DATABASE_ERROR;
    int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, updated_at.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(statement);
    sqlite3_finalize(statement);
    if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
    result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    return MINEG_OK;
  }
  if (type != "FoundationWriteProbe") return MINEG_NOT_FOUND;
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

std::string Core::read_account_state_locked() {
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT user_id,masked_phone,approval_status,updated_at FROM account_state WHERE singleton=1",
                         -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  if (sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    return "{\"version\":1,\"state\":null}";
  }
  const auto text_at = [statement](int column) -> std::string {
    const auto *value = sqlite3_column_text(statement, column);
    return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
  };
  const std::string result =
      "{\"version\":1,\"state\":{\"userId\":\"" + json_escape(text_at(0)) +
      "\",\"maskedPhone\":\"" + json_escape(text_at(1)) +
      "\",\"approvalStatus\":\"" + json_escape(text_at(2)) +
      "\",\"updatedAt\":\"" + json_escape(text_at(3)) + "\"}}";
  sqlite3_finalize(statement);
  return result;
}

mineg_error_code_t Core::update_backup_settings_locked(const std::string &command) {
  const std::string user_id = extract_json_string(command, "userId");
  const std::string device_id = extract_json_string(command, "deviceInstallationId");
  const std::string updated_at = extract_json_string(command, "updatedAt");
  if (user_id.empty() || device_id.empty() || updated_at.empty()) return MINEG_INVALID_ARGUMENT;
  const bool auto_backup = extract_json_boolean(command, "autoBackupEnabled", true);
  const bool cellular = extract_json_boolean(command, "allowCellularBackup", false);
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "INSERT INTO backup_settings(user_id,device_installation_id,auto_backup_enabled,"
      "allow_cellular_backup,updated_at) VALUES(?,?,?,?,?) "
      "ON CONFLICT(user_id,device_installation_id) DO UPDATE SET "
      "auto_backup_enabled=excluded.auto_backup_enabled,"
      "allow_cellular_backup=excluded.allow_cellular_backup,updated_at=excluded.updated_at";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int(statement, 3, auto_backup ? 1 : 0);
  if (status == SQLITE_OK) status = sqlite3_bind_int(statement, 4, cellular ? 1 : 0);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 5, updated_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  ++event_sequence_;
  emit_locked("{\"version\":1,\"type\":\"BackupSettingsChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"autoBackupEnabled\":" +
              (auto_backup ? "true" : "false") + ",\"allowCellularBackup\":" +
              (cellular ? "true" : "false") + "}");
  return MINEG_OK;
}

bool Core::execute_json_statement_locked(const char *sql, const std::string &json) {
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(statement, 1, json.c_str(), static_cast<int>(json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  return status == SQLITE_DONE;
}

bool Core::execute_json_update_locked(const char *sql, const std::string &json) {
  return execute_json_statement_locked(sql, json) && sqlite3_changes(database_) > 0;
}

mineg_error_code_t Core::apply_local_media_batch_locked(const std::string &command) {
  const std::string user_id = extract_json_string(command, "userId");
  const std::string scan_generation = extract_json_string(command, "scanGeneration");
  const std::string updated_at = extract_json_string(command, "updatedAt");
  if (user_id.empty() || scan_generation.empty() || updated_at.empty()) return MINEG_INVALID_ARGUMENT;
  sqlite3_stmt *validation = nullptr;
  if (sqlite3_prepare_v2(
          database_,
          "SELECT json_valid(?1),coalesce(json_array_length(?1,'$.media'),-1)",
          -1, &validation, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_text(validation, 1, command.c_str(), static_cast<int>(command.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(validation);
  const bool valid_json = status == SQLITE_ROW && sqlite3_column_int(validation, 0) == 1 &&
                          sqlite3_column_int(validation, 1) >= 0 &&
                          sqlite3_column_int(validation, 1) <= 500;
  sqlite3_finalize(validation);
  if (!valid_json) return MINEG_INVALID_ARGUMENT;

  if (sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  const auto fail = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return MINEG_DATABASE_ERROR;
  };
  if (!execute_json_statement_locked(
          "INSERT INTO local_albums(user_id,platform_album_ref,name,is_available,modified_at) "
          "SELECT json_extract(?1,'$.userId'), json_extract(item.value,'$.platformAlbumRef'), "
          "json_extract(item.value,'$.name'), 1, json_extract(?1,'$.updatedAt') "
          "FROM json_each(?1,'$.albums') item "
          "WHERE length(json_extract(item.value,'$.platformAlbumRef')) > 0 "
          "ON CONFLICT(user_id,platform_album_ref) DO UPDATE SET "
          "name=excluded.name,is_available=1,modified_at=excluded.modified_at",
          command)) return fail();
  if (!execute_json_statement_locked(
          "INSERT INTO local_media(user_id,platform_asset_ref,media_type,mime_type,width,height,"
          "duration_ms,captured_at,modified_at,modified_version,content_version,availability,"
          "thumbnail_uri,scan_generation) "
          "SELECT json_extract(?1,'$.userId'),json_extract(item.value,'$.platformAssetRef'),"
          "json_extract(item.value,'$.mediaType'),json_extract(item.value,'$.mimeType'),"
          "json_extract(item.value,'$.width'),json_extract(item.value,'$.height'),"
          "json_extract(item.value,'$.durationMs'),json_extract(item.value,'$.capturedAt'),"
          "json_extract(item.value,'$.modifiedAt'),json_extract(item.value,'$.modifiedVersion'),"
          "json_extract(item.value,'$.contentVersion'),json_extract(item.value,'$.availability'),"
          "json_extract(item.value,'$.thumbnailUri'),json_extract(?1,'$.scanGeneration') "
          "FROM json_each(?1,'$.media') item "
          "WHERE length(json_extract(item.value,'$.platformAssetRef')) > 0 "
          "ON CONFLICT(user_id,platform_asset_ref) DO UPDATE SET "
          "media_type=excluded.media_type,mime_type=excluded.mime_type,width=excluded.width,"
          "height=excluded.height,duration_ms=excluded.duration_ms,captured_at=excluded.captured_at,"
          "modified_at=excluded.modified_at,modified_version=excluded.modified_version,"
          "content_version=excluded.content_version,availability=excluded.availability,"
          "thumbnail_uri=excluded.thumbnail_uri,scan_generation=excluded.scan_generation",
          command)) return fail();
  if (!execute_json_statement_locked(
          "DELETE FROM local_media_albums WHERE user_id=json_extract(?1,'$.userId') AND "
          "platform_asset_ref IN (SELECT json_extract(value,'$.platformAssetRef') "
          "FROM json_each(?1,'$.media'))",
          command)) return fail();
  if (!execute_json_statement_locked(
          "INSERT OR IGNORE INTO local_media_albums(user_id,platform_asset_ref,platform_album_ref) "
          "SELECT json_extract(?1,'$.userId'),json_extract(item.value,'$.platformAssetRef'),"
          "json_extract(item.value,'$.platformAlbumRef') FROM json_each(?1,'$.relations') item",
          command)) return fail();
  if (extract_json_boolean(command, "complete", false)) {
    if (!execute_json_statement_locked(
            "UPDATE local_media SET availability='LOCAL_MISSING' "
            "WHERE user_id=json_extract(?1,'$.userId') AND scan_generation<>json_extract(?1,'$.scanGeneration')",
            command)) return fail();
    if (!execute_json_statement_locked(
            "UPDATE local_albums SET is_available=0,modified_at=json_extract(?1,'$.updatedAt') "
            "WHERE user_id=json_extract(?1,'$.userId') AND platform_album_ref NOT IN "
            "(SELECT DISTINCT relation.platform_album_ref FROM local_media_albums relation "
            "JOIN local_media media ON media.user_id=relation.user_id AND "
            "media.platform_asset_ref=relation.platform_asset_ref WHERE relation.user_id=json_extract(?1,'$.userId') "
            "AND media.scan_generation=json_extract(?1,'$.scanGeneration'))",
            command)) return fail();
  }
  if (!execute_json_statement_locked(
          "INSERT INTO local_scan_state(user_id,cursor_modified_version,cursor_asset_ref,status,"
          "indexed_count,scan_generation,updated_at) VALUES(json_extract(?1,'$.userId'),"
          "json_extract(?1,'$.cursorModifiedVersion'),json_extract(?1,'$.cursorAssetRef'),"
          "CASE WHEN json_extract(?1,'$.complete') THEN 'COMPLETE' ELSE 'SCANNING' END,"
          "(SELECT count(*) FROM local_media WHERE user_id=json_extract(?1,'$.userId') AND "
          "availability <> 'LOCAL_MISSING'),json_extract(?1,'$.scanGeneration'),"
          "json_extract(?1,'$.updatedAt')) ON CONFLICT(user_id) DO UPDATE SET "
          "cursor_modified_version=excluded.cursor_modified_version,"
          "cursor_asset_ref=excluded.cursor_asset_ref,status=excluded.status,"
          "indexed_count=excluded.indexed_count,scan_generation=excluded.scan_generation,"
          "updated_at=excluded.updated_at",
          command)) return fail();
  if (sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) return fail();
  ++event_sequence_;
  emit_locked("{\"version\":1,\"type\":\"LocalMediaIndexChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"complete\":" +
              (extract_json_boolean(command, "complete", false) ? "true" : "false") + "}");
  return MINEG_OK;
}

mineg_error_code_t Core::create_single_media_backup_locked(const std::string &command) {
  const std::string task_id = extract_json_string(command, "taskId");
  const std::string user_id = extract_json_string(command, "userId");
  const std::string asset_ref = extract_json_string(command, "platformAssetRef");
  const std::string content_version = extract_json_string(command, "contentVersion");
  const std::string media_type = extract_json_string(command, "mediaType");
  const std::string created_at = extract_json_string(command, "createdAt");
  if (task_id.empty() || user_id.empty() || asset_ref.empty() || content_version.empty() ||
      created_at.empty() || (media_type != "PHOTO" && media_type != "VIDEO" &&
                             media_type != "GIF" && media_type != "LIVE_PHOTO" &&
                             media_type != "DYNAMIC")) {
    return MINEG_INVALID_ARGUMENT;
  }
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "INSERT INTO backup_tasks(task_id,user_id,platform_asset_ref,content_version,media_type,"
      "state,created_at,updated_at) VALUES(?,?,?,?,?,'PREPARING',?,?) "
      "ON CONFLICT(user_id,platform_asset_ref,content_version) DO NOTHING";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_text(statement, 1, task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, asset_ref.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, content_version.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 5, media_type.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 6, created_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 7, created_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  const bool inserted = status == SQLITE_DONE && sqlite3_changes(database_) > 0;
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  if (!inserted) return MINEG_OK;
  ++event_sequence_;
  emit_locked("{\"version\":1,\"type\":\"SingleMediaBackupChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"taskId\":\"" + json_escape(task_id) +
              "\",\"state\":\"PREPARING\"}");
  return MINEG_OK;
}

mineg_error_code_t Core::record_prepared_media_locked(const std::string &command) {
  const std::string task_id = extract_json_string(command, "taskId");
  const std::string dedupe = extract_json_string(command, "dedupeFingerprint");
  const std::string media_key = extract_json_string(command, "encryptedMediaKey");
  const std::string encrypted_manifest = extract_json_string(command, "encryptedManifest");
  const std::string manifest_digest = extract_json_string(command, "manifestDigest");
  const std::string updated_at = extract_json_string(command, "updatedAt");
  if (task_id.empty() || dedupe.empty() || media_key.empty() || encrypted_manifest.empty() ||
      manifest_digest.empty() || updated_at.empty()) {
    return MINEG_INVALID_ARGUMENT;
  }
  sqlite3_stmt *validation = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT json_valid(?1),coalesce(json_array_length(?1,'$.resources'),-1)",
                         -1, &validation, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_text(validation, 1, command.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(validation);
  const int resource_count = status == SQLITE_ROW ? sqlite3_column_int(validation, 1) : -1;
  const bool valid = status == SQLITE_ROW && sqlite3_column_int(validation, 0) == 1 &&
                     resource_count >= 1 && resource_count <= 8;
  sqlite3_finalize(validation);
  if (!valid) return MINEG_INVALID_ARGUMENT;
  if (sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  const auto fail = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return MINEG_DATABASE_ERROR;
  };
  if (!execute_json_update_locked(
          "UPDATE backup_tasks SET state='PREPARED',dedupe_fingerprint=json_extract(?1,'$.dedupeFingerprint'),"
          "encrypted_media_key=json_extract(?1,'$.encryptedMediaKey'),"
          "encrypted_manifest=json_extract(?1,'$.encryptedManifest'),"
          "manifest_digest=json_extract(?1,'$.manifestDigest'),updated_at=json_extract(?1,'$.updatedAt') "
          "WHERE task_id=json_extract(?1,'$.taskId') AND state IN ('PREPARING','PREPARED','RETRYABLE_FAILED')",
          command)) {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return MINEG_INVALID_ARGUMENT;
  }
  if (!execute_json_statement_locked(
          "DELETE FROM backup_resources WHERE task_id=json_extract(?1,'$.taskId')", command)) {
    return fail();
  }
  if (!execute_json_statement_locked(
          "INSERT OR IGNORE INTO backup_resources(resource_id,task_id,resource_type,ciphertext_path,"
          "ciphertext_size,ciphertext_sha256,manifest_json) SELECT "
          "json_extract(item.value,'$.resourceId'),json_extract(?1,'$.taskId'),"
          "json_extract(item.value,'$.resourceType'),json_extract(item.value,'$.ciphertextPath'),"
          "json_extract(item.value,'$.ciphertextSize'),json_extract(item.value,'$.ciphertextSha256'),"
          "json(json_extract(item.value,'$.manifest')) FROM json_each(?1,'$.resources') item",
          command)) return fail();
  if (!execute_json_statement_locked(
          "INSERT OR IGNORE INTO backup_parts(resource_id,part_number,ciphertext_offset,ciphertext_size,"
          "ciphertext_sha256) SELECT json_extract(resource.value,'$.resourceId'),"
          "json_extract(part.value,'$.partNumber'),json_extract(part.value,'$.offset'),"
          "json_extract(part.value,'$.ciphertextSize'),json_extract(part.value,'$.ciphertextSha256') "
          "FROM json_each(?1,'$.resources') resource,json_each(resource.value,'$.parts') part",
          command)) return fail();
  if (sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) return fail();
  ++event_sequence_;
  emit_locked("{\"version\":1,\"type\":\"SingleMediaBackupChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"taskId\":\"" + json_escape(task_id) +
              "\",\"state\":\"PREPARED\"}");
  return MINEG_OK;
}

mineg_error_code_t Core::update_single_media_backup_locked(const std::string &command,
                                                           const std::string &type) {
  const std::string task_id = extract_json_string(command, "taskId");
  const std::string updated_at = extract_json_string(command, "updatedAt");
  if (task_id.empty() || updated_at.empty()) return MINEG_INVALID_ARGUMENT;
  bool success = false;
  std::string state;
  if (type == "RecordUploadSession") {
    const std::string upload_id = extract_json_string(command, "uploadId");
    if (upload_id.empty()) return MINEG_INVALID_ARGUMENT;
    success = execute_json_update_locked(
        "UPDATE backup_tasks SET state='UPLOADING',server_upload_id=json_extract(?1,'$.uploadId'),"
        "error_code=NULL,updated_at=json_extract(?1,'$.updatedAt') WHERE task_id=json_extract(?1,'$.taskId') "
        "AND state IN ('PREPARED','UPLOADING','SERVER_VERIFYING','RETRYABLE_FAILED')", command);
    state = "UPLOADING";
  } else if (type == "RecordUploadedPart") {
    const std::string resource_id = extract_json_string(command, "resourceId");
    const std::string etag = extract_json_string(command, "etag");
    const int64_t part_number = extract_json_integer(command, "partNumber");
    if (resource_id.empty() || etag.empty() || part_number < 1 || part_number > 10000) {
      return MINEG_INVALID_ARGUMENT;
    }
    if (sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
      return MINEG_DATABASE_ERROR;
    }
    success = execute_json_update_locked(
        "UPDATE backup_parts SET state='UPLOADED',etag=json_extract(?1,'$.etag') WHERE "
        "resource_id=json_extract(?1,'$.resourceId') AND part_number=json_extract(?1,'$.partNumber') "
        "AND (state='PENDING' OR etag=json_extract(?1,'$.etag'))", command) &&
        execute_json_update_locked(
            "UPDATE backup_tasks SET updated_at=json_extract(?1,'$.updatedAt') WHERE "
            "task_id=json_extract(?1,'$.taskId') AND state='UPLOADING'", command);
    if (!success || sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) {
      sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
      return success ? MINEG_DATABASE_ERROR : MINEG_INVALID_ARGUMENT;
    }
    state = "UPLOADING";
  } else if (type == "MarkServerVerifying") {
    success = execute_json_update_locked(
        "UPDATE backup_tasks SET state='SERVER_VERIFYING',updated_at=json_extract(?1,'$.updatedAt') "
        "WHERE task_id=json_extract(?1,'$.taskId') AND state IN ('UPLOADING','SERVER_VERIFYING') "
        "AND NOT EXISTS(SELECT 1 FROM backup_parts part JOIN backup_resources resource "
        "ON resource.resource_id=part.resource_id WHERE resource.task_id=json_extract(?1,'$.taskId') "
        "AND part.state<>'UPLOADED')", command);
    state = "SERVER_VERIFYING";
  } else if (type == "CompleteSingleMediaBackup") {
    const std::string media_id = extract_json_string(command, "serverMediaId");
    if (media_id.empty()) return MINEG_INVALID_ARGUMENT;
    success = execute_json_update_locked(
        "UPDATE backup_tasks SET state='COMPLETED',server_media_id=json_extract(?1,'$.serverMediaId'),"
        "error_code=NULL,updated_at=json_extract(?1,'$.updatedAt') WHERE "
        "task_id=json_extract(?1,'$.taskId') AND state IN ('SERVER_VERIFYING','COMPLETED')", command);
    state = "COMPLETED";
  } else if (type == "CompleteDeduplicatedSingleMediaBackup") {
    const std::string upload_id = extract_json_string(command, "serverUploadId");
    const std::string media_id = extract_json_string(command, "serverMediaId");
    if (upload_id.empty() || media_id.empty()) return MINEG_INVALID_ARGUMENT;
    success = execute_json_update_locked(
        "UPDATE backup_tasks SET state='COMPLETED',server_upload_id=json_extract(?1,'$.serverUploadId'),"
        "server_media_id=json_extract(?1,'$.serverMediaId'),error_code=NULL,"
        "updated_at=json_extract(?1,'$.updatedAt') WHERE task_id=json_extract(?1,'$.taskId') "
        "AND state IN ('PREPARED','UPLOADING','SERVER_VERIFYING','RETRYABLE_FAILED','COMPLETED')", command);
    state = "COMPLETED";
  } else {
    const std::string error_code = extract_json_string(command, "errorCode");
    const bool retryable = extract_json_boolean(command, "retryable", true);
    if (error_code.empty()) return MINEG_INVALID_ARGUMENT;
    success = execute_json_update_locked(
        retryable
            ? "UPDATE backup_tasks SET state='RETRYABLE_FAILED',error_code=json_extract(?1,'$.errorCode'),"
              "retry_count=retry_count+1,updated_at=json_extract(?1,'$.updatedAt') WHERE "
              "task_id=json_extract(?1,'$.taskId') AND state<>'COMPLETED'"
            : "UPDATE backup_tasks SET state='PERMANENT_FAILED',error_code=json_extract(?1,'$.errorCode'),"
              "updated_at=json_extract(?1,'$.updatedAt') WHERE task_id=json_extract(?1,'$.taskId') "
              "AND state<>'COMPLETED'",
        command);
    state = retryable ? "RETRYABLE_FAILED" : "PERMANENT_FAILED";
  }
  if (!success) return MINEG_INVALID_ARGUMENT;
  ++event_sequence_;
  emit_locked("{\"version\":1,\"type\":\"SingleMediaBackupChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"taskId\":\"" + json_escape(task_id) +
              "\",\"state\":\"" + state + "\"}");
  return MINEG_OK;
}

std::string Core::read_single_media_backup_locked(const std::string &query) {
  const std::string task_id = extract_json_string(query, "taskId");
  if (task_id.empty()) throw std::runtime_error("invalid backup query");
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT task_id,user_id,platform_asset_ref,content_version,media_type,state,"
      "server_upload_id,server_media_id,error_code,retry_count,created_at,updated_at,"
      "(SELECT count(*) FROM backup_parts part JOIN backup_resources resource ON "
      "resource.resource_id=part.resource_id WHERE resource.task_id=task.task_id),"
      "(SELECT count(*) FROM backup_parts part JOIN backup_resources resource ON "
      "resource.resource_id=part.resource_id WHERE resource.task_id=task.task_id "
      "AND part.state='UPLOADED'),dedupe_fingerprint,encrypted_media_key,encrypted_manifest,"
      "manifest_digest FROM backup_tasks task WHERE task_id=?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    return "{\"version\":1,\"task\":null}";
  }
  const auto text_at = [statement](int column) -> std::string {
    const auto *value = sqlite3_column_text(statement, column);
    return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
  };
  const auto nullable = [&text_at](int column) {
    const std::string value = text_at(column);
    return value.empty() ? std::string("null") : "\"" + json_escape(value) + "\"";
  };
  std::string result =
      "{\"version\":1,\"task\":{\"taskId\":\"" + json_escape(text_at(0)) +
      "\",\"userId\":\"" + json_escape(text_at(1)) + "\",\"platformAssetRef\":\"" +
      json_escape(text_at(2)) + "\",\"contentVersion\":\"" + json_escape(text_at(3)) +
      "\",\"mediaType\":\"" + json_escape(text_at(4)) + "\",\"state\":\"" +
      json_escape(text_at(5)) + "\",\"serverUploadId\":" + nullable(6) +
      ",\"serverMediaId\":" + nullable(7) + ",\"errorCode\":" + nullable(8) +
      ",\"retryCount\":" + std::to_string(sqlite3_column_int(statement, 9)) +
      ",\"createdAt\":\"" + json_escape(text_at(10)) + "\",\"updatedAt\":\"" +
      json_escape(text_at(11)) + "\",\"partCount\":" +
      std::to_string(sqlite3_column_int(statement, 12)) + ",\"uploadedParts\":" +
      std::to_string(sqlite3_column_int(statement, 13)) + ",\"recovery\":{"
      "\"dedupeFingerprint\":" + nullable(14) + ",\"encryptedMediaKey\":" + nullable(15) +
      ",\"encryptedManifest\":" + nullable(16) + ",\"manifestDigest\":" + nullable(17) +
      ",\"resources\":[";
  sqlite3_finalize(statement);
  const char *resource_sql =
      "SELECT resource.resource_id,resource.resource_type,resource.ciphertext_path,"
      "resource.ciphertext_size,resource.ciphertext_sha256,resource.manifest_json,"
      "part.part_number,part.ciphertext_offset,part.ciphertext_size,part.ciphertext_sha256,"
      "part.etag,part.state FROM backup_resources resource JOIN backup_parts part ON "
      "part.resource_id=resource.resource_id WHERE resource.task_id=? "
      "ORDER BY resource.resource_id,part.part_number";
  if (sqlite3_prepare_v2(database_, resource_sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, task_id.c_str(), -1, SQLITE_TRANSIENT);
  std::string current_resource;
  bool first_resource = true;
  bool first_part = true;
  while (sqlite3_step(statement) == SQLITE_ROW) {
    const auto resource_text = [statement](int column) -> std::string {
      const auto *value = sqlite3_column_text(statement, column);
      return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
    };
    const std::string resource_id = resource_text(0);
    if (resource_id != current_resource) {
      if (!current_resource.empty()) result += "]}";
      if (!first_resource) result += ',';
      first_resource = false;
      first_part = true;
      current_resource = resource_id;
      result += "{\"resourceId\":\"" + json_escape(resource_id) +
                "\",\"resourceType\":\"" + json_escape(resource_text(1)) +
                "\",\"ciphertextPath\":\"" + json_escape(resource_text(2)) +
                "\",\"ciphertextSize\":" + std::to_string(sqlite3_column_int64(statement, 3)) +
                ",\"ciphertextSha256\":\"" + json_escape(resource_text(4)) +
                "\",\"manifest\":" + resource_text(5) + ",\"parts\":[";
    }
    if (!first_part) result += ',';
    first_part = false;
    result += "{\"partNumber\":" + std::to_string(sqlite3_column_int(statement, 6)) +
              ",\"offset\":" + std::to_string(sqlite3_column_int64(statement, 7)) +
              ",\"ciphertextSize\":" + std::to_string(sqlite3_column_int64(statement, 8)) +
              ",\"ciphertextSha256\":\"" + json_escape(resource_text(9)) +
              "\",\"etag\":" + (resource_text(10).empty() ? "null" : "\"" + json_escape(resource_text(10)) + "\"") +
              ",\"state\":\"" + json_escape(resource_text(11)) + "\"}";
  }
  if (!current_resource.empty()) result += "]}";
  sqlite3_finalize(statement);
  result += "]}}}";
  return result;
}

std::string Core::read_backup_settings_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  const std::string device_id = extract_json_string(query, "deviceInstallationId");
  if (user_id.empty() || device_id.empty()) throw std::runtime_error("invalid settings query");
  sqlite3_stmt *statement = nullptr;
  const char *sql = "SELECT auto_backup_enabled,allow_cellular_backup,updated_at FROM backup_settings "
                    "WHERE user_id=? AND device_installation_id=?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    return "{\"version\":1,\"settings\":{\"autoBackupEnabled\":true,"
           "\"allowCellularBackup\":false,\"updatedAt\":null}}";
  }
  const bool auto_backup = sqlite3_column_int(statement, 0) == 1;
  const bool cellular = sqlite3_column_int(statement, 1) == 1;
  const auto *updated = sqlite3_column_text(statement, 2);
  const std::string updated_at = updated == nullptr ? "" : reinterpret_cast<const char *>(updated);
  sqlite3_finalize(statement);
  return "{\"version\":1,\"settings\":{\"autoBackupEnabled\":" +
         std::string(auto_backup ? "true" : "false") + ",\"allowCellularBackup\":" +
         (cellular ? "true" : "false") + ",\"updatedAt\":\"" + json_escape(updated_at) + "\"}}";
}

std::string Core::read_scan_state_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  if (user_id.empty()) throw std::runtime_error("invalid scan query");
  sqlite3_stmt *statement = nullptr;
  const char *sql = "SELECT cursor_modified_version,cursor_asset_ref,status,indexed_count,"
                    "scan_generation,updated_at FROM local_scan_state WHERE user_id=?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    return "{\"version\":1,\"state\":{\"cursorModifiedVersion\":0,\"cursorAssetRef\":\"\","
           "\"status\":\"IDLE\",\"indexedCount\":0,\"scanGeneration\":\"\",\"updatedAt\":null}}";
  }
  const auto text_at = [statement](int column) {
    const auto *value = sqlite3_column_text(statement, column);
    return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
  };
  const std::string result =
      "{\"version\":1,\"state\":{\"cursorModifiedVersion\":" +
      std::to_string(sqlite3_column_int64(statement, 0)) + ",\"cursorAssetRef\":\"" +
      json_escape(text_at(1)) + "\",\"status\":\"" + json_escape(text_at(2)) +
      "\",\"indexedCount\":" + std::to_string(sqlite3_column_int64(statement, 3)) +
      ",\"scanGeneration\":\"" + json_escape(text_at(4)) + "\",\"updatedAt\":" +
      (text_at(5).empty() ? "null" : "\"" + json_escape(text_at(5)) + "\"") + "}}";
  sqlite3_finalize(statement);
  return result;
}

std::string Core::list_local_albums_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  const std::string cursor_name = extract_json_string(query, "cursorName");
  const std::string cursor_ref = extract_json_string(query, "cursorAlbumRef");
  const int64_t requested = extract_json_integer(query, "limit", 50);
  const int limit = static_cast<int>(std::clamp<int64_t>(requested, 1, 100));
  if (user_id.empty()) throw std::runtime_error("invalid album query");
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT album.platform_album_ref,album.name,count(media.platform_asset_ref),"
      "(SELECT cover.thumbnail_uri FROM local_media_albums cover_relation "
      "JOIN local_media cover ON cover.user_id=cover_relation.user_id AND "
      "cover.platform_asset_ref=cover_relation.platform_asset_ref "
      "WHERE cover_relation.user_id=album.user_id AND cover_relation.platform_album_ref=album.platform_album_ref "
      "AND cover.availability<>'LOCAL_MISSING' ORDER BY cover.captured_at DESC,cover.platform_asset_ref DESC LIMIT 1) "
      "FROM local_albums album LEFT JOIN local_media_albums relation ON relation.user_id=album.user_id "
      "AND relation.platform_album_ref=album.platform_album_ref LEFT JOIN local_media media ON "
      "media.user_id=relation.user_id AND media.platform_asset_ref=relation.platform_asset_ref "
      "AND media.availability<>'LOCAL_MISSING' WHERE album.user_id=? AND album.is_available=1 "
      "AND (?='' OR (album.name,album.platform_album_ref)>(?,?)) "
      "GROUP BY album.platform_album_ref,album.name ORDER BY album.name,album.platform_album_ref LIMIT ?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 2, cursor_name.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 3, cursor_name.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 4, cursor_ref.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_int(statement, 5, limit + 1);
  std::string result = "{\"version\":1,\"items\":[";
  int count = 0;
  std::string last_name;
  std::string last_ref;
  bool has_more = false;
  while (sqlite3_step(statement) == SQLITE_ROW) {
    if (count == limit) {
      has_more = true;
      break;
    }
    const auto text_at = [statement](int column) {
      const auto *value = sqlite3_column_text(statement, column);
      return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
    };
    last_ref = text_at(0);
    last_name = text_at(1);
    if (count++ > 0) result += ',';
    result += "{\"platformAlbumRef\":\"" + json_escape(last_ref) + "\",\"name\":\"" +
              json_escape(last_name) + "\",\"mediaCount\":" +
              std::to_string(sqlite3_column_int64(statement, 2)) + ",\"coverThumbnailUri\":" +
              (text_at(3).empty() ? "null" : "\"" + json_escape(text_at(3)) + "\"") + "}";
  }
  sqlite3_finalize(statement);
  result += "],\"nextCursor\":";
  if (has_more) {
    result += "{\"name\":\"" + json_escape(last_name) + "\",\"platformAlbumRef\":\"" +
              json_escape(last_ref) + "\"}";
  } else {
    result += "null";
  }
  result += "}";
  return result;
}

std::string Core::list_local_media_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  const std::string album_ref = extract_json_string(query, "platformAlbumRef");
  const std::string cursor_time = extract_json_string(query, "cursorCapturedAt");
  const std::string cursor_ref = extract_json_string(query, "cursorAssetRef");
  const int64_t requested = extract_json_integer(query, "limit", 60);
  const int limit = static_cast<int>(std::clamp<int64_t>(requested, 1, 500));
  if (user_id.empty()) throw std::runtime_error("invalid media query");
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT media.platform_asset_ref,media.media_type,media.mime_type,media.width,media.height,"
      "media.duration_ms,media.captured_at,media.modified_at,media.content_version,media.availability,"
      "media.thumbnail_uri FROM local_media media WHERE media.user_id=? AND media.availability<>'LOCAL_MISSING' "
      "AND (?='' OR EXISTS(SELECT 1 FROM local_media_albums relation WHERE relation.user_id=media.user_id "
      "AND relation.platform_asset_ref=media.platform_asset_ref AND relation.platform_album_ref=?)) "
      "AND (?='' OR (media.captured_at,media.platform_asset_ref)<(?,?)) "
      "ORDER BY media.captured_at DESC,media.platform_asset_ref DESC LIMIT ?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 2, album_ref.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 3, album_ref.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 4, cursor_time.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 5, cursor_time.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 6, cursor_ref.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_int(statement, 7, limit + 1);
  std::string result = "{\"version\":1,\"items\":[";
  int count = 0;
  std::string last_time;
  std::string last_ref;
  bool has_more = false;
  while (sqlite3_step(statement) == SQLITE_ROW) {
    if (count == limit) {
      has_more = true;
      break;
    }
    const auto text_at = [statement](int column) {
      const auto *value = sqlite3_column_text(statement, column);
      return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
    };
    last_ref = text_at(0);
    last_time = text_at(6);
    if (count++ > 0) result += ',';
    result += "{\"platformAssetRef\":\"" + json_escape(last_ref) + "\",\"mediaType\":\"" +
              json_escape(text_at(1)) + "\",\"mimeType\":\"" + json_escape(text_at(2)) +
              "\",\"width\":" + std::to_string(sqlite3_column_int(statement, 3)) +
              ",\"height\":" + std::to_string(sqlite3_column_int(statement, 4)) +
              ",\"durationMs\":" + (sqlite3_column_type(statement, 5) == SQLITE_NULL
                                           ? std::string("null")
                                           : std::to_string(sqlite3_column_int64(statement, 5))) +
              ",\"capturedAt\":\"" + json_escape(last_time) + "\",\"modifiedAt\":\"" +
              json_escape(text_at(7)) + "\",\"contentVersion\":\"" + json_escape(text_at(8)) +
              "\",\"availability\":\"" + json_escape(text_at(9)) + "\",\"thumbnailUri\":" +
              (text_at(10).empty() ? "null" : "\"" + json_escape(text_at(10)) + "\"") + "}";
  }
  sqlite3_finalize(statement);
  result += "],\"nextCursor\":";
  if (has_more) {
    result += "{\"capturedAt\":\"" + json_escape(last_time) + "\",\"platformAssetRef\":\"" +
              json_escape(last_ref) + "\"}";
  } else {
    result += "null";
  }
  result += "}";
  return result;
}

mineg_error_code_t Core::query(const std::string &query, std::string &result) {
  std::lock_guard<std::mutex> lock(mutex_);
  const std::string type = extract_json_string(query, "type");
  try {
    if (type == "GetAccountState") {
      result = read_account_state_locked();
      return MINEG_OK;
    }
    if (type == "GetBackupSettings") {
      result = read_backup_settings_locked(query);
      return MINEG_OK;
    }
    if (type == "GetLocalScanState") {
      result = read_scan_state_locked(query);
      return MINEG_OK;
    }
    if (type == "ListLocalAlbums") {
      result = list_local_albums_locked(query);
      return MINEG_OK;
    }
    if (type == "ListLocalMedia") {
      result = list_local_media_locked(query);
      return MINEG_OK;
    }
    if (type == "GetSingleMediaBackup") {
      result = read_single_media_backup_locked(query);
      return MINEG_OK;
    }
    if (type != "FoundationReadProbe") return MINEG_NOT_FOUND;
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

mineg_error_code_t Core::unlock_user_key_bundle(
    const uint8_t *password, size_t password_size, const uint8_t public_key[MINEG_KEY_BYTES],
    const uint8_t *encrypted_bundle, size_t encrypted_bundle_size,
    const uint8_t device_wrap_key[MINEG_KEY_BYTES], std::string &device_unlock_blob) {
  constexpr std::array<uint8_t, 8> kBundleMagic = {'M', 'K', 'B', '0', '1', 0, 0, 0};
  constexpr std::array<uint8_t, 8> kDeviceMagic = {'M', 'U', 'K', '0', '1', 0, 0, 0};
  constexpr size_t kBundleBytes = kBundleMagic.size() + crypto_pwhash_SALTBYTES +
                                  crypto_aead_xchacha20poly1305_ietf_NPUBBYTES +
                                  2U * MINEG_KEY_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES;
  if (password == nullptr || password_size < 8 || password_size > 256 || public_key == nullptr ||
      encrypted_bundle == nullptr || encrypted_bundle_size != kBundleBytes ||
      device_wrap_key == nullptr ||
      !std::equal(kBundleMagic.begin(), kBundleMagic.end(), encrypted_bundle)) {
    return MINEG_INVALID_ARGUMENT;
  }
  {
    std::lock_guard<std::mutex> lock(mutex_);
    lock_keys_locked();
  }
  std::array<uint8_t, MINEG_KEY_BYTES> password_key{};
  std::array<uint8_t, 2U * MINEG_KEY_BYTES> plaintext{};
  std::array<uint8_t, MINEG_KEY_BYTES> derived_public{};
  const uint8_t *salt = encrypted_bundle + kBundleMagic.size();
  const uint8_t *nonce = salt + crypto_pwhash_SALTBYTES;
  const uint8_t *ciphertext = nonce + crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
  unsigned long long plaintext_size = 0;
  mineg_error_code_t result = MINEG_CRYPTO_ERROR;
  do {
    if (crypto_pwhash(password_key.data(), password_key.size(),
                      reinterpret_cast<const char *>(password), password_size, salt, 2,
                      64U * 1024U * 1024U, crypto_pwhash_ALG_ARGON2ID13) != 0) break;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(
            plaintext.data(), &plaintext_size, nullptr, ciphertext,
            2U * MINEG_KEY_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES,
            kBundleMagic.data(), kBundleMagic.size(), nonce, password_key.data()) != 0 ||
        plaintext_size != plaintext.size()) {
      result = MINEG_INTEGRITY_ERROR;
      break;
    }
    if (crypto_scalarmult_base(derived_public.data(), plaintext.data()) != 0 ||
        !std::equal(derived_public.begin(), derived_public.end(), public_key)) {
      result = MINEG_INTEGRITY_ERROR;
      break;
    }
    std::array<uint8_t, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES> device_nonce{};
    std::array<uint8_t, 2U * MINEG_KEY_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES>
        device_ciphertext{};
    randombytes_buf(device_nonce.data(), device_nonce.size());
    unsigned long long device_ciphertext_size = 0;
    if (crypto_aead_xchacha20poly1305_ietf_encrypt(
            device_ciphertext.data(), &device_ciphertext_size, plaintext.data(), plaintext.size(),
            kDeviceMagic.data(), kDeviceMagic.size(), nullptr, device_nonce.data(),
            device_wrap_key) != 0 || device_ciphertext_size != device_ciphertext.size()) break;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      lock_keys_locked();
      std::copy(public_key, public_key + MINEG_KEY_BYTES, user_public_key_.begin());
      std::copy(plaintext.begin(), plaintext.begin() + MINEG_KEY_BYTES, user_private_key_.begin());
      std::copy(plaintext.begin() + MINEG_KEY_BYTES, plaintext.end(), user_master_key_.begin());
      user_keys_unlocked_ = true;
    }
    device_unlock_blob.assign(reinterpret_cast<const char *>(kDeviceMagic.data()), kDeviceMagic.size());
    device_unlock_blob.append(reinterpret_cast<const char *>(device_nonce.data()), device_nonce.size());
    device_unlock_blob.append(reinterpret_cast<const char *>(device_ciphertext.data()),
                              device_ciphertext.size());
    sodium_memzero(device_ciphertext.data(), device_ciphertext.size());
    result = MINEG_OK;
  } while (false);
  sodium_memzero(password_key.data(), password_key.size());
  sodium_memzero(plaintext.data(), plaintext.size());
  sodium_memzero(derived_public.data(), derived_public.size());
  return result;
}

mineg_error_code_t Core::restore_user_key_bundle(
    const uint8_t public_key[MINEG_KEY_BYTES], const uint8_t device_wrap_key[MINEG_KEY_BYTES],
    const uint8_t *device_unlock_blob, size_t device_unlock_blob_size) {
  constexpr std::array<uint8_t, 8> kDeviceMagic = {'M', 'U', 'K', '0', '1', 0, 0, 0};
  constexpr size_t kBlobBytes = kDeviceMagic.size() + crypto_aead_xchacha20poly1305_ietf_NPUBBYTES +
                                2U * MINEG_KEY_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES;
  if (public_key == nullptr || device_wrap_key == nullptr || device_unlock_blob == nullptr ||
      device_unlock_blob_size != kBlobBytes ||
      !std::equal(kDeviceMagic.begin(), kDeviceMagic.end(), device_unlock_blob)) {
    return MINEG_INVALID_ARGUMENT;
  }
  {
    std::lock_guard<std::mutex> lock(mutex_);
    lock_keys_locked();
  }
  const uint8_t *nonce = device_unlock_blob + kDeviceMagic.size();
  const uint8_t *ciphertext = nonce + crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
  std::array<uint8_t, 2U * MINEG_KEY_BYTES> plaintext{};
  std::array<uint8_t, MINEG_KEY_BYTES> derived_public{};
  unsigned long long plaintext_size = 0;
  mineg_error_code_t result = MINEG_INTEGRITY_ERROR;
  if (crypto_aead_xchacha20poly1305_ietf_decrypt(
          plaintext.data(), &plaintext_size, nullptr, ciphertext,
          2U * MINEG_KEY_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES,
          kDeviceMagic.data(), kDeviceMagic.size(), nonce, device_wrap_key) == 0 &&
      plaintext_size == plaintext.size() &&
      crypto_scalarmult_base(derived_public.data(), plaintext.data()) == 0 &&
      std::equal(derived_public.begin(), derived_public.end(), public_key)) {
    std::lock_guard<std::mutex> lock(mutex_);
    lock_keys_locked();
    std::copy(public_key, public_key + MINEG_KEY_BYTES, user_public_key_.begin());
    std::copy(plaintext.begin(), plaintext.begin() + MINEG_KEY_BYTES, user_private_key_.begin());
    std::copy(plaintext.begin() + MINEG_KEY_BYTES, plaintext.end(), user_master_key_.begin());
    user_keys_unlocked_ = true;
    result = MINEG_OK;
  }
  sodium_memzero(plaintext.data(), plaintext.size());
  sodium_memzero(derived_public.data(), derived_public.size());
  return result;
}

mineg_error_code_t Core::unlock_family_key_envelope(const uint8_t *encrypted_envelope,
                                                     size_t encrypted_envelope_size) {
  if (encrypted_envelope == nullptr || encrypted_envelope_size != MINEG_FAMILY_KEY_ENVELOPE_BYTES) {
    return MINEG_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> lock(mutex_);
  if (!user_keys_unlocked_) return MINEG_NOT_FOUND;
  sodium_memzero(family_key_.data(), family_key_.size());
  family_key_unlocked_ = false;
  std::array<uint8_t, MINEG_KEY_BYTES> family_key{};
  if (crypto_box_seal_open(family_key.data(), encrypted_envelope, encrypted_envelope_size,
                           user_public_key_.data(), user_private_key_.data()) != 0) {
    sodium_memzero(family_key.data(), family_key.size());
    return MINEG_INTEGRITY_ERROR;
  }
  std::copy(family_key.begin(), family_key.end(), family_key_.begin());
  family_key_unlocked_ = true;
  sodium_memzero(family_key.data(), family_key.size());
  return MINEG_OK;
}

mineg_error_code_t Core::create_family_key_envelope(
    const uint8_t recipient_public_key[MINEG_KEY_BYTES], bool bootstrap_if_needed,
    std::string &encrypted_envelope) {
  if (recipient_public_key == nullptr) return MINEG_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(mutex_);
  if (!user_keys_unlocked_) return MINEG_NOT_FOUND;
  if (!family_key_unlocked_) {
    if (!bootstrap_if_needed) return MINEG_NOT_FOUND;
    randombytes_buf(family_key_.data(), family_key_.size());
    family_key_unlocked_ = true;
  }
  std::array<uint8_t, MINEG_FAMILY_KEY_ENVELOPE_BYTES> envelope{};
  if (crypto_box_seal(envelope.data(), family_key_.data(), family_key_.size(),
                      recipient_public_key) != 0) {
    return MINEG_CRYPTO_ERROR;
  }
  encrypted_envelope.assign(reinterpret_cast<const char *>(envelope.data()), envelope.size());
  sodium_memzero(envelope.data(), envelope.size());
  return MINEG_OK;
}

void Core::lock_keys_locked() {
  sodium_memzero(user_public_key_.data(), user_public_key_.size());
  sodium_memzero(user_private_key_.data(), user_private_key_.size());
  sodium_memzero(user_master_key_.data(), user_master_key_.size());
  sodium_memzero(family_key_.data(), family_key_.size());
  user_keys_unlocked_ = false;
  family_key_unlocked_ = false;
}

void Core::lock_keys() {
  std::lock_guard<std::mutex> lock(mutex_);
  lock_keys_locked();
}

mineg_error_code_t Core::create_media_key_envelope(const std::string &media_id,
                                                    std::string &encrypted_media_key) {
  if (media_id.empty() || media_id.size() > 128U) return MINEG_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(mutex_);
  if (!user_keys_unlocked_) return MINEG_NOT_FOUND;
  SensitiveBytes<MINEG_KEY_BYTES> media_key;
  std::array<unsigned char, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES> nonce{};
  std::array<unsigned char, MINEG_KEY_BYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES>
      ciphertext{};
  randombytes_buf(media_key.value.data(), media_key.value.size());
  randombytes_buf(nonce.data(), nonce.size());
  const std::string aad = "MINEG_MEDIA_KEY_V1\n" + media_id;
  unsigned long long ciphertext_size = 0;
  if (crypto_aead_xchacha20poly1305_ietf_encrypt(
          ciphertext.data(), &ciphertext_size, media_key.value.data(), media_key.value.size(),
          reinterpret_cast<const unsigned char *>(aad.data()), aad.size(), nullptr, nonce.data(),
          user_master_key_.data()) != 0 ||
      ciphertext_size != ciphertext.size()) {
    return MINEG_CRYPTO_ERROR;
  }
  encrypted_media_key.assign(reinterpret_cast<const char *>(kMediaKeyMagic.data()),
                             kMediaKeyMagic.size());
  encrypted_media_key.append(reinterpret_cast<const char *>(nonce.data()), nonce.size());
  encrypted_media_key.append(reinterpret_cast<const char *>(ciphertext.data()), ciphertext.size());
  return encrypted_media_key.size() == MINEG_MEDIA_KEY_ENVELOPE_BYTES ? MINEG_OK
                                                                      : MINEG_INTERNAL_ERROR;
}

mineg_error_code_t Core::compute_dedupe_fingerprint(int input_fd, const std::string &media_type,
                                                    std::string &fingerprint) {
  if (input_fd < 0 || media_type.empty() || media_type.size() > 64U) return MINEG_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(mutex_);
  if (!user_keys_unlocked_) return MINEG_NOT_FOUND;
  if (::lseek(input_fd, 0, SEEK_SET) < 0) return MINEG_INVALID_ARGUMENT;
  crypto_hash_sha256_state digest_state{};
  if (crypto_hash_sha256_init(&digest_state) != 0) return MINEG_CRYPTO_ERROR;
  const std::string domain = "MINEG_NORMALIZED_RESOURCE_V1\n" + media_type + "\n";
  if (crypto_hash_sha256_update(&digest_state,
                                reinterpret_cast<const unsigned char *>(domain.data()),
                                domain.size()) != 0) {
    return MINEG_CRYPTO_ERROR;
  }
  std::array<unsigned char, 256U * 1024U> buffer{};
  uint64_t total = 0;
  for (;;) {
    const ssize_t count = read_retry(input_fd, buffer.data(), buffer.size());
    if (count < 0) return MINEG_INTERNAL_ERROR;
    if (count == 0) break;
    total += static_cast<uint64_t>(count);
    if (crypto_hash_sha256_update(&digest_state, buffer.data(),
                                  static_cast<unsigned long long>(count)) != 0) {
      return MINEG_CRYPTO_ERROR;
    }
  }
  if (total == 0) return MINEG_INVALID_ARGUMENT;
  SensitiveBytes<crypto_hash_sha256_BYTES> content_digest;
  SensitiveBytes<MINEG_KEY_BYTES> dedupe_key;
  SensitiveBytes<crypto_auth_hmacsha256_BYTES> result;
  if (crypto_hash_sha256_final(&digest_state, content_digest.value.data()) != 0 ||
      !derive_hmac_key(user_master_key_.data(), "MINEG_DEDUPE_KEY_V1", dedupe_key.value.data()) ||
      crypto_auth_hmacsha256(result.value.data(), content_digest.value.data(),
                             content_digest.value.size(), dedupe_key.value.data()) != 0) {
    return MINEG_CRYPTO_ERROR;
  }
  fingerprint.assign(reinterpret_cast<const char *>(result.value.data()), result.value.size());
  return MINEG_OK;
}

mineg_error_code_t Core::encrypt_media_resource(
    int input_fd, const std::string &ciphertext_path, const std::string &media_id,
    const std::string &resource_id, const std::string &resource_type,
    const uint8_t *encrypted_media_key, size_t encrypted_media_key_size,
    std::string &resource_manifest_json) {
  if (input_fd < 0 || ciphertext_path.empty() || media_id.empty() || media_id.size() > 128U ||
      resource_id.empty() || resource_id.size() > 128U || resource_type.empty() ||
      resource_type.size() > 64U) {
    return MINEG_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> lock(mutex_);
  if (!user_keys_unlocked_) return MINEG_NOT_FOUND;
  if (::lseek(input_fd, 0, SEEK_SET) < 0) return MINEG_INVALID_ARGUMENT;
  SensitiveBytes<MINEG_KEY_BYTES> media_key;
  if (!unwrap_media_key(user_master_key_.data(), media_id, encrypted_media_key,
                        encrypted_media_key_size, media_key.value.data())) {
    return MINEG_INTEGRITY_ERROR;
  }
  SensitiveBytes<MINEG_KEY_BYTES> resource_key;
  if (!derive_hmac_key(media_key.value.data(),
                       "MINEG_RESOURCE_KEY_V1\n" + resource_id + "\n" + resource_type,
                       resource_key.value.data())) {
    return MINEG_CRYPTO_ERROR;
  }
  std::array<unsigned char, MINEG_MEDIA_NONCE_PREFIX_BYTES> nonce_prefix{};
  randombytes_buf(nonce_prefix.data(), nonce_prefix.size());
  const std::string partial_path = ciphertext_path + ".partial";
  std::ofstream output(partial_path, std::ios::binary | std::ios::trunc);
  if (!output.is_open()) return MINEG_INTERNAL_ERROR;
  std::vector<unsigned char> plaintext(kMediaChunkBytes);
  std::vector<unsigned char> ciphertext(kMediaChunkBytes + crypto_aead_xchacha20poly1305_ietf_ABYTES);
  crypto_hash_sha256_state resource_digest_state{};
  if (crypto_hash_sha256_init(&resource_digest_state) != 0) {
    output.close();
    std::filesystem::remove(partial_path);
    return MINEG_CRYPTO_ERROR;
  }
  std::string parts_json;
  uint64_t block_index = 0;
  uint64_t plaintext_total = 0;
  uint64_t ciphertext_total = 0;
  bool failed = false;
  for (;;) {
    size_t used = 0;
    while (used < plaintext.size()) {
      const ssize_t count = read_retry(input_fd, plaintext.data() + used, plaintext.size() - used);
      if (count < 0) {
        failed = true;
        break;
      }
      if (count == 0) break;
      used += static_cast<size_t>(count);
    }
    if (failed || used == 0) break;
    std::array<unsigned char, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES> nonce{};
    media_nonce(nonce_prefix.data(), block_index, nonce.data());
    const std::string aad = media_aad(media_id, resource_id, resource_type, block_index, used);
    unsigned long long encrypted_size = 0;
    if (crypto_aead_xchacha20poly1305_ietf_encrypt(
            ciphertext.data(), &encrypted_size, plaintext.data(), used,
            reinterpret_cast<const unsigned char *>(aad.data()), aad.size(), nullptr, nonce.data(),
            resource_key.value.data()) != 0 ||
        encrypted_size != used + crypto_aead_xchacha20poly1305_ietf_ABYTES) {
      failed = true;
      break;
    }
    std::array<unsigned char, crypto_hash_sha256_BYTES> part_digest{};
    if (crypto_hash_sha256(part_digest.data(), ciphertext.data(), encrypted_size) != 0 ||
        crypto_hash_sha256_update(&resource_digest_state, ciphertext.data(), encrypted_size) != 0 ||
        !write_all(output, ciphertext.data(), encrypted_size)) {
      failed = true;
      break;
    }
    if (!parts_json.empty()) parts_json += ',';
    parts_json += "{\"partNumber\":" + std::to_string(block_index + 1U) +
                  ",\"offset\":" + std::to_string(ciphertext_total) +
                  ",\"ciphertextSize\":" + std::to_string(encrypted_size) +
                  ",\"ciphertextSha256\":\"" + hex_encode(part_digest.data(), part_digest.size()) +
                  "\"}";
    plaintext_total += used;
    ciphertext_total += encrypted_size;
    ++block_index;
  }
  sodium_memzero(plaintext.data(), plaintext.size());
  if (failed || block_index == 0 || !output.good()) {
    output.close();
    std::filesystem::remove(partial_path);
    return failed ? MINEG_CRYPTO_ERROR : MINEG_INVALID_ARGUMENT;
  }
  output.close();
  std::array<unsigned char, crypto_hash_sha256_BYTES> resource_digest{};
  if (crypto_hash_sha256_final(&resource_digest_state, resource_digest.data()) != 0) {
    std::filesystem::remove(partial_path);
    return MINEG_CRYPTO_ERROR;
  }
  std::error_code rename_error;
  std::filesystem::rename(partial_path, ciphertext_path, rename_error);
  if (rename_error) {
    std::filesystem::remove(partial_path);
    return MINEG_INTERNAL_ERROR;
  }
  resource_manifest_json =
      "{\"formatVersion\":1,\"cipher\":\"XCHACHA20_POLY1305\",\"logicalBlockBytes\":" +
      std::to_string(kMediaChunkBytes) + ",\"mediaId\":\"" + json_escape(media_id) +
      "\",\"resourceId\":\"" + json_escape(resource_id) + "\",\"resourceType\":\"" +
      json_escape(resource_type) + "\",\"noncePrefix\":\"" +
      hex_encode(nonce_prefix.data(), nonce_prefix.size()) + "\",\"plaintextSize\":" +
      std::to_string(plaintext_total) + ",\"ciphertextSize\":" +
      std::to_string(ciphertext_total) + ",\"ciphertextSha256\":\"" +
      hex_encode(resource_digest.data(), resource_digest.size()) + "\",\"parts\":[" + parts_json +
      "]}";
  return MINEG_OK;
}

mineg_error_code_t Core::encrypt_media_manifest(
    const std::string &media_id, const uint8_t *manifest_json, size_t manifest_json_size,
    const uint8_t *encrypted_media_key, size_t encrypted_media_key_size,
    std::string &encrypted_manifest) {
  if (media_id.empty() || media_id.size() > 128U || manifest_json == nullptr ||
      manifest_json_size == 0 || manifest_json_size > 1024U * 1024U) {
    return MINEG_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> lock(mutex_);
  if (!user_keys_unlocked_) return MINEG_NOT_FOUND;
  SensitiveBytes<MINEG_KEY_BYTES> media_key;
  if (!unwrap_media_key(user_master_key_.data(), media_id, encrypted_media_key,
                        encrypted_media_key_size, media_key.value.data())) {
    return MINEG_INTEGRITY_ERROR;
  }
  SensitiveBytes<MINEG_KEY_BYTES> manifest_key;
  if (!derive_hmac_key(media_key.value.data(), "MINEG_MANIFEST_KEY_V1\n" + media_id,
                       manifest_key.value.data())) {
    return MINEG_CRYPTO_ERROR;
  }
  std::array<unsigned char, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES> nonce{};
  randombytes_buf(nonce.data(), nonce.size());
  std::vector<unsigned char> ciphertext(manifest_json_size +
                                        crypto_aead_xchacha20poly1305_ietf_ABYTES);
  const std::string aad = "MINEG_MANIFEST_V1\n" + media_id;
  unsigned long long ciphertext_size = 0;
  if (crypto_aead_xchacha20poly1305_ietf_encrypt(
          ciphertext.data(), &ciphertext_size, manifest_json, manifest_json_size,
          reinterpret_cast<const unsigned char *>(aad.data()), aad.size(), nullptr, nonce.data(),
          manifest_key.value.data()) != 0 ||
      ciphertext_size != ciphertext.size()) {
    return MINEG_CRYPTO_ERROR;
  }
  encrypted_manifest.assign(reinterpret_cast<const char *>(kManifestMagic.data()),
                            kManifestMagic.size());
  encrypted_manifest.append(reinterpret_cast<const char *>(nonce.data()), nonce.size());
  encrypted_manifest.append(reinterpret_cast<const char *>(ciphertext.data()), ciphertext.size());
  sodium_memzero(ciphertext.data(), ciphertext.size());
  return MINEG_OK;
}

mineg_error_code_t Core::decrypt_media_resource(
    const std::string &ciphertext_path, const std::string &plaintext_path,
    const std::string &media_id, const std::string &resource_id,
    const std::string &resource_type, uint64_t plaintext_size,
    const uint8_t nonce_prefix[MINEG_MEDIA_NONCE_PREFIX_BYTES],
    const uint8_t *encrypted_media_key, size_t encrypted_media_key_size) {
  if (ciphertext_path.empty() || plaintext_path.empty() || media_id.empty() || resource_id.empty() ||
      resource_type.empty() || plaintext_size == 0 || nonce_prefix == nullptr) {
    return MINEG_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> lock(mutex_);
  if (!user_keys_unlocked_) return MINEG_NOT_FOUND;
  SensitiveBytes<MINEG_KEY_BYTES> media_key;
  if (!unwrap_media_key(user_master_key_.data(), media_id, encrypted_media_key,
                        encrypted_media_key_size, media_key.value.data())) {
    return MINEG_INTEGRITY_ERROR;
  }
  SensitiveBytes<MINEG_KEY_BYTES> resource_key;
  if (!derive_hmac_key(media_key.value.data(),
                       "MINEG_RESOURCE_KEY_V1\n" + resource_id + "\n" + resource_type,
                       resource_key.value.data())) {
    return MINEG_CRYPTO_ERROR;
  }
  std::ifstream input(ciphertext_path, std::ios::binary);
  const std::string partial_path = plaintext_path + ".partial";
  std::ofstream output(partial_path, std::ios::binary | std::ios::trunc);
  if (!input.is_open() || !output.is_open()) {
    std::filesystem::remove(partial_path);
    return MINEG_INTERNAL_ERROR;
  }
  std::vector<unsigned char> encrypted(kMediaChunkBytes +
                                       crypto_aead_xchacha20poly1305_ietf_ABYTES);
  std::vector<unsigned char> plaintext(kMediaChunkBytes);
  uint64_t remaining = plaintext_size;
  uint64_t block_index = 0;
  bool failed = false;
  while (remaining > 0) {
    const size_t clear_size = static_cast<size_t>(std::min<uint64_t>(remaining, kMediaChunkBytes));
    const size_t encrypted_size = clear_size + crypto_aead_xchacha20poly1305_ietf_ABYTES;
    input.read(reinterpret_cast<char *>(encrypted.data()), static_cast<std::streamsize>(encrypted_size));
    if (static_cast<size_t>(input.gcount()) != encrypted_size) {
      failed = true;
      break;
    }
    std::array<unsigned char, crypto_aead_xchacha20poly1305_ietf_NPUBBYTES> nonce{};
    media_nonce(nonce_prefix, block_index, nonce.data());
    const std::string aad = media_aad(media_id, resource_id, resource_type, block_index, clear_size);
    unsigned long long decrypted_size = 0;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(
            plaintext.data(), &decrypted_size, nullptr, encrypted.data(), encrypted_size,
            reinterpret_cast<const unsigned char *>(aad.data()), aad.size(), nonce.data(),
            resource_key.value.data()) != 0 ||
        decrypted_size != clear_size || !write_all(output, plaintext.data(), clear_size)) {
      failed = true;
      break;
    }
    remaining -= clear_size;
    ++block_index;
  }
  char extra = 0;
  if (!failed && input.read(&extra, 1)) failed = true;
  sodium_memzero(plaintext.data(), plaintext.size());
  output.close();
  input.close();
  if (failed) {
    std::filesystem::remove(partial_path);
    return MINEG_INTEGRITY_ERROR;
  }
  std::error_code rename_error;
  std::filesystem::rename(partial_path, plaintext_path, rename_error);
  if (rename_error) {
    std::filesystem::remove(partial_path);
    return MINEG_INTERNAL_ERROR;
  }
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
