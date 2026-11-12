package org.amalia.unifiedhome.v2

import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import org.json.JSONTokener
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Safe local API failure: code only, never invitation/provider/peer input. */
class HomeV2Exception(val code: String) : Exception(code)
internal typealias V2Failure = HomeV2Exception
internal fun v2Require(value: Boolean, code: String = "invalid_request") { if (!value) throw V2Failure(code) }
internal val V2_HEX = Regex("[0-9a-f]{32}")
internal fun v2Id(value: String): String { v2Require(V2_HEX.matches(value)); return value }
internal fun v2Keys(obj: JSONObject, allowed: Set<String>, required: Set<String> = allowed) {
    val keys = obj.keys().asSequence().toSet()
    v2Require(keys.all { it in allowed } && keys.containsAll(required))
}
internal fun v2String(obj: JSONObject, key: String): String =
    (obj.opt(key) as? String) ?: throw V2Failure("invalid_request")
internal fun v2Int(obj: JSONObject, key: String): Int {
    val n = obj.opt(key)
    v2Require(n is Int || n is Long)
    val l = (n as Number).toLong()
    v2Require(l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return l.toInt()
}

/** Strict JSON grammar, duplicate keys (including escaped aliases), depth and UTF-8 checks. */
internal object V2Json {
    fun parse(bytes: ByteArray, max: Int = 524288): JSONObject {
        v2Require(bytes.size <= max, "message_too_large")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        Parser(text).validate()
        return JSONObject(text)
    }
    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \r\n\t") i++ }
        fun take(c: Char) { ws(); v2Require(i < s.length && s[i++] == c) }
        fun string(): String {
            ws(); val start = i; take('"')
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') {
                    val decoded = JSONTokener(s.substring(start, i)).nextValue() as String
                    var index = 0
                    while (index < decoded.length) {
                        val unit = decoded[index++]
                        if (unit.isHighSurrogate()) {
                            v2Require(index < decoded.length && decoded[index].isLowSurrogate())
                            index++
                        } else v2Require(!unit.isLowSurrogate())
                    }
                    return decoded
                }
                v2Require(c >= ' ')
                if (c == '\\') {
                    v2Require(i < s.length)
                    val e = s[i++]
                    v2Require(e in "\"\\/bfnrtu")
                    if (e == 'u') { repeat(4) { v2Require(i < s.length && s[i++] in "0123456789abcdefABCDEF") } }
                }
            }
            throw V2Failure("invalid_request")
        }
        fun value(depth: Int) {
            v2Require(depth <= 32); ws(); v2Require(i < s.length)
            when (s[i]) {
                '{' -> {
                    i++; ws(); val seen = HashSet<String>()
                    if (i < s.length && s[i] == '}') { i++; return }
                    while (true) {
                        v2Require(seen.add(string())); take(':'); value(depth + 1); ws()
                        v2Require(i < s.length)
                        if (s[i] == '}') { i++; break }; take(',')
                    }
                }
                '[' -> {
                    i++; ws(); if (i < s.length && s[i] == ']') { i++; return }
                    while (true) { value(depth + 1); ws(); v2Require(i < s.length)
                        if (s[i] == ']') { i++; break }; take(',') }
                }
                '"' -> string()
                't', 'f', 'n' -> {
                    val literal = when(s[i]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    v2Require(s.startsWith(literal, i)); i += literal.length
                }
                else -> {
                    val start = i
                    while (i < s.length && s[i] !in " \r\n\t,]}") i++
                    val n = s.substring(start, i)
                    v2Require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(n))
                    v2Require(n.toDoubleOrNull()?.isFinite() == true)
                }
            }
        }
        fun validate() { ws(); v2Require(i < s.length && s[i] == '{'); value(0); ws(); v2Require(i == s.length) }
    }
}

internal class V2Membership(val home: String, val secret: ByteArray) {
    val key: ByteArray = run {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(home.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val prk = extract.doFinal(secret)
        extract.init(SecretKeySpec(prk, "HmacSHA256"))
        extract.doFinal("amalia-home/v3/rpc".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
    }
}
internal data class V2Envelope(val from: String, val to: String, val id: String, val nonce: String, val body: JSONObject)
internal object V2Wire {
    const val FRAME_MAX = 1048576
    const val PLAIN_MAX = 524288
    private val random = SecureRandom()
    fun randomHex(bytes: Int): String = randomBytes(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String): ByteArray {
        val bytes = try { Base64.decode(s, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { throw V2Failure("invalid_frame") }
        v2Require(b64(bytes) == s, "invalid_frame"); return bytes
    }
    private fun aad(m: V2Membership, from: String, to: String, id: String, nonce: String) =
        listOf("amalia-home/v3/rpc", m.home, from, to, id, nonce).joinToString("\n").toByteArray(Charsets.UTF_8)
    fun encrypt(m: V2Membership, from: String, to: String, id: String, body: JSONObject): ByteArray {
        v2Id(from); v2Id(to); v2Id(id)
        val raw = body.toString()
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(raw))
        val plain = ByteArray(encoded.remaining()).also { encoded.get(it) }
        V2Json.parse(plain)
        v2Require(plain.size <= PLAIN_MAX, "message_too_large")
        val nonce = randomBytes(12); val ns = b64(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m.key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(m, from, to, id, ns))
        return JSONObject().put("v", 3).put("home", m.home).put("from", from).put("to", to)
            .put("id", id).put("nonce", ns).put("payload", b64(cipher.doFinal(plain))).toString().toByteArray(Charsets.UTF_8)
    }
    fun decrypt(m: V2Membership, local: String, frame: ByteArray): V2Envelope {
        val obj = V2Json.parse(frame, FRAME_MAX)
        v2Keys(obj, setOf("v", "home", "from", "to", "id", "nonce", "payload"))
        v2Require(v2Int(obj, "v") == 3 && v2String(obj, "home") == m.home && v2String(obj, "to") == local, "invalid_frame")
        val from = v2Id(v2String(obj, "from")); val to = v2Id(v2String(obj, "to")); val id = v2Id(v2String(obj, "id"))
        val ns = v2String(obj, "nonce"); val nonce = decode(ns); v2Require(nonce.size == 12, "invalid_frame")
        val payload = decode(v2String(obj, "payload")); v2Require(payload.size in 16..(PLAIN_MAX + 16), "message_too_large")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(m.key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(m, from, to, id, ns))
        return V2Envelope(from, to, id, ns, V2Json.parse(cipher.doFinal(payload)))
    }
    fun read(socket: Socket): ByteArray {
        val input = DataInputStream(socket.getInputStream()); val size = input.readInt()
        v2Require(size in 1..FRAME_MAX, "invalid_frame")
        return ByteArray(size).also(input::readFully)
    }
    fun write(socket: Socket, bytes: ByteArray) {
        v2Require(bytes.size in 1..FRAME_MAX, "message_too_large")
        DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
    }
}

/** Reject on saturation rather than evicting a still-live replay entry; survives service restarts. */
internal class V2Replay {
    private val entries = LinkedHashMap<String, Long>()
    @Synchronized fun admit(key: String) {
        val now = SystemClock.elapsedRealtime()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) { if (now - iterator.next().value >= 600000L) iterator.remove() }
        v2Require(key !in entries, "replay_rejected")
        v2Require(entries.size < 8192, "replay_capacity")
        entries[key] = now
    }
}
