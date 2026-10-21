package xyz.lychee.dynamicdns.shared

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HmacUtil {

    /**
     * Computes a HMAC-SHA256 signature encoded as a 32-character hexadecimal string.
     */
    fun sign(token: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(token.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            for (b in hmacBytes) {
                append(String.format("%02x", b))
            }
        }.take(32)
    }

    /**
     * Verifies that the provided [expectedSignature] matches the HMAC-SHA256 of [data] using [token].
     */
    fun verify(token: String, data: String, expectedSignature: String): Boolean {
        if (token.isBlank() || expectedSignature.isBlank()) return false
        val computed = sign(token, data)
        return computed.equals(expectedSignature, ignoreCase = true)
    }
}
