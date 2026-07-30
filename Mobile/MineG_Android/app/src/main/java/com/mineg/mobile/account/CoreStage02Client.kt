package com.mineg.mobile.account

import com.mineg.mobile.contracts.AccountProblem
import com.mineg.mobile.contracts.CoreOperationStatus
import com.mineg.mobile.contracts.OwnerMediaSummary
import com.mineg.mobile.contracts.Profile
import com.mineg.mobile.contracts.AlbumCursor
import com.mineg.mobile.contracts.BackupSettings
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.LocalAlbumPage
import com.mineg.mobile.contracts.LocalLibrarySummary
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.contracts.LocalMediaAvailability
import com.mineg.mobile.contracts.LocalMediaCursor
import com.mineg.mobile.contracts.LocalMediaPage
import com.mineg.mobile.contracts.LocalMediaType
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

class CoreStage02Client(
  private val core: CoreClient,
  private val runner: CoreOperationRunner,
) {
  private val operationIds = AtomicLong(2_000_000_000L)

  suspend fun coordinateFamilyKeyGrants(password: ByteArray?): Int {
    val command = JSONObject()
      .put("contractVersion", STAGE02_V2)
      .put("type", "CoordinateFamilyKeyGrants")
    password?.let { command.put("password", it.toString(Charsets.UTF_8)) }
    val result = runCommand(command) ?: return 0
    return result.getInt("completedCount")
  }

  suspend fun listPrivateMedia(limit: Int = 100, allowCached: Boolean = true): List<OwnerMediaSummary> {
    val result = runCommand(
      JSONObject()
        .put("contractVersion", STAGE02_V2)
        .put("type", "PrivateMediaList")
        .put("limit", limit.coerceIn(1, 100))
        .put("allowCached", allowCached),
    ) ?: return emptyList()
    val items = result.getJSONArray("items")
    return List(items.length()) { index ->
      items.getJSONObject(index).run {
        OwnerMediaSummary(
          id = getString("id"),
          mediaType = getString("mediaType"),
          contentRevision = getInt("contentRevision"),
          capturedAt = getString("capturedAt"),
          createdAt = getString("createdAt"),
        )
      }
    }
  }

  suspend fun updateAvatar(
    displayBytes: ByteArray,
    sourceSize: Long,
    width: Int,
    contentType: String = "image/webp",
  ): Profile {
    val result = runCommand(
      JSONObject()
        .put("contractVersion", STAGE02_V2)
        .put("type", "ProfileUpdateAvatar")
        .put("displayBase64", Base64.getEncoder().withoutPadding().encodeToString(displayBytes))
        .put("sourceSize", sourceSize)
        .put("width", width)
        .put("contentType", contentType)
        .put("idempotencyKey", java.util.UUID.randomUUID().toString()),
    ) ?: throw AccountProblem("PROFILE_MISSING", "account.profile.missing", false, "")
    return result.toProfile()
  }

  suspend fun startForegroundLocalScan(userId: String): LocalLibrarySummary {
    require(userId.isNotBlank())
    val result = runCommand(
      JSONObject()
        .put("contractVersion", STAGE02_V2)
        .put("type", "StartForegroundLocalScan")
        .put("userId", userId),
    ) ?: error("Foreground scan completed without a summary")
    return result.toLocalLibrarySummary()
  }

  fun getLocalLibrarySummary(userId: String): LocalLibrarySummary? {
    val result = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", STAGE02_V2)
          .put("type", "GetLocalLibrarySummary")
          .put("userId", userId)
          .toString(),
      ),
    )
    return result.optJSONObject("snapshot")?.toLocalLibrarySummary()
  }

  fun getBackupSettings(userId: String, deviceInstallationId: String): BackupSettings {
    val settings = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", STAGE02_V2)
          .put("type", "GetBackupSettings")
          .put("userId", userId)
          .put("deviceInstallationId", deviceInstallationId)
          .toString(),
      ),
    ).getJSONObject("settings")
    return BackupSettings(
      autoBackupEnabled = settings.getBoolean("autoBackupEnabled"),
      allowCellularBackup = settings.getBoolean("allowCellularBackup"),
      updatedAt = if (settings.isNull("updatedAt")) null else settings.getString("updatedAt"),
    )
  }

  fun updateBackupSettings(
    userId: String,
    deviceInstallationId: String,
    settings: BackupSettings,
  ): BackupSettings {
    core.execute(
      operationIds.getAndIncrement(),
      JSONObject()
        .put("contractVersion", STAGE02_V2)
        .put("type", "UpdateBackupSettings")
        .put("userId", userId)
        .put("deviceInstallationId", deviceInstallationId)
        .put("autoBackupEnabled", settings.autoBackupEnabled)
        .put("allowCellularBackup", settings.allowCellularBackup)
        .put("updatedAt", Instant.now().toString())
        .toString(),
    )
    return getBackupSettings(userId, deviceInstallationId)
  }

  fun listLocalAlbums(userId: String, cursor: AlbumCursor? = null, limit: Int = 50): LocalAlbumPage {
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", STAGE02_V2)
          .put("type", "ListLocalAlbums")
          .put("userId", userId)
          .put("cursorName", cursor?.name.orEmpty())
          .put("cursorAlbumRef", cursor?.platformAlbumRef.orEmpty())
          .put("limit", limit.coerceIn(1, 100))
          .toString(),
      ),
    )
    val values = payload.getJSONArray("items")
    val items = List(values.length()) { index ->
      values.getJSONObject(index).run {
        LocalAlbum(
          platformAlbumRef = getString("platformAlbumRef"),
          name = getString("name"),
          mediaCount = getLong("mediaCount"),
          coverThumbnailUri = if (isNull("coverThumbnailUri")) null else getString("coverThumbnailUri"),
        )
      }
    }
    val next = payload.optJSONObject("nextCursor")?.run {
      AlbumCursor(getString("name"), getString("platformAlbumRef"))
    }
    return LocalAlbumPage(items, next)
  }

  fun listLocalMedia(
    userId: String,
    albumRef: String?,
    cursor: LocalMediaCursor? = null,
    limit: Int = 60,
  ): LocalMediaPage {
    val payload = JSONObject(
      core.query(
        JSONObject()
          .put("contractVersion", STAGE02_V2)
          .put("type", "ListLocalMedia")
          .put("userId", userId)
          .put("platformAlbumRef", albumRef.orEmpty())
          .put("cursorCapturedAt", cursor?.capturedAt.orEmpty())
          .put("cursorAssetRef", cursor?.platformAssetRef.orEmpty())
          .put("limit", limit.coerceIn(1, 500))
          .toString(),
      ),
    )
    val values = payload.getJSONArray("items")
    val items = List(values.length()) { index ->
      values.getJSONObject(index).run {
        LocalMedia(
          platformAssetRef = getString("platformAssetRef"),
          mediaType = LocalMediaType.valueOf(getString("mediaType")),
          mimeType = getString("mimeType"),
          width = getInt("width"),
          height = getInt("height"),
          durationMs = if (isNull("durationMs")) null else getLong("durationMs"),
          capturedAt = getString("capturedAt"),
          modifiedAt = getString("modifiedAt"),
          contentVersion = getString("contentVersion"),
          availability = LocalMediaAvailability.valueOf(getString("availability")),
          thumbnailUri = if (isNull("thumbnailUri")) null else getString("thumbnailUri"),
        )
      }
    }
    val next = payload.optJSONObject("nextCursor")?.run {
      LocalMediaCursor(getString("capturedAt"), getString("platformAssetRef"))
    }
    return LocalMediaPage(items, next)
  }

  private suspend fun runCommand(command: JSONObject): JSONObject? {
    val operationId = operationIds.getAndIncrement()
    val terminal = runner.run(core.startOperation(operationId, command.toString()))
    return when (terminal.status) {
      CoreOperationStatus.COMPLETED -> terminal.resultJson?.let(::JSONObject)
      CoreOperationStatus.FAILED -> throw terminal.toProblem()
      CoreOperationStatus.CANCELLED ->
        throw AccountProblem("CANCELLED", "account.cancelled", false, "")
      CoreOperationStatus.WAITING_FOR_EFFECT -> error("CoreOperationRunner returned a pending operation")
    }
  }

  private fun com.mineg.mobile.contracts.CoreOperationStep.toProblem(): AccountProblem {
    val error = errorJson?.let(::JSONObject) ?: JSONObject()
    return AccountProblem(
      code = error.optString("code", "INTERNAL_ERROR"),
      messageKey = error.optString("messageKey", "account.internal.error"),
      retryable = error.optBoolean("retryable", false),
      requestId = error.optString("requestId"),
    )
  }

  private fun JSONObject.toProfile() = Profile(
    id = getString("id"),
    nickname = getString("nickname"),
    maskedPhone = getString("maskedPhone"),
    avatarUrl = if (isNull("avatarUrl")) null else getString("avatarUrl").ifBlank { null },
  )

  private fun JSONObject.toLocalLibrarySummary() = LocalLibrarySummary(
    generationId = getString("generationId"),
    indexedCount = getLong("indexedCount"),
    completedAt = getString("completedAt"),
  )

  private companion object {
    const val STAGE02_V2 = "stage02-v2"
  }
}
