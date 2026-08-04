# Private Media Local Save Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Save an already-opened private image from its verified Android private cache to MediaStore, persist its device-local mapping, and remove all remote-save download capability.

**Architecture:** Android coordinates cache lookup, MediaStore writing, receipt persistence, and cache cleanup. Core exposes a local-only receipt-record command and no longer issues access or OSS download effects for saves. The service exposes only VIEW and STREAM private-media access purposes.

**Tech Stack:** Kotlin/Android MediaStore, C++17 Core with SQLite, Go HTTP API/OpenAPI, JUnit/Kotlin test, CTest, Go test.

## Global Constraints

- A download_receipts mapping is device-local and is never sent to the service.
- A valid existing MediaStore mapping completes without copy or network activity, then removes the matching private cache entry.
- A missing or corrupt private cache returns PRIVATE_MEDIA_ORIGINAL_NOT_READY; it never falls back to service or OSS download.
- The private cache entry is removed only after a valid mapping is durable; failed receipt persistence deletes the newly written MediaStore entry and retains the cache.
- Remove private-media DOWNLOAD access from the service contract and Core save-download state machine; retain VIEW and STREAM.
- Do not stage or modify the unrelated untracked tmp/ directory.

---

## File Structure

- Service/api/openapi.yaml — remove the private-media DOWNLOAD access value.
- Service/internal/media/service.go and service_test.go — reject DOWNLOAD and remove its resource-selection branch.
- Mobile/core/src/core.cpp, core.h, and core_test.cpp — add a local receipt-record command and delete remote save stages.
- Mobile/contracts/stage05-v1.json — replace remote-save contract text with a local receipt command.
- Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/account/CoreStage05Client.kt — expose the local receipt recorder.
- Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/contracts/FoundationContracts.kt — identify verified task and verified private-cache sources.
- Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/platform/AndroidSystemAlbumWriterPort.kt — write only from approved private-cache roots.
- Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/platform/PrivateOriginalDiskStore.kt — return cache-removal success.
- Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/app/PrivateMediaLocalSaver.kt — new, testable cache-first coordinator.
- Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/app/MineGAppRuntime.kt and MineGAppViewModel.kt — inject detail metadata and use the coordinator.
- Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/app/PrivateMediaLocalSaverTest.kt — test every terminal branch.

## Task 1: Remove the Service DOWNLOAD Access Contract

**Files:**
- Modify: Service/api/openapi.yaml
- Modify: Service/internal/media/service.go
- Modify: Service/internal/media/service_test.go
- Modify: Service/api/openapi_test.go

**Interfaces:**
- Consumes: media.AccessInput{Purpose, Variant}.
- Produces: VIEW and STREAM as the only accepted private-media access purposes; DOWNLOAD returns PRIVATE_MEDIA_ACCESS_INVALID.

- [ ] **Step 1: Write a failing service test for removed DOWNLOAD behavior**

Add this assertion alongside private-media access validation tests:

~~~go
result, err := service.Access(ctx, approvedActor, mediaID, media.AccessInput{Purpose: "DOWNLOAD"})
if result != (media.AccessResult{}) { t.Fatalf("unexpected result: %#v", result) }
assertMediaError(t, err, "PRIVATE_MEDIA_ACCESS_INVALID", http.StatusUnprocessableEntity)
~~~

Add an OpenAPI test asserting the private access-purpose schema omits DOWNLOAD.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: cd Service && go test ./internal/media ./api

Expected: FAIL because DOWNLOAD is currently accepted.

- [ ] **Step 3: Remove the service and schema branches**

Restrict private-media access validation to:

~~~go
if input.Purpose != "VIEW" && input.Purpose != "STREAM" {
    return validation("PRIVATE_MEDIA_ACCESS_INVALID", "Invalid media access",
        "The purpose and variant combination is invalid.")
}
~~~

Delete case "DOWNLOAD" in selectAccessResources. Remove DOWNLOAD from the private access-purpose enum in openapi.yaml; do not alter family-media validation.

- [ ] **Step 4: Run focused service verification**

Run: cd Service && go test ./internal/media ./api

Expected: PASS; VIEW and STREAM remain green and DOWNLOAD is rejected.

- [ ] **Step 5: Commit**

~~~bash
git add Service/api/openapi.yaml Service/api/openapi_test.go Service/internal/media/service.go Service/internal/media/service_test.go
git commit -m "refactor(service): remove private media download access"
~~~

## Task 2: Replace the Core Remote Save State Machine with Local Receipt Recording

**Files:**
- Modify: Mobile/core/src/core.cpp
- Modify: Mobile/core/src/core.h
- Modify: Mobile/core/tests/core_test.cpp
- Modify: Mobile/contracts/stage05-v1.json

**Interfaces:**
- Consumes this Stage 05 command:

~~~json
{"contractVersion":"stage05-v1","type":"RecordPrivateMediaSystemSave",
 "mediaId":"<uuid>","resourceId":"<uuid>",
 "platformAssetRef":"android:media-store:<positive-id>"}
~~~

- Produces: {"mediaId":"<uuid>","state":"COMPLETED","savedResourceCount":1} after upserting download_receipts.
- Produces: no TransportEffect, FileEffect, or SystemAlbumEffect.

- [ ] **Step 1: Write failing Core tests for the receipt-only command**

Replace the current SavePrivateMediaToSystemAlbum effect-chain test with:

~~~cpp
expect(start(core, 13, R"({"contractVersion":"stage05-v1",
  "type":"RecordPrivateMediaSystemSave",
  "mediaId":"11111111-1111-4111-8111-111111111111",
  "resourceId":"22222222-2222-4222-8222-222222222222",
  "platformAssetRef":"android:media-store:123"})"), MINEG_OK, "record system save");
assert(as_string(result).find("\"state\":\"COMPLETED\"") != std::string::npos);
assert(as_string(result).find("TransportEffect") == std::string::npos);
~~~

Add negative cases for a non-ORIGINAL resource, a resource belonging to another media row, and malformed Android asset references.

- [ ] **Step 2: Run Core tests and verify the command is unsupported**

Run: Mobile/scripts/test-core.sh

Expected: FAIL at RecordPrivateMediaSystemSave with COMMAND_NOT_SUPPORTED.

- [ ] **Step 3: Implement the local command and delete remote save code**

Add RecordPrivateMediaSystemSave to the Stage 05 command set. Query persisted private_media_items_v2 and private_media_resources for the active account; require its matching ORIGINAL resource; validate android:media-store:[1-9][0-9]*; derive the content revision and resource-set digest from persisted metadata; then upsert:

~~~sql
INSERT INTO download_receipts(user_id,cloud_media_id,platform_asset_ref,created_at,
  content_revision,resource_set_digest,updated_at)
VALUES(?,?,?,?,?,?,?)
ON CONFLICT(user_id,cloud_media_id) DO UPDATE SET
  platform_asset_ref=excluded.platform_asset_ref,
  content_revision=excluded.content_revision,
  resource_set_digest=excluded.resource_set_digest,
  updated_at=excluded.updated_at;
~~~

Delete SavePrivateMediaToSystemAlbum, RetryPrivateMediaSave, and CancelPrivateMediaSave command handling. Delete every PRIVATE_MEDIA_SAVE_* stage that requests access, creates a task file, downloads, verifies a remote object, or writes a temp file to the system album. Retain every PRIVATE_MEDIA_VIEW_* stage.

- [ ] **Step 4: Run Core verification**

Run: Mobile/scripts/test-core.sh

Expected: PASS; no save test expects access, downloadObject, or a system-album effect.

- [ ] **Step 5: Commit**

~~~bash
git add Mobile/core/src/core.cpp Mobile/core/src/core.h Mobile/core/tests/core_test.cpp Mobile/contracts/stage05-v1.json
git commit -m "refactor(core): record local private media saves"
~~~

## Task 3: Make Android System-Album Writes Accept Verified Private Cache Sources

**Files:**
- Modify: Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/contracts/FoundationContracts.kt
- Modify: Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/platform/AndroidSystemAlbumWriterPort.kt
- Modify: Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/platform/PrivateOriginalDiskStore.kt
- Modify: Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/platform/PrivateOriginalDiskStoreTest.kt

**Interfaces:**
- Produces:

~~~kotlin
enum class SystemAlbumSource { VERIFIED_TASK_FILE, VERIFIED_PRIVATE_ORIGINAL }

data class SystemAlbumWriteRequest(
  val verifiedFilePath: String,
  val displayName: String,
  val mimeType: String,
  val capturedAt: String?,
  val source: SystemAlbumSource = SystemAlbumSource.VERIFIED_TASK_FILE,
)
~~~

- Produces: PrivateOriginalDiskStore.remove(accountId, mediaId): Boolean.

- [ ] **Step 1: Write failing private-cache removal tests**

Add tests asserting that successful removal returns true and deletes both the cache file and its .tmp sibling. Add a controlled delete failure assertion that requires false to be returned instead of ignored.

- [ ] **Step 2: Run focused tests and verify they fail**

Run: cd Mobile/MineG_Android && ./gradlew testDebugUnitTest --tests com.mineg.mobile.platform.PrivateOriginalDiskStoreTest

Expected: FAIL because remove returns Unit and ignores deletion failures.

- [ ] **Step 3: Implement source policy and removal result**

In AndroidSystemAlbumWriterPort, canonicalize the source and permit only the corresponding root:

~~~kotlin
val allowedRoot = when (request.source) {
  SystemAlbumSource.VERIFIED_TASK_FILE -> taskFilesDirectory
  SystemAlbumSource.VERIFIED_PRIVATE_ORIGINAL -> privateOriginalsDirectory
}
require(source.toPath().startsWith(allowedRoot.toPath())) {
  "system-album source is outside its verified root"
}
~~~

Set privateOriginalsDirectory to noBackupFilesDir/mineg-originals-v1. Keep the current MIME choice, pending MediaStore insertion, stream copy, publish, and failed-write cleanup. Make PrivateOriginalDiskStore.remove return true only if neither cache nor temporary file remains.

- [ ] **Step 4: Run focused Android tests**

Run: cd Mobile/MineG_Android && ./gradlew testDebugUnitTest --tests com.mineg.mobile.platform.PrivateOriginalDiskStoreTest

Expected: PASS.

- [ ] **Step 5: Commit**

~~~bash
git add Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/contracts/FoundationContracts.kt Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/platform/AndroidSystemAlbumWriterPort.kt Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/platform/PrivateOriginalDiskStore.kt Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/platform/PrivateOriginalDiskStoreTest.kt
git commit -m "feat(android): save verified private originals to MediaStore"
~~~

## Task 4: Add a Testable Cache-First Android Save Coordinator

**Files:**
- Create: Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/app/PrivateMediaLocalSaver.kt
- Create: Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/app/PrivateMediaLocalSaverTest.kt
- Modify: Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/account/CoreStage05Client.kt
- Modify: Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/app/MineGAppRuntime.kt
- Modify: Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/app/MineGAppViewModel.kt
- Modify: Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/app/MineGAppViewModelTest.kt
- Modify: Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/Stage05ContractTest.kt

**Interfaces:**
- Produces:

~~~kotlin
internal interface PrivateMediaSaveReceiptRecorder {
  suspend fun record(mediaId: String, resourceId: String, platformAssetRef: String): PrivateMediaSaveResult
}

internal class PrivateMediaLocalSaver(
  private val privateOriginals: PrivateOriginalDiskStore,
  private val album: SystemAlbumWriterPort,
  private val receiptRecorder: PrivateMediaSaveReceiptRecorder,
) {
  suspend fun save(userId: String, detail: PrivateMediaDetail): PrivateMediaSaveResult
}
~~~

- Consumes: a PrivateMediaDetail fetched while opening the detail page, with one image ORIGINAL resource.

- [ ] **Step 1: Write failing coordinator tests for each terminal branch**

Use fake cache, album, and receipt recorder and write these tests:

~~~kotlin
@Test fun validMappingDeletesCacheWithoutCopyOrReceiptWrite() = runTest {
  val cache = FakeCache(valid = true); val album = FakeAlbum(mappingPresent = true)
  val receipts = FakeReceipts()
  assertEquals("COMPLETED", saver(cache, album, receipts).save(USER, detail()).state)
  assertEquals(0, album.writeCalls); assertEquals(0, receipts.calls); assertEquals(1, cache.removeCalls)
}
@Test fun missingCacheReturnsOriginalNotReadyWithoutAlbumOrReceiptCall() = runTest {
  val cache = FakeCache(valid = false); val album = FakeAlbum(); val receipts = FakeReceipts()
  assertEquals("PRIVATE_MEDIA_ORIGINAL_NOT_READY", saver(cache, album, receipts).save(USER, detail()).state)
  assertEquals(0, album.writeCalls); assertEquals(0, receipts.calls)
}
@Test fun validCacheWritesAlbumThenReceiptThenDeletesCache() = runTest {
  val cache = FakeCache(valid = true); val album = FakeAlbum(); val receipts = FakeReceipts()
  assertEquals("COMPLETED", saver(cache, album, receipts).save(USER, detail()).state)
  assertEquals(1, album.writeCalls); assertEquals(1, receipts.calls); assertEquals(1, cache.removeCalls)
  assertEquals("android:media-store:77", receipts.platformAssetRef)
}
@Test fun receiptFailureDeletesNewAlbumEntryAndRetainsCache() = runTest {
  val cache = FakeCache(valid = true); val album = FakeAlbum(); val receipts = FakeReceipts(failure = IOException())
  assertFailsWith<IOException> { saver(cache, album, receipts).save(USER, detail()) }
  assertEquals(listOf("android:media-store:77"), album.deletedRefs); assertEquals(0, cache.removeCalls)
}
@Test fun cleanupRetryUsesMappingAndNeverCopiesAgain() = runTest {
  val cache = FakeCache(valid = true, removeResults = listOf(false, true)); val album = FakeAlbum(mappingPresent = true)
  assertEquals("PRIVATE_MEDIA_CACHE_CLEANUP_FAILED", saver(cache, album, FakeReceipts()).save(USER, detail()).state)
  assertEquals("COMPLETED", saver(cache, album, FakeReceipts()).save(USER, detail()).state)
  assertEquals(0, album.writeCalls)
}
~~~

- [ ] **Step 2: Run the focused test and verify it fails**

Run: cd Mobile/MineG_Android && ./gradlew testDebugUnitTest --tests com.mineg.mobile.app.PrivateMediaLocalSaverTest

Expected: FAIL because PrivateMediaLocalSaver does not exist.

- [ ] **Step 3: Implement the coordinator, Runtime, and ViewModel integration**

Implement this order:

~~~kotlin
detail.localPlatformAssetRef?.takeIf(album::isSystemAlbumEntryPresent)?.let {
  return completeAfterCacheRemoval(userId, detail.id)
}
val original = detail.resources.singleOrNull {
  it.resourceType == "ORIGINAL" && it.mimeType.startsWith("image/")
} ?: return PrivateMediaSaveResult(detail.id, "PRIVATE_MEDIA_ORIGINAL_NOT_READY", 0)
val cache = privateOriginals.get(userId, detail.id, original.contentSize, original.contentSha256)
  ?: return PrivateMediaSaveResult(detail.id, "PRIVATE_MEDIA_ORIGINAL_NOT_READY", 0)
val saved = album.writeVerifiedMedia(cacheRequest(cache, detail, original))
try {
  receiptRecorder.record(detail.id, original.resourceId, saved.platformAssetRef)
} catch (failure: Throwable) {
  album.deleteSystemAlbumEntry(saved.platformAssetRef)
  throw failure
}
return completeAfterCacheRemoval(userId, detail.id)
~~~

Change MineGAppRuntime.savePrivateMediaToSystemAlbum to receive userId and the existing PrivateMediaDetail. Keep a privateMediaDetails map in MineGAppViewModel; populate it after getPrivateMediaDetail and clear it when the media is trashed or the account session ends. If no detail metadata is available, transition to SAVE_FAILED without calling Runtime or the service.

Implement PrivateMediaSaveReceiptRecorder in CoreStage05Client using RecordPrivateMediaSystemSave. Set SystemAlbumSource.VERIFIED_PRIVATE_ORIGINAL in cacheRequest. Map PRIVATE_MEDIA_ORIGINAL_NOT_READY and cache cleanup failure to SAVE_FAILED; a retry re-enters the existing-mapping branch, so it cannot duplicate the MediaStore entry.

- [ ] **Step 4: Run Android unit tests**

Run: cd Mobile/MineG_Android && ./gradlew testDebugUnitTest --tests com.mineg.mobile.app.PrivateMediaLocalSaverTest --tests com.mineg.mobile.app.MineGAppViewModelTest --tests com.mineg.mobile.Stage05ContractTest

Expected: PASS; save tests do not instantiate TransportPort and do not assert an access endpoint.

- [ ] **Step 5: Commit**

~~~bash
git add Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/app/PrivateMediaLocalSaver.kt Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/app/PrivateMediaLocalSaverTest.kt Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/account/CoreStage05Client.kt Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/app/MineGAppRuntime.kt Mobile/MineG_Android/app/src/main/java/com/mineg/mobile/app/MineGAppViewModel.kt Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/app/MineGAppViewModelTest.kt Mobile/MineG_Android/app/src/test/java/com/mineg/mobile/Stage05ContractTest.kt
git commit -m "feat(android): save private media from local cache"
~~~

## Task 5: Run the Cross-Layer Regression Suite

**Files:**
- Modify only files identified by a failing formatter, compilation check, or test as necessary to satisfy Tasks 1-4.

**Interfaces:**
- Consumes: service without DOWNLOAD, Core local receipt command, Android cache-first saver.
- Produces: passing service, Core, Android unit, API, and sovereignty checks.

- [ ] **Step 1: Format all changed production sources**

Run:

~~~bash
cd Service && gofmt -w internal/media/service.go internal/media/service_test.go api/openapi_test.go
cd Mobile/MineG_Android && ./gradlew testDebugUnitTest
~~~

Expected: Go sources are formatted and Kotlin compilation completes without adding a formatter dependency.

- [ ] **Step 2: Run complete service verification**

Run: cd Service && make openapi-check && make test

Expected: PASS; no private-media endpoint accepts purpose DOWNLOAD.

- [ ] **Step 3: Run Core and Android verification**

Run:

~~~bash
Mobile/scripts/test-core.sh
cd Mobile/MineG_Android && ./gradlew testDebugUnitTest checkAndroidDataSovereignty
~~~

Expected: PASS; C++ tests, Android unit tests, and sovereignty validation are green.

- [ ] **Step 4: Inspect the final diff and commit verification-only corrections**

Run: git diff --check && git status --short

Expected: only files named by Tasks 1-4 are modified; tmp/ remains untracked and unstaged.

~~~bash
git add Service Mobile
git commit -m "test: verify local private media saving"
~~~
