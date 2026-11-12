package org.amalia.unifiedhome.v2

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.my.amali.plugin.HostServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest

/** Privileged encrypted adapter only. Persistent receipts prevent duplicate speech/agent turns.
 * No invitation handling, permission requests or model-facing raw credential operations.
 * Submissions never wait for the host; interrupted tasks are never replayed.
 */
class HomeV2Assistant(context: Context, private val host: HostServices,
    private val home: String, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) : Closeable {
    private val serial = Mutex()
    private val shutdown = Mutex()
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val tasks = CoroutineScope(scope.coroutineContext + supervisor + Dispatchers.IO)
    private var stopping = false
    private var closed = false
    private val db: SQLiteDatabase
    val operations = setOf("assistant.respond", "assistant.speak", "assistant.listen", "assistant.task.get")

    init {
        v2Id(home)
        val file = java.io.File(context.noBackupFilesDir, "home-v3-assistant-$home.sqlite3")
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA synchronous=FULL")
            db.execSQL("CREATE TABLE IF NOT EXISTS receipts (sender TEXT,id TEXT,operation TEXT,digest TEXT,state TEXT,result TEXT,error_code TEXT,PRIMARY KEY(sender,id))")
            val hasErrorCode = db.rawQuery("PRAGMA table_info(receipts)", null).use { c ->
                var found = false
                while (c.moveToNext()) { if (c.getString(1) == "error_code") found = true }
                found
            }
            if (!hasErrorCode) db.execSQL("ALTER TABLE receipts ADD COLUMN error_code TEXT")
            recoverInterrupted()
        } catch (failure: Exception) {
            supervisor.cancel()
            db.close()
            throw failure
        }
    }

    suspend fun handle(operation: String, args: JSONObject, from: String): JSONObject = withContext(Dispatchers.IO) {
        v2Require(V2_HEX.matches(from), "invalid_sender")
        v2Require(operation in operations, "unsupported_operation")
        val lookup = operation == "assistant.task.get"
        val fields = if (lookup || operation == "assistant.listen") setOf("request_id") else setOf("request_id", "text")
        v2Require(args.keys().asSequence().all { it in fields }, "unexpected_argument")
        val id = args.opt("request_id") as? String ?: throw V2Failure("invalid_request_id")
        v2Require(V2_HEX.matches(id), "invalid_request_id")
        val text = if ("text" in fields) {
            val value = args.opt("text") as? String ?: throw V2Failure("invalid_text")
            v2Require(value.isNotBlank(), "empty_text")
            v2Require(value.length <= 8000, "text_too_large")
            // Reject invalid Unicode before it can reach the host or change the digest on encoding.
            v2Require(validUnicode(value), "invalid_text")
            value
        } else ""
        val hash = if (lookup) "" else digest(operation + "\n" + text)
        serial.withLock {
            v2Require(!stopping && !closed, "assistant_closed")
            db.rawQuery("SELECT operation,digest,state,result,error_code FROM receipts WHERE sender=? AND id=?", arrayOf(from, id)).use { c ->
                if (c.moveToFirst()) {
                    if (!lookup) v2Require(c.getString(0) == operation && c.getString(1) == hash, "task_payload_conflict")
                    return@withLock snapshot(id, c.getString(2), c.getString(3), c.getString(4))
                }
            }
            v2Require(!lookup, "task_not_found")
            v2Require(supervisor.isActive, "assistant_closed")
            db.insertOrThrow("receipts", null, ContentValues().apply {
                put("sender", from); put("id", id); put("operation", operation); put("digest", hash); put("state", "queued")
            })
            // Enter the cancellation handler before releasing serial, even if the parent is cancelled
            // before dispatch. The worker must acquire serial before marking running or calling host.
            tasks.launch(start = CoroutineStart.UNDISPATCHED) { execute(from, id, operation, text) }
            snapshot(id, "queued", null, null)
        }
    }

    private suspend fun execute(from: String, id: String, operation: String, text: String) {
        try {
            val started = serial.withLock {
                if (stopping) false else db.update("receipts", ContentValues().apply { put("state", "running") },
                    "sender=? AND id=? AND state='queued'", arrayOf(from, id)) == 1
            }
            if (!started) return
            currentCoroutineContext().ensureActive()
            // Keep the existing host deduplication namespace, including across upgrades/restarts.
            val hostId = digest("$home:$from:$id")
            val result: JSONObject
            val state: String
            try {
                result = when (operation) {
                    "assistant.respond" -> {
                        val response = host.homeRespond(hostId, text, "{}")
                        if (response.length > MAX_RESULT_BYTES || !validUnicode(response)) {
                            complete(from, id, "unknown", null,
                                if (response.length > MAX_RESULT_BYTES) "result_too_large" else "invalid_host_result")
                            return
                        }
                        JSONObject().put("text", response)
                    }
                    "assistant.speak" -> JSONObject().put("spoken", host.homeSpeak(hostId, text))
                    else -> JSONObject().put("listening_requested", host.homeListen(hostId))
                }
                state = when {
                    // False conflates unavailable, busy, duplicate and failed: never claim success.
                    operation == "assistant.speak" && !result.getBoolean("spoken") -> "unknown"
                    operation == "assistant.listen" -> if (result.getBoolean("listening_requested")) "accepted" else "error"
                    else -> "succeeded"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Fixed codes only: no exception messages, class names, credentials or peer input.
                val code = when (failure) {
                    is SecurityException -> "permission_denied"
                    is UnsupportedOperationException -> "host_unavailable"
                    else -> "host_failure"
                }
                complete(from, id, "unknown", null, code)
                return
            }
            currentCoroutineContext().ensureActive()
            val encoded = result.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
                complete(from, id, "unknown", null, "result_too_large")
            } else {
                complete(from, id, state, encoded, null)
            }
        } finally {
            // A queued job never reached the host; a running job may have produced an effect.
            // Also handles cancellation during persistence; never replay uncertain speech.
            withContext(NonCancellable) {
                serial.withLock {
                    db.execSQL("UPDATE receipts SET state=CASE WHEN state='queued' THEN 'cancelled' ELSE 'unknown' END, result=NULL, error_code=NULL WHERE sender=? AND id=? AND state IN ('queued','running')",
                        arrayOf(from, id))
                }
            }
        }
    }

    private suspend fun complete(from: String, id: String, state: String, result: String?, error: String?) {
        serial.withLock {
            db.update("receipts", ContentValues().apply {
                put("state", state); put("result", result); put("error_code", error)
            }, "sender=? AND id=? AND state='running'", arrayOf(from, id))
        }
    }

    private fun snapshot(id: String, state: String, result: String?, error: String?) = JSONObject()
        .put("request_id", id).put("state", state)
        .put("result", result?.let(::JSONObject) ?: JSONObject.NULL)
        .put("error_code", error ?: JSONObject.NULL)

    /** Called only during initialization or under serial after all workers have exited. */
    private fun recoverInterrupted() {
        db.execSQL("UPDATE receipts SET state=CASE WHEN state='queued' THEN 'cancelled' ELSE 'unknown' END, result=NULL, error_code=NULL WHERE state IN ('queued','running')")
    }

    suspend fun closeAndWait() = withContext(NonCancellable + Dispatchers.IO) {
        shutdown.withLock {
            if (!closed) {
                serial.withLock { stopping = true }
                // Never join with serial held: each worker persists its terminal state in finally.
                supervisor.cancelAndJoin()
                serial.withLock {
                    recoverInterrupted()
                    db.close()
                    closed = true
                }
            }
        }
    }

    /** Closeable callers get the same cancellation/join/storage ordering as suspend callers. */
    override fun close() { runBlocking { closeAndWait() } }

    private companion object {
        const val MAX_RESULT_BYTES = 32768
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun validUnicode(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val unit = value[index++]
                if (unit.isHighSurrogate()) {
                    if (index == value.length || !value[index++].isLowSurrogate()) return false
                } else if (unit.isLowSurrogate()) return false
            }
            return true
        }
    }
}
