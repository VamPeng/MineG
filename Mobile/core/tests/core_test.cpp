#include "mineg/mineg_core.h"

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

std::string read_all(const std::filesystem::path &path) {
  std::ifstream input(path, std::ios::binary);
  return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

}  // namespace

int main() {
  assert(mineg_abi_version() == 1U);
  const auto root = std::filesystem::temp_directory_path() /
                    ("mineg-core-test-" + std::to_string(static_cast<long long>(::getpid())));
  std::filesystem::create_directories(root);
  const auto database = root / "core.db";

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

  const std::string command = R"({"version":1,"type":"FoundationWriteProbe","value":"persisted"})";
  mineg_buffer_t result{};
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
  mineg_core_close(core);

  expect(mineg_core_create(database.c_str(), &core), MINEG_OK, "reopen");
  const std::string query = R"({"version":1,"type":"FoundationReadProbe"})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(query.data()), query.size(), &result),
         MINEG_OK, "query after reopen");
  assert(as_string(result).find("persisted") != std::string::npos);
  mineg_buffer_free(&result);
  mineg_core_close(core);

  std::filesystem::remove_all(root);
  std::cout << "MineG core M0 tests passed\n";
  return 0;
}
