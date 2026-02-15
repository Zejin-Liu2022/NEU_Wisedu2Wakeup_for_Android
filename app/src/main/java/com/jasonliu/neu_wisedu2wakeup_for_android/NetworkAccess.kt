package com.jasonliu.neu_wisedu2wakeup_for_android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class NetworkMode {
    DIRECT,
    WEB_VPN
}

data class NetworkConfig(val mode: NetworkMode) {
    val modeLabel: String
        get() = if (mode == NetworkMode.DIRECT) "校园网直连" else "WebVPN"

    val loginUrl: String
        get() = if (mode == NetworkMode.DIRECT) {
            "https://jwxt.neu.edu.cn"
        } else {
            WebVpnMapper.map("https://jwxt.neu.edu.cn/jwapp/sys/homeapp/index.do")
        }

    val requestOrigin: String
        get() = if (mode == NetworkMode.DIRECT) "https://jwxt.neu.edu.cn" else "https://webvpn.neu.edu.cn"

    val requestReferer: String
        get() = resolve("https://jwxt.neu.edu.cn/jwapp/sys/homeapp/home/index.html?av=&contextPath=/jwapp")

    fun resolve(rawUrl: String): String {
        return if (mode == NetworkMode.DIRECT) rawUrl else WebVpnMapper.map(rawUrl)
    }
}

object NetworkDetector {
    suspend fun detect(): NetworkConfig = withContext(Dispatchers.IO) {
        if (canReach("http://jwxt.neu.edu.cn") || canReach("https://jwxt.neu.edu.cn")) {
            return@withContext NetworkConfig(NetworkMode.DIRECT)
        }
        if (canReach("https://webvpn.neu.edu.cn")) {
            return@withContext NetworkConfig(NetworkMode.WEB_VPN)
        }
        throw IllegalStateException("无法访问教务系统和 WebVPN，请检查网络。")
    }

    private fun canReach(url: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }
}

object WebVpnMapper {
    private const val WEBVPN_ROOT = "https://webvpn.neu.edu.cn"
    private const val PASS_PREFIX = "62304135386136393339346365373340"
    private const val PASS_QR_PATH =
        "https://webvpn.neu.edu.cn/https/62304135386136393339346365373340a0e0b72cc4cb43c8bc1d6f66c806db"
    private const val AES_KEY = "b0A58a69394ce73@"

    fun map(rawUrl: String): String {
        val uri = URI(rawUrl)
        val scheme = uri.scheme ?: return rawUrl
        val host = uri.host ?: return rawUrl
        val path = (uri.rawPath ?: "/").trimStart('/')
        val pathWithQuery = if (uri.rawQuery.isNullOrBlank()) path else "$path?${uri.rawQuery}"

        if (pathWithQuery.contains("qyQrLogin")) {
            val suffix = if (pathWithQuery.contains("?")) "&" else "?"
            return "$scheme://$host/$pathWithQuery${suffix}service=https://webvpn.neu.edu.cn/login?cas_login=true"
        }

        if (pathWithQuery.contains("checkQRCodeScan")) {
            val parts = pathWithQuery.split("?", limit = 2)
            val pre = parts.firstOrNull().orEmpty()
            val post = parts.getOrNull(1).orEmpty()
            val updated = "$pre?vpn-12-o2-pass.neu.edu.cn&$post"
            return "$PASS_QR_PATH/$updated"
        }

        val encryptedHostHex = encryptHost(host).toHexString()
        return "$WEBVPN_ROOT/$scheme/$PASS_PREFIX$encryptedHostHex/$pathWithQuery"
    }

    private fun encryptHost(host: String): ByteArray {
        val paddedLength = (host.length / 16) * 16 + 16
        val padded = host.padEnd(paddedLength, '\u0000')
        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        val keySpec = SecretKeySpec(AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(AES_KEY.toByteArray(StandardCharsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(padded.toByteArray(StandardCharsets.UTF_8))
        return encrypted.copyOfRange(0, host.length)
    }

    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        val hex = "0123456789abcdef"
        forEachIndexed { i, b ->
            val value = b.toInt() and 0xFF
            chars[i * 2] = hex[value ushr 4]
            chars[i * 2 + 1] = hex[value and 0x0F]
        }
        return String(chars)
    }
}
