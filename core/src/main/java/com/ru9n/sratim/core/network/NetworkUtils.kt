package com.ru9n.sratim.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address

object NetworkUtils {

    fun isInSameSubnet(context: Context, targetHost: String): Boolean {
        val targetBytes = parseIpv4Bytes(targetHost) ?: return false
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val linkProperties: LinkProperties =
                connectivityManager.getLinkProperties(activeNetwork) ?: return false

            for (linkAddress in linkProperties.linkAddresses) {
                val localAddress = linkAddress.address
                if (localAddress is Inet4Address) {
                    val localBytes = localAddress.address
                    if (isSameSubnet(localBytes, targetBytes, linkAddress.prefixLength)) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses a dotted IPv4 literal ("192.168.1.10") directly into 4 bytes
     * without invoking InetAddress / DNS resolution.
     */
    internal fun parseIpv4Bytes(host: String): ByteArray? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun isSameSubnet(b1: ByteArray, b2: ByteArray, prefixLength: Int): Boolean {
        if (b1.size != b2.size) return false
        val clampedPrefix = prefixLength.coerceIn(0, b1.size * 8)
        val fullBytes = clampedPrefix / 8
        val remainingBits = clampedPrefix % 8

        for (i in 0 until fullBytes) {
            if (b1[i] != b2[i]) return false
        }

        if (remainingBits > 0) {
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            if ((b1[fullBytes].toInt() and mask) != (b2[fullBytes].toInt() and mask)) {
                return false
            }
        }

        return true
    }
}
