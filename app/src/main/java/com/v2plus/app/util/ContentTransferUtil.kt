package com.v2plus.app.util

import android.util.Base64
import com.v2plus.app.handler.AngConfigManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ContentTransferUtil {
    private const val PREFIX = "v2plus://ct1/"

    data class StealthEnvelope(
        val v: Int,
        val m: String,
        val j: String,
        val p: String,
        val n: String,
        val c: String,
        val k: String,
        val count: Int
    )

    data class DecodeResult(
        val configs: List<String>,
        val count: Int
    )

    data class BlueBridgeMarker(
        val v: Int,
        val m: String,
        val uuid: String,
        val addr: String,
        val p: String,
        val name: String? = null
    )

    data class BeaconMarker(
        val v: Int,
        val m: String,
        val host: String,
        val port: Int,
        val token: String,
        val kind: String,
        val ssid: String? = null,
        val pass: String? = null
    )

    data class HybridPayload(
        val v: Int,
        val m: String,
        val p: String,
        val n: String,
        val c: String,
        val count: Int
    )

    fun buildStealthQrPayload(selectedGuids: List<String>): Pair<String, Int>? {
        if (selectedGuids.isEmpty()) return null
        val exported = AngConfigManager.exportNonCustomConfigs(selectedGuids)
        if (exported.isEmpty()) return null
        val plainData = exported.joinToString("\n").toByteArray(Charsets.UTF_8)
        val compressed = gzip(plainData)
        val junk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = createEphemeralPublicKey()
        val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipherText = aesGcmEncrypt(compressed, aesKey, nonce)
        val mask = sha256(pub + junk)
        val wrappedKey = xorWithMask(aesKey, mask)
        val envelope = StealthEnvelope(
            v = 1,
            m = "stealth_qr",
            j = b64(junk),
            p = b64(pub),
            n = b64(nonce),
            c = b64(cipherText),
            k = b64(wrappedKey),
            count = exported.size
        )
        val json = JsonUtil.toJson(envelope)
        val qrPayload = PREFIX + b64Url(json.toByteArray(Charsets.UTF_8))
        return qrPayload to exported.size
    }

    fun decodeStealthQrPayload(payload: String): DecodeResult? {
        if (!payload.startsWith(PREFIX)) return null
        val rawJson = runCatching {
            String(b64UrlDecode(payload.removePrefix(PREFIX)), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val envelope = JsonUtil.fromJson(rawJson, StealthEnvelope::class.java) ?: return null
        if (envelope.m != "stealth_qr") return null
        val junk = b64Decode(envelope.j)
        val pub = b64Decode(envelope.p)
        val nonce = b64Decode(envelope.n)
        val cipherText = b64Decode(envelope.c)
        val wrappedKey = b64Decode(envelope.k)
        val mask = sha256(pub + junk)
        val aesKey = xorWithMask(wrappedKey, mask)
        val compressed = aesGcmDecrypt(cipherText, aesKey, nonce)
        val plain = ungzip(compressed)
        val lines = String(plain, Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return null
        return DecodeResult(lines, lines.size)
    }

    fun buildBlueBridgeMarker(): String {
        val marker = BlueBridgeMarker(
            v = 1,
            m = "blue_bridge",
            uuid = "",
            addr = "",
            p = ""
        )
        val body = JsonUtil.toJson(marker)
        return PREFIX + b64Url(body.toByteArray(Charsets.UTF_8))
    }

    fun buildBlueBridgeMarker(
        uuid: String,
        address: String,
        name: String? = null
    ): String {
        val marker = BlueBridgeMarker(
            v = 1,
            m = "blue_bridge",
            uuid = uuid,
            addr = address,
            p = "",
            name = name
        )
        val body = JsonUtil.toJson(marker)
        return PREFIX + b64Url(body.toByteArray(Charsets.UTF_8))
    }

    fun buildBeaconMarker(): String {
        val marker = BeaconMarker(
            v = 1,
            m = "beacon",
            host = "",
            port = 0,
            token = "",
            kind = "configs"
        )
        val body = JsonUtil.toJson(marker)
        return PREFIX + b64Url(body.toByteArray(Charsets.UTF_8))
    }

    fun buildBeaconMarker(host: String, port: Int, token: String, kind: String, ssid: String? = null, pass: String? = null): String {
        val marker = BeaconMarker(
            v = 1,
            m = "beacon",
            host = host,
            port = port,
            token = token,
            kind = kind,
            ssid = ssid,
            pass = pass
        )
        val body = JsonUtil.toJson(marker)
        return PREFIX + b64Url(body.toByteArray(Charsets.UTF_8))
    }

    fun decodeBlueBridgeMarker(payload: String): BlueBridgeMarker? {
        val json = decodePayloadJson(payload) ?: return null
        return JsonUtil.fromJson(json, BlueBridgeMarker::class.java)?.takeIf { it.m == "blue_bridge" }
    }

    fun decodeBeaconMarker(payload: String): BeaconMarker? {
        val json = decodePayloadJson(payload) ?: return null
        return JsonUtil.fromJson(json, BeaconMarker::class.java)?.takeIf { it.m == "beacon" }
    }

    fun decodeMethod(payload: String): String? {
        val json = decodePayloadJson(payload) ?: return null
        val jo = JsonUtil.parseString(json) ?: return null
        return jo.get("m")?.asString
    }

    fun createEphemeralKeyPair() = runCatching { KeyPairGenerator.getInstance("X25519") }.getOrElse {
        KeyPairGenerator.getInstance("XDH")
    }.apply { initialize(255) }.generateKeyPair()

    fun encryptWithSharedKey(configs: List<String>, sharedKey: ByteArray): HybridPayload {
        val plainData = configs.joinToString("\n").toByteArray(Charsets.UTF_8)
        val compressed = gzip(plainData)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aesKey = sha256(sharedKey).copyOf(32)
        val cipherText = aesGcmEncrypt(compressed, aesKey, nonce)
        return HybridPayload(
            v = 1,
            m = "hybrid_transfer",
            p = "",
            n = b64(nonce),
            c = b64(cipherText),
            count = configs.size
        )
    }

    fun decryptWithSharedKey(payload: HybridPayload, sharedKey: ByteArray): DecodeResult? {
        val nonce = b64Decode(payload.n)
        val cipherText = b64Decode(payload.c)
        val aesKey = sha256(sharedKey).copyOf(32)
        val compressed = aesGcmDecrypt(cipherText, aesKey, nonce)
        val plain = ungzip(compressed)
        val lines = String(plain, Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return null
        return DecodeResult(lines, lines.size)
    }

    fun deriveSharedSecret(privateKey: PrivateKey, peerPublicEncoded: ByteArray): ByteArray {
        val factory = runCatching { KeyFactory.getInstance("X25519") }.getOrElse {
            KeyFactory.getInstance("XDH")
        }
        val peerPublic: PublicKey = factory.generatePublic(X509EncodedKeySpec(peerPublicEncoded))
        val agreement = runCatching { KeyAgreement.getInstance("X25519") }.getOrElse {
            KeyAgreement.getInstance("XDH")
        }
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        return agreement.generateSecret()
    }

    fun payloadToJson(payload: HybridPayload): String = JsonUtil.toJson(payload)
    fun payloadFromJson(json: String): HybridPayload? = JsonUtil.fromJson(json, HybridPayload::class.java)

    private fun createEphemeralPublicKey(): ByteArray = createEphemeralKeyPair().public.encoded

    private fun aesGcmEncrypt(input: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(input)
    }

    private fun aesGcmDecrypt(input: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(input)
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun xorWithMask(input: ByteArray, mask: ByteArray): ByteArray {
        return ByteArray(input.size) { i -> (input[i].toInt() xor mask[i % mask.size].toInt()).toByte() }
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun ungzip(input: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(input)
        return GZIPInputStream(bis).use { it.readBytes() }
    }

    private fun b64(input: ByteArray): String {
        return Base64.encodeToString(input, Base64.NO_WRAP)
    }

    private fun b64Decode(input: String): ByteArray {
        return Base64.decode(input, Base64.DEFAULT)
    }

    private fun b64Url(input: ByteArray): String {
        return Base64.encodeToString(input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun b64UrlDecode(input: String): ByteArray {
        return Base64.decode(input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun decodePayloadJson(payload: String): String? {
        if (!payload.startsWith(PREFIX)) return null
        return runCatching {
            String(b64UrlDecode(payload.removePrefix(PREFIX)), Charsets.UTF_8)
        }.getOrNull()
    }
}
