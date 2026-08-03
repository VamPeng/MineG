package com.mineg.mobile

import com.mineg.mobile.account.CoreStage02Client
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class Stage02V2ContractTest {
  private val manifest by lazy { resource("stage02-v2.json") }

  @Test
  fun manifestMovesBatchCDomainOwnershipIntoCore() {
    listOf(
      "stage02-v2",
      "ProfileUpdateAvatar",
      "PrivateMediaList",
      "ListPrivateMediaSnapshot",
      "uploadObject",
      "PrivateMediaSnapshotChanged",
      "StartForegroundLocalScan",
      "GetLocalLibrarySummary",
      "UpdateBackupSettings",
      "LocalLibraryIndexChanged",
      "MediaSourceEffect",
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "\"contractVersion\": \"2.1.0\"")
    assertContains(manifest, "\"status\": \"FROZEN\"")
  }

  @Test
  fun bridgeExposesOnlySnapshotAndOperationMethods() {
    val methods = CoreStage02Client::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(
      methods.containsAll(
        setOf(
          "updateAvatar", "listPrivateMedia",
          "startForegroundLocalScan", "getLocalLibrarySummary", "getBackupSettings",
          "updateBackupSettings", "listLocalAlbums", "listLocalMedia",
        ),
      ),
    )
    assertTrue("coordinateFamilyKeyGrants" !in methods)
  }

  @Test
  fun sqliteV7MigrationDefinesAccountIsolatedPrivateMediaSnapshot() {
    val migration = resource("007_stage02_home_keys.sql")
    listOf(
      "private_media_snapshots",
      "private_media_cache_state",
      "PRIMARY KEY (user_id, media_id)",
      "PRAGMA user_version = 7",
    ).forEach { assertContains(migration, it) }
  }

  @Test
  fun sqliteV8StoresOnlyCompletedGenerationsAndNoExecutionCursor() {
    val migration = resource("008_batch_d_local_index.sql")
    listOf(
      "local_library_active",
      "generation_id",
      "PRIMARY KEY(user_id, generation_id, platform_asset_ref)",
      "PRAGMA user_version=8",
    ).forEach { assertContains(migration.replace(" ", ""), it.replace(" ", "")) }
    listOf("cursor_modified_version", "cursor_asset_ref", "BLOCKED_PERMISSION")
      .forEach { value -> kotlin.test.assertFalse(migration.contains(value)) }
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}
