#include "core.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <sstream>
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
         effect_type == "MediaSourceEffect" ||
         effect_type == "BackgroundSchedulerEffect" || effect_type == "FileEffect";
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

std::string now_rfc3339() {
  const auto now = std::chrono::system_clock::now();
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
  std::string public_key_base64;
  std::string encrypted_bundle_base64;
  std::string kdf_parameters;
  std::string key_bundle_public;
  std::string key_bundle_encrypted;
  std::string family_envelope;
  std::string device_wrap_key;
  std::string device_unlock_blob;
  std::string pending_grants_json;
  std::string grant_id;
  std::string recipient_public_key;
  std::string encrypted_envelope;
  std::string avatar_bytes;
  std::string avatar_digest_base64;
  std::string avatar_content_type;
  std::string avatar_upload_id;
  int64_t avatar_source_size = 0;
  int64_t avatar_width = 0;
  int64_t media_limit = 100;
  int64_t grant_index = 0;
  int64_t completed_grant_count = 0;
  bool allow_cached_profile = false;
  bool allow_cached_media = false;
  bool replayed_after_refresh = false;
  int effect_retry_count = 0;

  ~AccountOperation() { clear_sensitive(); }

  void clear_sensitive() {
    wipe_string(phone);
    wipe_string(masked_phone);
    wipe_string(password);
    wipe_string(idempotency_key);
    wipe_string(nickname);
    wipe_string(device_installation_id);
    wipe_string(public_key_base64);
    wipe_string(encrypted_bundle_base64);
    wipe_string(kdf_parameters);
    wipe_string(key_bundle_public);
    wipe_string(key_bundle_encrypted);
    wipe_string(family_envelope);
    wipe_string(device_wrap_key);
    wipe_string(device_unlock_blob);
    wipe_string(pending_grants_json);
    wipe_string(grant_id);
    wipe_string(recipient_public_key);
    wipe_string(encrypted_envelope);
    wipe_string(avatar_bytes);
    wipe_string(avatar_digest_base64);
    wipe_string(avatar_content_type);
    wipe_string(avatar_upload_id);
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
  lock_keys_locked();
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
  set_account_effect_locked(operation, "BackgroundSchedulerEffect",
                            "{\"action\":\"cancelBackup\"}", "CANCEL_SCHEDULER");
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
        "\",\"public_key\":\"" + operation.public_key_base64 +
        "\",\"encrypted_key_bundle\":\"" + operation.encrypted_bundle_base64 +
        "\",\"kdf_parameters\":" + operation.kdf_parameters +
        ",\"bundle_version\":1,\"device_installation_id\":\"" +
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
    } else if (purpose == "KEY_BUNDLE") {
      method = "GET";
      path = "/api/v1/me/key-bundle";
    } else if (purpose == "KEY_GRANTS_LIST") {
      method = "GET";
      path = "/api/v1/key-grants/pending?limit=20";
    } else if (purpose == "KEY_GRANT_COMPLETE") {
      if (operation.grant_id.empty() || operation.recipient_public_key.size() != MINEG_KEY_BYTES ||
          operation.encrypted_envelope.size() != MINEG_FAMILY_KEY_ENVELOPE_BYTES) {
        return MINEG_INVALID_ARGUMENT;
      }
      method = "POST";
      path = "/api/v1/key-grants/" + operation.grant_id + "/complete";
      body = "{\"recipient_public_key\":\"" +
          base64_encode(operation.recipient_public_key, false) +
          "\",\"encrypted_envelope\":\"" + base64_encode(operation.encrypted_envelope, false) +
          "\",\"algorithm\":\"X25519_SEALED_BOX\",\"envelope_version\":1}";
    } else if (purpose == "PRIVATE_MEDIA_LIST") {
      method = "GET";
      path = "/api/v1/media?limit=" + std::to_string(operation.media_limit);
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
  emit_locked("{\"contractVersion\":\"account-v2\",\"type\":\"AccountRouteChanged\","
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

bool Core::persist_current_profile_locked(const std::string &profile_json) {
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
  emit_locked("{\"contractVersion\":\"account-v2\",\"type\":\"CurrentProfileChanged\","
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

void Core::clear_account_session_locked() {
  active_account_session_.reset();
  lock_keys_locked();
  sqlite3_exec(database_, "DELETE FROM account_state WHERE singleton=1", nullptr, nullptr, nullptr);
}

mineg_error_code_t Core::start_account_operation_locked(uint64_t operation_id,
                                                         const std::string &command,
                                                         std::string &result) {
  const std::string contract_version = top_level_json_string(command, "contractVersion");
  if (!valid_json(database_, command) ||
      (contract_version != "account-v2" && contract_version != "stage02-v2")) {
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
  const bool stage02_type = value->type == "CoordinateFamilyKeyGrants" ||
      value->type == "PrivateMediaList" || value->type == "ProfileUpdateAvatar";
  if ((contract_version == "stage02-v2") != stage02_type) {
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
      mineg_buffer_t public_key{};
      mineg_buffer_t encrypted_bundle{};
      mineg_buffer_t kdf{};
      const mineg_error_code_t code = mineg_core_create_user_key_bundle(
          reinterpret_cast<const uint8_t *>(value->password.data()), value->password.size(),
          &public_key, &encrypted_bundle, &kdf);
      if (code != MINEG_OK) {
        mineg_buffer_free(&public_key);
        mineg_buffer_free(&encrypted_bundle);
        mineg_buffer_free(&kdf);
        return fail(code == MINEG_INVALID_ARGUMENT ? "PASSWORD_INVALID" : "KEY_BUNDLE_INVALID");
      }
      value->public_key_base64 = base64_encode(public_key.data, public_key.size, false);
      value->encrypted_bundle_base64 =
          base64_encode(encrypted_bundle.data, encrypted_bundle.size, false);
      value->kdf_parameters.assign(reinterpret_cast<const char *>(kdf.data), kdf.size);
      mineg_buffer_free(&public_key);
      mineg_buffer_free(&encrypted_bundle);
      mineg_buffer_free(&kdf);
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
  } else if (value->type == "CoordinateFamilyKeyGrants") {
    value->password = top_level_json_string(command, "password");
    if (!value->password.empty() && !valid_password(value->password)) {
      return fail("PASSWORD_INVALID");
    }
    if (active_account_session_ != nullptr) {
      const mineg_error_code_t code = issue_account_request_locked(*value, "KEY_BUNDLE");
      if (code != MINEG_OK) return fail("SESSION_INVALID");
    } else {
      value->continuation = "KEY_BUNDLE";
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
                                                                   const std::string &request_id = std::string{}) {
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
    finish_account_error_locked(operation, code, retryable, request_id);
    return account_operation_step_locked(operation, result);
  };

  const auto continue_key_grants = [this, &operation, &result,
                                    &cached_profile_or_error]() -> mineg_error_code_t {
    const int64_t count = sqlite_json_integer(database_, operation.pending_grants_json,
                                               "$.itemCount", -1);
    if (count < 0 || count > 20 || operation.grant_index < 0 || operation.grant_index > count) {
      return cached_profile_or_error("RESPONSE_INVALID", false);
    }
    if (operation.grant_index == count) {
      operation.status = "COMPLETED";
      operation.terminal_payload = "{\"completed\":" +
          std::string(operation.completed_grant_count > 0 ? "true" : "false") +
          ",\"completedCount\":" + std::to_string(operation.completed_grant_count) +
          ",\"keysAvailable\":true}";
      operation.clear_sensitive();
      return account_operation_step_locked(operation, result);
    }
    const std::string prefix = "$.items[" + std::to_string(operation.grant_index) + "]";
    operation.grant_id = sqlite_json_text(database_, operation.pending_grants_json, prefix + ".id");
    const std::string kind = sqlite_json_text(database_, operation.pending_grants_json,
                                              prefix + ".kind");
    const std::string recipient = sqlite_json_text(database_, operation.pending_grants_json,
                                                   prefix + ".recipient_public_key");
    if (operation.grant_id.empty() ||
        (kind != "FAMILY_BOOTSTRAP" && kind != "MEMBER_GRANT") ||
        !base64_decode(recipient, operation.recipient_public_key) ||
        operation.recipient_public_key.size() != MINEG_KEY_BYTES) {
      return cached_profile_or_error("KEY_GRANT_RESPONSE_INVALID", false);
    }
    const mineg_error_code_t envelope_code = create_family_key_envelope_locked(
        reinterpret_cast<const uint8_t *>(operation.recipient_public_key.data()),
        kind == "FAMILY_BOOTSTRAP", operation.encrypted_envelope);
    if (envelope_code != MINEG_OK) {
      return cached_profile_or_error(envelope_code == MINEG_NOT_FOUND ? "KEYS_LOCKED" :
                                     "KEY_ENVELOPE_INVALID", false);
    }
    const mineg_error_code_t request_code = issue_account_request_locked(
        operation, "KEY_GRANT_COMPLETE");
    if (request_code != MINEG_OK) {
      return cached_profile_or_error("KEY_GRANT_RESPONSE_INVALID", false);
    }
    return account_operation_step_locked(operation, result);
  };

  if (effect_status == "FAILED") {
    const std::string code = sqlite_json_text(database_, effect_result, "$.error.code");
    const bool retryable = sqlite_json_boolean(database_, effect_result, "$.error.retryable", false);
    if (operation.effect_type == "TransportEffect" && retryable &&
        operation.effect_retry_count < 1) {
      ++operation.effect_retry_count;
      return account_operation_step_locked(operation, result);
    }
    if (operation.stage == "TRANSPORT_SIGN_OUT") {
      issue_session_cleanup_locked(operation, "SIGNED_OUT");
      return account_operation_step_locked(operation, result);
    }
    if (operation.stage == "CANCEL_SCHEDULER") {
      set_account_effect_locked(
          operation, "SecureStoreEffect",
          "{\"action\":\"deleteSecrets\",\"names\":[\"account.accessToken\","
          "\"account.refreshToken\",\"account.accessExpiresAt\","
          "\"account.refreshExpiresAt\",\"keys.userPublicKey\","
          "\"keys.deviceUnlockBlob\"]}",
          "DELETE_SESSION");
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
    const mineg_error_code_t code = issue_account_request_locked(operation, continuation);
    if (code != MINEG_OK) return cached_profile_or_error("SESSION_INVALID", false);
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "CANCEL_SCHEDULER") {
    set_account_effect_locked(
        operation, "SecureStoreEffect",
        "{\"action\":\"deleteSecrets\",\"names\":[\"account.accessToken\","
        "\"account.refreshToken\",\"account.accessExpiresAt\","
        "\"account.refreshExpiresAt\",\"keys.userPublicKey\","
        "\"keys.deviceUnlockBlob\"]}",
        "DELETE_SESSION");
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

  if (operation.stage == "READ_KEY_SECRETS") {
    operation.device_wrap_key = secure_result_value(database_, effect_result,
                                                     "keys.deviceWrapKey");
    const std::string stored_public = secure_result_value(database_, effect_result,
                                                          "keys.userPublicKey");
    operation.device_unlock_blob = secure_result_value(database_, effect_result,
                                                        "keys.deviceUnlockBlob");
    const bool stored_key_material_complete = stored_public == operation.key_bundle_public &&
        stored_public.size() == MINEG_KEY_BYTES &&
        operation.device_wrap_key.size() == MINEG_KEY_BYTES &&
        !operation.device_unlock_blob.empty();
    mineg_error_code_t key_code = MINEG_INVALID_ARGUMENT;
    if (!operation.password.empty()) {
      if (operation.device_wrap_key.size() != MINEG_KEY_BYTES) {
        operation.device_wrap_key.assign(MINEG_KEY_BYTES, '\0');
        randombytes_buf(operation.device_wrap_key.data(), operation.device_wrap_key.size());
      }
      key_code = unlock_user_key_bundle_locked(
          reinterpret_cast<const uint8_t *>(operation.password.data()), operation.password.size(),
          reinterpret_cast<const uint8_t *>(operation.key_bundle_public.data()),
          reinterpret_cast<const uint8_t *>(operation.key_bundle_encrypted.data()),
          operation.key_bundle_encrypted.size(),
          reinterpret_cast<const uint8_t *>(operation.device_wrap_key.data()),
          operation.device_unlock_blob);
      wipe_string(operation.password);
    } else if (stored_key_material_complete) {
      key_code = restore_user_key_bundle_locked(
          reinterpret_cast<const uint8_t *>(stored_public.data()),
          reinterpret_cast<const uint8_t *>(operation.device_wrap_key.data()),
          reinterpret_cast<const uint8_t *>(operation.device_unlock_blob.data()),
          operation.device_unlock_blob.size());
    }
    if (key_code != MINEG_OK) {
      return cached_profile_or_error(key_code == MINEG_INTEGRITY_ERROR ?
                                     "KEY_BUNDLE_INVALID" : "KEYS_LOCKED", false);
    }
    if (!operation.family_envelope.empty()) {
      const mineg_error_code_t family_code = unlock_family_key_envelope_locked(
          reinterpret_cast<const uint8_t *>(operation.family_envelope.data()),
          operation.family_envelope.size());
      if (family_code != MINEG_OK) {
        return cached_profile_or_error("KEY_ENVELOPE_INVALID", false);
      }
    }
    if (stored_key_material_complete) {
      const mineg_error_code_t request_code = issue_account_request_locked(
          operation, "KEY_GRANTS_LIST");
      if (request_code != MINEG_OK) return cached_profile_or_error("SESSION_INVALID", false);
      return account_operation_step_locked(operation, result);
    }
    const auto item = [](const std::string &name, const std::string &value) {
      return "{\"name\":\"" + name + "\",\"valueBase64\":\"" +
          base64_encode(value) + "\"}";
    };
    set_account_effect_locked(
        operation, "SecureStoreEffect",
        "{\"action\":\"writeSecrets\",\"values\":[" +
        item("keys.deviceWrapKey", operation.device_wrap_key) + ',' +
        item("keys.userPublicKey", operation.key_bundle_public) + ',' +
        item("keys.deviceUnlockBlob", operation.device_unlock_blob) + "]}",
        "WRITE_KEY_SECRETS");
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "WRITE_KEY_SECRETS") {
    const mineg_error_code_t request_code = issue_account_request_locked(
        operation, "KEY_GRANTS_LIST");
    if (request_code != MINEG_OK) return cached_profile_or_error("SESSION_INVALID", false);
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
  const auto problem = [this, &response_body, http_status]() {
    std::string code = sqlite_json_text(database_, response_body, "$.code");
    if (code.empty()) code = "SERVICE_UNAVAILABLE";
    const bool retryable = sqlite_json_boolean(database_, response_body, "$.retryable",
                                                http_status >= 500);
    return std::make_pair(code, retryable);
  };
  if (http_status < 200 || http_status >= 300) {
    const auto [code, retryable] = problem();
    if (retryable && http_status >= 500 && operation.effect_retry_count < 1) {
      ++operation.effect_retry_count;
      wipe_string(response_body);
      return account_operation_step_locked(operation, result);
    }
    const bool authorized_request = operation.stage == "TRANSPORT_REVIEW" ||
        operation.stage == "TRANSPORT_PROFILE_GET" ||
        operation.stage == "TRANSPORT_PROFILE_UPDATE" ||
        operation.stage == "TRANSPORT_KEY_BUNDLE" ||
        operation.stage == "TRANSPORT_KEY_GRANTS_LIST" ||
        operation.stage == "TRANSPORT_KEY_GRANT_COMPLETE" ||
        operation.stage == "TRANSPORT_PRIVATE_MEDIA_LIST" ||
        operation.stage == "TRANSPORT_AVATAR_CREATE" ||
        operation.stage == "TRANSPORT_AVATAR_COMPLETE";
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
    return cached_profile_or_error(code, retryable, request_id);
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
    if (!persist_current_profile_locked(response_body)) {
      wipe_string(response_body);
      return cached_profile_or_error("PROFILE_MISMATCH", false, request_id);
    }
    wipe_string(response_body);
    operation.status = "COMPLETED";
    operation.terminal_payload = read_current_profile_snapshot_locked();
    operation.clear_sensitive();
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_KEY_BUNDLE") {
    const std::string public_base64 = sqlite_json_text(database_, response_body, "$.public_key");
    const std::string bundle_base64 = sqlite_json_text(database_, response_body,
                                                       "$.encrypted_key_bundle");
    const std::string family_base64 = sqlite_json_text(database_, response_body,
                                                       "$.family_envelope");
    if (!base64_decode(public_base64, operation.key_bundle_public) ||
        operation.key_bundle_public.size() != MINEG_KEY_BYTES ||
        !base64_decode(bundle_base64, operation.key_bundle_encrypted) ||
        (!family_base64.empty() && !base64_decode(family_base64, operation.family_envelope))) {
      wipe_string(response_body);
      return cached_profile_or_error("KEY_BUNDLE_INVALID", false, request_id);
    }
    wipe_string(response_body);
    set_account_effect_locked(
        operation, "SecureStoreEffect",
        "{\"action\":\"readSecrets\",\"names\":[\"keys.deviceWrapKey\","
        "\"keys.userPublicKey\",\"keys.deviceUnlockBlob\"]}",
        "READ_KEY_SECRETS");
    return account_operation_step_locked(operation, result);
  }
  if (operation.stage == "TRANSPORT_KEY_GRANTS_LIST") {
    const int64_t count = sqlite_json_integer(database_, response_body, "$.items.#", -1);
    sqlite3_stmt *count_statement = nullptr;
    int64_t actual_count = -1;
    if (sqlite3_prepare_v2(database_, "SELECT json_array_length(?,'$.items')", -1,
                           &count_statement, nullptr) == SQLITE_OK) {
      int count_status = sqlite3_bind_text(count_statement, 1, response_body.c_str(),
                                           static_cast<int>(response_body.size()),
                                           SQLITE_TRANSIENT);
      if (count_status == SQLITE_OK && sqlite3_step(count_statement) == SQLITE_ROW) {
        actual_count = sqlite3_column_int64(count_statement, 0);
      }
    }
    sqlite3_finalize(count_statement);
    (void)count;
    if (actual_count < 0 || actual_count > 20) {
      wipe_string(response_body);
      return cached_profile_or_error("KEY_GRANT_RESPONSE_INVALID", false, request_id);
    }
    operation.pending_grants_json = "{\"itemCount\":" + std::to_string(actual_count) +
        ",\"items\":" + extract_top_level_json_value(response_body, "items") + "}";
    wipe_string(response_body);
    operation.grant_index = 0;
    operation.completed_grant_count = 0;
    return continue_key_grants();
  }
  if (operation.stage == "TRANSPORT_KEY_GRANT_COMPLETE") {
    const std::string completed_id = sqlite_json_text(database_, response_body, "$.grant_id");
    wipe_string(response_body);
    if (completed_id != operation.grant_id) {
      return cached_profile_or_error("KEY_GRANT_RESPONSE_INVALID", false, request_id);
    }
    ++operation.grant_index;
    ++operation.completed_grant_count;
    wipe_string(operation.grant_id);
    wipe_string(operation.recipient_public_key);
    wipe_string(operation.encrypted_envelope);
    return continue_key_grants();
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
    if (!persist_current_profile_locked(response_body)) {
      wipe_string(response_body);
      return cached_profile_or_error("PROFILE_MISMATCH", false, request_id);
    }
    wipe_string(response_body);
    operation.status = "COMPLETED";
    operation.terminal_payload = read_current_profile_snapshot_locked();
    operation.clear_sensitive();
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
  if (contract_version == "account-v2" || contract_version == "stage02-v2") {
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
    if (type == "GetAccountRouteSnapshot") {
      const std::string account = read_account_state_locked();
      const std::string user_id = sqlite_json_text(database_, account, "$.state.userId");
      const std::string approval = sqlite_json_text(database_, account, "$.state.approvalStatus");
      if (user_id.empty()) {
        result = "{\"contractVersion\":\"account-v2\",\"snapshot\":null}";
      } else {
        result = "{\"contractVersion\":\"account-v2\",\"snapshot\":{\"userId\":\"" +
            json_escape(user_id) + "\",\"approvalStatus\":\"" + approval +
            "\",\"nextStep\":\"" +
            (approval == "APPROVED" ? "APP_HOME" : "REVIEW_PENDING") + "\"}}";
      }
      return MINEG_OK;
    }
    if (type == "GetCurrentProfileSnapshot") {
      const std::string profile = read_current_profile_snapshot_locked();
      result = "{\"contractVersion\":\"account-v2\",\"snapshot\":" +
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

mineg_error_code_t Core::unlock_user_key_bundle(
    const uint8_t *password, size_t password_size, const uint8_t public_key[MINEG_KEY_BYTES],
    const uint8_t *encrypted_bundle, size_t encrypted_bundle_size,
    const uint8_t device_wrap_key[MINEG_KEY_BYTES], std::string &device_unlock_blob) {
  std::lock_guard<std::mutex> lock(mutex_);
  return unlock_user_key_bundle_locked(password, password_size, public_key, encrypted_bundle,
                                       encrypted_bundle_size, device_wrap_key,
                                       device_unlock_blob);
}

mineg_error_code_t Core::unlock_user_key_bundle_locked(
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
  lock_keys_locked();
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
    lock_keys_locked();
    std::copy(public_key, public_key + MINEG_KEY_BYTES, user_public_key_.begin());
    std::copy(plaintext.begin(), plaintext.begin() + MINEG_KEY_BYTES, user_private_key_.begin());
    std::copy(plaintext.begin() + MINEG_KEY_BYTES, plaintext.end(), user_master_key_.begin());
    user_keys_unlocked_ = true;
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
  std::lock_guard<std::mutex> lock(mutex_);
  return restore_user_key_bundle_locked(public_key, device_wrap_key, device_unlock_blob,
                                        device_unlock_blob_size);
}

mineg_error_code_t Core::restore_user_key_bundle_locked(
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
  lock_keys_locked();
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
  std::lock_guard<std::mutex> lock(mutex_);
  return unlock_family_key_envelope_locked(encrypted_envelope, encrypted_envelope_size);
}

mineg_error_code_t Core::unlock_family_key_envelope_locked(const uint8_t *encrypted_envelope,
                                                            size_t encrypted_envelope_size) {
  if (encrypted_envelope == nullptr || encrypted_envelope_size != MINEG_FAMILY_KEY_ENVELOPE_BYTES) {
    return MINEG_INVALID_ARGUMENT;
  }
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
  std::lock_guard<std::mutex> lock(mutex_);
  return create_family_key_envelope_locked(recipient_public_key, bootstrap_if_needed,
                                           encrypted_envelope);
}

mineg_error_code_t Core::create_family_key_envelope_locked(
    const uint8_t recipient_public_key[MINEG_KEY_BYTES], bool bootstrap_if_needed,
    std::string &encrypted_envelope) {
  if (recipient_public_key == nullptr) return MINEG_INVALID_ARGUMENT;
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
