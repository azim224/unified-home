package org.amalia.unifiedhome.v2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.my.amali.plugin.HostServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.MessageDigest

/** Local scheduled-scene pump. Only schedules.task.* uses the household-wide
 * receiver namespace; ordinary sender-scoped tasks are deliberately never used.
 * Call closeAndWait before disposing data/control. No interrupted run is resumed.
 * The optional parent owns lifetime; this scheduler cancels only its own child.
 */
class HomeV2Scheduler(
    context: Context,
    private val node: HomeV2Node,
    host: HostServices,
    private val home: String,
    private val control: HomeV2Control,
    private val data: HomeV2Data,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val local = node.status.value.nodeId
    private val receiver = HomeV2ScheduleReceiver(host, home, control)
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val tasks = CoroutineScope(scope.coroutineContext + supervisor + Dispatchers.IO)
    private val pump = Mutex()
    private val serial = Any()
    private val permits = Semaphore(4)
    private val targets = mutableMapOf<String, Mutex>()
    private var closed = false
    private var owner: RandomAccessFile? = null
    private var ownerLock: FileLock? = null
    private val db: SQLiteDatabase

    private data class Step(val id: String, val target: String, val capability: String,
        val arguments: JSONObject, val dependencies: List<String>)

    init {
        v2Id(home); v2Id(local)
        requireScheduler(control.node === node && data.nodeId == local && data.homeId == home,
            "scheduler_configuration_mismatch")
        var opened: SQLiteDatabase? = null
        try {
            val root = File(context.applicationContext.noBackupFilesDir.canonicalFile,
                "home-v2-scheduler-$home-$local")
            requireScheduler(root.canonicalFile == root.absoluteFile, "unsafe_storage_path")
            requireScheduler(root.isDirectory || root.mkdirs(), "scheduler_storage_unavailable")
            val lock = File(root, "scheduler.owner")
            val file = File(root, "scheduler.sqlite3")
            requireScheduler(lock.canonicalFile == lock.absoluteFile && file.canonicalFile == file.absoluteFile,
                "unsafe_storage_path")
            owner = RandomAccessFile(lock, "rw")
            ownerLock = owner!!.channel.tryLock()
            requireScheduler(ownerLock != null, "scheduler_storage_unavailable")
            opened = SQLiteDatabase.openOrCreateDatabase(file, null)
            db = opened
            db.execSQL("PRAGMA journal_mode=DELETE")
            db.execSQL("PRAGMA synchronous=FULL")
            db.execSQL("PRAGMA busy_timeout=5000")
            requireScheduler(db.version in 0..1, "unsupported_scheduler_version")
            transaction {
                db.execSQL("CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, digest TEXT NOT NULL, state TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS steps (run TEXT NOT NULL, id TEXT NOT NULL, task TEXT NOT NULL, state TEXT NOT NULL, result TEXT, PRIMARY KEY(run,id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO meta VALUES ('cursor','')")
                db.execSQL("INSERT OR IGNORE INTO meta VALUES ('after','')")
                // A queued step in an interrupted run also cannot safely be resumed.
                db.execSQL("UPDATE steps SET state=CASE WHEN state='running' THEN 'unknown' ELSE 'blocked' END WHERE state IN ('running','queued')")
                db.execSQL("UPDATE runs SET state='unknown' WHERE state='running'")
                db.version = 1
            }
        } catch (failure: Exception) {
            opened?.close()
            ownerLock?.release(); owner?.close()
            supervisor.cancel()
            if (failure is HomeV2Exception) throw failure
            throw HomeV2Exception("scheduler_storage_unavailable")
        }
    }

    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }

    private fun open() { requireScheduler(!closed, "scheduler_closed") }
    private fun membership(): String {
        val identity = node.status.value
        requireScheduler(identity.running && identity.nodeId == local && identity.homeId == home,
            "inactive_membership")
        val status = control.status()
        requireScheduler(status.optBoolean("can_schedule", false), "coordinator_not_leader")
        return status.getString("membership")
    }

    private fun meta(key: String): String = db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use {
        requireScheduler(it.moveToFirst(), "scheduler_storage_unavailable"); it.getString(0)
    }
    private fun progress(cursor: String, after: String) = synchronized(serial) {
        transaction {
            db.execSQL("UPDATE meta SET v=? WHERE k='cursor'", arrayOf(cursor))
            db.execSQL("UPDATE meta SET v=? WHERE k='after'", arrayOf(after))
        }
    }

    /** Bounded admission, not completion. At most 128 previously unvisited records
     * per tick (including disabled/future/invalid records). The durable cursor and
     * high-water ID survive ticks/restarts and stale cursors; no first-N starvation.
     * Scene records are fetched individually through the authenticated local API.
     */
    suspend fun tick(): JSONObject = withContext(Dispatchers.IO) {
        pump.withLock {
            synchronized(serial) { open() }
            supervisor.ensureActive()
            val runs = JSONArray(); val errors = JSONArray()
            val result = JSONObject().put("runs", runs).put("errors", errors)
                .put("automatic_execution_enabled", true).put("examined", 0)
            try { membership() }
            catch (failure: HomeV2Exception) {
                return@withLock result.put("skipped", failure.code)
            }
            var cursor = synchronized(serial) { meta("cursor") }
            var after = synchronized(serial) { meta("after") }
            var examined = 0
            var staleRestarts = 0
            while (examined < 128) {
                val args = JSONObject().put("scope", "home").put("kind", "schedules")
                    .put("limit", minOf(100, 128 - examined))
                if (cursor.isNotEmpty()) args.put("cursor", cursor)
                val page = try { data.handle("data.list", args, local) }
                catch (failure: HomeV2Exception) {
                    if (failure.code == "stale_cursor" && cursor.isNotEmpty()) {
                        cursor = ""; progress(cursor, after)
                        if (staleRestarts++ == 0) continue
                    }
                    errors.put(JSONObject().put("code", failure.code)); break
                }
                val records = page.getJSONArray("records")
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)
                    val schedule = record.getString("id")
                    // On cursor invalidation, walk back to the durable high-water ID.
                    if (schedule <= after) continue
                    examined++
                    try {
                        val value = record.getJSONObject("value")
                        fields(value, setOf("enabled", "scene_id", "at_ms"), setOf("interval_ms"))
                        val sceneId = identifier(value.opt("scene_id"))
                        requireScheduler(value.opt("enabled") is Boolean, "invalid_schedule")
                        val at = integer(value.opt("at_ms"), 0)
                        val interval = if (value.has("interval_ms")) integer(value.opt("interval_ms"), 1) else null
                        val now = System.currentTimeMillis()
                        if (value.getBoolean("enabled") && at <= now) {
                            val occurrence = if (interval == null) at else at + ((now - at) / interval) * interval
                            val sceneRecord = data.handle("data.get", JSONObject().put("scope", "home")
                                .put("kind", "scenes").put("id", sceneId), local).getJSONObject("record")
                            requireScheduler(!sceneRecord.getBoolean("tombstone"), "scene_deleted")
                            val scene = JSONObject(sceneRecord.getJSONObject("value").toString())
                            val steps = validate(scene)
                            // Exact PC codec: compact ASCII JSON, integer occurrence,
                            // no sender/term/revision in identity. IDs cannot contain escapes.
                            val seed = "[\"home-schedule-v1\",${JSONObject.quote(home)},${JSONObject.quote(identifier(schedule))},$occurrence]"
                            val run = sha(seed).take(32)
                            val member = membership()
                            claim(run, sha(canonical(scene, wire = true)), member)
                            requireScheduler(membership() == member, "coordination_membership_mismatch")
                            runs.put(admit(run, scene, steps, member))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        errors.put(JSONObject().put("schedule_id", schedule)
                            .put("code", (failure as? HomeV2Exception)?.code ?: "scheduler_error"))
                    }
                    after = schedule
                    // Save only after admission; a crash before this is deduplicated by runs.
                    progress(cursor, after)
                }
                cursor = if (page.isNull("next_cursor")) "" else page.getString("next_cursor")
                if (cursor.isEmpty()) {
                    after = ""; progress(cursor, after); break
                }
                progress(cursor, after)
            }
            result.put("examined", examined).put("continuation", cursor.isNotEmpty() || after.isNotEmpty())
                .put("coordination", control.status())
        }
    }

    /** Fixed voters durably bind the first digest; neither discovery nor a
     * transport success is itself a vote. Recheck the lease after all awaits. */
    private suspend fun claim(run: String, fingerprint: String, member: String) {
        requireScheduler(membership() == member, "coordination_membership_mismatch")
        val configured = control.status().getJSONArray("voters")
        val voters = (0 until configured.length()).map { identifier(configured.get(it)) }.toSet()
        requireScheduler(voters.isNotEmpty() && local in voters, "inactive_membership")
        val routes = node.peers.value.filter { it.homeId == home }.associateBy { it.nodeId }
        val accepted = coroutineScope {
            voters.map { voter -> async {
                try {
                    val args = JSONObject().put("membership", member).put("run_id", run)
                        .put("payload_digest", fingerprint)
                    val reply = withTimeoutOrNull(1500L) {
                        if (voter == local) control.handle("coord.claim", args, local)
                        else {
                            val peer = routes[voter] ?: throw HomeV2Exception("peer_unavailable")
                            val response = node.request(peer.address, peer.port, voter, "coord.claim", args)
                            requireScheduler(response.ok, response.error ?: "peer_unavailable")
                            response.data
                        }
                    } ?: return@async false
                    fields(reply, setOf("membership", "run_id", "payload_digest", "accepted"))
                    reply.opt("accepted") == true && reply.opt("membership") == member &&
                        reply.opt("run_id") == run && reply.opt("payload_digest") == fingerprint
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            } }.awaitAll().count { it }
        }
        requireScheduler(accepted >= voters.size / 2 + 1, "schedule_claim_no_quorum")
        requireScheduler(membership() == member, "coordination_membership_mismatch")
    }

    private fun admit(run: String, scene: JSONObject, steps: Map<String, Step>, member: String): JSONObject = synchronized(serial) {
        open(); supervisor.ensureActive()
        // This is a local conservative payload fingerprint, NOT a wire codec.
        // Numeric Java type tags avoid conflating distinct JSON number payloads.
        val fingerprint = sha(canonical(scene))
        var fresh = false
        transaction {
            val previous = db.rawQuery("SELECT digest FROM runs WHERE id=?", arrayOf(run)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (previous != null) requireScheduler(previous == fingerprint, "task_id_conflict")
            else {
                db.execSQL("INSERT INTO runs VALUES (?,?,'running')", arrayOf(run, fingerprint))
                for (step in steps.values) db.execSQL("INSERT INTO steps VALUES (?,?,?,'queued',NULL)",
                    arrayOf(run, step.id, taskId(run, step.id)))
                fresh = true
            }
        }
        if (fresh) {
            // LAZY prevents any effect until after the durable transaction commits.
            val job = tasks.launch(start = CoroutineStart.LAZY) { executeRun(run, steps, member) }
            // Even cancellation before the coroutine body starts must fence this run.
            job.invokeOnCompletion { failure ->
                if (failure != null) synchronized(serial) {
                    if (!closed) interrupt(run)
                }
            }
            job.start()
        }
        snapshot(run)
    }

    /** Snapshot reflects durable state; admission/queued is never reported as success. */
    suspend fun get(runId: String): JSONObject = withContext(Dispatchers.IO) {
        identifier(runId)
        synchronized(serial) { open(); snapshot(runId) }
    }
    private fun snapshot(run: String): JSONObject {
        val state = db.rawQuery("SELECT state FROM runs WHERE id=?", arrayOf(run)).use {
            requireScheduler(it.moveToFirst(), "task_not_found"); it.getString(0)
        }
        val steps = JSONArray()
        db.rawQuery("SELECT id,task,state,result FROM steps WHERE run=? ORDER BY id", arrayOf(run)).use {
            while (it.moveToNext()) steps.put(JSONObject().put("id", it.getString(0))
                .put("task_id", it.getString(1)).put("state", it.getString(2))
                .put("result", if (it.isNull(3)) JSONObject.NULL else JSONObject(it.getString(3))))
        }
        return JSONObject().put("run_id", run).put("state", state).put("steps", steps)
            .put("blocked", if (state == "unknown") "local_run_unknown" else JSONObject.NULL)
    }

    private fun stepState(run: String, step: String, state: String, result: JSONObject? = null) = synchronized(serial) {
        db.execSQL("UPDATE steps SET state=?,result=? WHERE run=? AND id=?",
            arrayOf(state, result?.toString(), run, step))
    }
    private fun interrupt(run: String) {
        transaction {
            db.execSQL("UPDATE runs SET state='unknown' WHERE id=? AND state='running'", arrayOf(run))
            db.execSQL("UPDATE steps SET state=CASE WHEN state='running' THEN 'unknown' ELSE 'blocked' END WHERE run=? AND state IN ('queued','running')", arrayOf(run))
        }
    }

    private suspend fun executeRun(run: String, steps: Map<String, Step>, member: String) {
        try {
            val states = mutableMapOf<String, String>()
            val pending = steps.keys.toMutableSet()
            while (pending.isNotEmpty()) {
                val ready = pending.sorted().filter { key -> steps.getValue(key).dependencies.all { it in states } }
                val completed = coroutineScope {
                    ready.map { key -> async {
                        val step = steps.getValue(key)
                        val state = if (step.dependencies.any { states[it] != "succeeded" }) {
                            stepState(run, key, "blocked"); "blocked"
                        } else perform(run, step, member)
                        key to state
                    } }.awaitAll()
                }
                completed.forEach { (key, state) -> states[key] = state; pending.remove(key) }
            }
            val state = when {
                states.values.all { it == "succeeded" } -> "succeeded"
                "unknown" in states.values -> "unknown"
                else -> "error"
            }
            synchronized(serial) { db.execSQL("UPDATE runs SET state=? WHERE id=? AND state='running'", arrayOf(state, run)) }
        } catch (cancelled: CancellationException) {
            synchronized(serial) { interrupt(run) }; throw cancelled
        } catch (_: Exception) {
            synchronized(serial) { interrupt(run) }
        }
    }

    private suspend fun perform(run: String, step: Step, member: String): String {
        val target = synchronized(serial) { targets.getOrPut(step.target) { Mutex() } }
        // Take target before permit so queued work for one device cannot occupy
        // all four slots and starve independent devices in other runs.
        return target.withLock {
            permits.withPermit {
                try {
                    requireScheduler(membership() == member, "coordination_membership_mismatch")
                    stepState(run, step.id, "running") // durable BEFORE any dispatch
                    val outcome = withTimeoutOrNull(120_000L) {
                        val args = JSONObject().put("run_id", run).put("step_id", step.id)
                            .put("task_id", taskId(run, step.id)).put("membership", member)
                        val submit = JSONObject(args.toString()).put("capability", step.capability)
                            .put("arguments", JSONObject(step.arguments.toString()))
                        var reply = taskCall(step.target, "schedules.task.submit", submit, member)
                        while (reply.optString("state") in setOf("queued", "running")) {
                            delay(250L)
                            reply = taskCall(step.target, "schedules.task.get", args, member)
                        }
                        reply
                    } ?: JSONObject().put("state", "unknown").put("error", "task_timeout")
                    val state = outcome.optString("state").let {
                        if (it in setOf("succeeded", "error", "cancelled", "unknown")) it else "unknown"
                    }
                    stepState(run, step.id, state, outcome)
                    state
                } catch (cancelled: CancellationException) {
                    stepState(run, step.id, "unknown"); throw cancelled
                } catch (failure: Exception) {
                    stepState(run, step.id, "unknown", JSONObject().put("state", "unknown")
                        .put("error", (failure as? HomeV2Exception)?.code ?: "task_unavailable"))
                    "unknown"
                }
            }
        }
    }

    private suspend fun taskCall(target: String, operation: String, args: JSONObject, member: String): JSONObject {
        // Recheck after every await, including lock acquisition and polling delay.
        requireScheduler(membership() == member, "coordination_membership_mismatch")
        if (target == local) return receiver.handle(operation, args, local)
        val peer = node.peers.value.firstOrNull { it.homeId == home && it.nodeId == target }
            ?: throw HomeV2Exception("peer_unavailable")
        val reply = node.request(peer.address, peer.port, target, operation, args)
        requireScheduler(reply.ok, reply.error ?: "peer_unavailable")
        return reply.data
    }

    /** Cancels only owned tasks, waits for their cleanup, then closes storage.
     * Unknown effects and queued dependencies are persisted before returning.
     */
    suspend fun closeAndWait() = withContext(NonCancellable + Dispatchers.IO) {
        pump.withLock {
            if (!synchronized(serial) { closed }) {
                supervisor.cancelAndJoin()
                synchronized(serial) {
                    transaction {
                        db.execSQL("UPDATE runs SET state='unknown' WHERE state='running'")
                        db.execSQL("UPDATE steps SET state=CASE WHEN state='running' THEN 'unknown' ELSE 'blocked' END WHERE state IN ('running','queued')")
                    }
                    closed = true
                    try { db.close() }
                    finally { try { ownerLock?.release() } finally { ownerLock = null; owner?.close(); owner = null } }
                }
            }
        }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        fun requireScheduler(ok: Boolean, code: String = "invalid_scene") { if (!ok) throw HomeV2Exception(code) }
        fun identifier(value: Any?): String {
            requireScheduler(value is String && ID.matches(value), "invalid_identifier")
            return value as String
        }
        fun integer(value: Any?, minimum: Long): Long {
            requireScheduler(value is Int || value is Long, "invalid_schedule")
            val result = (value as Number).toLong()
            requireScheduler(result in minimum..9223372036854775806L, "invalid_schedule")
            return result
        }
        fun fields(value: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
            val keys = value.keys().asSequence().toSet()
            requireScheduler(keys.containsAll(required) && (keys - required - optional).isEmpty(), "invalid_arguments")
        }
        fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun taskId(run: String, step: String) = sha("[${JSONObject.quote(run)},${JSONObject.quote(step)}]").take(32)

        fun validate(scene: JSONObject): Map<String, Step> {
            fields(scene, setOf("steps"))
            val array = scene.opt("steps") as? JSONArray ?: throw HomeV2Exception("invalid_scene")
            requireScheduler(array.length() in 1..128)
            val steps = linkedMapOf<String, Step>()
            for (i in 0 until array.length()) {
                val value = array.opt(i) as? JSONObject ?: throw HomeV2Exception("invalid_scene")
                fields(value, setOf("id", "node", "capability", "arguments", "depends_on"))
                val id = identifier(value.opt("id")); val target = identifier(value.opt("node"))
                val capability = identifier(value.opt("capability"))
                requireScheduler(!capability.startsWith("home_") && !capability.startsWith("home.") && !capability.startsWith("assistant."), "permission_denied")
                val arguments = value.opt("arguments") as? JSONObject ?: throw HomeV2Exception("invalid_scene")
                arguments.keys().asSequence().forEach { key ->
                    requireScheduler(arguments.get(key) !is JSONObject && arguments.get(key) !is JSONArray, "invalid_arguments")
                }
                val deps = value.opt("depends_on") as? JSONArray ?: throw HomeV2Exception("invalid_scene")
                requireScheduler(deps.length() <= 128)
                val dependencies = (0 until deps.length()).map { identifier(deps.opt(it)) }
                requireScheduler(dependencies.toSet().size == dependencies.size && id !in steps)
                steps[id] = Step(id, target, capability, arguments, dependencies)
            }
            val remaining = steps.keys.toMutableSet(); val visited = mutableSetOf<String>()
            while (remaining.isNotEmpty()) {
                val ready = remaining.filter { steps.getValue(it).dependencies.all { dep -> dep in visited } }
                requireScheduler(ready.isNotEmpty(), "scene_cycle_or_missing_dependency")
                visited.addAll(ready); remaining.removeAll(ready.toSet())
            }
            canonical(scene)
            return steps
        }

        // Default retains the legacy local fingerprint. Wire mode is PC codec
        // compact sorted ASCII JSON, SHA-256 over the scene ONLY, exact targets
        // included. Unsupported floating encodings fail closed before claims.
        fun canonical(value: Any?, wire: Boolean = false): String {
            val out = StringBuilder()
            fun quote(text: String) {
                out.append('"')
                if (wire) {
                    var index = 0
                    while (index < text.length) {
                        val c = text[index++]
                        if (Character.isHighSurrogate(c)) {
                            requireScheduler(index < text.length && Character.isLowSurrogate(text[index]), "invalid_unicode")
                            index++
                        } else requireScheduler(!Character.isLowSurrogate(c), "invalid_unicode")
                    }
                }
                for (c in text) when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    wire && c == '\b' -> out.append("\\b")
                    wire && c == '\u000c' -> out.append("\\f")
                    wire && c == '\n' -> out.append("\\n")
                    wire && c == '\r' -> out.append("\\r")
                    wire && c == '\t' -> out.append("\\t")
                    c.code < 32 || c.code > 126 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> out.append(c)
                }
                out.append('"')
            }
            fun visit(item: Any?, depth: Int) {
                requireScheduler(depth <= 16, "json_too_deep")
                when (item) {
                    null, JSONObject.NULL -> out.append("null")
                    is JSONObject -> {
                        out.append('{')
                        item.keys().asSequence().toList().sortedWith(Comparator { a, b ->
                            if (!wire) a.compareTo(b) else {
                                var ai = 0; var bi = 0; var order = 0
                                while (ai < a.length && bi < b.length && order == 0) {
                                    val ac = Character.codePointAt(a, ai); val bc = Character.codePointAt(b, bi)
                                    order = ac.compareTo(bc)
                                    ai += Character.charCount(ac); bi += Character.charCount(bc)
                                }
                                if (order != 0) order else (a.length - ai).compareTo(b.length - bi)
                            }
                        }).forEachIndexed { i, key ->
                            if (i > 0) out.append(',')
                            if (wire) requireScheduler(depth < 16, "json_too_deep")
                            quote(key); out.append(':'); visit(item.get(key), depth + 1)
                        }
                        out.append('}')
                    }
                    is JSONArray -> {
                        out.append('[')
                        for (i in 0 until item.length()) { if (i > 0) out.append(','); visit(item.get(i), depth + 1) }
                        out.append(']')
                    }
                    is String -> quote(item)
                    is Boolean -> out.append(item)
                    is Number -> {
                        requireScheduler(item.toDouble().isFinite(), "invalid_json_number")
                        if (wire) {
                            // Fail closed: Java/Python floating JSON spellings are
                            // not interchangeable. Only exact integers are admitted.
                            requireScheduler(item is Int || item is Long, "schedule_digest_unsupported_number")
                            out.append(item.toString())
                        } else {
                            // Preserve existing local DB fingerprints on upgrade.
                            out.append('#').append(item.javaClass.name).append(':').append(item.toString()).append(';')
                        }
                    }
                    else -> throw HomeV2Exception("invalid_json_value")
                }
                requireScheduler(out.length <= if (wire) 65536 else 262144, "json_too_large")
            }
            visit(value, 0)
            return out.toString()
        }
    }
}
