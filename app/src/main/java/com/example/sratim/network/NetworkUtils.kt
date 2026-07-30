package com.example.sratim.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.math.pow

object NetworkUtils {

    fun isInSameSubnet(context: Context, targetHost: String): Boolean {
        try {
            val targetAddress = InetAddress.getByName(targetHost)
            if (targetAddress !is Inet4Address) return false // Support IPv4 for now

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties: LinkProperties = connectivityManager.getLinkProperties(connectivityManager.activeNetwork) ?: return false
            
            for (linkAddress in linkProperties.linkAddresses) {
                val localAddress = linkAddress.address
                if (localAddress is Inet4Address) {
                    val prefixLength = linkAddress.prefixLength
                    if (isSameSubnet(localAddress, targetAddress, prefixLength)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun isSameSubnet(addr1: InetAddress, addr2: InetAddress, prefixLength: Int): Boolean {
        val b1 = addr1.address
        val b2 = addr2.address
        if (b1.size != b2.size) return false

        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8

        for (i in 0 until fullBytes) {
            if (b1[i] != b2[i]) return false
        }

        if (remainingBits > 0) {
            val mask = (0xFF shl (8 - remainingBits)).toByte()
            if ((b1[fullBytes].toInt() and mask.toInt()) != (b2[fullBytes].toInt() and mask.toInt())) {
                return false
            }
        }

        return true
    }
}
