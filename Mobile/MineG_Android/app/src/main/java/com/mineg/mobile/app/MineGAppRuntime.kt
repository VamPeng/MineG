package com.mineg.mobile.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.mineg.mobile.BuildConfig
import com.mineg.mobile.account.AndroidAccountClient
import com.mineg.mobile.account.CoreAccountClient
import com.mineg.mobile.account.CoreStage02Client
import com.mineg.mobile.contracts.AccountRouteSnapshot
import com.mineg.mobile.contracts.ApprovalStatus
import com.mineg.mobile.contracts.LibraryPermissionState
import com.mineg.mobile.contracts.LocalAlbum
import com.mineg.mobile.contracts.LocalScanState
import com.mineg.mobile.contracts.OwnerMediaSummary
import com.mineg.mobile.contracts.Profile
import com.mineg.mobile.core.CoreClient
import com.mineg.mobile.core.CoreOperationRunner
import com.mineg.mobile.core.PlatformEffectDispatcher
import com.mineg.mobile.platform.AndroidFilePort
import com.mineg.mobile.platform.AndroidMediaSourcePort
import com.mineg.mobile.platform.AndroidSecureStorePort
import com.mineg.mobile.platform.AndroidTransportPort
import java.util.UUID
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface MineGAppRuntime : AutoCloseable {
  suspend fun restoreSession(): AccountRouteSnapshot?
  suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot
  suspend fun signUp(phone: String, password: String): AccountRouteSnapshot
  suspend fun refreshReviewStatus(): ApprovalStatus
  suspend fun loadProfile(userId: String, allowCached: Boolean): Profile
  suspend fun updateProfile(nickname: String): Profile
  suspend fun updateAvatar(uri: Uri): Profile
  suspend fun listOwnerMedia(limit: Int = 100): List<OwnerMediaSummary>
  suspend fun refreshLocalLibrary(userId: String): LocalLibrarySnapshot
  suspend fun signOut()
  fun libraryAccess(): LibraryAccess
  fun markLibraryPermissionRequested()
}

internal data class LocalLibrarySnapshot(
  val scan: LocalScanState,
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
  private val dispatcher = PlatformEffectDispatcher(transport, secureStore, mediaSource, files)
  private val coreAccount = CoreAccountClient(core, CoreOperationRunner(core, dispatcher))
  private val coreStage02 = CoreStage02Client(core, CoreOperationRunner(core, dispatcher))
  private val account = AndroidAccountClient(
    core = core,
    secureStore = secureStore,
    transport = transport,
    mediaSource = mediaSource,
    files = files,
  )

  override suspend fun restoreSession(): AccountRouteSnapshot? = coreAccount.restoreSession()?.also {
    runCatching { coreStage02.coordinateFamilyKeyGrants(null) }
  }

  override suspend fun signIn(phone: String, password: String, agreementAccepted: Boolean): AccountRouteSnapshot {
    val route = coreAccount.signIn(phone, password, agreementAccepted)
    val passwordBytes = password.toByteArray()
    try {
      runCatching {
        coreStage02.coordinateFamilyKeyGrants(passwordBytes)
      }
    } finally {
      passwordBytes.fill(0)
    }
    return route
  }

  override suspend fun signUp(phone: String, password: String): AccountRouteSnapshot {
    val passwordBytes = password.toByteArray()
    return try {
      coreAccount.signUp(phone, passwordBytes, UUID.randomUUID().toString()).also {
        runCatching {
          coreStage02.coordinateFamilyKeyGrants(passwordBytes)
        }
      }
    } finally {
      passwordBytes.fill(0)
    }
  }

  override suspend fun refreshReviewStatus(): ApprovalStatus {
    runCatching {
      coreStage02.coordinateFamilyKeyGrants(null)
    }
    return coreAccount.refreshReviewStatus()
  }

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

  override suspend fun listOwnerMedia(limit: Int): List<OwnerMediaSummary> =
    coreStage02.listPrivateMedia(limit, allowCached = true)

  override suspend fun refreshLocalLibrary(userId: String): LocalLibrarySnapshot = withContext(Dispatchers.IO) {
    LocalLibrarySnapshot(
      scan = account.scanLocalMedia(userId),
      albums = account.listLocalAlbums(userId, limit = 100).items,
    )
  }

  override suspend fun signOut() {
    try {
      coreAccount.signOut()
    } finally {
      account.dropTransitionalSession()
    }
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

  override fun close() {
    dispatcher.close()
    core.close()
  }
}
