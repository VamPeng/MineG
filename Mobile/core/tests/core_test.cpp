#include "mineg/mineg_core.h"
#include "sodium_compat.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

#include <fcntl.h>
#include <unistd.h>

namespace {

std::string as_string(const mineg_buffer_t &buffer) {
  return {reinterpret_cast<const char *>(buffer.data), buffer.size};
}

void expect(mineg_error_code_t actual, mineg_error_code_t expected, const char *step) {
  if (actual != expected) {
    std::cerr << step << " returned " << actual << ", expected " << expected << '\n';
    std::abort();
  }
}

void event_callback(const uint8_t *bytes, size_t size, void *user_data) {
  auto *event = static_cast<std::string *>(user_data);
  event->assign(reinterpret_cast<const char *>(bytes), size);
}

void write_plaintext(const std::filesystem::path &path) {
  std::ofstream output(path, std::ios::binary);
  for (size_t index = 0; index < 1024U * 1024U + 37U; ++index) {
    output.put(static_cast<char>((index * 31U) & 0xffU));
  }
}

void write_media_plaintext(const std::filesystem::path &path) {
  std::ofstream output(path, std::ios::binary);
  for (size_t index = 0; index < 8U * 1024U * 1024U + 37U; ++index) {
    output.put(static_cast<char>((index * 17U + 29U) & 0xffU));
  }
}

std::string json_string(const std::string &json, const std::string &field) {
  const std::string marker = "\"" + field + "\"";
  const size_t start = json.find(marker);
  assert(start != std::string::npos);
  const size_t colon = json.find(':', start + marker.size());
  assert(colon != std::string::npos);
  const size_t quote = json.find('"', colon + 1U);
  assert(quote != std::string::npos);
  const size_t value = quote + 1U;
  const size_t end = json.find('"', value);
  assert(end != std::string::npos);
  return json.substr(value, end - value);
}

std::array<uint8_t, MINEG_MEDIA_NONCE_PREFIX_BYTES> decode_nonce_prefix(
    const std::string &hex) {
  assert(hex.size() == MINEG_MEDIA_NONCE_PREFIX_BYTES * 2U);
  std::array<uint8_t, MINEG_MEDIA_NONCE_PREFIX_BYTES> result{};
  const auto nibble = [](char value) -> uint8_t {
    if (value >= '0' && value <= '9') return static_cast<uint8_t>(value - '0');
    if (value >= 'a' && value <= 'f') return static_cast<uint8_t>(value - 'a' + 10);
    std::abort();
  };
  for (size_t index = 0; index < result.size(); ++index) {
    result[index] = static_cast<uint8_t>((nibble(hex[index * 2U]) << 4U) |
                                         nibble(hex[index * 2U + 1U]));
  }
  return result;
}

size_t count_occurrences(const std::string &value, const std::string &needle) {
  size_t count = 0;
  size_t offset = 0;
  while ((offset = value.find(needle, offset)) != std::string::npos) {
    ++count;
    offset += needle.size();
  }
  return count;
}

std::string read_all(const std::filesystem::path &path) {
  std::ifstream input(path, std::ios::binary);
  return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::vector<uint8_t> decode_hex(const std::string &hex) {
  assert(hex.size() % 2U == 0U);
  const auto nibble = [](char value) -> uint8_t {
    if (value >= '0' && value <= '9') return static_cast<uint8_t>(value - '0');
    if (value >= 'a' && value <= 'f') return static_cast<uint8_t>(value - 'a' + 10);
    std::abort();
  };
  std::vector<uint8_t> result(hex.size() / 2U);
  for (size_t index = 0; index < result.size(); ++index) {
    result[index] = static_cast<uint8_t>((nibble(hex[index * 2U]) << 4U) |
                                         nibble(hex[index * 2U + 1U]));
  }
  return result;
}

std::string encode_base64(const std::string &value) {
  static constexpr char kAlphabet[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string result;
  for (size_t index = 0; index < value.size(); index += 3U) {
    const uint32_t packet = static_cast<uint32_t>(static_cast<uint8_t>(value[index])) << 16U |
        (index + 1U < value.size()
             ? static_cast<uint32_t>(static_cast<uint8_t>(value[index + 1U])) << 8U
             : 0U) |
        (index + 2U < value.size()
             ? static_cast<uint32_t>(static_cast<uint8_t>(value[index + 2U]))
             : 0U);
    result += kAlphabet[(packet >> 18U) & 0x3fU];
    result += kAlphabet[(packet >> 12U) & 0x3fU];
    result += index + 1U < value.size() ? kAlphabet[(packet >> 6U) & 0x3fU] : '=';
    result += index + 2U < value.size() ? kAlphabet[packet & 0x3fU] : '=';
  }
  return result;
}

std::string effect_result(uint64_t operation_id, uint64_t sequence,
                          const std::string &effect_type, const std::string &payload) {
  return "{\"contractVersion\":\"foundation-v2\",\"operationId\":" +
      std::to_string(operation_id) + ",\"sequence\":" + std::to_string(sequence) +
      ",\"effectType\":\"" + effect_type +
      "\",\"status\":\"SUCCEEDED\",\"payload\":" + payload + "}";
}

std::string transport_result(uint64_t operation_id, uint64_t sequence, int status,
                             const std::string &body) {
  return effect_result(
      operation_id, sequence, "TransportEffect",
      "{\"status\":" + std::to_string(status) +
          ",\"contentType\":\"application/json\",\"requestId\":\"request-test\","
          "\"bodyBase64\":\"" + encode_base64(body) + "\"}");
}

void verify_media_encryption_vector() {
  const auto vector_path =
      std::filesystem::path(MINEG_CORE_SOURCE_DIR) / "test-vectors/media-encryption-v1.json";
  const std::string fixture = read_all(vector_path);
  const auto key = decode_hex(json_string(fixture, "resourceKeyHex"));
  const auto nonce = decode_hex(json_string(fixture, "nonceHex"));
  const auto aad = decode_hex(json_string(fixture, "aadHex"));
  const auto plaintext = decode_hex(json_string(fixture, "plaintextHex"));
  const auto expected = decode_hex(json_string(fixture, "ciphertextHex"));
  assert(key.size() == 32U);
  assert(nonce.size() == crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
  std::vector<uint8_t> ciphertext(plaintext.size() + crypto_aead_xchacha20poly1305_ietf_ABYTES);
  unsigned long long ciphertext_size = 0;
  assert(crypto_aead_xchacha20poly1305_ietf_encrypt(
             ciphertext.data(), &ciphertext_size, plaintext.data(), plaintext.size(), aad.data(),
             aad.size(), nullptr, nonce.data(), key.data()) == 0);
  ciphertext.resize(static_cast<size_t>(ciphertext_size));
  assert(ciphertext == expected);
}

}  // namespace

int main() {
  verify_media_encryption_vector();
  assert(mineg_abi_version() == 5U);
  const auto root = std::filesystem::temp_directory_path() /
                    ("mineg-core-test-" + std::to_string(static_cast<long long>(::getpid())));
  std::filesystem::create_directories(root);
  const auto database = root / "core.db";

  {
    const auto operation_database = root / "operations.db";
    mineg_core_t *operation_core = nullptr;
    expect(mineg_core_create(operation_database.c_str(), &operation_core), MINEG_OK,
           "create operation core");
    const std::string start_command =
        R"({"contractVersion":"foundation-v2","type":"FoundationEffectProbe","effectType":"TransportEffect","payload":{"action":"sendApiRequest","method":"GET","path":"/foundation/probe"}})";
    mineg_buffer_t operation_step{};
    expect(mineg_core_start_operation(
               operation_core, 9001,
               reinterpret_cast<const uint8_t *>(start_command.data()), start_command.size(),
               &operation_step),
           MINEG_OK, "start recoverable effect operation");
    assert(as_string(operation_step).find("\"status\":\"WAITING_FOR_EFFECT\"") !=
           std::string::npos);
    assert(as_string(operation_step).find("\"sequence\":1") != std::string::npos);
    mineg_buffer_free(&operation_step);

    expect(mineg_core_start_operation(
               operation_core, 9001,
               reinterpret_cast<const uint8_t *>(start_command.data()), start_command.size(),
               &operation_step),
           MINEG_OK, "idempotent operation start");
    mineg_buffer_free(&operation_step);
    const std::string conflicting_command =
        R"({"contractVersion":"foundation-v2","type":"FoundationEffectProbe","effectType":"FileEffect","payload":{"action":"getAvailableSpace"}})";
    expect(mineg_core_start_operation(
               operation_core, 9001,
               reinterpret_cast<const uint8_t *>(conflicting_command.data()),
               conflicting_command.size(), &operation_step),
           MINEG_INVALID_ARGUMENT, "reject reused operation id with different command");

    mineg_buffer_t recovered{};
    expect(mineg_core_recover_operations(operation_core, &recovered), MINEG_OK,
           "recover pending operation");
    assert(as_string(recovered).find("\"operationId\":9001") != std::string::npos);
    mineg_buffer_free(&recovered);
    mineg_core_close(operation_core);
    operation_core = nullptr;

    expect(mineg_core_create(operation_database.c_str(), &operation_core), MINEG_OK,
           "reopen operation core");
    expect(mineg_core_recover_operations(operation_core, &recovered), MINEG_OK,
           "recover operation after process restart");
    assert(as_string(recovered).find("\"effectType\":\"TransportEffect\"") !=
           std::string::npos);
    mineg_buffer_free(&recovered);

    const std::string wrong_sequence =
        R"({"contractVersion":"foundation-v2","operationId":9001,"sequence":2,"effectType":"TransportEffect","status":"SUCCEEDED","payload":{}})";
    expect(mineg_core_resume_operation(
               operation_core, 9001,
               reinterpret_cast<const uint8_t *>(wrong_sequence.data()), wrong_sequence.size(),
               &operation_step),
           MINEG_INVALID_ARGUMENT, "reject out of sequence effect result");
    const std::string success_result =
        R"({"contractVersion":"foundation-v2","operationId":9001,"sequence":1,"effectType":"TransportEffect","status":"SUCCEEDED","payload":{"status":204}})";
    expect(mineg_core_resume_operation(
               operation_core, 9001,
               reinterpret_cast<const uint8_t *>(success_result.data()), success_result.size(),
               &operation_step),
           MINEG_OK, "resume effect operation");
    assert(as_string(operation_step).find("\"status\":\"COMPLETED\"") != std::string::npos);
    assert(as_string(operation_step).find("\"status\":204") != std::string::npos);
    mineg_buffer_free(&operation_step);
    expect(mineg_core_resume_operation(
               operation_core, 9001,
               reinterpret_cast<const uint8_t *>(success_result.data()), success_result.size(),
               &operation_step),
           MINEG_OK, "idempotent effect result");
    mineg_buffer_free(&operation_step);

    const std::string retry_command =
        R"({"contractVersion":"foundation-v2","type":"FoundationEffectProbe","effectType":"SecureStoreEffect","maxRetries":1,"payload":{"action":"readSecret","name":"session"}})";
    expect(mineg_core_start_operation(
               operation_core, 9003,
               reinterpret_cast<const uint8_t *>(retry_command.data()), retry_command.size(),
               &operation_step),
           MINEG_OK, "start retryable operation");
    mineg_buffer_free(&operation_step);
    const std::string retryable_failure =
        R"({"contractVersion":"foundation-v2","operationId":9003,"sequence":1,"effectType":"SecureStoreEffect","status":"FAILED","error":{"code":"PLATFORM_IO_ERROR","retryable":true}})";
    expect(mineg_core_resume_operation(
               operation_core, 9003,
               reinterpret_cast<const uint8_t *>(retryable_failure.data()),
               retryable_failure.size(), &operation_step),
           MINEG_OK, "retry a retryable effect failure");
    assert(as_string(operation_step).find("\"status\":\"WAITING_FOR_EFFECT\"") !=
           std::string::npos);
    assert(as_string(operation_step).find("\"sequence\":2") != std::string::npos);
    mineg_buffer_free(&operation_step);
    expect(mineg_core_resume_operation(
               operation_core, 9003,
               reinterpret_cast<const uint8_t *>(retryable_failure.data()),
               retryable_failure.size(), &operation_step),
           MINEG_OK, "idempotent retryable effect result");
    assert(as_string(operation_step).find("\"sequence\":2") != std::string::npos);
    mineg_buffer_free(&operation_step);
    const std::string retry_success =
        R"({"contractVersion":"foundation-v2","operationId":9003,"sequence":2,"effectType":"SecureStoreEffect","status":"SUCCEEDED","payload":{"valueBase64":null}})";
    expect(mineg_core_resume_operation(
               operation_core, 9003,
               reinterpret_cast<const uint8_t *>(retry_success.data()), retry_success.size(),
               &operation_step),
           MINEG_OK, "complete retried operation");
    assert(as_string(operation_step).find("\"status\":\"COMPLETED\"") != std::string::npos);
    mineg_buffer_free(&operation_step);

    const std::string empty_result = "{}";
    expect(mineg_core_resume_operation(
               operation_core, 9003,
               reinterpret_cast<const uint8_t *>(empty_result.data()), empty_result.size(),
               &operation_step),
           MINEG_INVALID_ARGUMENT, "reject empty effect result");

    expect(mineg_core_start_operation(
               operation_core, 9002,
               reinterpret_cast<const uint8_t *>(conflicting_command.data()),
               conflicting_command.size(), &operation_step),
           MINEG_OK, "start cancellable operation");
    mineg_buffer_free(&operation_step);
    expect(mineg_core_cancel(operation_core, 9002), MINEG_OK, "persist operation cancellation");
    expect(mineg_core_recover_operations(operation_core, &recovered), MINEG_OK,
           "cancelled operation is not recovered");
    assert(as_string(recovered).find("\"operationId\":9002") == std::string::npos);
    mineg_buffer_free(&recovered);
    mineg_core_close(operation_core);
  }

  {
    const auto account_database = root / "account-v2.db";
    mineg_core_t *account_core = nullptr;
    expect(mineg_core_create(account_database.c_str(), &account_core), MINEG_OK,
           "create account v2 core");
    const std::string sign_up =
        R"({"contractVersion":"account-v2","type":"AccountSignUp","phone":"13800138000","password":"Password1","idempotencyKey":"registration-001"})";
    mineg_buffer_t step{};
    expect(mineg_core_start_operation(account_core, 9099,
                                      reinterpret_cast<const uint8_t *>(sign_up.data()),
                                      sign_up.size(), &step),
           MINEG_OK, "start account sign up in core");
    mineg_buffer_free(&step);
    const std::string sign_up_device = effect_result(
        9099, 1, "SecureStoreEffect",
        "{\"values\":[{\"name\":\"device.installationId\",\"valueBase64\":\"" +
            encode_base64("device-001") + "\"}]}");
    expect(mineg_core_resume_operation(
               account_core, 9099,
               reinterpret_cast<const uint8_t *>(sign_up_device.data()),
               sign_up_device.size(), &step),
           MINEG_OK, "build registration protocol in core");
    assert(as_string(step).find("/api/v1/auth/register") != std::string::npos);
    assert(as_string(step).find("Password1") == std::string::npos);
    assert(as_string(step).find("public_key") == std::string::npos);
    mineg_buffer_free(&step);
    expect(mineg_core_cancel(account_core, 9099), MINEG_OK,
           "cancel transient registration operation");

    const std::string sign_in =
        R"({"contractVersion":"account-v2","type":"AccountSignIn","phone":"13800138000","password":"Password1","agreementAccepted":true})";
    expect(mineg_core_start_operation(account_core, 9100,
                                      reinterpret_cast<const uint8_t *>(sign_in.data()),
                                      sign_in.size(), &step),
           MINEG_OK, "start account sign in");
    assert(as_string(step).find("\"effectType\":\"SecureStoreEffect\"") != std::string::npos);
    assert(as_string(step).find("Password1") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string device_result = effect_result(
        9100, 1, "SecureStoreEffect",
        "{\"values\":[{\"name\":\"device.installationId\",\"valueBase64\":\"" +
            encode_base64("device-001") + "\"}]}");
    expect(mineg_core_resume_operation(
               account_core, 9100, reinterpret_cast<const uint8_t *>(device_result.data()),
               device_result.size(), &step),
           MINEG_OK, "supply installation id");
    assert(as_string(step).find("/api/v1/auth/login") != std::string::npos);
    assert(as_string(step).find("Password1") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string session_body =
        R"({"user_id":"user-v2","access_token":"access-secret-v1","access_expires_at":"2000-07-30T12:15:00Z","refresh_token":"refresh-secret-v1","refresh_expires_at":"2026-08-30T12:00:00Z","approval_status":"APPROVED","next_step":"APP_HOME"})";
    const std::string sign_in_transport = transport_result(9100, 2, 200, session_body);
    expect(mineg_core_resume_operation(
               account_core, 9100,
               reinterpret_cast<const uint8_t *>(sign_in_transport.data()),
               sign_in_transport.size(), &step),
           MINEG_OK, "parse sign in response in core");
    assert(as_string(step).find("\"action\":\"writeSecrets\"") != std::string::npos);
    assert(as_string(step).find("access-secret-v1") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string secrets_written =
        effect_result(9100, 3, "SecureStoreEffect", "{\"written\":true}");
    expect(mineg_core_resume_operation(
               account_core, 9100,
               reinterpret_cast<const uint8_t *>(secrets_written.data()),
               secrets_written.size(), &step),
           MINEG_OK, "complete secure session write");
    assert(as_string(step).find("\"status\":\"COMPLETED\"") != std::string::npos);
    assert(as_string(step).find("\"userId\":\"user-v2\"") != std::string::npos);
    assert(as_string(step).find("access-secret-v1") == std::string::npos);
    mineg_buffer_free(&step);

    const std::string profile_get =
        R"({"contractVersion":"account-v2","type":"ProfileGetCurrent","allowCached":true})";
    expect(mineg_core_start_operation(account_core, 9101,
                                      reinterpret_cast<const uint8_t *>(profile_get.data()),
                                      profile_get.size(), &step),
           MINEG_OK, "start profile query");
    assert(as_string(step).find("/api/v1/me") != std::string::npos);
    mineg_buffer_free(&step);
    const std::string profile_body =
        R"({"id":"user-v2","nickname":"Mine G","masked_phone":"138****8000","avatar_url":"https://objects.invalid/avatar","version":3})";
    const std::string profile_response = transport_result(9101, 1, 200, profile_body);
    expect(mineg_core_resume_operation(
               account_core, 9101,
               reinterpret_cast<const uint8_t *>(profile_response.data()),
               profile_response.size(), &step),
           MINEG_OK, "persist core profile snapshot");
    assert(as_string(step).find("\"nickname\":\"Mine G\"") != std::string::npos);
    mineg_buffer_free(&step);

    const std::string profile_query =
        R"({"version":2,"type":"GetCurrentProfileSnapshot"})";
    expect(mineg_core_query(account_core,
                            reinterpret_cast<const uint8_t *>(profile_query.data()),
                            profile_query.size(), &step),
           MINEG_OK, "query current profile snapshot");
    assert(as_string(step).find("\"userId\":\"user-v2\"") == std::string::npos);
    assert(as_string(step).find("\"id\":\"user-v2\"") != std::string::npos);
    mineg_buffer_free(&step);
    mineg_core_close(account_core);
    account_core = nullptr;

    expect(mineg_core_create(account_database.c_str(), &account_core), MINEG_OK,
           "reopen account v2 core");
    const std::string restore =
        R"({"contractVersion":"account-v2","type":"AccountRestoreSession"})";
    expect(mineg_core_start_operation(account_core, 9102,
                                      reinterpret_cast<const uint8_t *>(restore.data()),
                                      restore.size(), &step),
           MINEG_OK, "start session restore");
    mineg_buffer_free(&step);
    const std::string restore_secrets = effect_result(
        9102, 1, "SecureStoreEffect",
        "{\"values\":["
        "{\"name\":\"account.accessToken\",\"valueBase64\":\"" +
            encode_base64("access-secret-v1") + "\"},"
        "{\"name\":\"account.refreshToken\",\"valueBase64\":\"" +
            encode_base64("refresh-secret-v1") + "\"},"
        "{\"name\":\"account.accessExpiresAt\",\"valueBase64\":\"" +
            encode_base64("2000-07-30T12:15:00Z") + "\"},"
        "{\"name\":\"account.refreshExpiresAt\",\"valueBase64\":\"" +
            encode_base64("2026-08-30T12:00:00Z") + "\"},"
        "{\"name\":\"device.installationId\",\"valueBase64\":\"" +
            encode_base64("device-001") + "\"}]}");
    expect(mineg_core_resume_operation(
               account_core, 9102,
               reinterpret_cast<const uint8_t *>(restore_secrets.data()),
               restore_secrets.size(), &step),
           MINEG_OK, "restore secrets into controlled core memory");
    assert(as_string(step).find("/api/v1/auth/refresh") != std::string::npos);
    assert(as_string(step).find("refresh-secret-v1") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string rotated_body =
        R"({"user_id":"user-v2","access_token":"access-secret-v2","access_expires_at":"2026-07-30T12:30:00Z","refresh_token":"refresh-secret-v2","refresh_expires_at":"2026-08-30T12:15:00Z","approval_status":"APPROVED","next_step":"APP_HOME"})";
    const std::string refresh_response = transport_result(9102, 2, 200, rotated_body);
    expect(mineg_core_resume_operation(
               account_core, 9102,
               reinterpret_cast<const uint8_t *>(refresh_response.data()),
               refresh_response.size(), &step),
           MINEG_OK, "rotate restored session");
    mineg_buffer_free(&step);
    const std::string rotated_written =
        effect_result(9102, 3, "SecureStoreEffect", "{\"written\":true}");
    expect(mineg_core_resume_operation(
               account_core, 9102,
               reinterpret_cast<const uint8_t *>(rotated_written.data()),
               rotated_written.size(), &step),
           MINEG_OK, "complete restored session");
    assert(as_string(step).find("\"status\":\"COMPLETED\"") != std::string::npos);
    mineg_buffer_free(&step);

    expect(mineg_core_start_operation(account_core, 9103,
                                      reinterpret_cast<const uint8_t *>(profile_get.data()),
                                      profile_get.size(), &step),
           MINEG_OK, "start cache fallback profile query");
    mineg_buffer_free(&step);
    const std::string network_failure =
        R"({"contractVersion":"foundation-v2","operationId":9103,"sequence":1,"effectType":"TransportEffect","status":"FAILED","error":{"code":"PLATFORM_IO_ERROR","retryable":true}})";
    expect(mineg_core_resume_operation(
               account_core, 9103,
               reinterpret_cast<const uint8_t *>(network_failure.data()),
               network_failure.size(), &step),
           MINEG_OK, "core retries a retryable profile transport failure");
    assert(as_string(step).find("\"status\":\"WAITING_FOR_EFFECT\"") != std::string::npos);
    assert(as_string(step).find("\"sequence\":2") != std::string::npos);
    mineg_buffer_free(&step);
    const std::string second_network_failure =
        R"({"contractVersion":"foundation-v2","operationId":9103,"sequence":2,"effectType":"TransportEffect","status":"FAILED","error":{"code":"PLATFORM_IO_ERROR","retryable":true}})";
    expect(mineg_core_resume_operation(
               account_core, 9103,
               reinterpret_cast<const uint8_t *>(second_network_failure.data()),
               second_network_failure.size(), &step),
           MINEG_OK, "core chooses account-isolated profile fallback after retry");
    assert(as_string(step).find("\"nickname\":\"Mine G\"") != std::string::npos);
    assert(as_string(step).find("\"status\":\"COMPLETED\"") != std::string::npos);
    mineg_buffer_free(&step);

    const std::string stage02_password = "Password1";
    mineg_buffer_t stage02_public{};
    mineg_buffer_t stage02_bundle{};
    mineg_buffer_t stage02_kdf{};
    expect(mineg_core_create_user_key_bundle(
               reinterpret_cast<const uint8_t *>(stage02_password.data()),
               stage02_password.size(), &stage02_public, &stage02_bundle, &stage02_kdf),
           MINEG_OK, "create stage02 key material");
    const std::string stage02_public_bytes(
        reinterpret_cast<const char *>(stage02_public.data), stage02_public.size);
    const std::string stage02_bundle_bytes(
        reinterpret_cast<const char *>(stage02_bundle.data), stage02_bundle.size);
    const std::string coordinate =
        R"({"contractVersion":"stage02-v2","type":"CoordinateFamilyKeyGrants","password":"Password1"})";
    expect(mineg_core_start_operation(account_core, 9200,
                                      reinterpret_cast<const uint8_t *>(coordinate.data()),
                                      coordinate.size(), &step),
           MINEG_OK, "start core key grant coordination");
    assert(as_string(step).find("/api/v1/me/key-bundle") != std::string::npos);
    assert(as_string(step).find("Password1") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string key_bundle_body =
        "{\"public_key\":\"" + encode_base64(stage02_public_bytes) +
        "\",\"encrypted_key_bundle\":\"" + encode_base64(stage02_bundle_bytes) +
        "\",\"kdf_parameters\":{},\"bundle_version\":1,\"family_envelope\":null}";
    const std::string key_bundle_result = transport_result(9200, 1, 200, key_bundle_body);
    expect(mineg_core_resume_operation(
               account_core, 9200,
               reinterpret_cast<const uint8_t *>(key_bundle_result.data()),
               key_bundle_result.size(), &step),
           MINEG_OK, "parse key bundle in core");
    assert(as_string(step).find("keys.deviceWrapKey") != std::string::npos);
    mineg_buffer_free(&step);
    const std::string missing_key_secrets = effect_result(
        9200, 2, "SecureStoreEffect",
        R"({"values":[{"name":"keys.deviceWrapKey","valueBase64":null},{"name":"keys.userPublicKey","valueBase64":null},{"name":"keys.deviceUnlockBlob","valueBase64":null}]})");
    expect(mineg_core_resume_operation(
               account_core, 9200,
               reinterpret_cast<const uint8_t *>(missing_key_secrets.data()),
               missing_key_secrets.size(), &step),
           MINEG_OK, "unlock key bundle and request atomic key storage");
    assert(as_string(step).find("\"action\":\"writeSecrets\"") != std::string::npos);
    assert(as_string(step).find("Password1") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string key_secrets_written =
        effect_result(9200, 3, "SecureStoreEffect", R"({"written":true})");
    expect(mineg_core_resume_operation(
               account_core, 9200,
               reinterpret_cast<const uint8_t *>(key_secrets_written.data()),
               key_secrets_written.size(), &step),
           MINEG_OK, "list pending grants after key unlock");
    assert(as_string(step).find("/api/v1/key-grants/pending?limit=20") != std::string::npos);
    mineg_buffer_free(&step);
    const std::string pending_grants =
        "{\"items\":[{\"id\":\"grant-001\",\"user_id\":\"user-v2\","
        "\"family_id\":\"family-001\",\"kind\":\"FAMILY_BOOTSTRAP\","
        "\"recipient_public_key\":\"" + encode_base64(stage02_public_bytes) +
        "\",\"bundle_version\":1,\"created_at\":\"2026-07-30T00:00:00Z\"}]}";
    const std::string pending_result = transport_result(9200, 4, 200, pending_grants);
    expect(mineg_core_resume_operation(
               account_core, 9200, reinterpret_cast<const uint8_t *>(pending_result.data()),
               pending_result.size(), &step),
           MINEG_OK, "create family envelope and request grant completion");
    assert(as_string(step).find("/api/v1/key-grants/grant-001/complete") != std::string::npos);
    assert(as_string(step).find("encrypted_envelope") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string grant_completed = transport_result(
        9200, 5, 200,
        R"({"grant_id":"grant-001","user_id":"user-v2","outcome":"COMPLETED","status":"APPROVED"})");
    expect(mineg_core_resume_operation(
               account_core, 9200, reinterpret_cast<const uint8_t *>(grant_completed.data()),
               grant_completed.size(), &step),
           MINEG_OK, "complete key grant coordination");
    assert(as_string(step).find("\"completedCount\":1") != std::string::npos);
    mineg_buffer_free(&step);
    mineg_buffer_free(&stage02_public);
    mineg_buffer_free(&stage02_bundle);
    mineg_buffer_free(&stage02_kdf);

    const std::string media_list =
        R"({"contractVersion":"stage02-v2","type":"PrivateMediaList","limit":100,"allowCached":true})";
    expect(mineg_core_start_operation(account_core, 9201,
                                      reinterpret_cast<const uint8_t *>(media_list.data()),
                                      media_list.size(), &step),
           MINEG_OK, "start private media list");
    assert(as_string(step).find("/api/v1/media?limit=100") != std::string::npos);
    mineg_buffer_free(&step);
    const std::string media_page =
        R"({"items":[{"id":"media-001","media_type":"PHOTO","content_revision":2,"captured_at":"2026-07-29T12:00:00Z","created_at":"2026-07-30T00:00:00Z"}]})";
    const std::string media_result = transport_result(9201, 1, 200, media_page);
    expect(mineg_core_resume_operation(
               account_core, 9201, reinterpret_cast<const uint8_t *>(media_result.data()),
               media_result.size(), &step),
           MINEG_OK, "persist private media snapshot");
    assert(as_string(step).find("\"mediaType\":\"PHOTO\"") != std::string::npos);
    mineg_buffer_free(&step);
    const std::string media_query =
        R"({"contractVersion":"stage02-v2","type":"ListPrivateMediaSnapshot","limit":100})";
    expect(mineg_core_query(account_core,
                            reinterpret_cast<const uint8_t *>(media_query.data()),
                            media_query.size(), &step),
           MINEG_OK, "query private media snapshot");
    assert(as_string(step).find("media-001") != std::string::npos);
    mineg_buffer_free(&step);

    expect(mineg_core_start_operation(account_core, 9203,
                                      reinterpret_cast<const uint8_t *>(media_list.data()),
                                      media_list.size(), &step),
           MINEG_OK, "start private media cache fallback");
    mineg_buffer_free(&step);
    const std::string media_network_failure =
        R"({"contractVersion":"foundation-v2","operationId":9203,"sequence":1,"effectType":"TransportEffect","status":"FAILED","error":{"code":"PLATFORM_IO_ERROR","retryable":true}})";
    expect(mineg_core_resume_operation(
               account_core, 9203,
               reinterpret_cast<const uint8_t *>(media_network_failure.data()),
               media_network_failure.size(), &step),
           MINEG_OK, "retry private media transport");
    mineg_buffer_free(&step);
    const std::string media_second_failure =
        R"({"contractVersion":"foundation-v2","operationId":9203,"sequence":2,"effectType":"TransportEffect","status":"FAILED","error":{"code":"PLATFORM_IO_ERROR","retryable":true}})";
    expect(mineg_core_resume_operation(
               account_core, 9203,
               reinterpret_cast<const uint8_t *>(media_second_failure.data()),
               media_second_failure.size(), &step),
           MINEG_OK, "return account isolated private media cache");
    assert(as_string(step).find("media-001") != std::string::npos);
    assert(as_string(step).find("\"status\":\"COMPLETED\"") != std::string::npos);
    mineg_buffer_free(&step);

    const std::string avatar =
        R"({"contractVersion":"stage02-v2","type":"ProfileUpdateAvatar","displayBase64":"YXZhdGFy","sourceSize":6,"width":1,"contentType":"image/webp","idempotencyKey":"avatar-001"})";
    expect(mineg_core_start_operation(account_core, 9202,
                                      reinterpret_cast<const uint8_t *>(avatar.data()),
                                      avatar.size(), &step),
           MINEG_OK, "start avatar update");
    assert(as_string(step).find("/api/v1/me/avatar/uploads") != std::string::npos);
    assert(as_string(step).find("YXZhdGFy") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string avatar_created = transport_result(
        9202, 1, 201,
        R"({"upload_id":"avatar-upload-001","grant":{"url":"https://objects.invalid/avatar-upload","method":"PUT","expires_at":"2026-07-30T00:10:00Z","headers":{"Content-Type":"image/webp"}}})");
    expect(mineg_core_resume_operation(
               account_core, 9202, reinterpret_cast<const uint8_t *>(avatar_created.data()),
               avatar_created.size(), &step),
           MINEG_OK, "emit avatar object transport effect");
    assert(as_string(step).find("\"action\":\"uploadObject\"") != std::string::npos);
    assert(as_string(step).find("HttpURLConnection") == std::string::npos);
    mineg_buffer_free(&step);
    const std::string avatar_uploaded =
        effect_result(9202, 2, "TransportEffect", R"({"status":200})");
    expect(mineg_core_resume_operation(
               account_core, 9202, reinterpret_cast<const uint8_t *>(avatar_uploaded.data()),
               avatar_uploaded.size(), &step),
           MINEG_OK, "confirm avatar object upload");
    assert(as_string(step).find("/api/v1/me/avatar/uploads/avatar-upload-001/complete") !=
           std::string::npos);
    mineg_buffer_free(&step);
    const std::string avatar_profile =
        R"({"id":"user-v2","nickname":"Mine G","masked_phone":"138****8000","avatar_url":"https://objects.invalid/avatar-v2","version":4})";
    const std::string avatar_completed = transport_result(9202, 3, 200, avatar_profile);
    expect(mineg_core_resume_operation(
               account_core, 9202, reinterpret_cast<const uint8_t *>(avatar_completed.data()),
               avatar_completed.size(), &step),
           MINEG_OK, "persist avatar profile snapshot");
    assert(as_string(step).find("avatar-v2") != std::string::npos);
    mineg_buffer_free(&step);

    const std::string sign_out =
        R"({"contractVersion":"account-v2","type":"AccountSignOut"})";
    expect(mineg_core_start_operation(account_core, 9104,
                                      reinterpret_cast<const uint8_t *>(sign_out.data()),
                                      sign_out.size(), &step),
           MINEG_OK, "start account sign out");
    mineg_buffer_free(&step);
    const std::string sign_out_secrets = effect_result(
        9104, 1, "SecureStoreEffect",
        "{\"values\":[{\"name\":\"account.refreshToken\",\"valueBase64\":\"" +
            encode_base64("refresh-secret-v2") + "\"}]}");
    expect(mineg_core_resume_operation(
               account_core, 9104,
               reinterpret_cast<const uint8_t *>(sign_out_secrets.data()),
               sign_out_secrets.size(), &step),
           MINEG_OK, "read refresh token for sign out");
    mineg_buffer_free(&step);
    const std::string logout_response = transport_result(9104, 2, 200, R"({"status":"signed_out"})");
    expect(mineg_core_resume_operation(
               account_core, 9104,
               reinterpret_cast<const uint8_t *>(logout_response.data()),
               logout_response.size(), &step),
           MINEG_OK, "confirm server sign out");
    assert(as_string(step).find("BackgroundSchedulerEffect") != std::string::npos);
    mineg_buffer_free(&step);
    const std::string scheduler_cancelled =
        effect_result(9104, 3, "BackgroundSchedulerEffect", "{\"cancelled\":true}");
    expect(mineg_core_resume_operation(
               account_core, 9104,
               reinterpret_cast<const uint8_t *>(scheduler_cancelled.data()),
               scheduler_cancelled.size(), &step),
           MINEG_OK, "cancel scheduler before deleting session");
    assert(as_string(step).find("deleteSecrets") != std::string::npos);
    mineg_buffer_free(&step);
    const std::string secrets_deleted =
        effect_result(9104, 4, "SecureStoreEffect", "{\"deleted\":true}");
    expect(mineg_core_resume_operation(
               account_core, 9104,
               reinterpret_cast<const uint8_t *>(secrets_deleted.data()),
               secrets_deleted.size(), &step),
           MINEG_OK, "complete account sign out");
    assert(as_string(step).find("\"signedOut\":true") != std::string::npos);
    mineg_buffer_free(&step);
    expect(mineg_core_query(account_core,
                            reinterpret_cast<const uint8_t *>(media_query.data()),
                            media_query.size(), &step),
           MINEG_OK, "private media query closes after sign out");
    assert(as_string(step).find("\"snapshot\":null") != std::string::npos);
    assert(as_string(step).find("media-001") == std::string::npos);
    mineg_buffer_free(&step);
    mineg_core_close(account_core);

    const std::string database_bytes = read_all(account_database);
    assert(database_bytes.find("access-secret-v1") == std::string::npos);
    assert(database_bytes.find("refresh-secret-v1") == std::string::npos);
    assert(database_bytes.find("access-secret-v2") == std::string::npos);
    assert(database_bytes.find("refresh-secret-v2") == std::string::npos);
  }

  for (int iteration = 0; iteration < 50; ++iteration) {
    mineg_core_t *lifecycle = nullptr;
    expect(mineg_core_create(database.c_str(), &lifecycle), MINEG_OK, "repeat create");
    mineg_core_close(lifecycle);
  }

  mineg_core_t *core = nullptr;
  expect(mineg_core_create(database.c_str(), &core), MINEG_OK, "create");
  std::string event;
  uint64_t subscription = 0;
  expect(mineg_core_subscribe(core, event_callback, &event, &subscription), MINEG_OK, "subscribe");

  const std::string account_command =
      R"({"version":1,"type":"PersistAccountState","userId":"user-001","maskedPhone":"138****8000","approvalStatus":"PENDING","updatedAt":"2026-07-26T00:00:00.000Z"})";
  mineg_buffer_t result{};
  expect(mineg_core_execute(core, 9, reinterpret_cast<const uint8_t *>(account_command.data()),
                            account_command.size(), &result),
         MINEG_OK, "persist account state");
  mineg_buffer_free(&result);
  const std::string account_query = R"({"version":1,"type":"GetAccountState"})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(account_query.data()),
                          account_query.size(), &result),
         MINEG_OK, "query account state");
  assert(as_string(result).find("138****8000") != std::string::npos);
  assert(as_string(result).find("accessToken") == std::string::npos);
  mineg_buffer_free(&result);

  const std::string password = "family-photo-2026";
  mineg_buffer_t public_key{};
  mineg_buffer_t encrypted_bundle{};
  mineg_buffer_t kdf{};
  expect(mineg_core_create_user_key_bundle(
             reinterpret_cast<const uint8_t *>(password.data()), password.size(), &public_key,
             &encrypted_bundle, &kdf),
         MINEG_OK, "create user key bundle");
  assert(public_key.size == 32U);
  assert(encrypted_bundle.size == 128U);
  assert(as_string(kdf).find("ARGON2ID13") != std::string::npos);
  assert(as_string(encrypted_bundle).find(password) == std::string::npos);
  std::array<uint8_t, MINEG_KEY_BYTES> device_wrap_key{};
  for (size_t index = 0; index < device_wrap_key.size(); ++index) {
    device_wrap_key[index] = static_cast<uint8_t>(index + 1U);
  }
  mineg_buffer_t device_unlock_blob{};
  const std::string wrong_password = "wrong-family-photo-2026";
  expect(mineg_core_unlock_user_key_bundle(
             core, reinterpret_cast<const uint8_t *>(wrong_password.data()), wrong_password.size(),
             public_key.data, encrypted_bundle.data, encrypted_bundle.size, device_wrap_key.data(),
             &device_unlock_blob),
         MINEG_INTEGRITY_ERROR, "reject wrong key bundle password");
  expect(mineg_core_unlock_user_key_bundle(
             core, reinterpret_cast<const uint8_t *>(password.data()), password.size(), public_key.data,
             encrypted_bundle.data, encrypted_bundle.size, device_wrap_key.data(), &device_unlock_blob),
         MINEG_OK, "unlock user key bundle");
  assert(device_unlock_blob.size == 112U);
  mineg_buffer_t family_envelope{};
  expect(mineg_core_create_family_key_envelope(core, public_key.data, 1, &family_envelope),
         MINEG_OK, "bootstrap family key envelope");
  assert(family_envelope.size == MINEG_FAMILY_KEY_ENVELOPE_BYTES);
  mineg_core_lock_keys(core);
  expect(mineg_core_unlock_family_key_envelope(core, family_envelope.data, family_envelope.size),
         MINEG_NOT_FOUND, "family envelope requires user keys");
  expect(mineg_core_restore_user_key_bundle(core, public_key.data, device_wrap_key.data(),
                                            device_unlock_blob.data, device_unlock_blob.size),
         MINEG_OK, "restore device wrapped user keys");
  expect(mineg_core_unlock_family_key_envelope(core, family_envelope.data, family_envelope.size),
         MINEG_OK, "unlock family key envelope");
  std::array<uint8_t, MINEG_FAMILY_KEY_ENVELOPE_BYTES> invalid_family_envelope{};
  expect(mineg_core_unlock_family_key_envelope(core, invalid_family_envelope.data(),
                                               invalid_family_envelope.size()),
         MINEG_INTEGRITY_ERROR, "reject invalid family envelope");
  mineg_buffer_t rejected_member_envelope{};
  expect(mineg_core_create_family_key_envelope(core, public_key.data, 0, &rejected_member_envelope),
         MINEG_NOT_FOUND, "invalid envelope clears prior family key");
  expect(mineg_core_unlock_user_key_bundle(
             core, reinterpret_cast<const uint8_t *>(wrong_password.data()), wrong_password.size(),
             public_key.data, encrypted_bundle.data, encrypted_bundle.size, device_wrap_key.data(),
             &rejected_member_envelope),
         MINEG_INTEGRITY_ERROR, "failed user unlock clears prior user keys");
  expect(mineg_core_create_family_key_envelope(core, public_key.data, 0, &rejected_member_envelope),
         MINEG_NOT_FOUND, "failed user unlock leaves no usable keys");
  expect(mineg_core_restore_user_key_bundle(core, public_key.data, device_wrap_key.data(),
                                            device_unlock_blob.data, device_unlock_blob.size),
         MINEG_OK, "restore user keys after rejected unlock");
  expect(mineg_core_unlock_family_key_envelope(core, family_envelope.data, family_envelope.size),
         MINEG_OK, "restore family key after rejected unlock");
  mineg_buffer_free(&kdf);

  const auto media_plaintext = root / "media-plain.bin";
  const auto media_ciphertext = root / "media-cipher.bin";
  const auto media_decrypted = root / "media-decrypted.bin";
  write_media_plaintext(media_plaintext);
  const std::string media_id = "10000000-0000-4000-8000-000000000003";
  const std::string resource_id = "20000000-0000-4000-8000-000000000003";
  mineg_buffer_t media_key_envelope{};
  expect(mineg_core_create_media_key_envelope(core, media_id.c_str(), &media_key_envelope),
         MINEG_OK, "create media key envelope");
  assert(media_key_envelope.size == MINEG_MEDIA_KEY_ENVELOPE_BYTES);
  const int media_fd = ::open(media_plaintext.c_str(), O_RDONLY);
  assert(media_fd >= 0);
  mineg_buffer_t fingerprint{};
  expect(mineg_core_compute_dedupe_fingerprint(core, media_fd, "PHOTO", &fingerprint),
         MINEG_OK, "compute account private dedupe fingerprint");
  assert(fingerprint.size == 32U);
  mineg_buffer_t fingerprint_replay{};
  expect(mineg_core_compute_dedupe_fingerprint(core, media_fd, "PHOTO", &fingerprint_replay),
         MINEG_OK, "repeat dedupe fingerprint");
  assert(as_string(fingerprint) == as_string(fingerprint_replay));
  mineg_buffer_free(&fingerprint_replay);
  mineg_buffer_t resource_manifest{};
  expect(mineg_core_encrypt_media_resource(
             core, media_fd, media_ciphertext.c_str(), media_id.c_str(), resource_id.c_str(),
             "ORIGINAL", media_key_envelope.data, media_key_envelope.size, &resource_manifest),
         MINEG_OK, "encrypt 4 MiB media blocks");
  ::close(media_fd);
  const std::string manifest = as_string(resource_manifest);
  assert(manifest.find("\"logicalBlockBytes\":4194304") != std::string::npos);
  assert(count_occurrences(manifest, "\"partNumber\"") == 3U);
  assert(manifest.find("XCHACHA20_POLY1305") != std::string::npos);
  const auto nonce_prefix = decode_nonce_prefix(json_string(manifest, "noncePrefix"));
  mineg_buffer_t encrypted_manifest{};
  expect(mineg_core_encrypt_media_manifest(
             core, media_id.c_str(), resource_manifest.data, resource_manifest.size,
             media_key_envelope.data, media_key_envelope.size, &encrypted_manifest),
         MINEG_OK, "authenticate encrypted resource manifest");
  assert(encrypted_manifest.size == resource_manifest.size + 48U);
  assert(as_string(encrypted_manifest).find(resource_id) == std::string::npos);
  const uint64_t media_plaintext_size = std::filesystem::file_size(media_plaintext);
  expect(mineg_core_decrypt_media_resource(
             core, media_ciphertext.c_str(), media_decrypted.c_str(), media_id.c_str(),
             resource_id.c_str(), "ORIGINAL", media_plaintext_size, nonce_prefix.data(),
             media_key_envelope.data, media_key_envelope.size),
         MINEG_OK, "decrypt authenticated media blocks");
  assert(read_all(media_plaintext) == read_all(media_decrypted));

  const auto reordered_ciphertext = root / "media-reordered.bin";
  std::string reordered = read_all(media_ciphertext);
  constexpr size_t kEncryptedFullBlock = 4U * 1024U * 1024U + 16U;
  std::swap_ranges(reordered.begin(), reordered.begin() + kEncryptedFullBlock,
                   reordered.begin() + kEncryptedFullBlock);
  {
    std::ofstream output(reordered_ciphertext, std::ios::binary);
    output.write(reordered.data(), static_cast<std::streamsize>(reordered.size()));
  }
  const auto rejected_reorder = root / "media-reorder-rejected.bin";
  expect(mineg_core_decrypt_media_resource(
             core, reordered_ciphertext.c_str(), rejected_reorder.c_str(), media_id.c_str(),
             resource_id.c_str(), "ORIGINAL", media_plaintext_size, nonce_prefix.data(),
             media_key_envelope.data, media_key_envelope.size),
         MINEG_INTEGRITY_ERROR, "reject reordered media blocks");
  assert(!std::filesystem::exists(rejected_reorder));

  const auto truncated_ciphertext = root / "media-truncated.bin";
  reordered = read_all(media_ciphertext);
  reordered.resize(reordered.size() - 1U);
  {
    std::ofstream output(truncated_ciphertext, std::ios::binary);
    output.write(reordered.data(), static_cast<std::streamsize>(reordered.size()));
  }
  const auto rejected_truncated = root / "media-truncated-rejected.bin";
  expect(mineg_core_decrypt_media_resource(
             core, truncated_ciphertext.c_str(), rejected_truncated.c_str(), media_id.c_str(),
             resource_id.c_str(), "ORIGINAL", media_plaintext_size, nonce_prefix.data(),
             media_key_envelope.data, media_key_envelope.size),
         MINEG_INTEGRITY_ERROR, "reject truncated media resource");
  assert(!std::filesystem::exists(rejected_truncated));
  mineg_buffer_t wrong_manifest{};
  expect(mineg_core_encrypt_media_manifest(
             core, "wrong-media-id", resource_manifest.data, resource_manifest.size,
             media_key_envelope.data, media_key_envelope.size, &wrong_manifest),
         MINEG_INTEGRITY_ERROR, "reject manifest media mismatch");

  const std::string create_backup =
      "{\"version\":1,\"type\":\"CreateSingleMediaBackup\",\"taskId\":\"" + media_id +
      "\",\"userId\":\"user-001\",\"platformAssetRef\":\"android:asset:backup-1\","
      "\"contentVersion\":\"7:8388645\",\"mediaType\":\"PHOTO\","
      "\"createdAt\":\"2026-07-26T06:00:00.000Z\"}";
  expect(mineg_core_execute(core, 20, reinterpret_cast<const uint8_t *>(create_backup.data()),
                            create_backup.size(), &result),
         MINEG_OK, "persist backup before side effects");
  mineg_buffer_free(&result);
  const std::string premature_completion =
      "{\"version\":1,\"type\":\"CompleteSingleMediaBackup\",\"taskId\":\"" + media_id +
      "\",\"serverMediaId\":\"must-not-complete\","
      "\"updatedAt\":\"2026-07-26T06:00:00.500Z\"}";
  expect(mineg_core_execute(core, 21,
                            reinterpret_cast<const uint8_t *>(premature_completion.data()),
                            premature_completion.size(), &result),
         MINEG_INVALID_ARGUMENT, "reject premature local completion");
  const std::string fake_digest(64, 'a');
  const std::string prepared_backup =
      "{\"version\":1,\"type\":\"RecordPreparedMedia\",\"taskId\":\"" + media_id +
      "\",\"dedupeFingerprint\":\"dedupe-base64\",\"encryptedMediaKey\":\"key-base64\","
      "\"encryptedManifest\":\"manifest-base64\",\"manifestDigest\":\"digest-base64\","
      "\"resources\":[{\"resourceId\":\"" + resource_id +
      "\",\"resourceType\":\"ORIGINAL\",\"ciphertextPath\":\"" +
      media_ciphertext.string() + "\",\"ciphertextSize\":" +
      std::to_string(std::filesystem::file_size(media_ciphertext)) +
      ",\"ciphertextSha256\":\"" + fake_digest + "\",\"manifest\":" + manifest +
      ",\"parts\":[{\"partNumber\":1,\"offset\":0,\"ciphertextSize\":4194320,"
      "\"ciphertextSha256\":\"" + fake_digest +
      "\"},{\"partNumber\":2,\"offset\":4194320,\"ciphertextSize\":4194320,"
      "\"ciphertextSha256\":\"" + fake_digest +
      "\"},{\"partNumber\":3,\"offset\":8388640,\"ciphertextSize\":53,"
      "\"ciphertextSha256\":\"" + fake_digest +
      "\"}]}],\"updatedAt\":\"2026-07-26T06:00:01.000Z\"}";
  expect(mineg_core_execute(core, 22, reinterpret_cast<const uint8_t *>(prepared_backup.data()),
                            prepared_backup.size(), &result),
         MINEG_OK, "persist encrypted resources before upload");
  mineg_buffer_free(&result);
  const std::string upload_session_command =
      "{\"version\":1,\"type\":\"RecordUploadSession\",\"taskId\":\"" + media_id +
      "\",\"uploadId\":\"server-upload-1\",\"updatedAt\":\"2026-07-26T06:00:02.000Z\"}";
  expect(mineg_core_execute(core, 23, reinterpret_cast<const uint8_t *>(upload_session_command.data()),
                            upload_session_command.size(), &result),
         MINEG_OK, "persist server upload session");
  mineg_buffer_free(&result);
  for (int part = 1; part <= 3; ++part) {
    const std::string report =
        "{\"version\":1,\"type\":\"RecordUploadedPart\",\"taskId\":\"" + media_id +
        "\",\"resourceId\":\"" + resource_id + "\",\"partNumber\":" +
        std::to_string(part) + ",\"etag\":\"etag-" + std::to_string(part) +
        "\",\"updatedAt\":\"2026-07-26T06:00:03.000Z\"}";
    expect(mineg_core_execute(core, 23U + static_cast<uint64_t>(part),
                              reinterpret_cast<const uint8_t *>(report.data()), report.size(), &result),
           MINEG_OK, "persist uploaded part");
    mineg_buffer_free(&result);
    if (part == 1) {
      mineg_core_close(core);
      core = nullptr;
      expect(mineg_core_create(database.c_str(), &core), MINEG_OK,
             "reopen during multipart upload");
      expect(mineg_core_subscribe(core, event_callback, &event, &subscription), MINEG_OK,
             "restore subscription after process death");
      expect(mineg_core_restore_user_key_bundle(core, public_key.data, device_wrap_key.data(),
                                                device_unlock_blob.data,
                                                device_unlock_blob.size),
             MINEG_OK, "restore user keys during multipart recovery");
      expect(mineg_core_unlock_family_key_envelope(core, family_envelope.data,
                                                   family_envelope.size),
             MINEG_OK, "restore family key during multipart recovery");
      const std::string recovery_query =
          "{\"version\":1,\"type\":\"GetSingleMediaBackup\",\"taskId\":\"" +
          media_id + "\"}";
      expect(mineg_core_query(core,
                              reinterpret_cast<const uint8_t *>(recovery_query.data()),
                              recovery_query.size(), &result),
             MINEG_OK, "recover multipart upload after process death");
      const std::string recovery = as_string(result);
      assert(recovery.find("\"state\":\"UPLOADING\"") != std::string::npos);
      assert(recovery.find("\"etag\":\"etag-1\"") != std::string::npos);
      assert(recovery.find(media_ciphertext.string()) != std::string::npos);
      mineg_buffer_free(&result);
    }
  }
  const std::string verifying =
      "{\"version\":1,\"type\":\"MarkServerVerifying\",\"taskId\":\"" + media_id +
      "\",\"updatedAt\":\"2026-07-26T06:00:04.000Z\"}";
  expect(mineg_core_execute(core, 27, reinterpret_cast<const uint8_t *>(verifying.data()),
                            verifying.size(), &result),
         MINEG_OK, "persist server verifying state");
  mineg_buffer_free(&result);
  const std::string completed_backup =
      "{\"version\":1,\"type\":\"CompleteSingleMediaBackup\",\"taskId\":\"" + media_id +
      "\",\"serverMediaId\":\"server-media-1\","
      "\"updatedAt\":\"2026-07-26T06:00:05.000Z\"}";
  expect(mineg_core_execute(core, 28, reinterpret_cast<const uint8_t *>(completed_backup.data()),
                            completed_backup.size(), &result),
         MINEG_OK, "complete single media backup only after server confirmation");
  mineg_buffer_free(&result);
  const std::string backup_query =
      "{\"version\":1,\"type\":\"GetSingleMediaBackup\",\"taskId\":\"" + media_id + "\"}";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(backup_query.data()),
                          backup_query.size(), &result),
         MINEG_OK, "read completed single media backup");
  assert(as_string(result).find("\"state\":\"COMPLETED\"") != std::string::npos);
  assert(as_string(result).find("\"uploadedParts\":3") != std::string::npos);
  mineg_buffer_free(&result);
  mineg_buffer_free(&encrypted_manifest);
  mineg_buffer_free(&resource_manifest);
  mineg_buffer_free(&fingerprint);
  mineg_buffer_free(&media_key_envelope);

  const std::string settings_query =
      R"({"version":1,"type":"GetBackupSettings","userId":"user-001","deviceInstallationId":"device-001"})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(settings_query.data()),
                          settings_query.size(), &result),
         MINEG_OK, "default backup settings");
  assert(as_string(result).find("\"autoBackupEnabled\":true") != std::string::npos);
  assert(as_string(result).find("\"allowCellularBackup\":false") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string settings_command =
      R"({"version":1,"type":"UpdateBackupSettings","userId":"user-001","deviceInstallationId":"device-001","autoBackupEnabled":false,"allowCellularBackup":true,"updatedAt":"2026-07-26T01:00:00.000Z"})";
  expect(mineg_core_execute(core, 10, reinterpret_cast<const uint8_t *>(settings_command.data()),
                            settings_command.size(), &result),
         MINEG_OK, "update backup settings");
  mineg_buffer_free(&result);
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(settings_query.data()),
                          settings_query.size(), &result),
         MINEG_OK, "persisted backup settings");
  assert(as_string(result).find("\"autoBackupEnabled\":false") != std::string::npos);
  assert(as_string(result).find("\"allowCellularBackup\":true") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string media_batch =
      R"({"version":1,"type":"ApplyLocalMediaBatch","userId":"user-001","scanGeneration":"scan-001","albums":[{"platformAlbumRef":"android:album:camera","name":"Camera"}],"media":[{"platformAssetRef":"android:asset:2","mediaType":"VIDEO","mimeType":"video/mp4","width":1920,"height":1080,"durationMs":3000,"capturedAt":"2026-07-26T02:00:00.000Z","modifiedAt":"2026-07-26T02:00:01.000Z","modifiedVersion":2,"contentVersion":"2:2048","availability":"AVAILABLE","thumbnailUri":"content://media/2"},{"platformAssetRef":"android:asset:1","mediaType":"PHOTO","mimeType":"image/jpeg","width":1000,"height":1000,"durationMs":null,"capturedAt":"2026-07-26T01:00:00.000Z","modifiedAt":"2026-07-26T01:00:01.000Z","modifiedVersion":1,"contentVersion":"1:1024","availability":"AVAILABLE","thumbnailUri":"content://media/1"}],"relations":[{"platformAssetRef":"android:asset:2","platformAlbumRef":"android:album:camera"},{"platformAssetRef":"android:asset:1","platformAlbumRef":"android:album:camera"}],"cursorModifiedVersion":2,"cursorAssetRef":"android:asset:2","complete":true,"updatedAt":"2026-07-26T03:00:00.000Z"})";
  expect(mineg_core_execute(core, 11, reinterpret_cast<const uint8_t *>(media_batch.data()),
                            media_batch.size(), &result),
         MINEG_OK, "apply local media batch");
  mineg_buffer_free(&result);
  const std::string album_query =
      R"({"version":1,"type":"ListLocalAlbums","userId":"user-001","limit":50})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(album_query.data()),
                          album_query.size(), &result),
         MINEG_OK, "list local albums");
  assert(as_string(result).find("Camera") != std::string::npos);
  assert(as_string(result).find("\"mediaCount\":2") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string media_query =
      R"({"version":1,"type":"ListLocalMedia","userId":"user-001","platformAlbumRef":"android:album:camera","limit":1})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(media_query.data()),
                          media_query.size(), &result),
         MINEG_OK, "list local media page");
  assert(as_string(result).find("android:asset:2") != std::string::npos);
  assert(as_string(result).find("nextCursor") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string reconciled_batch =
      R"({"version":1,"type":"ApplyLocalMediaBatch","userId":"user-001","scanGeneration":"scan-002","albums":[{"platformAlbumRef":"android:album:camera","name":"Family Camera"},{"platformAlbumRef":"android:album:favorites","name":"Favorites"}],"media":[{"platformAssetRef":"android:asset:1","mediaType":"PHOTO","mimeType":"image/webp","width":1200,"height":1200,"durationMs":null,"capturedAt":"2026-07-26T01:00:00.000Z","modifiedAt":"2026-07-26T04:00:00.000Z","modifiedVersion":3,"contentVersion":"3:1536","availability":"AVAILABLE","thumbnailUri":"content://media/1"}],"relations":[{"platformAssetRef":"android:asset:1","platformAlbumRef":"android:album:camera"},{"platformAssetRef":"android:asset:1","platformAlbumRef":"android:album:favorites"}],"cursorModifiedVersion":3,"cursorAssetRef":"android:asset:1","complete":true,"updatedAt":"2026-07-26T04:00:01.000Z"})";
  expect(mineg_core_execute(core, 12, reinterpret_cast<const uint8_t *>(reconciled_batch.data()),
                            reconciled_batch.size(), &result),
         MINEG_OK, "reconcile rename edit multi-album and deletion");
  mineg_buffer_free(&result);
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(album_query.data()),
                          album_query.size(), &result),
         MINEG_OK, "list reconciled albums");
  assert(as_string(result).find("Family Camera") != std::string::npos);
  assert(as_string(result).find("Favorites") != std::string::npos);
  assert(count_occurrences(as_string(result), "\"mediaCount\":1") == 2U);
  mineg_buffer_free(&result);
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(media_query.data()),
                          media_query.size(), &result),
         MINEG_OK, "list reconciled media");
  assert(as_string(result).find("\"contentVersion\":\"3:1536\"") != std::string::npos);
  assert(as_string(result).find("android:asset:2") == std::string::npos);
  mineg_buffer_free(&result);

  constexpr int kMediaCount = 100000;
  constexpr int kBatchSize = 500;
  for (int base = 0; base < kMediaCount; base += kBatchSize) {
    std::string batch =
        "{\"version\":1,\"type\":\"ApplyLocalMediaBatch\",\"userId\":\"user-100k\","
        "\"scanGeneration\":\"scan-100k\",\"albums\":[{\"platformAlbumRef\":"
        "\"android:album:large\",\"name\":\"Large Library\"}],\"media\":[";
    std::string relations = "],\"relations\":[";
    for (int offset = 0; offset < kBatchSize; ++offset) {
      const int index = base + offset;
      const std::string ref = "android:asset:large:" + std::to_string(index);
      if (offset > 0) {
        batch += ',';
        relations += ',';
      }
      batch += "{\"platformAssetRef\":\"" + ref +
               "\",\"mediaType\":\"PHOTO\",\"mimeType\":\"image/jpeg\","
               "\"width\":1000,\"height\":1000,\"durationMs\":null,"
               "\"capturedAt\":\"2026-07-26T05:00:00.000Z\","
               "\"modifiedAt\":\"2026-07-26T05:00:00.000Z\",\"modifiedVersion\":" +
               std::to_string(index) + ",\"contentVersion\":\"" + std::to_string(index) +
               ":1024\",\"availability\":\"AVAILABLE\",\"thumbnailUri\":\"content://media/" +
               std::to_string(index) + "\"}";
      relations += "{\"platformAssetRef\":\"" + ref +
                   "\",\"platformAlbumRef\":\"android:album:large\"}";
    }
    const bool complete = base + kBatchSize == kMediaCount;
    batch += relations + "],\"cursorModifiedVersion\":" +
             std::to_string(base + kBatchSize - 1) + ",\"cursorAssetRef\":\"android:asset:large:" +
             std::to_string(base + kBatchSize - 1) + "\",\"complete\":" +
             (complete ? "true" : "false") +
             ",\"updatedAt\":\"2026-07-26T05:00:01.000Z\"}";
    expect(mineg_core_execute(core, 1000U + static_cast<uint64_t>(base / kBatchSize),
                              reinterpret_cast<const uint8_t *>(batch.data()), batch.size(), &result),
           MINEG_OK, "apply 100k local media index batch");
    mineg_buffer_free(&result);
  }
  const std::string large_state_query =
      R"({"version":1,"type":"GetLocalScanState","userId":"user-100k"})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(large_state_query.data()),
                          large_state_query.size(), &result),
         MINEG_OK, "read 100k local media scan state");
  assert(as_string(result).find("\"indexedCount\":100000") != std::string::npos);
  assert(as_string(result).find("\"status\":\"COMPLETE\"") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string large_page_query =
      R"({"version":1,"type":"ListLocalMedia","userId":"user-100k","platformAlbumRef":"android:album:large","limit":500})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(large_page_query.data()),
                          large_page_query.size(), &result),
         MINEG_OK, "page 100k local media index");
  assert(count_occurrences(as_string(result), "\"platformAssetRef\"") == 501U);
  assert(as_string(result).find("\"nextCursor\":null") == std::string::npos);
  mineg_buffer_free(&result);

  const std::string command = R"({"version":1,"type":"FoundationWriteProbe","value":"persisted"})";
  expect(mineg_core_execute(core, 1, reinterpret_cast<const uint8_t *>(command.data()), command.size(),
                            &result),
         MINEG_OK, "execute");
  assert(as_string(result).find("SUCCESS") != std::string::npos);
  assert(event.find("FoundationProbeChanged") != std::string::npos);
  mineg_buffer_free(&result);

  expect(mineg_core_cancel(core, 77), MINEG_OK, "cancel");
  expect(mineg_core_execute(core, 77, reinterpret_cast<const uint8_t *>(command.data()), command.size(),
                            &result),
         MINEG_CANCELLED, "cancelled execute");
  expect(mineg_core_unsubscribe(core, subscription), MINEG_OK, "unsubscribe");
  event.clear();
  expect(mineg_core_execute(core, 2, reinterpret_cast<const uint8_t *>(command.data()), command.size(),
                            &result),
         MINEG_OK, "execute after unsubscribe");
  assert(event.empty());
  mineg_buffer_free(&result);

  const auto plaintext = root / "plain.bin";
  const auto ciphertext = root / "cipher.mineg";
  const auto decrypted = root / "decrypted.bin";
  write_plaintext(plaintext);
  mineg_buffer_t key{};
  expect(mineg_core_random_key(&key), MINEG_OK, "random key");
  assert(key.size == MINEG_KEY_BYTES);
  const int input_fd = ::open(plaintext.c_str(), O_RDONLY);
  assert(input_fd >= 0);
  expect(mineg_core_encrypt_fd(core, input_fd, ciphertext.c_str(), key.data), MINEG_OK, "encrypt");
  ::close(input_fd);
  expect(mineg_core_decrypt_file(core, ciphertext.c_str(), decrypted.c_str(), key.data), MINEG_OK,
         "decrypt");
  assert(read_all(plaintext) == read_all(decrypted));

  {
    std::fstream tamper(ciphertext, std::ios::binary | std::ios::in | std::ios::out);
    tamper.seekg(64);
    char value = 0;
    tamper.read(&value, 1);
    value ^= 0x40;
    tamper.seekp(64);
    tamper.write(&value, 1);
  }
  const auto rejected = root / "must-not-exist.bin";
  expect(mineg_core_decrypt_file(core, ciphertext.c_str(), rejected.c_str(), key.data),
         MINEG_INTEGRITY_ERROR, "tampered decrypt");
  assert(!std::filesystem::exists(rejected));
  assert(!std::filesystem::exists(rejected.string() + ".partial"));
  mineg_buffer_free(&key);
  mineg_buffer_free(&family_envelope);
  mineg_core_close(core);

  expect(mineg_core_create(database.c_str(), &core), MINEG_OK, "reopen");
  const std::string query = R"({"version":1,"type":"FoundationReadProbe"})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(query.data()), query.size(), &result),
         MINEG_OK, "query after reopen");
  assert(as_string(result).find("persisted") != std::string::npos);
  mineg_buffer_free(&result);
  expect(mineg_core_restore_user_key_bundle(core, public_key.data, device_wrap_key.data(),
                                            device_unlock_blob.data, device_unlock_blob.size),
         MINEG_OK, "restore keys after process reopen");
  expect(mineg_core_unlock_family_key_envelope(core, family_envelope.data, family_envelope.size),
         MINEG_INVALID_ARGUMENT, "freed family envelope is rejected");
  mineg_buffer_free(&public_key);
  mineg_buffer_free(&encrypted_bundle);
  mineg_buffer_free(&device_unlock_blob);
  mineg_core_close(core);

  std::filesystem::remove_all(root);
  std::cout << "MineG core through M2 tests passed\n";
  return 0;
}
