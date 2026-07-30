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
      "CoordinateFamilyKeyGrants",
      "ProfileUpdateAvatar",
      "PrivateMediaList",
      "ListPrivateMediaSnapshot",
      "uploadObject",
      "PrivateMediaSnapshotChanged",
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "\"status\": \"BASELINED\"")
  }

  @Test
  fun bridgeExposesOnlySnapshotAndOperationMethods() {
    val methods = CoreStage02Client::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(
      methods.containsAll(
        setOf("coordinateFamilyKeyGrants", "updateAvatar", "listPrivateMedia"),
      ),
    )
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

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}
