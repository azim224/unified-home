package org.amalia.unifiedhome.v2

import android.content.Context
import android.util.AtomicFile
import com.my.amali.plugin.HomeTextRequest
import com.my.amali.plugin.HomeTextRoute
import com.my.amali.plugin.HostServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Local, home-scoped choices. Never migrate legacy node IDs/trust or reuse schedule quorum.
 * Remote turns use the receiver's durable receipts. A failed/unknown submission is never replayed.
 */
class HomeV2Routing(context: Context, private val node: HomeV2Node, private val home: String,
    private val host: HostServices) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "home-v3-voice-$home.json"))
    private val lock = Any()
    private var revision = 0L
    private var choices: JSONObject

    init {
        v2Id(home)
        val bytes = try { file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val block = ByteArray(1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                v2Require(out.size() + count <= 4096, "invalid_voice_config")
                out.write(block, 0, count)
            }
            out.toByteArray()
        } } catch (_: java.io.FileNotFoundException) { null }
        choices = bytes?.let { V2Json.parse(it, 4096) }
            ?: JSONObject().put("brain", "").put("responder", "").put("listener", "")
        validate(choices)
    }

    private fun validate(value: JSONObject) {
        v2Keys(value, setOf("brain", "responder", "listener"))
        for (key in listOf("brain", "responder", "listener")) {
            val id = value.getString(key)
            v2Require(id.isEmpty() || V2_HEX.matches(id), "invalid_voice_target")
        }
    }

    fun preferences(): JSONObject = synchronized(lock) { JSONObject(choices.toString()) }

    /** Explicit local human action; authenticate routes before persisting any remote choice. */
    suspend fun configure(brain: String, responder: String, listener: String) = withContext(Dispatchers.IO) {
        val next = JSONObject().put("brain", brain).put("responder", responder).put("listener", listener)
        validate(next)
        val before = synchronized(lock) { revision }
        checkHome()
        for (target in setOf(brain, responder, listener).filter { it.isNotEmpty() && it != node.status.value.nodeId }) {
            val route = node.peers.value.firstOrNull { it.nodeId == target && it.homeId == home }
                ?: throw HomeV2Exception("voice_target_unavailable")
            val reply = withTimeoutOrNull(4000L) {
                node.request(route.address, route.port, target, "node.describe")
            } ?: throw HomeV2Exception("voice_target_unavailable")
            v2Require(reply.ok, reply.error ?: "voice_target_unavailable")
            val features = reply.data.getJSONArray("features")
            val required = mutableSetOf<String>()
            if (target == brain) required.addAll(listOf("assistant.respond", "assistant.task.get"))
            if (target == responder) required.addAll(listOf("assistant.speak", "assistant.task.get"))
            v2Require((0 until features.length()).map { features.getString(it) }.containsAll(required), "voice_target_unsupported")
        }
        synchronized(lock) {
            checkHome()
            v2Require(revision == before, "voice_config_changed")
            val stream = file.startWrite()
            try { stream.write(next.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (failure: Exception) { file.failWrite(stream); throw failure }
            choices = next; revision++
        }
    }

    /** A manual listener is the only device allowed to handle its own wake detection.
     * No remote recording, proximity election or hidden fallback is implied.
     */
    fun permitsLocalWake(): Boolean = synchronized(lock) {
        val target = choices.getString("listener")
        target.isEmpty() || target == node.status.value.nodeId
    }

    private fun checkHome() {
        v2Require(node.status.value.homeId == home && node.status.value.running, "home_not_running")
    }

    suspend fun route(request: HomeTextRequest): HomeTextRoute? = withContext(Dispatchers.IO) {
        val (selection, generation) = synchronized(lock) { JSONObject(choices.toString()) to revision }
        val self = node.status.value.nodeId
        val brain = selection.getString("brain").ifEmpty { self }
        val responder = selection.getString("responder").ifEmpty { self }
        if (brain == self && responder == self) return@withContext null
        var answer = ""
        fun current() {
            checkHome()
            v2Require(synchronized(lock) { revision == generation }, "voice_config_changed")
        }
        suspend fun rpc(target: String, operation: String, args: JSONObject): JSONObject {
            current()
            val peer = node.peers.value.firstOrNull { it.nodeId == target && it.homeId == home }
                ?: throw HomeV2Exception("voice_target_unavailable")
            // The encrypted reply authenticates the peer; discovery alone never authorizes success.
            val reply = withTimeoutOrNull(4000L) {
                node.request(peer.address, peer.port, target, operation, args)
            } ?: throw HomeV2Exception("voice_outcome_unknown")
            current()
            v2Require(reply.ok, reply.error ?: "voice_outcome_unknown")
            return reply.data
        }
        suspend fun submit(target: String, operation: String, text: String, budget: Long): JSONObject {
            val id = MessageDigest.getInstance("SHA-256").digest(
                "$home:$self:${request.requestId}:$operation".toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
            return withTimeoutOrNull(budget) {
                var receipt = rpc(target, operation, JSONObject().put("request_id", id).put("text", text))
                while (receipt.optString("state") in setOf("queued", "running")) {
                    delay(250L)
                    receipt = rpc(target, "assistant.task.get", JSONObject().put("request_id", id))
                }
                v2Require(receipt.optString("state") == "succeeded",
                    (receipt.opt("error_code") as? String)?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) }
                        ?: "voice_outcome_unknown")
                receipt.optJSONObject("result") ?: throw HomeV2Exception("invalid_voice_result")
            } ?: throw HomeV2Exception("voice_outcome_unknown")
        }
        try {
            current()
            // Preflight both targets before any provider work, including an offline speaker.
            for (target in setOf(brain, responder).filter { it != self }) {
                val description = rpc(target, "node.describe", JSONObject())
                val features = description.getJSONArray("features")
                val required = if (target == brain && target == responder)
                    setOf("assistant.respond", "assistant.speak", "assistant.task.get")
                else setOf(if (target == brain) "assistant.respond" else "assistant.speak", "assistant.task.get")
                v2Require((0 until features.length()).map { features.getString(it) }.containsAll(required), "voice_target_unsupported")
            }
            v2Require(request.text.length <= 8000, "voice_text_too_large")
            answer = if (brain == self) host.homeRespondForeground(request.requestId, request.text, "{}")
                else submit(brain, "assistant.respond", request.text, 115000L).getString("text")
            current()
            v2Require(answer.isNotBlank() && answer.length <= 16000, "invalid_voice_result")
            if (responder != self) {
                v2Require(answer.length <= 8000, "voice_text_too_large")
                val result = submit(responder, "assistant.speak", answer, 65000L)
                // Android reports spoken=true; PC reports the completed synchronous playback state.
                v2Require(result.optBoolean("spoken") || result.optString("state") == "playback_returned",
                    "voice_outcome_unknown")
            }
            HomeTextRoute(reply = answer, suppressLocalSpeech = responder != self)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            HomeTextRoute(reply = answer, error = (failure as? HomeV2Exception)?.code ?: "home_voice_failed",
                suppressLocalSpeech = true)
        }
    }
}
