package org.amalia.unifiedhome

import com.my.amali.plugin.HostServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import android.os.SystemClock

/** Human UI only. No tool, prompt, context or peer export/import endpoint. */
class HomeSettingsTransfer(private val home: HomeEngine, private val host: HostServices, private val scope: CoroutineScope) {
    data class Review(val id: String, val sender: String, val fields: List<String>)
    private data class Offer(val review: Review, val settings: String, val home: String,
        val localSession: String, val senderSession: String, val generation: Long, val expires: Long)
    private val lock = Any()
    private var cleaner: Job? = null
    @Volatile private var enabled = false
    private val offers = LinkedHashMap<String, Offer>()
    private val seen = LinkedHashMap<String, Long>()
    private val state = MutableStateFlow<List<Review>>(emptyList())
    val pending = state.asStateFlow()

    fun start() { enabled = true; if (cleaner?.isActive != true) cleaner = scope.launch {
        while (isActive) { delay(1_000); synchronized(lock) { prune() } }
    } }
    fun stop() { enabled = false; cleaner?.cancel(); cleaner = null; synchronized(lock) {
        offers.clear(); seen.clear(); state.value = emptyList()
    } }

    private fun valid(offer: Offer): Boolean = enabled && home.mesh.running &&
        home.mesh.membership().optString("home_id") == offer.home &&
        home.sharing.state.value.receiveSettings && home.sharing.generation == offer.generation &&
        home.mesh.nodes.value.any { it.self && it.session == offer.localSession } &&
        home.mesh.nodes.value.any { it.id == offer.review.sender && it.session == offer.senderSession && it.online() } &&
        SystemClock.elapsedRealtime() < offer.expires

    private fun prune() {
        offers.entries.removeAll { !valid(it.value) }
        seen.entries.removeAll { it.value <= SystemClock.elapsedRealtime() }
        state.value = offers.values.map { it.review }
    }

    /** Called only after encrypted peer/session authentication by HomeRouting. Never writes settings. */
    suspend fun offer(args: JSONObject, sender: String, session: String): JSONObject = synchronized(lock) {
        prune()
        require(args.toString().toByteArray(Charsets.UTF_8).size <= 6_000)
        require(args.length() == 2 && args.opt("transfer_id") is String && args.opt("settings") is JSONObject)
        val id = args.getString("transfer_id")
        require(id.matches(Regex("[0-9a-f]{32}")))
        val settings = validate(args.getJSONObject("settings"))
        check(enabled && home.sharing.state.value.receiveSettings && home.mesh.running)
        val membership = home.mesh.membership()
        check(membership.optBoolean("joined"))
        val local = home.mesh.nodes.value.first { it.self }.session
        check(home.mesh.nodes.value.any { it.id == sender && it.session == session && it.online() })
        offers[id]?.let {
            check(it.review.sender == sender && it.senderSession == session && it.settings == settings.toString())
            return@synchronized response(id)
        }
        check(id !in seen && offers.size < 8 && seen.size < 128)
        val expires = SystemClock.elapsedRealtime() + 180_000
        offers[id] = Offer(Review(id, sender, settings.keys().asSequence().toList().sorted()),
            settings.toString(), membership.getString("home_id"), local, session, home.sharing.generation, expires)
        seen[id] = expires
        prune()
        response(id)
    }

    /** Explicit local review approval; consume before IO so cancellation cannot replay an import. */
    suspend fun approve(id: String) {
        val approved = synchronized(lock) {
            prune()
            val offer = offers.remove(id) ?: error("Review expired")
            state.value = offers.values.map { it.review }
            check(valid(offer))
            validate(JSONObject(offer.settings)).toString()
        }
        host.homeImportSettings(approved)
    }

    suspend fun reject(id: String) = synchronized(lock) { offers.remove(id); prune() }
    suspend fun clear() = synchronized(lock) { offers.clear(); state.value = emptyList() }

    /** Only a local click supplies selected categories and a concrete currently-online peer. */
    suspend fun send(peerId: String, keys: Boolean, settings: Boolean): JSONObject {
        require(keys || settings)
        check(enabled)
        val peer = home.mesh.nodes.value.first { it.id == peerId && !it.self && it.online() && it.secureVersion == 1 }
        val local = home.mesh.nodes.value.first { it.self }.session
        val household = home.mesh.membership().getString("home_id")
        val selected = JSONObject().put("keys", keys).put("settings", settings)
        val exported = validate(HomeLink.parse(host.homeExportSettings(selected.toString())))
        exported.keys().forEach { field ->
            check(if (field.endsWith("api_key")) keys else settings)
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val args = JSONObject().put("transfer_id", id).put("settings", exported)
        require(args.toString().toByteArray(Charsets.UTF_8).size <= 6_000)
        check(enabled && home.mesh.running && home.mesh.membership().optString("home_id") == household &&
            home.mesh.nodes.value.any { it.self && it.session == local } &&
            home.mesh.nodes.value.any { it.id == peer.id && it.session == peer.session && it.online() })
        val response = home.mesh.secureRequest(peer.id, "settings_offer", args)
        if (response.optBoolean("ok")) {
            val data = response.optJSONObject("data") ?: error("Invalid settings offer acknowledgement")
            check(data.opt("transfer_id") == id && data.opt("status") == "pending_review")
        }
        return response
    }

    private fun response(id: String) = JSONObject().put("ok", true).put("data",
        JSONObject().put("transfer_id", id).put("status", "pending_review")).put("error", "")

    companion object {
        /** Android mapped subset. URL/volume are deliberately unsupported, not silently discarded. */
        private val strings = setOf("llm_api_key", "stt_api_key", "tts_api_key", "llm_model", "stt_model", "tts_model", "tts_reference_id")
        fun validate(value: JSONObject): JSONObject {
            require(value.length() in 1..10 && value.toString().toByteArray(Charsets.UTF_8).size <= 6_000)
            value.keys().forEach { key ->
                val v = value.get(key)
                if (key in strings) {
                    require(v is String && v.isNotBlank() && v.trim() != "***" && v.length <= (if (key.endsWith("api_key")) 2048 else 256) && v.none { it.isISOControl() })
                } else {
                    require(v is Number)
                    val n = v.toDouble()
                    require(n.isFinite() && when (key) {
                        "stt_silence_seconds" -> n in 1.8..6.0
                        "tts_speed" -> n in 0.5..2.0
                        "llm_temperature" -> n in 0.0..2.0
                        else -> false
                    })
                }
            }
            return JSONObject(value.toString())
        }
    }
}
