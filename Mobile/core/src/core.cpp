#include "core.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>
#include <limits>

#include <fcntl.h>
#include <unistd.h>

#include "sodium_compat.h"

namespace mineg {
namespace {

constexpr size_t kMediaChunkBytes = 4U * 1024U * 1024U;
constexpr int64_t kMinimumBackupFreeBytes = 64LL * 1024LL * 1024LL;

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

std::string extract_top_level_json_value(const std::string &json, const std::string &field) {
  size_t index = json.find_first_not_of(" \t\r\n");
  if (index == std::string::npos || json[index] != '{') return {};
  ++index;
  while (index < json.size()) {
    index = json.find_first_not_of(" \t\r\n,", index);
    if (index == std::string::npos || json[index] == '}') return {};
    if (json[index] != '"') return {};
    const size_t key_start = ++index;
    bool escaped = false;
    while (index < json.size()) {
      const char character = json[index];
      if (!escaped && character == '"') break;
      escaped = !escaped && character == '\\';
      if (character != '\\') escaped = false;
      ++index;
    }
    if (index >= json.size()) return {};
    const std::string key = json.substr(key_start, index - key_start);
    index = json.find(':', index + 1U);
    if (index == std::string::npos) return {};
    index = json.find_first_not_of(" \t\r\n", index + 1U);
    if (index == std::string::npos) return {};
    const size_t value_start = index;
    if (json[index] == '"') {
      ++index;
      escaped = false;
      while (index < json.size()) {
        const char character = json[index];
        if (!escaped && character == '"') {
          ++index;
          break;
        }
        escaped = !escaped && character == '\\';
        if (character != '\\') escaped = false;
        ++index;
      }
    } else if (json[index] == '{' || json[index] == '[') {
      const char opening = json[index];
      const char closing = opening == '{' ? '}' : ']';
      int depth = 0;
      bool in_string = false;
      escaped = false;
      while (index < json.size()) {
        const char character = json[index++];
        if (in_string) {
          if (!escaped && character == '"') in_string = false;
          escaped = !escaped && character == '\\';
          if (character != '\\') escaped = false;
          continue;
        }
        if (character == '"') in_string = true;
        else if (character == opening) ++depth;
        else if (character == closing && --depth == 0) break;
      }
      if (depth != 0 || in_string) return {};
    } else {
      index = json.find_first_of(",}", index);
      if (index == std::string::npos) return {};
    }
    size_t value_end = index;
    while (value_end > value_start &&
           std::string(" \t\r\n").find(json[value_end - 1U]) != std::string::npos) {
      --value_end;
    }
    if (key == field) return json.substr(value_start, value_end - value_start);
    index = json.find_first_not_of(" \t\r\n", index);
    if (index != std::string::npos && json[index] == ',') ++index;
  }
  return {};
}

std::string top_level_json_string(const std::string &json, const std::string &field) {
  const std::string value = extract_top_level_json_value(json, field);
  if (value.size() < 2U || value.front() != '"' || value.back() != '"' ||
      value.find('\\') != std::string::npos) {
    return {};
  }
  return value.substr(1U, value.size() - 2U);
}

uint64_t top_level_json_u64(const std::string &json, const std::string &field) {
  const std::string value = extract_top_level_json_value(json, field);
  if (value.empty() || value.find_first_not_of("0123456789") != std::string::npos) return 0;
  try {
    return std::stoull(value);
  } catch (...) {
    return 0;
  }
}

bool supported_effect_type(const std::string &effect_type) {
  return effect_type == "TransportEffect" || effect_type == "SecureStoreEffect" ||
         effect_type == "MediaSourceEffect" || effect_type == "FileEffect" ||
         effect_type == "ConnectivityEffect" || effect_type == "MediaPlaybackEffect" ||
         effect_type == "SystemAlbumEffect";
}

bool valid_json(sqlite3 *database, const std::string &json) {
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database, "SELECT json_valid(?)", -1, &statement, nullptr) != SQLITE_OK) {
    return false;
  }
  int status = sqlite3_bind_text(statement, 1, json.c_str(), static_cast<int>(json.size()),
                                 SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  const bool result = status == SQLITE_ROW && sqlite3_column_int(statement, 0) == 1;
  sqlite3_finalize(statement);
  return result;
}

std::string operation_step_json(uint64_t operation_id, uint64_t sequence,
                                const std::string &status, const std::string &effect_type,
                                const std::string &effect_payload,
                                const std::string &terminal_payload) {
  std::string result = "{\"contractVersion\":\"foundation-v2\",\"operationId\":" +
                       std::to_string(operation_id) + ",\"sequence\":" +
                       std::to_string(sequence) + ",\"status\":\"" + status + "\"";
  if (status == "WAITING_FOR_EFFECT") {
    result += ",\"effect\":{\"contractVersion\":\"foundation-v2\",\"operationId\":" +
              std::to_string(operation_id) + ",\"sequence\":" + std::to_string(sequence) +
              ",\"effectType\":\"" + effect_type + "\",\"payload\":" + effect_payload + "}";
  } else if (status == "COMPLETED") {
    result += ",\"result\":" + (terminal_payload.empty() ? "null" : terminal_payload);
  } else if (status == "FAILED") {
    result += ",\"error\":" + (terminal_payload.empty() ?
        "{\"code\":\"PLATFORM_EFFECT_FAILED\",\"retryable\":false}" : terminal_payload);
  }
  result += "}";
  return result;
}

std::string hex_encode(const unsigned char *bytes, size_t size);

std::string sqlite_json_text(sqlite3 *database, const std::string &json,
                             const std::string &path) {
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database, "SELECT json_extract(?,?)", -1, &statement, nullptr) !=
      SQLITE_OK) {
    return {};
  }
  int status = sqlite3_bind_text(statement, 1, json.c_str(), static_cast<int>(json.size()),
                                 SQLITE_TRANSIENT);
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 2, path.c_str(), static_cast<int>(path.size()),
                               SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  std::string result;
  if (status == SQLITE_ROW && sqlite3_column_type(statement, 0) != SQLITE_NULL) {
    const auto *value = sqlite3_column_text(statement, 0);
    if (value != nullptr) result = reinterpret_cast<const char *>(value);
  }
  sqlite3_finalize(statement);
  return result;
}

int64_t sqlite_json_integer(sqlite3 *database, const std::string &json,
                            const std::string &path, int64_t fallback = 0) {
  const std::string value = sqlite_json_text(database, json, path);
  if (value.empty()) return fallback;
  try {
    return std::stoll(value);
  } catch (...) {
    return fallback;
  }
}

bool sqlite_json_boolean(sqlite3 *database, const std::string &json, const std::string &path,
                         bool fallback = false) {
  const std::string value = sqlite_json_text(database, json, path);
  if (value == "1" || value == "true") return true;
  if (value == "0" || value == "false") return false;
  return fallback;
}

std::string base64_encode(const uint8_t *bytes, size_t size, bool padded = true) {
  static constexpr char kAlphabet[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string result;
  result.reserve(((size + 2U) / 3U) * 4U);
  for (size_t index = 0; index < size; index += 3U) {
    const uint32_t value = static_cast<uint32_t>(bytes[index]) << 16U |
        (index + 1U < size ? static_cast<uint32_t>(bytes[index + 1U]) << 8U : 0U) |
        (index + 2U < size ? static_cast<uint32_t>(bytes[index + 2U]) : 0U);
    result += kAlphabet[(value >> 18U) & 0x3fU];
    result += kAlphabet[(value >> 12U) & 0x3fU];
    if (index + 1U < size) result += kAlphabet[(value >> 6U) & 0x3fU];
    else if (padded) result += '=';
    if (index + 2U < size) result += kAlphabet[value & 0x3fU];
    else if (padded) result += '=';
  }
  return result;
}

std::string base64_encode(const std::string &value, bool padded = true) {
  return base64_encode(reinterpret_cast<const uint8_t *>(value.data()), value.size(), padded);
}

bool base64_decode(const std::string &encoded, std::string &result) {
  const auto decode = [](char value) -> int {
    if (value >= 'A' && value <= 'Z') return value - 'A';
    if (value >= 'a' && value <= 'z') return value - 'a' + 26;
    if (value >= '0' && value <= '9') return value - '0' + 52;
    if (value == '+') return 62;
    if (value == '/') return 63;
    return -1;
  };
  if (encoded.empty()) {
    result.clear();
    return true;
  }
  if (encoded.size() % 4U == 1U) return false;
  result.clear();
  result.reserve(encoded.size() / 4U * 3U);
  uint32_t accumulator = 0;
  int bits = 0;
  bool padding = false;
  for (const char character : encoded) {
    if (character == '=') {
      padding = true;
      continue;
    }
    if (padding) return false;
    const int value = decode(character);
    if (value < 0) return false;
    accumulator = (accumulator << 6U) | static_cast<uint32_t>(value);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      result.push_back(static_cast<char>((accumulator >> bits) & 0xffU));
    }
  }
  return true;
}

void wipe_string(std::string &value) {
  if (!value.empty()) sodium_memzero(value.data(), value.size());
  value.clear();
  value.shrink_to_fit();
}

std::string rfc3339_at(std::chrono::system_clock::time_point now) {
  const auto seconds = std::chrono::time_point_cast<std::chrono::seconds>(now);
  const auto milliseconds =
      std::chrono::duration_cast<std::chrono::milliseconds>(now - seconds).count();
  const std::time_t value = std::chrono::system_clock::to_time_t(now);
  std::tm utc{};
#if defined(_WIN32)
  gmtime_s(&utc, &value);
#else
  gmtime_r(&value, &utc);
#endif
  std::ostringstream output;
  output << std::put_time(&utc, "%Y-%m-%dT%H:%M:%S") << '.' << std::setw(3)
         << std::setfill('0') << milliseconds << 'Z';
  return output.str();
}

std::string now_rfc3339() { return rfc3339_at(std::chrono::system_clock::now()); }

std::string rfc3339_after_seconds(int64_t delay_seconds) {
  return rfc3339_at(std::chrono::system_clock::now() +
                    std::chrono::seconds(std::max<int64_t>(0, delay_seconds)));
}

int64_t backup_retry_delay_seconds(int retry_count) {
  constexpr int64_t kBaseSeconds = 5;
  constexpr int64_t kMaximumSeconds = 15 * 60;
  int64_t upper_bound = kBaseSeconds;
  for (int index = 0; index < std::min(retry_count, 16) && upper_bound < kMaximumSeconds; ++index) {
    upper_bound = std::min(kMaximumSeconds, upper_bound * 2);
  }
  // Full jitter prevents a fleet of resumed queues from retrying in lock-step.
  std::array<unsigned char, sizeof(uint32_t)> random_bytes{};
  randombytes_buf(random_bytes.data(), random_bytes.size());
  uint32_t random = 0;
  std::memcpy(&random, random_bytes.data(), sizeof(random));
  sodium_memzero(random_bytes.data(), random_bytes.size());
  return kBaseSeconds + static_cast<int64_t>(random % static_cast<uint32_t>(
      upper_bound - kBaseSeconds + 1));
}

bool rfc3339_is_after(const std::string &value, std::chrono::seconds margin) {
  if (value.size() < 20U || (value[19] != 'Z' && value[19] != '.')) return false;
  std::tm utc{};
  std::istringstream input(value.substr(0, 19U));
  input >> std::get_time(&utc, "%Y-%m-%dT%H:%M:%S");
  if (input.fail()) return false;
#if defined(_WIN32)
  const std::time_t expiry = _mkgmtime(&utc);
#else
  const std::time_t expiry = timegm(&utc);
#endif
  return expiry > std::chrono::system_clock::to_time_t(std::chrono::system_clock::now() + margin);
}

std::string random_identifier() {
  std::array<uint8_t, 16> bytes{};
  randombytes_buf(bytes.data(), bytes.size());
  std::string value = hex_encode(bytes.data(), bytes.size());
  sodium_memzero(bytes.data(), bytes.size());
  return value;
}

std::string random_uuid() {
  std::array<uint8_t, 16> bytes{};
  randombytes_buf(bytes.data(), bytes.size());
  bytes[6] = static_cast<uint8_t>((bytes[6] & 0x0fU) | 0x40U);
  bytes[8] = static_cast<uint8_t>((bytes[8] & 0x3fU) | 0x80U);
  const std::string hex = hex_encode(bytes.data(), bytes.size());
  sodium_memzero(bytes.data(), bytes.size());
  return hex.substr(0, 8) + "-" + hex.substr(8, 4) + "-" + hex.substr(12, 4) + "-" +
      hex.substr(16, 4) + "-" + hex.substr(20, 12);
}

int64_t json_array_length(sqlite3 *database, const std::string &json, const std::string &path) {
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database, "SELECT json_array_length(?,?)", -1, &statement, nullptr) != SQLITE_OK) return -1;
  int status = sqlite3_bind_text(statement, 1, json.c_str(), static_cast<int>(json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, path.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  const int64_t value = status == SQLITE_ROW ? sqlite3_column_int64(statement, 0) : -1;
  sqlite3_finalize(statement);
  return value;
}

std::string normalize_phone(const std::string &value) {
  size_t first = value.find_first_not_of(" \t\r\n");
  size_t last = value.find_last_not_of(" \t\r\n");
  if (first == std::string::npos) return {};
  std::string phone = value.substr(first, last - first + 1U);
  if (phone.rfind("+86", 0) == 0) phone.erase(0, 3U);
  if (phone.size() != 11U || phone[0] != '1' || phone[1] < '3' || phone[1] > '9' ||
      phone.find_first_not_of("0123456789") != std::string::npos) {
    return {};
  }
  return "+86" + phone;
}

std::string masked_phone(const std::string &normalized) {
  return normalized.size() == 14U ? normalized.substr(3U, 3U) + "****" +
      normalized.substr(normalized.size() - 4U) : "***********";
}

bool valid_password(const std::string &password) {
  if (password.size() < 8U || password.size() > 64U) return false;
  bool letter = false;
  bool digit = false;
  for (const unsigned char value : password) {
    if (value < 0x20U || value == 0x7fU) return false;
    letter = letter || (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') ||
             value >= 0x80U;
    digit = digit || (value >= '0' && value <= '9');
  }
  return letter && digit;
}

std::string command_digest(const std::string &command) {
  std::array<uint8_t, crypto_hash_sha256_BYTES> digest{};
  if (crypto_hash_sha256(digest.data(), reinterpret_cast<const uint8_t *>(command.data()),
                         command.size()) != 0) {
    return {};
  }
  const std::string result = hex_encode(digest.data(), digest.size());
  sodium_memzero(digest.data(), digest.size());
  return result;
}

std::string secure_result_value(sqlite3 *database, const std::string &effect_result,
                                const std::string &name) {
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT json_extract(value,'$.valueBase64') FROM json_each(?, '$.payload.values') "
      "WHERE json_extract(value,'$.name')=? LIMIT 1";
  if (sqlite3_prepare_v2(database, sql, -1, &statement, nullptr) != SQLITE_OK) return {};
  int status = sqlite3_bind_text(statement, 1, effect_result.c_str(),
                                 static_cast<int>(effect_result.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 2, name.c_str(), static_cast<int>(name.size()),
                               SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  std::string encoded;
  if (status == SQLITE_ROW && sqlite3_column_type(statement, 0) != SQLITE_NULL) {
    const auto *value = sqlite3_column_text(statement, 0);
    if (value != nullptr) encoded = reinterpret_cast<const char *>(value);
  }
  sqlite3_finalize(statement);
  std::string decoded;
  return !encoded.empty() && base64_decode(encoded, decoded) ? decoded : std::string{};
}

std::string hex_encode(const unsigned char *bytes, size_t size) {
  static constexpr char kHex[] = "0123456789abcdef";
  std::string result(size * 2U, '0');
  for (size_t index = 0; index < size; ++index) {
    result[index * 2U] = kHex[(bytes[index] >> 4U) & 0x0fU];
    result[index * 2U + 1U] = kHex[bytes[index] & 0x0fU];
  }
  return result;
}

struct LocalMediaMapping {
  std::string platform_asset_ref;
  std::string source_uri;
};

LocalMediaMapping find_local_media_mapping(sqlite3 *database, const std::string &user_id,
                                           const std::string &cloud_media_id) {
  if (database == nullptr || user_id.empty() || cloud_media_id.empty()) return {};
  sqlite3_stmt *statement = nullptr;
  const char *sql = R"SQL(
    WITH mapping(platform_asset_ref,content_version,priority,updated_at) AS (
      SELECT task.platform_asset_ref,task.content_version,0,task.updated_at
      FROM backup_tasks task
      WHERE task.user_id=?1 AND task.server_media_id=?2 AND task.state='COMPLETED'
      UNION ALL
      SELECT receipt.platform_asset_ref,NULL,1,receipt.updated_at
      FROM download_receipts receipt
      JOIN private_media_items_v2 item ON item.user_id=receipt.user_id
        AND item.media_id=receipt.cloud_media_id
        AND item.content_revision=receipt.content_revision
      WHERE receipt.user_id=?1 AND receipt.cloud_media_id=?2
    )
    SELECT media.platform_asset_ref,media.thumbnail_uri
    FROM mapping
    JOIN local_library_active active ON active.user_id=?1
    JOIN local_media media ON media.user_id=?1
      AND media.generation_id=active.generation_id
      AND media.platform_asset_ref=mapping.platform_asset_ref
    WHERE media.availability='AVAILABLE'
      AND (mapping.priority=1 OR mapping.content_version=media.content_version)
    ORDER BY mapping.priority,mapping.updated_at DESC
    LIMIT 1
  )SQL";
  if (sqlite3_prepare_v2(database, sql, -1, &statement, nullptr) != SQLITE_OK) return {};
  int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 2, cloud_media_id.c_str(), -1, SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  LocalMediaMapping mapping;
  if (status == SQLITE_ROW) {
    const auto *asset_ref = sqlite3_column_text(statement, 0);
    const auto *source_uri = sqlite3_column_text(statement, 1);
    if (asset_ref != nullptr) mapping.platform_asset_ref = reinterpret_cast<const char *>(asset_ref);
    if (source_uri != nullptr) mapping.source_uri = reinterpret_cast<const char *>(source_uri);
  }
  sqlite3_finalize(statement);
  return mapping;
}

}  // namespace

struct Core::ActiveAccountSession {
  std::string user_id;
  std::string access_token;
  std::string access_expires_at;
  std::string refresh_token;
  std::string refresh_expires_at;
  std::string approval_status;
  std::string next_step;

  ~ActiveAccountSession() {
    wipe_string(access_token);
    wipe_string(refresh_token);
  }
};

struct Core::AccountOperation {
  uint64_t operation_id = 0;
  uint64_t sequence = 1;
  std::string command_digest;
  std::string contract_version;
  std::string type;
  std::string stage;
  std::string status = "WAITING_FOR_EFFECT";
  std::string effect_type;
  std::string effect_payload;
  std::string terminal_payload;
  std::string last_effect_result;
  std::string continuation;
  std::string phone;
  std::string masked_phone;
  std::string password;
  std::string idempotency_key;
  std::string nickname;
  std::string device_installation_id;
  std::string user_id;
  std::string access_token;
  std::string access_expires_at;
  std::string refresh_token;
  std::string refresh_expires_at;
  std::string approval_status;
  std::string next_step;
  std::string pending_error;
  std::string avatar_bytes;
  std::string avatar_digest_base64;
  std::string avatar_content_type;
  std::string avatar_upload_id;
  std::string local_generation_id;
  std::string local_next_cursor;
  std::string media_asset_ref;
  std::string backup_task_id;
  std::string backup_lease_token;
  std::string media_type;
  std::string media_mime_type;
  std::string media_captured_at;
  std::string media_content_version;
  std::string media_client_albums_json;
  std::string media_resource_handle;
  std::string media_content_digest_base64;
  std::string media_client_id;
  std::string media_resource_id;
  std::string media_upload_id;
  std::string media_upload_response;
  std::string media_pending_result;
  std::string media_part_etag;
  std::string private_media_cursor;
  std::string private_media_id;
  std::string private_media_view_variant;
  std::string private_media_view_handle;
  std::string family_media_filter;
  std::string family_media_cursor;
  std::string feedback_category;
  std::string feedback_description;
  std::string feedback_contact;
  std::string feedback_app_version;
  std::string feedback_os_version;
  std::vector<std::string> private_media_resource_ids;
  std::vector<std::string> private_media_resource_types;
  std::vector<std::string> private_media_resource_mime_types;
  std::vector<std::string> private_media_resource_digests;
  std::vector<std::string> private_media_resource_urls;
  std::vector<std::string> private_media_resource_headers;
  std::vector<std::string> private_media_temp_paths;
  std::vector<int64_t> private_media_resource_sizes;
  int64_t private_media_view_maximum_output_size = 0;
  bool private_media_view_uses_oss_image_thumbnail = false;
  bool private_media_share_active = false;
  std::vector<int64_t> media_part_sizes;
  std::vector<std::string> media_part_digests;
  std::vector<int64_t> media_uploaded_part_indexes;
  std::vector<std::string> media_uploaded_part_etags;
  int64_t media_source_descriptor = -1;
  int64_t media_source_size = 0;
  int64_t media_part_index = 0;
  int64_t media_uploaded_part_report_index = 0;
  int64_t avatar_source_size = 0;
  int64_t avatar_width = 0;
  int64_t media_limit = 100;
  int64_t local_indexed_count = 0;
  bool allow_cached_profile = false;
  bool allow_cached_media = false;
  bool local_account_bound = false;
  bool replayed_after_refresh = false;
  bool backup_no_work = false;
  bool backup_incremental_scan = false;
  bool backup_paused_by_setting = false;
  int effect_retry_count = 0;

  ~AccountOperation() { clear_sensitive(); }

  void clear_sensitive() {
    wipe_string(phone);
    wipe_string(masked_phone);
    wipe_string(password);
    wipe_string(idempotency_key);
    wipe_string(nickname);
    wipe_string(pending_error);
    wipe_string(device_installation_id);
    wipe_string(avatar_bytes);
    wipe_string(avatar_digest_base64);
    wipe_string(avatar_content_type);
    wipe_string(avatar_upload_id);
    wipe_string(local_generation_id);
    wipe_string(local_next_cursor);
    wipe_string(media_asset_ref);
    wipe_string(backup_task_id);
    wipe_string(backup_lease_token);
    wipe_string(media_type);
    wipe_string(media_mime_type);
    wipe_string(media_captured_at);
    wipe_string(media_content_version);
    wipe_string(media_client_albums_json);
    wipe_string(media_resource_handle);
    wipe_string(media_content_digest_base64);
    wipe_string(media_client_id);
    wipe_string(media_resource_id);
    wipe_string(media_upload_id);
    wipe_string(media_upload_response);
    wipe_string(media_pending_result);
    wipe_string(media_part_etag);
    wipe_string(private_media_cursor);
    wipe_string(private_media_id);
    wipe_string(private_media_view_variant);
    wipe_string(private_media_view_handle);
    wipe_string(family_media_filter);
    wipe_string(family_media_cursor);
    wipe_string(feedback_category);
    wipe_string(feedback_description);
    wipe_string(feedback_contact);
    wipe_string(feedback_app_version);
    wipe_string(feedback_os_version);
    for (auto &value : private_media_resource_ids) wipe_string(value);
    private_media_resource_ids.clear();
    for (auto &value : private_media_resource_types) wipe_string(value);
    private_media_resource_types.clear();
    for (auto &value : private_media_resource_mime_types) wipe_string(value);
    private_media_resource_mime_types.clear();
    for (auto &value : private_media_resource_digests) wipe_string(value);
    private_media_resource_digests.clear();
    for (auto &value : private_media_resource_urls) wipe_string(value);
    private_media_resource_urls.clear();
    for (auto &value : private_media_resource_headers) wipe_string(value);
    private_media_resource_headers.clear();
    for (auto &value : private_media_temp_paths) wipe_string(value);
    private_media_temp_paths.clear();
    private_media_resource_sizes.clear();
    private_media_view_maximum_output_size = 0;
    private_media_view_uses_oss_image_thumbnail = false;
    private_media_share_active = false;
    for (auto &digest : media_part_digests) wipe_string(digest);
    media_part_digests.clear();
    for (auto &etag : media_uploaded_part_etags) wipe_string(etag);
    media_uploaded_part_etags.clear();
    media_uploaded_part_indexes.clear();
    media_part_sizes.clear();
    media_uploaded_part_report_index = 0;
    media_source_descriptor = -1;
    wipe_string(access_token);
    wipe_string(refresh_token);
    wipe_string(access_expires_at);
    wipe_string(refresh_expires_at);
    wipe_string(effect_payload);
  }
};

Core::Core(const std::string &database_path) { open_and_migrate(database_path); }

Core::~Core() {
  std::lock_guard<std::mutex> lock(mutex_);
  subscribers_.clear();
  cancelled_operations_.clear();
  account_operations_.clear();
  active_account_session_.reset();
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
      "  auto_backup_enabled INTEGER NOT NULL DEFAULT 0 CHECK(auto_backup_enabled IN (0,1)),"
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
      "CREATE TABLE IF NOT EXISTS core_operations("
      " operation_id INTEGER PRIMARY KEY CHECK(operation_id>0),"
      " contract_version TEXT NOT NULL CHECK(contract_version='foundation-v2'),"
      " command_type TEXT NOT NULL,command_json TEXT NOT NULL CHECK(json_valid(command_json)),"
      " sequence INTEGER NOT NULL CHECK(sequence>0),"
      " status TEXT NOT NULL CHECK(status IN "
      " ('WAITING_FOR_EFFECT','COMPLETED','FAILED','CANCELLED')),"
      " effect_type TEXT NOT NULL,effect_payload TEXT NOT NULL CHECK(json_valid(effect_payload)),"
      " effect_result_json TEXT,terminal_payload TEXT,"
      " created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')) ,"
      " updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))"
      ");"
      "CREATE INDEX IF NOT EXISTS core_operations_recovery_idx "
      " ON core_operations(status,updated_at,operation_id);"
      "INSERT OR IGNORE INTO schema_migrations(version) VALUES(5);"
      "CREATE TABLE IF NOT EXISTS current_profile_snapshots("
      " user_id TEXT PRIMARY KEY,nickname TEXT NOT NULL,masked_phone TEXT NOT NULL,"
      " avatar_url TEXT,profile_version INTEGER NOT NULL CHECK(profile_version>=1),"
      " updated_at TEXT NOT NULL"
      ");"
      "INSERT OR IGNORE INTO schema_migrations(version) VALUES(6);"
      "CREATE TABLE IF NOT EXISTS private_media_snapshots("
      " user_id TEXT NOT NULL,media_id TEXT NOT NULL,media_type TEXT NOT NULL,"
      " content_revision INTEGER NOT NULL CHECK(content_revision>=1),"
      " captured_at TEXT NOT NULL,created_at TEXT NOT NULL,"
      " PRIMARY KEY(user_id,media_id)"
      ");"
      "CREATE INDEX IF NOT EXISTS private_media_snapshot_order_idx ON "
      " private_media_snapshots(user_id,captured_at DESC,created_at DESC,media_id DESC);"
      "CREATE TABLE IF NOT EXISTS private_media_cache_state("
      " user_id TEXT PRIMARY KEY,refreshed_at TEXT NOT NULL"
      ");"
      "INSERT OR IGNORE INTO schema_migrations(version) VALUES(7);"
      "PRAGMA user_version=7;"
      "COMMIT;");

  sqlite3_stmt *migration = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM schema_migrations WHERE version=8", -1,
                         &migration, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  const bool batch_d_migrated = sqlite3_step(migration) == SQLITE_ROW;
  sqlite3_finalize(migration);
  if (!batch_d_migrated) {
    exec_sql(R"SQL(
      BEGIN IMMEDIATE;
      ALTER TABLE local_albums RENAME TO local_albums_v3_legacy;
      ALTER TABLE local_media RENAME TO local_media_v3_legacy;
      ALTER TABLE local_media_albums RENAME TO local_media_albums_v3_legacy;
      ALTER TABLE local_scan_state RENAME TO local_scan_state_v3_legacy;
      DROP INDEX local_media_capture_idx;
      DROP INDEX local_media_albums_album_idx;
      CREATE TABLE local_library_active(
        user_id TEXT PRIMARY KEY,generation_id TEXT NOT NULL,
        indexed_count INTEGER NOT NULL CHECK(indexed_count>=0),completed_at TEXT NOT NULL);
      CREATE TABLE local_albums(
        user_id TEXT NOT NULL,generation_id TEXT NOT NULL,platform_album_ref TEXT NOT NULL,
        name TEXT NOT NULL,PRIMARY KEY(user_id,generation_id,platform_album_ref));
      CREATE TABLE local_media(
        user_id TEXT NOT NULL,generation_id TEXT NOT NULL,platform_asset_ref TEXT NOT NULL,
        media_type TEXT NOT NULL CHECK(media_type IN ('PHOTO','VIDEO','GIF','LIVE_PHOTO','DYNAMIC')),
        mime_type TEXT NOT NULL,width INTEGER NOT NULL CHECK(width>=0),
        height INTEGER NOT NULL CHECK(height>=0),duration_ms INTEGER,captured_at TEXT NOT NULL,
        modified_at TEXT NOT NULL,modified_version INTEGER NOT NULL,content_version TEXT NOT NULL,
        availability TEXT NOT NULL CHECK(availability IN ('AVAILABLE','WAITING_LOCAL_RESOURCE','LOCAL_MISSING')),
        thumbnail_uri TEXT,PRIMARY KEY(user_id,generation_id,platform_asset_ref));
      CREATE INDEX local_media_capture_idx ON local_media(
        user_id,generation_id,captured_at DESC,platform_asset_ref DESC);
      CREATE TABLE local_media_albums(
        user_id TEXT NOT NULL,generation_id TEXT NOT NULL,platform_asset_ref TEXT NOT NULL,
        platform_album_ref TEXT NOT NULL,
        PRIMARY KEY(user_id,generation_id,platform_asset_ref,platform_album_ref),
        FOREIGN KEY(user_id,generation_id,platform_asset_ref)
          REFERENCES local_media(user_id,generation_id,platform_asset_ref) ON DELETE CASCADE,
        FOREIGN KEY(user_id,generation_id,platform_album_ref)
          REFERENCES local_albums(user_id,generation_id,platform_album_ref) ON DELETE CASCADE);
      CREATE INDEX local_media_albums_album_idx ON local_media_albums(
        user_id,generation_id,platform_album_ref,platform_asset_ref);
      INSERT INTO local_library_active(user_id,generation_id,indexed_count,completed_at)
        SELECT user_id,scan_generation,indexed_count,updated_at FROM local_scan_state_v3_legacy
        WHERE status='COMPLETE' AND scan_generation<>'';
      INSERT INTO local_albums(user_id,generation_id,platform_album_ref,name)
        SELECT album.user_id,state.scan_generation,album.platform_album_ref,album.name
        FROM local_albums_v3_legacy album JOIN local_scan_state_v3_legacy state
          ON state.user_id=album.user_id
        WHERE state.status='COMPLETE' AND state.scan_generation<>'' AND album.is_available=1;
      INSERT INTO local_media(user_id,generation_id,platform_asset_ref,media_type,mime_type,
        width,height,duration_ms,captured_at,modified_at,modified_version,content_version,
        availability,thumbnail_uri)
        SELECT media.user_id,state.scan_generation,media.platform_asset_ref,media.media_type,
          media.mime_type,media.width,media.height,media.duration_ms,media.captured_at,
          media.modified_at,media.modified_version,media.content_version,media.availability,
          media.thumbnail_uri FROM local_media_v3_legacy media
        JOIN local_scan_state_v3_legacy state ON state.user_id=media.user_id
        WHERE state.status='COMPLETE' AND state.scan_generation<>''
          AND media.availability<>'LOCAL_MISSING';
      INSERT OR IGNORE INTO local_media_albums(
        user_id,generation_id,platform_asset_ref,platform_album_ref)
        SELECT relation.user_id,state.scan_generation,relation.platform_asset_ref,
          relation.platform_album_ref FROM local_media_albums_v3_legacy relation
        JOIN local_scan_state_v3_legacy state ON state.user_id=relation.user_id
        JOIN local_media media ON media.user_id=relation.user_id
          AND media.generation_id=state.scan_generation
          AND media.platform_asset_ref=relation.platform_asset_ref
        JOIN local_albums album ON album.user_id=relation.user_id
          AND album.generation_id=state.scan_generation
          AND album.platform_album_ref=relation.platform_album_ref
        WHERE state.status='COMPLETE' AND state.scan_generation<>'';
      DROP TABLE local_media_albums_v3_legacy;
      DROP TABLE local_media_v3_legacy;
      DROP TABLE local_albums_v3_legacy;
      DROP TABLE local_scan_state_v3_legacy;
      INSERT INTO schema_migrations(version) VALUES(8);
      PRAGMA user_version=8;
      COMMIT;
    )SQL");
  } else {
    exec_sql("DROP TABLE IF EXISTS local_scan_state; PRAGMA user_version=8;");
  }

  sqlite3_stmt *legacy_media_migration = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM schema_migrations WHERE version=9", -1,
                         &legacy_media_migration, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  const bool legacy_media_migrated = sqlite3_step(legacy_media_migration) == SQLITE_ROW;
  sqlite3_finalize(legacy_media_migration);
  if (!legacy_media_migrated) {
    exec_sql(
        "BEGIN IMMEDIATE;"
        "DROP TABLE IF EXISTS backup_parts;"
        "DROP TABLE IF EXISTS backup_resources;"
        "DROP TABLE IF EXISTS backup_tasks;"
        "INSERT INTO schema_migrations(version) VALUES(9);"
        "PRAGMA user_version=9;"
        "COMMIT;");
  } else {
    exec_sql("PRAGMA user_version=9;");
  }

  sqlite3_stmt *backup_queue_migration = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM schema_migrations WHERE version=10", -1,
                         &backup_queue_migration, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  const bool backup_queue_migrated = sqlite3_step(backup_queue_migration) == SQLITE_ROW;
  sqlite3_finalize(backup_queue_migration);
  if (!backup_queue_migrated) {
    exec_sql(R"SQL(
      BEGIN IMMEDIATE;
      CREATE TABLE backup_scan_state(
        user_id TEXT NOT NULL,device_installation_id TEXT NOT NULL,
        mode TEXT NOT NULL CHECK(mode IN ('HISTORICAL','INCREMENTAL','FULL_RECONCILE')),
        state TEXT NOT NULL CHECK(state IN ('IDLE','SCANNING','WAITING_PERMISSION','FAILED')),
        generation_id TEXT NOT NULL,cursor_json TEXT,upper_bound_json TEXT,
        reconcile_requested INTEGER NOT NULL DEFAULT 0 CHECK(reconcile_requested IN (0,1)),
        discovered_count INTEGER NOT NULL DEFAULT 0 CHECK(discovered_count>=0),
        started_at TEXT,completed_at TEXT,updated_at TEXT NOT NULL,
        PRIMARY KEY(user_id,device_installation_id));
      CREATE TABLE backup_tasks(
        task_id TEXT PRIMARY KEY,user_id TEXT NOT NULL,device_installation_id TEXT NOT NULL,
        platform_asset_ref TEXT NOT NULL,content_version TEXT NOT NULL,client_media_id TEXT NOT NULL,
        idempotency_key TEXT NOT NULL,
        media_type TEXT NOT NULL CHECK(media_type IN ('PHOTO','VIDEO','GIF','LIVE_PHOTO','DYNAMIC')),
        mime_type TEXT NOT NULL,captured_at TEXT NOT NULL,
        state TEXT NOT NULL CHECK(state IN ('DISCOVERED','WAITING_PERMISSION','WAITING_RESOURCE',
          'WAITING_NETWORK','PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING',
          'RETRYABLE_FAILED','PERMANENT_FAILED','PAUSED_BY_SETTING','COMPLETED')),
        resume_state TEXT,server_upload_id TEXT,server_media_id TEXT,
        retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count>=0),next_retry_at TEXT,
        failure_code TEXT,failure_scope TEXT CHECK(failure_scope IS NULL OR failure_scope IN ('LOCAL','NETWORK','SERVICE','OSS','AUTH')),
        lease_token TEXT,lease_expires_at TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
        UNIQUE(user_id,device_installation_id,platform_asset_ref,content_version),
        UNIQUE(user_id,device_installation_id,idempotency_key));
      CREATE INDEX backup_tasks_runnable_idx ON backup_tasks(
        user_id,device_installation_id,state,next_retry_at,captured_at DESC,task_id DESC);
      CREATE TABLE backup_resources(
        resource_id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES backup_tasks(task_id) ON DELETE CASCADE,
        resource_type TEXT NOT NULL,byte_length INTEGER NOT NULL CHECK(byte_length>0),
        sha256_base64 TEXT NOT NULL,
        preparation_state TEXT NOT NULL CHECK(preparation_state IN ('PENDING','READY','UNAVAILABLE','FAILED')),
        server_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(server_confirmed IN (0,1)),
        created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(task_id,resource_type));
      CREATE TABLE backup_parts(
        resource_id TEXT NOT NULL REFERENCES backup_resources(resource_id) ON DELETE CASCADE,
        part_number INTEGER NOT NULL CHECK(part_number>0),byte_offset INTEGER NOT NULL CHECK(byte_offset>=0),
        byte_length INTEGER NOT NULL CHECK(byte_length>0),sha256_base64 TEXT NOT NULL,etag TEXT,
        state TEXT NOT NULL CHECK(state IN ('PENDING','TRANSFERRED','CONFIRMED')),confirmed_at TEXT,
        PRIMARY KEY(resource_id,part_number));
      CREATE INDEX backup_parts_pending_idx ON backup_parts(resource_id,state,part_number);
      INSERT INTO schema_migrations(version) VALUES(10);
      PRAGMA user_version=10;
      COMMIT;
    )SQL");
  } else {
    exec_sql("PRAGMA user_version=10;");
  }

  sqlite3_stmt *backup_task_albums_migration = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM schema_migrations WHERE version=11", -1,
                         &backup_task_albums_migration, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  const bool backup_task_albums_migrated = sqlite3_step(backup_task_albums_migration) == SQLITE_ROW;
  sqlite3_finalize(backup_task_albums_migration);
  if (!backup_task_albums_migrated) {
    exec_sql(
        "BEGIN IMMEDIATE;"
        "ALTER TABLE backup_tasks ADD COLUMN client_albums_json TEXT NOT NULL DEFAULT '[]';"
        "INSERT INTO schema_migrations(version) VALUES(11);"
        "PRAGMA user_version=11;"
        "COMMIT;");
  } else {
    exec_sql("PRAGMA user_version=11;");
  }

  sqlite3_stmt *manual_backup_migration = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM schema_migrations WHERE version=12", -1,
                         &manual_backup_migration, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  const bool manual_backup_migrated = sqlite3_step(manual_backup_migration) == SQLITE_ROW;
  sqlite3_finalize(manual_backup_migration);
  if (!manual_backup_migrated) {
    exec_sql(
        "BEGIN IMMEDIATE;"
        "ALTER TABLE backup_tasks ADD COLUMN requested_manually INTEGER NOT NULL DEFAULT 0 "
        "CHECK(requested_manually IN (0,1));"
        "INSERT INTO schema_migrations(version) VALUES(12);"
        "PRAGMA user_version=12;"
        "COMMIT;");
  } else {
    exec_sql("PRAGMA user_version=12;");
  }

  sqlite3_stmt *private_media_stage05_migration = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM schema_migrations WHERE version=13", -1,
                         &private_media_stage05_migration, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  const bool private_media_stage05_migrated = sqlite3_step(private_media_stage05_migration) == SQLITE_ROW;
  sqlite3_finalize(private_media_stage05_migration);
  if (!private_media_stage05_migrated) {
    exec_sql(R"SQL(
      BEGIN IMMEDIATE;
      CREATE TABLE private_media_items_v2(
        user_id TEXT NOT NULL,media_id TEXT NOT NULL,media_type TEXT NOT NULL,
        captured_at TEXT NOT NULL,created_at TEXT NOT NULL,width INTEGER,height INTEGER,
        duration_ms INTEGER,original_total_size INTEGER NOT NULL CHECK(original_total_size>=0),
        preview_resource_id TEXT,content_revision INTEGER NOT NULL CHECK(content_revision>=1),
        updated_at TEXT NOT NULL,PRIMARY KEY(user_id,media_id));
      CREATE INDEX private_media_items_v2_order_idx ON private_media_items_v2(
        user_id,captured_at DESC,media_id DESC);
      CREATE TABLE private_media_page_state_v2(
        user_id TEXT PRIMARY KEY,next_cursor TEXT,
        fully_loaded INTEGER NOT NULL DEFAULT 0 CHECK(fully_loaded IN (0,1)),
        refreshed_at TEXT NOT NULL);
      CREATE TABLE private_media_resources(
        user_id TEXT NOT NULL,media_id TEXT NOT NULL,resource_id TEXT NOT NULL,
        resource_type TEXT NOT NULL,mime_type TEXT NOT NULL,
        content_size INTEGER NOT NULL CHECK(content_size>0),content_sha256_base64 TEXT NOT NULL,
        PRIMARY KEY(user_id,resource_id),UNIQUE(user_id,media_id,resource_type),
        FOREIGN KEY(user_id,media_id) REFERENCES private_media_items_v2(user_id,media_id) ON DELETE CASCADE);
      INSERT OR IGNORE INTO private_media_items_v2(
        user_id,media_id,media_type,captured_at,created_at,original_total_size,content_revision,updated_at)
      SELECT user_id,media_id,media_type,captured_at,created_at,0,content_revision,created_at
      FROM private_media_snapshots;
      INSERT OR IGNORE INTO private_media_page_state_v2(user_id,fully_loaded,refreshed_at)
      SELECT user_id,0,refreshed_at FROM private_media_cache_state;
      ALTER TABLE download_receipts ADD COLUMN content_revision INTEGER NOT NULL DEFAULT 1;
      ALTER TABLE download_receipts ADD COLUMN resource_set_digest TEXT;
      ALTER TABLE download_receipts ADD COLUMN updated_at TEXT NOT NULL DEFAULT '';
      INSERT INTO schema_migrations(version) VALUES(13);
      PRAGMA user_version=13;
      COMMIT;
    )SQL");
  } else {
    exec_sql("PRAGMA user_version=13;");
  }
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
  if (type == "RetryBackupQueue") {
    const mineg_error_code_t code = retry_backup_queue_locked(command);
    if (code == MINEG_OK) result = "{\"contractVersion\":\"stage04-v1\",\"status\":\"SUCCESS\"}";
    return code;
  }
  if (type == "EnqueueBackupMedia") {
    return enqueue_backup_media_locked(command, result);
  }
  if (type == "NotifyLibraryChanged") {
    const mineg_error_code_t code = notify_library_changed_locked(command);
    if (code == MINEG_OK) result = "{\"contractVersion\":\"stage04-v1\",\"status\":\"SUCCESS\"}";
    return code;
  }
  if (type == "ApplyLocalMediaBatch") {
    const mineg_error_code_t code = apply_local_media_batch_locked(command);
    if (code == MINEG_OK) result = "{\"version\":1,\"status\":\"SUCCESS\"}";
    return code;
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

mineg_error_code_t Core::read_operation_step_locked(uint64_t operation_id, std::string &result,
                                                     std::string *command_json,
                                                     std::string *effect_result_json) {
  if (operation_id > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
    return MINEG_INVALID_ARGUMENT;
  }
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT command_json,sequence,status,effect_type,effect_payload,effect_result_json,"
      "terminal_payload FROM core_operations WHERE operation_id=?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_int64(statement, 1, static_cast<long long>(operation_id));
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  if (status == SQLITE_DONE) {
    sqlite3_finalize(statement);
    return MINEG_NOT_FOUND;
  }
  if (status != SQLITE_ROW) {
    sqlite3_finalize(statement);
    return MINEG_DATABASE_ERROR;
  }
  const auto text_at = [statement](int column) -> std::string {
    const auto *value = sqlite3_column_text(statement, column);
    return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
  };
  if (command_json != nullptr) *command_json = text_at(0);
  const uint64_t sequence = static_cast<uint64_t>(sqlite3_column_int64(statement, 1));
  const std::string operation_status = text_at(2);
  const std::string effect_type = text_at(3);
  const std::string effect_payload = text_at(4);
  if (effect_result_json != nullptr) *effect_result_json = text_at(5);
  const std::string terminal_payload = text_at(6);
  sqlite3_finalize(statement);
  result = operation_step_json(operation_id, sequence, operation_status, effect_type,
                               effect_payload, terminal_payload);
  return MINEG_OK;
}

mineg_error_code_t Core::account_operation_step_locked(AccountOperation &operation,
                                                        std::string &result) {
  result = operation_step_json(operation.operation_id, operation.sequence, operation.status,
                               operation.effect_type, operation.effect_payload,
                               operation.terminal_payload);
  return MINEG_OK;
}

void Core::set_account_effect_locked(AccountOperation &operation,
                                     const std::string &effect_type,
                                     const std::string &payload,
                                     const std::string &stage) {
  wipe_string(operation.effect_payload);
  operation.effect_type = effect_type;
  operation.effect_payload = payload;
  operation.stage = stage;
  operation.status = "WAITING_FOR_EFFECT";
  operation.terminal_payload.clear();
  operation.effect_retry_count = 0;
}

void Core::finish_account_error_locked(AccountOperation &operation, const std::string &code,
                                        bool retryable, const std::string &request_id) {
  std::string key = code;
  std::transform(key.begin(), key.end(), key.begin(), [](unsigned char value) {
    return static_cast<char>(std::tolower(value));
  });
  operation.status = "FAILED";
  operation.terminal_payload = "{\"code\":\"" + json_escape(code) +
      "\",\"messageKey\":\"account." + json_escape(key) +
      "\",\"retryable\":" + (retryable ? "true" : "false") +
      ",\"requestId\":\"" + json_escape(request_id) + "\"}";
  operation.clear_sensitive();
}

void Core::issue_session_read_locked(AccountOperation &operation) {
  set_account_effect_locked(
      operation, "SecureStoreEffect",
      "{\"action\":\"readSecrets\",\"names\":[\"account.accessToken\","
      "\"account.refreshToken\",\"account.accessExpiresAt\","
      "\"account.refreshExpiresAt\",\"device.installationId\"]}",
      "READ_SESSION");
}

void Core::issue_session_write_locked(AccountOperation &operation,
                                      const std::string &continuation) {
  const auto item = [](const std::string &name, const std::string &value) {
    return "{\"name\":\"" + name + "\",\"valueBase64\":\"" +
        base64_encode(value) + "\"}";
  };
  const std::string payload =
      "{\"action\":\"writeSecrets\",\"values\":[" +
      item("account.accessToken", operation.access_token) + ',' +
      item("account.refreshToken", operation.refresh_token) + ',' +
      item("account.accessExpiresAt", operation.access_expires_at) + ',' +
      item("account.refreshExpiresAt", operation.refresh_expires_at) + "]}";
  operation.continuation = continuation;
  set_account_effect_locked(operation, "SecureStoreEffect", payload, "WRITE_SESSION");
}

void Core::issue_session_cleanup_locked(AccountOperation &operation,
                                        const std::string &completion) {
  operation.continuation = completion;
  set_account_effect_locked(
      operation, "SecureStoreEffect",
      "{\"action\":\"deleteSecrets\",\"names\":[\"account.accessToken\","
      "\"account.refreshToken\",\"account.accessExpiresAt\","
      "\"account.refreshExpiresAt\"]}",
      "DELETE_SESSION");
}

mineg_error_code_t Core::issue_account_request_locked(AccountOperation &operation,
                                                       const std::string &purpose) {
  std::string method;
  std::string path;
  std::string body;
  std::string headers = "{}";
  if (purpose == "SIGN_IN") {
    method = "POST";
    path = "/api/v1/auth/login";
    body = "{\"phone\":\"" + json_escape(operation.phone) +
        "\",\"password\":\"" + json_escape(operation.password) +
        "\",\"device_installation_id\":\"" +
        json_escape(operation.device_installation_id) +
        "\",\"platform\":\"ANDROID\",\"agreement_accepted\":true,"
        "\"terms_version\":\"1.0\",\"privacy_version\":\"1.0\"}";
  } else if (purpose == "SIGN_UP") {
    method = "POST";
    path = "/api/v1/auth/register";
    headers = "{\"Idempotency-Key\":\"" + json_escape(operation.idempotency_key) + "\"}";
    body = "{\"phone\":\"" + json_escape(operation.phone) +
        "\",\"password\":\"" + json_escape(operation.password) +
        "\",\"device_installation_id\":\"" +
        json_escape(operation.device_installation_id) +
        "\",\"platform\":\"ANDROID\"}";
  } else if (purpose == "REFRESH") {
    method = "POST";
    path = "/api/v1/auth/refresh";
    body = "{\"refresh_token\":\"" + json_escape(operation.refresh_token) + "\"}";
  } else if (purpose == "SIGN_OUT") {
    method = "POST";
    path = "/api/v1/auth/logout";
    body = "{\"refresh_token\":\"" + json_escape(operation.refresh_token) + "\"}";
  } else {
    if (active_account_session_ == nullptr || active_account_session_->access_token.empty()) {
      return MINEG_NOT_FOUND;
    }
    headers = "{\"Authorization\":\"Bearer " +
        json_escape(active_account_session_->access_token) + "\"}";
    if (purpose == "REVIEW") {
      method = "GET";
      path = "/api/v1/auth/approval-status";
    } else if (purpose == "PROFILE_GET") {
      method = "GET";
      path = "/api/v1/me";
    } else if (purpose == "PROFILE_UPDATE") {
      method = "PATCH";
      path = "/api/v1/me/profile";
      body = "{\"nickname\":\"" + json_escape(operation.nickname) + "\"}";
  } else if (purpose == "PRIVATE_MEDIA_LIST") {
    method = "GET";
    path = "/api/v1/media?limit=" + std::to_string(operation.media_limit);
  } else if (purpose == "PRIVATE_MEDIA_REFRESH" || purpose == "PRIVATE_MEDIA_LOAD_MORE") {
    method = "GET";
    path = "/api/v1/private/media?limit=" + std::to_string(operation.media_limit);
    if (purpose == "PRIVATE_MEDIA_LOAD_MORE" && !operation.private_media_cursor.empty()) {
      path += "&cursor=" + operation.private_media_cursor;
    }
  } else if (purpose == "PRIVATE_MEDIA_DETAIL") {
    if (operation.private_media_id.empty()) return MINEG_INVALID_ARGUMENT;
    method = "GET";
    path = "/api/v1/private/media/" + operation.private_media_id;
  } else if (purpose == "PRIVATE_MEDIA_TRASH") {
    if (operation.private_media_id.empty() || operation.idempotency_key.empty()) return MINEG_INVALID_ARGUMENT;
    method = "POST";
    path = "/api/v1/private/media/" + operation.private_media_id + "/trash";
    headers = "{\"Authorization\":\"Bearer " +
        json_escape(active_account_session_->access_token) +
        "\",\"Idempotency-Key\":\"" + json_escape(operation.idempotency_key) + "\"}";
    body = "{}";
  } else if (purpose == "PRIVATE_MEDIA_VIEW_ACCESS") {
    if (operation.private_media_id.empty() ||
        (operation.private_media_view_variant != "THUMBNAIL" &&
         operation.private_media_view_variant != "DETAIL")) {
      return MINEG_INVALID_ARGUMENT;
    }
    method = "POST";
    path = "/api/v1/private/media/" + operation.private_media_id + "/access";
    body = "{\"purpose\":\"VIEW\",\"variant\":\"" +
        operation.private_media_view_variant + "\"}";
  } else if (purpose == "PRIVATE_MEDIA_SHARE") {
    if (operation.private_media_id.empty() || operation.idempotency_key.empty()) return MINEG_INVALID_ARGUMENT;
    method = "POST";
    path = "/api/v1/private/media/" + operation.private_media_id + "/share";
    headers = "{\"Authorization\":\"Bearer " +
        json_escape(active_account_session_->access_token) +
        "\",\"Idempotency-Key\":\"" + json_escape(operation.idempotency_key) + "\"}";
    body = std::string("{\"shared\":") + (operation.private_media_share_active ? "true" : "false") + "}";
  } else if (purpose == "FAMILY_MEDIA_LIST") {
    method = "GET";
    path = "/api/v1/family/media?filter=" + operation.family_media_filter +
        "&limit=" + std::to_string(operation.media_limit);
    if (!operation.family_media_cursor.empty()) path += "&cursor=" + operation.family_media_cursor;
  } else if (purpose == "FAMILY_MEDIA_DETAIL") {
    if (operation.private_media_id.empty()) return MINEG_INVALID_ARGUMENT;
    method = "GET";
    path = "/api/v1/family/media/" + operation.private_media_id;
  } else if (purpose == "FAMILY_MEDIA_VIEW_ACCESS") {
    if (operation.private_media_id.empty() ||
        (operation.private_media_view_variant != "THUMBNAIL" &&
         operation.private_media_view_variant != "DETAIL")) return MINEG_INVALID_ARGUMENT;
    method = "POST";
    path = "/api/v1/family/media/" + operation.private_media_id + "/access";
    body = "{\"purpose\":\"VIEW\",\"variant\":\"" +
        operation.private_media_view_variant + "\"}";
  } else if (purpose == "TRASH_MEDIA_LIST") {
    method = "GET";
    path = "/api/v1/trash?limit=" + std::to_string(operation.media_limit);
    if (!operation.family_media_cursor.empty()) path += "&cursor=" + operation.family_media_cursor;
  } else if (purpose == "TRASH_MEDIA_RESTORE") {
    if (operation.private_media_id.empty() || operation.idempotency_key.empty()) return MINEG_INVALID_ARGUMENT;
    method = "POST";
    path = "/api/v1/trash/" + operation.private_media_id + "/restore";
    headers = "{\"Authorization\":\"Bearer " +
        json_escape(active_account_session_->access_token) +
        "\",\"Idempotency-Key\":\"" + json_escape(operation.idempotency_key) + "\"}";
    body = "{}";
  } else if (purpose == "FEEDBACK_SUBMIT") {
    if (operation.device_installation_id.empty() || operation.idempotency_key.empty()) return MINEG_INVALID_ARGUMENT;
    method = "POST";
    path = "/api/v1/feedback";
    headers = "{\"Authorization\":\"Bearer " +
        json_escape(active_account_session_->access_token) +
        "\",\"Idempotency-Key\":\"" + json_escape(operation.idempotency_key) + "\"}";
    body = "{\"category\":\"" + json_escape(operation.feedback_category) +
        "\",\"description\":\"" + json_escape(operation.feedback_description) +
        "\",\"app_version\":\"" + json_escape(operation.feedback_app_version) +
        "\",\"platform\":\"ANDROID\",\"os_version\":\"" +
        json_escape(operation.feedback_os_version) +
        "\",\"device_installation_id\":\"" + json_escape(operation.device_installation_id) + "\"";
    if (!operation.feedback_contact.empty()) {
      body += ",\"contact\":\"" + json_escape(operation.feedback_contact) + "\"";
    }
    body += "}";
    } else if (purpose == "AVATAR_CREATE") {
      method = "POST";
      path = "/api/v1/me/avatar/uploads";
      headers = "{\"Authorization\":\"Bearer " +
          json_escape(active_account_session_->access_token) +
          "\",\"Idempotency-Key\":\"" + json_escape(operation.idempotency_key) + "\"}";
      body = "{\"content_type\":\"" + json_escape(operation.avatar_content_type) +
          "\",\"source_size\":" + std::to_string(operation.avatar_source_size) +
          ",\"display_size\":" + std::to_string(operation.avatar_bytes.size()) +
          ",\"width\":" + std::to_string(operation.avatar_width) +
          ",\"height\":" + std::to_string(operation.avatar_width) +
          ",\"content_sha256\":\"" + operation.avatar_digest_base64 + "\"}";
    } else if (purpose == "AVATAR_COMPLETE") {
      if (operation.avatar_upload_id.empty()) return MINEG_INVALID_ARGUMENT;
      method = "POST";
      path = "/api/v1/me/avatar/uploads/" + operation.avatar_upload_id + "/complete";
      body = "{}";
    } else {
      return MINEG_INVALID_ARGUMENT;
    }
  }
  std::string payload = "{\"action\":\"sendApiRequest\",\"method\":\"" + method +
      "\",\"path\":\"" + path + "\",\"headers\":" + headers;
  if (!body.empty()) payload += ",\"bodyBase64\":\"" + base64_encode(body) + "\"";
  payload += "}";
  set_account_effect_locked(operation, "TransportEffect", payload, "TRANSPORT_" + purpose);
  if (purpose == "SIGN_IN" || purpose == "SIGN_UP") wipe_string(operation.password);
  wipe_string(body);
  return MINEG_OK;
}

bool Core::activate_account_session_locked(AccountOperation &operation) {
  if (operation.user_id.empty() || operation.access_token.empty() ||
      operation.refresh_token.empty() || operation.access_expires_at.empty() ||
      operation.refresh_expires_at.empty() ||
      (operation.approval_status != "PENDING" && operation.approval_status != "APPROVED") ||
      (operation.next_step != "REVIEW_PENDING" && operation.next_step != "APP_HOME")) {
    return false;
  }
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "INSERT INTO account_state(singleton,user_id,masked_phone,approval_status,updated_at) "
      "VALUES(1,?,?,?,?) ON CONFLICT(singleton) DO UPDATE SET user_id=excluded.user_id,"
      "masked_phone=excluded.masked_phone,approval_status=excluded.approval_status,"
      "updated_at=excluded.updated_at";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  const std::string mask = operation.masked_phone.empty() ? "***********" : operation.masked_phone;
  const std::string updated_at = now_rfc3339();
  int status = sqlite3_bind_text(statement, 1, operation.user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, mask.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, operation.approval_status.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, updated_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return false;
  auto session = std::make_unique<ActiveAccountSession>();
  session->user_id = operation.user_id;
  session->access_token = operation.access_token;
  session->access_expires_at = operation.access_expires_at;
  session->refresh_token = operation.refresh_token;
  session->refresh_expires_at = operation.refresh_expires_at;
  session->approval_status = operation.approval_status;
  session->next_step = operation.next_step;
  active_account_session_ = std::move(session);
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"" + json_escape(operation.contract_version) +
              "\",\"type\":\"AccountRouteChanged\","
              "\"sequence\":" + std::to_string(event_sequence_) + ",\"userId\":\"" +
              json_escape(operation.user_id) + "\",\"approvalStatus\":\"" +
              operation.approval_status + "\",\"nextStep\":\"" + operation.next_step + "\"}");
  return true;
}

std::string Core::read_current_profile_snapshot_locked() {
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT p.user_id,p.nickname,p.masked_phone,p.avatar_url,p.profile_version,p.updated_at "
      "FROM current_profile_snapshots p JOIN account_state a ON a.singleton=1 AND "
      "a.user_id=p.user_id";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return {};
  if (sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    return {};
  }
  const auto text_at = [statement](int column) -> std::string {
    const auto *value = sqlite3_column_text(statement, column);
    return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
  };
  const bool avatar_null = sqlite3_column_type(statement, 3) == SQLITE_NULL;
  std::string result = "{\"id\":\"" + json_escape(text_at(0)) +
      "\",\"nickname\":\"" + json_escape(text_at(1)) +
      "\",\"maskedPhone\":\"" + json_escape(text_at(2)) + "\",\"avatarUrl\":" +
      (avatar_null ? "null" : "\"" + json_escape(text_at(3)) + "\"") +
      ",\"version\":" + std::to_string(sqlite3_column_int64(statement, 4)) +
      ",\"updatedAt\":\"" + json_escape(text_at(5)) + "\"}";
  sqlite3_finalize(statement);
  return result;
}

bool Core::persist_current_profile_locked(const std::string &profile_json,
                                          const std::string &contract_version) {
  const std::string user_id = sqlite_json_text(database_, profile_json, "$.id");
  const std::string nickname = sqlite_json_text(database_, profile_json, "$.nickname");
  const std::string mask = sqlite_json_text(database_, profile_json, "$.masked_phone");
  const std::string avatar = sqlite_json_text(database_, profile_json, "$.avatar_url");
  const int64_t version = sqlite_json_integer(database_, profile_json, "$.version", 1);
  if (active_account_session_ == nullptr || user_id != active_account_session_->user_id ||
      nickname.empty() || mask.empty() || version < 1) {
    return false;
  }
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "INSERT INTO current_profile_snapshots(user_id,nickname,masked_phone,avatar_url,"
      "profile_version,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET "
      "nickname=excluded.nickname,masked_phone=excluded.masked_phone,"
      "avatar_url=excluded.avatar_url,profile_version=excluded.profile_version,"
      "updated_at=excluded.updated_at";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  const std::string updated_at = now_rfc3339();
  int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, nickname.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, mask.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK && avatar.empty()) status = sqlite3_bind_null(statement, 4);
  if (status == SQLITE_OK && !avatar.empty()) status = sqlite3_bind_text(statement, 4, avatar.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int64(statement, 5, version);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 6, updated_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return false;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"" + json_escape(contract_version) +
              "\",\"type\":\"CurrentProfileChanged\","
              "\"sequence\":" + std::to_string(event_sequence_) + ",\"userId\":\"" +
              json_escape(user_id) + "\",\"version\":" + std::to_string(version) + "}");
  return true;
}

bool Core::has_private_media_cache_locked() {
  if (active_account_session_ == nullptr) return false;
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM private_media_cache_state WHERE user_id=?", -1,
                         &statement, nullptr) != SQLITE_OK) {
    return false;
  }
  int status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                                 SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  const bool found = status == SQLITE_ROW;
  sqlite3_finalize(statement);
  return found;
}

std::string Core::read_private_media_snapshot_locked(int limit) {
  if (active_account_session_ == nullptr) return {};
  limit = std::clamp(limit, 1, 100);
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT media_id,media_type,content_revision,captured_at,created_at "
      "FROM private_media_snapshots WHERE user_id=? "
      "ORDER BY captured_at DESC,created_at DESC,media_id DESC LIMIT ?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return {};
  int status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                                 SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int(statement, 2, limit);
  if (status != SQLITE_OK) {
    sqlite3_finalize(statement);
    return {};
  }
  std::string result = "{\"items\":[";
  int count = 0;
  while ((status = sqlite3_step(statement)) == SQLITE_ROW) {
    const auto text_at = [statement](int column) -> std::string {
      const auto *value = sqlite3_column_text(statement, column);
      return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
    };
    if (count++ > 0) result += ',';
    result += "{\"id\":\"" + json_escape(text_at(0)) +
        "\",\"mediaType\":\"" + json_escape(text_at(1)) +
        "\",\"contentRevision\":" + std::to_string(sqlite3_column_int64(statement, 2)) +
        ",\"capturedAt\":\"" + json_escape(text_at(3)) +
        "\",\"createdAt\":\"" + json_escape(text_at(4)) + "\"}";
  }
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return {};
  sqlite3_stmt *cache = nullptr;
  std::string refreshed;
  if (sqlite3_prepare_v2(database_,
                         "SELECT refreshed_at FROM private_media_cache_state WHERE user_id=?", -1,
                         &cache, nullptr) == SQLITE_OK) {
    int cache_status = sqlite3_bind_text(cache, 1, active_account_session_->user_id.c_str(), -1,
                                         SQLITE_TRANSIENT);
    if (cache_status == SQLITE_OK && sqlite3_step(cache) == SQLITE_ROW) {
      const auto *value = sqlite3_column_text(cache, 0);
      if (value != nullptr) refreshed = reinterpret_cast<const char *>(value);
    }
  }
  sqlite3_finalize(cache);
  result += "],\"refreshedAt\":" +
      (refreshed.empty() ? std::string("null") : "\"" + json_escape(refreshed) + "\"") + "}";
  return result;
}

bool Core::persist_private_media_locked(const std::string &page_json) {
  if (active_account_session_ == nullptr) return false;
  sqlite3_stmt *validation = nullptr;
  const char *validation_sql =
      "SELECT coalesce(json_array_length(?1,'$.items'),-1),"
      "coalesce((SELECT count(*) FROM json_each(?1,'$.items') item WHERE "
      "length(coalesce(json_extract(item.value,'$.id'),''))=0 OR "
      "length(coalesce(json_extract(item.value,'$.media_type'),''))=0 OR "
      "coalesce(json_extract(item.value,'$.content_revision'),0)<1 OR "
      "length(coalesce(json_extract(item.value,'$.captured_at'),''))=0 OR "
      "length(coalesce(json_extract(item.value,'$.created_at'),''))=0),-1)";
  if (sqlite3_prepare_v2(database_, validation_sql, -1, &validation, nullptr) != SQLITE_OK) {
    return false;
  }
  int status = sqlite3_bind_text(validation, 1, page_json.c_str(),
                                 static_cast<int>(page_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(validation);
  const int item_count = status == SQLITE_ROW ? sqlite3_column_int(validation, 0) : -1;
  const int invalid_count = status == SQLITE_ROW ? sqlite3_column_int(validation, 1) : -1;
  sqlite3_finalize(validation);
  if (item_count < 0 || item_count > 100 || invalid_count != 0) return false;
  if (sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return false;
  }
  const auto rollback = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  };
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, "DELETE FROM private_media_snapshots WHERE user_id=?", -1,
                         &statement, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                             SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return rollback();
  const char *insert_sql =
      "INSERT INTO private_media_snapshots(user_id,media_id,media_type,content_revision,"
      "captured_at,created_at) SELECT ?1,json_extract(item.value,'$.id'),"
      "json_extract(item.value,'$.media_type'),json_extract(item.value,'$.content_revision'),"
      "json_extract(item.value,'$.captured_at'),json_extract(item.value,'$.created_at') "
      "FROM json_each(?2,'$.items') item";
  if (sqlite3_prepare_v2(database_, insert_sql, -1, &statement, nullptr) != SQLITE_OK) {
    return rollback();
  }
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                             SQLITE_TRANSIENT);
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 2, page_json.c_str(), static_cast<int>(page_json.size()),
                               SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return rollback();
  const std::string refreshed_at = now_rfc3339();
  const char *cache_sql =
      "INSERT INTO private_media_cache_state(user_id,refreshed_at) VALUES(?,?) "
      "ON CONFLICT(user_id) DO UPDATE SET refreshed_at=excluded.refreshed_at";
  if (sqlite3_prepare_v2(database_, cache_sql, -1, &statement, nullptr) != SQLITE_OK) {
    return rollback();
  }
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                             SQLITE_TRANSIENT);
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 2, refreshed_at.c_str(), -1, SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE || sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return rollback();
  }
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage02-v2\",\"type\":"
              "\"PrivateMediaSnapshotChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" +
              json_escape(active_account_session_->user_id) + "\",\"itemCount\":" +
              std::to_string(item_count) + "}");
  return true;
}

bool Core::has_private_media_page_v2_locked() {
  if (active_account_session_ == nullptr) return false;
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM private_media_page_state_v2 WHERE user_id=?", -1,
                         &statement, nullptr) != SQLITE_OK) {
    return false;
  }
  int status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                                 SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  const bool found = status == SQLITE_ROW;
  sqlite3_finalize(statement);
  return found;
}

std::string Core::read_private_media_next_cursor_v2_locked() {
  if (active_account_session_ == nullptr) return {};
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT COALESCE(next_cursor,'') FROM private_media_page_state_v2 WHERE user_id=? "
                         "AND fully_loaded=0", -1, &statement, nullptr) != SQLITE_OK) {
    return {};
  }
  int status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                                 SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  std::string result;
  if (status == SQLITE_ROW) {
    const auto *value = sqlite3_column_text(statement, 0);
    if (value != nullptr) result = reinterpret_cast<const char *>(value);
  }
  sqlite3_finalize(statement);
  return result;
}

std::string Core::read_private_media_page_v2_locked(int limit,
                                                    const std::string &page_json) {
  if (active_account_session_ == nullptr) return {};
  limit = std::clamp(limit, 1, 100);
  sqlite3_stmt *statement = nullptr;
  const bool response_page = !page_json.empty();
  const char *cached_page_sql =
      "SELECT item.media_id,item.media_type,item.captured_at,item.created_at,item.duration_ms,"
      "item.original_total_size,resource.resource_id,resource.resource_type,resource.mime_type,"
      "resource.content_size,resource.content_sha256_base64 "
      "FROM private_media_items_v2 item "
      "LEFT JOIN private_media_resources resource ON resource.user_id=item.user_id "
      "AND resource.resource_id=item.preview_resource_id "
      "WHERE item.user_id=? ORDER BY item.captured_at DESC,item.media_id DESC LIMIT ?";
  const char *response_page_sql =
      "SELECT item.media_id,item.media_type,item.captured_at,item.created_at,item.duration_ms,"
      "item.original_total_size,resource.resource_id,resource.resource_type,resource.mime_type,"
      "resource.content_size,resource.content_sha256_base64 "
      "FROM json_each(?2,'$.items') page "
      "JOIN private_media_items_v2 item ON item.user_id=?1 "
      "AND item.media_id=json_extract(page.value,'$.id') "
      "LEFT JOIN private_media_resources resource ON resource.user_id=item.user_id "
      "AND resource.resource_id=item.preview_resource_id "
      "ORDER BY CAST(page.key AS INTEGER) LIMIT ?3";
  const char *sql = response_page ? response_page_sql : cached_page_sql;
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return {};
  int status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                                 SQLITE_TRANSIENT);
  if (response_page) {
    if (status == SQLITE_OK) {
      status = sqlite3_bind_text(statement, 2, page_json.c_str(),
                                 static_cast<int>(page_json.size()), SQLITE_TRANSIENT);
    }
    if (status == SQLITE_OK) status = sqlite3_bind_int(statement, 3, limit);
  } else if (status == SQLITE_OK) {
    status = sqlite3_bind_int(statement, 2, limit);
  }
  if (status != SQLITE_OK) {
    sqlite3_finalize(statement);
    return {};
  }
  std::string result = "{\"items\":[";
  int count = 0;
  while ((status = sqlite3_step(statement)) == SQLITE_ROW) {
    const auto text_at = [statement](int column) -> std::string {
      const auto *value = sqlite3_column_text(statement, column);
      return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
    };
    if (count++ > 0) result += ',';
    result += "{\"id\":\"" + json_escape(text_at(0)) + "\",\"mediaType\":\"" +
        json_escape(text_at(1)) + "\",\"capturedAt\":\"" + json_escape(text_at(2)) +
        "\",\"createdAt\":\"" + json_escape(text_at(3)) + "\",\"durationMs\":" +
        (sqlite3_column_type(statement, 4) == SQLITE_NULL ? std::string("null") :
         std::to_string(sqlite3_column_int64(statement, 4))) +
        ",\"originalTotalSize\":" + std::to_string(sqlite3_column_int64(statement, 5)) +
        ",\"previewResource\":";
    if (sqlite3_column_type(statement, 6) == SQLITE_NULL) {
      result += "null";
    } else {
      result += "{\"resourceId\":\"" + json_escape(text_at(6)) +
          "\",\"resourceType\":\"" + json_escape(text_at(7)) +
          "\",\"mimeType\":\"" + json_escape(text_at(8)) +
          "\",\"contentSize\":" + std::to_string(sqlite3_column_int64(statement, 9)) +
          ",\"contentSha256\":\"" + json_escape(text_at(10)) + "\"}";
    }
    const LocalMediaMapping local_mapping = find_local_media_mapping(
        database_, active_account_session_->user_id, text_at(0));
    result += ",\"localPlatformAssetRef\":" +
        (local_mapping.platform_asset_ref.empty() ? std::string("null") :
         "\"" + json_escape(local_mapping.platform_asset_ref) + "\"") +
        ",\"localSourceUri\":" +
        (local_mapping.source_uri.empty() ? std::string("null") :
         "\"" + json_escape(local_mapping.source_uri) + "\"") + "}";
  }
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return {};
  sqlite3_stmt *state = nullptr;
  std::string next_cursor;
  std::string refreshed_at;
  bool fully_loaded = false;
  if (sqlite3_prepare_v2(database_,
                         "SELECT COALESCE(next_cursor,''),fully_loaded,refreshed_at "
                         "FROM private_media_page_state_v2 WHERE user_id=?", -1,
                         &state, nullptr) == SQLITE_OK) {
    int state_status = sqlite3_bind_text(state, 1, active_account_session_->user_id.c_str(), -1,
                                         SQLITE_TRANSIENT);
    if (state_status == SQLITE_OK && sqlite3_step(state) == SQLITE_ROW) {
      const auto *cursor = sqlite3_column_text(state, 0);
      const auto *refreshed = sqlite3_column_text(state, 2);
      if (cursor != nullptr) next_cursor = reinterpret_cast<const char *>(cursor);
      if (refreshed != nullptr) refreshed_at = reinterpret_cast<const char *>(refreshed);
      fully_loaded = sqlite3_column_int(state, 1) == 1;
    }
  }
  sqlite3_finalize(state);
  result += "],\"nextCursor\":" + (next_cursor.empty() ? std::string("null") :
      "\"" + json_escape(next_cursor) + "\"") + ",\"fullyLoaded\":" +
      (fully_loaded ? "true" : "false") + ",\"refreshedAt\":" +
      (refreshed_at.empty() ? std::string("null") : "\"" + json_escape(refreshed_at) + "\"") + "}";
  return result;
}

bool Core::persist_private_media_page_v2_locked(const std::string &page_json, bool replace) {
  if (active_account_session_ == nullptr) return false;
  sqlite3_stmt *validation = nullptr;
  const char *validation_sql =
      "SELECT coalesce(json_array_length(?1,'$.items'),-1),"
      "coalesce((SELECT count(*) FROM json_each(?1,'$.items') item WHERE "
      "length(coalesce(json_extract(item.value,'$.id'),''))=0 OR "
      "length(coalesce(json_extract(item.value,'$.media_type'),''))=0 OR "
      "length(coalesce(json_extract(item.value,'$.captured_at'),''))=0 OR "
      "length(coalesce(json_extract(item.value,'$.created_at'),''))=0 OR "
      "coalesce(json_extract(item.value,'$.original_total_size'),-1)<0 OR "
      "(json_type(item.value,'$.content_revision') IS NOT NULL AND "
      "(json_type(item.value,'$.content_revision')<>'integer' OR "
      "json_extract(item.value,'$.content_revision')<1))),-1)";
  if (sqlite3_prepare_v2(database_, validation_sql, -1, &validation, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(validation, 1, page_json.c_str(), static_cast<int>(page_json.size()),
                                 SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(validation);
  const int item_count = status == SQLITE_ROW ? sqlite3_column_int(validation, 0) : -1;
  const int invalid_count = status == SQLITE_ROW ? sqlite3_column_int(validation, 1) : -1;
  sqlite3_finalize(validation);
  if (item_count < 0 || item_count > 100 || invalid_count != 0) return false;
  if (sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) return false;
  const auto rollback = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  };
  sqlite3_stmt *statement = nullptr;
  if (replace) {
    if (sqlite3_prepare_v2(database_, "DELETE FROM private_media_items_v2 WHERE user_id=?", -1,
                           &statement, nullptr) != SQLITE_OK) return rollback();
    status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1,
                               SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(statement);
    sqlite3_finalize(statement);
    if (status != SQLITE_DONE) return rollback();
  } else {
    const char *clear_previews =
        "DELETE FROM private_media_resources WHERE user_id=? AND media_id IN "
        "(SELECT json_extract(item.value,'$.id') FROM json_each(?,'$.items') item)";
    if (sqlite3_prepare_v2(database_, clear_previews, -1, &statement, nullptr) != SQLITE_OK) return rollback();
    status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, page_json.c_str(), static_cast<int>(page_json.size()), SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(statement);
    sqlite3_finalize(statement);
    if (status != SQLITE_DONE) return rollback();
  }
  const char *insert_items =
      "INSERT INTO private_media_items_v2(user_id,media_id,media_type,captured_at,created_at,"
      "duration_ms,original_total_size,preview_resource_id,content_revision,updated_at) "
      "SELECT ?1,json_extract(item.value,'$.id'),json_extract(item.value,'$.media_type'),"
      "json_extract(item.value,'$.captured_at'),json_extract(item.value,'$.created_at'),"
      "json_extract(item.value,'$.duration_ms'),json_extract(item.value,'$.original_total_size'),"
      "json_extract(item.value,'$.preview_resource.resource_id'),coalesce(json_extract(item.value,'$.content_revision'),1),?3 FROM json_each(?2,'$.items') item WHERE 1 "
      "ON CONFLICT(user_id,media_id) DO UPDATE SET media_type=excluded.media_type,"
      "captured_at=excluded.captured_at,created_at=excluded.created_at,duration_ms=excluded.duration_ms,"
      "original_total_size=excluded.original_total_size,preview_resource_id=excluded.preview_resource_id,"
      "content_revision=excluded.content_revision,updated_at=excluded.updated_at";
  if (sqlite3_prepare_v2(database_, insert_items, -1, &statement, nullptr) != SQLITE_OK) return rollback();
  const std::string refreshed_at = now_rfc3339();
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, page_json.c_str(), static_cast<int>(page_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, refreshed_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return rollback();
  const char *insert_preview =
      "INSERT INTO private_media_resources(user_id,media_id,resource_id,resource_type,mime_type,"
      "content_size,content_sha256_base64) "
      "SELECT ?1,json_extract(item.value,'$.id'),json_extract(item.value,'$.preview_resource.resource_id'),"
      "json_extract(item.value,'$.preview_resource.resource_type'),json_extract(item.value,'$.preview_resource.mime_type'),"
      "json_extract(item.value,'$.preview_resource.content_size'),json_extract(item.value,'$.preview_resource.content_sha256') "
      "FROM json_each(?2,'$.items') item WHERE json_type(item.value,'$.preview_resource')='object'";
  if (sqlite3_prepare_v2(database_, insert_preview, -1, &statement, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, page_json.c_str(), static_cast<int>(page_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return rollback();
  const char *state_sql =
      "INSERT INTO private_media_page_state_v2(user_id,next_cursor,fully_loaded,refreshed_at) "
      "VALUES(?1,json_extract(?2,'$.next_cursor'),CASE WHEN json_extract(?2,'$.next_cursor') IS NULL THEN 1 ELSE 0 END,?3) "
      "ON CONFLICT(user_id) DO UPDATE SET next_cursor=excluded.next_cursor,"
      "fully_loaded=excluded.fully_loaded,refreshed_at=excluded.refreshed_at";
  if (sqlite3_prepare_v2(database_, state_sql, -1, &statement, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, page_json.c_str(), static_cast<int>(page_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, refreshed_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE || sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) return rollback();
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage05-v1\",\"type\":\"PrivateMediaPageChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" +
              json_escape(active_account_session_->user_id) + "\",\"itemCount\":" +
              std::to_string(item_count) + "}");
  return true;
}

std::string Core::read_private_media_detail_v2_locked(const std::string &media_id) {
  if (active_account_session_ == nullptr || media_id.empty()) return {};
  sqlite3_stmt *item = nullptr;
  const char *item_sql =
      "SELECT media_id,media_type,captured_at,created_at,width,height,duration_ms,original_total_size "
      "FROM private_media_items_v2 WHERE user_id=? AND media_id=?";
  if (sqlite3_prepare_v2(database_, item_sql, -1, &item, nullptr) != SQLITE_OK) return {};
  int status = sqlite3_bind_text(item, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(item, 2, media_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(item);
  if (status != SQLITE_ROW) {
    sqlite3_finalize(item);
    return {};
  }
  const auto text_at = [item](int column) -> std::string {
    const auto *value = sqlite3_column_text(item, column);
    return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
  };
  std::string result = "{\"id\":\"" + json_escape(text_at(0)) + "\",\"mediaType\":\"" +
      json_escape(text_at(1)) + "\",\"capturedAt\":\"" + json_escape(text_at(2)) +
      "\",\"createdAt\":\"" + json_escape(text_at(3)) + "\",\"width\":" +
      (sqlite3_column_type(item, 4) == SQLITE_NULL ? std::string("null") : std::to_string(sqlite3_column_int(item, 4))) +
      ",\"height\":" +
      (sqlite3_column_type(item, 5) == SQLITE_NULL ? std::string("null") : std::to_string(sqlite3_column_int(item, 5))) +
      ",\"durationMs\":" +
      (sqlite3_column_type(item, 6) == SQLITE_NULL ? std::string("null") : std::to_string(sqlite3_column_int64(item, 6))) +
      ",\"originalTotalSize\":" + std::to_string(sqlite3_column_int64(item, 7));
  sqlite3_finalize(item);
  const LocalMediaMapping local_mapping = find_local_media_mapping(
      database_, active_account_session_->user_id, media_id);
  result += ",\"localPlatformAssetRef\":" +
      (local_mapping.platform_asset_ref.empty() ? std::string("null") :
       "\"" + json_escape(local_mapping.platform_asset_ref) + "\"") +
      ",\"localSourceUri\":" +
      (local_mapping.source_uri.empty() ? std::string("null") :
       "\"" + json_escape(local_mapping.source_uri) + "\"") + ",\"resources\":[";
  sqlite3_stmt *resources = nullptr;
  const char *resources_sql =
      "SELECT resource_id,resource_type,mime_type,content_size,content_sha256_base64 "
      "FROM private_media_resources WHERE user_id=? AND media_id=? "
      "ORDER BY CASE resource_type WHEN 'ORIGINAL' THEN 1 WHEN 'LIVE_PHOTO_VIDEO' THEN 2 "
      "WHEN 'THUMBNAIL' THEN 3 WHEN 'VIDEO_COVER' THEN 4 WHEN 'PREVIEW' THEN 5 ELSE 6 END,resource_id";
  if (sqlite3_prepare_v2(database_, resources_sql, -1, &resources, nullptr) != SQLITE_OK) return {};
  status = sqlite3_bind_text(resources, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(resources, 2, media_id.c_str(), -1, SQLITE_TRANSIENT);
  int count = 0;
  if (status != SQLITE_OK) {
    sqlite3_finalize(resources);
    return {};
  }
  while ((status = sqlite3_step(resources)) == SQLITE_ROW) {
    const auto resource_text = [resources](int column) -> std::string {
      const auto *value = sqlite3_column_text(resources, column);
      return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
    };
    if (count++ > 0) result += ',';
    result += "{\"resourceId\":\"" + json_escape(resource_text(0)) +
        "\",\"resourceType\":\"" + json_escape(resource_text(1)) +
        "\",\"mimeType\":\"" + json_escape(resource_text(2)) +
        "\",\"contentSize\":" + std::to_string(sqlite3_column_int64(resources, 3)) +
        ",\"contentSha256\":\"" + json_escape(resource_text(4)) + "\"}";
  }
  sqlite3_finalize(resources);
  if (status != SQLITE_DONE) return {};
  return result + "]}";
}

bool Core::persist_private_media_detail_v2_locked(const std::string &detail_json) {
  if (active_account_session_ == nullptr) return false;
  sqlite3_stmt *validation = nullptr;
  const char *validation_sql =
      "SELECT length(coalesce(json_extract(?1,'$.id'),'')),"
      "length(coalesce(json_extract(?1,'$.media_type'),'')),"
      "length(coalesce(json_extract(?1,'$.captured_at'),'')),"
      "length(coalesce(json_extract(?1,'$.created_at'),'')),"
      "coalesce(json_extract(?1,'$.original_total_size'),-1),"
      "CASE WHEN json_type(?1,'$.content_revision') IS NULL THEN 1 "
      "WHEN json_type(?1,'$.content_revision')='integer' THEN json_extract(?1,'$.content_revision') "
      "ELSE 0 END,"
      "coalesce((SELECT count(*) FROM json_each(?1,'$.resources') resource WHERE "
      "length(coalesce(json_extract(resource.value,'$.resource_id'),''))=0 OR "
      "length(coalesce(json_extract(resource.value,'$.resource_type'),''))=0 OR "
      "length(coalesce(json_extract(resource.value,'$.mime_type'),''))=0 OR "
      "coalesce(json_extract(resource.value,'$.content_size'),0)<1 OR "
      "length(coalesce(json_extract(resource.value,'$.content_sha256'),''))=0),-1)";
  if (sqlite3_prepare_v2(database_, validation_sql, -1, &validation, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(validation, 1, detail_json.c_str(), static_cast<int>(detail_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(validation);
  const bool valid = status == SQLITE_ROW && sqlite3_column_int(validation, 0) > 0 &&
      sqlite3_column_int(validation, 1) > 0 && sqlite3_column_int(validation, 2) > 0 &&
      sqlite3_column_int(validation, 3) > 0 && sqlite3_column_int64(validation, 4) >= 0 &&
      sqlite3_column_int64(validation, 5) >= 1 && sqlite3_column_int(validation, 6) == 0;
  sqlite3_finalize(validation);
  if (!valid) return false;
  if (sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) return false;
  const auto rollback = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  };
  sqlite3_stmt *statement = nullptr;
  const std::string updated_at = now_rfc3339();
  const char *upsert_item =
      "INSERT INTO private_media_items_v2(user_id,media_id,media_type,captured_at,created_at,width,height,"
      "duration_ms,original_total_size,content_revision,updated_at) VALUES(?1,json_extract(?2,'$.id'),"
      "json_extract(?2,'$.media_type'),json_extract(?2,'$.captured_at'),json_extract(?2,'$.created_at'),"
      "json_extract(?2,'$.width'),json_extract(?2,'$.height'),json_extract(?2,'$.duration_ms'),"
      "json_extract(?2,'$.original_total_size'),coalesce(json_extract(?2,'$.content_revision'),1),?3) ON CONFLICT(user_id,media_id) DO UPDATE SET "
      "media_type=excluded.media_type,captured_at=excluded.captured_at,created_at=excluded.created_at,"
      "width=excluded.width,height=excluded.height,duration_ms=excluded.duration_ms,"
      "original_total_size=excluded.original_total_size,content_revision=excluded.content_revision,"
      "updated_at=excluded.updated_at";
  if (sqlite3_prepare_v2(database_, upsert_item, -1, &statement, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, detail_json.c_str(), static_cast<int>(detail_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, updated_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return rollback();
  const char *clear_resources =
      "DELETE FROM private_media_resources WHERE user_id=? AND media_id=json_extract(?,'$.id')";
  if (sqlite3_prepare_v2(database_, clear_resources, -1, &statement, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, detail_json.c_str(), static_cast<int>(detail_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return rollback();
  const char *insert_resources =
      "INSERT INTO private_media_resources(user_id,media_id,resource_id,resource_type,mime_type,content_size,content_sha256_base64) "
      "SELECT ?1,json_extract(?2,'$.id'),json_extract(resource.value,'$.resource_id'),"
      "json_extract(resource.value,'$.resource_type'),json_extract(resource.value,'$.mime_type'),"
      "json_extract(resource.value,'$.content_size'),json_extract(resource.value,'$.content_sha256') "
      "FROM json_each(?2,'$.resources') resource";
  if (sqlite3_prepare_v2(database_, insert_resources, -1, &statement, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, detail_json.c_str(), static_cast<int>(detail_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return rollback();
  const char *select_preview =
      "UPDATE private_media_items_v2 SET preview_resource_id=(SELECT resource_id FROM private_media_resources "
      "WHERE user_id=?1 AND media_id=json_extract(?2,'$.id') AND resource_type IN "
      "('THUMBNAIL','VIDEO_COVER','PREVIEW','DYNAMIC_PREVIEW') ORDER BY CASE resource_type "
      "WHEN 'THUMBNAIL' THEN 1 WHEN 'VIDEO_COVER' THEN 2 WHEN 'PREVIEW' THEN 3 ELSE 4 END LIMIT 1) "
      "WHERE user_id=?1 AND media_id=json_extract(?2,'$.id')";
  if (sqlite3_prepare_v2(database_, select_preview, -1, &statement, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, detail_json.c_str(), static_cast<int>(detail_json.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE || sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) return rollback();
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage05-v1\",\"type\":\"PrivateMediaDetailChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(active_account_session_->user_id) +
              "\",\"mediaId\":\"" + json_escape(sqlite_json_text(database_, detail_json, "$.id")) + "\"}");
  return true;
}

bool Core::remove_private_media_v2_locked(const std::string &media_id) {
  if (active_account_session_ == nullptr || media_id.empty()) return false;
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, "DELETE FROM private_media_items_v2 WHERE user_id=? AND media_id=?", -1,
                         &statement, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(statement, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, media_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return false;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage05-v1\",\"type\":\"PrivateMediaTrashed\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(active_account_session_->user_id) +
              "\",\"mediaId\":\"" + json_escape(media_id) + "\"}");
  return true;
}

bool Core::record_private_media_system_save_locked(const std::string &media_id,
                                                    const std::string &resource_id,
                                                    const std::string &platform_asset_ref,
                                                    std::string &error_code) {
  error_code.clear();
  static constexpr std::string_view kAndroidAssetPrefix = "android:media-store:";
  const bool valid_asset_ref = platform_asset_ref.rfind(kAndroidAssetPrefix, 0) == 0 &&
      platform_asset_ref.size() > kAndroidAssetPrefix.size() &&
      platform_asset_ref[kAndroidAssetPrefix.size()] >= '1' &&
      platform_asset_ref[kAndroidAssetPrefix.size()] <= '9' &&
      std::all_of(platform_asset_ref.begin() + static_cast<std::ptrdiff_t>(kAndroidAssetPrefix.size()),
                  platform_asset_ref.end(), [](unsigned char value) { return value >= '0' && value <= '9'; });
  if (active_account_session_ == nullptr) {
    error_code = "SESSION_INVALID";
    return false;
  }
  if (!valid_asset_ref) {
    error_code = "PRIVATE_MEDIA_SYSTEM_ALBUM_WRITE_FAILED";
    return false;
  }

  sqlite3_stmt *resource = nullptr;
  const char *resource_sql =
      "SELECT item.content_revision,resource.resource_type FROM private_media_items_v2 item "
      "JOIN private_media_resources resource ON resource.user_id=item.user_id AND resource.media_id=item.media_id "
      "WHERE item.user_id=? AND item.media_id=? AND resource.resource_id=?";
  if (sqlite3_prepare_v2(database_, resource_sql, -1, &resource, nullptr) != SQLITE_OK) {
    error_code = "DATABASE_ERROR";
    return false;
  }
  int status = sqlite3_bind_text(resource, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(resource, 2, media_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(resource, 3, resource_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(resource);
  if (status != SQLITE_ROW) {
    sqlite3_finalize(resource);
    error_code = status == SQLITE_DONE ? "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE" : "DATABASE_ERROR";
    return false;
  }
  const int64_t content_revision = sqlite3_column_int64(resource, 0);
  const auto *resource_type = sqlite3_column_text(resource, 1);
  const bool original = resource_type != nullptr &&
      std::string_view(reinterpret_cast<const char *>(resource_type)) == "ORIGINAL";
  sqlite3_finalize(resource);
  if (!original) {
    error_code = "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE";
    return false;
  }

  sqlite3_stmt *resources = nullptr;
  const char *resources_sql =
      "SELECT resource_id,content_sha256_base64 FROM private_media_resources "
      "WHERE user_id=? AND media_id=? ORDER BY resource_id";
  if (sqlite3_prepare_v2(database_, resources_sql, -1, &resources, nullptr) != SQLITE_OK) {
    error_code = "DATABASE_ERROR";
    return false;
  }
  status = sqlite3_bind_text(resources, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(resources, 2, media_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status != SQLITE_OK) {
    sqlite3_finalize(resources);
    error_code = "DATABASE_ERROR";
    return false;
  }
  std::string resource_set;
  while ((status = sqlite3_step(resources)) == SQLITE_ROW) {
    const auto *id = sqlite3_column_text(resources, 0);
    const auto *digest = sqlite3_column_text(resources, 1);
    if (id == nullptr || digest == nullptr) {
      sqlite3_finalize(resources);
      error_code = "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE";
      return false;
    }
    resource_set += reinterpret_cast<const char *>(id);
    resource_set += ':';
    resource_set += reinterpret_cast<const char *>(digest);
    resource_set += ';';
  }
  sqlite3_finalize(resources);
  if (status != SQLITE_DONE || resource_set.empty()) {
    error_code = status == SQLITE_DONE ? "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE" : "DATABASE_ERROR";
    return false;
  }
  const std::string resource_set_digest = command_digest(resource_set);
  wipe_string(resource_set);

  sqlite3_stmt *receipt = nullptr;
  const char *receipt_sql =
      "INSERT INTO download_receipts(user_id,cloud_media_id,platform_asset_ref,created_at,"
      "content_revision,resource_set_digest,updated_at) VALUES(?,?,?,?,?,?,?) "
      "ON CONFLICT(user_id,cloud_media_id) DO UPDATE SET "
      "platform_asset_ref=excluded.platform_asset_ref,"
      "content_revision=excluded.content_revision,"
      "resource_set_digest=excluded.resource_set_digest,"
      "updated_at=excluded.updated_at";
  if (sqlite3_prepare_v2(database_, receipt_sql, -1, &receipt, nullptr) != SQLITE_OK) {
    error_code = "DATABASE_ERROR";
    return false;
  }
  const std::string now = now_rfc3339();
  status = sqlite3_bind_text(receipt, 1, active_account_session_->user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(receipt, 2, media_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(receipt, 3, platform_asset_ref.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(receipt, 4, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int64(receipt, 5, content_revision);
  if (status == SQLITE_OK) status = sqlite3_bind_text(receipt, 6, resource_set_digest.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(receipt, 7, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(receipt);
  sqlite3_finalize(receipt);
  if (status != SQLITE_DONE) {
    error_code = "DATABASE_ERROR";
    return false;
  }
  return true;
}

bool Core::prepare_private_media_view_resource_locked(AccountOperation &operation,
                                                      const std::string &access_json) {
  if (active_account_session_ == nullptr || operation.private_media_id.empty() ||
      (operation.private_media_view_variant != "THUMBNAIL" &&
       operation.private_media_view_variant != "DETAIL") ||
      sqlite_json_text(database_, access_json, "$.media_id") != operation.private_media_id ||
      sqlite_json_text(database_, access_json, "$.purpose") != "VIEW" ||
      sqlite_json_text(database_, access_json, "$.variant") != operation.private_media_view_variant ||
      json_array_length(database_, access_json, "$.resources") != 1) {
    return false;
  }
  for (auto &value : operation.private_media_resource_ids) wipe_string(value);
  operation.private_media_resource_ids.clear();
  for (auto &value : operation.private_media_resource_types) wipe_string(value);
  operation.private_media_resource_types.clear();
  for (auto &value : operation.private_media_resource_mime_types) wipe_string(value);
  operation.private_media_resource_mime_types.clear();
  for (auto &value : operation.private_media_resource_digests) wipe_string(value);
  operation.private_media_resource_digests.clear();
  for (auto &value : operation.private_media_resource_urls) wipe_string(value);
  operation.private_media_resource_urls.clear();
  for (auto &value : operation.private_media_resource_headers) wipe_string(value);
  operation.private_media_resource_headers.clear();
  operation.private_media_resource_sizes.clear();
  operation.private_media_view_maximum_output_size = 0;
  operation.private_media_view_uses_oss_image_thumbnail = false;

  const std::string prefix = "$.resources[0]";
  const std::string resource_id = sqlite_json_text(database_, access_json, prefix + ".resource_id");
  const std::string resource_type = sqlite_json_text(database_, access_json, prefix + ".resource_type");
  const std::string mime_type = sqlite_json_text(database_, access_json, prefix + ".mime_type");
  const int64_t content_size = sqlite_json_integer(database_, access_json, prefix + ".content_size", -1);
  const std::string digest = sqlite_json_text(database_, access_json, prefix + ".content_sha256");
  const std::string delivery_mode = sqlite_json_text(database_, access_json, prefix + ".delivery_mode");
  const int64_t maximum_output_size = sqlite_json_integer(database_, access_json,
                                                           prefix + ".maximum_output_size", -1);
  const std::string method = sqlite_json_text(database_, access_json, prefix + ".grant.method");
  const std::string url = sqlite_json_text(database_, access_json, prefix + ".grant.url");
  const std::string headers = sqlite_json_text(database_, access_json, prefix + ".grant.headers");
  std::string decoded_digest;
  const bool digest_valid = base64_decode(digest, decoded_digest) &&
      decoded_digest.size() == crypto_hash_sha256_BYTES;
  wipe_string(decoded_digest);
  const bool thumbnail_type = resource_type == "THUMBNAIL" || resource_type == "VIDEO_COVER";
  const bool detail_type = thumbnail_type || resource_type == "PREVIEW" ||
      resource_type == "DYNAMIC_PREVIEW";
  const bool oss_image_thumbnail = delivery_mode == "OSS_IMAGE_THUMBNAIL";
  const bool original_delivery = delivery_mode == "ORIGINAL_RESOURCE";
  const bool original_svg_thumbnail = original_delivery &&
      operation.private_media_view_variant == "THUMBNAIL" && resource_type == "ORIGINAL" &&
      mime_type == "image/svg+xml" && content_size <= 5LL * 1024LL * 1024LL;
  const bool original_image_detail = original_delivery &&
      operation.private_media_view_variant == "DETAIL" && resource_type == "ORIGINAL" &&
      mime_type.rfind("image/", 0) == 0;
  if (resource_id.size() != 36U ||
      resource_id.find_first_not_of("0123456789abcdefABCDEF-") != std::string::npos ||
      !(oss_image_thumbnail
          ? (operation.private_media_view_variant == "THUMBNAIL" && resource_type == "ORIGINAL" &&
             mime_type.rfind("image/", 0) == 0 && maximum_output_size > 0 &&
             maximum_output_size <= 5LL * 1024LL * 1024LL)
          : (original_svg_thumbnail || original_image_detail ||
             (original_delivery && (operation.private_media_view_variant == "THUMBNAIL" ? thumbnail_type : detail_type)))) ||
      mime_type.empty() || mime_type.size() > 128U || content_size < 1 || method != "GET" ||
      url.empty() || url.size() > 8192U || !digest_valid || headers.empty() ||
      !valid_json(database_, headers) || headers.front() != '{') {
    return false;
  }
  operation.private_media_resource_ids.push_back(resource_id);
  operation.private_media_resource_types.push_back(resource_type);
  operation.private_media_resource_mime_types.push_back(mime_type);
  operation.private_media_resource_digests.push_back(digest);
  operation.private_media_resource_urls.push_back(url);
  operation.private_media_resource_headers.push_back(headers);
  operation.private_media_resource_sizes.push_back(content_size);
  operation.private_media_view_uses_oss_image_thumbnail = oss_image_thumbnail;
  operation.private_media_view_maximum_output_size = oss_image_thumbnail ? maximum_output_size : content_size;
  return true;
}

void Core::clear_account_session_locked() {
  active_account_session_.reset();
  sqlite3_exec(database_, "DELETE FROM account_state WHERE singleton=1", nullptr, nullptr, nullptr);
}

mineg_error_code_t Core::start_account_operation_locked(uint64_t operation_id,
                                                         const std::string &command,
                                                         std::string &result) {
  const std::string contract_version = top_level_json_string(command, "contractVersion");
  if (!valid_json(database_, command) ||
      (contract_version != "account-v3" &&
       contract_version != "stage02-v2" && contract_version != "stage03-v2" &&
       contract_version != "stage04-v1" && contract_version != "stage05-v1" &&
       contract_version != "stage06-v1")) {
    return MINEG_INVALID_ARGUMENT;
  }
  const std::string digest = command_digest(command);
  const auto existing = account_operations_.find(operation_id);
  if (existing != account_operations_.end()) {
    return existing->second->command_digest == digest
        ? account_operation_step_locked(*existing->second, result)
        : MINEG_INVALID_ARGUMENT;
  }
  std::string persisted_step;
  const mineg_error_code_t persisted = read_operation_step_locked(operation_id, persisted_step);
  if (persisted == MINEG_OK) return MINEG_INVALID_ARGUMENT;
  if (persisted != MINEG_NOT_FOUND) return persisted;

  auto operation = std::make_unique<AccountOperation>();
  operation->operation_id = operation_id;
  operation->command_digest = digest;
  operation->contract_version = contract_version;
  operation->type = top_level_json_string(command, "type");
  AccountOperation *value = operation.get();
  account_operations_.emplace(operation_id, std::move(operation));

  const auto fail = [this, value, &result](const std::string &code) {
    finish_account_error_locked(*value, code, false);
    return account_operation_step_locked(*value, result);
  };
  const bool stage02_type = value->type == "PrivateMediaList" || value->type == "ProfileUpdateAvatar" ||
      value->type == "StartForegroundLocalScan";
  const bool stage03_type = value->type == "BackupSingleMedia";
  const bool stage04_type = value->type == "ReconcileBackupQueue" ||
      value->type == "RunBackupCycle";
  const bool stage05_type = value->type == "RefreshPrivateMedia" ||
      value->type == "LoadMorePrivateMedia" || value->type == "GetPrivateMediaDetail" ||
      value->type == "OpenPrivateMedia" || value->type == "ClosePrivateMedia" ||
      value->type == "TrashPrivateMedia" || value->type == "RecordPrivateMediaSystemSave";
  const bool stage06_type = value->type == "SetPrivateMediaShare" ||
      value->type == "RefreshFamilyMedia" || value->type == "LoadMoreFamilyMedia" ||
      value->type == "GetFamilyMediaDetail" || value->type == "OpenFamilyMedia" ||
      value->type == "CloseFamilyMedia" || value->type == "RefreshTrashMedia" ||
      value->type == "LoadMoreTrashMedia" || value->type == "RestoreTrashMedia" ||
      value->type == "SubmitFeedback";
  if ((contract_version == "stage02-v2") != stage02_type ||
      (contract_version == "stage03-v2") != stage03_type ||
      (contract_version == "stage04-v1") != stage04_type ||
      (contract_version == "stage05-v1") != stage05_type ||
      (contract_version == "stage06-v1") != stage06_type) {
    return fail("COMMAND_NOT_SUPPORTED");
  }
  if (value->type == "AccountSignIn" || value->type == "AccountSignUp") {
    value->phone = normalize_phone(top_level_json_string(command, "phone"));
    value->password = top_level_json_string(command, "password");
    if (value->phone.empty()) return fail("PHONE_INVALID");
    if (!valid_password(value->password)) return fail("PASSWORD_INVALID");
    value->masked_phone = masked_phone(value->phone);
    if (value->type == "AccountSignIn" &&
        !extract_json_boolean(command, "agreementAccepted", false)) {
      return fail("AGREEMENT_REQUIRED");
    }
    if (value->type == "AccountSignUp") {
      value->idempotency_key = top_level_json_string(command, "idempotencyKey");
      if (value->idempotency_key.size() < 8U || value->idempotency_key.size() > 128U ||
          value->idempotency_key.find_first_not_of(
              "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-") !=
              std::string::npos) {
        return fail("IDEMPOTENCY_KEY_INVALID");
      }
    }
    set_account_effect_locked(
        *value, "SecureStoreEffect",
        "{\"action\":\"readSecrets\",\"names\":[\"device.installationId\"]}",
        "READ_DEVICE");
  } else if (value->type == "AccountRestoreSession" || value->type == "AccountSignOut") {
    issue_session_read_locked(*value);
  } else if (value->type == "AccountRefreshReviewStatus" ||
             value->type == "ProfileGetCurrent" ||
             value->type == "ProfileUpdateCurrent") {
    value->allow_cached_profile = extract_json_boolean(command, "allowCached", false);
    if (value->type == "ProfileUpdateCurrent") {
      value->nickname = top_level_json_string(command, "nickname");
      if (value->nickname.size() < 2U || value->nickname.size() > 80U ||
          value->nickname.find_first_of("\r\n\t") != std::string::npos) {
        return fail("NICKNAME_INVALID");
      }
    }
    const std::string purpose = value->type == "AccountRefreshReviewStatus" ? "REVIEW" :
        value->type == "ProfileGetCurrent" ? "PROFILE_GET" : "PROFILE_UPDATE";
    if (active_account_session_ != nullptr) {
      const mineg_error_code_t code = issue_account_request_locked(*value, purpose);
      if (code != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = purpose;
      issue_session_read_locked(*value);
    }
  } else if (value->type == "PrivateMediaList") {
    value->media_limit = std::clamp<int64_t>(extract_json_integer(command, "limit", 100), 1, 100);
    value->allow_cached_media = extract_json_boolean(command, "allowCached", true);
    if (active_account_session_ != nullptr) {
      const mineg_error_code_t code = issue_account_request_locked(*value, "PRIVATE_MEDIA_LIST");
      if (code != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = "PRIVATE_MEDIA_LIST";
      issue_session_read_locked(*value);
    }
  } else if (value->type == "RefreshPrivateMedia" || value->type == "LoadMorePrivateMedia") {
    value->media_limit = std::clamp<int64_t>(extract_json_integer(command, "limit", 50), 1, 100);
    value->allow_cached_media = extract_json_boolean(command, "allowCached", true);
    if (value->type == "LoadMorePrivateMedia") {
      value->private_media_cursor = read_private_media_next_cursor_v2_locked();
      if (value->private_media_cursor.empty()) {
        value->status = "COMPLETED";
        value->terminal_payload = read_private_media_page_v2_locked(static_cast<int>(value->media_limit));
        value->clear_sensitive();
        return account_operation_step_locked(*value, result);
      }
    }
    const std::string purpose = value->type == "RefreshPrivateMedia" ?
        "PRIVATE_MEDIA_REFRESH" : "PRIVATE_MEDIA_LOAD_MORE";
    if (active_account_session_ != nullptr) {
      const mineg_error_code_t code = issue_account_request_locked(*value, purpose);
      if (code != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = purpose;
      issue_session_read_locked(*value);
    }
  } else if (value->type == "RecordPrivateMediaSystemSave") {
    const std::string media_id = top_level_json_string(command, "mediaId");
    const std::string resource_id = top_level_json_string(command, "resourceId");
    const std::string platform_asset_ref = top_level_json_string(command, "platformAssetRef");
    const auto valid_uuid = [](const std::string &id) {
      return id.size() == 36U &&
          id.find_first_not_of("0123456789abcdefABCDEF-") == std::string::npos;
    };
    if (!valid_uuid(media_id) || !valid_uuid(resource_id)) return fail("PRIVATE_MEDIA_INVALID");
    std::string error_code;
    if (!record_private_media_system_save_locked(media_id, resource_id, platform_asset_ref, error_code)) {
      return fail(error_code.empty() ? "DATABASE_ERROR" : error_code);
    }
    value->status = "COMPLETED";
    value->terminal_payload = "{\"mediaId\":\"" + json_escape(media_id) +
        "\",\"state\":\"COMPLETED\",\"savedResourceCount\":1}";
    value->clear_sensitive();
  } else if (value->type == "GetPrivateMediaDetail" || value->type == "OpenPrivateMedia" ||
             value->type == "TrashPrivateMedia") {
    value->private_media_id = top_level_json_string(command, "mediaId");
    if (value->private_media_id.size() != 36U ||
        value->private_media_id.find_first_not_of("0123456789abcdefABCDEF-") != std::string::npos) {
      return fail("PRIVATE_MEDIA_INVALID");
    }
    std::string purpose = "PRIVATE_MEDIA_DETAIL";
    if (value->type == "OpenPrivateMedia") {
      value->private_media_view_variant = top_level_json_string(command, "variant");
      if (value->private_media_view_variant.empty()) value->private_media_view_variant = "THUMBNAIL";
      if (value->private_media_view_variant != "THUMBNAIL" &&
          value->private_media_view_variant != "DETAIL") {
        return fail("PRIVATE_MEDIA_RESOURCE_UNAVAILABLE");
      }
      purpose = "PRIVATE_MEDIA_VIEW_ACCESS";
    }
    if (value->type == "TrashPrivateMedia") {
      value->idempotency_key = top_level_json_string(command, "idempotencyKey");
      if (value->idempotency_key.size() < 8U || value->idempotency_key.size() > 128U ||
          value->idempotency_key.find_first_not_of(
              "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-") != std::string::npos) {
        return fail("IDEMPOTENCY_KEY_INVALID");
      }
      purpose = "PRIVATE_MEDIA_TRASH";
    }
    if (active_account_session_ != nullptr) {
      const mineg_error_code_t code = issue_account_request_locked(*value, purpose);
      if (code != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = purpose;
      issue_session_read_locked(*value);
    }
  } else if (value->type == "ClosePrivateMedia") {
    value->private_media_view_handle = top_level_json_string(command, "viewHandle");
    if (value->private_media_view_handle.size() < 8U || value->private_media_view_handle.size() > 256U ||
        value->private_media_view_handle.find_first_not_of(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-") != std::string::npos) {
      return fail("PRIVATE_MEDIA_RESOURCE_UNAVAILABLE");
    }
    set_account_effect_locked(*value, "MediaPlaybackEffect",
        "{\"action\":\"closeVerifiedMedia\",\"viewHandle\":\"" +
        json_escape(value->private_media_view_handle) + "\"}", "PRIVATE_MEDIA_VIEW_CLOSE");
  } else if (value->type == "SetPrivateMediaShare" ||
             value->type == "GetFamilyMediaDetail" || value->type == "OpenFamilyMedia" ||
             value->type == "RestoreTrashMedia") {
    value->private_media_id = top_level_json_string(command, "mediaId");
    if (value->private_media_id.size() != 36U ||
        value->private_media_id.find_first_not_of("0123456789abcdefABCDEF-") != std::string::npos) {
      return fail("PRIVATE_MEDIA_INVALID");
    }
    std::string purpose = "FAMILY_MEDIA_DETAIL";
    if (value->type == "SetPrivateMediaShare" || value->type == "RestoreTrashMedia") {
      value->idempotency_key = top_level_json_string(command, "idempotencyKey");
      if (value->idempotency_key.size() < 8U || value->idempotency_key.size() > 128U ||
          value->idempotency_key.find_first_not_of(
              "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-") !=
              std::string::npos) {
        return fail("IDEMPOTENCY_KEY_INVALID");
      }
      if (value->type == "SetPrivateMediaShare") {
        value->private_media_share_active = extract_json_boolean(command, "shared", false);
        purpose = "PRIVATE_MEDIA_SHARE";
      } else {
        purpose = "TRASH_MEDIA_RESTORE";
      }
    } else if (value->type == "OpenFamilyMedia") {
      value->private_media_view_variant = top_level_json_string(command, "variant");
      if (value->private_media_view_variant.empty()) value->private_media_view_variant = "THUMBNAIL";
      if (value->private_media_view_variant != "THUMBNAIL" &&
          value->private_media_view_variant != "DETAIL") {
        return fail("FAMILY_MEDIA_RESOURCE_UNAVAILABLE");
      }
      purpose = "FAMILY_MEDIA_VIEW_ACCESS";
    }
    if (active_account_session_ != nullptr) {
      if (issue_account_request_locked(*value, purpose) != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = purpose;
      issue_session_read_locked(*value);
    }
  } else if (value->type == "RefreshFamilyMedia" || value->type == "LoadMoreFamilyMedia") {
    value->media_limit = std::clamp<int64_t>(extract_json_integer(command, "limit", 50), 1, 100);
    value->family_media_filter = top_level_json_string(command, "filter");
    if (value->family_media_filter.empty()) value->family_media_filter = "all";
    if (value->family_media_filter != "all" && value->family_media_filter != "mine") {
      return fail("FAMILY_FILTER_INVALID");
    }
    value->family_media_cursor = top_level_json_string(command, "cursor");
    const std::string purpose = "FAMILY_MEDIA_LIST";
    if (active_account_session_ != nullptr) {
      if (issue_account_request_locked(*value, purpose) != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = purpose;
      issue_session_read_locked(*value);
    }
  } else if (value->type == "RefreshTrashMedia" || value->type == "LoadMoreTrashMedia") {
    value->media_limit = std::clamp<int64_t>(extract_json_integer(command, "limit", 50), 1, 100);
    value->family_media_cursor = top_level_json_string(command, "cursor");
    const std::string purpose = "TRASH_MEDIA_LIST";
    if (active_account_session_ != nullptr) {
      if (issue_account_request_locked(*value, purpose) != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = purpose;
      issue_session_read_locked(*value);
    }
  } else if (value->type == "CloseFamilyMedia") {
    value->private_media_view_handle = top_level_json_string(command, "viewHandle");
    if (value->private_media_view_handle.size() < 8U || value->private_media_view_handle.size() > 256U ||
        value->private_media_view_handle.find_first_not_of(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-") != std::string::npos) {
      return fail("FAMILY_MEDIA_RESOURCE_UNAVAILABLE");
    }
    set_account_effect_locked(*value, "MediaPlaybackEffect",
        "{\"action\":\"closeVerifiedMedia\",\"viewHandle\":\"" +
        json_escape(value->private_media_view_handle) + "\"}", "PRIVATE_MEDIA_VIEW_CLOSE");
  } else if (value->type == "SubmitFeedback") {
    value->feedback_category = top_level_json_string(command, "category");
    value->feedback_description = top_level_json_string(command, "description");
    value->feedback_contact = top_level_json_string(command, "contact");
    value->feedback_app_version = top_level_json_string(command, "appVersion");
    value->feedback_os_version = top_level_json_string(command, "osVersion");
    value->idempotency_key = top_level_json_string(command, "idempotencyKey");
    const bool valid_category = value->feedback_category == "ACCOUNT" ||
        value->feedback_category == "PERMISSION" || value->feedback_category == "BACKUP" ||
        value->feedback_category == "BROWSE_PLAYBACK" || value->feedback_category == "SHARING" ||
        value->feedback_category == "TRASH" || value->feedback_category == "OTHER";
    const bool valid_idempotency = value->idempotency_key.size() >= 8U &&
        value->idempotency_key.size() <= 128U &&
        value->idempotency_key.find_first_not_of(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-") == std::string::npos;
    if (!valid_category || value->feedback_description.empty() ||
        value->feedback_description.size() > 4000U || value->feedback_contact.size() > 800U ||
        value->feedback_app_version.empty() || value->feedback_app_version.size() > 64U ||
        value->feedback_os_version.empty() || value->feedback_os_version.size() > 128U ||
        !valid_idempotency) {
      return fail("FEEDBACK_INVALID");
    }
    value->continuation = "FEEDBACK_SUBMIT";
    issue_session_read_locked(*value);
  } else if (value->type == "StartForegroundLocalScan") {
    value->user_id = top_level_json_string(command, "userId");
    if (value->user_id.empty() || value->user_id.size() > 128U) {
      return fail("USER_ID_INVALID");
    }
    const std::string account = read_account_state_locked();
    const std::string current_user = sqlite_json_text(database_, account, "$.state.userId");
    if (!current_user.empty() && current_user != value->user_id) {
      return fail("ACCOUNT_MISMATCH");
    }
    value->local_account_bound = !current_user.empty();
    set_account_effect_locked(*value, "MediaSourceEffect",
                              "{\"action\":\"getPermissionSnapshot\"}",
                              "LOCAL_SCAN_PERMISSION");
  } else if (value->type == "ReconcileBackupQueue") {
    value->user_id = top_level_json_string(command, "userId");
    value->device_installation_id = top_level_json_string(command, "deviceInstallationId");
    if (active_account_session_ == nullptr || active_account_session_->approval_status != "APPROVED" ||
        value->user_id.empty() || value->device_installation_id.empty() ||
        value->user_id != active_account_session_->user_id) {
      return fail("SESSION_INVALID");
    }
    value->local_account_bound = true;
    sqlite3_stmt *settings = nullptr;
    if (sqlite3_prepare_v2(database_, "SELECT auto_backup_enabled FROM backup_settings WHERE user_id=? "
                           "AND device_installation_id=?", -1, &settings, nullptr) != SQLITE_OK) {
      return fail("DATABASE_ERROR");
    }
    int settings_status = sqlite3_bind_text(settings, 1, value->user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (settings_status == SQLITE_OK) settings_status = sqlite3_bind_text(settings, 2, value->device_installation_id.c_str(), -1, SQLITE_TRANSIENT);
    if (settings_status == SQLITE_OK) settings_status = sqlite3_step(settings);
    const bool enabled = settings_status == SQLITE_ROW && sqlite3_column_int(settings, 0) == 1;
    sqlite3_finalize(settings);
    if (settings_status != SQLITE_ROW && settings_status != SQLITE_DONE) return fail("DATABASE_ERROR");
    if (!enabled) {
      value->status = "COMPLETED";
      value->terminal_payload = "{\"reconciled\":false,\"autoBackupDisabled\":true}";
      value->clear_sensitive();
      return account_operation_step_locked(*value, result);
    }
    set_account_effect_locked(*value, "MediaSourceEffect",
                              "{\"action\":\"getPermissionSnapshot\"}",
                              "LOCAL_SCAN_PERMISSION");
  } else if (value->type == "RunBackupCycle") {
    value->user_id = top_level_json_string(command, "userId");
    value->device_installation_id = top_level_json_string(command, "deviceInstallationId");
    if (active_account_session_ == nullptr || active_account_session_->approval_status != "APPROVED" ||
        value->user_id.empty() || value->device_installation_id.empty() ||
        value->user_id != active_account_session_->user_id) {
      return fail("SESSION_INVALID");
    }
    if (!claim_next_backup_task_locked(*value)) return fail("DATABASE_ERROR");
    if (value->backup_no_work) {
      value->status = "COMPLETED";
      value->terminal_payload = "{\"processed\":false}";
      value->clear_sensitive();
    } else {
      set_account_effect_locked(*value, "ConnectivityEffect",
                                "{\"action\":\"getConnectivitySnapshot\"}",
                                "BACKUP_CONNECTIVITY");
    }
  } else if (value->type == "ProfileUpdateAvatar") {
    value->idempotency_key = top_level_json_string(command, "idempotencyKey");
    value->avatar_content_type = top_level_json_string(command, "contentType");
    value->avatar_source_size = extract_json_integer(command, "sourceSize", 0);
    value->avatar_width = extract_json_integer(command, "width", 0);
    const std::string display_base64 = top_level_json_string(command, "displayBase64");
    const bool valid_idempotency = value->idempotency_key.size() >= 8U &&
        value->idempotency_key.size() <= 128U &&
        value->idempotency_key.find_first_not_of(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-") ==
            std::string::npos;
    const bool valid_content_type = value->avatar_content_type == "image/jpeg" ||
        value->avatar_content_type == "image/png" ||
        value->avatar_content_type == "image/heic" ||
        value->avatar_content_type == "image/heif" ||
        value->avatar_content_type == "image/webp";
    if (!valid_idempotency || !valid_content_type ||
        value->avatar_source_size < 1 || value->avatar_source_size > 10LL * 1024LL * 1024LL ||
        value->avatar_width < 1 || value->avatar_width > 1024 ||
        !base64_decode(display_base64, value->avatar_bytes) || value->avatar_bytes.empty() ||
        value->avatar_bytes.size() > 10U * 1024U * 1024U) {
      return fail("AVATAR_INVALID");
    }
    std::array<unsigned char, crypto_hash_sha256_BYTES> digest_bytes{};
    if (crypto_hash_sha256(digest_bytes.data(),
                           reinterpret_cast<const unsigned char *>(value->avatar_bytes.data()),
                           value->avatar_bytes.size()) != 0) {
      sodium_memzero(digest_bytes.data(), digest_bytes.size());
      return fail("CRYPTO_ERROR");
    }
    value->avatar_digest_base64 = base64_encode(digest_bytes.data(), digest_bytes.size(), false);
    sodium_memzero(digest_bytes.data(), digest_bytes.size());
    if (active_account_session_ != nullptr) {
      const mineg_error_code_t code = issue_account_request_locked(*value, "AVATAR_CREATE");
      if (code != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = "AVATAR_CREATE";
      issue_session_read_locked(*value);
    }
  } else if (value->type == "BackupSingleMedia") {
    value->user_id = top_level_json_string(command, "userId");
    value->media_asset_ref = top_level_json_string(command, "platformAssetRef");
    if (active_account_session_ == nullptr || active_account_session_->approval_status != "APPROVED" ||
        value->user_id != active_account_session_->user_id || value->media_asset_ref.empty()) {
      return fail("SESSION_INVALID");
    }
    sqlite3_stmt *statement = nullptr;
    const char *sql =
        "SELECT media.media_type,media.mime_type,media.captured_at,media.content_version,media.availability "
        "FROM local_media media JOIN local_library_active active ON active.user_id=media.user_id "
        "AND active.generation_id=media.generation_id WHERE media.user_id=? AND media.platform_asset_ref=?";
    if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
      return fail("DATABASE_ERROR");
    }
    int status = sqlite3_bind_text(statement, 1, value->user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, value->media_asset_ref.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(statement);
    const auto text_at = [statement](int column) -> std::string {
      const auto *text = sqlite3_column_text(statement, column);
      return text == nullptr ? std::string{} : reinterpret_cast<const char *>(text);
    };
    if (status == SQLITE_ROW) {
      value->media_type = text_at(0);
      value->media_mime_type = text_at(1);
      value->media_captured_at = text_at(2);
      value->media_content_version = text_at(3);
      if (text_at(4) != "AVAILABLE") status = SQLITE_DONE;
    }
    sqlite3_finalize(statement);
    if (status != SQLITE_ROW || value->media_type.empty() || value->media_mime_type.empty() || value->media_captured_at.empty()) {
      return fail("LOCAL_MEDIA_UNAVAILABLE");
    }
    value->idempotency_key = random_uuid();
    value->media_client_id = random_uuid();
    value->media_resource_id = random_uuid();
    set_account_effect_locked(*value, "MediaSourceEffect",
                              "{\"action\":\"openMediaResource\",\"platformAssetRef\":\"" +
                              json_escape(value->media_asset_ref) + "\"}", "MEDIA_UPLOAD_OPEN_SOURCE");
  } else {
    return fail("COMMAND_NOT_SUPPORTED");
  }
  return account_operation_step_locked(*value, result);
}

mineg_error_code_t Core::resume_account_operation_locked(uint64_t operation_id,
                                                          const std::string &effect_result,
                                                          std::string &result) {
  const auto found = account_operations_.find(operation_id);
  if (found == account_operations_.end()) return MINEG_NOT_FOUND;
  AccountOperation &operation = *found->second;
  const std::string digest = command_digest(effect_result);
  if (!operation.last_effect_result.empty() && operation.last_effect_result == digest) {
    return account_operation_step_locked(operation, result);
  }
  if (operation.status != "WAITING_FOR_EFFECT") return MINEG_INVALID_ARGUMENT;
  if (!valid_json(database_, effect_result) ||
      top_level_json_string(effect_result, "contractVersion") != "foundation-v2" ||
      top_level_json_u64(effect_result, "operationId") != operation_id ||
      top_level_json_u64(effect_result, "sequence") != operation.sequence ||
      top_level_json_string(effect_result, "effectType") != operation.effect_type) {
    return MINEG_INVALID_ARGUMENT;
  }
  const std::string effect_status = top_level_json_string(effect_result, "status");
  if (effect_status != "SUCCEEDED" && effect_status != "FAILED" &&
      effect_status != "CANCELLED") {
    return MINEG_INVALID_ARGUMENT;
  }
  operation.last_effect_result = digest;
  ++operation.sequence;
  if (effect_status == "CANCELLED") {
    operation.status = "CANCELLED";
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }

  const auto cached_profile_or_error = [this, &operation, &result](const std::string &code,
                                                                   bool retryable,
                                                                   const std::string &request_id = std::string{},
                                                                   int64_t retry_after_seconds = 0) {
    if (operation.type == "ProfileGetCurrent" && operation.allow_cached_profile && retryable) {
      const std::string cached = read_current_profile_snapshot_locked();
      if (!cached.empty()) {
        operation.status = "COMPLETED";
        operation.terminal_payload = cached;
        operation.clear_sensitive();
        return account_operation_step_locked(operation, result);
      }
    }
    if (operation.type == "PrivateMediaList" && operation.allow_cached_media && retryable &&
        has_private_media_cache_locked()) {
      const std::string cached = read_private_media_snapshot_locked(
          static_cast<int>(operation.media_limit));
      if (!cached.empty()) {
        operation.status = "COMPLETED";
        operation.terminal_payload = cached;
        operation.clear_sensitive();
        return account_operation_step_locked(operation, result);
      }
    }
    if ((operation.type == "RefreshPrivateMedia" || operation.type == "LoadMorePrivateMedia") &&
        operation.allow_cached_media && retryable && has_private_media_page_v2_locked()) {
      const std::string cached = read_private_media_page_v2_locked(
          static_cast<int>(operation.media_limit));
      if (!cached.empty()) {
        operation.status = "COMPLETED";
        operation.terminal_payload = cached;
        operation.clear_sensitive();
        return account_operation_step_locked(operation, result);
      }
    }
    if (operation.type == "RunBackupCycle") {
      if (!fail_backup_task_locked(operation, code, retryable, retry_after_seconds)) {
        finish_account_error_locked(operation, "DATABASE_ERROR", false, request_id);
        return account_operation_step_locked(operation, result);
      }
    }
    if ((operation.type == "BackupSingleMedia" || operation.type == "RunBackupCycle") &&
        !operation.media_resource_handle.empty() &&
        operation.stage != "MEDIA_UPLOAD_RELEASE_SOURCE") {
      std::string key = code;
      std::transform(key.begin(), key.end(), key.begin(), [](unsigned char value) {
        return static_cast<char>(std::tolower(value));
      });
      operation.pending_error = "{\"code\":\"" + json_escape(code) +
          "\",\"messageKey\":\"account." + json_escape(key) +
          "\",\"retryable\":" + (retryable ? "true" : "false") +
          ",\"requestId\":\"" + json_escape(request_id) + "\"}";
      set_account_effect_locked(operation, "MediaSourceEffect",
          "{\"action\":\"releaseMediaResource\",\"resourceHandle\":\"" +
          json_escape(operation.media_resource_handle) + "\"}", "MEDIA_UPLOAD_RELEASE_SOURCE");
      return account_operation_step_locked(operation, result);
    }
    finish_account_error_locked(operation, code, retryable, request_id);
    return account_operation_step_locked(operation, result);
  };

  const auto issue_media_api = [this, &operation](const std::string &method,
                                                   const std::string &path,
                                                   const std::string &body,
                                                   const std::string &idempotency,
                                                   const std::string &stage) {
    if (active_account_session_ == nullptr || active_account_session_->access_token.empty()) return false;
    std::string headers = "{\"Authorization\":\"Bearer " +
        json_escape(active_account_session_->access_token) + "\"";
    if (!idempotency.empty()) headers += ",\"Idempotency-Key\":\"" + json_escape(idempotency) + "\"";
    headers += "}";
    std::string payload = "{\"action\":\"sendApiRequest\",\"method\":\"" + method +
        "\",\"path\":\"" + json_escape(path) + "\",\"headers\":" + headers;
    if (!body.empty()) payload += ",\"bodyBase64\":\"" + base64_encode(body) + "\"";
    payload += "}";
    set_account_effect_locked(operation, "TransportEffect", payload, stage);
    return true;
  };

  const auto issue_media_part = [this, &operation]() {
    if (operation.type == "RunBackupCycle") {
      if (operation.media_resource_id.empty() || operation.media_upload_response.empty()) return false;
      if (backup_task_should_pause_locked(operation)) {
        if (!pause_backup_task_locked(operation)) return false;
        operation.backup_paused_by_setting = true;
        set_account_effect_locked(operation, "MediaSourceEffect",
            "{\"action\":\"releaseMediaResource\",\"resourceHandle\":\"" +
            json_escape(operation.media_resource_handle) + "\"}", "MEDIA_UPLOAD_RELEASE_SOURCE");
        return true;
      }
      sqlite3_stmt *pending = nullptr;
      if (sqlite3_prepare_v2(database_, "SELECT part_number FROM backup_parts WHERE resource_id=? "
                             "AND state<>'CONFIRMED' ORDER BY part_number LIMIT 2", -1,
                             &pending, nullptr) != SQLITE_OK) {
        return false;
      }
      int status = sqlite3_bind_text(pending, 1, operation.media_resource_id.c_str(), -1,
                                     SQLITE_TRANSIENT);
      std::vector<int64_t> indexes;
      while (status == SQLITE_OK && sqlite3_step(pending) == SQLITE_ROW) {
        const int64_t part_number = sqlite3_column_int64(pending, 0);
        if (part_number < 1 || part_number > static_cast<int64_t>(operation.media_part_sizes.size())) {
          sqlite3_finalize(pending);
          return false;
        }
        indexes.push_back(part_number - 1);
      }
      sqlite3_finalize(pending);
      if (status != SQLITE_OK || indexes.empty()) return false;
      std::string parts = "[";
      for (size_t selected = 0; selected < indexes.size(); ++selected) {
        const size_t index = static_cast<size_t>(indexes[selected]);
        const std::string prefix = "$.grant.resources[0].parts[" + std::to_string(index) + "].grant";
        const std::string url = sqlite_json_text(database_, operation.media_upload_response, prefix + ".url");
        const std::string method = sqlite_json_text(database_, operation.media_upload_response, prefix + ".method");
        std::string headers = sqlite_json_text(database_, operation.media_upload_response, prefix + ".headers");
        if (url.empty() || method != "PUT") return false;
        if (headers.empty()) headers = "{}";
        int64_t offset = 0;
        for (size_t prior = 0; prior < index; ++prior) offset += operation.media_part_sizes[prior];
        if (selected > 0) parts += ',';
        parts += "{\"partNumber\":" + std::to_string(index + 1U) +
            ",\"url\":\"" + json_escape(url) + "\",\"method\":\"PUT\",\"headers\":" +
            headers + ",\"sourceDescriptor\":" + std::to_string(operation.media_source_descriptor) +
            ",\"offset\":" + std::to_string(offset) + ",\"size\":" +
            std::to_string(operation.media_part_sizes[index]) + "}";
      }
      operation.media_uploaded_part_indexes = std::move(indexes);
      for (auto &etag : operation.media_uploaded_part_etags) wipe_string(etag);
      operation.media_uploaded_part_etags.clear();
      operation.media_uploaded_part_report_index = 0;
      set_account_effect_locked(operation, "TransportEffect",
          "{\"action\":\"uploadParts\",\"parts\":" + parts + "]}",
          "MEDIA_UPLOAD_OBJECT_PARTS");
      return true;
    }
    if (operation.media_part_index < 0 ||
        operation.media_part_index >= static_cast<int64_t>(operation.media_part_sizes.size())) return false;
    const size_t index = static_cast<size_t>(operation.media_part_index);
    const std::string prefix = "$.grant.resources[0].parts[" + std::to_string(index) + "].grant";
    const std::string url = sqlite_json_text(database_, operation.media_upload_response, prefix + ".url");
    const std::string method = sqlite_json_text(database_, operation.media_upload_response, prefix + ".method");
    std::string headers = sqlite_json_text(database_, operation.media_upload_response, prefix + ".headers");
    if (headers.empty()) headers = "{}";
    int64_t offset = 0;
    for (size_t prior = 0; prior < index; ++prior) offset += operation.media_part_sizes[prior];
    if (url.empty() || method != "PUT") return false;
    set_account_effect_locked(operation, "TransportEffect",
        "{\"action\":\"uploadPart\",\"url\":\"" + json_escape(url) +
        "\",\"method\":\"PUT\",\"headers\":" + headers +
        ",\"sourceDescriptor\":" + std::to_string(operation.media_source_descriptor) +
        ",\"offset\":" + std::to_string(offset) + ",\"size\":" +
        std::to_string(operation.media_part_sizes[index]) + "}", "MEDIA_UPLOAD_OBJECT_PART");
    return true;
  };

  const auto issue_media_create = [&operation, &issue_media_api]() {
    if (operation.media_part_sizes.empty() ||
        operation.media_part_sizes.size() != operation.media_part_digests.size()) return false;
    std::string parts = "[";
    for (size_t index = 0; index < operation.media_part_sizes.size(); ++index) {
      if (index > 0) parts += ',';
      parts += "{\"part_number\":" + std::to_string(index + 1U) +
          ",\"content_size\":" + std::to_string(operation.media_part_sizes[index]) +
          ",\"content_sha256\":\"" + operation.media_part_digests[index] + "\"}";
    }
    parts += "]";
    const std::string protocol_version = operation.type == "RunBackupCycle" ? "stage04-v1" : "stage03-v2";
    const std::string stage04_fields = operation.type == "RunBackupCycle"
        ? ",\"device_installation_id\":\"" + json_escape(operation.device_installation_id) +
              "\",\"client_albums\":" + operation.media_client_albums_json
        : std::string{};
    const std::string body = "{\"protocol_version\":\"" + protocol_version + "\",\"client_media_id\":\"" +
        operation.media_client_id + "\",\"content_sha256\":\"" +
        operation.media_content_digest_base64 + "\",\"content_revision\":1,\"media_type\":\"" +
        json_escape(operation.media_type) + "\",\"captured_at\":\"" +
        json_escape(operation.media_captured_at) + "\",\"mime_type\":\"" +
        json_escape(operation.media_mime_type) + "\",\"resources\":[{\"resource_id\":\"" +
        operation.media_resource_id + "\",\"resource_type\":\"ORIGINAL\",\"content_size\":" +
        std::to_string(operation.media_source_size) + ",\"content_sha256\":\"" +
        operation.media_content_digest_base64 + "\",\"parts\":" + parts + "}]" + stage04_fields + "}";
    return issue_media_api("POST", "/api/v1/uploads", body, operation.idempotency_key,
                           "TRANSPORT_MEDIA_UPLOAD_CREATE");
  };

  const auto issue_media_report = [&operation, &issue_media_api]() {
    if (operation.media_part_etag.empty() || operation.media_upload_id.empty() ||
        operation.media_part_index < 0 ||
        operation.media_part_index >= static_cast<int64_t>(operation.media_part_sizes.size())) return false;
    const size_t index = static_cast<size_t>(operation.media_part_index);
    const std::string body = "{\"resource_id\":\"" + operation.media_resource_id +
        "\",\"part_number\":" + std::to_string(index + 1U) +
        ",\"content_size\":" + std::to_string(operation.media_part_sizes[index]) +
        ",\"content_sha256\":\"" + operation.media_part_digests[index] +
        "\",\"etag\":\"" + json_escape(operation.media_part_etag) + "\"}";
    const std::string key = operation.idempotency_key + ":part:" + std::to_string(index + 1U);
    return issue_media_api("POST", "/api/v1/uploads/" + operation.media_upload_id + "/parts",
                           body, key, "TRANSPORT_MEDIA_UPLOAD_REPORT");
  };

  const auto issue_media_complete = [&operation, &issue_media_api]() {
    if (operation.media_upload_id.empty()) return false;
    return issue_media_api("POST", "/api/v1/uploads/" + operation.media_upload_id + "/complete",
                           "{}", operation.idempotency_key + ":complete",
                           "TRANSPORT_MEDIA_UPLOAD_COMPLETE");
  };

  const auto issue_media_status = [&operation, &issue_media_api]() {
    if (operation.media_upload_id.empty()) return false;
    return issue_media_api("GET", "/api/v1/uploads/" + operation.media_upload_id, "", "",
                           "TRANSPORT_MEDIA_UPLOAD_STATUS");
  };

  if (effect_status == "FAILED") {
    const std::string code = sqlite_json_text(database_, effect_result, "$.error.code");
    const bool retryable = sqlite_json_boolean(database_, effect_result, "$.error.retryable", false);
    if (operation.type == "OpenPrivateMedia" && !operation.private_media_temp_paths.empty() &&
        operation.stage != "PRIVATE_MEDIA_VIEW_DELETE_TEMP_ERROR") {
      operation.pending_error = "{\"code\":\"" +
          json_escape(code.empty() ? "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE" : code) +
          "\",\"retryable\":" + (retryable ? "true" : "false") + "}";
      set_account_effect_locked(operation, "FileEffect",
          "{\"action\":\"deleteTempFile\",\"path\":\"" +
          json_escape(operation.private_media_temp_paths.front()) + "\"}",
          "PRIVATE_MEDIA_VIEW_DELETE_TEMP_ERROR");
      return account_operation_step_locked(operation, result);
    }
    if (operation.type != "RunBackupCycle" && operation.effect_type == "TransportEffect" && retryable &&
        operation.effect_retry_count < 1) {
      ++operation.effect_retry_count;
      return account_operation_step_locked(operation, result);
    }
    if (operation.stage == "TRANSPORT_SIGN_OUT") {
      issue_session_cleanup_locked(operation, "SIGNED_OUT");
      return account_operation_step_locked(operation, result);
    }
    if (operation.stage == "DELETE_SESSION") {
      clear_account_session_locked();
      finish_account_error_locked(operation, code.empty() ? "SECURE_STORE_ERROR" : code,
                                  retryable);
      return account_operation_step_locked(operation, result);
    }
    if (operation.stage == "WRITE_SESSION") {
      issue_session_cleanup_locked(operation,
                                   "ERROR:" + (code.empty() ? "SECURE_STORE_ERROR" : code));
      return account_operation_step_locked(operation, result);
    }
    return cached_profile_or_error(code.empty() ? "PLATFORM_EFFECT_FAILED" : code, retryable);
  }

  if (operation.stage == "PRIVATE_MEDIA_VIEW_DELETE_TEMP_ERROR") {
    const std::string code = sqlite_json_text(database_, operation.pending_error, "$.code");
    const bool retryable = sqlite_json_boolean(database_, operation.pending_error, "$.retryable", false);
    return cached_profile_or_error(code.empty() ? "PRIVATE_MEDIA_RESOURCE_UNAVAILABLE" : code, retryable);
  }

  if (operation.stage == "PRIVATE_MEDIA_VIEW_CLOSE") {
    operation.status = "COMPLETED";
    operation.terminal_payload = "{\"closed\":" +
        std::string(sqlite_json_boolean(database_, effect_result, "$.payload.closed", false) ? "true" : "false") + "}";
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "PRIVATE_MEDIA_VIEW_CREATE_TEMP") {
    const std::string path = sqlite_json_text(database_, effect_result, "$.payload.path");
    if (path.empty() || path.size() > 4096U || operation.private_media_resource_ids.size() != 1U) {
      return cached_profile_or_error("PRIVATE_MEDIA_RESOURCE_UNAVAILABLE", false);
    }
    operation.private_media_temp_paths.push_back(path);
    const std::string download_limits = operation.private_media_view_uses_oss_image_thumbnail
        ? ",\"maximumSize\":" + std::to_string(operation.private_media_view_maximum_output_size)
        : ",\"expectedSize\":" + std::to_string(operation.private_media_resource_sizes.front()) +
              ",\"maximumSize\":" + std::to_string(operation.private_media_resource_sizes.front());
    set_account_effect_locked(operation, "TransportEffect",
        "{\"action\":\"downloadObject\",\"url\":\"" +
        json_escape(operation.private_media_resource_urls.front()) + "\",\"method\":\"GET\",\"headers\":" +
        operation.private_media_resource_headers.front() + ",\"destinationPath\":\"" +
        json_escape(path) + "\"" + download_limits + "}",
        "PRIVATE_MEDIA_VIEW_DOWNLOAD");
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "PRIVATE_MEDIA_VIEW_DOWNLOAD") {
    const int64_t status = sqlite_json_integer(database_, effect_result, "$.payload.status", 0);
    const int64_t bytes = sqlite_json_integer(database_, effect_result, "$.payload.bytesWritten", -1);
    const std::string digest = sqlite_json_text(database_, effect_result, "$.payload.sha256Base64");
    const std::string content_type = sqlite_json_text(database_, effect_result, "$.payload.contentType");
    const bool allowed_dynamic_image_type = content_type == "image/jpeg" || content_type == "image/png" ||
        content_type == "image/gif" || content_type == "image/webp" || content_type == "image/bmp";
    const bool dynamic_image_verified = operation.private_media_view_uses_oss_image_thumbnail &&
        bytes > 0 && bytes <= operation.private_media_view_maximum_output_size &&
        allowed_dynamic_image_type;
    const bool static_resource_verified = !operation.private_media_view_uses_oss_image_thumbnail &&
        bytes == operation.private_media_resource_sizes.front() &&
        digest == operation.private_media_resource_digests.front();
    if (operation.private_media_resource_ids.size() != 1U || operation.private_media_temp_paths.size() != 1U ||
        status < 200 || status >= 300 || !(dynamic_image_verified || static_resource_verified)) {
      operation.pending_error = "{\"code\":\"PRIVATE_MEDIA_DOWNLOAD_INTEGRITY_FAILED\",\"retryable\":false}";
      const std::string path = operation.private_media_temp_paths.empty() ? std::string{} :
          operation.private_media_temp_paths.front();
      if (path.empty()) return cached_profile_or_error("PRIVATE_MEDIA_DOWNLOAD_INTEGRITY_FAILED", false);
      set_account_effect_locked(operation, "FileEffect",
          "{\"action\":\"deleteTempFile\",\"path\":\"" + json_escape(path) + "\"}",
          "PRIVATE_MEDIA_VIEW_DELETE_TEMP_ERROR");
      return account_operation_step_locked(operation, result);
    }
    set_account_effect_locked(operation, "MediaPlaybackEffect",
        "{\"action\":\"openVerifiedMedia\",\"verifiedFilePath\":\"" +
        json_escape(operation.private_media_temp_paths.front()) + "\",\"mimeType\":\"" +
        json_escape(operation.private_media_resource_mime_types.front()) + "\"}",
        "PRIVATE_MEDIA_VIEW_OPEN");
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "PRIVATE_MEDIA_VIEW_OPEN") {
    const std::string view_handle = sqlite_json_text(database_, effect_result, "$.payload.viewHandle");
    const std::string source_uri = sqlite_json_text(database_, effect_result, "$.payload.sourceUri");
    if (view_handle.size() < 8U || view_handle.size() > 256U || source_uri.empty() ||
        source_uri.size() > 4096U || source_uri.rfind("https://", 0) == 0 ||
        source_uri.rfind("http://", 0) == 0) {
      operation.pending_error = "{\"code\":\"PRIVATE_MEDIA_RESOURCE_UNAVAILABLE\",\"retryable\":false}";
      if (operation.private_media_temp_paths.empty()) {
        return cached_profile_or_error("PRIVATE_MEDIA_RESOURCE_UNAVAILABLE", false);
      }
      set_account_effect_locked(operation, "FileEffect",
          "{\"action\":\"deleteTempFile\",\"path\":\"" +
          json_escape(operation.private_media_temp_paths.front()) + "\"}",
          "PRIVATE_MEDIA_VIEW_DELETE_TEMP_ERROR");
      return account_operation_step_locked(operation, result);
    }
    operation.status = "COMPLETED";
    operation.terminal_payload = "{\"mediaId\":\"" + json_escape(operation.private_media_id) +
        "\",\"resourceType\":\"" + json_escape(operation.private_media_resource_types.front()) +
        "\",\"mimeType\":\"" + json_escape(operation.private_media_resource_mime_types.front()) +
        "\",\"viewHandle\":\"" + json_escape(view_handle) + "\",\"sourceUri\":\"" +
        json_escape(source_uri) + "\"}";
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "BACKUP_CONNECTIVITY") {
    const bool connected = sqlite_json_boolean(database_, effect_result, "$.payload.connected", false);
    const bool metered = sqlite_json_boolean(database_, effect_result, "$.payload.metered", false);
    sqlite3_stmt *settings = nullptr;
    if (sqlite3_prepare_v2(database_, "SELECT auto_backup_enabled,allow_cellular_backup,"
                           "COALESCE((SELECT requested_manually FROM backup_tasks WHERE task_id=?),0) "
                           "FROM backup_settings WHERE user_id=? AND device_installation_id=?", -1,
                           &settings, nullptr) != SQLITE_OK) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    int status = sqlite3_bind_text(settings, 1, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_text(settings, 2, operation.user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_text(settings, 3, operation.device_installation_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(settings);
    const bool enabled = status == SQLITE_ROW && sqlite3_column_int(settings, 0) == 1;
    const bool allow_cellular = status == SQLITE_ROW && sqlite3_column_int(settings, 1) == 1;
    const bool requested_manually = status == SQLITE_ROW && sqlite3_column_int(settings, 2) == 1;
    sqlite3_finalize(settings);
    if (status != SQLITE_ROW && status != SQLITE_DONE) return cached_profile_or_error("DATABASE_ERROR", false);
    if ((!enabled && !requested_manually) || !connected || (metered && !allow_cellular)) {
      sqlite3_stmt *waiting = nullptr;
      const char *waiting_sql = (enabled || requested_manually)
          ? "UPDATE backup_tasks SET state='WAITING_NETWORK',resume_state='PREPARING',lease_token=NULL,lease_expires_at=NULL,updated_at=? WHERE task_id=? AND lease_token=?"
          : "UPDATE backup_tasks SET state='PAUSED_BY_SETTING',resume_state='PREPARING',lease_token=NULL,lease_expires_at=NULL,updated_at=? WHERE task_id=? AND lease_token=?";
      if (sqlite3_prepare_v2(database_, waiting_sql, -1, &waiting, nullptr) != SQLITE_OK) {
        return cached_profile_or_error("DATABASE_ERROR", false);
      }
      const std::string now = now_rfc3339();
      status = sqlite3_bind_text(waiting, 1, now.c_str(), -1, SQLITE_TRANSIENT);
      if (status == SQLITE_OK) status = sqlite3_bind_text(waiting, 2, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
      if (status == SQLITE_OK) status = sqlite3_bind_text(waiting, 3, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
      if (status == SQLITE_OK) status = sqlite3_step(waiting);
      sqlite3_finalize(waiting);
      if (status != SQLITE_DONE) return cached_profile_or_error("DATABASE_ERROR", false);
      operation.status = "COMPLETED";
      operation.terminal_payload = "{\"processed\":false,\"waitingForNetwork\":" +
          std::string(enabled || requested_manually ? "true" : "false") + "}";
      operation.clear_sensitive();
      return account_operation_step_locked(operation, result);
    }
    set_account_effect_locked(operation, "FileEffect", "{\"action\":\"getAvailableSpace\"}",
                              "BACKUP_STORAGE");
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "BACKUP_STORAGE") {
    const int64_t available_bytes = sqlite_json_integer(database_, effect_result,
                                                         "$.payload.availableBytes", -1);
    if (available_bytes < kMinimumBackupFreeBytes) {
      return cached_profile_or_error("BACKUP_DEVICE_STORAGE_LOW", true);
    }
    set_account_effect_locked(operation, "MediaSourceEffect",
                              "{\"action\":\"openMediaResource\",\"platformAssetRef\":\"" +
                              json_escape(operation.media_asset_ref) + "\"}", "MEDIA_UPLOAD_OPEN_SOURCE");
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "MEDIA_UPLOAD_OPEN_SOURCE") {
    operation.media_resource_handle = sqlite_json_text(database_, effect_result, "$.payload.resource.resourceHandle");
    operation.media_source_descriptor = sqlite_json_integer(database_, effect_result, "$.payload.resource.descriptor", -1);
    operation.media_source_size = sqlite_json_integer(database_, effect_result, "$.payload.resource.byteLength", -1);
    if (operation.media_resource_handle.empty() || operation.media_source_descriptor < 0 ||
        operation.media_source_size < 1 || operation.media_source_size >
        static_cast<int64_t>(kMediaChunkBytes) * 10000LL) {
      return cached_profile_or_error("LOCAL_MEDIA_UNAVAILABLE", false);
    }
    if (operation.type == "RunBackupCycle" && backup_task_should_pause_locked(operation)) {
      if (!pause_backup_task_locked(operation)) {
        return cached_profile_or_error("DATABASE_ERROR", false);
      }
      operation.backup_paused_by_setting = true;
      set_account_effect_locked(operation, "MediaSourceEffect",
          "{\"action\":\"releaseMediaResource\",\"resourceHandle\":\"" +
          json_escape(operation.media_resource_handle) + "\"}", "MEDIA_UPLOAD_RELEASE_SOURCE");
      return account_operation_step_locked(operation, result);
    }
    crypto_hash_sha256_state whole_state{};
    if (crypto_hash_sha256_init(&whole_state) != 0) return cached_profile_or_error("CRYPTO_ERROR", false);
    operation.media_part_sizes.clear();
    operation.media_part_digests.clear();
    std::vector<unsigned char> buffer(kMediaChunkBytes);
    int64_t offset = 0;
    while (offset < operation.media_source_size) {
      const size_t part_size = static_cast<size_t>(std::min<int64_t>(
          static_cast<int64_t>(kMediaChunkBytes), operation.media_source_size - offset));
      size_t consumed = 0;
      while (consumed < part_size) {
        const ssize_t count = pread(static_cast<int>(operation.media_source_descriptor),
                                    buffer.data() + consumed, part_size - consumed,
                                    static_cast<off_t>(offset + static_cast<int64_t>(consumed)));
        if (count <= 0) {
          sodium_memzero(buffer.data(), buffer.size());
          return cached_profile_or_error("LOCAL_MEDIA_READ_FAILED", true);
        }
        consumed += static_cast<size_t>(count);
      }
      std::array<unsigned char, crypto_hash_sha256_BYTES> part_digest{};
      if (crypto_hash_sha256_update(&whole_state, buffer.data(), part_size) != 0 ||
          crypto_hash_sha256(part_digest.data(), buffer.data(), part_size) != 0) {
        sodium_memzero(buffer.data(), buffer.size());
        return cached_profile_or_error("CRYPTO_ERROR", false);
      }
      operation.media_part_sizes.push_back(static_cast<int64_t>(part_size));
      operation.media_part_digests.push_back(base64_encode(part_digest.data(), part_digest.size(), false));
      sodium_memzero(part_digest.data(), part_digest.size());
      offset += static_cast<int64_t>(part_size);
    }
    std::array<unsigned char, crypto_hash_sha256_BYTES> whole_digest{};
    if (crypto_hash_sha256_final(&whole_state, whole_digest.data()) != 0) {
      sodium_memzero(buffer.data(), buffer.size());
      return cached_profile_or_error("CRYPTO_ERROR", false);
    }
    operation.media_content_digest_base64 = base64_encode(whole_digest.data(), whole_digest.size(), false);
    sodium_memzero(whole_digest.data(), whole_digest.size());
    sodium_memzero(buffer.data(), buffer.size());
    if (operation.type == "RunBackupCycle" && !renew_backup_task_lease_locked(operation)) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    if (operation.type == "RunBackupCycle" && !persist_backup_resource_manifest_locked(operation)) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    if (operation.type == "RunBackupCycle" && !operation.media_upload_id.empty()) {
      if (!issue_media_status()) return cached_profile_or_error("SESSION_INVALID", false);
    } else if (!issue_media_create()) {
      return cached_profile_or_error("SESSION_INVALID", false);
    }
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "MEDIA_UPLOAD_RELEASE_SOURCE") {
    if (!operation.pending_error.empty()) {
      operation.status = "FAILED";
      operation.terminal_payload = operation.pending_error;
    } else if (operation.type == "RunBackupCycle" && operation.backup_paused_by_setting) {
      operation.status = "COMPLETED";
      operation.terminal_payload = "{\"processed\":false,\"pausedBySetting\":true}";
    } else {
      if (operation.type == "RunBackupCycle" && !finish_backup_task_locked(operation)) {
        return cached_profile_or_error("DATABASE_ERROR", false);
      }
      operation.status = "COMPLETED";
      operation.terminal_payload = operation.type == "RunBackupCycle"
          ? "{\"processed\":true,\"taskId\":\"" + json_escape(operation.backup_task_id) + "\"}"
          : operation.media_pending_result;
    }
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "MEDIA_UPLOAD_OBJECT_PART") {
    const std::string etag = sqlite_json_text(database_, effect_result, "$.payload.etag");
    if (etag.empty() || operation.media_part_index < 0 ||
        operation.media_part_index >= static_cast<int64_t>(operation.media_part_sizes.size())) {
      return cached_profile_or_error("OBJECT_UPLOAD_RESPONSE_INVALID", false);
    }
    operation.media_part_etag = etag;
    if (!issue_media_report()) {
      return cached_profile_or_error("SESSION_INVALID", false);
    }
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "MEDIA_UPLOAD_OBJECT_PARTS") {
    const int64_t response_count = json_array_length(database_, effect_result, "$.payload.parts");
    if (response_count != static_cast<int64_t>(operation.media_uploaded_part_indexes.size()) ||
        response_count < 1 || response_count > 2) {
      return cached_profile_or_error("OBJECT_UPLOAD_RESPONSE_INVALID", false);
    }
    operation.media_uploaded_part_etags.clear();
    for (int64_t index = 0; index < response_count; ++index) {
      const int64_t part_number = sqlite_json_integer(
          database_, effect_result, "$.payload.parts[" + std::to_string(index) + "].partNumber", -1);
      const std::string etag = sqlite_json_text(
          database_, effect_result, "$.payload.parts[" + std::to_string(index) + "].etag");
      if (part_number != operation.media_uploaded_part_indexes[static_cast<size_t>(index)] + 1 ||
          etag.empty()) {
        return cached_profile_or_error("OBJECT_UPLOAD_RESPONSE_INVALID", false);
      }
      operation.media_uploaded_part_etags.push_back(etag);
    }
    operation.media_uploaded_part_report_index = 0;
    operation.media_part_index = operation.media_uploaded_part_indexes.front();
    operation.media_part_etag = operation.media_uploaded_part_etags.front();
    if (!mark_backup_parts_transferred_locked(operation, operation.media_uploaded_part_indexes)) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    if (!issue_media_report()) return cached_profile_or_error("SESSION_INVALID", false);
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "LOCAL_SCAN_PERMISSION") {
    const std::string permission = sqlite_json_text(database_, effect_result, "$.payload.library");
    if (permission != "FULL") {
      return cached_profile_or_error("PERMISSION_REQUIRED", false);
    }
    operation.local_generation_id = random_identifier();
    operation.local_indexed_count = 0;
    bool resuming_backup_scan = false;
    if (operation.type == "ReconcileBackupQueue" &&
        !begin_backup_scan_locked(operation.user_id, operation.device_installation_id,
                                  operation.local_generation_id, operation.backup_incremental_scan,
                                  operation.local_next_cursor, operation.local_indexed_count,
                                  resuming_backup_scan)) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    if (!resuming_backup_scan && !prepare_local_scan_locked(operation.user_id, operation.local_generation_id,
                                                             operation.backup_incremental_scan)) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    set_account_effect_locked(operation, "MediaSourceEffect",
                              "{\"action\":\"listAlbums\"}",
                              "LOCAL_SCAN_ALBUMS");
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "LOCAL_SCAN_ALBUMS") {
    if (!write_local_scan_albums_locked(operation.user_id, operation.local_generation_id,
                                        effect_result)) {
      return cached_profile_or_error("LOCAL_INDEX_PAGE_INVALID", false);
    }
    set_account_effect_locked(operation, "MediaSourceEffect",
                              "{\"action\":\"listMedia\",\"cursor\":" +
                              (operation.local_next_cursor.empty() ? "null" : operation.local_next_cursor) +
                              ",\"limit\":500}", "LOCAL_SCAN_MEDIA");
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "LOCAL_SCAN_MEDIA") {
    int64_t page_count = 0;
    if (!write_local_scan_page_locked(operation.user_id, operation.local_generation_id,
                                      effect_result, page_count)) {
      return cached_profile_or_error("LOCAL_INDEX_PAGE_INVALID", false);
    }
    operation.local_indexed_count += page_count;
    if (page_count > 0) {
      const int64_t last_version = sqlite_json_integer(database_, effect_result,
          "$.payload.items[#-1].modifiedVersion", -1);
      const std::string last_ref = sqlite_json_text(database_, effect_result,
          "$.payload.items[#-1].platformAssetRef");
      if (last_version >= 0 && !last_ref.empty()) {
        operation.local_next_cursor = "{\"modifiedVersion\":" + std::to_string(last_version) +
            ",\"platformAssetRef\":\"" + json_escape(last_ref) + "\"}";
      }
    }
    if (operation.type == "ReconcileBackupQueue" &&
        !persist_backup_scan_progress_locked(operation.user_id, operation.device_installation_id,
                                              operation.local_generation_id,
                                              operation.local_indexed_count,
                                              operation.local_next_cursor)) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    ++event_sequence_;
    emit_locked("{\"contractVersion\":\"stage02-v2\",\"type\":"
                "\"LocalScanProgressChanged\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"userId\":\"" +
                json_escape(operation.user_id) + "\",\"indexedCount\":" +
                std::to_string(operation.local_indexed_count) + "}");
    const std::string next_ref = sqlite_json_text(database_, effect_result,
                                                   "$.payload.nextCursor.platformAssetRef");
    if (!next_ref.empty()) {
      const int64_t next_version = sqlite_json_integer(
          database_, effect_result, "$.payload.nextCursor.modifiedVersion", -1);
      if (next_version < 0 || page_count == 0) {
        return cached_profile_or_error("LOCAL_INDEX_CURSOR_INVALID", false);
      }
      operation.local_next_cursor = "{\"modifiedVersion\":" +
          std::to_string(next_version) + ",\"platformAssetRef\":\"" +
          json_escape(next_ref) + "\"}";
      set_account_effect_locked(
          operation, "MediaSourceEffect",
          "{\"action\":\"listMedia\",\"cursor\":" + operation.local_next_cursor +
          ",\"limit\":500}", "LOCAL_SCAN_MEDIA");
      return account_operation_step_locked(operation, result);
    }
    const std::string account = read_account_state_locked();
    const std::string current_user = sqlite_json_text(database_, account, "$.state.userId");
    if (operation.local_account_bound && current_user != operation.user_id) {
      return cached_profile_or_error("ACCOUNT_CHANGED", false);
    }
    const std::string completed_at = now_rfc3339();
    if (!finalize_local_scan_locked(operation.user_id, operation.local_generation_id,
                                    operation.local_indexed_count, completed_at)) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    if (operation.type == "ReconcileBackupQueue") {
      const int64_t discovered = discover_backup_tasks_locked(operation.user_id,
                                                               operation.device_installation_id);
      if (discovered < 0 || !finish_backup_scan_locked(operation.user_id,
                                                       operation.device_installation_id,
                                                       operation.local_generation_id,
                                                       discovered, completed_at,
                                                       operation.local_next_cursor)) {
        return cached_profile_or_error("DATABASE_ERROR", false);
      }
      ++event_sequence_;
      emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupQueueChanged\",\"sequence\":" +
                  std::to_string(event_sequence_) + ",\"userId\":\"" +
                  json_escape(operation.user_id) + "\",\"deviceInstallationId\":\"" +
                  json_escape(operation.device_installation_id) + "\",\"discoveredCount\":" +
                  std::to_string(discovered) + "}");
    }
    ++event_sequence_;
    emit_locked("{\"contractVersion\":\"stage02-v2\",\"type\":"
                "\"LocalLibraryIndexChanged\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"userId\":\"" +
                json_escape(operation.user_id) + "\",\"generationId\":\"" +
                json_escape(operation.local_generation_id) + "\",\"indexedCount\":" +
                std::to_string(operation.local_indexed_count) + "}");
    operation.status = "COMPLETED";
    if (operation.type == "ReconcileBackupQueue") {
      operation.terminal_payload = "{\"generationId\":\"" +
          json_escape(operation.local_generation_id) + "\",\"indexedCount\":" +
          std::to_string(operation.local_indexed_count) + ",\"completedAt\":\"" +
          json_escape(completed_at) + "\",\"reconciled\":true}";
    } else {
      operation.terminal_payload = "{\"generationId\":\"" +
          json_escape(operation.local_generation_id) + "\",\"indexedCount\":" +
          std::to_string(operation.local_indexed_count) + ",\"completedAt\":\"" +
          json_escape(completed_at) + "\"}";
    }
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "READ_DEVICE") {
    operation.device_installation_id =
        secure_result_value(database_, effect_result, "device.installationId");
    if (operation.device_installation_id.empty()) {
      operation.device_installation_id = random_identifier();
      set_account_effect_locked(
          operation, "SecureStoreEffect",
          "{\"action\":\"writeSecrets\",\"values\":[{\"name\":"
          "\"device.installationId\",\"valueBase64\":\"" +
          base64_encode(operation.device_installation_id) + "\"}]}",
          "WRITE_DEVICE");
      return account_operation_step_locked(operation, result);
    }
    const mineg_error_code_t code = issue_account_request_locked(
        operation, operation.type == "AccountSignIn" ? "SIGN_IN" : "SIGN_UP");
    if (code != MINEG_OK) return cached_profile_or_error("INTERNAL_ERROR", false);
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "WRITE_DEVICE") {
    const mineg_error_code_t code = issue_account_request_locked(
        operation, operation.type == "AccountSignIn" ? "SIGN_IN" : "SIGN_UP");
    if (code != MINEG_OK) return cached_profile_or_error("INTERNAL_ERROR", false);
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "READ_SESSION") {
    operation.access_token = secure_result_value(database_, effect_result, "account.accessToken");
    operation.refresh_token = secure_result_value(database_, effect_result, "account.refreshToken");
    operation.access_expires_at =
        secure_result_value(database_, effect_result, "account.accessExpiresAt");
    operation.refresh_expires_at =
        secure_result_value(database_, effect_result, "account.refreshExpiresAt");
    operation.device_installation_id =
        secure_result_value(database_, effect_result, "device.installationId");
    const std::string account = read_account_state_locked();
    operation.user_id = sqlite_json_text(database_, account, "$.state.userId");
    operation.masked_phone = sqlite_json_text(database_, account, "$.state.maskedPhone");
    operation.approval_status = sqlite_json_text(database_, account, "$.state.approvalStatus");
    operation.next_step = operation.approval_status == "APPROVED" ? "APP_HOME" : "REVIEW_PENDING";
    if (operation.type != "AccountSignOut" && !operation.access_token.empty() &&
        !operation.user_id.empty() && rfc3339_is_after(operation.access_expires_at,
                                                       std::chrono::seconds(30)) &&
        activate_account_session_locked(operation)) {
      if (operation.type == "AccountRestoreSession") {
        operation.status = "COMPLETED";
        operation.terminal_payload = "{\"userId\":\"" + json_escape(operation.user_id) +
            "\",\"approvalStatus\":\"" + operation.approval_status +
            "\",\"nextStep\":\"" + operation.next_step + "\"}";
        operation.clear_sensitive();
        return account_operation_step_locked(operation, result);
      }
      const mineg_error_code_t code = issue_account_request_locked(operation,
                                                                    operation.continuation);
      if (code != MINEG_OK) return cached_profile_or_error("SESSION_INVALID", false);
      return account_operation_step_locked(operation, result);
    }
    if (operation.refresh_token.empty()) {
      if (operation.type == "AccountRestoreSession") {
        issue_session_cleanup_locked(operation, "RESTORE_NULL");
        return account_operation_step_locked(operation, result);
      }
      if (operation.type == "AccountSignOut") {
        issue_session_cleanup_locked(operation, "SIGNED_OUT");
        return account_operation_step_locked(operation, result);
      }
      return cached_profile_or_error("SESSION_INVALID", false);
    }
    if (operation.type == "AccountSignOut") {
      issue_account_request_locked(operation, "SIGN_OUT");
      return account_operation_step_locked(operation, result);
    }
    if (operation.type == "AccountRestoreSession") operation.continuation = "RESTORE";
    const mineg_error_code_t code = issue_account_request_locked(operation, "REFRESH");
    if (code != MINEG_OK) return cached_profile_or_error("SESSION_INVALID", false);
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "WRITE_SESSION") {
    if (!activate_account_session_locked(operation)) {
      return cached_profile_or_error("SESSION_INVALID", false);
    }
    const std::string continuation = operation.continuation;
    if (continuation == "COMPLETE_SESSION" || continuation == "RESTORE") {
      operation.status = "COMPLETED";
      operation.terminal_payload = "{\"userId\":\"" + json_escape(operation.user_id) +
          "\",\"approvalStatus\":\"" + operation.approval_status +
          "\",\"nextStep\":\"" + operation.next_step + "\"}";
      operation.clear_sensitive();
      return account_operation_step_locked(operation, result);
    }
    bool issued = false;
    if (continuation == "MEDIA_UPLOAD_CREATE") {
      issued = issue_media_create();
    } else if (continuation == "MEDIA_UPLOAD_STATUS") {
      issued = issue_media_status();
    } else if (continuation == "MEDIA_UPLOAD_REPORT") {
      issued = issue_media_report();
    } else if (continuation == "MEDIA_UPLOAD_COMPLETE") {
      issued = issue_media_complete();
    } else {
      issued = issue_account_request_locked(operation, continuation) == MINEG_OK;
    }
    if (!issued) return cached_profile_or_error("SESSION_INVALID", false);
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "DELETE_SESSION") {
    clear_account_session_locked();
    if (operation.continuation == "RESTORE_NULL") {
      operation.status = "COMPLETED";
      operation.terminal_payload = "null";
    } else if (operation.continuation.rfind("ERROR:", 0) == 0) {
      finish_account_error_locked(operation, operation.continuation.substr(6U), false);
      return account_operation_step_locked(operation, result);
    } else {
      operation.status = "COMPLETED";
      operation.terminal_payload = "{\"signedOut\":true}";
    }
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage == "TRANSPORT_AVATAR_OBJECT") {
    const int64_t object_status = sqlite_json_integer(database_, effect_result,
                                                       "$.payload.status", 0);
    if (object_status < 200 || object_status >= 300) {
      return cached_profile_or_error("OBJECT_STORAGE_UNAVAILABLE", true);
    }
    const mineg_error_code_t request_code = issue_account_request_locked(
        operation, "AVATAR_COMPLETE");
    if (request_code != MINEG_OK) return cached_profile_or_error("SESSION_INVALID", false);
    return account_operation_step_locked(operation, result);
  }

  if (operation.stage.rfind("TRANSPORT_", 0) != 0) {
    return cached_profile_or_error("OPERATION_STATE_INVALID", false);
  }
  const int64_t http_status = sqlite_json_integer(database_, effect_result, "$.payload.status", 0);
  const std::string request_id = sqlite_json_text(database_, effect_result, "$.payload.requestId");
  const std::string encoded_body = sqlite_json_text(database_, effect_result, "$.payload.bodyBase64");
  std::string response_body;
  if (http_status < 100 || !base64_decode(encoded_body, response_body) ||
      (!response_body.empty() && !valid_json(database_, response_body))) {
    wipe_string(response_body);
    return cached_profile_or_error("SERVICE_UNAVAILABLE", true, request_id);
  }
  const int64_t retry_after_seconds = std::clamp<int64_t>(
      sqlite_json_integer(database_, effect_result, "$.payload.retryAfterSeconds", 0), 0, 15 * 60);
  const auto problem = [this, &response_body, http_status]() {
    std::string code = sqlite_json_text(database_, response_body, "$.code");
    if (code.empty()) code = "SERVICE_UNAVAILABLE";
    const bool retryable = sqlite_json_boolean(database_, response_body, "$.retryable",
                                                http_status >= 500);
    return std::make_pair(code, retryable);
  };
  if (http_status < 200 || http_status >= 300) {
    const auto [code, retryable] = problem();
    if (operation.type != "RunBackupCycle" && retryable && http_status >= 500 &&
        operation.effect_retry_count < 1) {
      ++operation.effect_retry_count;
      wipe_string(response_body);
      return account_operation_step_locked(operation, result);
    }
    const bool authorized_request = operation.stage == "TRANSPORT_REVIEW" ||
        operation.stage == "TRANSPORT_PROFILE_GET" ||
        operation.stage == "TRANSPORT_PROFILE_UPDATE" ||
        operation.stage == "TRANSPORT_PRIVATE_MEDIA_LIST" ||
        operation.stage == "TRANSPORT_PRIVATE_MEDIA_REFRESH" ||
        operation.stage == "TRANSPORT_PRIVATE_MEDIA_LOAD_MORE" ||
        operation.stage == "TRANSPORT_PRIVATE_MEDIA_DETAIL" ||
        operation.stage == "TRANSPORT_PRIVATE_MEDIA_TRASH" ||
        operation.stage == "TRANSPORT_PRIVATE_MEDIA_VIEW_ACCESS" ||
        operation.stage == "TRANSPORT_PRIVATE_MEDIA_SHARE" ||
        operation.stage == "TRANSPORT_FAMILY_MEDIA_LIST" ||
        operation.stage == "TRANSPORT_FAMILY_MEDIA_DETAIL" ||
        operation.stage == "TRANSPORT_FAMILY_MEDIA_VIEW_ACCESS" ||
        operation.stage == "TRANSPORT_TRASH_MEDIA_LIST" ||
        operation.stage == "TRANSPORT_TRASH_MEDIA_RESTORE" ||
        operation.stage == "TRANSPORT_FEEDBACK_SUBMIT" ||
        operation.stage == "TRANSPORT_AVATAR_CREATE" ||
        operation.stage == "TRANSPORT_AVATAR_COMPLETE" ||
        operation.stage == "TRANSPORT_MEDIA_UPLOAD_STATUS" ||
        operation.stage == "TRANSPORT_MEDIA_UPLOAD_CREATE" ||
        operation.stage == "TRANSPORT_MEDIA_UPLOAD_REPORT" ||
        operation.stage == "TRANSPORT_MEDIA_UPLOAD_COMPLETE";
    if (http_status == 401 && authorized_request && !operation.replayed_after_refresh &&
        active_account_session_ != nullptr) {
      operation.replayed_after_refresh = true;
      operation.continuation = operation.stage.substr(std::string("TRANSPORT_").size());
      operation.refresh_token = active_account_session_->refresh_token;
      wipe_string(response_body);
      issue_account_request_locked(operation, "REFRESH");
      return account_operation_step_locked(operation, result);
    }
    if (operation.stage == "TRANSPORT_REFRESH" &&
        (code == "AUTH_REQUIRED" || code == "SESSION_INVALID" ||
         code == "SESSION_EXPIRED" || code == "SESSION_REPLAYED")) {
      const std::string completion = operation.type == "AccountRestoreSession"
          ? "RESTORE_NULL" : "ERROR:" + code;
      wipe_string(response_body);
      issue_session_cleanup_locked(operation, completion);
      return account_operation_step_locked(operation, result);
    }
    wipe_string(response_body);
    return cached_profile_or_error(code, retryable, request_id, retry_after_seconds);
  }

  if (operation.stage == "TRANSPORT_SIGN_IN" || operation.stage == "TRANSPORT_SIGN_UP" ||
      operation.stage == "TRANSPORT_REFRESH") {
    operation.user_id = sqlite_json_text(database_, response_body, "$.user_id");
    operation.access_token = sqlite_json_text(database_, response_body, "$.access_token");
    operation.access_expires_at = sqlite_json_text(database_, response_body, "$.access_expires_at");
    operation.refresh_token = sqlite_json_text(database_, response_body, "$.refresh_token");
    operation.refresh_expires_at = sqlite_json_text(database_, response_body, "$.refresh_expires_at");
    operation.approval_status = sqlite_json_text(database_, response_body, "$.approval_status");
    operation.next_step = sqlite_json_text(database_, response_body, "$.next_step");
    if (operation.masked_phone.empty()) {
      const std::string account = read_account_state_locked();
      operation.masked_phone = sqlite_json_text(database_, account, "$.state.maskedPhone");
    }
    const std::string continuation = operation.stage == "TRANSPORT_REFRESH"
        ? operation.continuation : "COMPLETE_SESSION";
    wipe_string(response_body);
    issue_session_write_locked(operation, continuation);
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_SIGN_OUT") {
    wipe_string(response_body);
    issue_session_cleanup_locked(operation, "SIGNED_OUT");
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_REVIEW") {
    const std::string approval = sqlite_json_text(database_, response_body, "$.status");
    const std::string next = sqlite_json_text(database_, response_body, "$.next_step");
    wipe_string(response_body);
    if (active_account_session_ == nullptr ||
        (approval != "PENDING" && approval != "APPROVED") ||
        (next != "REVIEW_PENDING" && next != "APP_HOME")) {
      return cached_profile_or_error("RESPONSE_INVALID", false, request_id);
    }
    active_account_session_->approval_status = approval;
    active_account_session_->next_step = next;
    sqlite3_stmt *statement = nullptr;
    if (sqlite3_prepare_v2(database_,
                           "UPDATE account_state SET approval_status=?,updated_at=? WHERE singleton=1",
                           -1, &statement, nullptr) != SQLITE_OK) {
      return cached_profile_or_error("DATABASE_ERROR", false);
    }
    int status = sqlite3_bind_text(statement, 1, approval.c_str(), -1, SQLITE_TRANSIENT);
    const std::string updated_at = now_rfc3339();
    if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, updated_at.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(statement);
    sqlite3_finalize(statement);
    if (status != SQLITE_DONE) return cached_profile_or_error("DATABASE_ERROR", false);
    operation.status = "COMPLETED";
    operation.terminal_payload = "{\"approvalStatus\":\"" + approval +
        "\",\"nextStep\":\"" + next + "\"}";
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_PROFILE_GET" ||
      operation.stage == "TRANSPORT_PROFILE_UPDATE") {
    if (!persist_current_profile_locked(response_body, operation.contract_version)) {
      wipe_string(response_body);
      return cached_profile_or_error("PROFILE_MISMATCH", false, request_id);
    }
    wipe_string(response_body);
    operation.status = "COMPLETED";
    operation.terminal_payload = read_current_profile_snapshot_locked();
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_PRIVATE_MEDIA_LIST") {
    if (!persist_private_media_locked(response_body)) {
      wipe_string(response_body);
      return cached_profile_or_error("PRIVATE_MEDIA_RESPONSE_INVALID", false, request_id);
    }
    wipe_string(response_body);
    operation.status = "COMPLETED";
    operation.terminal_payload = read_private_media_snapshot_locked(
        static_cast<int>(operation.media_limit));
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_PRIVATE_MEDIA_REFRESH" ||
      operation.stage == "TRANSPORT_PRIVATE_MEDIA_LOAD_MORE") {
    const bool replace = operation.stage == "TRANSPORT_PRIVATE_MEDIA_REFRESH";
    if (!persist_private_media_page_v2_locked(response_body, replace)) {
      wipe_string(response_body);
      return cached_profile_or_error("PRIVATE_MEDIA_RESPONSE_INVALID", false, request_id);
    }
    const std::string response_page = read_private_media_page_v2_locked(
        static_cast<int>(operation.media_limit), response_body);
    wipe_string(response_body);
    if (response_page.empty()) {
      return cached_profile_or_error("PRIVATE_MEDIA_RESPONSE_INVALID", false, request_id);
    }
    operation.status = "COMPLETED";
    operation.terminal_payload = response_page;
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_PRIVATE_MEDIA_DETAIL") {
    if (!persist_private_media_detail_v2_locked(response_body)) {
      wipe_string(response_body);
      return cached_profile_or_error("PRIVATE_MEDIA_RESPONSE_INVALID", false, request_id);
    }
    wipe_string(response_body);
    operation.status = "COMPLETED";
    operation.terminal_payload = read_private_media_detail_v2_locked(operation.private_media_id);
    if (operation.terminal_payload.empty()) {
      return cached_profile_or_error("PRIVATE_MEDIA_RESPONSE_INVALID", false, request_id);
    }
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_PRIVATE_MEDIA_TRASH") {
    const std::string response_media_id = sqlite_json_text(database_, response_body, "$.media_id");
    const std::string outcome = sqlite_json_text(database_, response_body, "$.outcome");
    const std::string trashed_at = sqlite_json_text(database_, response_body, "$.trashed_at");
    wipe_string(response_body);
    if (response_media_id != operation.private_media_id ||
        (outcome != "TRASHED" && outcome != "ALREADY_TRASHED") || trashed_at.empty() ||
        !remove_private_media_v2_locked(operation.private_media_id)) {
      return cached_profile_or_error("PRIVATE_MEDIA_TRASH_CONFLICT", false, request_id);
    }
    operation.status = "COMPLETED";
    operation.terminal_payload = "{\"mediaId\":\"" + json_escape(response_media_id) +
        "\",\"outcome\":\"" + json_escape(outcome) + "\",\"trashedAt\":\"" +
        json_escape(trashed_at) + "\"}";
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_PRIVATE_MEDIA_SHARE") {
    const std::string response_media_id = sqlite_json_text(database_, response_body, "$.media_id");
    const std::string state = sqlite_json_text(database_, response_body, "$.state");
    const std::string outcome = sqlite_json_text(database_, response_body, "$.outcome");
    const std::string effective_at = sqlite_json_text(database_, response_body, "$.effective_at");
    wipe_string(response_body);
    const std::string expected_state = operation.private_media_share_active ? "ACTIVE" : "INACTIVE";
    const bool valid_outcome = operation.private_media_share_active
        ? (outcome == "SHARED" || outcome == "ALREADY_SHARED")
        : (outcome == "UNSHARED" || outcome == "ALREADY_UNSHARED");
    if (response_media_id != operation.private_media_id || state != expected_state ||
        !valid_outcome || effective_at.empty()) {
      return cached_profile_or_error("PRIVATE_MEDIA_SHARE_CONFLICT", false, request_id);
    }
    ++event_sequence_;
    emit_locked("{\"contractVersion\":\"stage06-v1\",\"type\":\"PrivateMediaShareChanged\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"mediaId\":\"" +
                json_escape(response_media_id) + "\",\"state\":\"" + json_escape(state) + "\"}");
    operation.status = "COMPLETED";
    operation.terminal_payload = "{\"mediaId\":\"" + json_escape(response_media_id) +
        "\",\"state\":\"" + json_escape(state) + "\",\"outcome\":\"" +
        json_escape(outcome) + "\",\"effectiveAt\":\"" + json_escape(effective_at) + "\"}";
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_FAMILY_MEDIA_LIST") {
    const int64_t count = json_array_length(database_, response_body, "$.items");
    if (count < 0 || count > 100) {
      wipe_string(response_body);
      return cached_profile_or_error("FAMILY_MEDIA_RESPONSE_INVALID", false, request_id);
    }
    std::string page = "{\"items\":[";
    for (int64_t index = 0; index < count; ++index) {
      const std::string base = "$.items[" + std::to_string(index) + "]";
      const std::string id = sqlite_json_text(database_, response_body, base + ".id");
      const std::string owner_id = sqlite_json_text(database_, response_body, base + ".owner.id");
      const std::string nickname = sqlite_json_text(database_, response_body, base + ".owner.nickname");
      const std::string media_type = sqlite_json_text(database_, response_body, base + ".media_type");
      const std::string captured_at = sqlite_json_text(database_, response_body, base + ".captured_at");
      const std::string created_at = sqlite_json_text(database_, response_body, base + ".created_at");
      const std::string duration = sqlite_json_text(database_, response_body, base + ".duration_ms");
      const int64_t size = sqlite_json_integer(database_, response_body, base + ".original_total_size", -1);
      if (id.size() != 36U || owner_id.size() != 36U || nickname.empty() || media_type.empty() ||
          captured_at.empty() || created_at.empty() || size < 0) {
        wipe_string(response_body);
        return cached_profile_or_error("FAMILY_MEDIA_RESPONSE_INVALID", false, request_id);
      }
      if (index > 0) page += ',';
      page += "{\"id\":\"" + json_escape(id) + "\",\"owner\":{\"id\":\"" +
          json_escape(owner_id) + "\",\"nickname\":\"" + json_escape(nickname) +
          "\"},\"mediaType\":\"" + json_escape(media_type) + "\",\"capturedAt\":\"" +
          json_escape(captured_at) + "\",\"createdAt\":\"" + json_escape(created_at) +
          "\",\"durationMs\":" + (duration.empty() ? std::string("null") : duration) +
          ",\"originalTotalSize\":" + std::to_string(size) + "}";
    }
    const std::string cursor = sqlite_json_text(database_, response_body, "$.next_cursor");
    wipe_string(response_body);
    page += "],\"nextCursor\":" + (cursor.empty() ? std::string("null") :
        "\"" + json_escape(cursor) + "\"") + ",\"fullyLoaded\":" +
        (cursor.empty() ? "true" : "false") + "}";
    operation.status = "COMPLETED";
    operation.terminal_payload = page;
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_FAMILY_MEDIA_DETAIL") {
    const std::string id = sqlite_json_text(database_, response_body, "$.id");
    const std::string owner_id = sqlite_json_text(database_, response_body, "$.owner.id");
    const std::string nickname = sqlite_json_text(database_, response_body, "$.owner.nickname");
    const std::string media_type = sqlite_json_text(database_, response_body, "$.media_type");
    const std::string captured_at = sqlite_json_text(database_, response_body, "$.captured_at");
    const std::string created_at = sqlite_json_text(database_, response_body, "$.created_at");
    const std::string width = sqlite_json_text(database_, response_body, "$.width");
    const std::string height = sqlite_json_text(database_, response_body, "$.height");
    const std::string duration = sqlite_json_text(database_, response_body, "$.duration_ms");
    const int64_t size = sqlite_json_integer(database_, response_body, "$.original_total_size", -1);
    const int64_t resource_count = json_array_length(database_, response_body, "$.resources");
    if (id != operation.private_media_id || owner_id.size() != 36U || nickname.empty() ||
        media_type.empty() || captured_at.empty() || created_at.empty() || size < 0 ||
        resource_count < 1 || resource_count > 8) {
      wipe_string(response_body);
      return cached_profile_or_error("FAMILY_MEDIA_RESPONSE_INVALID", false, request_id);
    }
    std::string detail = "{\"id\":\"" + json_escape(id) + "\",\"owner\":{\"id\":\"" +
        json_escape(owner_id) + "\",\"nickname\":\"" + json_escape(nickname) +
        "\"},\"mediaType\":\"" + json_escape(media_type) + "\",\"capturedAt\":\"" +
        json_escape(captured_at) + "\",\"createdAt\":\"" + json_escape(created_at) +
        "\",\"width\":" + (width.empty() ? std::string("null") : width) +
        ",\"height\":" + (height.empty() ? std::string("null") : height) +
        ",\"durationMs\":" + (duration.empty() ? std::string("null") : duration) +
        ",\"originalTotalSize\":" + std::to_string(size) + ",\"resources\":[";
    for (int64_t index = 0; index < resource_count; ++index) {
      const std::string base = "$.resources[" + std::to_string(index) + "]";
      const std::string resource_id = sqlite_json_text(database_, response_body, base + ".resource_id");
      const std::string resource_type = sqlite_json_text(database_, response_body, base + ".resource_type");
      const std::string mime_type = sqlite_json_text(database_, response_body, base + ".mime_type");
      const std::string digest = sqlite_json_text(database_, response_body, base + ".content_sha256");
      const int64_t resource_size = sqlite_json_integer(database_, response_body, base + ".content_size", -1);
      if (resource_id.size() != 36U || resource_type.empty() || mime_type.empty() || digest.empty() || resource_size < 1) {
        wipe_string(response_body);
        return cached_profile_or_error("FAMILY_MEDIA_RESPONSE_INVALID", false, request_id);
      }
      if (index > 0) detail += ',';
      detail += "{\"resourceId\":\"" + json_escape(resource_id) +
          "\",\"resourceType\":\"" + json_escape(resource_type) +
          "\",\"mimeType\":\"" + json_escape(mime_type) +
          "\",\"contentSize\":" + std::to_string(resource_size) +
          ",\"contentSha256\":\"" + json_escape(digest) + "\"}";
    }
    wipe_string(response_body);
    detail += "]}";
    operation.status = "COMPLETED";
    operation.terminal_payload = detail;
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_FAMILY_MEDIA_VIEW_ACCESS") {
    if (!prepare_private_media_view_resource_locked(operation, response_body)) {
      wipe_string(response_body);
      return cached_profile_or_error("FAMILY_MEDIA_RESOURCE_UNAVAILABLE", false, request_id);
    }
    wipe_string(response_body);
    set_account_effect_locked(operation, "FileEffect",
        "{\"action\":\"createTaskTempFile\",\"name\":\"private-view-" +
        std::to_string(operation.operation_id) + "\"}", "PRIVATE_MEDIA_VIEW_CREATE_TEMP");
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_TRASH_MEDIA_LIST") {
    const int64_t count = json_array_length(database_, response_body, "$.items");
    if (count < 0 || count > 100) {
      wipe_string(response_body);
      return cached_profile_or_error("TRASH_MEDIA_RESPONSE_INVALID", false, request_id);
    }
    std::string page = "{\"items\":[";
    for (int64_t index = 0; index < count; ++index) {
      const std::string base = "$.items[" + std::to_string(index) + "]";
      const std::string id = sqlite_json_text(database_, response_body, base + ".id");
      const std::string media_type = sqlite_json_text(database_, response_body, base + ".media_type");
      const std::string captured_at = sqlite_json_text(database_, response_body, base + ".captured_at");
      const std::string created_at = sqlite_json_text(database_, response_body, base + ".created_at");
      const std::string trashed_at = sqlite_json_text(database_, response_body, base + ".trashed_at");
      const std::string duration = sqlite_json_text(database_, response_body, base + ".duration_ms");
      const int64_t size = sqlite_json_integer(database_, response_body, base + ".original_total_size", -1);
      if (id.size() != 36U || media_type.empty() || captured_at.empty() || created_at.empty() ||
          trashed_at.empty() || size < 0) {
        wipe_string(response_body);
        return cached_profile_or_error("TRASH_MEDIA_RESPONSE_INVALID", false, request_id);
      }
      if (index > 0) page += ',';
      page += "{\"id\":\"" + json_escape(id) + "\",\"mediaType\":\"" +
          json_escape(media_type) + "\",\"capturedAt\":\"" + json_escape(captured_at) +
          "\",\"createdAt\":\"" + json_escape(created_at) + "\",\"durationMs\":" +
          (duration.empty() ? std::string("null") : duration) + ",\"originalTotalSize\":" +
          std::to_string(size) + ",\"trashedAt\":\"" + json_escape(trashed_at) + "\"}";
    }
    const std::string cursor = sqlite_json_text(database_, response_body, "$.next_cursor");
    wipe_string(response_body);
    page += "],\"nextCursor\":" + (cursor.empty() ? std::string("null") :
        "\"" + json_escape(cursor) + "\"") + ",\"fullyLoaded\":" +
        (cursor.empty() ? "true" : "false") + "}";
    operation.status = "COMPLETED";
    operation.terminal_payload = page;
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_TRASH_MEDIA_RESTORE") {
    const std::string id = sqlite_json_text(database_, response_body, "$.media_id");
    const std::string outcome = sqlite_json_text(database_, response_body, "$.outcome");
    const std::string restored_at = sqlite_json_text(database_, response_body, "$.restored_at");
    wipe_string(response_body);
    if (id != operation.private_media_id ||
        (outcome != "RESTORED" && outcome != "ALREADY_RESTORED") || restored_at.empty()) {
      return cached_profile_or_error("TRASH_RESTORE_CONFLICT", false, request_id);
    }
    ++event_sequence_;
    emit_locked("{\"contractVersion\":\"stage06-v1\",\"type\":\"PrivateMediaRestored\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"mediaId\":\"" + json_escape(id) + "\"}");
    operation.status = "COMPLETED";
    operation.terminal_payload = "{\"mediaId\":\"" + json_escape(id) +
        "\",\"outcome\":\"" + json_escape(outcome) + "\",\"restoredAt\":\"" +
        json_escape(restored_at) + "\"}";
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_FEEDBACK_SUBMIT") {
    const std::string feedback_id = sqlite_json_text(database_, response_body, "$.feedback_id");
    const std::string outcome = sqlite_json_text(database_, response_body, "$.outcome");
    const std::string created_at = sqlite_json_text(database_, response_body, "$.created_at");
    wipe_string(response_body);
    if (feedback_id.size() != 36U ||
        (outcome != "SUBMITTED" && outcome != "ALREADY_SUBMITTED") || created_at.empty()) {
      return cached_profile_or_error("FEEDBACK_RESPONSE_INVALID", false, request_id);
    }
    ++event_sequence_;
    emit_locked("{\"contractVersion\":\"stage06-v1\",\"type\":\"FeedbackSubmitted\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"feedbackId\":\"" +
                json_escape(feedback_id) + "\"}");
    operation.status = "COMPLETED";
    operation.terminal_payload = "{\"feedbackId\":\"" + json_escape(feedback_id) +
        "\",\"outcome\":\"" + json_escape(outcome) + "\",\"createdAt\":\"" +
        json_escape(created_at) + "\"}";
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_PRIVATE_MEDIA_VIEW_ACCESS") {
    if (!prepare_private_media_view_resource_locked(operation, response_body)) {
      wipe_string(response_body);
      return cached_profile_or_error("PRIVATE_MEDIA_RESOURCE_UNAVAILABLE", false, request_id);
    }
    wipe_string(response_body);
    set_account_effect_locked(operation, "FileEffect",
        "{\"action\":\"createTaskTempFile\",\"name\":\"private-view-" +
        std::to_string(operation.operation_id) + "\"}", "PRIVATE_MEDIA_VIEW_CREATE_TEMP");
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_AVATAR_CREATE") {
    operation.avatar_upload_id = sqlite_json_text(database_, response_body, "$.upload_id");
    const std::string url = sqlite_json_text(database_, response_body, "$.grant.url");
    const std::string method = sqlite_json_text(database_, response_body, "$.grant.method");
    std::string headers = sqlite_json_text(database_, response_body, "$.grant.headers");
    wipe_string(response_body);
    if (operation.avatar_upload_id.empty() || url.empty() || method != "PUT") {
      return cached_profile_or_error("AVATAR_UPLOAD_RESPONSE_INVALID", false, request_id);
    }
    if (headers.empty()) headers = "{}";
    set_account_effect_locked(
        operation, "TransportEffect",
        "{\"action\":\"uploadObject\",\"url\":\"" + json_escape(url) +
        "\",\"method\":\"PUT\",\"headers\":" + headers +
        ",\"bodyBase64\":\"" + base64_encode(operation.avatar_bytes) + "\"}",
        "TRANSPORT_AVATAR_OBJECT");
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_AVATAR_COMPLETE") {
    if (!persist_current_profile_locked(response_body, operation.contract_version)) {
      wipe_string(response_body);
      return cached_profile_or_error("PROFILE_MISMATCH", false, request_id);
    }
    wipe_string(response_body);
    operation.status = "COMPLETED";
    operation.terminal_payload = read_current_profile_snapshot_locked();
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_MEDIA_UPLOAD_CREATE") {
    const std::string state = sqlite_json_text(database_, response_body, "$.state");
    operation.media_upload_id = sqlite_json_text(database_, response_body, "$.id");
    if (state == "COMPLETED") {
      const std::string media_id = sqlite_json_text(database_, response_body, "$.media_id");
      const bool deduplicated = sqlite_json_boolean(database_, response_body, "$.deduplicated", false);
      wipe_string(response_body);
      if (operation.media_upload_id.empty() || media_id.empty()) {
        return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
      }
      operation.media_pending_result = "{\"uploadId\":\"" + operation.media_upload_id +
          "\",\"mediaId\":\"" + media_id + "\",\"deduplicated\":" +
          (deduplicated ? "true" : "false") + "}";
      set_account_effect_locked(operation, "MediaSourceEffect",
          "{\"action\":\"releaseMediaResource\",\"resourceHandle\":\"" +
          json_escape(operation.media_resource_handle) + "\"}", "MEDIA_UPLOAD_RELEASE_SOURCE");
      return account_operation_step_locked(operation, result);
    }
    const std::string purpose = sqlite_json_text(database_, response_body, "$.purpose");
    const int64_t resource_count = json_array_length(database_, response_body, "$.grant.resources");
    const int64_t part_count = json_array_length(database_, response_body, "$.grant.resources[0].parts");
    if (state != "PENDING" || purpose != "MEDIA_ORIGINAL" || operation.media_upload_id.empty() ||
        resource_count != 1 || part_count != static_cast<int64_t>(operation.media_part_sizes.size()) ||
        sqlite_json_text(database_, response_body, "$.grant.resources[0].resource_id") != operation.media_resource_id) {
      wipe_string(response_body);
      return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
    }
    if (operation.type == "RunBackupCycle") {
      sqlite3_stmt *task = nullptr;
      if (sqlite3_prepare_v2(database_, "UPDATE backup_tasks SET state='UPLOADING',"
                             "resume_state='UPLOADING',server_upload_id=?,updated_at=? WHERE task_id=? AND lease_token=?",
                             -1, &task, nullptr) != SQLITE_OK) {
        wipe_string(response_body);
        return cached_profile_or_error("DATABASE_ERROR", false, request_id);
      }
      const std::string now = now_rfc3339();
      int task_status = sqlite3_bind_text(task, 1, operation.media_upload_id.c_str(), -1, SQLITE_TRANSIENT);
      if (task_status == SQLITE_OK) task_status = sqlite3_bind_text(task, 2, now.c_str(), -1, SQLITE_TRANSIENT);
      if (task_status == SQLITE_OK) task_status = sqlite3_bind_text(task, 3, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
      if (task_status == SQLITE_OK) task_status = sqlite3_bind_text(task, 4, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
      if (task_status == SQLITE_OK) task_status = sqlite3_step(task);
      sqlite3_finalize(task);
      if (task_status != SQLITE_DONE || sqlite3_changes(database_) != 1) {
        wipe_string(response_body);
        return cached_profile_or_error("DATABASE_ERROR", false, request_id);
      }
    }
    operation.media_upload_response = response_body;
    wipe_string(response_body);
    operation.media_part_index = 0;
    if (operation.type == "RunBackupCycle") {
      sqlite3_stmt *pending = nullptr;
      if (sqlite3_prepare_v2(database_, "SELECT min(part_number) FROM backup_parts "
                             "WHERE resource_id=? AND state<>'CONFIRMED'", -1, &pending, nullptr) != SQLITE_OK) {
        return cached_profile_or_error("DATABASE_ERROR", false, request_id);
      }
      int pending_status = sqlite3_bind_text(pending, 1, operation.media_resource_id.c_str(), -1, SQLITE_TRANSIENT);
      if (pending_status == SQLITE_OK) pending_status = sqlite3_step(pending);
      const int64_t next_part = pending_status == SQLITE_ROW && sqlite3_column_type(pending, 0) != SQLITE_NULL
          ? sqlite3_column_int64(pending, 0) : 0;
      sqlite3_finalize(pending);
      if (pending_status != SQLITE_ROW || next_part < 0 ||
          next_part > static_cast<int64_t>(operation.media_part_sizes.size())) {
        return cached_profile_or_error("DATABASE_ERROR", false, request_id);
      }
      operation.media_part_index = next_part == 0
          ? static_cast<int64_t>(operation.media_part_sizes.size()) : next_part - 1;
    }
    if (operation.media_part_index >= static_cast<int64_t>(operation.media_part_sizes.size())) {
      if (!issue_media_complete()) return cached_profile_or_error("SESSION_INVALID", false);
      return account_operation_step_locked(operation, result);
    }
    if (!issue_media_part()) return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_MEDIA_UPLOAD_STATUS") {
    const std::string state = sqlite_json_text(database_, response_body, "$.state");
    const std::string upload_id = sqlite_json_text(database_, response_body, "$.id");
    const std::string media_id = sqlite_json_text(database_, response_body, "$.media_id");
    if (upload_id.empty() || upload_id != operation.media_upload_id) {
      wipe_string(response_body);
      return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
    }
    if (state == "COMPLETED") {
      wipe_string(response_body);
      if (media_id.empty()) return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
      operation.media_pending_result = "{\"uploadId\":\"" + operation.media_upload_id +
          "\",\"mediaId\":\"" + media_id + "\",\"deduplicated\":false}";
      set_account_effect_locked(operation, "MediaSourceEffect",
          "{\"action\":\"releaseMediaResource\",\"resourceHandle\":\"" +
          json_escape(operation.media_resource_handle) + "\"}", "MEDIA_UPLOAD_RELEASE_SOURCE");
      return account_operation_step_locked(operation, result);
    }
    if (state == "VERIFYING") {
      wipe_string(response_body);
      return cached_profile_or_error("BACKUP_SERVER_CONFIRMATION_PENDING", true, request_id);
    }
    if (state != "PENDING" && state != "EXPIRED") {
      wipe_string(response_body);
      return cached_profile_or_error("BACKUP_UPLOAD_SESSION_EXPIRED", true, request_id);
    }
    const bool reconciled = operation.type != "RunBackupCycle" ||
        reconcile_backup_confirmed_parts_locked(operation, response_body);
    wipe_string(response_body);
    if (!reconciled) return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
    // The idempotent create endpoint is the grant-refresh endpoint. It revives an expired
    // session when necessary and returns fresh, owner-bound object grants.
    if (!issue_media_create()) return cached_profile_or_error("SESSION_INVALID", false);
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_MEDIA_UPLOAD_REPORT") {
    const int64_t reported_number = sqlite_json_integer(database_, response_body, "$.part_number", -1);
    wipe_string(response_body);
    if (reported_number != operation.media_part_index + 1) {
      return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
    }
    if (operation.type == "RunBackupCycle" &&
        !confirm_backup_part_locked(operation, reported_number, operation.media_part_etag)) {
      return cached_profile_or_error("DATABASE_ERROR", false, request_id);
    }
    if (operation.type == "RunBackupCycle" && !operation.media_uploaded_part_indexes.empty()) {
      if (operation.media_uploaded_part_report_index < 0 ||
          operation.media_uploaded_part_report_index >=
              static_cast<int64_t>(operation.media_uploaded_part_indexes.size()) ||
          reported_number != operation.media_uploaded_part_indexes[
              static_cast<size_t>(operation.media_uploaded_part_report_index)] + 1) {
        return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
      }
      ++operation.media_uploaded_part_report_index;
      if (operation.media_uploaded_part_report_index <
          static_cast<int64_t>(operation.media_uploaded_part_indexes.size())) {
        const size_t next = static_cast<size_t>(operation.media_uploaded_part_report_index);
        operation.media_part_index = operation.media_uploaded_part_indexes[next];
        operation.media_part_etag = operation.media_uploaded_part_etags[next];
        if (!issue_media_report()) return cached_profile_or_error("SESSION_INVALID", false);
        return account_operation_step_locked(operation, result);
      }
      operation.media_uploaded_part_indexes.clear();
      for (auto &etag : operation.media_uploaded_part_etags) wipe_string(etag);
      operation.media_uploaded_part_etags.clear();
      operation.media_uploaded_part_report_index = 0;
    }
    wipe_string(operation.media_part_etag);
    ++operation.media_part_index;
    if (operation.type == "RunBackupCycle") {
      sqlite3_stmt *pending = nullptr;
      if (sqlite3_prepare_v2(database_, "SELECT min(part_number) FROM backup_parts "
                             "WHERE resource_id=? AND state<>'CONFIRMED'", -1, &pending, nullptr) != SQLITE_OK) {
        return cached_profile_or_error("DATABASE_ERROR", false, request_id);
      }
      int pending_status = sqlite3_bind_text(pending, 1, operation.media_resource_id.c_str(), -1, SQLITE_TRANSIENT);
      if (pending_status == SQLITE_OK) pending_status = sqlite3_step(pending);
      const int64_t next_part = pending_status == SQLITE_ROW && sqlite3_column_type(pending, 0) != SQLITE_NULL
          ? sqlite3_column_int64(pending, 0) : 0;
      sqlite3_finalize(pending);
      if (pending_status != SQLITE_ROW || next_part < 0 ||
          next_part > static_cast<int64_t>(operation.media_part_sizes.size())) {
        return cached_profile_or_error("DATABASE_ERROR", false, request_id);
      }
      operation.media_part_index = next_part == 0
          ? static_cast<int64_t>(operation.media_part_sizes.size()) : next_part - 1;
    }
    if (operation.media_part_index < static_cast<int64_t>(operation.media_part_sizes.size())) {
      if (!issue_media_part()) return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
      return account_operation_step_locked(operation, result);
    }
    if (!issue_media_complete()) {
      return cached_profile_or_error("SESSION_INVALID", false);
    }
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_MEDIA_UPLOAD_COMPLETE") {
    const std::string media_id = sqlite_json_text(database_, response_body, "$.media_id");
    const bool deduplicated = sqlite_json_boolean(database_, response_body, "$.deduplicated", false);
    wipe_string(response_body);
    if (media_id.empty()) return cached_profile_or_error("MEDIA_UPLOAD_RESPONSE_INVALID", false, request_id);
    operation.media_pending_result = "{\"uploadId\":\"" + operation.media_upload_id +
        "\",\"mediaId\":\"" + media_id + "\",\"deduplicated\":" +
        (deduplicated ? "true" : "false") + "}";
    set_account_effect_locked(operation, "MediaSourceEffect",
        "{\"action\":\"releaseMediaResource\",\"resourceHandle\":\"" +
        json_escape(operation.media_resource_handle) + "\"}", "MEDIA_UPLOAD_RELEASE_SOURCE");
    return account_operation_step_locked(operation, result);
  }
  wipe_string(response_body);
  return cached_profile_or_error("OPERATION_STATE_INVALID", false);
}

mineg_error_code_t Core::start_operation(uint64_t operation_id, const std::string &command,
                                          std::string &result) {
  if (operation_id == 0 || operation_id > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) ||
      command.empty() || command.size() > 16U * 1024U * 1024U) {
    return MINEG_INVALID_ARGUMENT;
  }
  const std::string command_type = top_level_json_string(command, "type");
  const std::string contract_version = top_level_json_string(command, "contractVersion");
  if (contract_version == "account-v3" ||
      contract_version == "stage02-v2" || contract_version == "stage03-v2" ||
      contract_version == "stage04-v1" || contract_version == "stage05-v1" ||
      contract_version == "stage06-v1") {
    std::lock_guard<std::mutex> lock(mutex_);
    if (cancelled_operations_.erase(operation_id) > 0) return MINEG_CANCELLED;
    return start_account_operation_locked(operation_id, command, result);
  }
  const std::string effect_type = top_level_json_string(command, "effectType");
  const std::string effect_payload = extract_top_level_json_value(command, "payload");
  const int64_t max_retries = extract_json_integer(command, "maxRetries", 0);
  if (contract_version != "foundation-v2" || command_type != "FoundationEffectProbe" ||
      !supported_effect_type(effect_type) || effect_payload.size() < 2U ||
      effect_payload.front() != '{' || effect_payload.back() != '}' || max_retries < 0 ||
      max_retries > 3) {
    return MINEG_INVALID_ARGUMENT;
  }

  std::lock_guard<std::mutex> lock(mutex_);
  if (!valid_json(database_, command) || !valid_json(database_, effect_payload)) {
    return MINEG_INVALID_ARGUMENT;
  }
  if (cancelled_operations_.erase(operation_id) > 0) return MINEG_CANCELLED;
  std::string existing_command;
  mineg_error_code_t existing =
      read_operation_step_locked(operation_id, result, &existing_command, nullptr);
  if (existing == MINEG_OK) return existing_command == command ? MINEG_OK : MINEG_INVALID_ARGUMENT;
  if (existing != MINEG_NOT_FOUND) return existing;

  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "INSERT INTO core_operations(operation_id,contract_version,command_type,command_json,"
      "sequence,status,effect_type,effect_payload) VALUES(?, 'foundation-v2', ?, ?, 1, "
      "'WAITING_FOR_EFFECT', ?, ?)";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_int64(statement, 1, static_cast<long long>(operation_id));
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 2, command_type.c_str(), -1, SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 3, command.c_str(), static_cast<int>(command.size()),
                               SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 4, effect_type.c_str(), -1, SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) {
    status = sqlite3_bind_text(statement, 5, effect_payload.c_str(),
                               static_cast<int>(effect_payload.size()), SQLITE_TRANSIENT);
  }
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  result = operation_step_json(operation_id, 1, "WAITING_FOR_EFFECT", effect_type,
                               effect_payload, {});
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"foundation-v2\",\"type\":\"CoreOperationChanged\","
              "\"sequence\":" + std::to_string(event_sequence_) + ",\"operationId\":" +
              std::to_string(operation_id) + ",\"status\":\"WAITING_FOR_EFFECT\"}");
  return MINEG_OK;
}

mineg_error_code_t Core::resume_operation(uint64_t operation_id,
                                           const std::string &effect_result,
                                           std::string &result) {
  if (operation_id == 0 || operation_id > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) ||
      effect_result.empty() || effect_result.size() > 1024U * 1024U ||
      top_level_json_string(effect_result, "contractVersion") != "foundation-v2" ||
      top_level_json_u64(effect_result, "operationId") != operation_id) {
    return MINEG_INVALID_ARGUMENT;
  }
  const uint64_t result_sequence = top_level_json_u64(effect_result, "sequence");
  const std::string result_effect_type = top_level_json_string(effect_result, "effectType");
  const std::string result_status = top_level_json_string(effect_result, "status");
  if (result_sequence == 0 || !supported_effect_type(result_effect_type) ||
      (result_status != "SUCCEEDED" && result_status != "FAILED" &&
       result_status != "CANCELLED")) {
    return MINEG_INVALID_ARGUMENT;
  }
  std::string terminal_payload = extract_top_level_json_value(
      effect_result, result_status == "SUCCEEDED" ? "payload" : "error");
  if (terminal_payload.empty()) terminal_payload = "null";

  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (account_operations_.find(operation_id) != account_operations_.end()) {
      return resume_account_operation_locked(operation_id, effect_result, result);
    }
  }

  std::lock_guard<std::mutex> lock(mutex_);
  if (!valid_json(database_, effect_result) ||
      (result_status == "SUCCEEDED" && terminal_payload != "null" &&
       (terminal_payload.size() < 2U || terminal_payload.front() != '{' ||
        terminal_payload.back() != '}')) ||
      (result_status == "FAILED" &&
       (terminal_payload.size() < 2U || terminal_payload.front() != '{' ||
        terminal_payload.back() != '}'))) {
    return MINEG_INVALID_ARGUMENT;
  }
  sqlite3_stmt *read = nullptr;
  const char *read_sql =
      "SELECT sequence,status,effect_type,effect_result_json,command_json FROM core_operations "
      "WHERE operation_id=?";
  if (sqlite3_prepare_v2(database_, read_sql, -1, &read, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int sqlite_status = sqlite3_bind_int64(read, 1, static_cast<long long>(operation_id));
  if (sqlite_status == SQLITE_OK) sqlite_status = sqlite3_step(read);
  if (sqlite_status == SQLITE_DONE) {
    sqlite3_finalize(read);
    return MINEG_NOT_FOUND;
  }
  if (sqlite_status != SQLITE_ROW) {
    sqlite3_finalize(read);
    return MINEG_DATABASE_ERROR;
  }
  const uint64_t expected_sequence = static_cast<uint64_t>(sqlite3_column_int64(read, 0));
  const auto text_at = [read](int column) -> std::string {
    const auto *value = sqlite3_column_text(read, column);
    return value == nullptr ? std::string{} : reinterpret_cast<const char *>(value);
  };
  const std::string current_status = text_at(1);
  const std::string expected_effect_type = text_at(2);
  const std::string previous_result = text_at(3);
  const std::string command_json = text_at(4);
  sqlite3_finalize(read);
  if (!previous_result.empty() && previous_result == effect_result) {
    return read_operation_step_locked(operation_id, result);
  }
  if (current_status != "WAITING_FOR_EFFECT") {
    return current_status == "CANCELLED" ? MINEG_CANCELLED : MINEG_INVALID_ARGUMENT;
  }
  if (result_sequence != expected_sequence || result_effect_type != expected_effect_type) {
    return MINEG_INVALID_ARGUMENT;
  }
  const int64_t max_retries = extract_json_integer(command_json, "maxRetries", 0);
  const bool retryable_failure = result_status == "FAILED" &&
      extract_json_boolean(terminal_payload, "retryable", false) &&
      result_sequence <= static_cast<uint64_t>(max_retries);
  const std::string next_status = retryable_failure ? "WAITING_FOR_EFFECT" :
                                  result_status == "SUCCEEDED" ? "COMPLETED" :
                                  result_status == "FAILED" ? "FAILED" : "CANCELLED";
  sqlite3_stmt *update = nullptr;
  const char *update_sql =
      "UPDATE core_operations SET sequence=sequence+1,status=?,effect_result_json=?,"
      "terminal_payload=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') "
      "WHERE operation_id=? AND status='WAITING_FOR_EFFECT' AND sequence=? AND effect_type=?";
  if (sqlite3_prepare_v2(database_, update_sql, -1, &update, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  sqlite_status = sqlite3_bind_text(update, 1, next_status.c_str(), -1, SQLITE_TRANSIENT);
  if (sqlite_status == SQLITE_OK) {
    sqlite_status = sqlite3_bind_text(update, 2, effect_result.c_str(),
                                      static_cast<int>(effect_result.size()), SQLITE_TRANSIENT);
  }
  if (sqlite_status == SQLITE_OK && retryable_failure) sqlite_status = sqlite3_bind_null(update, 3);
  if (sqlite_status == SQLITE_OK && !retryable_failure) {
    sqlite_status = sqlite3_bind_text(update, 3, terminal_payload.c_str(),
                                      static_cast<int>(terminal_payload.size()), SQLITE_TRANSIENT);
  }
  if (sqlite_status == SQLITE_OK) {
    sqlite_status = sqlite3_bind_int64(update, 4, static_cast<long long>(operation_id));
  }
  if (sqlite_status == SQLITE_OK) {
    sqlite_status = sqlite3_bind_int64(update, 5, static_cast<long long>(result_sequence));
  }
  if (sqlite_status == SQLITE_OK) {
    sqlite_status = sqlite3_bind_text(update, 6, result_effect_type.c_str(), -1, SQLITE_TRANSIENT);
  }
  if (sqlite_status == SQLITE_OK) sqlite_status = sqlite3_step(update);
  sqlite3_finalize(update);
  if (sqlite_status != SQLITE_DONE || sqlite3_changes(database_) != 1) {
    return MINEG_DATABASE_ERROR;
  }
  const mineg_error_code_t read_code = read_operation_step_locked(operation_id, result);
  if (read_code != MINEG_OK) return read_code;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"foundation-v2\",\"type\":\"CoreOperationChanged\","
              "\"sequence\":" + std::to_string(event_sequence_) + ",\"operationId\":" +
              std::to_string(operation_id) + ",\"status\":\"" + next_status + "\"}");
  return MINEG_OK;
}

mineg_error_code_t Core::recover_operations(std::string &result) {
  std::lock_guard<std::mutex> lock(mutex_);
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT operation_id FROM core_operations WHERE status='WAITING_FOR_EFFECT' "
      "ORDER BY updated_at,operation_id";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  std::vector<uint64_t> operation_ids;
  int status = SQLITE_OK;
  while ((status = sqlite3_step(statement)) == SQLITE_ROW) {
    operation_ids.push_back(static_cast<uint64_t>(sqlite3_column_int64(statement, 0)));
  }
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  result = "{\"contractVersion\":\"foundation-v2\",\"operations\":[";
  for (size_t index = 0; index < operation_ids.size(); ++index) {
    std::string step;
    const mineg_error_code_t code = read_operation_step_locked(operation_ids[index], step);
    if (code != MINEG_OK) return code;
    if (index > 0) result += ',';
    result += step;
  }
  result += "]}";
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
  const bool auto_backup = extract_json_boolean(command, "autoBackupEnabled", false);
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
  sqlite3_stmt *tasks = nullptr;
  const char *tasks_sql = auto_backup
      ? "UPDATE backup_tasks SET state=COALESCE(resume_state,'DISCOVERED'),resume_state=NULL,"
        "lease_token=NULL,lease_expires_at=NULL,updated_at=? WHERE user_id=? AND device_installation_id=? AND state='PAUSED_BY_SETTING'"
      : "UPDATE backup_tasks SET resume_state=CASE WHEN state='PAUSED_BY_SETTING' THEN resume_state ELSE state END,"
        "state='PAUSED_BY_SETTING',lease_token=NULL,lease_expires_at=NULL,updated_at=? WHERE user_id=? AND device_installation_id=? "
        "AND requested_manually=0 AND state NOT IN ('COMPLETED','PERMANENT_FAILED','PAUSED_BY_SETTING') "
        "AND (lease_token IS NULL OR lease_expires_at<=strftime('%Y-%m-%dT%H:%M:%fZ','now'))";
  if (sqlite3_prepare_v2(database_, tasks_sql, -1, &tasks, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  status = sqlite3_bind_text(tasks, 1, updated_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(tasks, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(tasks, 3, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(tasks);
  sqlite3_finalize(tasks);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage02-v2\",\"type\":"
              "\"BackupSettingsChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(user_id) +
              "\",\"deviceInstallationId\":\"" + json_escape(device_id) +
              "\",\"autoBackupEnabled\":" +
              (auto_backup ? "true" : "false") + ",\"allowCellularBackup\":" +
              (cellular ? "true" : "false") + "}");
  if (auto_backup) {
    ++event_sequence_;
    emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupScheduleRequested\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(user_id) +
                "\",\"deviceInstallationId\":\"" + json_escape(device_id) + "\"}");
  }
  return MINEG_OK;
}

mineg_error_code_t Core::enqueue_backup_media_locked(const std::string &command,
                                                     std::string &result) {
  if (extract_json_string(command, "contractVersion") != "stage04-v1") {
    return MINEG_INVALID_ARGUMENT;
  }
  const std::string user_id = extract_json_string(command, "userId");
  const std::string device_id = extract_json_string(command, "deviceInstallationId");
  const std::string asset_ref = extract_json_string(command, "platformAssetRef");
  if (user_id.empty() || device_id.empty() || asset_ref.empty()) return MINEG_INVALID_ARGUMENT;
  const std::string account = read_account_state_locked();
  if (sqlite_json_text(database_, account, "$.state.userId") != user_id ||
      sqlite_json_text(database_, account, "$.state.approvalStatus") != "APPROVED") {
    return MINEG_INVALID_ARGUMENT;
  }
  sqlite3_stmt *media = nullptr;
  const char *media_sql =
      "SELECT media.content_version,media.media_type,media.mime_type,media.captured_at,media.availability "
      "FROM local_media media JOIN local_library_active active ON active.user_id=media.user_id "
      "AND active.generation_id=media.generation_id WHERE media.user_id=? AND media.platform_asset_ref=?";
  if (sqlite3_prepare_v2(database_, media_sql, -1, &media, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_text(media, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(media, 2, asset_ref.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(media);
  const auto media_text = [media](int column) {
    const auto *value = sqlite3_column_text(media, column);
    return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
  };
  const std::string content_version = status == SQLITE_ROW ? media_text(0) : std::string{};
  const std::string media_type = status == SQLITE_ROW ? media_text(1) : std::string{};
  const std::string mime_type = status == SQLITE_ROW ? media_text(2) : std::string{};
  const std::string captured_at = status == SQLITE_ROW ? media_text(3) : std::string{};
  const std::string availability = status == SQLITE_ROW ? media_text(4) : std::string{};
  sqlite3_finalize(media);
  if (status != SQLITE_ROW || content_version.empty() || media_type.empty() || mime_type.empty() ||
      captured_at.empty() || availability == "LOCAL_MISSING") {
    return MINEG_NOT_FOUND;
  }
  const std::string task_id = random_uuid();
  const std::string client_media_id = random_uuid();
  const std::string idempotency_key = "backup:" + random_uuid();
  const std::string initial_state = availability == "AVAILABLE" ? "DISCOVERED" : "WAITING_RESOURCE";
  sqlite3_stmt *upsert = nullptr;
  const char *upsert_sql = R"SQL(
    INSERT INTO backup_tasks(task_id,user_id,device_installation_id,platform_asset_ref,content_version,
      client_media_id,idempotency_key,media_type,mime_type,captured_at,state,resume_state,
      requested_manually,created_at,updated_at)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL,1,strftime('%Y-%m-%dT%H:%M:%fZ','now'),
      strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    ON CONFLICT(user_id,device_installation_id,platform_asset_ref,content_version) DO UPDATE SET
      requested_manually=1,
      state=CASE WHEN backup_tasks.state IN ('PERMANENT_FAILED','PAUSED_BY_SETTING','WAITING_RESOURCE')
        THEN excluded.state ELSE backup_tasks.state END,
      resume_state=CASE WHEN backup_tasks.state IN ('PERMANENT_FAILED','PAUSED_BY_SETTING','WAITING_RESOURCE')
        THEN NULL ELSE backup_tasks.resume_state END,
      next_retry_at=CASE WHEN backup_tasks.state IN ('PERMANENT_FAILED','PAUSED_BY_SETTING','WAITING_RESOURCE')
        THEN NULL ELSE backup_tasks.next_retry_at END,
      failure_code=CASE WHEN backup_tasks.state IN ('PERMANENT_FAILED','PAUSED_BY_SETTING','WAITING_RESOURCE')
        THEN NULL ELSE backup_tasks.failure_code END,
      failure_scope=CASE WHEN backup_tasks.state IN ('PERMANENT_FAILED','PAUSED_BY_SETTING','WAITING_RESOURCE')
        THEN NULL ELSE backup_tasks.failure_scope END,
      lease_token=CASE WHEN backup_tasks.state IN ('PERMANENT_FAILED','PAUSED_BY_SETTING','WAITING_RESOURCE')
        THEN NULL ELSE backup_tasks.lease_token END,
      lease_expires_at=CASE WHEN backup_tasks.state IN ('PERMANENT_FAILED','PAUSED_BY_SETTING','WAITING_RESOURCE')
        THEN NULL ELSE backup_tasks.lease_expires_at END,
      updated_at=excluded.updated_at
  )SQL";
  if (sqlite3_prepare_v2(database_, upsert_sql, -1, &upsert, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  status = sqlite3_bind_text(upsert, 1, task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 3, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 4, asset_ref.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 5, content_version.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 6, client_media_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 7, idempotency_key.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 8, media_type.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 9, mime_type.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 10, captured_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(upsert, 11, initial_state.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(upsert);
  sqlite3_finalize(upsert);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  sqlite3_stmt *read = nullptr;
  if (sqlite3_prepare_v2(database_, "SELECT task_id FROM backup_tasks WHERE user_id=? AND "
                         "device_installation_id=? AND platform_asset_ref=? AND content_version=?", -1,
                         &read, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  status = sqlite3_bind_text(read, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(read, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(read, 3, asset_ref.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(read, 4, content_version.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(read);
  const auto *stored_id = status == SQLITE_ROW ? sqlite3_column_text(read, 0) : nullptr;
  const std::string stored_task_id = stored_id == nullptr ? std::string{} :
      std::string(reinterpret_cast<const char *>(stored_id));
  sqlite3_finalize(read);
  if (stored_task_id.empty()) return MINEG_DATABASE_ERROR;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupQueueChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(user_id) +
              "\",\"deviceInstallationId\":\"" + json_escape(device_id) + "\",\"taskId\":\"" +
              json_escape(stored_task_id) + "\",\"state\":\"" + initial_state + "\"}");
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupScheduleRequested\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(user_id) +
              "\",\"deviceInstallationId\":\"" + json_escape(device_id) + "\"}");
  result = "{\"contractVersion\":\"stage04-v1\",\"status\":\"SUCCESS\",\"taskId\":\"" +
      json_escape(stored_task_id) + "\"}";
  return MINEG_OK;
}

mineg_error_code_t Core::retry_backup_queue_locked(const std::string &command) {
  if (extract_json_string(command, "contractVersion") != "stage04-v1") {
    return MINEG_INVALID_ARGUMENT;
  }
  const std::string user_id = extract_json_string(command, "userId");
  const std::string device_id = extract_json_string(command, "deviceInstallationId");
  if (user_id.empty() || device_id.empty()) return MINEG_INVALID_ARGUMENT;
  const std::string account = read_account_state_locked();
  const std::string active_user = sqlite_json_text(database_, account, "$.state.userId");
  if (!active_user.empty() && active_user != user_id) return MINEG_INVALID_ARGUMENT;
  const char *sql =
      "UPDATE backup_tasks SET state=CASE state "
      "WHEN 'PERMANENT_FAILED' THEN 'PREPARING' ELSE COALESCE(resume_state,'PREPARING') END,"
      "retry_count=0,next_retry_at=NULL,failure_code=NULL,failure_scope=NULL,"
      "updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') "
      "WHERE user_id=? AND device_installation_id=? "
      "AND state IN ('RETRYABLE_FAILED','PERMANENT_FAILED')";
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  const int changes = status == SQLITE_DONE ? sqlite3_changes(database_) : 0;
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupQueueChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(user_id) +
              "\",\"deviceInstallationId\":\"" + json_escape(device_id) +
              "\",\"retriedCount\":" + std::to_string(changes) + "}");
  return MINEG_OK;
}

mineg_error_code_t Core::notify_library_changed_locked(const std::string &command) {
  if (extract_json_string(command, "contractVersion") != "stage04-v1") {
    return MINEG_INVALID_ARGUMENT;
  }
  const std::string user_id = extract_json_string(command, "userId");
  const std::string device_id = extract_json_string(command, "deviceInstallationId");
  if (user_id.empty() || device_id.empty()) return MINEG_INVALID_ARGUMENT;
  const std::string account = read_account_state_locked();
  const std::string active_user = sqlite_json_text(database_, account, "$.state.userId");
  if (!active_user.empty() && active_user != user_id) return MINEG_INVALID_ARGUMENT;
  const char *sql =
      "INSERT INTO backup_scan_state(user_id,device_installation_id,mode,state,generation_id,"
      "reconcile_requested,discovered_count,updated_at) VALUES(?,?,'INCREMENTAL','IDLE','',1,0,"
      "strftime('%Y-%m-%dT%H:%M:%fZ','now')) ON CONFLICT(user_id,device_installation_id) DO UPDATE SET "
      // MediaStore does not expose a portable deletion cursor.  A change notification therefore
      // requires a fresh generation/diff instead of pretending DATE_MODIFIED can observe deletes.
      "mode='FULL_RECONCILE',completed_at=NULL,reconcile_requested=1,updated_at=excluded.updated_at";
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupScheduleRequested\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(user_id) +
              "\",\"deviceInstallationId\":\"" + json_escape(device_id) + "\"}");
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

bool Core::prepare_local_scan_locked(const std::string &user_id,
                                     const std::string &generation_id,
                                     bool clone_active_generation) {
  if (user_id.empty() || generation_id.empty() ||
      sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return false;
  }
  const auto rollback = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  };
  sqlite3_stmt *statement = nullptr;
  const char *statements[] = {
      "DELETE FROM local_media_albums WHERE user_id=? AND generation_id NOT IN "
      "(SELECT generation_id FROM local_library_active WHERE user_id=?)",
      "DELETE FROM local_media WHERE user_id=? AND generation_id NOT IN "
      "(SELECT generation_id FROM local_library_active WHERE user_id=?)",
      "DELETE FROM local_albums WHERE user_id=? AND generation_id NOT IN "
      "(SELECT generation_id FROM local_library_active WHERE user_id=?)",
  };
  for (const char *sql : statements) {
    if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return rollback();
    int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) {
      status = sqlite3_bind_text(statement, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
    }
    if (status == SQLITE_OK) status = sqlite3_step(statement);
    sqlite3_finalize(statement);
    statement = nullptr;
    if (status != SQLITE_DONE) return rollback();
  }
  if (clone_active_generation) {
    const char *clone_statements[] = {
        "INSERT INTO local_albums(user_id,generation_id,platform_album_ref,name) "
        "SELECT album.user_id,?,album.platform_album_ref,album.name FROM local_albums album "
        "JOIN local_library_active active ON active.user_id=album.user_id "
        "AND active.generation_id=album.generation_id WHERE album.user_id=?",
        "INSERT INTO local_media(user_id,generation_id,platform_asset_ref,media_type,mime_type,width,"
        "height,duration_ms,captured_at,modified_at,modified_version,content_version,availability,thumbnail_uri) "
        "SELECT media.user_id,?,media.platform_asset_ref,media.media_type,media.mime_type,media.width,"
        "media.height,media.duration_ms,media.captured_at,media.modified_at,media.modified_version,"
        "media.content_version,media.availability,media.thumbnail_uri FROM local_media media "
        "JOIN local_library_active active ON active.user_id=media.user_id "
        "AND active.generation_id=media.generation_id WHERE media.user_id=?",
        "INSERT INTO local_media_albums(user_id,generation_id,platform_asset_ref,platform_album_ref) "
        "SELECT relation.user_id,?,relation.platform_asset_ref,relation.platform_album_ref "
        "FROM local_media_albums relation JOIN local_library_active active "
        "ON active.user_id=relation.user_id AND active.generation_id=relation.generation_id "
        "WHERE relation.user_id=?",
    };
    for (const char *sql : clone_statements) {
      if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return rollback();
      int status = sqlite3_bind_text(statement, 1, generation_id.c_str(), -1, SQLITE_TRANSIENT);
      if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
      if (status == SQLITE_OK) status = sqlite3_step(statement);
      sqlite3_finalize(statement);
      statement = nullptr;
      if (status != SQLITE_DONE) return rollback();
    }
  }
  if (sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) return rollback();
  return true;
}

bool Core::write_local_scan_albums_locked(const std::string &user_id,
                                          const std::string &generation_id,
                                          const std::string &effect_result) {
  const std::string envelope = "{\"userId\":\"" + json_escape(user_id) +
      "\",\"generationId\":\"" + json_escape(generation_id) + "\",\"effect\":" +
      effect_result + "}";
  sqlite3_stmt *validation = nullptr;
  const char *validation_sql =
      "SELECT coalesce(json_array_length(?1,'$.effect.payload.items'),-1),"
      "coalesce((SELECT count(*) FROM json_each(?1,'$.effect.payload.items') item WHERE "
      "length(coalesce(json_extract(item.value,'$.platformAlbumRef'),''))=0 OR "
      "length(coalesce(json_extract(item.value,'$.name'),''))=0),-1)";
  if (sqlite3_prepare_v2(database_, validation_sql, -1, &validation, nullptr) != SQLITE_OK) {
    return false;
  }
  int status = sqlite3_bind_text(validation, 1, envelope.c_str(),
                                 static_cast<int>(envelope.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(validation);
  const bool valid = status == SQLITE_ROW && sqlite3_column_int64(validation, 0) >= 0 &&
      sqlite3_column_int64(validation, 0) <= 10000 && sqlite3_column_int64(validation, 1) == 0;
  sqlite3_finalize(validation);
  if (!valid || sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return false;
  }
  const bool inserted = execute_json_statement_locked(
      "INSERT INTO local_albums(user_id,generation_id,platform_album_ref,name) "
      "SELECT json_extract(?1,'$.userId'),json_extract(?1,'$.generationId'),"
      "json_extract(item.value,'$.platformAlbumRef'),json_extract(item.value,'$.name') "
      "FROM json_each(?1,'$.effect.payload.items') item WHERE true "
      "ON CONFLICT(user_id,generation_id,platform_album_ref) "
      "DO UPDATE SET name=excluded.name",
      envelope);
  if (!inserted || sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  }
  return true;
}

bool Core::write_local_scan_page_locked(const std::string &user_id,
                                        const std::string &generation_id,
                                        const std::string &effect_result,
                                        int64_t &item_count) {
  const std::string envelope = "{\"userId\":\"" + json_escape(user_id) +
      "\",\"generationId\":\"" + json_escape(generation_id) + "\",\"effect\":" +
      effect_result + "}";
  sqlite3_stmt *validation = nullptr;
  const char *validation_sql =
      "SELECT coalesce(json_array_length(?1,'$.effect.payload.items'),-1),"
      "coalesce((SELECT count(*) FROM json_each(?1,'$.effect.payload.items') item WHERE "
      "length(coalesce(json_extract(item.value,'$.platformAssetRef'),''))=0 OR "
      "json_extract(item.value,'$.mediaType') NOT IN ('PHOTO','VIDEO','GIF','LIVE_PHOTO','DYNAMIC') OR "
      "length(coalesce(json_extract(item.value,'$.mimeType'),''))=0 OR "
      "coalesce(json_extract(item.value,'$.width'),-1)<0 OR "
      "coalesce(json_extract(item.value,'$.height'),-1)<0 OR "
      "length(coalesce(json_extract(item.value,'$.capturedAt'),''))=0 OR "
      "length(coalesce(json_extract(item.value,'$.modifiedAt'),''))=0 OR "
      "length(coalesce(json_extract(item.value,'$.contentVersion'),''))=0 OR "
      "json_extract(item.value,'$.availability') NOT IN "
      "('AVAILABLE','WAITING_LOCAL_RESOURCE','LOCAL_MISSING') OR "
      "(coalesce(json_type(item.value,'$.platformAlbumRefs'),'')<>'array' AND "
      "length(coalesce(json_extract(item.value,'$.platformAlbumRef'),''))=0)), -1)";
  if (sqlite3_prepare_v2(database_, validation_sql, -1, &validation, nullptr) != SQLITE_OK) {
    return false;
  }
  int status = sqlite3_bind_text(validation, 1, envelope.c_str(),
                                 static_cast<int>(envelope.size()), SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(validation);
  item_count = status == SQLITE_ROW ? sqlite3_column_int64(validation, 0) : -1;
  const bool valid = status == SQLITE_ROW && item_count >= 0 && item_count <= 500 &&
      sqlite3_column_int64(validation, 1) == 0;
  sqlite3_finalize(validation);
  if (!valid || sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return false;
  }
  const auto rollback = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  };
  if (!execute_json_statement_locked(
          "INSERT INTO local_media(user_id,generation_id,platform_asset_ref,media_type,mime_type,"
          "width,height,duration_ms,captured_at,modified_at,modified_version,content_version,"
          "availability,thumbnail_uri) SELECT json_extract(?1,'$.userId'),"
          "json_extract(?1,'$.generationId'),json_extract(item.value,'$.platformAssetRef'),"
          "json_extract(item.value,'$.mediaType'),json_extract(item.value,'$.mimeType'),"
          "json_extract(item.value,'$.width'),json_extract(item.value,'$.height'),"
          "json_extract(item.value,'$.durationMs'),json_extract(item.value,'$.capturedAt'),"
          "json_extract(item.value,'$.modifiedAt'),json_extract(item.value,'$.modifiedVersion'),"
          "json_extract(item.value,'$.contentVersion'),json_extract(item.value,'$.availability'),"
          "json_extract(item.value,'$.thumbnailUri') FROM json_each(?1,'$.effect.payload.items') item WHERE true "
          "ON CONFLICT(user_id,generation_id,platform_asset_ref) DO UPDATE SET "
          "media_type=excluded.media_type,mime_type=excluded.mime_type,width=excluded.width,height=excluded.height,"
          "duration_ms=excluded.duration_ms,captured_at=excluded.captured_at,modified_at=excluded.modified_at,"
          "modified_version=excluded.modified_version,content_version=excluded.content_version,"
          "availability=excluded.availability,thumbnail_uri=excluded.thumbnail_uri",
          envelope)) return rollback();
  if (!execute_json_statement_locked(
          "DELETE FROM local_media_albums WHERE user_id=json_extract(?1,'$.userId') "
          "AND generation_id=json_extract(?1,'$.generationId') AND platform_asset_ref IN "
          "(SELECT json_extract(item.value,'$.platformAssetRef') FROM json_each(?1,'$.effect.payload.items') item)",
          envelope)) return rollback();
  if (!execute_json_statement_locked(
          "INSERT INTO local_media_albums(user_id,generation_id,platform_asset_ref,platform_album_ref) "
          "SELECT json_extract(?1,'$.userId'),json_extract(?1,'$.generationId'),"
          "json_extract(item.value,'$.platformAssetRef'),album.value "
          "FROM json_each(?1,'$.effect.payload.items') item JOIN json_each(CASE "
          "WHEN json_type(item.value,'$.platformAlbumRefs')='array' "
          "THEN json_extract(item.value,'$.platformAlbumRefs') "
          "ELSE json_array(json_extract(item.value,'$.platformAlbumRef')) END) album",
          envelope)) return rollback();
  if (sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) return rollback();
  return true;
}

bool Core::finalize_local_scan_locked(const std::string &user_id,
                                      const std::string &generation_id,
                                      int64_t indexed_count,
                                      const std::string &completed_at) {
  if (user_id.empty() || generation_id.empty() || indexed_count < 0 || completed_at.empty() ||
      sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return false;
  }
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "INSERT INTO local_library_active(user_id,generation_id,indexed_count,completed_at) "
      "VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET generation_id=excluded.generation_id,"
      "indexed_count=excluded.indexed_count,completed_at=excluded.completed_at";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  }
  int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, generation_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int64(statement, 3, indexed_count);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, completed_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE || sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  }
  return true;
}

bool Core::begin_backup_scan_locked(const std::string &user_id, const std::string &device_id,
                                    std::string &generation_id, bool &incremental,
                                    std::string &cursor_json, int64_t &indexed_count,
                                    bool &resuming) {
  if (user_id.empty() || device_id.empty() || generation_id.empty()) return false;
  incremental = false;
  cursor_json.clear();
  indexed_count = 0;
  resuming = false;
  sqlite3_stmt *previous = nullptr;
  if (sqlite3_prepare_v2(database_, "SELECT mode,state,generation_id,cursor_json,discovered_count,"
                         "reconcile_requested,completed_at "
                         "FROM backup_scan_state WHERE user_id=? AND device_installation_id=?", -1,
                         &previous, nullptr) != SQLITE_OK) return false;
  int previous_status = sqlite3_bind_text(previous, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (previous_status == SQLITE_OK) previous_status = sqlite3_bind_text(previous, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (previous_status == SQLITE_OK) previous_status = sqlite3_step(previous);
  const auto previous_text = [previous](int column) {
    const auto *value = sqlite3_column_text(previous, column);
    return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
  };
  const std::string previous_mode = previous_status == SQLITE_ROW ? previous_text(0) : std::string{};
  const std::string previous_state = previous_status == SQLITE_ROW ? previous_text(1) : std::string{};
  const std::string previous_generation = previous_status == SQLITE_ROW ? previous_text(2) : std::string{};
  const std::string previous_cursor = previous_status == SQLITE_ROW ? previous_text(3) : std::string{};
  const int64_t previous_indexed = previous_status == SQLITE_ROW ? sqlite3_column_int64(previous, 4) : 0;
  const bool previous_reconcile_requested = previous_status == SQLITE_ROW && sqlite3_column_int(previous, 5) == 1;
  const std::string previous_completed = previous_status == SQLITE_ROW ? previous_text(6) : std::string{};
  sqlite3_finalize(previous);
  if (previous_status != SQLITE_ROW && previous_status != SQLITE_DONE) return false;
  if (previous_state == "SCANNING" && !previous_generation.empty()) {
    generation_id = previous_generation;
    incremental = previous_mode == "INCREMENTAL";
    cursor_json = previous_cursor;
    indexed_count = std::max<int64_t>(0, previous_indexed);
    resuming = true;
    return true;
  }
  sqlite3_stmt *active = nullptr;
  if (sqlite3_prepare_v2(database_, "SELECT 1 FROM local_library_active WHERE user_id=?", -1,
                         &active, nullptr) != SQLITE_OK) return false;
  int active_status = sqlite3_bind_text(active, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (active_status == SQLITE_OK) active_status = sqlite3_step(active);
  const bool has_active_library = active_status == SQLITE_ROW;
  sqlite3_finalize(active);
  const std::string full_reconcile_before = rfc3339_at(
      std::chrono::system_clock::now() - std::chrono::hours(24 * 7));
  const bool force_full_reconcile = previous_mode == "FULL_RECONCILE" || previous_reconcile_requested;
  incremental = !force_full_reconcile && has_active_library && !previous_cursor.empty() &&
      previous_completed >= full_reconcile_before;
  cursor_json = incremental ? previous_cursor : std::string{};
  const std::string mode = incremental ? "INCREMENTAL" :
      (force_full_reconcile ? "FULL_RECONCILE" : "HISTORICAL");
  const char *sql =
      "INSERT INTO backup_scan_state(user_id,device_installation_id,mode,state,generation_id,"
      "cursor_json,reconcile_requested,discovered_count,started_at,completed_at,updated_at) "
      "VALUES(?,?,?,'SCANNING',?,?,0,0,strftime('%Y-%m-%dT%H:%M:%fZ','now'),NULL,"
      "strftime('%Y-%m-%dT%H:%M:%fZ','now')) "
      "ON CONFLICT(user_id,device_installation_id) DO UPDATE SET mode=excluded.mode,"
      "state='SCANNING',generation_id=excluded.generation_id,cursor_json=excluded.cursor_json,reconcile_requested=0,"
      "discovered_count=0,started_at=excluded.started_at,completed_at=NULL,updated_at=excluded.updated_at";
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, mode.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, generation_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK && incremental) status = sqlite3_bind_text(statement, 5, cursor_json.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK && !incremental) status = sqlite3_bind_null(statement, 5);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  return status == SQLITE_DONE;
}

bool Core::persist_backup_scan_progress_locked(const std::string &user_id, const std::string &device_id,
                                               const std::string &generation_id, int64_t indexed_count,
                                               const std::string &cursor_json) {
  if (user_id.empty() || device_id.empty() || generation_id.empty() || indexed_count < 0) return false;
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "UPDATE backup_scan_state SET cursor_json=?,discovered_count=?,updated_at="
      "strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE user_id=? AND device_installation_id=? "
      "AND generation_id=? AND state='SCANNING'";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  int status = cursor_json.empty() ? sqlite3_bind_null(statement, 1) :
      sqlite3_bind_text(statement, 1, cursor_json.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int64(statement, 2, indexed_count);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 5, generation_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  return status == SQLITE_DONE && sqlite3_changes(database_) == 1;
}

int64_t Core::discover_backup_tasks_locked(const std::string &user_id, const std::string &device_id) {
  if (user_id.empty() || device_id.empty() ||
      sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return -1;
  }
  const auto rollback = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return static_cast<int64_t>(-1);
  };
  sqlite3_stmt *availability = nullptr;
  const char *availability_sql = R"SQL(
    UPDATE backup_tasks AS task
    SET state=CASE media.availability
        WHEN 'AVAILABLE' THEN CASE WHEN task.state='WAITING_RESOURCE' THEN 'DISCOVERED' ELSE task.state END
        ELSE CASE WHEN task.state<>'COMPLETED' THEN 'WAITING_RESOURCE' ELSE task.state END
      END,
      updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
    FROM local_media AS media JOIN local_library_active AS active
      ON active.user_id=media.user_id AND active.generation_id=media.generation_id
    WHERE task.user_id=? AND task.device_installation_id=?
      AND media.user_id=task.user_id
      AND media.platform_asset_ref=task.platform_asset_ref
      AND media.content_version=task.content_version
      AND task.state<>'COMPLETED'
  )SQL";
  if (sqlite3_prepare_v2(database_, availability_sql, -1, &availability, nullptr) != SQLITE_OK) return rollback();
  int availability_status = sqlite3_bind_text(availability, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (availability_status == SQLITE_OK) availability_status = sqlite3_bind_text(availability, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (availability_status == SQLITE_OK) availability_status = sqlite3_step(availability);
  sqlite3_finalize(availability);
  if (availability_status != SQLITE_DONE) return rollback();
  sqlite3_stmt *missing = nullptr;
  const char *missing_sql = R"SQL(
    UPDATE backup_tasks AS task
    SET state='WAITING_RESOURCE',resume_state=CASE WHEN task.state='PAUSED_BY_SETTING'
        THEN task.resume_state ELSE task.state END,
        failure_code='BACKUP_LOCAL_RESOURCE_UNAVAILABLE',failure_scope='LOCAL',
        lease_token=NULL,lease_expires_at=NULL,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
    WHERE task.user_id=? AND task.device_installation_id=? AND task.state<>'COMPLETED'
      AND NOT EXISTS (
        SELECT 1 FROM local_media media JOIN local_library_active active
          ON active.user_id=media.user_id AND active.generation_id=media.generation_id
        WHERE media.user_id=task.user_id AND media.platform_asset_ref=task.platform_asset_ref
          AND media.content_version=task.content_version)
  )SQL";
  if (sqlite3_prepare_v2(database_, missing_sql, -1, &missing, nullptr) != SQLITE_OK) return rollback();
  int missing_status = sqlite3_bind_text(missing, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (missing_status == SQLITE_OK) missing_status = sqlite3_bind_text(missing, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (missing_status == SQLITE_OK) missing_status = sqlite3_step(missing);
  sqlite3_finalize(missing);
  if (missing_status != SQLITE_DONE) return rollback();
  sqlite3_stmt *source = nullptr;
  const char *source_sql =
      "SELECT media.platform_asset_ref,media.content_version,media.media_type,media.mime_type,"
      "media.captured_at,media.availability FROM local_media media JOIN local_library_active active "
      "ON active.user_id=media.user_id AND active.generation_id=media.generation_id "
      "WHERE media.user_id=? AND media.availability<>'LOCAL_MISSING' "
      "AND NOT EXISTS (SELECT 1 FROM download_receipts receipt "
      "WHERE receipt.user_id=media.user_id AND receipt.platform_asset_ref=media.platform_asset_ref) "
      "ORDER BY media.captured_at DESC,media.platform_asset_ref DESC";
  if (sqlite3_prepare_v2(database_, source_sql, -1, &source, nullptr) != SQLITE_OK) return rollback();
  int status = sqlite3_bind_text(source, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status != SQLITE_OK) {
    sqlite3_finalize(source);
    return rollback();
  }
  sqlite3_stmt *insert = nullptr;
  const char *insert_sql =
      "INSERT OR IGNORE INTO backup_tasks(task_id,user_id,device_installation_id,platform_asset_ref,"
      "content_version,client_media_id,idempotency_key,media_type,mime_type,captured_at,state,"
      "resume_state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL,"
      "strftime('%Y-%m-%dT%H:%M:%fZ','now'),strftime('%Y-%m-%dT%H:%M:%fZ','now'))";
  if (sqlite3_prepare_v2(database_, insert_sql, -1, &insert, nullptr) != SQLITE_OK) {
    sqlite3_finalize(source);
    return rollback();
  }
  int64_t inserted_count = 0;
  while ((status = sqlite3_step(source)) == SQLITE_ROW) {
    const auto text_at = [source](int column) {
      const auto *value = sqlite3_column_text(source, column);
      return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
    };
    const std::string asset_ref = text_at(0);
    const std::string content_version = text_at(1);
    const std::string media_type = text_at(2);
    const std::string mime_type = text_at(3);
    const std::string captured_at = text_at(4);
    const std::string state = text_at(5) == "AVAILABLE" ? "DISCOVERED" : "WAITING_RESOURCE";
    const std::string task_id = random_uuid();
    const std::string client_media_id = random_uuid();
    const std::string idempotency_key = "backup:" + random_uuid();
    sqlite3_finalize(insert);
    insert = nullptr;
    if (sqlite3_prepare_v2(database_, insert_sql, -1, &insert, nullptr) != SQLITE_OK) {
      sqlite3_finalize(source);
      return rollback();
    }
    int insert_status = sqlite3_bind_text(insert, 1, task_id.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 3, device_id.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 4, asset_ref.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 5, content_version.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 6, client_media_id.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 7, idempotency_key.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 8, media_type.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 9, mime_type.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 10, captured_at.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_bind_text(insert, 11, state.c_str(), -1, SQLITE_TRANSIENT);
    if (insert_status == SQLITE_OK) insert_status = sqlite3_step(insert);
    if (insert_status != SQLITE_DONE) {
      sqlite3_finalize(insert);
      sqlite3_finalize(source);
      return rollback();
    }
    inserted_count += sqlite3_changes(database_);
  }
  sqlite3_finalize(insert);
  sqlite3_finalize(source);
  if (status != SQLITE_DONE || sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return rollback();
  }
  return inserted_count;
}

bool Core::claim_next_backup_task_locked(AccountOperation &operation) {
  operation.backup_no_work = false;
  sqlite3_stmt *enabled = nullptr;
  if (sqlite3_prepare_v2(
          database_,
          "SELECT auto_backup_enabled FROM backup_settings WHERE user_id=? AND device_installation_id=?",
          -1, &enabled, nullptr) != SQLITE_OK) {
    return false;
  }
  int status = sqlite3_bind_text(enabled, 1, operation.user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(enabled, 2, operation.device_installation_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(enabled);
  const bool auto_backup_enabled = status == SQLITE_ROW && sqlite3_column_int(enabled, 0) == 1;
  sqlite3_finalize(enabled);
  if (status != SQLITE_ROW && status != SQLITE_DONE) return false;
  const auto active_task_count = [this, &operation]() {
    sqlite3_stmt *active = nullptr;
    const char *active_sql = R"SQL(
      SELECT count(*) FROM backup_tasks
      WHERE user_id=? AND device_installation_id=?
        AND state IN ('PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING')
        AND lease_expires_at IS NOT NULL AND lease_expires_at>?
    )SQL";
    if (sqlite3_prepare_v2(database_, active_sql, -1, &active, nullptr) != SQLITE_OK) return -1;
    const std::string now = now_rfc3339();
    int active_status = sqlite3_bind_text(active, 1, operation.user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (active_status == SQLITE_OK) {
      active_status = sqlite3_bind_text(active, 2, operation.device_installation_id.c_str(), -1,
                                        SQLITE_TRANSIENT);
    }
    if (active_status == SQLITE_OK) {
      active_status = sqlite3_bind_text(active, 3, now.c_str(), -1, SQLITE_TRANSIENT);
    }
    if (active_status == SQLITE_OK) active_status = sqlite3_step(active);
    const int count = active_status == SQLITE_ROW ? sqlite3_column_int(active, 0) : -1;
    sqlite3_finalize(active);
    return count;
  };
  if (const int active = active_task_count(); active < 0) return false;
  else if (active >= 2) {
    operation.backup_no_work = true;
    return true;
  }
  sqlite3_stmt *statement = nullptr;
  const char *sql = R"SQL(
    SELECT task_id,platform_asset_ref,content_version,client_media_id,idempotency_key,
           media_type,mime_type,captured_at,
           COALESCE((SELECT resource_id FROM backup_resources resource
                     WHERE resource.task_id=backup_tasks.task_id
                       AND resource.resource_type='ORIGINAL'),''),
           COALESCE(client_albums_json,'[]'),COALESCE(server_upload_id,''),requested_manually
    FROM backup_tasks
    WHERE user_id=? AND device_installation_id=?
      AND (?=1 OR requested_manually=1)
      AND (state IN ('DISCOVERED','WAITING_NETWORK','RETRYABLE_FAILED') OR
           (state IN ('PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING') AND
            (lease_expires_at IS NULL OR lease_expires_at<=?)))
      AND (next_retry_at IS NULL OR next_retry_at<=?)
      AND (state<>'RETRYABLE_FAILED' OR retry_count<12)
    ORDER BY captured_at DESC,task_id DESC LIMIT 1
  )SQL";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  const std::string now = now_rfc3339();
  status = sqlite3_bind_text(statement, 1, operation.user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, operation.device_installation_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int(statement, 3, auto_backup_enabled ? 1 : 0);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 5, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  if (status == SQLITE_DONE) {
    sqlite3_finalize(statement);
    operation.backup_no_work = true;
    return true;
  }
  if (status != SQLITE_ROW) {
    sqlite3_finalize(statement);
    return false;
  }
  const auto text_at = [statement](int column) {
    const auto *value = sqlite3_column_text(statement, column);
    return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
  };
  operation.backup_task_id = text_at(0);
  operation.media_asset_ref = text_at(1);
  operation.media_content_version = text_at(2);
  operation.media_client_id = text_at(3);
  operation.idempotency_key = text_at(4);
  operation.media_type = text_at(5);
  operation.media_mime_type = text_at(6);
  operation.media_captured_at = text_at(7);
  operation.media_resource_id = text_at(8);
  operation.media_client_albums_json = text_at(9);
  operation.media_upload_id = text_at(10);
  const bool requested_manually = sqlite3_column_int(statement, 11) == 1;
  sqlite3_finalize(statement);
  if (operation.backup_task_id.empty() || operation.media_asset_ref.empty() ||
      operation.media_client_id.empty() || operation.idempotency_key.empty() ||
      operation.media_type.empty() || operation.media_mime_type.empty() ||
      operation.media_captured_at.empty()) {
    return false;
  }
  if (operation.media_resource_id.empty()) operation.media_resource_id = random_uuid();

  sqlite3_stmt *albums = nullptr;
  const char *albums_sql = R"SQL(
    SELECT relation.platform_album_ref,album.name
    FROM local_media_albums relation
    JOIN local_library_active active ON active.user_id=relation.user_id
      AND active.generation_id=relation.generation_id
    JOIN local_albums album ON album.user_id=relation.user_id
      AND album.generation_id=relation.generation_id
      AND album.platform_album_ref=relation.platform_album_ref
    WHERE relation.user_id=? AND relation.platform_asset_ref=?
    ORDER BY relation.platform_album_ref ASC LIMIT 200
  )SQL";
  if (sqlite3_prepare_v2(database_, albums_sql, -1, &albums, nullptr) != SQLITE_OK) return false;
  status = sqlite3_bind_text(albums, 1, operation.user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(albums, 2, operation.media_asset_ref.c_str(), -1, SQLITE_TRANSIENT);
  if (status != SQLITE_OK) {
    sqlite3_finalize(albums);
    return false;
  }
  std::string client_albums = "[";
  bool first_album = true;
  while ((status = sqlite3_step(albums)) == SQLITE_ROW) {
    const auto *album_id = sqlite3_column_text(albums, 0);
    const auto *album_name = sqlite3_column_text(albums, 1);
    if (album_id == nullptr || album_name == nullptr) {
      sqlite3_finalize(albums);
      return false;
    }
    if (!first_album) client_albums += ',';
    first_album = false;
    client_albums += "{\"client_album_id\":\"" + json_escape(reinterpret_cast<const char *>(album_id)) +
        "\",\"name\":\"" + json_escape(reinterpret_cast<const char *>(album_name)) + "\"}";
  }
  sqlite3_finalize(albums);
  if (status != SQLITE_DONE) return false;
  if (operation.media_upload_id.empty()) operation.media_client_albums_json = client_albums + "]";

  sqlite3_stmt *claim = nullptr;
  operation.backup_lease_token = random_identifier();
  const std::string lease_expires_at = rfc3339_after_seconds(15 * 60);
  const char *claim_sql =
      "UPDATE backup_tasks SET state='PREPARING',resume_state='PREPARING',failure_code=NULL,"
      "failure_scope=NULL,client_albums_json=CASE WHEN server_upload_id IS NULL THEN ? "
      "ELSE client_albums_json END,lease_token=?,lease_expires_at=?,updated_at=? WHERE task_id=? "
      "AND user_id=? AND device_installation_id=? AND (?=1 OR requested_manually=1) "
      "AND (state IN ('DISCOVERED','WAITING_NETWORK','RETRYABLE_FAILED') OR (state IN ('PREPARING','CREATING_SESSION',"
      "'UPLOADING','SERVER_VERIFYING') AND (lease_expires_at IS NULL OR lease_expires_at<=?)))"
      "AND (next_retry_at IS NULL OR next_retry_at<=?) "
      "AND (SELECT count(*) FROM backup_tasks active WHERE active.user_id=? "
      "AND active.device_installation_id=? AND active.state IN "
      "('PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING') "
      "AND active.lease_expires_at IS NOT NULL AND active.lease_expires_at>?) < 2";
  if (sqlite3_prepare_v2(database_, claim_sql, -1, &claim, nullptr) != SQLITE_OK) return false;
  status = sqlite3_bind_text(claim, 1, operation.media_client_albums_json.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 2, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 3, lease_expires_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 4, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 5, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 6, operation.user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 7, operation.device_installation_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int(claim, 8, auto_backup_enabled || requested_manually ? 1 : 0);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 9, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 10, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 11, operation.user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 12, operation.device_installation_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(claim, 13, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(claim);
  const bool claimed = status == SQLITE_DONE && sqlite3_changes(database_) == 1;
  sqlite3_finalize(claim);
  if (!claimed && active_task_count() >= 2) {
    operation.backup_no_work = true;
    return true;
  }
  return claimed;
}

bool Core::renew_backup_task_lease_locked(const AccountOperation &operation) {
  if (operation.backup_task_id.empty() || operation.backup_lease_token.empty()) return false;
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, "UPDATE backup_tasks SET lease_expires_at=?,updated_at=? "
                         "WHERE task_id=? AND lease_token=? AND state IN "
                         "('PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING')",
                         -1, &statement, nullptr) != SQLITE_OK) {
    return false;
  }
  const std::string now = now_rfc3339();
  const std::string lease_expires_at = rfc3339_after_seconds(15 * 60);
  int status = sqlite3_bind_text(statement, 1, lease_expires_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  return status == SQLITE_DONE && sqlite3_changes(database_) == 1;
}

bool Core::persist_backup_resource_manifest_locked(AccountOperation &operation) {
  if (operation.backup_task_id.empty() || operation.media_resource_id.empty() ||
      operation.media_source_size < 1 || operation.media_part_sizes.empty() ||
      operation.media_part_sizes.size() != operation.media_part_digests.size() ||
      operation.media_content_digest_base64.empty() ||
      sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return false;
  }
  const auto rollback = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return false;
  };
  const std::string now = now_rfc3339();
  sqlite3_stmt *resource = nullptr;
  const char *resource_sql =
      "INSERT INTO backup_resources(resource_id,task_id,resource_type,byte_length,sha256_base64,"
      "preparation_state,server_confirmed,created_at,updated_at) VALUES(?,?,'ORIGINAL',?,?,"
      "'READY',0,?,?) ON CONFLICT(task_id,resource_type) DO UPDATE SET "
      "byte_length=excluded.byte_length,sha256_base64=excluded.sha256_base64,"
      "preparation_state='READY',updated_at=excluded.updated_at";
  if (sqlite3_prepare_v2(database_, resource_sql, -1, &resource, nullptr) != SQLITE_OK) return rollback();
  int status = sqlite3_bind_text(resource, 1, operation.media_resource_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(resource, 2, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int64(resource, 3, operation.media_source_size);
  if (status == SQLITE_OK) status = sqlite3_bind_text(resource, 4, operation.media_content_digest_base64.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(resource, 5, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(resource, 6, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(resource);
  sqlite3_finalize(resource);
  if (status != SQLITE_DONE) return rollback();

  sqlite3_stmt *actual = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT resource_id FROM backup_resources WHERE task_id=? AND resource_type='ORIGINAL'",
                         -1, &actual, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(actual, 1, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(actual);
  if (status != SQLITE_ROW) {
    sqlite3_finalize(actual);
    return rollback();
  }
  const auto *resource_id = sqlite3_column_text(actual, 0);
  operation.media_resource_id = resource_id == nullptr ? std::string{} : reinterpret_cast<const char *>(resource_id);
  sqlite3_finalize(actual);
  if (operation.media_resource_id.empty()) return rollback();

  const char *part_sql =
      "INSERT OR IGNORE INTO backup_parts(resource_id,part_number,byte_offset,byte_length,sha256_base64,state) "
      "VALUES(?,?,?,?,?,'PENDING')";
  int64_t offset = 0;
  for (size_t index = 0; index < operation.media_part_sizes.size(); ++index) {
    sqlite3_stmt *part = nullptr;
    if (sqlite3_prepare_v2(database_, part_sql, -1, &part, nullptr) != SQLITE_OK) return rollback();
    status = sqlite3_bind_text(part, 1, operation.media_resource_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_int64(part, 2, static_cast<int64_t>(index + 1U));
    if (status == SQLITE_OK) status = sqlite3_bind_int64(part, 3, offset);
    if (status == SQLITE_OK) status = sqlite3_bind_int64(part, 4, operation.media_part_sizes[index]);
    if (status == SQLITE_OK) status = sqlite3_bind_text(part, 5, operation.media_part_digests[index].c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(part);
    if (status != SQLITE_DONE) {
      sqlite3_finalize(part);
      return rollback();
    }
    sqlite3_finalize(part);
    offset += operation.media_part_sizes[index];
  }
  sqlite3_stmt *task = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "UPDATE backup_tasks SET state='CREATING_SESSION',resume_state='CREATING_SESSION',"
                         "updated_at=? WHERE task_id=? AND lease_token=?", -1, &task, nullptr) != SQLITE_OK) return rollback();
  status = sqlite3_bind_text(task, 1, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(task, 2, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(task, 3, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(task);
  sqlite3_finalize(task);
  if (status != SQLITE_DONE || sqlite3_changes(database_) != 1 ||
      sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) return rollback();
  return true;
}

bool Core::reconcile_backup_confirmed_parts_locked(const AccountOperation &operation,
                                                   const std::string &session_json) {
  if (operation.backup_task_id.empty() || operation.backup_lease_token.empty() ||
      operation.media_resource_id.empty() || operation.media_part_sizes.empty() ||
      json_array_length(database_, session_json, "$.resources") != 1 ||
      sqlite_json_text(database_, session_json, "$.resources[0].resource_id") !=
          operation.media_resource_id) {
    return false;
  }
  const int64_t count = json_array_length(database_, session_json,
                                           "$.resources[0].confirmed_part_numbers");
  if (count < 0 || count > static_cast<int64_t>(operation.media_part_sizes.size())) return false;
  if (!renew_backup_task_lease_locked(operation)) return false;
  // An EXPIRED response is allowed to represent a freshly reset multipart session.  Drop
  // stale local confirmations first, then apply exactly the parts the server retained.
  if (sqlite_json_text(database_, session_json, "$.state") == "EXPIRED") {
    sqlite3_stmt *reset = nullptr;
    if (sqlite3_prepare_v2(database_, "UPDATE backup_parts SET state='PENDING',etag=NULL,confirmed_at=NULL "
                           "WHERE resource_id=? AND EXISTS (SELECT 1 FROM backup_resources resource "
                           "JOIN backup_tasks task ON task.task_id=resource.task_id "
                           "WHERE resource.resource_id=backup_parts.resource_id AND task.task_id=? "
                           "AND task.lease_token=?)", -1, &reset, nullptr) != SQLITE_OK) {
      return false;
    }
    int reset_status = sqlite3_bind_text(reset, 1, operation.media_resource_id.c_str(), -1, SQLITE_TRANSIENT);
    if (reset_status == SQLITE_OK) reset_status = sqlite3_bind_text(reset, 2, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
    if (reset_status == SQLITE_OK) reset_status = sqlite3_bind_text(reset, 3, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
    if (reset_status == SQLITE_OK) reset_status = sqlite3_step(reset);
    sqlite3_finalize(reset);
    if (reset_status != SQLITE_DONE) return false;
  }
  const std::string now = now_rfc3339();
  for (int64_t index = 0; index < count; ++index) {
    const int64_t part_number = sqlite_json_integer(
        database_, session_json, "$.resources[0].confirmed_part_numbers[" + std::to_string(index) + "]", -1);
    if (part_number < 1 || part_number > static_cast<int64_t>(operation.media_part_sizes.size())) return false;
    sqlite3_stmt *statement = nullptr;
    if (sqlite3_prepare_v2(database_, "UPDATE backup_parts SET state='CONFIRMED',"
                           "etag=COALESCE(etag,'server-confirmed'),confirmed_at=COALESCE(confirmed_at,?) "
                           "WHERE resource_id=? AND part_number=?", -1, &statement, nullptr) != SQLITE_OK) {
      return false;
    }
    int status = sqlite3_bind_text(statement, 1, now.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, operation.media_resource_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_int64(statement, 3, part_number);
    if (status == SQLITE_OK) status = sqlite3_step(statement);
    sqlite3_finalize(statement);
    if (status != SQLITE_DONE || sqlite3_changes(database_) != 1) return false;
  }
  return true;
}

bool Core::mark_backup_parts_transferred_locked(const AccountOperation &operation,
                                                const std::vector<int64_t> &part_indexes) {
  if (operation.backup_task_id.empty() || operation.backup_lease_token.empty() ||
      operation.media_resource_id.empty() || part_indexes.empty() ||
      part_indexes.size() > 2 || !renew_backup_task_lease_locked(operation)) {
    return false;
  }
  for (const int64_t index : part_indexes) {
    if (index < 0 || index >= static_cast<int64_t>(operation.media_part_sizes.size())) return false;
    sqlite3_stmt *statement = nullptr;
    if (sqlite3_prepare_v2(database_, "UPDATE backup_parts SET state=CASE WHEN state='CONFIRMED' "
                           "THEN state ELSE 'TRANSFERRED' END WHERE resource_id=? AND part_number=? "
                           "AND EXISTS (SELECT 1 FROM backup_resources resource JOIN backup_tasks task "
                           "ON task.task_id=resource.task_id WHERE resource.resource_id=backup_parts.resource_id "
                           "AND task.task_id=? AND task.lease_token=?)", -1, &statement, nullptr) != SQLITE_OK) {
      return false;
    }
    int status = sqlite3_bind_text(statement, 1, operation.media_resource_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_int64(statement, 2, index + 1);
    if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(statement);
    sqlite3_finalize(statement);
    if (status != SQLITE_DONE || sqlite3_changes(database_) != 1) return false;
  }
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupProgressChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(operation.user_id) +
              "\",\"deviceInstallationId\":\"" + json_escape(operation.device_installation_id) +
              "\",\"taskId\":\"" + json_escape(operation.backup_task_id) + "\"}");
  return true;
}

bool Core::confirm_backup_part_locked(const AccountOperation &operation, int64_t part_number,
                                      const std::string &etag) {
  if (operation.backup_task_id.empty() || operation.media_resource_id.empty() || part_number < 1 ||
      etag.empty() || !renew_backup_task_lease_locked(operation)) return false;
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "UPDATE backup_parts SET state='CONFIRMED',etag=?,confirmed_at=? "
                         "WHERE resource_id=? AND part_number=? AND EXISTS (SELECT 1 FROM backup_resources "
                         "resource JOIN backup_tasks task ON task.task_id=resource.task_id "
                         "WHERE resource.resource_id=backup_parts.resource_id AND task.task_id=? "
                         "AND task.lease_token=?)", -1, &statement, nullptr) != SQLITE_OK) {
    return false;
  }
  const std::string now = now_rfc3339();
  int status = sqlite3_bind_text(statement, 1, etag.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, operation.media_resource_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int64(statement, 4, part_number);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 5, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 6, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE || sqlite3_changes(database_) != 1) return false;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupProgressChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(operation.user_id) +
              "\",\"deviceInstallationId\":\"" + json_escape(operation.device_installation_id) +
              "\",\"taskId\":\"" + json_escape(operation.backup_task_id) + "\"}");
  return true;
}

bool Core::finish_backup_task_locked(const AccountOperation &operation) {
  if (operation.backup_task_id.empty() || operation.media_upload_id.empty()) return false;
  const std::string media_id = sqlite_json_text(database_, operation.media_pending_result, "$.mediaId");
  if (media_id.empty()) return false;
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "UPDATE backup_tasks SET state='COMPLETED',resume_state=NULL,server_upload_id=?,"
                         "server_media_id=?,retry_count=0,next_retry_at=NULL,failure_code=NULL,"
                         "failure_scope=NULL,lease_token=NULL,lease_expires_at=NULL,updated_at=? WHERE task_id=? AND lease_token=?",
                         -1, &statement, nullptr) != SQLITE_OK) return false;
  const std::string now = now_rfc3339();
  int status = sqlite3_bind_text(statement, 1, operation.media_upload_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, media_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, now.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 5, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE || sqlite3_changes(database_) != 1) return false;
  ++event_sequence_;
  emit_locked("{\"contractVersion\":\"stage04-v1\",\"type\":\"BackupQueueChanged\",\"sequence\":" +
              std::to_string(event_sequence_) + ",\"userId\":\"" + json_escape(operation.user_id) +
              "\",\"deviceInstallationId\":\"" + json_escape(operation.device_installation_id) +
              "\",\"taskId\":\"" + json_escape(operation.backup_task_id) + "\",\"state\":\"COMPLETED\"}");
  return true;
}

bool Core::backup_task_should_pause_locked(const AccountOperation &operation) {
  if (operation.backup_task_id.empty()) return false;
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "SELECT setting.auto_backup_enabled,task.requested_manually FROM backup_tasks task "
      "LEFT JOIN backup_settings setting ON setting.user_id=task.user_id "
      "AND setting.device_installation_id=task.device_installation_id WHERE task.task_id=?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(statement, 1, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  const bool should_pause = status == SQLITE_ROW && sqlite3_column_int(statement, 0) == 0 &&
      sqlite3_column_int(statement, 1) == 0;
  sqlite3_finalize(statement);
  return should_pause;
}

bool Core::pause_backup_task_locked(const AccountOperation &operation) {
  if (operation.backup_task_id.empty() || operation.backup_lease_token.empty()) return false;
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "UPDATE backup_tasks SET resume_state=state,state='PAUSED_BY_SETTING',lease_token=NULL,"
      "lease_expires_at=NULL,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') "
      "WHERE task_id=? AND lease_token=? AND requested_manually=0 AND state IN "
      "('PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING')";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(statement, 1, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  return status == SQLITE_DONE && sqlite3_changes(database_) == 1;
}

bool Core::fail_backup_task_locked(const AccountOperation &operation, const std::string &code,
                                   bool retryable, int64_t retry_after_seconds) {
  if (operation.backup_task_id.empty()) return false;
  const bool missing_resource = code == "LOCAL_MEDIA_UNAVAILABLE" || code == "LOCAL_MEDIA_READ_FAILED" ||
      code == "PLATFORM_PERMISSION_DENIED";
  const bool storage_low = code == "BACKUP_DEVICE_STORAGE_LOW";
  const bool network_failure = code.find("NETWORK") != std::string::npos ||
      code == "PLATFORM_IO_ERROR" || code == "SERVICE_UNAVAILABLE";
  const bool object_failure = operation.stage == "MEDIA_UPLOAD_OBJECT_PART" ||
      code == "OBJECT_STORAGE_UNAVAILABLE" || code == "OBJECT_STORAGE_ERROR";
  const bool auth_failure = code == "SESSION_INVALID" || code == "AUTH_REQUIRED" ||
      code == "SESSION_EXPIRED" || code == "SESSION_REPLAYED";
  sqlite3_stmt *count_statement = nullptr;
  if (sqlite3_prepare_v2(database_, "SELECT retry_count FROM backup_tasks WHERE task_id=?", -1,
                         &count_statement, nullptr) != SQLITE_OK) {
    return false;
  }
  int count_status = sqlite3_bind_text(count_statement, 1, operation.backup_task_id.c_str(), -1,
                                       SQLITE_TRANSIENT);
  if (count_status == SQLITE_OK) count_status = sqlite3_step(count_statement);
  const int retry_count = count_status == SQLITE_ROW ? sqlite3_column_int(count_statement, 0) : -1;
  sqlite3_finalize(count_statement);
  if (retry_count < 0) return false;
  // Connectivity, local availability and storage are gates, not failed transfer attempts;
  // they deliberately do not consume the twelve-attempt upload retry budget.
  const bool counts_against_retry_budget = retryable && !missing_resource && !storage_low && !network_failure;
  const bool exhausted = counts_against_retry_budget && retry_count + 1 >= 12;
  const bool will_retry = retryable && !exhausted && !missing_resource;
  const std::string state = missing_resource ? "WAITING_RESOURCE" :
      (network_failure && will_retry ? "WAITING_NETWORK" :
       ((will_retry || exhausted) ? "RETRYABLE_FAILED" : "PERMANENT_FAILED"));
  const std::string scope = (missing_resource || storage_low) ? "LOCAL" :
      (auth_failure ? "AUTH" : (object_failure ? "OSS" : (network_failure ? "NETWORK" : "SERVICE")));
  const std::string stable_code = missing_resource ? "BACKUP_LOCAL_RESOURCE_UNAVAILABLE" :
      (storage_low ? "BACKUP_DEVICE_STORAGE_LOW" :
      (exhausted ? "BACKUP_RETRY_EXHAUSTED" :
       (network_failure ? "BACKUP_NETWORK_OFFLINE" :
        (object_failure ? "BACKUP_SERVICE_UNAVAILABLE" :
         (retryable ? "BACKUP_SERVICE_UNAVAILABLE" : code)))));
  const int64_t retry_delay = will_retry ? std::max<int64_t>(
      backup_retry_delay_seconds(retry_count), std::clamp<int64_t>(retry_after_seconds, 0, 15 * 60)) : 0;
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "UPDATE backup_tasks SET state=?,resume_state='PREPARING',retry_count=retry_count+?,"
      "next_retry_at=?,"
      "failure_code=?,failure_scope=?,lease_token=NULL,lease_expires_at=NULL,updated_at="
      "strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE task_id=? AND lease_token=?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(statement, 1, state.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_int(statement, 2, counts_against_retry_budget ? 1 : 0);
  const std::string retry_at = will_retry ? rfc3339_after_seconds(retry_delay) : std::string{};
  if (status == SQLITE_OK && will_retry) status = sqlite3_bind_text(statement, 3, retry_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK && !will_retry) status = sqlite3_bind_null(statement, 3);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, stable_code.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 5, scope.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 6, operation.backup_task_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 7, operation.backup_lease_token.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  return status == SQLITE_DONE && sqlite3_changes(database_) == 1;
}

bool Core::finish_backup_scan_locked(const std::string &user_id, const std::string &device_id,
                                     const std::string &generation_id, int64_t discovered_count,
                                     const std::string &completed_at,
                                     const std::string &cursor_json) {
  if (user_id.empty() || device_id.empty() || generation_id.empty() || discovered_count < 0 ||
      completed_at.empty()) return false;
  const char *sql =
      "UPDATE backup_scan_state SET state='IDLE',generation_id=?,cursor_json=?,"
      "discovered_count=?,completed_at=?,updated_at=? "
      "WHERE user_id=? AND device_installation_id=?";
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) return false;
  int status = sqlite3_bind_text(statement, 1, generation_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK && !cursor_json.empty()) status = sqlite3_bind_text(statement, 2, cursor_json.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK && cursor_json.empty()) status = sqlite3_bind_null(statement, 2);
  if (status == SQLITE_OK) status = sqlite3_bind_int64(statement, 3, discovered_count);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 4, completed_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 5, completed_at.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 6, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 7, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  return status == SQLITE_DONE;
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
  sqlite3_stmt *existing = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT 1 FROM local_albums WHERE user_id=? AND generation_id=? LIMIT 1",
                         -1, &existing, nullptr) != SQLITE_OK) return MINEG_DATABASE_ERROR;
  status = sqlite3_bind_text(existing, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(existing, 2, scan_generation.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_step(existing);
  const bool generation_exists = status == SQLITE_ROW;
  sqlite3_finalize(existing);
  if (status != SQLITE_ROW && status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  if (!generation_exists && !prepare_local_scan_locked(user_id, scan_generation)) {
    return MINEG_DATABASE_ERROR;
  }
  if (sqlite3_exec(database_, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  const auto fail = [this]() {
    sqlite3_exec(database_, "ROLLBACK", nullptr, nullptr, nullptr);
    return MINEG_DATABASE_ERROR;
  };
  if (!execute_json_statement_locked(
          "INSERT INTO local_albums(user_id,generation_id,platform_album_ref,name) "
          "SELECT json_extract(?1,'$.userId'),json_extract(?1,'$.scanGeneration'),"
          "json_extract(item.value,'$.platformAlbumRef'),json_extract(item.value,'$.name') "
          "FROM json_each(?1,'$.albums') item WHERE true "
          "ON CONFLICT(user_id,generation_id,platform_album_ref) "
          "DO UPDATE SET name=excluded.name",
          command)) return fail();
  if (!execute_json_statement_locked(
          "INSERT OR REPLACE INTO local_media(user_id,generation_id,platform_asset_ref,media_type,"
          "mime_type,width,height,duration_ms,captured_at,modified_at,modified_version,"
          "content_version,availability,thumbnail_uri) SELECT json_extract(?1,'$.userId'),"
          "json_extract(?1,'$.scanGeneration'),json_extract(item.value,'$.platformAssetRef'),"
          "json_extract(item.value,'$.mediaType'),json_extract(item.value,'$.mimeType'),"
          "json_extract(item.value,'$.width'),json_extract(item.value,'$.height'),"
          "json_extract(item.value,'$.durationMs'),json_extract(item.value,'$.capturedAt'),"
          "json_extract(item.value,'$.modifiedAt'),json_extract(item.value,'$.modifiedVersion'),"
          "json_extract(item.value,'$.contentVersion'),json_extract(item.value,'$.availability'),"
          "json_extract(item.value,'$.thumbnailUri') FROM json_each(?1,'$.media') item",
          command)) return fail();
  if (!execute_json_statement_locked(
          "INSERT OR REPLACE INTO local_media_albums(user_id,generation_id,platform_asset_ref,"
          "platform_album_ref) SELECT json_extract(?1,'$.userId'),"
          "json_extract(?1,'$.scanGeneration'),json_extract(item.value,'$.platformAssetRef'),"
          "json_extract(item.value,'$.platformAlbumRef') FROM json_each(?1,'$.relations') item",
          command)) return fail();
  if (sqlite3_exec(database_, "COMMIT", nullptr, nullptr, nullptr) != SQLITE_OK) return fail();
  if (extract_json_boolean(command, "complete", false)) {
    sqlite3_stmt *count = nullptr;
    if (sqlite3_prepare_v2(database_,
                           "SELECT count(*) FROM local_media WHERE user_id=? AND generation_id=?",
                           -1, &count, nullptr) != SQLITE_OK) return MINEG_DATABASE_ERROR;
    status = sqlite3_bind_text(count, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_bind_text(count, 2, scan_generation.c_str(), -1, SQLITE_TRANSIENT);
    if (status == SQLITE_OK) status = sqlite3_step(count);
    const int64_t indexed_count = status == SQLITE_ROW ? sqlite3_column_int64(count, 0) : -1;
    sqlite3_finalize(count);
    if (indexed_count < 0 || !finalize_local_scan_locked(user_id, scan_generation,
                                                          indexed_count, updated_at)) {
      return MINEG_DATABASE_ERROR;
    }
    ++event_sequence_;
    emit_locked("{\"contractVersion\":\"stage02-v2\",\"type\":"
                "\"LocalLibraryIndexChanged\",\"sequence\":" +
                std::to_string(event_sequence_) + ",\"userId\":\"" +
                json_escape(user_id) + "\",\"generationId\":\"" +
                json_escape(scan_generation) + "\",\"indexedCount\":" +
                std::to_string(indexed_count) + "}");
  }
  return MINEG_OK;
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
    return "{\"version\":1,\"settings\":{\"autoBackupEnabled\":false,"
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

std::string Core::read_backup_overview_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  const std::string device_id = extract_json_string(query, "deviceInstallationId");
  if (user_id.empty() || device_id.empty()) throw std::runtime_error("invalid backup overview query");
  const char *sql = R"SQL(
    SELECT
      COALESCE((SELECT auto_backup_enabled FROM backup_settings WHERE user_id=? AND device_installation_id=?),0),
      COALESCE((SELECT allow_cellular_backup FROM backup_settings WHERE user_id=? AND device_installation_id=?),0),
      COALESCE((SELECT state FROM backup_scan_state WHERE user_id=? AND device_installation_id=?),'IDLE'),
      COALESCE((SELECT discovered_count FROM backup_scan_state WHERE user_id=? AND device_installation_id=?),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state<>'COMPLETED'),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state='COMPLETED'),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state IN ('RETRYABLE_FAILED','PERMANENT_FAILED')),0),
      (SELECT next_retry_at FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND next_retry_at IS NOT NULL ORDER BY next_retry_at,task_id LIMIT 1),
      (SELECT failure_code FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND failure_code IS NOT NULL ORDER BY updated_at DESC,task_id DESC LIMIT 1),
      (SELECT completed_at FROM backup_scan_state WHERE user_id=? AND device_installation_id=?),
      (SELECT max(updated_at) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state='COMPLETED'),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state IN ('UPLOADING','SERVER_VERIFYING','CREATING_SESSION','PREPARING')),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state='WAITING_NETWORK'),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=?
        AND requested_manually=1 AND state IN ('DISCOVERED','PREPARING','CREATING_SESSION',
          'UPLOADING','SERVER_VERIFYING')),0)
  )SQL";
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  int bind = SQLITE_OK;
  for (int index = 1; index <= 28 && bind == SQLITE_OK; ++index) {
    bind = sqlite3_bind_text(statement, index, (index % 2 == 1 ? user_id : device_id).c_str(), -1, SQLITE_TRANSIENT);
  }
  if (bind != SQLITE_OK || sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    throw std::runtime_error("backup overview query failed");
  }
  const auto text_at = [statement](int column) {
    const auto *value = sqlite3_column_text(statement, column);
    return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
  };
  const bool auto_backup = sqlite3_column_int(statement, 0) == 1;
  const bool cellular = sqlite3_column_int(statement, 1) == 1;
  const std::string scan_state = text_at(2);
  const int64_t discovered = sqlite3_column_int64(statement, 3);
  const int64_t pending = sqlite3_column_int64(statement, 4);
  const int64_t completed = sqlite3_column_int64(statement, 5);
  const int64_t failed = sqlite3_column_int64(statement, 6);
  const std::string next_retry_at = text_at(7);
  const std::string failure_code = text_at(8);
  const std::string last_scan_completed_at = text_at(9);
  const std::string last_server_confirmed_at = text_at(10);
  const int64_t active = sqlite3_column_int64(statement, 11);
  const int64_t waiting_network = sqlite3_column_int64(statement, 12);
  const int64_t manual_pending = sqlite3_column_int64(statement, 13);
  sqlite3_finalize(statement);

  std::string current_media_ref;
  std::string current_media_type;
  std::string current_resource_type;
  int64_t confirmed_bytes = 0;
  int64_t transferred_bytes = 0;
  int64_t total_bytes = 0;
  sqlite3_stmt *progress = nullptr;
  const char *progress_sql = R"SQL(
    SELECT task.platform_asset_ref,task.media_type,resource.resource_type,
      COALESCE(sum(CASE WHEN part.state='CONFIRMED' THEN part.byte_length ELSE 0 END),0),
      COALESCE(sum(CASE WHEN part.state IN ('TRANSFERRED','CONFIRMED') THEN part.byte_length ELSE 0 END),0),
      COALESCE(resource.byte_length,0)
    FROM backup_tasks task
    LEFT JOIN backup_resources resource ON resource.task_id=task.task_id AND resource.resource_type='ORIGINAL'
    LEFT JOIN backup_parts part ON part.resource_id=resource.resource_id
    WHERE task.user_id=? AND task.device_installation_id=?
      AND task.state IN ('PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING')
    GROUP BY task.task_id,task.platform_asset_ref,task.media_type,resource.resource_type,resource.byte_length
    ORDER BY task.captured_at DESC,task.task_id DESC LIMIT 1
  )SQL";
  if (sqlite3_prepare_v2(database_, progress_sql, -1, &progress, nullptr) == SQLITE_OK) {
    int progress_status = sqlite3_bind_text(progress, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
    if (progress_status == SQLITE_OK) progress_status = sqlite3_bind_text(progress, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
    if (progress_status == SQLITE_OK) progress_status = sqlite3_step(progress);
    if (progress_status == SQLITE_ROW) {
      const auto *ref = sqlite3_column_text(progress, 0);
      const auto *type = sqlite3_column_text(progress, 1);
      const auto *resource_type = sqlite3_column_text(progress, 2);
      current_media_ref = ref == nullptr ? std::string{} : reinterpret_cast<const char *>(ref);
      current_media_type = type == nullptr ? std::string{} : reinterpret_cast<const char *>(type);
      current_resource_type = resource_type == nullptr ? std::string{} : reinterpret_cast<const char *>(resource_type);
      confirmed_bytes = sqlite3_column_int64(progress, 3);
      transferred_bytes = sqlite3_column_int64(progress, 4);
      total_bytes = sqlite3_column_int64(progress, 5);
    }
  }
  sqlite3_finalize(progress);

  std::string state = "COMPLETED";
  if (scan_state == "WAITING_PERMISSION") state = "PERMISSION_REQUIRED";
  else if (!auto_backup && manual_pending == 0) state = "AUTO_BACKUP_DISABLED";
  else if (active > 0 || manual_pending > 0) state = "UPLOADING";
  else if (scan_state == "SCANNING" || (auto_backup && last_scan_completed_at.empty())) state = "SCANNING";
  else if (waiting_network > 0) state = cellular ? "OFFLINE" : "WAITING_FOR_WIFI";
  else if (!failure_code.empty()) state = failure_code == "BACKUP_REMOTE_STORAGE_FULL" ? "REMOTE_STORAGE_FULL" :
      failure_code == "BACKUP_DEVICE_STORAGE_LOW" ? "DEVICE_STORAGE_LOW" :
      failure_code == "BACKUP_SERVICE_UNAVAILABLE" ? "SERVICE_UNAVAILABLE" : "RETRY_REQUIRED";
  const bool retryable = failed > 0 && state != "REMOTE_STORAGE_FULL";
  return "{\"contractVersion\":\"stage04-v1\",\"snapshot\":{\"state\":\"" + state +
      "\",\"autoBackupEnabled\":" + (auto_backup ? "true" : "false") +
      ",\"allowCellularBackup\":" + (cellular ? "true" : "false") +
      ",\"discoveredCount\":" + std::to_string(discovered) +
      ",\"pendingCount\":" + std::to_string(pending) +
      ",\"completedCount\":" + std::to_string(completed) +
      ",\"failedCount\":" + std::to_string(failed) +
      ",\"currentMediaRef\":" + (current_media_ref.empty() ? "null" : "\"" + json_escape(current_media_ref) + "\"") +
      ",\"currentMediaType\":" + (current_media_type.empty() ? "null" : "\"" + json_escape(current_media_type) + "\"") +
      ",\"currentResourceType\":" + (current_resource_type.empty() ? "null" : "\"" + json_escape(current_resource_type) + "\"") +
      ",\"confirmedBytes\":" + std::to_string(confirmed_bytes) +
      ",\"transferredBytes\":" + std::to_string(transferred_bytes) +
      ",\"totalBytes\":" + std::to_string(total_bytes) + ",\"nextRetryAt\":" +
      (next_retry_at.empty() ? "null" : "\"" + json_escape(next_retry_at) + "\"") +
      ",\"failureCode\":" + (failure_code.empty() ? "null" : "\"" + json_escape(failure_code) + "\"") +
      ",\"retryable\":" + (retryable ? "true" : "false") +
      ",\"lastScanCompletedAt\":" + (last_scan_completed_at.empty() ? "null" : "\"" + json_escape(last_scan_completed_at) + "\"") +
      ",\"lastServerConfirmedAt\":" + (last_server_confirmed_at.empty() ? "null" : "\"" + json_escape(last_server_confirmed_at) + "\"") + "}}";
}

std::string Core::read_local_album_backup_progress_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  const std::string device_id = extract_json_string(query, "deviceInstallationId");
  const std::string album_ref = extract_json_string(query, "platformAlbumRef");
  if (user_id.empty() || device_id.empty() || album_ref.empty()) {
    throw std::runtime_error("invalid local album backup progress query");
  }
  sqlite3_stmt *statement = nullptr;
  const char *sql = R"SQL(
    SELECT count(*),COALESCE(sum(CASE WHEN task.state='COMPLETED' THEN 1 ELSE 0 END),0)
    FROM local_media media
    JOIN local_library_active active ON active.user_id=media.user_id
      AND active.generation_id=media.generation_id
    JOIN local_media_albums relation ON relation.user_id=media.user_id
      AND relation.generation_id=media.generation_id
      AND relation.platform_asset_ref=media.platform_asset_ref
    LEFT JOIN backup_tasks task ON task.user_id=media.user_id
      AND task.device_installation_id=?
      AND task.platform_asset_ref=media.platform_asset_ref
      AND task.content_version=media.content_version
    WHERE media.user_id=? AND relation.platform_album_ref=?
      AND media.availability<>'LOCAL_MISSING'
  )SQL";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  int status = sqlite3_bind_text(statement, 1, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(statement, 3, album_ref.c_str(), -1, SQLITE_TRANSIENT);
  if (status != SQLITE_OK || sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    throw std::runtime_error("local album backup progress query failed");
  }
  const int64_t total = sqlite3_column_int64(statement, 0);
  const int64_t completed = sqlite3_column_int64(statement, 1);
  sqlite3_finalize(statement);

  sqlite3_stmt *media_states = nullptr;
  const char *media_states_sql = R"SQL(
    SELECT media.platform_asset_ref,
      CASE
        WHEN task.state='COMPLETED' THEN 'SYNCED'
        WHEN task.state IN ('DISCOVERED','WAITING_NETWORK','PREPARING','CREATING_SESSION',
                            'UPLOADING','SERVER_VERIFYING') THEN 'SYNCING'
        WHEN task.state IN ('WAITING_RESOURCE','RETRYABLE_FAILED','PERMANENT_FAILED') THEN 'FAILED'
        ELSE 'UNSYNCED'
      END
    FROM local_media media
    JOIN local_library_active active ON active.user_id=media.user_id
      AND active.generation_id=media.generation_id
    JOIN local_media_albums relation ON relation.user_id=media.user_id
      AND relation.generation_id=media.generation_id
      AND relation.platform_asset_ref=media.platform_asset_ref
    LEFT JOIN backup_tasks task ON task.user_id=media.user_id
      AND task.device_installation_id=?
      AND task.platform_asset_ref=media.platform_asset_ref
      AND task.content_version=media.content_version
    WHERE media.user_id=? AND relation.platform_album_ref=?
      AND media.availability<>'LOCAL_MISSING'
    ORDER BY media.captured_at DESC,media.platform_asset_ref DESC
    LIMIT 120
  )SQL";
  if (sqlite3_prepare_v2(database_, media_states_sql, -1, &media_states, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  status = sqlite3_bind_text(media_states, 1, device_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(media_states, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (status == SQLITE_OK) status = sqlite3_bind_text(media_states, 3, album_ref.c_str(), -1, SQLITE_TRANSIENT);
  if (status != SQLITE_OK) {
    sqlite3_finalize(media_states);
    throw std::runtime_error("local album media state query bind failed");
  }
  std::string result = "{\"contractVersion\":\"stage04-v1\",\"snapshot\":{\"completedCount\":" +
      std::to_string(completed) + ",\"totalCount\":" + std::to_string(total) + ",\"mediaStates\":[";
  int media_count = 0;
  while ((status = sqlite3_step(media_states)) == SQLITE_ROW) {
    const auto *asset_ref = sqlite3_column_text(media_states, 0);
    const auto *sync_state = sqlite3_column_text(media_states, 1);
    if (asset_ref == nullptr || sync_state == nullptr) {
      sqlite3_finalize(media_states);
      throw std::runtime_error("local album media state is invalid");
    }
    if (media_count++ > 0) result += ',';
    result += "{\"platformAssetRef\":\"" +
        json_escape(reinterpret_cast<const char *>(asset_ref)) + "\",\"state\":\"" +
        json_escape(reinterpret_cast<const char *>(sync_state)) + "\"}";
  }
  sqlite3_finalize(media_states);
  if (status != SQLITE_DONE) throw std::runtime_error("local album media state query failed");
  result += "]}}";
  return result;
}

std::string Core::read_backup_queue_summary_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  const std::string device_id = extract_json_string(query, "deviceInstallationId");
  if (user_id.empty() || device_id.empty()) throw std::runtime_error("invalid backup queue query");
  const char *sql = R"SQL(
    SELECT
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state<>'COMPLETED'),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state='RETRYABLE_FAILED'),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND state='PERMANENT_FAILED'),0),
      (SELECT min(next_retry_at) FROM backup_tasks WHERE user_id=? AND device_installation_id=? AND next_retry_at IS NOT NULL),
      COALESCE((SELECT reconcile_requested FROM backup_scan_state WHERE user_id=? AND device_installation_id=?),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=?
        AND (state IN ('DISCOVERED','WAITING_NETWORK','RETRYABLE_FAILED') OR
          (state IN ('PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING') AND
           (lease_expires_at IS NULL OR lease_expires_at<=strftime('%Y-%m-%dT%H:%M:%fZ','now'))))
        AND (next_retry_at IS NULL OR next_retry_at<=strftime('%Y-%m-%dT%H:%M:%fZ','now'))),0),
      COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=?
        AND state='WAITING_NETWORK'),0)
      ,COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=?
        AND requested_manually=1 AND state<>'COMPLETED'),0)
      ,COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=?
        AND requested_manually=1 AND (state IN ('DISCOVERED','WAITING_NETWORK','RETRYABLE_FAILED') OR
          (state IN ('PREPARING','CREATING_SESSION','UPLOADING','SERVER_VERIFYING') AND
           (lease_expires_at IS NULL OR lease_expires_at<=strftime('%Y-%m-%dT%H:%M:%fZ','now'))))
        AND (next_retry_at IS NULL OR next_retry_at<=strftime('%Y-%m-%dT%H:%M:%fZ','now'))),0)
      ,COALESCE((SELECT count(*) FROM backup_tasks WHERE user_id=? AND device_installation_id=?
        AND requested_manually=1 AND state='WAITING_NETWORK'),0)
      ,(SELECT min(next_retry_at) FROM backup_tasks WHERE user_id=? AND device_installation_id=?
        AND requested_manually=1 AND next_retry_at IS NOT NULL)
  )SQL";
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  int bind = SQLITE_OK;
  for (int index = 1; index <= 22 && bind == SQLITE_OK; ++index) {
    bind = sqlite3_bind_text(statement, index, (index % 2 == 1 ? user_id : device_id).c_str(), -1, SQLITE_TRANSIENT);
  }
  if (bind != SQLITE_OK || sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    throw std::runtime_error("backup queue query failed");
  }
  const auto *next = sqlite3_column_text(statement, 3);
  const std::string next_retry_at = next == nullptr ? std::string{} : reinterpret_cast<const char *>(next);
  const int64_t pending = sqlite3_column_int64(statement, 0);
  const int64_t retryable = sqlite3_column_int64(statement, 1);
  const int64_t permanent = sqlite3_column_int64(statement, 2);
  const bool reconcile_requested = sqlite3_column_int(statement, 4) == 1;
  const int64_t runnable = sqlite3_column_int64(statement, 5);
  const int64_t waiting_network = sqlite3_column_int64(statement, 6);
  const int64_t manual_pending = sqlite3_column_int64(statement, 7);
  const int64_t manual_runnable = sqlite3_column_int64(statement, 8);
  const int64_t manual_waiting_network = sqlite3_column_int64(statement, 9);
  const auto *manual_next = sqlite3_column_text(statement, 10);
  const std::string manual_next_retry_at = manual_next == nullptr ? std::string{} :
      reinterpret_cast<const char *>(manual_next);
  const bool schedule_requested = reconcile_requested || runnable > 0 || waiting_network > 0 ||
      !next_retry_at.empty();
  sqlite3_finalize(statement);

  // Queue draining must not implicitly trigger a library scan.  A scan is only needed for
  // the first automatic-backup run, an explicit MediaStore change, an interrupted scan, or
  // the periodic full reconciliation.  Keeping this decision in Core makes it durable across
  // Worker recreation and process death.
  bool reconciliation_required = true;
  sqlite3_stmt *scan = nullptr;
  const char *scan_sql = "SELECT state,reconcile_requested,completed_at FROM backup_scan_state "
                         "WHERE user_id=? AND device_installation_id=?";
  if (sqlite3_prepare_v2(database_, scan_sql, -1, &scan, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  int scan_status = sqlite3_bind_text(scan, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (scan_status == SQLITE_OK) {
    scan_status = sqlite3_bind_text(scan, 2, device_id.c_str(), -1, SQLITE_TRANSIENT);
  }
  if (scan_status == SQLITE_OK) scan_status = sqlite3_step(scan);
  if (scan_status == SQLITE_ROW) {
    const auto text_at = [scan](int column) {
      const auto *value = sqlite3_column_text(scan, column);
      return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
    };
    const std::string scan_state = text_at(0);
    const bool scan_requested = sqlite3_column_int(scan, 1) == 1;
    const std::string scan_completed_at = text_at(2);
    const std::string full_reconcile_before = rfc3339_at(
        std::chrono::system_clock::now() - std::chrono::hours(24 * 7));
    reconciliation_required = scan_requested || scan_state != "IDLE" ||
        scan_completed_at.empty() || scan_completed_at < full_reconcile_before;
  } else if (scan_status != SQLITE_DONE) {
    sqlite3_finalize(scan);
    throw std::runtime_error("backup scan query failed");
  }
  sqlite3_finalize(scan);

  return "{\"contractVersion\":\"stage04-v1\",\"summary\":{\"pendingCount\":" +
      std::to_string(pending) + ",\"retryableFailedCount\":" + std::to_string(retryable) +
      ",\"permanentFailedCount\":" + std::to_string(permanent) + ",\"earliestNextRetryAt\":" +
      (next_retry_at.empty() ? "null" : "\"" + json_escape(next_retry_at) + "\"") +
      ",\"runnableCount\":" + std::to_string(runnable) +
      ",\"waitingNetworkCount\":" + std::to_string(waiting_network) +
      ",\"manualPendingCount\":" + std::to_string(manual_pending) +
      ",\"manualRunnableCount\":" + std::to_string(manual_runnable) +
      ",\"manualWaitingNetworkCount\":" + std::to_string(manual_waiting_network) +
      ",\"manualEarliestNextRetryAt\":" +
      (manual_next_retry_at.empty() ? "null" : "\"" + json_escape(manual_next_retry_at) + "\"") +
      ",\"reconciliationRequired\":" +
      (reconciliation_required ? "true" : "false") +
      ",\"scheduleRequested\":" + (schedule_requested ? "true" : "false") + "}}";
}

std::string Core::read_scan_state_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  if (user_id.empty()) throw std::runtime_error("invalid scan query");
  sqlite3_stmt *statement = nullptr;
  const char *sql = "SELECT generation_id,indexed_count,completed_at "
                    "FROM local_library_active WHERE user_id=?";
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
      "{\"version\":1,\"state\":{\"cursorModifiedVersion\":0,\"cursorAssetRef\":\"\","
      "\"status\":\"COMPLETE\",\"indexedCount\":" +
      std::to_string(sqlite3_column_int64(statement, 1)) + ",\"scanGeneration\":\"" +
      json_escape(text_at(0)) + "\",\"updatedAt\":\"" + json_escape(text_at(2)) + "\"}}";
  sqlite3_finalize(statement);
  return result;
}

std::string Core::read_local_library_summary_locked(const std::string &query) {
  const std::string user_id = extract_json_string(query, "userId");
  if (user_id.empty()) throw std::runtime_error("invalid local library summary query");
  sqlite3_stmt *statement = nullptr;
  if (sqlite3_prepare_v2(database_,
                         "SELECT generation_id,indexed_count,completed_at "
                         "FROM local_library_active WHERE user_id=?",
                         -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  if (sqlite3_step(statement) != SQLITE_ROW) {
    sqlite3_finalize(statement);
    return "{\"contractVersion\":\"stage02-v2\",\"snapshot\":null}";
  }
  const auto text_at = [statement](int column) {
    const auto *value = sqlite3_column_text(statement, column);
    return value == nullptr ? std::string{} : std::string(reinterpret_cast<const char *>(value));
  };
  const std::string result =
      "{\"contractVersion\":\"stage02-v2\",\"snapshot\":{\"generationId\":\"" +
      json_escape(text_at(0)) + "\",\"indexedCount\":" +
      std::to_string(sqlite3_column_int64(statement, 1)) + ",\"completedAt\":\"" +
      json_escape(text_at(2)) + "\"}}";
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
      "cover.generation_id=cover_relation.generation_id AND "
      "cover.platform_asset_ref=cover_relation.platform_asset_ref "
      "WHERE cover_relation.user_id=album.user_id AND cover_relation.generation_id=album.generation_id "
      "AND cover_relation.platform_album_ref=album.platform_album_ref "
      "ORDER BY cover.captured_at DESC,cover.platform_asset_ref DESC LIMIT 1) "
      "FROM local_albums album LEFT JOIN local_media_albums relation ON relation.user_id=album.user_id "
      "AND relation.generation_id=album.generation_id "
      "AND relation.platform_album_ref=album.platform_album_ref LEFT JOIN local_media media ON "
      "media.user_id=relation.user_id AND media.generation_id=relation.generation_id "
      "AND media.platform_asset_ref=relation.platform_asset_ref WHERE album.user_id=? "
      "AND album.generation_id=(SELECT generation_id FROM local_library_active WHERE user_id=?) "
      "AND (?='' OR (album.name,album.platform_album_ref)>(?,?)) "
      "GROUP BY album.platform_album_ref,album.name ORDER BY album.name,album.platform_album_ref LIMIT ?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 3, cursor_name.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 4, cursor_name.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 5, cursor_ref.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_int(statement, 6, limit + 1);
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
      "AND media.generation_id=(SELECT generation_id FROM local_library_active WHERE user_id=?) "
      "AND (?='' OR EXISTS(SELECT 1 FROM local_media_albums relation WHERE relation.user_id=media.user_id "
      "AND relation.generation_id=media.generation_id "
      "AND relation.platform_asset_ref=media.platform_asset_ref AND relation.platform_album_ref=?)) "
      "AND (?='' OR (media.captured_at,media.platform_asset_ref)<(?,?)) "
      "ORDER BY media.captured_at DESC,media.platform_asset_ref DESC LIMIT ?";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    throw std::runtime_error(sqlite3_errmsg(database_));
  }
  sqlite3_bind_text(statement, 1, user_id.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 2, user_id.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 3, album_ref.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 4, album_ref.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 5, cursor_time.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 6, cursor_time.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_text(statement, 7, cursor_ref.c_str(), -1, SQLITE_TRANSIENT);
  sqlite3_bind_int(statement, 8, limit + 1);
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
  const std::string requested_contract = extract_json_string(query, "contractVersion");
  const std::string account_contract = "account-v3";
  try {
    if ((type == "GetAccountRouteSnapshot" || type == "GetCurrentProfileSnapshot") &&
        requested_contract != account_contract) {
      return MINEG_INVALID_ARGUMENT;
    }
    if ((type == "GetBackupOverview" || type == "GetLocalAlbumBackupProgress" ||
         type == "GetBackupQueueSummary") &&
        requested_contract != "stage04-v1") {
      return MINEG_INVALID_ARGUMENT;
    }
    if (type == "GetPrivateMediaPage" && requested_contract != "stage05-v1") {
      return MINEG_INVALID_ARGUMENT;
    }
    if (type == "GetPrivateMediaDetail" && requested_contract != "stage05-v1") {
      return MINEG_INVALID_ARGUMENT;
    }
    if (type == "GetAccountState") {
      result = read_account_state_locked();
      return MINEG_OK;
    }
    if (type == "GetAccountRouteSnapshot") {
      const std::string account = read_account_state_locked();
      const std::string user_id = sqlite_json_text(database_, account, "$.state.userId");
      const std::string approval = sqlite_json_text(database_, account, "$.state.approvalStatus");
      if (user_id.empty()) {
        result = "{\"contractVersion\":\"" + account_contract + "\",\"snapshot\":null}";
      } else {
        result = "{\"contractVersion\":\"" + account_contract +
            "\",\"snapshot\":{\"userId\":\"" +
            json_escape(user_id) + "\",\"approvalStatus\":\"" + approval +
            "\",\"nextStep\":\"" +
            (approval == "APPROVED" ? "APP_HOME" : "REVIEW_PENDING") + "\"}}";
      }
      return MINEG_OK;
    }
    if (type == "GetCurrentProfileSnapshot") {
      const std::string profile = read_current_profile_snapshot_locked();
      result = "{\"contractVersion\":\"" + account_contract + "\",\"snapshot\":" +
          (profile.empty() ? "null" : profile) + "}";
      return MINEG_OK;
    }
    if (type == "ListPrivateMediaSnapshot") {
      const int limit = static_cast<int>(std::clamp<int64_t>(
          extract_json_integer(query, "limit", 100), 1, 100));
      const std::string snapshot = read_private_media_snapshot_locked(limit);
      result = "{\"contractVersion\":\"stage02-v2\",\"snapshot\":" +
          (snapshot.empty() ? "null" : snapshot) + "}";
      return MINEG_OK;
    }
    if (type == "GetPrivateMediaPage") {
      const int limit = static_cast<int>(std::clamp<int64_t>(
          extract_json_integer(query, "limit", 50), 1, 100));
      const std::string snapshot = read_private_media_page_v2_locked(limit);
      result = "{\"contractVersion\":\"stage05-v1\",\"snapshot\":" +
          (snapshot.empty() ? "null" : snapshot) + "}";
      return MINEG_OK;
    }
    if (type == "GetPrivateMediaDetail") {
      const std::string media_id = extract_json_string(query, "mediaId");
      const std::string detail = read_private_media_detail_v2_locked(media_id);
      result = "{\"contractVersion\":\"stage05-v1\",\"snapshot\":" +
          (detail.empty() ? "null" : detail) + "}";
      return MINEG_OK;
    }
    if (type == "GetBackupSettings") {
      result = read_backup_settings_locked(query);
      return MINEG_OK;
    }
    if (type == "GetBackupOverview") {
      result = read_backup_overview_locked(query);
      return MINEG_OK;
    }
    if (type == "GetLocalAlbumBackupProgress") {
      result = read_local_album_backup_progress_locked(query);
      return MINEG_OK;
    }
    if (type == "GetBackupQueueSummary") {
      result = read_backup_queue_summary_locked(query);
      return MINEG_OK;
    }
    if (type == "GetLocalScanState") {
      result = read_scan_state_locked(query);
      return MINEG_OK;
    }
    if (type == "GetLocalLibrarySummary") {
      result = read_local_library_summary_locked(query);
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
  if (operation_id == 0 || operation_id > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
    return MINEG_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> lock(mutex_);
  const auto account = account_operations_.find(operation_id);
  if (account != account_operations_.end()) {
    if (account->second->status == "WAITING_FOR_EFFECT") {
      ++account->second->sequence;
      account->second->status = "CANCELLED";
      account->second->clear_sensitive();
    }
    return MINEG_OK;
  }
  sqlite3_stmt *statement = nullptr;
  const char *sql =
      "UPDATE core_operations SET sequence=sequence+1,status='CANCELLED',terminal_payload=NULL,"
      "updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') "
      "WHERE operation_id=? AND status='WAITING_FOR_EFFECT'";
  if (sqlite3_prepare_v2(database_, sql, -1, &statement, nullptr) != SQLITE_OK) {
    return MINEG_DATABASE_ERROR;
  }
  int status = sqlite3_bind_int64(statement, 1, static_cast<long long>(operation_id));
  if (status == SQLITE_OK) status = sqlite3_step(statement);
  sqlite3_finalize(statement);
  if (status != SQLITE_DONE) return MINEG_DATABASE_ERROR;
  if (sqlite3_changes(database_) == 0) {
    sqlite3_stmt *existing = nullptr;
    if (sqlite3_prepare_v2(database_, "SELECT 1 FROM core_operations WHERE operation_id=?", -1,
                           &existing, nullptr) != SQLITE_OK) {
      return MINEG_DATABASE_ERROR;
    }
    status = sqlite3_bind_int64(existing, 1, static_cast<long long>(operation_id));
    if (status == SQLITE_OK) status = sqlite3_step(existing);
    const bool operation_exists = status == SQLITE_ROW;
    sqlite3_finalize(existing);
    if (!operation_exists) cancelled_operations_.insert(operation_id);
  }
  return MINEG_OK;
}

void Core::emit_locked(const std::string &event) {
  for (const auto &entry : subscribers_) entry.second(event);
}

}  // namespace mineg
