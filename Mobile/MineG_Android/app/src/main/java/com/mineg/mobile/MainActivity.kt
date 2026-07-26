package com.mineg.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
  private val viewModel by viewModels<FoundationViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        val permissionLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestMultiplePermissions(),
        ) { viewModel.runProbe() }
        FoundationScreen(
          viewModel = viewModel,
          onRun = { permissionLauncher.launch(mediaPermissions()) },
        )
      }
    }
  }

  private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.READ_MEDIA_VIDEO,
      Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
  }
}

@Composable
private fun FoundationScreen(viewModel: FoundationViewModel, onRun: () -> Unit) {
  val uiState by viewModel.state.collectAsStateWithLifecycle()
  Surface(modifier = Modifier.fillMaxSize().testTag("foundation.probe")) {
    Column(
      modifier = Modifier.padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Text(
        text = "MineG 基座纵向验证",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
      )
      Text("这是工程探针，不是登录、备份或媒体业务页面。")
      Card(modifier = Modifier.fillMaxWidth().testTag("foundation.probe.status")) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(uiState.state.name, style = MaterialTheme.typography.labelLarge)
          Text(uiState.message)
          uiState.lastEvent?.let { Text("最近事件已收到", style = MaterialTheme.typography.bodySmall) }
        }
      }
      Button(
        onClick = onRun,
        enabled = uiState.state != FoundationPageState.LOADING,
        modifier = Modifier.fillMaxWidth().testTag("foundation.probe.run"),
      ) {
        if (uiState.state == FoundationPageState.LOADING) {
          CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        }
        Text("运行探针")
      }
    }
  }
}
