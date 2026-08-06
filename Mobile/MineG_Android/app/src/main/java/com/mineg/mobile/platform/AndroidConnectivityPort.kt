/** Android connectivity snapshot adapter for the platform port boundary. */
package com.mineg.mobile.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mineg.mobile.platform.port.ConnectivityPort
import com.mineg.mobile.platform.port.ConnectivitySnapshot

/** Reads active network capability without making backup policy decisions. */
class AndroidConnectivityPort(context: Context) : ConnectivityPort {
  private val manager = context.getSystemService(ConnectivityManager::class.java)

  override fun getConnectivitySnapshot(): ConnectivitySnapshot {
    val network = manager.activeNetwork ?: return ConnectivitySnapshot(false, false)
    val capabilities = manager.getNetworkCapabilities(network) ?: return ConnectivitySnapshot(false, false)
    return ConnectivitySnapshot(
      connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
      metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
  }
}
