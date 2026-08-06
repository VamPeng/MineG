/** Core-backed backup preference access, isolated from backup queue execution. */
package com.mineg.mobile.bridge.backup

import com.mineg.mobile.bridge.library.model.BackupSettings
import com.mineg.mobile.core.CoreClient
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Reads and updates the per-account, per-installation backup preferences stored by Core. */
class BackupSettingsCoreGateway(private val core: CoreClient) {
  private val operationIds = AtomicLong(2_200_000_000L)

  /** Returns the authoritative settings snapshot for one app installation. */
  fun getSettings(userId: String, deviceInstallationId: String): BackupSettings {
    val settings = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", CONTRACT_VERSION)
          .put("type", "GetBackupSettings")
          .put("userId", userId)
          .put("deviceInstallationId", deviceInstallationId)
          .toString(),
      ),
    ).getJSONObject("settings")
    return settings.toBackupSettings()
  }

  /** Persists both backup switches atomically and reads back the committed Core snapshot. */
  fun updateSettings(
    userId: String,
    deviceInstallationId: String,
    settings: BackupSettings,
  ): BackupSettings {
    // The timestamp represents this settings mutation, while Core remains the durable owner.
    core.execute(
      operationIds.getAndIncrement(),
      JSONObject()
        .put("contractVersion", CONTRACT_VERSION)
        .put("type", "UpdateBackupSettings")
        .put("userId", userId)
        .put("deviceInstallationId", deviceInstallationId)
        .put("autoBackupEnabled", settings.autoBackupEnabled)
        .put("allowCellularBackup", settings.allowCellularBackup)
        .put("updatedAt", Instant.now().toString())
        .toString(),
    )
    return getSettings(userId, deviceInstallationId)
  }

  /** Maps the Core settings object without supplying Android-owned defaults. */
  private fun JSONObject.toBackupSettings() = BackupSettings(
    autoBackupEnabled = getBoolean("autoBackupEnabled"),
    allowCellularBackup = getBoolean("allowCellularBackup"),
    updatedAt = if (isNull("updatedAt")) null else getString("updatedAt"),
  )

  private companion object {
    const val CONTRACT_VERSION = "stage02-v2"
  }
}
