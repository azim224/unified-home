package org.amalia.unifiedhome

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Wire codec only: no credentials are exposed through mesh settings or status. */
internal object HomeLink {
    const val MAX_PACKET = 16_384
    private val hex32 = Regex("[0-9a-f]{32}")
    private val hex64 = Regex("[0-9a-f]{64}")
    private val random = SecureRandom()
    val operations = setOf("catalog", "plan_scene", "apply_plan")

    data class Credentials(val home: String, val key: ByteArray)
    data class Message(val id: String, val session: String, val nonce: String, val body: JSONObject)

    fun uuid(): String = UUID.randomUUID().toString().replace("-", "")
    fun validUuid(value: String): Boolean = hex32.matches(value)
    fun validText(value: String, max: Int): Boolean =
        value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }

    fun create(): Credentials = Credentials(uuid(), ByteArray(32).also { random.nextBytes(it) })
    fun invite(credentials: Credentials): String = "amalia-home:v2:${credentials.home}:${hex(credentials.key)}"
    fun parseInvite(invite: String): Credentials {
        val parts = invite.split(':')
        require(parts.size == 4 && parts[0] == "amalia-home" && parts[1] == "v2" &&
            hex32.matches(parts[2]) && hex64.matches(parts[3])) { "Invalid home invite" }
        return Credentials(parts[2], unhex(parts[3]))
    }

    fun encode(credentials: Credentials, id: String, session: String, body: JSONObject): ByteArray? {
        val raw = body.toString()
        parse(raw) // Reject nonfinite numbers and excessively deep/complex callback output too.
        val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val nonce = uuid()
        val at = System.currentTimeMillis()
        val mac = sign(credentials, id, session, nonce, at, encoded)
        return JSONObject().put("v", 2).put("home", credentials.home).put("id", id)
            .put("session", session).put("nonce", nonce).put("at", at).put("body", encoded)
            .put("mac", hex(mac)).toString().toByteArray(Charsets.UTF_8).takeIf { it.size <= MAX_PACKET }
    }

    fun decode(credentials: Credentials, packet: ByteArray, length: Int): Message? = try {
        val json = parse(utf8(packet.copyOf(length)))
        val id = json.opt("id") as? String
        val session = json.opt("session") as? String
        val nonce = json.opt("nonce") as? String
        val body = json.opt("body") as? String
        val mac = json.opt("mac") as? String
        val atNumber = json.opt("at")
        val at = (atNumber as? Number)?.toLong()
        val now = System.currentTimeMillis()
        if (integer(json, "v") != 2 || json.opt("home") != credentials.home ||
            id == null || !validText(id, 128) || session == null || !hex32.matches(session) ||
            nonce == null || !hex32.matches(nonce) || body == null || mac == null || !hex64.matches(mac) ||
            (atNumber !is Long && atNumber !is Int) || at == null || at < now - 60_000 || at > now + 60_000 ||
            !MessageDigest.isEqual(sign(credentials, id, session, nonce, at, body), unhex(mac))) null
        else {
            val decoded = Base64.decode(body, Base64.NO_WRAP)
            // Android's Base64 decoder is permissive; accept only canonical standard Base64.
            if (Base64.encodeToString(decoded, Base64.NO_WRAP) != body) null
            else Message(id, session, nonce, parse(utf8(decoded)))
        }
    } catch (_: Exception) { null }

    fun integer(json: JSONObject, key: String): Int? {
        val n = json.opt(key) as? Number ?: return null
        val d = n.toDouble()
        return n.toInt().takeIf { d.isFinite() && it.toDouble() == d }
    }

    private fun sign(c: Credentials, id: String, session: String, nonce: String, at: Long, body: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(c.key, "HmacSHA256"))
            doFinal("amalia-home-v2\n${c.home}\n$id\n$session\n$nonce\n$at\n$body".toByteArray(Charsets.UTF_8))
        }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun parse(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PACKET)
        StrictJson(text).check()
        return JSONObject(text)
    }

    /** Android's JSONTokener accepts NaN, comments and unquoted strings. The wire does not. */
    private class StrictJson(private val text: String) {
        private var pos = 0
        private var tokens = 0
        fun check() {
            whitespace()
            require(pos < text.length && text[pos] == '{')
            value(0)
            whitespace()
            require(pos == text.length)
        }
        private fun whitespace() { while (pos < text.length && text[pos] in " \t\r\n") pos++ }
        private fun take(c: Char): Boolean {
            whitespace()
            return if (pos < text.length && text[pos] == c) { pos++; true } else false
        }
        private fun value(depth: Int) {
            require(depth <= 32 && ++tokens <= 4096)
            whitespace()
            require(pos < text.length)
            when (text[pos]) {
                '{' -> {
                    pos++
                    val keys = HashSet<String>()
                    if (!take('}')) {
                        do {
                            whitespace()
                            val keyStart = pos
                            string()
                            // Decode escapes before checking duplicate property names.
                            val key = org.json.JSONTokener(text.substring(keyStart, pos)).nextValue() as String
                            require(keys.add(key) && take(':'))
                            value(depth + 1)
                        } while (take(','))
                        require(take('}'))
                    }
                }
                '[' -> {
                    pos++
                    if (!take(']')) {
                        do { value(depth + 1) } while (take(','))
                        require(take(']'))
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }
        private fun literal(s: String) { require(text.startsWith(s, pos)); pos += s.length }
        private fun string() {
            require(pos < text.length && text[pos++] == '"')
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '"') return
                require(c >= ' ')
                if (c == '\\') {
                    require(pos < text.length)
                    val escaped = text[pos++]
                    if (escaped == 'u') {
                        require(pos + 4 <= text.length && text.substring(pos, pos + 4).all { it in "0123456789abcdefABCDEF" })
                        pos += 4
                    } else require(escaped in "\"\\/bfnrt")
                }
            }
            error("Invalid JSON")
        }
        private fun number() {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            require(pos < text.length)
            if (text[pos] == '0') pos++ else {
                require(text[pos] in '1'..'9')
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                val digits = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                require(pos > digits)
            }
            if (pos < text.length && text[pos] in "eE") {
                pos++
                if (pos < text.length && text[pos] in "+-") pos++
                val digits = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                require(pos > digits)
            }
            require(text.substring(start, pos).toDouble().isFinite())
        }
    }
}
