#include "mineg/mineg_core.h"

#include <cassert>
#include <filesystem>
#include <iostream>
#include <string>

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

std::string base64_encode(const std::string &input) {
  static constexpr char kAlphabet[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string output;
  output.reserve(((input.size() + 2U) / 3U) * 4U);
  for (size_t index = 0; index < input.size(); index += 3U) {
    const unsigned value = static_cast<unsigned>(static_cast<unsigned char>(input[index])) << 16U |
        (index + 1U < input.size() ? static_cast<unsigned>(static_cast<unsigned char>(input[index + 1U])) << 8U : 0U) |
        (index + 2U < input.size() ? static_cast<unsigned>(static_cast<unsigned char>(input[index + 2U])) : 0U);
    output += kAlphabet[(value >> 18U) & 63U];
    output += kAlphabet[(value >> 12U) & 63U];
    output += index + 1U < input.size() ? kAlphabet[(value >> 6U) & 63U] : '=';
    output += index + 2U < input.size() ? kAlphabet[value & 63U] : '=';
  }
  return output;
}

}  // namespace

int main() {
  assert(mineg_abi_version() == 6U);
  const auto root = std::filesystem::temp_directory_path() /
      ("mineg-core-test-" + std::to_string(static_cast<long long>(::getpid())));
  std::filesystem::create_directories(root);
  const auto database = root / "core.db";

  mineg_core_t *core = nullptr;
  expect(mineg_core_create(database.c_str(), &core), MINEG_OK, "create");
  mineg_buffer_t result{};

  const std::string backup_overview =
      R"({"contractVersion":"stage04-v1","type":"GetBackupOverview","userId":"user-1","deviceInstallationId":"device-1"})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(backup_overview.data()),
                          backup_overview.size(), &result),
         MINEG_OK, "empty backup overview");
  assert(as_string(result).find("AUTO_BACKUP_DISABLED") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string library_changed =
      R"({"contractVersion":"stage04-v1","type":"NotifyLibraryChanged","userId":"user-1","deviceInstallationId":"device-1"})";
  expect(mineg_core_execute(core, 1, reinterpret_cast<const uint8_t *>(library_changed.data()),
                            library_changed.size(), &result),
         MINEG_OK, "record library change");
  mineg_buffer_free(&result);
  const std::string backup_queue =
      R"({"contractVersion":"stage04-v1","type":"GetBackupQueueSummary","userId":"user-1","deviceInstallationId":"device-1"})";
  const std::string album_backup_progress =
      R"({"contractVersion":"stage04-v1","type":"GetLocalAlbumBackupProgress","userId":"user-1","deviceInstallationId":"device-1","platformAlbumRef":"camera"})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(backup_queue.data()),
                          backup_queue.size(), &result),
         MINEG_OK, "backup queue summary");
  assert(as_string(result).find("\"reconciliationRequired\":true") != std::string::npos);
  assert(as_string(result).find("\"scheduleRequested\":true") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string enable_backup =
      R"({"contractVersion":"stage02-v2","type":"UpdateBackupSettings","userId":"user-1","deviceInstallationId":"device-1","autoBackupEnabled":true,"allowCellularBackup":false,"updatedAt":"2026-08-02T00:00:00Z"})";
  expect(mineg_core_execute(core, 4, reinterpret_cast<const uint8_t *>(enable_backup.data()),
                            enable_backup.size(), &result),
         MINEG_OK, "enable backup setting");
  mineg_buffer_free(&result);
  const std::string disable_backup =
      R"({"contractVersion":"stage02-v2","type":"UpdateBackupSettings","userId":"user-1","deviceInstallationId":"device-1","autoBackupEnabled":false,"allowCellularBackup":false,"updatedAt":"2026-08-02T00:01:00Z"})";
  expect(mineg_core_execute(core, 5, reinterpret_cast<const uint8_t *>(disable_backup.data()),
                            disable_backup.size(), &result),
         MINEG_OK, "disable backup setting");
  mineg_buffer_free(&result);
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(backup_overview.data()),
                          backup_overview.size(), &result),
         MINEG_OK, "disabled backup overview");
  assert(as_string(result).find("AUTO_BACKUP_DISABLED") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string persist_account =
      R"({"version":1,"type":"PersistAccountState","userId":"user-1","maskedPhone":"138****8000","approvalStatus":"APPROVED","updatedAt":"2026-08-02T00:02:00Z"})";
  expect(mineg_core_execute(core, 6, reinterpret_cast<const uint8_t *>(persist_account.data()),
                            persist_account.size(), &result),
         MINEG_OK, "persist approved account for manual backup");
  mineg_buffer_free(&result);
  const std::string indexed_media = R"({"version":1,"type":"ApplyLocalMediaBatch","userId":"user-1","scanGeneration":"manual-generation","updatedAt":"2026-08-02T00:02:01Z","albums":[{"platformAlbumRef":"camera","name":"Camera"}],"media":[{"platformAssetRef":"asset-1","mediaType":"PHOTO","mimeType":"image/jpeg","width":100,"height":100,"durationMs":null,"capturedAt":"2026-08-01T00:00:00Z","modifiedAt":"2026-08-02T00:00:00Z","modifiedVersion":1,"contentVersion":"version-1","availability":"AVAILABLE","thumbnailUri":null}],"relations":[{"platformAssetRef":"asset-1","platformAlbumRef":"camera"}],"complete":true})";
  expect(mineg_core_execute(core, 7, reinterpret_cast<const uint8_t *>(indexed_media.data()),
                            indexed_media.size(), &result),
         MINEG_OK, "index media for manual backup");
  mineg_buffer_free(&result);
  const std::string enqueue_manual =
      R"({"contractVersion":"stage04-v1","type":"EnqueueBackupMedia","userId":"user-1","deviceInstallationId":"device-1","platformAssetRef":"asset-1"})";
  expect(mineg_core_execute(core, 8, reinterpret_cast<const uint8_t *>(enqueue_manual.data()),
                            enqueue_manual.size(), &result),
         MINEG_OK, "enqueue manual media in durable queue");
  assert(as_string(result).find("taskId") != std::string::npos);
  mineg_buffer_free(&result);
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(backup_queue.data()),
                          backup_queue.size(), &result),
         MINEG_OK, "manual queue summary");
  assert(as_string(result).find("\"manualPendingCount\":1") != std::string::npos);
  mineg_buffer_free(&result);
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(album_backup_progress.data()),
                          album_backup_progress.size(), &result),
         MINEG_OK, "local album backup progress");
  assert(as_string(result).find("\"completedCount\":0") != std::string::npos);
  assert(as_string(result).find("\"totalCount\":1") != std::string::npos);
  assert(as_string(result).find("\"platformAssetRef\":\"asset-1\",\"state\":\"SYNCING\"") !=
         std::string::npos);
  mineg_buffer_free(&result);

  const std::string unavailable_media = R"({"version":1,"type":"ApplyLocalMediaBatch","userId":"user-1","scanGeneration":"manual-generation","updatedAt":"2026-08-02T00:02:02Z","albums":[{"platformAlbumRef":"camera","name":"Camera"}],"media":[{"platformAssetRef":"asset-unavailable","mediaType":"PHOTO","mimeType":"image/jpeg","width":100,"height":100,"durationMs":null,"capturedAt":"2026-08-01T00:00:01Z","modifiedAt":"2026-08-02T00:00:01Z","modifiedVersion":2,"contentVersion":"version-2","availability":"WAITING_LOCAL_RESOURCE","thumbnailUri":null}],"relations":[{"platformAssetRef":"asset-unavailable","platformAlbumRef":"camera"}],"complete":true})";
  expect(mineg_core_execute(core, 9, reinterpret_cast<const uint8_t *>(unavailable_media.data()),
                            unavailable_media.size(), &result),
         MINEG_OK, "index unavailable media for manual backup");
  mineg_buffer_free(&result);
  const std::string enqueue_unavailable =
      R"({"contractVersion":"stage04-v1","type":"EnqueueBackupMedia","userId":"user-1","deviceInstallationId":"device-1","platformAssetRef":"asset-unavailable"})";
  expect(mineg_core_execute(core, 10, reinterpret_cast<const uint8_t *>(enqueue_unavailable.data()),
                            enqueue_unavailable.size(), &result),
         MINEG_OK, "queue unavailable manual media");
  mineg_buffer_free(&result);
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(backup_queue.data()),
                          backup_queue.size(), &result),
         MINEG_OK, "queue summary excludes waiting local resource from runnable work");
  assert(as_string(result).find("\"manualPendingCount\":2") != std::string::npos);
  assert(as_string(result).find("\"manualRunnableCount\":1") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string retired_contract =
      R"({"contractVersion":"account-v2","type":"AccountSignIn","phone":"13800138000","password":"Password1","agreementAccepted":true})";
  expect(mineg_core_start_operation(
             core, 1, reinterpret_cast<const uint8_t *>(retired_contract.data()),
             retired_contract.size(), &result),
         MINEG_INVALID_ARGUMENT, "reject retired account-v2");
  assert(result.data == nullptr);

  const std::string private_media_page =
      R"({"contractVersion":"stage05-v1","type":"GetPrivateMediaPage","limit":50})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(private_media_page.data()),
                          private_media_page.size(), &result),
         MINEG_OK, "empty stage05 private media page");
  assert(as_string(result).find("\"contractVersion\":\"stage05-v1\"") != std::string::npos);
  assert(as_string(result).find("\"snapshot\":null") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string refresh_private_media =
      R"({"contractVersion":"stage05-v1","type":"RefreshPrivateMedia","limit":50,"allowCached":true})";
  expect(mineg_core_start_operation(
             core, 12, reinterpret_cast<const uint8_t *>(refresh_private_media.data()),
             refresh_private_media.size(), &result),
         MINEG_OK, "start stage05 private media refresh");
  assert(as_string(result).find("WAITING_FOR_EFFECT") != std::string::npos);
  assert(as_string(result).find("SecureStoreEffect") != std::string::npos);
  mineg_buffer_free(&result);

  const auto successful_effect = [](uint64_t operation_id, uint64_t sequence,
                                    const std::string &effect_type,
                                    const std::string &payload) {
    return "{\"contractVersion\":\"foundation-v2\",\"operationId\":" +
        std::to_string(operation_id) + ",\"sequence\":" + std::to_string(sequence) +
        ",\"effectType\":\"" + effect_type + "\",\"status\":\"SUCCEEDED\",\"payload\":" +
        payload + "}";
  };
  const std::string session_values = "{\"values\":["
      "{\"name\":\"account.accessToken\",\"valueBase64\":\"" + base64_encode("access-token") + "\"},"
      "{\"name\":\"account.refreshToken\",\"valueBase64\":\"" + base64_encode("refresh-token") + "\"},"
      "{\"name\":\"account.accessExpiresAt\",\"valueBase64\":\"" + base64_encode("2030-01-01T00:00:00Z") + "\"},"
      "{\"name\":\"account.refreshExpiresAt\",\"valueBase64\":\"" + base64_encode("2030-01-02T00:00:00Z") + "\"},"
      "{\"name\":\"device.installationId\",\"valueBase64\":\"" + base64_encode("device-1") + "\"}]}";
  const std::string refresh_session_result = successful_effect(12, 1, "SecureStoreEffect", session_values);
  expect(mineg_core_resume_operation(core, 12, reinterpret_cast<const uint8_t *>(refresh_session_result.data()),
                                     refresh_session_result.size(), &result),
         MINEG_OK, "restore session for stage05 refresh");
  assert(as_string(result).find("TRANSPORT_PRIVATE_MEDIA_REFRESH") == std::string::npos);
  assert(as_string(result).find("TransportEffect") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string saved_media_id = "11111111-1111-4111-8111-111111111111";
  const std::string private_page_response =
      "{\"items\":[{\"id\":\"" + saved_media_id +
      "\",\"media_type\":\"PHOTO\",\"captured_at\":\"2026-08-01T00:00:00Z\","
      "\"created_at\":\"2026-08-01T00:00:00Z\",\"original_total_size\":3}],\"next_cursor\":null}";
  const std::string refresh_response = successful_effect(12, 2, "TransportEffect",
      "{\"status\":200,\"contentType\":\"application/json\",\"requestId\":\"request-refresh\","
      "\"retryAfterSeconds\":null,\"bodyBase64\":\"" + base64_encode(private_page_response) + "\"}");
  expect(mineg_core_resume_operation(core, 12, reinterpret_cast<const uint8_t *>(refresh_response.data()),
                                     refresh_response.size(), &result),
         MINEG_OK, "persist stage05 private media page");
  assert(as_string(result).find("COMPLETED") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string save_command = "{\"contractVersion\":\"stage05-v1\",\"type\":\"SavePrivateMediaToSystemAlbum\",\"mediaId\":\"" +
      saved_media_id + "\"}";
  expect(mineg_core_start_operation(core, 13, reinterpret_cast<const uint8_t *>(save_command.data()),
                                    save_command.size(), &result),
         MINEG_OK, "start stage05 private media save");
  assert(as_string(result).find("TransportEffect") != std::string::npos);
  assert(as_string(result).find("/access") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string resource_id = "22222222-2222-4222-8222-222222222222";
  const std::string digest = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0";
  const std::string access_response_body =
      "{\"media_id\":\"" + saved_media_id + "\",\"purpose\":\"DOWNLOAD\",\"resources\":[{"
      "\"resource_id\":\"" + resource_id + "\",\"resource_type\":\"ORIGINAL\","
      "\"mime_type\":\"image/jpeg\",\"content_size\":3,\"content_sha256\":\"" + digest + "\","
      "\"supports_range\":false,\"grant\":{\"url\":\"https://object.example.test/private?grant=short\","
      "\"method\":\"GET\",\"headers\":{\"X-MineG-Grant\":\"short\"},"
      "\"expires_at\":\"2026-08-01T00:05:00Z\"}}]}";
  const std::string access_response = successful_effect(13, 1, "TransportEffect",
      "{\"status\":200,\"contentType\":\"application/json\",\"requestId\":\"request-access\","
      "\"retryAfterSeconds\":null,\"bodyBase64\":\"" + base64_encode(access_response_body) + "\"}");
  expect(mineg_core_resume_operation(core, 13, reinterpret_cast<const uint8_t *>(access_response.data()),
                                     access_response.size(), &result),
         MINEG_OK, "accept short-lived private media grant");
  assert(as_string(result).find("getAvailableSpace") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string storage_result = successful_effect(13, 2, "FileEffect", "{\"availableBytes\":67108864}");
  expect(mineg_core_resume_operation(core, 13, reinterpret_cast<const uint8_t *>(storage_result.data()),
                                     storage_result.size(), &result),
         MINEG_OK, "check private media save storage");
  assert(as_string(result).find("createTaskTempFile") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string temp_result = successful_effect(13, 3, "FileEffect", "{\"path\":\"/safe/private-save-13-0.mineg-task\"}");
  expect(mineg_core_resume_operation(core, 13, reinterpret_cast<const uint8_t *>(temp_result.data()),
                                     temp_result.size(), &result),
         MINEG_OK, "create private media save temp file");
  assert(as_string(result).find("downloadObject") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string download_result = successful_effect(13, 4, "TransportEffect",
      "{\"status\":200,\"bytesWritten\":3,\"sha256Base64\":\"" + digest + "\"}");
  expect(mineg_core_resume_operation(core, 13, reinterpret_cast<const uint8_t *>(download_result.data()),
                                     download_result.size(), &result),
         MINEG_OK, "verify private media object download");
  assert(as_string(result).find("SystemAlbumEffect") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string album_result = successful_effect(13, 5, "SystemAlbumEffect",
      "{\"platformAssetRef\":\"android:media-store:123\"}");
  expect(mineg_core_resume_operation(core, 13, reinterpret_cast<const uint8_t *>(album_result.data()),
                                     album_result.size(), &result),
         MINEG_OK, "write verified private media to system album");
  assert(as_string(result).find("deleteTempFile") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string cleanup_result = successful_effect(13, 6, "FileEffect", "{\"deleted\":true}");
  expect(mineg_core_resume_operation(core, 13, reinterpret_cast<const uint8_t *>(cleanup_result.data()),
                                     cleanup_result.size(), &result),
         MINEG_OK, "finish private media save");
  assert(as_string(result).find("\"state\":\"COMPLETED\"") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string save_snapshot = "{\"contractVersion\":\"stage05-v1\",\"type\":\"GetPrivateMediaSaveOperation\",\"mediaId\":\"" +
      saved_media_id + "\"}";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(save_snapshot.data()),
                          save_snapshot.size(), &result),
         MINEG_OK, "query completed stage05 private media save");
  assert(as_string(result).find("\"state\":\"COMPLETED\"") != std::string::npos);
  assert(as_string(result).find("signed_url") == std::string::npos);
  assert(as_string(result).find("object.example.test") == std::string::npos);
  mineg_buffer_free(&result);

  const std::string open_thumbnail = "{\"contractVersion\":\"stage05-v1\",\"type\":\"OpenPrivateMedia\",\"mediaId\":\"" +
      saved_media_id + "\",\"variant\":\"THUMBNAIL\"}";
  expect(mineg_core_start_operation(core, 14, reinterpret_cast<const uint8_t *>(open_thumbnail.data()),
                                    open_thumbnail.size(), &result),
         MINEG_OK, "start verified private media thumbnail");
  assert(as_string(result).find("TransportEffect") != std::string::npos);
  assert(as_string(result).find("/access") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string preview_resource_id = "33333333-3333-4333-8333-333333333333";
  const std::string view_access_body =
      "{\"media_id\":\"" + saved_media_id +
      "\",\"purpose\":\"VIEW\",\"variant\":\"THUMBNAIL\",\"resources\":[{"
      "\"resource_id\":\"" + preview_resource_id +
      "\",\"resource_type\":\"THUMBNAIL\",\"mime_type\":\"image/jpeg\",\"content_size\":3,"
      "\"content_sha256\":\"" + digest + "\",\"supports_range\":false,\"delivery_mode\":\"ORIGINAL_RESOURCE\",\"grant\":{"
      "\"url\":\"https://object.example.test/private-thumb?grant=short\",\"method\":\"GET\","
      "\"headers\":{\"X-MineG-Grant\":\"short\"},\"expires_at\":\"2026-08-01T00:05:00Z\"}}]}";
  const std::string view_access_result = successful_effect(14, 1, "TransportEffect",
      "{\"status\":200,\"contentType\":\"application/json\",\"requestId\":\"request-view\","
      "\"retryAfterSeconds\":null,\"bodyBase64\":\"" + base64_encode(view_access_body) + "\"}");
  expect(mineg_core_resume_operation(core, 14, reinterpret_cast<const uint8_t *>(view_access_result.data()),
                                     view_access_result.size(), &result),
         MINEG_OK, "accept thumbnail view grant");
  assert(as_string(result).find("createTaskTempFile") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string preview_temp_result = successful_effect(14, 2, "FileEffect",
      "{\"path\":\"/safe/private-view-14.mineg-task\"}");
  expect(mineg_core_resume_operation(core, 14, reinterpret_cast<const uint8_t *>(preview_temp_result.data()),
                                     preview_temp_result.size(), &result),
         MINEG_OK, "create thumbnail view temp file");
  assert(as_string(result).find("downloadObject") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string preview_download_result = successful_effect(14, 3, "TransportEffect",
      "{\"status\":200,\"bytesWritten\":3,\"sha256Base64\":\"" + digest + "\",\"contentType\":\"image/jpeg\"}");
  expect(mineg_core_resume_operation(core, 14, reinterpret_cast<const uint8_t *>(preview_download_result.data()),
                                     preview_download_result.size(), &result),
         MINEG_OK, "verify thumbnail view object");
  assert(as_string(result).find("MediaPlaybackEffect") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string preview_open_result = successful_effect(14, 4, "MediaPlaybackEffect",
      "{\"viewHandle\":\"preview-handle-123\",\"sourceUri\":\"file:///safe/private-view-14.mineg-task\"}");
  expect(mineg_core_resume_operation(core, 14, reinterpret_cast<const uint8_t *>(preview_open_result.data()),
                                     preview_open_result.size(), &result),
         MINEG_OK, "open verified thumbnail view");
  assert(as_string(result).find("\"sourceUri\":\"file:///safe/private-view-14.mineg-task\"") != std::string::npos);
  assert(as_string(result).find("object.example.test") == std::string::npos);
  mineg_buffer_free(&result);
  const std::string close_thumbnail =
      R"({"contractVersion":"stage05-v1","type":"ClosePrivateMedia","viewHandle":"preview-handle-123"})";
  expect(mineg_core_start_operation(core, 15, reinterpret_cast<const uint8_t *>(close_thumbnail.data()),
                                    close_thumbnail.size(), &result),
         MINEG_OK, "start private thumbnail close");
  assert(as_string(result).find("closeVerifiedMedia") != std::string::npos);
  mineg_buffer_free(&result);
  const std::string preview_close_result = successful_effect(15, 1, "MediaPlaybackEffect", "{\"closed\":true}");
  expect(mineg_core_resume_operation(core, 15, reinterpret_cast<const uint8_t *>(preview_close_result.data()),
                                     preview_close_result.size(), &result),
         MINEG_OK, "close private thumbnail view");
  assert(as_string(result).find("\"closed\":true") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string open_dynamic_thumbnail = "{\"contractVersion\":\"stage05-v1\",\"type\":\"OpenPrivateMedia\",\"mediaId\":\"" +
      saved_media_id + "\",\"variant\":\"THUMBNAIL\"}";
  expect(mineg_core_start_operation(core, 16, reinterpret_cast<const uint8_t *>(open_dynamic_thumbnail.data()),
                                    open_dynamic_thumbnail.size(), &result),
         MINEG_OK, "start OSS dynamic private media thumbnail");
  mineg_buffer_free(&result);
  const std::string dynamic_access_body =
      "{\"media_id\":\"" + saved_media_id +
      "\",\"purpose\":\"VIEW\",\"variant\":\"THUMBNAIL\",\"resources\":[{"
      "\"resource_id\":\"" + resource_id +
      "\",\"resource_type\":\"ORIGINAL\",\"mime_type\":\"image/jpeg\",\"content_size\":3,"
      "\"content_sha256\":\"" + digest + "\",\"supports_range\":false,"
      "\"delivery_mode\":\"OSS_IMAGE_THUMBNAIL\",\"maximum_output_size\":5242880,\"grant\":{"
      "\"url\":\"https://object.example.test/private?x-oss-process=image%2Fresize%2Cm_lfit%2Cl_512&grant=short\","
      "\"method\":\"GET\",\"headers\":{\"X-MineG-Grant\":\"short\"},"
      "\"expires_at\":\"2026-08-01T00:05:00Z\"}}]}";
  const std::string dynamic_access_result = successful_effect(16, 1, "TransportEffect",
      "{\"status\":200,\"contentType\":\"application/json\",\"requestId\":\"request-dynamic-view\","
      "\"retryAfterSeconds\":null,\"bodyBase64\":\"" + base64_encode(dynamic_access_body) + "\"}");
  expect(mineg_core_resume_operation(core, 16, reinterpret_cast<const uint8_t *>(dynamic_access_result.data()),
                                     dynamic_access_result.size(), &result),
         MINEG_OK, "accept OSS dynamic thumbnail grant");
  mineg_buffer_free(&result);
  const std::string dynamic_temp_result = successful_effect(16, 2, "FileEffect",
      "{\"path\":\"/safe/private-view-16.mineg-task\"}");
  expect(mineg_core_resume_operation(core, 16, reinterpret_cast<const uint8_t *>(dynamic_temp_result.data()),
                                     dynamic_temp_result.size(), &result),
         MINEG_OK, "create OSS dynamic thumbnail temp file");
  assert(as_string(result).find("\"maximumSize\":5242880") != std::string::npos);
  assert(as_string(result).find("\"expectedSize\"") == std::string::npos);
  mineg_buffer_free(&result);
  const std::string dynamic_download_result = successful_effect(16, 3, "TransportEffect",
      "{\"status\":200,\"bytesWritten\":2,\"sha256Base64\":\"not-the-original-digest\",\"contentType\":\"image/jpeg\"}");
  expect(mineg_core_resume_operation(core, 16, reinterpret_cast<const uint8_t *>(dynamic_download_result.data()),
                                     dynamic_download_result.size(), &result),
         MINEG_OK, "accept bounded OSS dynamic thumbnail bytes");
  assert(as_string(result).find("MediaPlaybackEffect") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string write_probe =
      R"({"version":1,"type":"FoundationWriteProbe","value":"persisted"})";
  expect(mineg_core_execute(core, 2, reinterpret_cast<const uint8_t *>(write_probe.data()),
                            write_probe.size(), &result),
         MINEG_OK, "write probe");
  assert(as_string(result).find("SUCCESS") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string read_probe =
      R"({"version":1,"type":"FoundationReadProbe"})";
  expect(mineg_core_query(core, reinterpret_cast<const uint8_t *>(read_probe.data()),
                          read_probe.size(), &result),
         MINEG_OK, "read probe");
  assert(as_string(result).find("persisted") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string start =
      R"({"contractVersion":"foundation-v2","type":"FoundationEffectProbe","effectType":"TransportEffect","payload":{"action":"sendApiRequest","method":"GET","path":"/foundation/probe"}})";
  expect(mineg_core_start_operation(core, 3, reinterpret_cast<const uint8_t *>(start.data()),
                                    start.size(), &result),
         MINEG_OK, "start operation");
  assert(as_string(result).find("WAITING_FOR_EFFECT") != std::string::npos);
  mineg_buffer_free(&result);

  const std::string effect =
      R"({"contractVersion":"foundation-v2","operationId":3,"sequence":1,"effectType":"TransportEffect","status":"SUCCEEDED","payload":{"status":200}})";
  expect(mineg_core_resume_operation(core, 3, reinterpret_cast<const uint8_t *>(effect.data()),
                                     effect.size(), &result),
         MINEG_OK, "resume operation");
  assert(as_string(result).find("COMPLETED") != std::string::npos);
  mineg_buffer_free(&result);

  mineg_core_close(core);
  std::filesystem::remove_all(root);
  std::cout << "MineG core active-path tests passed\n";
  return 0;
}
