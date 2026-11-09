package org.amalia.unifiedhome

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Public routing preferences, not permissions to invoke arbitrary host tools. Empty target = automatic. */
data class CoordinationPreferences(
    val mode: String = "automatic",
    val brain: String = "",
    val listener: String = "",
    val executor: String = "",
    val responder: String = "",
    val roles: Set<String> = ROLE_NAMES.toSet(),
) {
    fun target(role: String): String = when (role) {
        "brain" -> brain; "listener" -> listener; "executor" -> executor; "responder" -> responder
        else -> ""
    }
    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("brain", brain)
        .put("listener", listener).put("executor", executor).put("responder", responder)
        .put("roles", JSONArray(roles.sorted()))

    companion object {
        val ROLE_NAMES = listOf("brain", "listener", "executor", "responder")
        fun fromJson(json: JSONObject): CoordinationPreferences {
            val keys = json.keys()
            val allowed = ROLE_NAMES.toSet() + setOf("mode", "roles")
            while (keys.hasNext()) require(keys.next() in allowed) { "Unknown coordination setting" }
            val mode = json.optString("mode", "automatic")
            require(mode in setOf("automatic", "manual"))
            fun target(key: String): String {
                require(!json.has(key) || json.opt(key) is String)
                val value = json.optString(key)
                require(value.isEmpty() || HomeLink.validText(value, 128))
                return value
            }
            require(!json.has("roles") || json.opt("roles") is JSONArray)
            val array = json.optJSONArray("roles") ?: JSONArray(ROLE_NAMES)
            require(array.length() <= ROLE_NAMES.size)
            val roles = (0 until array.length()).map { array.getString(it) }.toSet()
            require(roles.all { it in ROLE_NAMES })
            return CoordinationPreferences(mode, target("brain"), target("listener"), target("executor"), target("responder"), roles)
        }
    }
}

/** Host integration boundary. No audio, transcript, user prompt or conversation is accepted by mesh. */
interface HomeVoiceCoordinator {
    /** Publish only a proximity score after local consent/VAD; expires quickly. Does not start recording. */
    fun publishProximity(score: Double)
    /** Opaque detection id (32 lowercase hex digits); overlapping detections share the leader lease. */
    suspend fun arbitrate(roundId: String): JSONObject
}

/** Narrow, public extension of catalog RPC, compatible with v2's fixed operation allowlist. */
class HomeCoordination internal constructor(private val mesh: MeshService) : HomeVoiceCoordinator {
    companion object { const val VERSION = 1; private const val SAMPLE_TTL = 2_000L }
    private data class Sample(val score: Double, val at: Long)
    private data class SceneRef(val id: String, val node: String, val scene: String, val dependencies: List<String>)
    private data class Dispatch(val expires: Long, val home: String, val session: String,
        val generation: Long, val rows: List<JSONObject>)
    @Volatile private var generation = 0L
    @Volatile private var sample: Sample? = null
    @Volatile private var engine: HomeEngine? = null
    @Volatile private var voiceHookEnabled = false
    private val rounds = LinkedHashMap<String, Pair<Long, JSONObject>>()
    private val roundLock = Mutex()
    private var voiceLease: Triple<String, Long, JSONObject>? = null
    private val dispatches = ConcurrentHashMap<String, Dispatch>()
    // Shared across tokens; clearing membership must not release an in-flight node lock.
    private val executionLocks = ConcurrentHashMap<String, Mutex>()
    private val executionSlots = Semaphore(4)

    internal fun attach(home: HomeEngine) { engine = home }
    internal fun setVoiceHookEnabled(enabled: Boolean) { voiceHookEnabled = enabled }
    internal fun clear() {
        generation++
        sample = null
        // Sessions are included in round cache keys; old entries expire and cannot authorize work.
        dispatches.clear()
    }
    override fun publishProximity(score: Double) {
        require(score.isFinite() && score in 0.0..100.0)
        sample = Sample(score, android.os.SystemClock.elapsedRealtime())
    }
    private fun currentScore(): Double? = sample?.takeIf {
        android.os.SystemClock.elapsedRealtime() - it.at in 0..SAMPLE_TTL
    }?.score
    private fun ok(data: JSONObject) = JSONObject().put("ok", true).put("data", data).put("error", "")
    private fun unavailable(reason: String) = JSONObject().put("ok", false).put("data", JSONObject())
        .put("error", reason)

    fun preferences(): JSONObject = mesh.settings.coordination.toJson()
    fun updatePreferences(json: JSONObject): JSONObject = mesh.updateCoordination(CoordinationPreferences.fromJson(json)).toJson()

    /** Explicitly public metadata only; never device states, bridge endpoints, audit or conversation. */
    fun publicContext(): JSONObject {
        val home = engine
        return JSONObject().put("coordination_version", VERSION).put("node_id", mesh.nodeId)
            .put("preferences", preferences()).put("capability_catalog", CapabilityCatalog.toJson())
            .put("voice_hook", if (voiceHookEnabled) "wake_arbitration" else "disabled").put("proximity_available", currentScore() != null)
            .put("devices", JSONArray(home?.store?.devices()?.take(40)?.map { it.catalogJson() } ?: emptyList<JSONObject>()))
            .put("scenes", JSONArray(home?.store?.scenes()?.take(40)?.map {
                JSONObject().put("id", it.id).put("title", it.title.take(80)).put("steps", it.steps.size)
            } ?: emptyList<JSONObject>()))
            .put("scope", "public_metadata_only")
    }

    internal suspend fun handle(args: JSONObject): JSONObject {
        require(args.length() == 1)
        val request = args.getJSONObject("coordination")
        return when (request.getString("action")) {
            "context" -> { require(request.length() == 1); ok(publicContext()) }
            "sample" -> {
                require(request.length() == 2 && HomeLink.validUuid(request.getString("round")))
                val score = currentScore()
                ok(JSONObject().put("round", request.getString("round")).put("preferences", preferences())
                    .put("score", score ?: JSONObject.NULL))
            }
            "arbitrate" -> {
                require(request.length() == 2)
                resolveRound(request.getString("round"))
            }
            else -> unavailable("Unsupported coordination operation")
        }
    }

    suspend fun discover(nodeId: String): JSONObject = if (nodeId == mesh.nodeId) ok(publicContext())
        else rpc(nodeId, "context")

    private suspend fun rpc(node: String, action: String, round: String? = null): JSONObject {
        val body = JSONObject().put("action", action)
        if (round != null) body.put("round", round)
        return mesh.request(node, "catalog", JSONObject().put("coordination", body))
    }

    /** One deterministic coordinator and one shared round: peers supply their OWN fresh measurements. */
    override suspend fun arbitrate(roundId: String): JSONObject {
        require(HomeLink.validUuid(roundId))
        val leader = mesh.nodes.value.filter { it.online() && (it.self || it.coordinationVersion == VERSION) }
            .minByOrNull { it.id } ?: return unavailable("No coordinator")
        return if (leader.self) resolveRound(roundId) else rpc(leader.id, "arbitrate", roundId)
    }

    /** Only the actual host wake callback may label a decision as controlling a voice turn.
     * Cached/network decisions remain diagnostic so tools cannot claim to control audio.
     */
    internal suspend fun arbitrateWakeRound(roundId: String): JSONObject {
        val startGeneration = generation
        val home = mesh.membership().optString("home_id")
        val session = mesh.nodes.value.firstOrNull { it.self }?.session
        if (!voiceHookEnabled || !mesh.running || home.isEmpty() || session == null) {
            return unavailable("Voice hook unavailable; do not answer")
        }
        val result = JSONObject(arbitrate(roundId).toString())
        if (!voiceHookEnabled || !mesh.running || generation != startGeneration ||
            mesh.membership().optString("home_id") != home ||
            mesh.nodes.value.firstOrNull { it.self }?.session != session) {
            return unavailable("Voice session changed; do not answer")
        }
        if (result.optBoolean("ok")) result.getJSONObject("data").put("diagnostic_only", false)
        return result
    }

    private suspend fun resolveRound(round: String): JSONObject = roundLock.withLock {
        require(HomeLink.validUuid(round))
        val nodes = mesh.nodes.value.filter { it.online() && (it.self || it.coordinationVersion == VERSION) }
        if (nodes.minByOrNull { it.id }?.self != true) return@withLock unavailable("Coordinator changed; do not answer")
        val session = nodes.first { it.self }.session
        val startGeneration = generation
        val view = "$session:$startGeneration:" + nodes.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.session}" }
        val key = "$view:$round"
        val now = android.os.SystemClock.elapsedRealtime()
        rounds.entries.removeAll { now - it.value.first > 30_000 }
        rounds[key]?.let {
            return@withLock if (now - it.first < 2_000) JSONObject(it.second.toString()).apply {
                getJSONObject("data").put("lease_ms", 2_000 - (now - it.first))
            } else unavailable("Voice round expired; do not answer")
        }
        // Independent wake detectors need not invent matching ids: overlapping requests join
        // the coordinator's active decision, never start competing elections in separate rounds.
        if (rounds.size >= 64) return@withLock unavailable("Too many voice rounds")
        voiceLease?.takeIf { it.first == view && now - it.second < 2_000 }?.let {
            val joined = JSONObject(it.third.toString()).apply {
                getJSONObject("data").put("lease_ms", 2_000 - (now - it.second))
            }
            rounds[key] = it.second to JSONObject(joined.toString())
            return@withLock joined
        }
        // Settle independent wake detections before reading proximity. This suspend
        // delay holds only the election mutex, never the host's audio mutex.
        delay(150L)
        val samples = withTimeoutOrNull(900) { coroutineScope {
            nodes.map { node -> async {
                val response = if (node.self) ok(JSONObject().put("preferences", preferences())
                    .put("score", currentScore() ?: JSONObject.NULL)) else rpc(node.id, "sample", round)
                node to response
            } }.awaitAll()
        } } ?: return@withLock unavailable("Voice round timed out; do not answer")
        // A missing eligible peer could be the manual target. Fail closed instead of making two speakers.
        if (generation != startGeneration || samples.any { !it.second.optBoolean("ok") } || mesh.nodes.value.firstOrNull { it.self }?.session != session ||
            nodes.any { original -> mesh.nodes.value.none { it.id == original.id && it.session == original.session && it.online() } } ||
            mesh.nodes.value.filter { it.online() && (it.self || it.coordinationVersion == VERSION) }.minByOrNull { it.id }?.self != true) {
            return@withLock unavailable("Incomplete voice round; do not answer")
        }
        val prefs = mesh.settings.coordination
        val routing = JSONObject()
        for (role in CoordinationPreferences.ROLE_NAMES) {
            val candidates = samples.filter { (_, reply) ->
                val roles = reply.optJSONObject("data")?.optJSONObject("preferences")?.optJSONArray("roles")
                (0 until (roles?.length() ?: 0)).any { roles?.optString(it) == role }
            }
            val manualTargets = samples.mapNotNull { (_, reply) ->
                val settings = reply.optJSONObject("data")?.optJSONObject("preferences")
                if (settings?.optString("mode") == "manual") settings.optString(role).takeIf { it.isNotBlank() } else null
            }.distinct()
            // A manual selection made on any phone must beat acoustic proximity.
            // Conflicting explicit selections fail closed instead of choosing arbitrarily.
            val target = if (prefs.mode == "manual" && prefs.target(role).isNotEmpty()) prefs.target(role)
                else manualTargets.singleOrNull().orEmpty()
            val chosen = if (manualTargets.size > 1 && target.isEmpty()) null else if (target.isNotEmpty()) {
                candidates.firstOrNull { it.first.id == target }
            } else {
                candidates.filter { role !in setOf("listener", "responder") ||
                    (it.second.optJSONObject("data")?.opt("score") as? Number)?.toDouble()?.let { score ->
                        score.isFinite() && score in 0.0..100.0
                    } == true }
                    .maxWithOrNull(compareBy<Pair<MeshNode, JSONObject>> {
                        if (role in setOf("listener", "responder")) it.second.getJSONObject("data").getDouble("score") else 0.0
                    }.thenBy { it.first.priority }.thenBy { it.first.id })
            }
            routing.put(role, chosen?.first?.id ?: JSONObject.NULL)
        }
        val decision = ok(JSONObject().put("round", round).put("coordinator", mesh.nodeId)
            .put("routing", routing).put("participants", samples.size).put("lease_ms", 2_000)
            .put("diagnostic_only", true))
        val decidedAt = android.os.SystemClock.elapsedRealtime()
        rounds[key] = decidedAt to JSONObject(decision.toString())
        voiceLease = Triple(view, decidedAt, JSONObject(decision.toString()))
        decision
    }

    /** Validate the complete DAG before requesting any fixed, single-use scene previews. */
    suspend fun planScenes(raw: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        require(raw.length() in 1..64) { "Expected 1 to 64 scenes" }
        val home = engine ?: return@withContext unavailable("Home unavailable")
        dispatches.entries.removeAll { it.value.expires <= System.currentTimeMillis() }
        require(dispatches.size < 32) { "Too many pending dispatches" }
        val startGeneration = generation
        val startHome = mesh.membership().optString("home_id")
        val startSession = mesh.nodes.value.firstOrNull { it.self }?.session.orEmpty()
        val prefs = mesh.settings.coordination
        val defaultNode = if (prefs.mode == "manual" && prefs.executor.isNotEmpty()) prefs.executor else mesh.nodeId
        val refs = (0 until raw.length()).map { index ->
            val row = raw.getJSONObject(index)
            val keys = row.keys()
            while (keys.hasNext()) require(keys.next() in setOf("node", "scene", "id", "depends_on")) { "Unknown scene field" }
            require(row.opt("scene") is String && (!row.has("node") || row.opt("node") is String) &&
                (!row.has("id") || row.opt("id") is String)) { "Scene, node and id must be strings" }
            val node = (if (row.has("node")) row.getString("node") else defaultNode).let {
                if (it == "self") mesh.nodeId else it
            }
            val scene = row.getString("scene")
            val id = if (row.has("id")) row.getString("id") else "scene_${index + 1}"
            require(HomeLink.validText(node, 128) && HomeLink.validText(scene, 256) && HomeLink.validText(id, 128))
            require(!row.has("depends_on") || row.opt("depends_on") is JSONArray) { "depends_on must be an array of ids" }
            val deps = row.optJSONArray("depends_on") ?: JSONArray()
            require(deps.length() <= 64)
            val dependencies = (0 until deps.length()).map {
                require(deps.opt(it) is String) { "Dependency ids must be strings" }
                deps.getString(it)
            }
            require(dependencies.distinct().size == dependencies.size) { "Duplicate dependency" }
            SceneRef(id, node, scene, dependencies)
        }
        require(refs.map { it.id }.distinct().size == refs.size) { "Duplicate scene id" }
        require(refs.map { it.node to it.scene }.distinct().size == refs.size) { "Duplicate scene reference" }
        val ids = refs.map { it.id }.toSet()
        require(refs.all { ref -> ref.dependencies.all { it in ids && it != ref.id } }) { "Unknown or self dependency" }
        // Kahn ordering also supports references to rows later in the input.
        val ordered = mutableListOf<SceneRef>()
        val remaining = refs.toMutableList()
        val visited = HashSet<String>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { ref -> ref.dependencies.all { it in visited } }
            require(ready.isNotEmpty()) { "Scene dependencies contain a cycle" }
            ordered.addAll(ready)
            visited.addAll(ready.map { it.id })
            remaining.removeAll(ready.toSet())
        }
        val sessions = refs.map { it.node }.distinct().associateWith { node ->
            mesh.nodes.value.firstOrNull { it.id == node && it.online() }?.session.orEmpty()
        }
        fun unchanged(): Boolean = generation == startGeneration &&
            startHome == mesh.membership().optString("home_id") &&
            startSession == mesh.nodes.value.firstOrNull { it.self }?.session.orEmpty()
        val rows = mutableListOf<JSONObject>()
        var expires = Long.MAX_VALUE
        for (ref in ordered) {
            val session = sessions.getValue(ref.node)
            if (!unchanged() || session.isEmpty() || mesh.nodes.value.none {
                    it.id == ref.node && it.session == session && it.online()
                }) return@withContext unavailable("Node or home session changed during planning; nothing applied")
            // Count validity from BEFORE the request, not from the end of all planning.
            val requestedAt = System.currentTimeMillis()
            val plan = if (ref.node == mesh.nodeId) home.planScene(ref.scene)?.toJson() else {
                val response = mesh.request(ref.node, "plan_scene", JSONObject().put("scene", ref.scene))
                if (response.optBoolean("ok")) response.optJSONObject("data") else null
            }
            if (plan == null || plan.optString("token").isEmpty()) return@withContext unavailable("A scene could not be planned; nothing applied")
            val steps = plan.optJSONArray("steps") ?: return@withContext unavailable("Invalid plan")
            if ((0 until steps.length()).any { steps.getJSONObject(it).optString("status") == StepStatus.BLOCKED }) {
                return@withContext unavailable("Blocked scene; nothing applied")
            }
            val ttl = plan.optLong("expires_in_sec", 0)
            require(ttl in 1..86_400) { "Invalid plan lifetime" }
            expires = minOf(expires, requestedAt + ttl * 1000)
            rows += JSONObject().put("id", ref.id).put("node", ref.node).put("scene", ref.scene)
                .put("depends_on", JSONArray(ref.dependencies)).put("plan", JSONObject(plan.toString())).put("session", session)
        }
        // Repeated steps within ONE scene are valid; only overlap BETWEEN scenes is rejected.
        val devices = HashSet<Pair<String, String>>()
        for (row in rows) {
            val steps = row.getJSONObject("plan").getJSONArray("steps")
            val sceneDevices = (0 until steps.length()).map { steps.getJSONObject(it).getString("device_id") }.toSet()
            for (device in sceneDevices) require(devices.add(row.getString("node") to device)) {
                "Overlapping scenes must be combined into one scene before planning"
            }
        }
        require(expires > System.currentTimeMillis()) { "A scene plan expired during planning" }
        require(unchanged() && sessions.all { (node, session) -> mesh.nodes.value.any {
            it.id == node && it.session == session && it.online()
        } }) { "Home or node session changed during planning" }
        // Keep output in caller order, independent of the topological scheduling order.
        val byId = rows.associateBy { it.getString("id") }
        val previews = refs.map { JSONObject(byId.getValue(it.id).toString()) }
        val token = newId("dispatch")
        dispatches[token] = Dispatch(expires, startHome, startSession, startGeneration, previews)
        ok(JSONObject().put("token", token).put("plans", JSONArray(previews.map { JSONObject(it.toString()).apply { remove("session") } }))
            .put("expires_at", expires).put("atomic", false).put("requires_confirmation", true))
    }

    private fun confirmed(result: JSONObject): Boolean = result.optBoolean("ok") &&
        result.optJSONObject("data")?.optBoolean("honest", false) == true

    /** A token is consumed once; only failed prerequisites block a branch. No retries or replanning. */
    suspend fun applyScenes(token: String): JSONObject = withContext(Dispatchers.IO) {
        val dispatch = dispatches.remove(token) ?: return@withContext unavailable("Plan missing or already consumed")
        val home = engine ?: return@withContext unavailable("Home unavailable")
        fun dispatchProblem(row: JSONObject): String? = when {
            generation != dispatch.generation || dispatch.home != mesh.membership().optString("home_id") ||
                dispatch.session != mesh.nodes.value.firstOrNull { it.self }?.session.orEmpty() -> "Home session changed"
            dispatch.expires <= System.currentTimeMillis() -> "Plan deadline expired"
            mesh.nodes.value.none { it.id == row.getString("node") && it.online() &&
                it.session == row.getString("session") } -> "Node session changed or unavailable"
            else -> null
        }
        fun notDispatched(reason: String): JSONObject = unavailable(reason).put("status", "not_dispatched")
        val outcomes = coroutineScope {
            val pending = dispatch.rows.toMutableList()
            val tasks = LinkedHashMap<String, Deferred<JSONObject>>()
            while (pending.isNotEmpty()) {
                val ready = pending.filter { row ->
                    val deps = row.getJSONArray("depends_on")
                    (0 until deps.length()).all { deps.getString(it) in tasks }
                }
                check(ready.isNotEmpty()) { "Invalid stored DAG" }
                for (row in ready) {
                    val deps = row.getJSONArray("depends_on")
                    val prerequisites = (0 until deps.length()).map { tasks.getValue(deps.getString(it)) }
                    tasks[row.getString("id")] = async {
                        val dependencyResults = prerequisites.awaitAll()
                        if (dependencyResults.any { !confirmed(it) }) {
                            notDispatched("Prerequisite not confirmed")
                        } else {
                            val node = row.getString("node")
                            executionLocks.computeIfAbsent(node) { Mutex() }.withLock {
                                executionSlots.withPermit {
                                    // Recheck AFTER both queues, immediately before any hardware dispatch.
                                    val problem = dispatchProblem(row)
                                    if (problem != null) notDispatched(problem) else try {
                                        val planToken = row.getJSONObject("plan").getString("token")
                                        if (node == mesh.nodeId) home.apply(planToken)?.let { ok(it) }
                                            ?: notDispatched("Plan expired or unavailable")
                                        else mesh.request(node, "apply_plan", JSONObject().put("token", planToken))
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) {
                                        unavailable("Execution uncertain; do not retry blindly").put("status", "unknown")
                                    }
                                }
                            }
                        }
                    }
                }
                pending.removeAll(ready.toSet())
            }
            tasks.mapValues { it.value.await() }
        }
        val results = dispatch.rows.map { row ->
            JSONObject().put("id", row.getString("id")).put("node", row.getString("node"))
                .put("scene", row.getString("scene")).put("depends_on", JSONArray(row.getJSONArray("depends_on").toString()))
                .put("outcome", outcomes.getValue(row.getString("id")))
        }
        ok(JSONObject().put("results", JSONArray(results)).put("honest", outcomes.values.all { confirmed(it) })
            .put("atomic", false).put("retry_safe", false))
    }
}
