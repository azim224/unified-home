package org.amalia.unifiedhome.v2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.my.amali.data.ai.ModelCatalog
import com.my.amali.plugin.HostServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Privileged runtime RPC extension, NOT a model/tool dispatcher. See HOME_V2_DATA.md. */
class HomeV2Data(
    context: Context,
    private val host: HostServices,
    val nodeId: String,
    val homeId: String,
) : Closeable {
    private val serial = Mutex()
    private val lifecycle = Any()
    private var closed = false
    private var inFlight = false
    private val db: SQLiteDatabase
    private val secrets: ProfileCipher
    private var settingsAdapter: SettingsAdapter? = null

    /** Separate host contract: installation explicitly attests NEW membership-trust authorization.
     * Do not wrap the old LOCAL HUMAN ONLY methods without an authorized host contract change.
     * Import must be an atomic, validated settings patch, throwing on failure. */
    interface SettingsAdapter {
        suspend fun exportSettings(): JSONObject
        suspend fun importSettings(patch: JSONObject)
        suspend fun exportProviderProfile(): JSONObject
        suspend fun applyProviderProfile(profile: JSONObject)
        suspend fun restoreProviderProfile(): Boolean
    }

    /** Call while stopped, before registering the extension. Null keeps apply/restore disabled. */
    fun installSettingsAdapter(adapter: SettingsAdapter?) = synchronized(lifecycle) {
        checkOpen(); require(!inFlight) { "data_busy" }; settingsAdapter = adapter
    }

    val operations: Set<String> get() = synchronized(lifecycle) {
        BASE_OPERATIONS + if (settingsAdapter != null) setOf("profiles.apply", "profiles.restore", "profiles.capture") else emptySet()
    }

    init {
        hex(nodeId); hex(homeId)
        val partition = "home-v2-data-$nodeId-$homeId"
        secrets = ProfileCipher(partition)
        var opened: SQLiteDatabase? = null
        try {
            val directory = File(context.applicationContext.noBackupFilesDir.canonicalFile, partition)
            requireData(directory.canonicalFile == directory.absoluteFile, "unsafe_storage_path")
            requireData(directory.isDirectory || directory.mkdirs(), "storage_unavailable")
            val file = File(directory, "data.sqlite3")
            requireData(file.canonicalFile == file.absoluteFile, "unsafe_storage_path")
            opened = SQLiteDatabase.openOrCreateDatabase(file, null)
            db = opened
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                check(cursor.moveToFirst()) { "Missing SQLite pragma result" }
                check(cursor.getString(0).equals("delete", ignoreCase = true)) { "Unexpected journal mode" }
            }
            db.execSQL("PRAGMA synchronous=FULL")
            db.execSQL("PRAGMA temp_store=MEMORY")
            db.rawQuery("PRAGMA busy_timeout=5000", null).use { cursor ->
                check(cursor.moveToFirst()) { "Missing SQLite pragma result" }
                check(cursor.getLong(0) == 5000L) { "Unexpected busy timeout" }
            }
            requireData(db.version in 0..1, "unsupported_data_version")
            transaction {
                db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS records (scope TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(scope,kind,id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS changes (seq INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, body TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS changes_scope ON changes(scope,seq)")
                db.execSQL("CREATE TABLE IF NOT EXISTS profiles (id TEXT NOT NULL, version INTEGER NOT NULL, provider TEXT NOT NULL, label TEXT NOT NULL, secret TEXT NOT NULL, PRIMARY KEY(id,version))")
                for ((key, value) in mapOf("node" to nodeId, "home" to homeId, "clock" to "0", "cursor_key" to randomKey(), "selection" to emptySelection().toString(), "pending" to "", "backup" to "")) {
                    db.execSQL("INSERT OR IGNORE INTO meta VALUES (?,?)", arrayOf(key, value))
                }
                requireData(meta("node") == nodeId && meta("home") == homeId, "partition_mismatch")
                db.version = 1
            }
        } catch (failure: Exception) {
            opened?.close()
            if (failure is HomeV2Exception) throw failure
            // Log the exception type only: SQLite messages can contain private paths or data.
            android.util.Log.e("UnifiedHome", "Home data initialization failed: ${failure.javaClass.name}")
            throw HomeV2Exception("storage_unavailable")
        }
    }

    /** from is authenticated adapter context (hex32), never a field taken from args.
     * The caller MUST fence this instance to the Node's current home membership. */
    suspend fun handle(op: String, args: JSONObject, from: String): JSONObject = withContext(Dispatchers.IO) {
        serial.withLock {
            synchronized(lifecycle) { checkOpen(); requireData(!inFlight, "data_busy"); inFlight = true }
            try {
                hex(from)
                val copy = JSONObject(encode(args, 65536, if (op == "data.merge") 16 else 12))
                when (op) {
                    "context.search" -> search(copy)
                    "profiles.apply", "profiles.restore" -> applyProfile(op, copy)
                    "profiles.capture" -> captureProfile(copy)
                    else -> transaction {
                        when (op) {
                            "data.get" -> get(copy)
                            "data.put", "data.delete" -> write(copy, op == "data.delete")
                            "data.list" -> list(copy)
                            "data.changes" -> changes(copy)
                            "data.merge" -> merge(copy)
                            "profiles.import" -> importProfile(copy)
                            "profiles.export" -> exportProfile(copy)
                            "profiles.list" -> listProfiles(copy)
                            else -> throw HomeV2Exception("unsupported_operation")
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: HomeV2Exception) { throw failure }
            catch (_: Exception) { throw HomeV2Exception("storage_unavailable") }
            finally { synchronized(lifecycle) { inFlight = false } }
        }
    }

    suspend fun closeAndWait() = withContext(Dispatchers.IO) {
        serial.withLock { close() }
    }

    override fun close() = synchronized(lifecycle) {
        if (!closed) { requireData(!inFlight, "data_busy"); db.close(); closed = true }
    }
    private fun checkOpen() = requireData(!closed, "data_closed")
    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    private fun meta(key: String): String = db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use {
        requireData(it.moveToFirst(), "storage_unavailable"); it.getString(0)
    }
    private fun setMeta(key: String, value: String) { db.execSQL("UPDATE meta SET v=? WHERE k=?", arrayOf(value, key)) }
    private fun number(sql: String, vararg values: String): Long = db.rawQuery(sql, values).use { it.moveToFirst(); it.getLong(0) }
    private fun revision(scope: String) = number("SELECT COALESCE(MAX(seq),0) FROM changes WHERE scope=?", scope)
    private fun row(scope: String, kind: String, id: String): JSONObject? = db.rawQuery(
        "SELECT body FROM records WHERE scope=? AND kind=? AND id=?", arrayOf(scope, kind, id)
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    private fun persist(record: JSONObject): Long {
        val body = encode(record, 16384)
        db.execSQL("INSERT OR REPLACE INTO records VALUES (?,?,?,?)", arrayOf(record.getString("scope"), record.getString("kind"), record.getString("id"), body))
        db.execSQL("INSERT INTO changes(scope,body) VALUES (?,?)", arrayOf(record.getString("scope"), body))
        return number("SELECT last_insert_rowid()")
    }
    private fun scope(a: JSONObject): String = text(a.opt("scope"), 5).also { requireData(it in setOf("home", "local"), "invalid_scope") }
    private fun kind(a: JSONObject): String = text(a.opt("kind"), 16).also { requireData(it in KINDS, "invalid_kind") }
    private fun get(a: JSONObject): JSONObject {
        fields(a, setOf("scope", "kind", "id"))
        return JSONObject().put("record", row(scope(a), kind(a), identifier(a.opt("id"))) ?: throw HomeV2Exception("record_not_found"))
    }
    private fun write(a: JSONObject, deleted: Boolean): JSONObject {
        fields(a, setOf("scope", "kind", "id", "expected_revision") + if (deleted) emptySet() else setOf("value"))
        val s = scope(a); val k = kind(a); val id = identifier(a.opt("id")); val current = row(s, k, id)
        val expected = a.opt("expected_revision")
        val matches = if (expected is JSONObject) {
            fields(expected, setOf("revision", "origin"))
            val r = integer(expected.opt("revision"), 1); val o = hex(expected.opt("origin"))
            current != null && current.getLong("revision") == r && current.getString("origin") == o
        } else {
            requireData(integer(expected) == 0L, "invalid_arguments")
            current == null
        }
        requireData(matches, "revision_conflict")
        requireData(!deleted || current != null, "record_not_found")
        val value = if (deleted) JSONObject() else a.opt("value") as? JSONObject ?: throw HomeV2Exception("invalid_arguments")
        encode(value, 16384)
        val clock = increment(meta("clock").toLong())
        val record = JSONObject().put("home_id", if (s == "home") homeId else JSONObject.NULL).put("scope", s).put("kind", k).put("id", id)
            .put("revision", clock).put("origin", nodeId).put("tombstone", deleted).put("value", value)
        val seq = persist(record); setMeta("clock", clock.toString())
        return JSONObject().put("record", record).put("seq", seq)
    }
    private fun list(a: JSONObject): JSONObject {
        fields(a, setOf("scope", "kind"), setOf("limit", "cursor", "include_tombstones"))
        val s = scope(a); val k = kind(a); val limit = limit(a)
        val include = if (a.has("include_tombstones")) a.opt("include_tombstones") as? Boolean ?: throw HomeV2Exception("invalid_arguments") else false
        val rev = revision(s); val binding = JSONArray().put("data.list").put(s).put(k).put(include)
        val token = if (a.has("cursor")) cursor(a.opt("cursor"), binding) else null
        if (token != null) requireData(integer(token.opt("revision")) == rev, "stale_cursor")
        val after = if (token == null) "" else identifier(token.opt("after"))
        val records = JSONArray(); var used = 0; var more = false; var last = after
        db.rawQuery("SELECT id,body FROM records WHERE scope=? AND kind=? AND id>? ORDER BY id", arrayOf(s, k, after)).use { rows ->
            while (rows.moveToNext()) {
                val body = rows.getString(1); val record = JSONObject(body)
                if (!include && record.getBoolean("tombstone")) continue
                if (records.length() == limit || used + body.length > PAGE_BYTES) { more = true; break }
                records.put(record); used += body.length; last = rows.getString(0)
            }
        }
        return JSONObject().put("revision", rev).put("records", records).put("next_cursor", if (more) makeCursor(binding, rev, last) else JSONObject.NULL)
    }
    private fun changes(a: JSONObject): JSONObject {
        fields(a, setOf("scope"), setOf("after", "cursor", "limit")); requireData(!(a.has("after") && a.has("cursor")), "invalid_arguments")
        val s = scope(a); val binding = JSONArray().put("data.changes").put(s); val limit = limit(a)
        val token = if (a.has("cursor")) cursor(a.opt("cursor"), binding) else null
        val rev = if (token != null) integer(token.opt("revision")) else revision(s)
        val after = integer(token?.opt("after") ?: if (a.has("after")) a.opt("after") else 0)
        requireData(after <= rev && rev <= revision(s), "invalid_cursor")
        val items = JSONArray(); var used = 0; var more = false; var last = after
        db.rawQuery("SELECT seq,body FROM changes WHERE scope=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?", arrayOf(s, after.toString(), rev.toString(), (limit + 1).toString())).use { rows ->
            while (rows.moveToNext()) {
                val body = rows.getString(1)
                val item = JSONObject().put("seq", rows.getLong(0)).put("record", JSONObject(body))
                val size = encode(item, 18000).length
                if (items.length() == limit || used + size > PAGE_BYTES) { more = true; break }
                items.put(item); used += size; last = rows.getLong(0)
            }
        }
        return JSONObject().put("revision", rev).put("changes", items).put("after", if (more) last else rev)
            .put("next_cursor", if (more) makeCursor(binding, rev, last) else JSONObject.NULL)
    }
    private fun merge(a: JSONObject): JSONObject {
        fields(a, setOf("home_id", "records")); requireData(a.opt("home_id") == homeId, "partition_mismatch")
        val records = a.optJSONArray("records") ?: throw HomeV2Exception("invalid_arguments")
        requireData(records.length() in 1..100, "invalid_arguments")
        var accepted = 0; var ignored = 0; var clock = meta("clock").toLong()
        for (i in 0 until records.length()) {
            val r = records.opt(i) as? JSONObject ?: throw HomeV2Exception("invalid_arguments")
            fields(r, setOf("home_id", "scope", "kind", "id", "revision", "origin", "tombstone", "value"))
            requireData(r.opt("home_id") == homeId && r.opt("scope") == "home", "partition_mismatch")
            val k = kind(r); val id = identifier(r.opt("id")); val rev = integer(r.opt("revision"), 1); val origin = hex(r.opt("origin"))
            val tombstone = r.opt("tombstone") as? Boolean ?: throw HomeV2Exception("invalid_arguments")
            val value = r.optJSONObject("value") ?: throw HomeV2Exception("invalid_arguments")
            requireData(!tombstone || value.length() == 0, "invalid_tombstone"); encode(r, 16384)
            clock = maxOf(clock, rev)
            val current = row("home", k, id)
            val order = if (current == null) 1 else {
                val comparison = rev.compareTo(current.getLong("revision"))
                if (comparison != 0) comparison else origin.compareTo(current.getString("origin"))
            }
            if (order == 0) requireData(encode(r, 16384) == encode(current, 16384), "lamport_collision")
            if (order > 0) { persist(r); accepted++ } else ignored++
        }
        setMeta("clock", clock.toString())
        return JSONObject().put("accepted", accepted).put("ignored", ignored).put("revision", revision("home"))
    }

    private fun emptySelection() = JSONObject().put("node_id", nodeId).put("revision", 0).put("selected", JSONObject.NULL).put("previous", JSONObject.NULL)
    private fun selection() = JSONObject(meta("selection"))
    private fun profile(id: String, version: Long): JSONObject = db.rawQuery(
        "SELECT provider,label,secret FROM profiles WHERE id=? AND version=?", arrayOf(id, version.toString())
    ).use { rows ->
        requireData(rows.moveToFirst(), "profile_not_found")
        JSONObject().put("node_id", nodeId).put("id", id).put("version", version).put("provider", rows.getString(0)).put("label", rows.getString(1))
            .put("values", JSONObject(secrets.decrypt(rows.getString(2), "profile:$id:$version")))
    }
    private fun importProfile(a: JSONObject): JSONObject {
        fields(a, setOf("id", "provider", "label", "values", "expected_revision"))
        val id = identifier(a.opt("id")); val provider = identifier(a.opt("provider")); val label = text(a.opt("label"), 128)
        val values = a.optJSONObject("values") ?: throw HomeV2Exception("invalid_arguments")
        requireData(values.length() in 1..64, "invalid_arguments")
        values.keys().forEach { key -> identifier(key); text(values.opt(key), 8192, true) }
        val body = encode(values, 16384)
        val current = number("SELECT COALESCE(MAX(version),0) FROM profiles WHERE id=?", id)
        requireData(integer(a.opt("expected_revision")) == current, "revision_conflict")
        val version = increment(current)
        db.execSQL("INSERT INTO profiles VALUES (?,?,?,?,?)", arrayOf(id, version, provider, label, secrets.encrypt(body, "profile:$id:$version")))
        return JSONObject().put("node_id", nodeId).put("id", id).put("version", version).put("provider", provider).put("label", label)
    }
    private fun exportProfile(a: JSONObject): JSONObject {
        fields(a, setOf("id", "version")); return profile(identifier(a.opt("id")), integer(a.opt("version"), 1))
    }
    private fun listProfiles(a: JSONObject): JSONObject {
        fields(a, emptySet(), setOf("limit", "cursor")); val limit = limit(a); val selected = selection()
        val rev = number("SELECT COUNT(*) FROM profiles") + selected.getLong("revision")
        val binding = JSONArray().put("profiles.list").put(nodeId)
        val token = if (a.has("cursor")) cursor(a.opt("cursor"), binding) else null
        if (token != null) requireData(integer(token.opt("revision")) == rev, "stale_cursor")
        val after = token?.optJSONArray("after")
        if (token != null) requireData(after != null && after.length() == 2, "invalid_cursor")
        val id = if (after == null) "" else identifier(after.opt(0)); val version = if (after == null) 0L else integer(after.opt(1), 1)
        val items = JSONArray(); var more = false; var used = 0; var last: JSONArray? = null
        db.rawQuery("SELECT id,version,provider,label FROM profiles WHERE id>? OR (id=? AND version>?) ORDER BY id,version LIMIT ?", arrayOf(id, id, version.toString(), (limit + 1).toString())).use { rows ->
            while (rows.moveToNext()) {
                val item = JSONObject().put("id", rows.getString(0)).put("version", rows.getLong(1)).put("provider", rows.getString(2)).put("label", rows.getString(3))
                val size = encode(item, 4096).length
                if (items.length() == limit || used + size > PAGE_BYTES) { more = true; break }
                items.put(item); used += size
                last = JSONArray().put(rows.getString(0)).put(rows.getLong(1))
            }
        }
        return JSONObject().put("profiles", items).put("selection", selected).put("revision", rev).put("next_cursor", if (more) makeCursor(binding, rev, last) else JSONObject.NULL)
    }

    private suspend fun captureProfile(a: JSONObject): JSONObject {
        fields(a, setOf("id", "label", "expected_revision"))
        val adapter = settingsAdapter ?: throw HomeV2Exception("profile_apply_unavailable")
        val exported = adapter.exportProviderProfile()
        // Raw host preferences only: resolve public request defaults, never effective/build keys.
        val values = JSONObject(exported.getJSONObject("values").toString())
        if (exported.getString("provider") == "groq") {
            val model = ModelCatalog.resolveForRequest(ModelCatalog.Provider.GROQ, values.optString("chat_model"))
            values.put("chat_model", model).put("groq_model", model)
        }
        values.put("assemblyai_model", ModelCatalog.resolveForRequest(
            ModelCatalog.Provider.ASSEMBLY_AI, values.optString("assemblyai_model")))
        values.put("fish_model", ModelCatalog.resolveForRequest(
            ModelCatalog.Provider.FISH_AUDIO, values.optString("fish_model")))
        values.put("fish_reference_id", values.optString("fish_reference_id").trim()
            .ifBlank { "096d410e860346a7a73762d557a290d7" })
        val strings = JSONObject()
        values.keys().forEach { key -> strings.put(key, values.get(key).toString()) }
        return transaction { importProfile(JSONObject().put("id", a.getString("id"))
            .put("label", a.getString("label")).put("expected_revision", a.get("expected_revision"))
            .put("provider", exported.getString("provider")).put("values", strings)) }
    }

    private suspend fun applyProfile(op: String, a: JSONObject): JSONObject {
        fields(a, if (op == "profiles.apply") setOf("id", "version", "expected_revision") else setOf("expected_revision"))
        val adapter = settingsAdapter ?: throw HomeV2Exception("profile_apply_unavailable")
        val state = transaction {
            requireData(meta("pending").isEmpty(), "profile_apply_pending")
            selection().also { requireData(integer(a.opt("expected_revision")) == it.getLong("revision"), "revision_conflict") }
        }
        if (op == "profiles.restore") {
            val raw = transaction { meta("backup") }
            requireData(raw.isNotEmpty(),"no_previous_profile")
            val backup = JSONObject(secrets.decrypt(raw,"backup"))
            val next = JSONObject(state.toString()).put("revision",increment(state.getLong("revision")))
                .put("selected",state.opt("previous") ?: JSONObject.NULL).put("previous",JSONObject.NULL)
            val current = if (backup.has("schema")) adapter.exportProviderProfile() else adapter.exportSettings()
            transaction { setMeta("pending",secrets.encrypt(encode(JSONObject().put("before",state)
                .put("after",next).put("backup",current),65536),"pending")) }
            try {
                if (backup.has("schema")) adapter.applyProviderProfile(backup) else adapter.importSettings(backup)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw HomeV2Exception("profile_apply_unknown") }
            return transaction { setMeta("selection",next.toString());setMeta("pending", "");setMeta("backup", "");next }
        }
        val ref = if (op == "profiles.apply") JSONObject().put("id", identifier(a.opt("id"))).put("version", integer(a.opt("version"), 1))
            else state.optJSONObject("previous") ?: throw HomeV2Exception("no_previous_profile")
        val selected = state.optJSONObject("selected")
        if (selected != null && encode(selected, 1024) == encode(ref, 1024)) return state
        val values = transaction { profile(ref.getString("id"), ref.getLong("version")).getJSONObject("values") }
        if (values.keys().asSequence().any { it in COMMON_PROFILE_FIELDS || it in CHAT_ALIASES ||
                it in setOf("fish_speed", "assemblyai_rest_model") })
            return applyFullProfile(adapter, state, ref, values)
        val patch = nativePatch(values)
        // The host export is intentionally not exposed over RPC. Missing keys cannot be cleared by
        // the current patch-only host API, so refuse changes that cannot be backed up exactly.
        val exported = try { adapter.exportSettings() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw HomeV2Exception("profile_backup_failed") }
        validateNative(exported)
        val backup = JSONObject()
        patch.keys().forEach { key ->
            requireData(exported.has(key), "profile_backup_incomplete")
            backup.put(key, exported.get(key))
        }
        val next = JSONObject(state.toString()).put("revision", increment(state.getLong("revision"))).put("previous", state.opt("selected") ?: JSONObject.NULL).put("selected", ref)
        val pending = JSONObject().put("before", state).put("after", next).put("backup", backup)
        transaction {
            setMeta("backup", secrets.encrypt(encode(backup, 16384), "backup"))
            setMeta("pending", secrets.encrypt(encode(pending, 32768), "pending"))
        }
        // No SQLite transaction spans suspension. The durable pending marker survives cancellation,
        // host failure or a crash between provider commit and local selection commit.
        try { adapter.importSettings(JSONObject(patch.toString())) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw HomeV2Exception("profile_apply_unknown") }
        return transaction { setMeta("selection", next.toString()); setMeta("pending", ""); next }
    }

    /** LOCAL HUMAN recovery only: restores the saved patch atomically, then clears pending.
     * Never register this method as an RPC or model capability. */
    suspend fun rollbackPendingLocally() = withContext(Dispatchers.IO) {
        serial.withLock {
            synchronized(lifecycle) { checkOpen(); requireData(!inFlight, "data_busy"); inFlight = true }
            try {
                val adapter = settingsAdapter ?: throw HomeV2Exception("profile_apply_unavailable")
                val pending = transaction {
                    val raw = meta("pending"); requireData(raw.isNotEmpty(), "no_pending_profile")
                    JSONObject(secrets.decrypt(raw, "pending"))
                }
                try {
                    val backup = pending.getJSONObject("backup")
                    if (backup.has("schema")) adapter.applyProviderProfile(backup)
                    else adapter.importSettings(backup)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw HomeV2Exception("profile_apply_unknown") }
                transaction { setMeta("selection", pending.getJSONObject("before").toString()); setMeta("pending", "") }
            } finally { synchronized(lifecycle) { inFlight = false } }
        }
    }

    /** LOCAL HUMAN recovery only, after independently checking/reconciling host settings.
     * applied=true commits the intended selection; false keeps the old one. Never RPC/model. */
    suspend fun resolvePendingLocally(applied: Boolean) = withContext(Dispatchers.IO) {
        serial.withLock {
            synchronized(lifecycle) { checkOpen(); requireData(!inFlight, "data_busy"); inFlight = true }
            try { transaction {
                val raw = meta("pending"); requireData(raw.isNotEmpty(), "no_pending_profile")
                val pending = JSONObject(secrets.decrypt(raw, "pending"))
                setMeta("selection", pending.getJSONObject(if (applied) "after" else "before").toString())
                setMeta("pending", "")
            } } finally { synchronized(lifecycle) { inFlight = false } }
        }
    }

    private suspend fun applyFullProfile(adapter: SettingsAdapter, before: JSONObject,
        ref: JSONObject, stored: JSONObject): JSONObject {
        val current = adapter.exportProviderProfile()
        // Build a host-only envelope. PC-only fields stay in the encrypted stored version.
        // Portable chat fields are authoritative after PC edits; omit ALL raw chat aliases
        // so the host derives the active ones and leaves the disabled provider untouched.
        val values = JSONObject()
        stored.keys().forEach { key ->
            if (key in PROFILE_FIELDS && key !in CHAT_ALIASES) values.put(key, stored.get(key))
        }
        for (key in COMMON_PROFILE_FIELDS) {
            if (!values.has(key)) values.put(key, "")
        }
        if (stored.has("fish_speed")) values.put("tts_speed", stored.getString("fish_speed"))
        // Older PC profiles put REST speech_model in the streaming field. Preserve only
        // this non-secret Android setting; omitted credentials above are deliberately blank.
        if ((values.getString("assemblyai_model").isBlank() && stored.has("assemblyai_rest_model")) ||
            values.getString("assemblyai_model").trim().lowercase() in setOf("universal-2", "slam-1")) {
            values.put("assemblyai_model", current.getJSONObject("values").optString("assemblyai_model"))
        }
        // Stored values are strings on both platforms; restore the host's strict types.
        for (key in PROFILE_NUMBER_FIELDS) {
            if (values.has(key)) {
                val number = values.getString(key).toDoubleOrNull()
                requireData(number != null && number.isFinite(), "unsupported_profile_values")
                values.put(key, number)
            }
        }
        if (values.has("custom_json_mode")) {
            val value = values.getString("custom_json_mode")
            requireData(value == "true" || value == "false", "unsupported_profile_values")
            values.put("custom_json_mode", value == "true")
        }
        val base = values.getString("chat_base_url")
        val uri = if (base.isEmpty()) null else try {
            URI(if ("://" in base) base else "https://$base")
        } catch (_: java.net.URISyntaxException) { throw HomeV2Exception("unsupported_profile_values") }
        val provider = if (uri?.host.equals("api.groq.com", ignoreCase = true)) "groq" else "custom"
        if (base.isNotBlank()) {
            // Insert the suffix into the path, not after a query, and never duplicate it.
            val pathEnd = base.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) base.length else it }
            val endpoint = base.substring(0, pathEnd).trimEnd('/')
            values.put("chat_base_url", (if (uri?.path.orEmpty().trimEnd('/').endsWith("/chat/completions"))
                endpoint else "$endpoint/chat/completions") + base.substring(pathEnd))
        }
        val next = JSONObject(before.toString()).put("revision", increment(before.getLong("revision")))
            .put("previous", before.opt("selected") ?: JSONObject.NULL).put("selected", ref)
        val pending = JSONObject().put("before", before).put("after", next).put("backup", current)
        transaction {
            setMeta("backup", secrets.encrypt(encode(current, 32768), "backup"))
            setMeta("pending", secrets.encrypt(encode(pending, 65536), "pending"))
        }
        try { adapter.applyProviderProfile(JSONObject().put("schema",1).put("provider",provider).put("values",values)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw HomeV2Exception("profile_apply_unknown") }
        return transaction { setMeta("selection",next.toString()); setMeta("pending", ""); next }
    }

    private fun nativePatch(values: JSONObject): JSONObject {
        val patch = JSONObject()
        values.keys().forEach { key ->
            val value = values.getString(key)
            when (key) {
                in STRING_SETTINGS -> patch.put(key, value)
                in NUMBER_SETTINGS -> {
                    requireData(NUMBER.matches(value), "unsupported_profile_values")
                    patch.put(key, value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw HomeV2Exception("unsupported_profile_values"))
                }
                else -> throw HomeV2Exception("unsupported_profile_values")
            }
        }
        validateNative(patch); return patch
    }
    private fun validateNative(value: JSONObject) {
        requireData(value.length() in 1..10 && value.toString().toByteArray(Charsets.UTF_8).size <= 6000, "unsupported_profile_values")
        value.keys().forEach { key ->
            val v = value.opt(key)
            if (key in STRING_SETTINGS) requireData(v is String && v.isNotBlank() && v.trim() != "***" && v.length <= (if (key.endsWith("api_key")) 2048 else 256) && v.none { it.isISOControl() }, "unsupported_profile_values")
            else {
                requireData(v is Number, "unsupported_profile_values")
                val n = (v as Number).toDouble()
                requireData(n.isFinite() && when (key) {
                    "stt_silence_seconds" -> n in 1.8..6.0
                    "tts_speed" -> n in 0.5..2.0
                    "llm_temperature" -> n in 0.0..2.0
                    else -> false
                }, "unsupported_profile_values")
            }
        }
    }

    private suspend fun search(a: JSONObject): JSONObject {
        fields(a, setOf("query"), setOf("limit", "session_id", "cursor"))
        val query = text(a.opt("query"), 256, true); val count = limit(a)
        try {
            val page = V2Json.parse(host.homeHistory(a.toString()).toByteArray(Charsets.UTF_8),262144)
            if (page.has("error")) throw HomeV2Exception(page.getString("error"))
            return page
        } catch (_: UnsupportedOperationException) {
            // Old hosts remain explicit partial-summary sources, not full history.
        }
        requireData(!a.has("session_id") && !a.has("cursor"), "unsupported_context_filter")
        val raw = try { host.homeContext(query) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw HomeV2Exception("context_unavailable") }
        requireData(raw.toByteArray(Charsets.UTF_8).size <= 262144, "context_too_large")
        val source = V2Json.parse(raw.toByteArray(Charsets.UTF_8), 262144)
        // Preserve the host's real summary/metadata, do not fabricate PC history rows or timestamps.
        val result = JSONObject(); var truncated = false
        for (key in listOf("conversations", "device_tools")) {
            val array = source.optJSONArray(key) ?: continue
            val out = JSONArray(); var used = 0
            for (i in 0 until array.length()) {
                val item = array.get(i); val size = encode(item, 65536).length
                if (out.length() >= count || used + size > 18000) { truncated = true; break }
                out.put(item); used += size
            }
            result.put(key, out)
        }
        return JSONObject().put("context", result).put("next_cursor", JSONObject.NULL).put("partial", true).put("truncated", truncated)
            .put("capabilities", JSONObject().put("source", "host.homeContext").put("full_history", false).put("session_filter", false).put("pagination", false))
    }

    private fun makeCursor(binding: JSONArray, revision: Long, after: Any?): String {
        val bytes = encode(JSONObject().put("binding", binding).put("revision", revision).put("after", after), 4096).toByteArray(Charsets.US_ASCII)
        return b64(mac(bytes) + bytes)
    }
    private fun cursor(value: Any?, binding: JSONArray): JSONObject {
        try {
            val raw = Base64.decode(text(value, 4096), Base64.URL_SAFE or Base64.NO_WRAP)
            requireData(raw.size > 32, "invalid_cursor")
            val body = raw.copyOfRange(32, raw.size)
            requireData(MessageDigest.isEqual(raw.copyOfRange(0, 32), mac(body)), "invalid_cursor")
            val token = V2Json.parse(body, 4096); fields(token, setOf("binding", "revision", "after"))
            requireData(encode(token.opt("binding"), 4096) == encode(binding, 4096), "invalid_cursor")
            integer(token.opt("revision")); return token
        } catch (_: Exception) { throw HomeV2Exception("invalid_cursor") }
    }
    private fun mac(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(Base64.decode(meta("cursor_key"), Base64.DEFAULT), "HmacSHA256")); doFinal(bytes)
    }

    /** Keystore key never leaves Android. Every row/partition has authenticated associated data. */
    private class ProfileCipher(private val alias: String) {
        private fun key(create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            requireData(create, "profile_protection_unavailable")
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
        }
        fun encrypt(value: String, row: String): String = protect {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD("$alias:$row".toByteArray(Charsets.UTF_8))
            b64(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        }
        fun decrypt(value: String, row: String): String = protect {
            val bytes = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
            requireData(bytes.size >= 28, "profile_protection_failed")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD("$alias:$row".toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
        private fun <T> protect(block: () -> T): T = try { block() }
        catch (failure: HomeV2Exception) { throw failure }
        catch (_: Exception) { throw HomeV2Exception("profile_protection_failed") }
    }

    companion object {
        private const val MAX_INTEGER = Long.MAX_VALUE - 1
        private const val PAGE_BYTES = 48000
        private val KINDS = setOf("notes", "tags", "scenes", "schedules")
        private val COMMON_PROFILE_FIELDS = setOf("chat_api_key", "chat_base_url", "chat_model",
            "assemblyai_api_key", "assemblyai_model", "fish_api_key", "fish_model", "fish_reference_id")
        private val CHAT_ALIASES = setOf("groq_api_key", "groq_model", "custom_api_key", "custom_model", "custom_endpoint")
        private val PROFILE_NUMBER_FIELDS = setOf("stt_silence_seconds", "tts_speed", "tts_pitch", "llm_temperature")
        // Mirrors SettingsRepository.parseHomeProviderProfile's allowed field contract.
        private val PROFILE_FIELDS = COMMON_PROFILE_FIELDS + CHAT_ALIASES + PROFILE_NUMBER_FIELDS +
            setOf("assemblyai_base_url", "fish_base_url", "custom_json_mode")
        private val STRING_SETTINGS = setOf("llm_api_key", "stt_api_key", "tts_api_key", "llm_model", "stt_model", "tts_model", "tts_reference_id")
        private val NUMBER_SETTINGS = setOf("stt_silence_seconds", "tts_speed", "llm_temperature")
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        private val BASE_OPERATIONS = setOf("data.list", "data.get", "data.put", "data.delete", "data.changes", "data.merge", "profiles.list", "profiles.import", "profiles.export", "context.search")
        private fun requireData(condition: Boolean, code: String) { if (!condition) throw HomeV2Exception(code) }
        private fun hex(value: Any?): String = (value as? String)?.also { requireData(Regex("[0-9a-f]{32}").matches(it), "invalid_identity") } ?: throw HomeV2Exception("invalid_identity")
        private fun identifier(value: Any?): String = (value as? String)?.also { requireData(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}").matches(it), "invalid_identifier") } ?: throw HomeV2Exception("invalid_identifier")
        private fun integer(value: Any?, minimum: Long = 0, maximum: Long = MAX_INTEGER): Long {
            requireData(value is Int || value is Long, "invalid_integer")
            return (value as Number).toLong().also { requireData(it in minimum..maximum, "invalid_integer") }
        }
        private fun increment(value: Long): Long { requireData(value < MAX_INTEGER, "invalid_integer"); return value + 1 }
        private fun text(value: Any?, maximum: Int, empty: Boolean = false): String {
            val s = value as? String ?: throw HomeV2Exception("invalid_string")
            unicode(s); requireData((empty || s.isNotEmpty()) && s.toByteArray(Charsets.UTF_8).size <= maximum, "invalid_string"); return s
        }
        private fun fields(value: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
            val keys = value.keys().asSequence().toSet()
            requireData(keys.containsAll(required) && keys.all { it in required || it in optional }, "invalid_arguments")
        }
        private fun limit(a: JSONObject) = integer(if (a.has("limit")) a.opt("limit") else 50, 1, 100).toInt()
        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        private fun randomKey() = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
        private fun unicode(s: String) {
            var i = 0
            while (i < s.length) {
                val ch = s[i++]
                if (ch.isHighSurrogate()) { requireData(i < s.length && s[i].isLowSurrogate(), "invalid_unicode"); i++ }
                else requireData(!ch.isLowSurrogate(), "invalid_unicode")
            }
        }
        /** Sorted ASCII JSON, bounds before expansion; matches PC ensure_ascii record budgets. */
        private fun encode(value: Any?, maximum: Int, maximumDepth: Int = 12): String {
            val out = StringBuilder(); var visited = 0
            fun append(s: String) { requireData(out.length + s.length <= maximum, if (maximum == 16384) "record_too_large" else "json_limit"); out.append(s) }
            fun quote(s: String) {
                unicode(s); requireData(s.length <= 65536, "json_limit"); append("\"")
                for (ch in s) when (ch) {
                    '"' -> append("\\\""); '\\' -> append("\\\\")
                    '\b' -> append("\\b"); '\u000c' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (ch < ' ' || ch > '~') append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch.toString())
                }
                append("\"")
            }
            fun visit(v: Any?, depth: Int) {
                visited++; requireData(visited <= 8192 && depth <= maximumDepth, "json_limit")
                when (v) {
                    null, JSONObject.NULL -> append("null")
                    is JSONObject -> {
                        requireData(v.length() <= 1024, "json_limit"); append("{")
                        v.keys().asSequence().toList().sorted().forEachIndexed { i, key ->
                            if (i > 0) append(","); visited++; requireData(visited <= 8192 && depth + 1 <= 12, "json_limit")
                            quote(key); append(":"); visit(v.get(key), depth + 1)
                        }; append("}")
                    }
                    is JSONArray -> { requireData(v.length() <= 1024, "json_limit"); append("["); for (i in 0 until v.length()) { if (i > 0) append(","); visit(v.get(i), depth + 1) }; append("]") }
                    is String -> quote(v)
                    is Boolean -> append(v.toString())
                    is Int, is Long -> { val n = (v as Number).toLong(); requireData(n in -MAX_INTEGER..MAX_INTEGER, "invalid_integer"); append(n.toString()) }
                    is Float, is Double -> { requireData((v as Number).toDouble().isFinite(), "invalid_json_value"); append(v.toString()) }
                    else -> throw HomeV2Exception("invalid_json_value")
                }
            }
            visit(value, 0); return out.toString()
        }
    }
}
