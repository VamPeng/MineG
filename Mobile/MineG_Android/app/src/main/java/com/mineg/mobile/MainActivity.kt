/** Android entry Activity responsible only for permissions, lifecycle forwarding and Compose. */
package com.mineg.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineg.mobile.presentation.LibraryAccess
import com.mineg.mobile.presentation.MineGApp
import com.mineg.mobile.presentation.MineGAppViewModel
import com.mineg.mobile.ui.theme.MineGTheme

/** Hosts the root Compose tree and bridges Android permission/lifecycle callbacks to ViewModel. */
class MainActivity : ComponentActivity() {
  private val viewModel by viewModels<MineGAppViewModel> { MineGAppViewModel.factory(this) }

  /** Creates the root UI and registers the platform library-permission launcher. */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MineGTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          val state by viewModel.state.collectAsStateWithLifecycle()
          val context = LocalContext.current
          val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.onLibraryPermissionResult()
          }
          val requestLibraryAccess = {
            viewModel.markLibraryPermissionRequested()
            if (state.libraryAccess in setOf(LibraryAccess.DENIED, LibraryAccess.LIMITED)) {
              context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
              )
            } else {
              permissionLauncher.launch(
                if (Build.VERSION.SDK_INT >= 33) {
                  arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                  arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                },
              )
            }
          }
          LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }
          MineGApp(viewModel, requestLibraryAccess)
        }
      }
    }
  }
}
