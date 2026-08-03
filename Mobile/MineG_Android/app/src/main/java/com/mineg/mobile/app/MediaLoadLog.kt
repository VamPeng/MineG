package com.mineg.mobile.app

import android.util.Log

internal object MediaLoadLog {
  private const val TAG = "MineGMedia"
  private const val TRACE_IMAGE_EVENTS = false

  fun debug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  fun warning(message: String) {
    runCatching { Log.w(TAG, message) }
  }

  fun trace(message: String) {
    if (TRACE_IMAGE_EVENTS) debug(message)
  }

  fun mediaRef(mediaId: String): String = mediaId.takeLast(8)
}
