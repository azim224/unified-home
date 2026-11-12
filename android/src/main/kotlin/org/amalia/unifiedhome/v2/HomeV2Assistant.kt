package org.amalia.unifiedhome.v2

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.my.amali.plugin.HostServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest

/** Privileged encrypted adapter only. Persistent receipts prevent duplicate speech/agent turns.
 * No invitation handling, permission requests or model-facing raw credential operations.
 */
class HomeV2Assistant(context: Context, private val host: HostServices,
    private val home: String) : Closeable {
    private val serial = Mutex()
    private val db: SQLiteDatabase
    val operations = setOf("assistant.respond", "assistant.speak", "assistant.listen")
    init {
        v2Id(home)
        val file = java.io.File(context.noBackupFilesDir, "home-v3-assistant-$home.sqlite3")
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS receipts (sender TEXT,id TEXT,operation TEXT,digest TEXT,state TEXT,result TEXT,PRIMARY KEY(sender,id))")
        db.execSQL("UPDATE receipts SET state='unknown' WHERE state='running'")
    }
    suspend fun handle(operation: String, args: JSONObject, from: String): JSONObject = withContext(Dispatchers.IO) {
        serial.withLock {
            v2Id(from); v2Require(operation in operations, "unsupported_operation")
            val fields = if (operation == "assistant.listen") setOf("request_id") else setOf("request_id", "text")
            v2Keys(args, fields)
            val id = v2Id(v2String(args, "request_id"))
            val text = if (operation == "assistant.listen") "" else v2String(args, "text")
            v2Require(text.length <= 8000 && (operation == "assistant.listen" || text.isNotBlank()))
            val hash = MessageDigest.getInstance("SHA-256").digest((operation + "\n" + text).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            db.rawQuery("SELECT operation,digest,state,result FROM receipts WHERE sender=? AND id=?", arrayOf(from,id)).use { c ->
                if (c.moveToFirst()) {
                    v2Require(c.getString(0) == operation && c.getString(1) == hash, "task_payload_conflict")
                    return@withLock snapshot(id, c.getString(2), c.getString(3))
                }
            }
            db.insertOrThrow("receipts", null, ContentValues().apply {
                put("sender", from); put("id", id); put("operation", operation); put("digest", hash); put("state", "running")
            })
            val hostId = MessageDigest.getInstance("SHA-256").digest("$home:$from:$id".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            try {
                val result = when (operation) {
                    "assistant.respond" -> JSONObject().put("text", host.homeRespond(hostId, text, "{}"))
                    "assistant.speak" -> JSONObject().put("spoken", host.homeSpeak(hostId, text))
                    else -> JSONObject().put("listening_requested", host.homeListen(hostId))
                }
                val state = when {
                    operation == "assistant.speak" && !result.getBoolean("spoken") -> "unknown"
                    operation == "assistant.listen" -> if (result.getBoolean("listening_requested")) "accepted" else "error"
                    else -> "succeeded"
                }
                if (result.toString().toByteArray(Charsets.UTF_8).size > 32768) {
                    finish(from,id,"unknown",null); snapshot(id,"unknown",null)
                } else {
                    finish(from,id,state,result.toString()); snapshot(id,state,result.toString())
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { finish(from,id,"unknown",null) }; throw cancelled
            } catch (_: Exception) {
                finish(from,id,"unknown",null); snapshot(id,"unknown",null)
            }
        }
    }
    private fun snapshot(id: String,state: String,result: String?) = JSONObject().put("request_id",id)
        .put("state",state).put("result",result?.let(::JSONObject) ?: JSONObject.NULL)
    private fun finish(from: String,id: String,state: String,result: String?) {
        db.update("receipts", ContentValues().apply { put("state",state); put("result",result) },
            "sender=? AND id=? AND state='running'",arrayOf(from,id))
    }
    suspend fun closeAndWait() = withContext(Dispatchers.IO) { serial.withLock { close() } }
    override fun close() { db.close() }
}
