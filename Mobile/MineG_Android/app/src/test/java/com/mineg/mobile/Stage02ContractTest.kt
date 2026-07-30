package com.mineg.mobile

import com.mineg.mobile.contracts.BackgroundSchedulerPort
import com.mineg.mobile.contracts.BackupSettings
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.contracts.MediaSourcePort
import com.mineg.mobile.contracts.Stage02Client
import com.mineg.mobile.core.CoreClient
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage02ContractTest {
  private val manifest by lazy { resource("stage02-v1.json") }

  @Test
  fun baselineRegistersKeysPermissionSettingsAndLocalMediaSurface() {
    listOf(
      "completeFamilyKeyGrant", "updateAvatar", "getPermissionSnapshot", "requestFullLibraryAccess",
      "getBackupSettings", "updateBackupSettings", "scanLocalMedia", "listLocalAlbums", "listLocalMedia",
      "FAMILY_BOOTSTRAP", "MEMBER_GRANT", "NOT_DETERMINED", "FULL", "LIMITED", "RESTRICTED",
      "DENIED", "SYSTEM_RESTRICTED", "backup.overview", "backup.album.grid",
      "mineg_core_unlock_user_key_bundle", "mineg_core_create_family_key_envelope",
    ).forEach { assertContains(manifest, "\"$it\"") }
    assertContains(manifest, "\"status\": \"FROZEN\"")
  }

  @Test
  fun publicInterfacesExposeRegisteredMethodsWithoutPlatformTypes() {
    val clientMethods = Stage02Client::class.java.methods.map { it.name }.toSet()
    assertTrue(
      clientMethods.containsAll(
        setOf("updateProfile", "updateAvatar", "getKeyBundle", "completeFamilyKeyGrant", "getBackupSettings", "updateBackupSettings", "scanLocalMedia", "listLocalAlbums", "listLocalMedia"),
      ),
    )
    val mediaMethods = MediaSourcePort::class.java.methods.map { it.name }.toSet()
    assertTrue(mediaMethods.containsAll(setOf("getPermissionSnapshot", "requestFullLibraryAccess", "listAlbums", "listMedia")))
    val schedulerMethods = BackgroundSchedulerPort::class.java.methods.map { it.name }.toSet()
    assertContains(schedulerMethods, "configureBackup")
    listOf(Stage02Client::class.java, MediaSourcePort::class.java, BackgroundSchedulerPort::class.java).forEach { type ->
      type.methods.flatMap { it.parameterTypes.asList() + it.returnType }.forEach {
        assertFalse(it.name.startsWith("android."), "${type.simpleName} exposes ${it.name}")
      }
    }
  }

  @Test
  fun coreAndPermissionEnumsMatchTheBaseline() {
    val names = CoreClient::class.java.declaredMethods
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .toSet()
    assertTrue(names.containsAll(setOf("unlockUserKeyBundle", "restoreUserKeyBundle", "unlockFamilyKeyEnvelope", "createFamilyKeyEnvelope", "lockKeys")))
    assertEquals(
      listOf("NOT_DETERMINED", "FULL", "LIMITED", "RESTRICTED", "DENIED", "SYSTEM_RESTRICTED"),
      LibraryPermissionState.entries.map { it.name },
    )
  }

  @Test
  fun currentBackupSettingsDefaultToManualOptIn() {
    assertFalse(BackupSettings().autoBackupEnabled)
    assertFalse(BackupSettings().allowCellularBackup)
  }

  @Test
  fun sqliteV3StoresIndexesAndSettingsButNoDecryptedSecretsOrMediaBytes() {
    val migration = resource("003_local_media.sql")
    listOf("backup_settings", "local_albums", "local_media", "local_media_albums", "local_scan_state", "download_receipts", "PRAGMA user_version=3").forEach {
      assertContains(migration, it)
    }
    listOf("private_key", "user_master_key", "family_key", "password", "media_bytes", "plaintext").forEach {
      assertFalse(migration.contains(it, ignoreCase = true), "migration contains forbidden field $it")
    }
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
}
