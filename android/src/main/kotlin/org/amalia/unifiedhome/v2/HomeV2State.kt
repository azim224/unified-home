package org.amalia.unifiedhome.v2

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Public state deliberately contains neither the invitation nor its secret. */
data class HomeV2Status(
    val nodeId: String,
    val homeId: String?,
    val running: Boolean = false,
    val tcpPort: Int = 47655,
    val discoveryAvailable: Boolean = false,
    val error: String? = null,
)

/** UDP is untrusted, including name/home/platform; authenticated means a TCP reply was verified. */
data class HomeV2Peer(
    val nodeId: String,
    val homeId: String,
    val name: String,
    val platform: String,
    val address: String,
    val port: Int,
    val authenticated: Boolean = false,
    val lastSeenElapsedMs: Long = 0,
)

/** Local return value, not an exception containing peer or provider input. No automatic retries. */
data class HomeV2Reply(val ok: Boolean, val data: JSONObject = JSONObject(), val error: String? = null)

internal class V2Store(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "unified_home_v3_private.json"))
    val nodeId: String
    var membership: V2Membership? = null
        private set
    init {
        val bytes = try { file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (true) { val n = input.read(chunk); if (n < 0) break
                v2Require(out.size() + n <= 4096, "state_corrupt"); out.write(chunk, 0, n) }
            out.toByteArray()
        } } catch (_: FileNotFoundException) {
            // Permission/read failures must not regenerate identity over existing AtomicFile data.
            v2Require(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists(), "state_unreadable")
            null
        }
        if (bytes == null) {
            nodeId = java.util.UUID.randomUUID().toString().replace("-", ""); persist(null)
        } else {
            // Corruption fails closed: never silently replace node identity or trust.
            val obj = V2Json.parse(bytes, 4096)
            v2Keys(obj, setOf("version", "node", "home", "secret"))
            v2Require(v2Int(obj, "version") == 3, "state_corrupt")
            nodeId = v2Id(v2String(obj, "node"))
            val home = v2String(obj, "home"); val secret = v2String(obj, "secret")
            if (home.isNotEmpty()) membership = parseInvitation("amalia-home:v3:$home:$secret")
            else v2Require(secret.isEmpty(), "state_corrupt")
        }
    }
    fun persist(next: V2Membership?) {
        val obj = JSONObject().put("version", 3).put("node", nodeId)
            .put("home", next?.home ?: "").put("secret", next?.secret?.let(::hex) ?: "")
        val stream = file.startWrite()
        try { stream.write(obj.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
        membership = next
    }
    companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun parseInvitation(text: String): V2Membership {
            v2Require(Regex("amalia-home:v3:[0-9a-f]{32}:[0-9a-f]{64}").matches(text), "invalid_invitation")
            val parts = text.split(':')
            return V2Membership(parts[2], ByteArray(32) { parts[3].substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }
    }
}
