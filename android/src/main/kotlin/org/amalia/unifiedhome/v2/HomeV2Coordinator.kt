package org.amalia.unifiedhome.v2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/** Fixed-voter lease admission, NOT task execution authority or Byzantine consensus.
 * Only handle() is inbound. Tick's callback must bind peer identity and response to
 * the outstanding request. Shared PSK assumes EVERY member is fully trusted.
 * Keep one identity/storage owner; never restore/copy/delete live coordination DBs.
 */
class HomeV2Coordinator(
    context: Context,
    val nodeId: String,
    val homeId: String,
    voters: Collection<String>,
    val preferred: String? = null,
) : Closeable {
    val voters: List<String> = voters.sorted()
    val majority: Int = if (voters.isEmpty()) 0 else voters.size / 2 + 1
    val membership: String
    val operations: Set<String> = setOf("coord.vote", "coord.status", "coord.preferred", "coord.claim")
    private val serial = Any()
    private val pump = Mutex()
    private var db: SQLiteDatabase? = null
    private var owner: RandomAccessFile? = null
    private var ownerLock: FileLock? = null
    private var failed = false
    private var term = 0L
    private var voteTerm = 0L
    private var voteCandidate: String? = null
    private var voteRequest: String? = null
    private var voteLease = 0.0
    private var grantDeadline = 0.0
    private var lastNow = elapsed()
    private var restartUntil = 0.0
    private var preferredSince: Double? = null
    private var preferredSeen: Double? = null
    private data class Round(val args: JSONObject, val deadline: Double, val votes: MutableSet<String>)
    private var round: Round? = null
    private var pending: JSONObject? = null
    private var campaignDue = 0.0
    private var heartbeatDue = 0.0

    init {
        id(nodeId); id(homeId)
        requireCoord(this.voters.size <= 256 && this.voters.toSet().size == this.voters.size)
        this.voters.forEach(::id)
        requireCoord(this.voters.isEmpty() || nodeId in this.voters)
        if (preferred != null) { id(preferred); requireCoord(preferred in this.voters) }
        // IDs are ASCII with no JSON escapes. This is EXACTLY Python's sorted
        // json.dumps(voters, separators=(",", ":")), including no escaped slash.
        val encoded = this.voters.joinToString(",", "[", "]") { "\"$it\"" }
        membership = sha(encoded)
        try {
            val partition = sha("coordination-v1\n$homeId\n$nodeId")
            val directory = File(context.applicationContext.noBackupFilesDir, "home-v2-coordination-$partition")
            requireCoord(directory.canonicalFile == directory.absoluteFile, "unsafe_storage_path")
            requireCoord(directory.isDirectory || directory.mkdirs(), "coordinator_storage_unavailable")
            val file = File(directory, "election.sqlite3")
            requireCoord(file.canonicalFile == file.absoluteFile, "unsafe_storage_path")
            val lockFile = File(directory, "election.owner")
            requireCoord(lockFile.canonicalFile == lockFile.absoluteFile, "unsafe_storage_path")
            owner = RandomAccessFile(lockFile, "rw")
            ownerLock = owner?.channel?.tryLock()
            requireCoord(ownerLock != null, "coordinator_storage_unavailable")
            val database = SQLiteDatabase.openOrCreateDatabase(file, null)
            db = database
            database.execSQL("PRAGMA journal_mode=DELETE")
            database.execSQL("PRAGMA synchronous=FULL")
            database.execSQL("PRAGMA busy_timeout=5000")
            requireCoord(database.version in 0..1, "unsupported_coordinator_version")
            transaction {
                database.execSQL("CREATE TABLE IF NOT EXISTS coordination (singleton INTEGER PRIMARY KEY CHECK(singleton=1), node TEXT NOT NULL, home TEXT NOT NULL, membership TEXT NOT NULL, preferred TEXT, term INTEGER NOT NULL, vote_term INTEGER NOT NULL, vote_candidate TEXT, vote_request TEXT, vote_lease REAL NOT NULL)")
                database.execSQL("INSERT OR IGNORE INTO coordination VALUES (1,?,?,?,?,0,0,NULL,NULL,0)", arrayOf(nodeId, homeId, encoded, preferred))
                database.execSQL("CREATE TABLE IF NOT EXISTS occurrence_claims(run_id TEXT PRIMARY KEY,payload_digest TEXT NOT NULL)")
                database.version = 1
            }
            database.rawQuery("SELECT node,home,membership,preferred,term,vote_term,vote_candidate,vote_request,vote_lease FROM coordination WHERE singleton=1", null).use { row ->
                requireCoord(row.moveToFirst(), "coordinator_storage_invalid")
                requireCoord(row.getString(0) == nodeId && row.getString(1) == homeId && row.getString(2) == encoded && row.getString(3) == preferred, "coordinator_configuration_mismatch")
                requireCoord(row.getType(4) == android.database.Cursor.FIELD_TYPE_INTEGER && row.getType(5) == android.database.Cursor.FIELD_TYPE_INTEGER, "coordinator_storage_invalid")
                term = row.getLong(4); voteTerm = row.getLong(5)
                voteCandidate = row.getString(6); voteRequest = row.getString(7); voteLease = row.getDouble(8)
                requireCoord(term in 0..MAX_TERM && voteTerm in 0..term, "coordinator_storage_invalid")
                if (voteTerm > 0) {
                    id(voteCandidate); id(voteRequest); duration(voteLease)
                    requireCoord(voteCandidate in this.voters, "coordinator_storage_invalid")
                } else requireCoord(voteCandidate == null && voteRequest == null && voteLease == 0.0, "coordinator_storage_invalid")
            }
            // Wait AFTER storage ownership and load, on EVERY construction.
            lastNow = elapsed(); restartUntil = lastNow + 32.0
        } catch (failure: Exception) {
            close()
            if (failure is HomeV2Exception) throw failure
            throw HomeV2Exception("coordinator_storage_unavailable")
        }
    }

    private fun elapsed() = SystemClock.elapsedRealtime() / 1000.0 // includes deep sleep
    private fun now(): Double {
        requireCoord(db != null && !failed, "coordinator_unavailable")
        val value = elapsed()
        if (value < lastNow) { failed = true; round = null; throw HomeV2Exception("coordinator_clock_invalid") }
        lastNow = value
        return value
    }
    private fun <T> transaction(block: () -> T): T {
        val database = db ?: throw HomeV2Exception("coordinator_unavailable")
        database.beginTransaction()
        try { val result = block(); database.setTransactionSuccessful(); return result }
        finally { database.endTransaction() }
    }
    private fun persist(t: Long, vt: Long, candidate: String?, request: String?, lease: Double) {
        try {
            transaction {
                db?.execSQL("UPDATE coordination SET term=?,vote_term=?,vote_candidate=?,vote_request=?,vote_lease=? WHERE singleton=1", arrayOf(t, vt, candidate, request, lease))
            }
        } catch (_: Exception) {
            failed = true; round = null; throw HomeV2Exception("coordinator_storage_unavailable")
        }
        term = t; voteTerm = vt; voteCandidate = candidate; voteRequest = request; voteLease = lease
    }
    private fun sender(value: String) { id(value); requireCoord(value in voters, "permission_denied") }
    private fun stablePreferred(value: Double): Boolean {
        val seen = preferredSeen ?: return false
        val since = preferredSince ?: return false
        return preferred != null && preferred != nodeId && value - seen <= 3.0 && value - since >= 10.0
    }

    /** Inbound allowlist only; from comes from authenticated channel context. */
    suspend fun handle(operation: String, args: JSONObject, from: String): JSONObject = withContext(Dispatchers.IO) {
        synchronized(serial) {
            now(); sender(from)
            val copy = JSONObject(args.toString())
            when (operation) {
                "coord.claim" -> {
                    fields(copy,setOf("membership","run_id","payload_digest"))
                    val run = copy.getString("run_id"); val payload = copy.getString("payload_digest")
                    requireCoord(copy.getString("membership") == membership && Regex("[0-9a-f]{32}").matches(run)
                        && Regex("[0-9a-f]{64}").matches(payload))
                    val accepted = transaction {
                        val database = db ?: throw HomeV2Exception("coordinator_unavailable")
                        database.execSQL("INSERT OR IGNORE INTO occurrence_claims VALUES (?,?)",arrayOf(run,payload))
                        database.rawQuery("SELECT payload_digest FROM occurrence_claims WHERE run_id=?",arrayOf(run)).use { row ->
                            requireCoord(row.moveToFirst(),"coordinator_storage_unavailable"); row.getString(0) == payload
                        }
                    }
                    JSONObject().put("membership",membership).put("run_id",run).put("payload_digest",payload).put("accepted",accepted)
                }
                "coord.vote" -> vote(copy, from)
                "coord.status" -> { fields(copy, emptySet()); statusLocked() }
                "coord.preferred" -> {
                    fields(copy, emptySet()); requireCoord(from == preferred, "permission_denied")
                    val value = now()
                    if (preferredSeen == null || value - (preferredSeen ?: value) > 3.0) preferredSince = value
                    preferredSeen = value
                    JSONObject().put("observed", true)
                }
                else -> throw HomeV2Exception("unsupported_operation")
            }
        }
    }

    private fun vote(args: JSONObject, from: String): JSONObject {
        fields(args, VOTE_FIELDS)
        val requestedTerm = positiveTerm(args.opt("term"))
        val candidate = id(args.opt("candidate")); val request = id(args.opt("request_id"))
        val lease = duration(args.opt("lease_seconds"))
        requireCoord(candidate == from && args.opt("membership") == membership, "permission_denied")
        val value = now()
        if (requestedTerm > term) { persist(requestedTerm, voteTerm, voteCandidate, voteRequest, voteLease); round = null }
        var granted = false
        var reason = "stale_term"
        if (requestedTerm == term) {
            when {
                value < restartUntil -> reason = "restart_fence"
                requestedTerm == voteTerm -> {
                    granted = candidate == voteCandidate && request == voteRequest && lease == voteLease && value < grantDeadline
                    reason = if (granted) "duplicate" else "already_voted"
                }
                value < grantDeadline -> reason = "lease_active"
                else -> {
                    val deadline = value + lease
                    persist(term, term, candidate, request, lease)
                    grantDeadline = deadline; granted = true; reason = "granted"
                }
            }
        }
        val remaining = if (granted) maxOf(0.0, grantDeadline - now()) else 0.0
        if (remaining == 0.0) granted = false
        return JSONObject().put("membership", membership).put("term", term).put("request_term", requestedTerm)
            .put("candidate", candidate).put("request_id", request).put("lease_seconds", lease)
            .put("granted", granted).put("remaining_seconds", remaining).put("reason", reason)
    }

    /** Trusted LOCAL action; never expose as RPC. Retransmit the same args. */
    fun begin(leaseSeconds: Double = 30.0): JSONObject = synchronized(serial) {
        val lease = duration(leaseSeconds); val value = now()
        requireCoord(voters.isNotEmpty(), "coordinator_disabled")
        requireCoord(value >= restartUntil, "coordinator_restart_fence")
        requireCoord(!stablePreferred(value), "coordinator_preferred_handover")
        requireCoord(round?.let { value < it.deadline } != true && value >= grantDeadline, "coordinator_busy")
        requireCoord(term < MAX_TERM, "coordinator_term_exhausted")
        persist(term + 1, voteTerm, voteCandidate, voteRequest, voteLease)
        val args = JSONObject().put("membership", membership).put("term", term).put("candidate", nodeId)
            .put("request_id", UUID.randomUUID().toString().replace("-", "")).put("lease_seconds", lease)
        round = Round(JSONObject(args.toString()), value + lease - 2.0, mutableSetOf())
        recordLocked(nodeId, vote(args, nodeId))
        JSONObject(args.toString())
    }

    /** Trusted outbound reply hook ONLY, authenticated identity + request correlation. */
    fun recordVoteReply(from: String, reply: JSONObject): Boolean = synchronized(serial) { recordLocked(from, reply) }
    private fun recordLocked(from: String, reply: JSONObject): Boolean {
        val value = now(); sender(from); fields(reply, REPLY_FIELDS)
        val replyTerm = positiveTerm(reply.opt("term")); val requestedTerm = positiveTerm(reply.opt("request_term"))
        id(reply.opt("candidate")); id(reply.opt("request_id"))
        val lease = duration(reply.opt("lease_seconds"))
        val remaining = number(reply.opt("remaining_seconds"))
        val granted = reply.opt("granted")
        requireCoord(granted is Boolean && remaining in 0.0..lease && reply.opt("reason") in REASONS
            && reply.opt("membership") == membership && replyTerm >= requestedTerm)
        requireCoord(if (granted == true) replyTerm == requestedTerm && remaining > 0 else remaining == 0.0)
        val active = round ?: return false
        val args = active.args
        if (requestedTerm != args.getLong("term") || reply.opt("candidate") != nodeId || reply.opt("request_id") != args.opt("request_id") || lease != args.getDouble("lease_seconds")) return false
        if (replyTerm > term) { persist(replyTerm, voteTerm, voteCandidate, voteRequest, voteLease); round = null; return false }
        if (value >= active.deadline || granted != true) return false
        active.votes.add(from)
        return active.votes.size >= majority
    }

    fun canSchedule(): Boolean = synchronized(serial) { canScheduleLocked(now()) }
    private fun canScheduleLocked(value: Double): Boolean = voters.isNotEmpty() && value >= restartUntil &&
        round?.let { it.args.getLong("term") == term && value < it.deadline && it.votes.size >= majority } == true
    fun status(): JSONObject = synchronized(serial) { statusLocked() }
    private fun statusLocked(): JSONObject {
        val value = now(); val leader = canScheduleLocked(value)
        return JSONObject().put("node_id", nodeId).put("enabled", voters.isNotEmpty()).put("membership", membership)
            .put("voters", JSONArray(voters)).put("majority", majority).put("term", term).put("vote_term", voteTerm)
            .put("voted_for", voteCandidate ?: JSONObject.NULL).put("restart_fence_seconds", maxOf(0.0, restartUntil - value))
            .put("grant_remaining_seconds", maxOf(0.0, grantDeadline - value)).put("can_schedule", leader)
            .put("lease_remaining_seconds", if (leader) maxOf(0.0, (round?.deadline ?: value) - value) else 0.0)
            .put("collected_votes", round?.votes?.size ?: 0).put("preferred", preferred ?: JSONObject.NULL)
            .put("preferred_stable", stablePreferred(value))
    }

    /** One async pump iteration. peers are routable IDs, NOT voters.
     * request must unwrap transport success/error and bind the authenticated peer
     * and exact outstanding request. A claimed status/quorum never grants a vote.
     * No scheduler dispatch is performed. Call periodically (~1 second).
     */
    suspend fun tick(peers: Collection<String>, request: suspend (String, String, JSONObject) -> JSONObject): JSONObject = withContext(Dispatchers.IO) {
        pump.withLock {
            val available = peers.toSet().intersect(voters.toSet()) - nodeId
            val errors = mutableMapOf<String, String>()
            val errorsLock = Any()
            suspend fun call(peer: String, op: String, args: JSONObject) {
                try {
                    val reply = withTimeout(1500L) { request(peer, op, JSONObject(args.toString())) }
                    if (op == "coord.vote") recordVoteReply(peer, reply)
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    synchronized(errorsLock) { errors[peer] = "peer_unavailable" }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    synchronized(errorsLock) { errors[peer] = (failure as? HomeV2Exception)?.code ?: "peer_unavailable" }
                }
            }
            val value = synchronized(serial) { now() }
            if (nodeId == preferred && value >= heartbeatDue) {
                heartbeatDue = value + 1.0
                coroutineScope { available.map { async { call(it, "coord.preferred", JSONObject()) } }.awaitAll() }
            }
            val snapshot = status()
            if (snapshot.getDouble("grant_remaining_seconds") <= 0 && snapshot.getDouble("lease_remaining_seconds") <= 0 && value >= campaignDue) {
                pending = null; campaignDue = value + Random.nextDouble(1.0, 3.0)
                try { pending = begin() }
                catch (failure: HomeV2Exception) { if (failure.code !in EXPECTED_BEGIN) throw failure }
            }
            val args = pending
            if (args != null) coroutineScope { available.map { async { call(it, "coord.vote", args) } }.awaitAll() }
            JSONObject().put("coordination", status()).put("peer_errors", JSONObject(errors.toMap()))
                .put("automatic_execution_enabled", false)
        }
    }

    /** Stop/await the caller-owned pump before close. Never revokes voter grants. */
    override fun close(): Unit = synchronized(serial) {
        round = null
        try { db?.close() }
        finally {
            db = null
            try { ownerLock?.release() }
            finally { ownerLock = null; owner?.close(); owner = null }
        }
    }

    private companion object {
        const val MAX_TERM = 9223372036854775806L
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        val VOTE_FIELDS = setOf("membership", "term", "candidate", "request_id", "lease_seconds")
        val REPLY_FIELDS = setOf("membership", "term", "request_term", "candidate", "request_id", "lease_seconds", "granted", "remaining_seconds", "reason")
        val REASONS = setOf("stale_term", "restart_fence", "duplicate", "already_voted", "lease_active", "granted")
        val EXPECTED_BEGIN = setOf("coordinator_disabled", "coordinator_restart_fence", "coordinator_preferred_handover", "coordinator_busy")
        fun requireCoord(ok: Boolean, code: String = "invalid_arguments") { if (!ok) throw HomeV2Exception(code) }
        fun id(value: Any?): String { requireCoord(value is String && ID.matches(value), "invalid_identifier"); return value as String }
        fun fields(value: JSONObject, names: Set<String>) { requireCoord(value.keys().asSequence().toSet() == names) }
        fun positiveTerm(value: Any?): Long {
            requireCoord(value is Int || value is Long)
            val result = (value as Number).toLong(); requireCoord(result in 1..MAX_TERM); return result
        }
        fun number(value: Any?): Double {
            requireCoord(value is Int || value is Long || value is Float || value is Double)
            val result = (value as Number).toDouble(); requireCoord(result.isFinite()); return result
        }
        fun duration(value: Any?): Double { val result = number(value); requireCoord(result > 2.0 && result <= 30.0); return result }
        fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
