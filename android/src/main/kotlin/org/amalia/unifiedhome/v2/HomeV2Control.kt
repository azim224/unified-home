package org.amalia.unifiedhome.v2

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Closeable

/** Explicit fixed-voter configuration; discovery NEVER changes voting membership. */
class HomeV2Control(private val context: Context, val node: HomeV2Node,
    private val home: String) : Closeable {
    private val config = AtomicFile(File(context.noBackupFilesDir, "home-v3-voters-$home.json"))
    private var coordinator: HomeV2Coordinator? = null
    val operations get() = coordinator?.operations ?: emptySet()
    init {
        v2Id(home)
        val text = try { config.openRead().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val block = ByteArray(4096)
            while (true) {
                val n = input.read(block); if (n < 0) break
                v2Require(bytes.size()+n <=65536,"invalid_coordination_config")
                bytes.write(block,0,n)
            }
            v2Require(bytes.size()>0,"invalid_coordination_config")
            String(bytes.toByteArray(),Charsets.UTF_8)
        } } catch (_: java.io.FileNotFoundException) { null }
        if (text != null) install(V2Json.parse(text.toByteArray(Charsets.UTF_8),65536))
    }
    private fun install(value: JSONObject) {
        v2Keys(value,setOf("voters","preferred"))
        val array = value.getJSONArray("voters")
        v2Require(array.length() in 1..256)
        val voters = (0 until array.length()).map { v2Id(array.getString(it)) }
        val preferred = v2Id(value.getString("preferred"))
        coordinator = HomeV2Coordinator(context,node.status.value.nodeId,home,voters,preferred)
    }
    /** Local human only; no in-place membership change while old grants might survive. */
    suspend fun configure(voters: List<String>, preferred: String) = withContext(Dispatchers.IO) {
        v2Require(coordinator == null,"coordination_already_configured")
        v2Require(voters.isNotEmpty() && voters.size <=256 && voters.toSet().size == voters.size)
        voters.forEach(::v2Id); v2Id(preferred)
        v2Require(node.status.value.nodeId in voters && preferred in voters)
        val value = JSONObject().put("voters",JSONArray(voters.sorted())).put("preferred",preferred)
        val stream = config.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); config.finishWrite(stream) }
        catch (e: Exception) { config.failWrite(stream); throw e }
        install(value)
    }
    suspend fun handle(operation: String,args: JSONObject,from: String): JSONObject =
        coordinator?.handle(operation,args,from) ?: throw HomeV2Exception("coordinator_disabled")
    suspend fun tick(): JSONObject {
        val current = coordinator ?: return JSONObject().put("enabled",false)
        val peers = node.peers.value.filter { it.homeId == home }.associateBy { it.nodeId }
        return current.tick(peers.keys) { peer,operation,args ->
            val route = peers[peer] ?: throw HomeV2Exception("peer_unavailable")
            val result = node.request(route.address,route.port,peer,operation,args)
            if (!result.ok) throw HomeV2Exception(result.error ?: "peer_unavailable")
            result.data
        }
    }
    fun status(): JSONObject = coordinator?.status() ?: JSONObject().put("enabled",false)
    override fun close() { coordinator?.close(); coordinator = null }
}
