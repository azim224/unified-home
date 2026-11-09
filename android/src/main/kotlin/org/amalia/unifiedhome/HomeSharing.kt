package org.amalia.unifiedhome

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Local consent only. Never expose [update] through an assistant tool or peer request. */
class HomeSharing(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "unified-home-sharing", Context.MODE_PRIVATE,
    )
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<HomeSharingState> = mutableState.asStateFlow()
    val generation: Long get() = state.value.generation

    /** Safe to expose as read-only status; contains no context or invitation secrets. */
    @Synchronized
    fun snapshot(): JSONObject = mutableState.value.toJson()

    /**
     * Partial update from an explicit local settings interaction only. Strict booleans,
     * no role selection or membership change implicitly grants consent. Call on IO.
     * Publishes only after a successful synchronous disk commit; throws on failure.
     */
    @Synchronized
    fun update(patch: JSONObject): JSONObject {
        val keys = setOf("shareContext", "allowBrain", "allowSpeaker", "allowListener")
        patch.keys().forEach { key ->
            require(key in keys && patch.get(key) is Boolean) { "Invalid sharing preference: $key" }
        }
        val before = mutableState.value
        fun value(key: String, previous: Boolean): Boolean =
            if (patch.has(key)) patch.getBoolean(key) else previous
        val requested = before.copy(
            shareContext = value("shareContext", before.shareContext),
            allowBrain = value("allowBrain", before.allowBrain),
            allowSpeaker = value("allowSpeaker", before.allowSpeaker),
            allowListener = value("allowListener", before.allowListener),
        )
        if (requested == before) return before.toJson()
        check(before.generation < Long.MAX_VALUE) { "Sharing generation exhausted" }
        val next = requested.copy(generation = before.generation + 1)
        if (!persist(next)) {
            // SharedPreferences changes its in-memory map even when commit fails.
            // Restore the previous consent before a new owner can read that map.
            check(persist(before)) { "Could not persist or restore sharing consent" }
            throw IllegalStateException("Could not persist sharing consent")
        }
        mutableState.value = next
        return next.toJson()
    }

    private fun persist(value: HomeSharingState): Boolean = preferences.edit()
        .putBoolean("shareContext", value.shareContext)
        .putBoolean("allowBrain", value.allowBrain)
        .putBoolean("allowSpeaker", value.allowSpeaker)
        .putBoolean("allowListener", value.allowListener)
        .putLong("generation", value.generation)
        .commit()

    private fun read(): HomeSharingState = try {
        HomeSharingState(
            shareContext = preferences.getBoolean("shareContext", false),
            allowBrain = preferences.getBoolean("allowBrain", false),
            allowSpeaker = preferences.getBoolean("allowSpeaker", false),
            allowListener = preferences.getBoolean("allowListener", false),
            generation = preferences.getLong("generation", 0L).coerceAtLeast(0L),
        )
    } catch (error: ClassCastException) {
        // Corrupt/mismatched storage must never be interpreted as consent.
        android.util.Log.e("UnifiedHome", "Invalid sharing preferences; consent disabled")
        HomeSharingState()
    }
}

/** Immutable observable consent. Every changed, durably saved set advances generation. */
data class HomeSharingState(
    val shareContext: Boolean = false,
    val allowBrain: Boolean = false,
    val allowSpeaker: Boolean = false,
    val generation: Long = 0L,
    val allowListener: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("shareContext", shareContext)
        .put("allowBrain", allowBrain)
        .put("allowSpeaker", allowSpeaker)
        .put("allowListener", allowListener)
        .put("generation", generation)
}
