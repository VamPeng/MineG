/** Privacy-preserving media diagnostics that log only hashed identifiers. */
package com.mineg.mobile.platform.logging

import android.util.Log

internal object MediaLoadLog {
  private const val TAG = "MineGMedia"
  private const val TRACE_IMAGE_EVENTS = false

  /** Writes a debug diagnostic without allowing logging failure to affect media loading. */
  fun debug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  /** Writes a warning diagnostic without allowing logging failure to affect media loading. */
  fun warning(message: String) {
    runCatching { Log.w(TAG, message) }
  }

  /** Writes optional high-volume image events when explicitly enabled. */
  fun trace(message: String) {
    if (TRACE_IMAGE_EVENTS) debug(message)
  }

  /** Reduces a media UUID to a non-authoritative diagnostic suffix. */
  fun mediaRef(mediaId: String): String = mediaId.takeLast(8)
}
