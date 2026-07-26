package com.mineg.mobile.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mineg.mobile.contracts.ConnectivityPort
import com.mineg.mobile.contracts.ConnectivitySnapshot

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
