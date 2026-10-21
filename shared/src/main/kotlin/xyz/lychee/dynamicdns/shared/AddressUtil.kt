package xyz.lychee.dynamicdns.shared

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.UnknownHostException

object AddressUtil {
    @JvmStatic
    fun parseAddressAsUri(ip: String): URI {
        val uri = URI.create("tcp://$ip")
        checkNotNull(uri.host) { "Invalid hostname/IP $ip" }
        return uri
    }

    @JvmStatic
    fun parseAddressFromUri(uri: URI): InetSocketAddress {
        val port = if (uri.port == -1) 25565 else uri.port
        return try {
            val ia = InetAddress.getByName(uri.host)
            InetSocketAddress(ia, port)
        } catch (e: UnknownHostException) {
            InetSocketAddress.createUnresolved(uri.host, port)
        }
    }

    @JvmStatic
    fun parseAddress(ip: String): InetSocketAddress {
        return parseAddressFromUri(parseAddressAsUri(ip))
    }

    @JvmStatic
    fun parseAndResolveAddress(ip: String): InetSocketAddress {
        val uri = parseAddressAsUri(ip)
        val port = if (uri.port == -1) 25565 else uri.port
        return InetSocketAddress(uri.host, port)
    }

    @JvmStatic
    fun checkPublicIp(): InetAddress {
        return try {
            BufferedReader(
                InputStreamReader(URI.create("https://checkip.amazonaws.com").toURL().openStream())
            ).use { reader ->
                InetAddress.getByName(reader.readLine().trim())
            }
        } catch (e: IOException) {
            InetAddress.getLoopbackAddress()
        }
    }

    @JvmStatic
    fun checkPublicIpv6(): InetAddress? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create("https://v6.ident.me").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    val line = reader.readLine()?.trim() ?: return null
                    val addr = InetAddress.getByName(line)
                    if (addr is Inet6Address) addr else null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
