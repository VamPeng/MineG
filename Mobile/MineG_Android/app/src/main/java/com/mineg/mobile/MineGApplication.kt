/** Process-level Coil configuration for bounded in-memory media caching. */
package com.mineg.mobile

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import kotlinx.coroutines.Dispatchers

/** Provides the process-wide image loader with a bounded memory cache. */
class MineGApplication : Application(), SingletonImageLoader.Factory {
  /** Builds Coil's singleton loader using IO work and a 20% memory-cache ceiling. */
  override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .memoryCache {
      MemoryCache.Builder()
        .maxSizeBytes(minOf(MAX_THUMBNAIL_MEMORY_CACHE_BYTES, Runtime.getRuntime().maxMemory() / 10L))
        .build()
    }
    .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(4))
    .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(1))
    .build()

  private companion object {
    const val MAX_THUMBNAIL_MEMORY_CACHE_BYTES = 32L * 1024L * 1024L
  }
}
