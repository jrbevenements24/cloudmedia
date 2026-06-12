package com.cloudmedia.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Net {
    enum class Type { WIFI, MOBILE, AUTRE, AUCUN }

    fun current(context: Context): Type {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return Type.AUCUN
        val caps = cm.getNetworkCapabilities(net) ?: return Type.AUCUN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> Type.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Type.WIFI  // assimilé "illimité"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Type.MOBILE
            else -> Type.AUTRE
        }
    }

    fun isWifi(context: Context) = current(context) == Type.WIFI
}
