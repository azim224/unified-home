package org.amalia.unifiedhome

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Private RPC codec, independent of any PC/service. All household members share this trust domain.
 * Metadata is public; operation/arguments/results are encrypted. No fragmentation or compression.
 * 8192 bytes includes the complete UTF-8 plaintext JSON, not just its data field.
 */
internal object SecureHome {
    const val VERSION = 1
    const val MAX_PLAINTEXT = 8192
    const val MAX_TIMEOUT_MS = 120_000L
    private val random = SecureRandom()
    private val fields = setOf("t", "v", "home", "from", "from_session", "to", "to_session",
        "request_id", "at", "timeout_ms", "nonce", "payload")

    data class Opened(val id: String, val timeoutMs: Long, val payload: JSONObject)

    fun failure(error: String): JSONObject = JSONObject().put("ok", false)
        .put("data", JSONObject()).put("error", error)

    /** Snapshot and validate JSON before sending, also rejecting oversized/complex callback values. */
    fun snapshot(value: JSONObject): JSONObject {
        val raw = value.toString()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT) { "Secure payload exceeds 8192 bytes" }
        return HomeLink.parse(raw)
    }

    fun seal(member: HomeLink.Credentials, from: String, session: String, to: String,
        targetSession: String, id: String, type: String, timeoutMs: Long, payload: JSONObject): JSONObject {
        require(type == "secure_request" || type == "secure_result")
        require(HomeLink.validText(from, 128) && HomeLink.validText(to, 128) &&
            HomeLink.validUuid(session) && HomeLink.validUuid(targetSession) && HomeLink.validUuid(id))
        require(timeoutMs in 1..MAX_TIMEOUT_MS)
        val plain = snapshot(payload).toString().toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val header = JSONObject().put("t", type).put("v", VERSION).put("home", member.home)
            .put("from", from).put("from_session", session).put("to", to)
            .put("to_session", targetSession).put("request_id", id).put("at", System.currentTimeMillis())
            .put("timeout_ms", timeoutMs).put("nonce", encode(nonce))
        try {
            header.put("payload", encode(crypt(Cipher.ENCRYPT_MODE, member, nonce, aad(header), plain)))
            return header
        } finally { plain.fill(0) }
    }

    /** Called only after the outer HMAC, replay window and announced sender session were checked. */
    fun open(member: HomeLink.Credentials, message: HomeLink.Message, to: String, session: String): Opened? = try {
        val b = message.body
        require(b.keys().asSequence().toSet() == fields)
        require(b.opt("t") in setOf("secure_request", "secure_result") && long(b, "v") == VERSION.toLong())
        require(b.opt("home") == member.home && b.opt("from") == message.id &&
            b.opt("from_session") == message.session && b.opt("to") == to && b.opt("to_session") == session)
        val id = b.opt("request_id") as? String ?: error("Invalid request id")
        require(HomeLink.validUuid(id))
        val at = long(b, "at")
        val now = System.currentTimeMillis()
        require(at >= now - 60_000 && at <= now + 60_000)
        val timeout = long(b, "timeout_ms")
        require(timeout in 1..MAX_TIMEOUT_MS)
        val nonce = decode(b.opt("nonce") as? String ?: error("Invalid nonce"), 12)
        require(nonce.size == 12)
        val ciphertext = decode(b.opt("payload") as? String ?: error("Invalid ciphertext"), MAX_PLAINTEXT + 16)
        require(ciphertext.size in 18..MAX_PLAINTEXT + 16)
        val plain = crypt(Cipher.DECRYPT_MODE, member, nonce, aad(b), ciphertext)
        try { Opened(id, timeout, HomeLink.parse(HomeLink.utf8(plain))) } finally { plain.fill(0) }
    } catch (_: Exception) { null }

    private fun long(b: JSONObject, key: String): Long {
        val value = b.opt(key)
        require(value is Long || value is Int)
        return (value as Number).toLong()
    }

    // Fixed, ordered, newline-delimited header. Identity validation forbids newlines; all other
    // values are strict integers, protocol constants, hex UUIDs or canonical Base64.
    private fun aad(b: JSONObject): ByteArray = listOf("amalia-home-secure-rpc", "v", "t", "home", "from",
        "from_session", "to", "to_session", "request_id", "at", "timeout_ms", "nonce")
        .joinToString("\n") { if (it == "amalia-home-secure-rpc") it else b.get(it).toString() }
        .toByteArray(Charsets.UTF_8)

    private fun crypt(mode: Int, member: HomeLink.Credentials, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        // HKDF-SHA256 extract(salt=home ID, IKM=household key), expand(info=label, L=32).
        // Separate from HomeLink's HMAC key; never cached across membership/session changes.
        val prk = hmac(member.home.toByteArray(Charsets.UTF_8), member.key)
        val key = try { hmac(prk, "amalia-home/secure-rpc/aes-256-gcm/v1\u0001".toByteArray(Charsets.UTF_8)) }
            finally { prk.fill(0) }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(input)
            }
        } finally { key.fill(0) }
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
    }
    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String, maxBytes: Int): ByteArray {
        require(text.length <= ((maxBytes + 2) / 3) * 4)
        return Base64.decode(text, Base64.NO_WRAP).also { require(it.size <= maxBytes && encode(it) == text) }
    }
}
