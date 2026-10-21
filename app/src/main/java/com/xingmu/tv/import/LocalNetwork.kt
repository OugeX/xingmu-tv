package com.xingmu.tv.import

import com.xingmu.tv.core.TvLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * 局域网地址与配对码（扫码导入用）。
 *
 * 安全约束（SOURCE_IMPORT_SPEC §2.5）：
 * - 服务只绑定**一个**站点本地（site-local 192.168/10/172.16-31）网卡地址，不监听 0.0.0.0；
 * - 只有回环/内网客户端才允许访问（[isLanClient]）。
 */
object LocalNetwork {

    private const val TAG = "LocalNetwork"
    private val random = SecureRandom()

    data class LanAddress(val ip: String, val interfaceName: String)

    /** 取当前可用的局域网 IPv4（优先 wlan/eth，跳过 rmnet/蜂窝与 VPN）。 */
    fun lanIpv4(): LanAddress? {
        val candidates = ArrayList<LanAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("ppp") || name.contains("vpn")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress && isSiteLocal(addr)) {
                        candidates.add(LanAddress(addr.hostAddress ?: continue, nif.name))
                    }
                }
            }
        } catch (t: Throwable) {
            TvLog.w(TAG, "枚举网卡失败", t)
            return null
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.interfaceName.lowercase().startsWith("wlan") }
            ?: candidates.firstOrNull { it.interfaceName.lowercase().startsWith("eth") }
            ?: candidates.first()
    }

    /** 站点本地地址（10/8、172.16/12、192.168/16）——不依赖 InetAddress.isSiteLocalAddress 的实现细节。 */
    fun isSiteLocal(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** 允许的客户端：回环或站点本地（拒绝公网/链路本地）。 */
    fun isLanClient(addr: InetAddress?): Boolean {
        if (addr == null) return false
        if (addr.isLoopbackAddress) return true
        if (addr.isLinkLocalAddress) return false
        return isSiteLocal(addr)
    }

    /** 6 位数字配对码（100000..999999，SecureRandom）。 */
    fun pairingCode(): String = "%06d".format(100000 + random.nextInt(900000))

    /** 22 字符 URL-safe 一次性 token。 */
    fun token(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val sb = StringBuilder(22)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        for (b in bytes) sb.append(alphabet[(b.toInt() and 0xFF) % alphabet.length])
        return sb.toString()
    }

    /** 服务地址（不带 token）。 */
    fun serviceUrl(ip: String, port: Int): String = "http://$ip:$port"

    fun tokenUrl(ip: String, port: Int, token: String): String = "http://$ip:$port/i/$token"

    fun formattedServiceUrl(ip: String, port: Int): String = "$ip:$port"
}
