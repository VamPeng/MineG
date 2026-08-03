package com.mineg.mobile.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.mineg.mobile.BuildConfig
import com.mineg.mobile.account.CoreAccountClient
import com.mineg.mobile.account.CoreStage02Client
import com.mineg.mobile.account.CoreStage04Client
import com.mineg.mobile.account.CoreStage05Client
import com.mineg.mobile.account.BackupCycleResult
import com.mineg.mobile.account.BackupQueueSummary
import com.mineg.mobile.account.BackupOverview
import com.mineg.mobile.account.LocalAlbumBackupProgress
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.BackupSettings
import com.mineg.mobile.contracts.LocalLibrarySummary
import com.mineg.mobile.contracts.LocalMedia
import com.mineg.mobile.contracts.PrivateMediaPage
import com.mineg.mobile.contracts.PrivateMediaDetail
import com.mineg.mobile.contracts.PrivateMediaView
import com.mineg.mobile.contracts.PrivateMediaSaveResult
import com.mineg.mobile.contracts.PrivateMediaTrashResult
import com.mineg.mobile.contracts.Profile
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.PlatformEffectDispatcher
import com.mineg.mobile.platform.AndroidFilePort
import com.mineg.mobile.platform.AndroidConnectivityPort
import com.mineg.mobile.platform.AndroidMediaSourcePort
import com.mineg.mobile.platform.AndroidMediaPlaybackPort
import com.mineg.mobile.platform.AndroidSecureStorePort
import com.mineg.mobile.platform.AndroidSystemAlbumWriterPort
import com.mineg.mobile.platform.AndroidTransportPort
import java.util.UUID
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface MineGAppRuntime : AutoCloseable {
  suspend fun restoreSession(): AccountRouteSnapshot?
  suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot
  suspend fun signUp(phone: String, password: String): AccountRouteSnapshot
  suspend fun refreshReviewStatus(): ApprovalStatus
  suspend fun loadProfile(userId: String, allowCached: Boolean): Profile
  suspend fun updateProfile(nickname: String): Profile
  suspend fun updateAvatar(uri: Uri): Profile
  suspend fun refreshPrivateMedia(limit: Int = 50): PrivateMediaPage
  suspend fun loadMorePrivateMedia(limit: Int = 50): PrivateMediaPage
  suspend fun getPrivateMediaPage(limit: Int = 100): PrivateMediaPage?
  suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail
  suspend fun openPrivateMedia(mediaId: String): PrivateMediaView
  suspend fun closePrivateMedia(viewHandle: String): Boolean
  suspend fun savePrivateMediaToSystemAlbum(mediaId: String): PrivateMediaSaveResult
  suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult
  suspend fun loadLocalLibrary(userId: String, forceRefresh: Boolean = false): LocalLibrarySnapshot
  suspend fun getBackupSettings(userId: String): BackupSettings
  suspend fun updateBackupSettings(userId: String, settings: BackupSettings): BackupSettings
  suspend fun getBackupOverview(userId: String): BackupOverview
  suspend fun getLocalAlbumBackupProgress(userId: String, albumRef: String): LocalAlbumBackupProgress
  fun startBackupChangeObservation(userId: String)
  suspend fun listLocalMedia(userId: String, albumRef: String, limit: Int = 120): List<LocalMedia>
  suspend fun enqueueBackupMedia(userId: String, platformAssetRef: String)
  suspend fun signOut()
  fun libraryAccess(): LibraryAccess
  fun markLibraryPermissionRequested()
}

internal data class LocalLibrarySnapshot(
  val summary: LocalLibrarySummary,
  val albums: List<LocalAlbum>,
)

internal class AndroidMineGAppRuntime(context: Context) : MineGAppRuntime {
  private val applicationContext = context.applicationContext
  private val core = CoreClient().apply {
    initialize(applicationContext.getDatabasePath("mineg-core.db").absolutePath)
  }
  private val mediaSource = AndroidMediaSourcePort(applicationContext)
  private val secureStore = AndroidSecureStorePort(applicationContext)
  private val transport = AndroidTransportPort(BuildConfig.MINEG_API_BASE_URL, allowPrivateHttp = BuildConfig.DEBUG)
  private val files = AndroidFilePort(applicationContext)
  private val connectivity = AndroidConnectivityPort(applicationContext)
  private val mediaPlayback = AndroidMediaPlaybackPort(applicationContext)
  private val systemAlbum = AndroidSystemAlbumWriterPort(applicationContext)
  private val backupScheduler = AndroidBackupScheduler(applicationContext)
  private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var scheduledBackupUserId: String? = null
  private var libraryChangeObserver: AutoCloseable? = null
  private var libraryChangeJob: Job? = null
  private val dispatcher = PlatformEffectDispatcher(
    transport,
    secureStore,
    mediaSource,
    files,
    connectivity,
    systemAlbum,
    mediaPlayback,
  )
  private val coreAccount = CoreAccountClient(core, CoreOperationRunner(core, dispatcher))
  private val coreStage02 = CoreStage02Client(core, CoreOperationRunner(core, dispatcher))
  private val coreStage04 = CoreStage04Client(core, CoreOperationRunner(core, dispatcher))
  private val coreStage05 = CoreStage05Client(core, CoreOperationRunner(core, dispatcher))

  override suspend fun restoreSession(): AccountRouteSnapshot? = coreAccount.restoreSession()

  override suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot {
    return coreAccount.signIn(phone, password, agreementAccepted)
  }

  override suspend fun signUp(phone: String, password: String): AccountRouteSnapshot {
    val passwordBytes = password.toByteArray()
    return try {
      coreAccount.signUp(phone, passwordBytes, UUID.randomUUID().toString())
    } finally {
      passwordBytes.fill(0)
    }
  }

  override suspend fun refreshReviewStatus(): ApprovalStatus = coreAccount.refreshReviewStatus()

  override suspend fun loadProfile(userId: String, allowCached: Boolean): Profile =
    coreAccount.getProfile(allowCached).also { require(it.id == userId) }

  override suspend fun updateProfile(nickname: String): Profile = coreAccount.updateProfile(nickname)

  override suspend fun updateAvatar(uri: Uri): Profile = withContext(Dispatchers.IO) {
    val resolver = applicationContext.contentResolver
    val sourceSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getLong(0) else -1L
    } ?: -1L
    require(sourceSize in 1..10L * 1024 * 1024) { "avatar source size invalid" }
    val source = ImageDecoder.createSource(resolver, uri)
    val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      val largest = max(info.size.width, info.size.height)
      if (largest > 2048) decoder.setTargetSampleSize(ceil(largest / 2048.0).toInt())
    }
    val side = min(decoded.width, decoded.height)
    val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
    val targetSide = side.coerceAtMost(1024)
    val display = if (cropped.width == targetSide) cropped else Bitmap.createScaledBitmap(cropped, targetSide, targetSide, true)
    if (cropped !== decoded && decoded !== display) decoded.recycle()
    if (display !== cropped) cropped.recycle()
    val output = ByteArrayOutputStream()
    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    check(display.compress(format, 90, output))
    display.recycle()
    val bytes = output.toByteArray()
    require(bytes.size <= 10 * 1024 * 1024) { "avatar display size invalid" }
    try {
      coreStage02.updateAvatar(bytes, sourceSize, targetSide)
    } finally {
      bytes.fill(0)
    }
  }

  override suspend fun refreshPrivateMedia(limit: Int): PrivateMediaPage =
    coreStage05.refreshPrivateMedia(limit, allowCached = true)

  override suspend fun loadMorePrivateMedia(limit: Int): PrivateMediaPage =
    coreStage05.loadMorePrivateMedia(limit, allowCached = true)

  override suspend fun getPrivateMediaPage(limit: Int): PrivateMediaPage? =
    coreStage05.getPrivateMediaPage(limit)

  override suspend fun getPrivateMediaDetail(mediaId: String): PrivateMediaDetail =
    coreStage05.getPrivateMediaDetail(mediaId)

  override suspend fun openPrivateMedia(mediaId: String): PrivateMediaView =
    coreStage05.openPrivateMedia(mediaId)

  override suspend fun closePrivateMedia(viewHandle: String): Boolean =
    coreStage05.closePrivateMedia(viewHandle)

  override suspend fun savePrivateMediaToSystemAlbum(mediaId: String): PrivateMediaSaveResult =
    coreStage05.savePrivateMediaToSystemAlbum(mediaId)

  override suspend fun trashPrivateMedia(mediaId: String): PrivateMediaTrashResult =
    coreStage05.trashPrivateMedia(mediaId)

  override suspend fun loadLocalLibrary(userId: String, forceRefresh: Boolean): LocalLibrarySnapshot = withContext(Dispatchers.IO) {
    val current = coreStage02.getLocalLibrarySummary(userId)
    val summary = if (forceRefresh || current == null) {
      coreStage02.startForegroundLocalScan(userId)
    } else {
      current
    }
    getBackupSettings(userId).also { settings ->
      if (settings.autoBackupEnabled) {
        scheduledBackupUserId = userId
        backupScheduler.schedule(userId, settings.allowCellularBackup, ensurePeriodicReconciliation = true)
      }
    }
    LocalLibrarySnapshot(
      summary = summary,
      albums = coreStage02.listLocalAlbums(userId, limit = 100).items,
    )
  }

  override suspend fun getBackupSettings(userId: String): BackupSettings = withContext(Dispatchers.IO) {
    coreStage02.getBackupSettings(userId, deviceInstallationId())
  }

  override suspend fun updateBackupSettings(
    userId: String,
    settings: BackupSettings,
  ): BackupSettings = withContext(Dispatchers.IO) {
    coreStage02.updateBackupSettings(userId, deviceInstallationId(), settings).also { confirmed ->
      if (confirmed.autoBackupEnabled) {
        scheduledBackupUserId = userId
        backupScheduler.schedule(userId, confirmed.allowCellularBackup, ensurePeriodicReconciliation = true)
      } else {
        backupScheduler.cancelPeriodicReconciliation(userId)
        // Disabling automatic backup pauses only automatic work in Core.  A user-initiated
        // one-off upload is durable work of its own and must keep its execution window.
        val summary = coreStage04.getBackupQueueSummary(userId, deviceInstallationId())
        if (summary.manualPendingCount > 0) {
          scheduledBackupUserId = userId
          backupScheduler.schedule(userId, confirmed.allowCellularBackup)
        } else {
          backupScheduler.cancel(userId)
          if (scheduledBackupUserId == userId) scheduledBackupUserId = null
        }
      }
    }
  }

  override suspend fun listLocalMedia(userId: String, albumRef: String, limit: Int): List<LocalMedia> =
    withContext(Dispatchers.IO) {
      coreStage02.listLocalMedia(userId, albumRef, limit = limit).items
    }

  override suspend fun enqueueBackupMedia(userId: String, platformAssetRef: String) = withContext(Dispatchers.IO) {
    coreStage04.enqueueBackupMedia(userId, deviceInstallationId(), platformAssetRef)
    val settings = coreStage02.getBackupSettings(userId, deviceInstallationId())
    scheduledBackupUserId = userId
    backupScheduler.schedule(userId, settings.allowCellularBackup, ensurePeriodicReconciliation = settings.autoBackupEnabled)
  }

  internal suspend fun reconcileBackupQueue(userId: String) = withContext(Dispatchers.IO) {
    coreStage04.reconcileBackupQueue(userId, deviceInstallationId())
  }

  internal suspend fun runBackupCycle(userId: String): BackupCycleResult = withContext(Dispatchers.IO) {
    coreStage04.runBackupCycle(userId, deviceInstallationId())
  }

  internal suspend fun getBackupQueueSummary(userId: String): BackupQueueSummary = withContext(Dispatchers.IO) {
    coreStage04.getBackupQueueSummary(userId, deviceInstallationId())
  }

  override suspend fun getBackupOverview(userId: String): BackupOverview = withContext(Dispatchers.IO) {
    coreStage04.getBackupOverview(userId, deviceInstallationId())
  }

  override suspend fun getLocalAlbumBackupProgress(
    userId: String,
    albumRef: String,
  ): LocalAlbumBackupProgress = withContext(Dispatchers.IO) {
    coreStage04.getLocalAlbumBackupProgress(userId, deviceInstallationId(), albumRef)
  }

  override fun startBackupChangeObservation(userId: String) {
    libraryChangeObserver?.close()
    libraryChangeObserver = mediaSource.observeLibraryChanges {
      libraryChangeJob?.cancel()
      libraryChangeJob = runtimeScope.launch {
        delay(1_000)
        coreStage04.notifyLibraryChanged(userId, deviceInstallationId())
        val settings = coreStage02.getBackupSettings(userId, deviceInstallationId())
        if (settings.autoBackupEnabled) {
          backupScheduler.schedule(userId, settings.allowCellularBackup, ensurePeriodicReconciliation = true)
        }
      }
    }
  }

  override suspend fun signOut() {
    scheduledBackupUserId?.let(backupScheduler::cancel)
    scheduledBackupUserId = null
    libraryChangeObserver?.close()
    libraryChangeObserver = null
    libraryChangeJob?.cancel()
    coreAccount.signOut()
  }

  override fun libraryAccess(): LibraryAccess = when (mediaSource.getPermissionSnapshot().library) {
    LibraryPermissionState.NOT_DETERMINED -> LibraryAccess.NOT_DETERMINED
    LibraryPermissionState.FULL -> LibraryAccess.FULL
    LibraryPermissionState.LIMITED -> LibraryAccess.LIMITED
    LibraryPermissionState.RESTRICTED -> LibraryAccess.RESTRICTED
    LibraryPermissionState.DENIED -> LibraryAccess.DENIED
    LibraryPermissionState.SYSTEM_RESTRICTED -> LibraryAccess.SYSTEM_RESTRICTED
  }

  override fun markLibraryPermissionRequested() {
    mediaSource.markPermissionRequested()
  }

  private fun deviceInstallationId(): String =
    checkNotNull(secureStore.readSecret("device.installationId"))
      .toString(Charsets.UTF_8)
      .also { require(it.isNotBlank()) }

  override fun close() {
    libraryChangeObserver?.close()
    libraryChangeJob?.cancel()
    runtimeScope.cancel()
    dispatcher.close()
    core.close()
  }
}
