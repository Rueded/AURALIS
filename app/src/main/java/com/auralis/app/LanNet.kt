package com.auralis.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网通信的小工具。
 *
 * 为什么需要：手机连着一个"已连接，但无法访问互联网"的 Wi-Fi（校园网要登录、路由器没接外网……）时，
 * Android 会把【默认网络】切到移动数据。App 里普通的 Socket 走默认网络，
 * 于是访问 192.168.x.x 这种局域网地址的请求实际是从移动数据发出去的——当然连不上。
 * 把 Socket 绑定到 Wi-Fi 网络上就能解决。
 */
object LanNet {

    /** 当前连着的 Wi-Fi 网络；本机自己开热点（没有连别人的 Wi-Fi）时返回 null。 */
    @Suppress("DEPRECATION")
    fun wifiNetwork(context: Context): Network? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** 基于 [base] 派生一个把 Socket 绑到 Wi-Fi 上的客户端（共享连接池和线程池，开销很小）。 */
    fun client(context: Context, base: OkHttpClient): OkHttpClient {
        val net = wifiNetwork(context) ?: return base
        return base.newBuilder().socketFactory(net.socketFactory).build()
    }

    /**
     * 只有目标地址确实在这个 Wi-Fi 的子网里才绑定到它；回环地址、本机自己开的热点、其它网络一律走默认路由。
     * 以前 [client] 是"只要连着 Wi-Fi 就把所有请求都绑到 Wi-Fi 上"，目标不在这个网络里时反而会连不上。
     */
    @Suppress("DEPRECATION")
    fun clientFor(context: Context, base: OkHttpClient, host: String): OkHttpClient {
        val h = host.substringBefore('%')
        if (h.isBlank() || h.startsWith("127.") || h == "::1" || h == "localhost") return base
        if (!h.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }) return base // 不是 IP 字面量，不做 DNS
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = wifiNetwork(context) ?: return base
        val lp = cm.getLinkProperties(net) ?: return base
        val target = try { java.net.InetAddress.getByName(h) } catch (_: Exception) { return base }
        val inSubnet = lp.linkAddresses.any { la -> sameSubnet(la.address, la.prefixLength, target) }
        return if (inSubnet) base.newBuilder().socketFactory(net.socketFactory).build() else base
    }

    fun sameSubnet(a: java.net.InetAddress, prefix: Int, b: java.net.InetAddress): Boolean {
        val x = a.address; val y = b.address
        if (x.size != y.size) return false
        var bits = prefix.coerceIn(0, x.size * 8)
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8; i++
        }
        return true
    }

    data class LocalAddr(val ip: String, val prefix: Int)

    /** 本机在局域网里的 IPv4 地址（Wi-Fi 或自己开的热点），排除移动数据/VPN 等接口。 */
    fun localIpv4(): List<LocalAddr> {
        val result = mutableListOf<LocalAddr>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                val name = ni.name.lowercase()
                if (!ni.isUp || ni.isLoopback) return@forEach
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("dummy") ||
                    name.startsWith("ccmni") || name.startsWith("v4-")) return@forEach
                ni.interfaceAddresses.forEach { ia ->
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        result += LocalAddr(a.hostAddress ?: return@forEach, ia.networkPrefixLength.toInt())
                    }
                }
            }
        } catch (_: Exception) {}
        return result.distinctBy { it.ip }
    }
}
