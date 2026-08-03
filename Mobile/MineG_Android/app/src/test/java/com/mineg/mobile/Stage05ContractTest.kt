package com.mineg.mobile

import com.mineg.mobile.account.CoreStage05Client
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.lang.reflect.Modifier

class Stage05ContractTest {
  @Test
  fun baselineKeepsPrivateMediaOwnershipAndShortLivedObjectGrantsInCore() {
    val contract = resource("stage05-v1.json")
    listOf(
      "stage05-v1", "FROZEN", "C++ Core", "RefreshPrivateMedia",
      "OpenPrivateMedia", "ClosePrivateMedia", "SavePrivateMediaToSystemAlbum", "TrashPrivateMedia", "DOWNLOAD",
      "maxGrantLifetimeSeconds", "PRIVATE_MEDIA_DOWNLOAD_INTEGRITY_FAILED",
    ).forEach { assertContains(contract, "\"$it\"") }
    assertFalse(contract.contains("Media Key"))
  }

  @Test
  fun sqliteMigrationPersistsOnlyDurablePrivateMediaTruth() {
    val migration = resource("013_private_media_stage05.sql")
    listOf(
      "private_media_items_v2", "private_media_page_state_v2", "private_media_resources",
      "private_media_save_operations", "content_revision", "PRAGMA user_version=13",
    ).forEach { assertContains(migration, it) }
    assertTrue(!migration.contains("signed_url") && !migration.contains("object_key"))
  }

  @Test
  fun androidExposesOnlyCoreOwnedPageOperations() {
    val publicMethods = CoreStage05Client::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(publicMethods.containsAll(setOf(
      "refreshPrivateMedia", "loadMorePrivateMedia", "getPrivateMediaPage",
      "getPrivateMediaDetail", "openPrivateMedia", "closePrivateMedia", "trashPrivateMedia",
    )))
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}
