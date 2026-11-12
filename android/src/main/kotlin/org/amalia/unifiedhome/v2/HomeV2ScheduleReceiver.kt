package org.amalia.unifiedhome.v2

import com.my.amali.plugin.HostServices
import org.json.JSONObject
import java.security.MessageDigest

/** Shared household namespace, independent of submitting coordinator identity.
 * Voters are fixed by the local-human configuration, not request metadata.
 * A prior lease holder may deliver late, but the durable action ID remains the same.
 */
class HomeV2ScheduleReceiver(private val host: HostServices,
    private val home: String, private val control: HomeV2Control) {
    val operations = setOf("schedules.task.submit","schedules.task.get")
    suspend fun handle(op: String,args: JSONObject,from: String): JSONObject {
        val status = control.status()
        val voters = status.optJSONArray("voters") ?: throw HomeV2Exception("coordinator_disabled")
        v2Require((0 until voters.length()).any { voters.getString(it) == from },"permission_denied")
        v2Require(args.optString("membership") == status.getString("membership"),"coordination_membership_mismatch")
        val fields = setOf("run_id","step_id","task_id","membership") +
            if (op == "schedules.task.submit") setOf("capability","arguments") else emptySet()
        v2Keys(args,fields)
        val run = v2Id(args.getString("run_id")); val step = args.getString("step_id")
        v2Require(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}").matches(step))
        // ASCII IDs: exact compact Python dumps([run,step]) byte layout.
        val seed = "[\"$run\",\"$step\"]"
        val expected = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
        v2Require(v2Id(args.getString("task_id")) == expected,"invalid_task_id")
        val requester = "home.v3:$home:schedule"
        val raw = when (op) {
            "schedules.task.get" -> host.homeCapabilityTask(requester,expected)
            "schedules.task.submit" -> {
                val name = args.getString("capability")
                v2Require(!name.startsWith("home_") && !name.startsWith("home.") && !name.startsWith("assistant."),"permission_denied")
                val input = args.getJSONObject("arguments"); val sorted = JSONObject()
                input.keys().asSequence().toList().sorted().forEach { key ->
                    val value = input.get(key)
                    v2Require(value !is JSONObject && value !is org.json.JSONArray,"invalid_arguments")
                    sorted.put(key,value)
                }
                host.homeExecuteCapability(requester,expected,name,sorted.toString())
            }
            else -> throw HomeV2Exception("unsupported_operation")
        }
        val result = V2Json.parse(raw.toByteArray(Charsets.UTF_8))
        if (result.has("error")) throw HomeV2Exception(result.getString("error"))
        return JSONObject().put("task_id",expected).put("state",if (result.getString("state") == "failed") "error" else result.getString("state"))
            .put("result",result.opt("output") ?: JSONObject.NULL).put("error",result.opt("code") ?: JSONObject.NULL)
    }
}
