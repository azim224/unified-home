package org.amalia.unifiedhome

import android.content.Context
import android.util.AtomicFile
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
//  Домен «Единого дома»
//
//  Здесь только данные и правила: что такое устройство, что оно умеет, что
//  значит «применить команду» и когда результат считается подтверждённым.
//  Сеть и конкретные мосты — в Transport.kt, договор между узлами — в Mesh.kt.
//
//  Главное правило дома: «отправлено» ≠ «подтверждено». Устройство может не
//  ответить, ответить позже или ответить не тем, что просили, — и во всех
//  этих случаях человек должен увидеть правду, а не бодрое «готово».
// ─────────────────────────────────────────────────────────────────────────────

/** Тип устройства. Тип определяет набор возможностей, а не модель железа. */
object Kind {
    const val LIGHT = "light"
    const val SWITCH = "switch"
    const val CLIMATE = "climate"
    const val MEDIA = "media"
    const val COVER = "cover"
    const val SENSOR = "sensor"

    val all = listOf(LIGHT, SWITCH, CLIMATE, MEDIA, COVER, SENSOR)

    fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).takeIf { it in all } ?: SWITCH
}

/** Действие над устройством. Словарь маленький намеренно: его понимает и мост. */
object Action {
    const val ON = "on"
    const val OFF = "off"
    const val TOGGLE = "toggle"
    const val SET = "set"
    const val OPEN = "open"
    const val CLOSE = "close"

    val all = listOf(ON, OFF, TOGGLE, SET, OPEN, CLOSE)
}

/** Как устройство подключено к дому. */
object Adapter {
    const val SIM = "sim"
    const val HTTP = "http"
    const val HOME_ASSISTANT = "homeassistant"

    val all = listOf(SIM, HTTP, HOME_ASSISTANT)

    fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).takeIf { it in all } ?: SIM
}

/** Статус шага плана: что случится, если план применить. */
object StepStatus {
    const val CHANGE = "change"
    const val SKIP = "skip"
    const val BLOCKED = "blocked"
}

/** Статус исполнения шага: что случилось на самом деле. */
object ApplyStatus {
    const val CONFIRMED = "confirmed"
    const val SENT = "sent"
    const val UNKNOWN = "unknown"
    const val FAILED = "failed"
}

/** Возможности типа устройства — то, что можно показать человеку и модели. */
fun capabilitiesFor(kind: String): Set<String> = when (kind) {
    Kind.LIGHT -> setOf("power", "brightness", "color_temp")
    Kind.SWITCH -> setOf("power")
    Kind.CLIMATE -> setOf("power", "temperature")
    Kind.MEDIA -> setOf("power", "volume")
    Kind.COVER -> setOf("position")
    else -> emptySet()
}

/** Действия, осмысленные для типа. */
fun actionsFor(kind: String): Set<String> = when (kind) {
    Kind.LIGHT, Kind.MEDIA -> setOf(Action.ON, Action.OFF, Action.TOGGLE, Action.SET)
    Kind.SWITCH -> setOf(Action.ON, Action.OFF, Action.TOGGLE)
    Kind.CLIMATE -> setOf(Action.ON, Action.OFF, Action.SET)
    Kind.COVER -> setOf(Action.OPEN, Action.CLOSE, Action.SET)
    else -> emptySet()
}

/**
 * Устройство дома.
 *
 * @property id стабильный идентификатор; по нему работают сцены и журнал.
 * @property adapter как с ним говорить: симулятор, HTTP-мост или Home Assistant.
 * @property token токен моста. В интерфейс и в ответы модели он не попадает
 *   никогда — только признак «задан/не задан».
 */
data class HomeDevice(
    val id: String,
    val name: String,
    val room: String = "",
    val kind: String = Kind.SWITCH,
    val adapter: String = Adapter.SIM,
    val address: String = "",
    val entity: String = "",
    val token: String = "",
    val capabilities: Set<String> = capabilitiesFor(kind),
    val state: Map<String, Any?> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
) {

    fun supports(action: String): Boolean {
        if (action !in actionsFor(kind)) return false
        if (action == Action.SET) return capabilities.any { it != "power" }
        return true
    }

    fun power(): Boolean = state["power"] as? Boolean ?: false

    fun number(key: String): Double? = (state[key] as? Number)?.toDouble()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("room", room)
        put("kind", kind)
        put("adapter", adapter)
        put("address", address)
        put("entity", entity)
        put("token", token)
        put("capabilities", JSONArray(capabilities.toList()))
        put("state", stateToJson(state))
        put("updated_at", updatedAt)
    }

    /** Представление без секретов — для модели и журнала. */
    fun publicJson(): JSONObject = toJson().apply {
        remove("token")
        remove("address")
        put("has_token", token.isNotBlank())
    }

    companion object {
        fun fromJson(json: JSONObject): HomeDevice {
            val kind = Kind.normalize(json.optString("kind", Kind.SWITCH))
            val capabilities = mutableSetOf<String>()
            json.optJSONArray("capabilities")?.let { array ->
                for (index in 0 until array.length()) capabilities += array.optString(index)
            }
            if (capabilities.isEmpty()) capabilities += capabilitiesFor(kind)
            return HomeDevice(
                id = json.optString("id").ifBlank { newId("dev") },
                name = json.optString("name", "").ifBlank { "—" },
                room = json.optString("room", ""),
                kind = kind,
                adapter = Adapter.normalize(json.optString("adapter", Adapter.SIM)),
                address = json.optString("address", ""),
                entity = json.optString("entity", ""),
                token = json.optString("token", ""),
                capabilities = capabilities,
                state = jsonToState(json.optJSONObject("state")),
                updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
            )
        }
    }
}

/** Один шаг сцены: «что сделать с каким устройством». */
data class SceneStep(
    val deviceId: String,
    val action: String,
    val params: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device_id", deviceId)
        .put("action", action)
        .put("params", stateToJson(params))

    companion object {
        fun fromJson(json: JSONObject): SceneStep = SceneStep(
            deviceId = json.optString("device_id"),
            action = json.optString("action", Action.ON),
            params = jsonToState(json.optJSONObject("params")),
        )
    }
}

/** Сцена — именованный набор шагов. Сцена ничего не делает, пока её не применят. */
data class Scene(
    val id: String,
    val title: String,
    val description: String = "",
    val steps: List<SceneStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("description", description)
        .put("steps", JSONArray(steps.map { it.toJson() }))
        .put("created_at", createdAt)

    companion object {
        fun fromJson(json: JSONObject): Scene {
            val steps = mutableListOf<SceneStep>()
            json.optJSONArray("steps")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { steps += SceneStep.fromJson(it) }
                }
            }
            return Scene(
                id = json.optString("id").ifBlank { newId("scene") },
                title = json.optString("title", "").ifBlank { "—" },
                description = json.optString("description", ""),
                steps = steps,
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
            )
        }
    }
}

/** Строка журнала: дом помнит, что с ним делали, но не хранит секретов. */
data class AuditEntry(
    val at: Long,
    val kind: String,
    val title: String,
    val details: String = "",
    val failed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("at", at)
        .put("kind", kind)
        .put("title", title)
        .put("details", details)
        .put("failed", failed)

    companion object {
        fun fromJson(json: JSONObject): AuditEntry = AuditEntry(
            at = json.optLong("at", System.currentTimeMillis()),
            kind = json.optString("kind", "event"),
            title = json.optString("title", ""),
            details = json.optString("details", ""),
            failed = json.optBoolean("failed", false),
        )
    }
}

/** Снимок дома для интерфейса. */
data class HomeSnapshot(
    val devices: List<HomeDevice> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val audit: List<AuditEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Шаг плана: снимок «до», ожидаемое «после» и честная причина пропуска. */
data class PlannedStep(
    val deviceId: String,
    val deviceName: String,
    val action: String,
    val params: Map<String, Any?>,
    val before: Map<String, Any?> = emptyMap(),
    val after: Map<String, Any?> = emptyMap(),
    val status: String = StepStatus.CHANGE,
    val reason: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device_id", deviceId)
        .put("device_name", deviceName)
        .put("action", action)
        .put("params", stateToJson(params))
        .put("before", stateToJson(before))
        .put("after", stateToJson(after))
        .put("status", status)
        .put("reason", reason)
}

/**
 * План — это обещание посмотреть и решить, а не выполнить.
 *
 * Живёт ограниченное время: план, собранный десять минут назад, уже не
 * описывает дом, поэтому применять его вслепую нельзя.
 */
data class Plan(
    val token: String,
    val title: String,
    val steps: List<PlannedStep>,
    val sceneId: String = "",
    val expiresAt: Long = System.currentTimeMillis() + 180_000,
) {
    val changes: Int get() = steps.count { it.status == StepStatus.CHANGE }

    fun toJson(): JSONObject = JSONObject()
        .put("token", token)
        .put("title", title)
        .put("scene_id", sceneId)
        .put("steps", JSONArray(steps.map { it.toJson() }))
        .put("changes", changes)
        .put("expires_in_sec", ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
}

/** Одноразовые планы: токен выдаётся, применяется и исчезает. */
class PlanStore {
    private val plans = java.util.concurrent.ConcurrentHashMap<String, Plan>()

    fun add(plan: Plan): Plan {
        prune()
        plans[plan.token] = plan
        return plan
    }

    fun get(token: String): Plan? = plans[token]?.takeIf { it.expiresAt > System.currentTimeMillis() }

    fun take(token: String): Plan? {
        val plan = plans.remove(token) ?: return null
        return plan.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        plans.entries.removeAll { it.value.expiresAt <= now }
    }
}

/**
 * Хранилище дома: устройства, сцены и журнал в одном JSON-файле.
 *
 * Чтение и запись — только из IO-корутин: конструктор дешёвый, поэтому
 * плагин не читает диск на главном потоке при загрузке.
 */
class HomeStore(context: Context) {

    private val directory = File(context.filesDir, "unified-home")
    private val file = File(directory, "state.json")

    private val devices = mutableListOf<HomeDevice>()
    private val scenes = mutableListOf<Scene>()
    private val audit = mutableListOf<AuditEntry>()
    private var loaded = false

    private val _state = MutableStateFlow(HomeSnapshot())
    val state: StateFlow<HomeSnapshot> = _state.asStateFlow()

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            devices.clear()
            scenes.clear()
            audit.clear()
            directory.mkdirs()
            val atomic = AtomicFile(file)
            if (file.isFile || File(directory, "state.json.bak").isFile) {
                parse(JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }))
            } else {
                seed()
                persist()
            }
            loaded = true
            publish(null)
        } catch (error: Exception) {
            devices.clear()
            scenes.clear()
            audit.clear()
            _state.value = HomeSnapshot(loading = false, error = "Не удалось прочитать дом. Повторите загрузку.")
            throw IllegalStateException("Не удалось прочитать дом. Исходный файл сохранён.", error)
        }
    }

    /** Повторная попытка после ошибки чтения: сбрасывает кэш и читает заново. */
    @Synchronized
    fun reload() {
        loaded = false
        devices.clear()
        scenes.clear()
        audit.clear()
        _state.value = HomeSnapshot(loading = true)
        ensureLoaded()
    }

    @Synchronized
    fun devices(): List<HomeDevice> {
        ensureLoaded()
        return devices.toList()
    }

    @Synchronized
    fun scenes(): List<Scene> {
        ensureLoaded()
        return scenes.toList()
    }

    @Synchronized
    fun audit(limit: Int = 40): List<AuditEntry> {
        ensureLoaded()
        return audit.takeLast(limit).reversed()
    }

    @Synchronized
    fun device(query: String): HomeDevice? {
        ensureLoaded()
        val text = query.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return null
        devices.firstOrNull { it.id == query }?.let { return it }
        val exact = devices.filter { it.name.lowercase(Locale.ROOT) == text }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        return devices.filter { it.name.lowercase(Locale.ROOT).contains(text) }.singleOrNull()
    }

    @Synchronized
    fun scene(query: String): Scene? {
        ensureLoaded()
        val text = query.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return null
        return scenes.firstOrNull { it.id == query }
            ?: scenes.filter { it.title.lowercase(Locale.ROOT) == text }.singleOrNull()
            ?: scenes.filter { it.title.lowercase(Locale.ROOT).contains(text) }.singleOrNull()
    }

    @Synchronized
    fun saveDevice(device: HomeDevice): HomeDevice {
        ensureLoaded()
        val updated = device.copy(updatedAt = System.currentTimeMillis())
        val index = devices.indexOfFirst { it.id == updated.id }
        if (index >= 0) devices[index] = updated else devices += updated
        persist()
        publish(null)
        return updated
    }

    @Synchronized
    fun removeDevice(id: String): Boolean {
        ensureLoaded()
        val removed = devices.removeAll { it.id == id }
        if (removed) {
            persist()
            publish(null)
        }
        return removed
    }

    @Synchronized
    fun saveScene(scene: Scene): Scene {
        ensureLoaded()
        val index = scenes.indexOfFirst { it.id == scene.id }
        if (index >= 0) scenes[index] = scene else scenes += scene
        persist()
        publish(null)
        return scene
    }

    @Synchronized
    fun deleteScene(id: String): Boolean {
        ensureLoaded()
        val removed = scenes.removeAll { it.id == id }
        if (removed) {
            persist()
            publish(null)
        }
        return removed
    }

    @Synchronized
    fun record(kind: String, title: String, details: String = "", failed: Boolean = false) {
        ensureLoaded()
        audit += AuditEntry(System.currentTimeMillis(), kind, title, details, failed)
        while (audit.size > 120) audit.removeAt(0)
        persist()
        publish(null)
    }

    private fun parse(root: JSONObject) {
        require(root.optJSONArray("devices") != null && root.optJSONArray("scenes") != null &&
            root.optJSONArray("audit") != null) { "Повреждён формат хранилища дома." }
        root.optJSONArray("devices")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { devices += HomeDevice.fromJson(it) }
            }
        }
        root.optJSONArray("scenes")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { scenes += Scene.fromJson(it) }
            }
        }
        root.optJSONArray("audit")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { audit += AuditEntry.fromJson(it) }
            }
        }
        // An intentionally empty saved home must remain empty.
    }

    /**
     * Демо-дом при первом запуске.
     *
     * Это не «фальшивые данные ради красоты»: симулятор — полноценный
     * адаптер, у которого есть состояние и который честно подтверждает
     * команды. Человек видит, как всё работает, до подключения железа.
     */
    private fun seed() {
        devices += HomeDevice(
            id = "lamp-kitchen", name = "Свет на кухне", room = "Кухня", kind = Kind.LIGHT,
            state = mapOf("power" to false, "brightness" to 80, "color_temp" to 3000),
        )
        devices += HomeDevice(
            id = "lamp-living", name = "Лампа в гостиной", room = "Гостиная", kind = Kind.LIGHT,
            state = mapOf("power" to false, "brightness" to 60, "color_temp" to 2700),
        )
        devices += HomeDevice(
            id = "tv-living", name = "Телевизор", room = "Гостиная", kind = Kind.MEDIA,
            state = mapOf("power" to false, "volume" to 20),
        )
        devices += HomeDevice(
            id = "climate-bedroom", name = "Климат в спальне", room = "Спальня", kind = Kind.CLIMATE,
            state = mapOf("power" to false, "temperature" to 21.0),
        )
        devices += HomeDevice(
            id = "cover-bedroom", name = "Шторы в спальне", room = "Спальня", kind = Kind.COVER,
            state = mapOf("position" to 0),
        )
        scenes += Scene(
            id = "scene-quiet-evening",
            title = "Тихий вечер",
            description = "Приглушить свет, выключить телевизор, опустить шторы.",
            steps = listOf(
                SceneStep("lamp-living", Action.ON, mapOf("brightness" to 25, "color_temp" to 2700)),
                SceneStep("lamp-kitchen", Action.ON, mapOf("brightness" to 35)),
                SceneStep("tv-living", Action.OFF),
                SceneStep("cover-bedroom", Action.CLOSE),
            ),
        )
        scenes += Scene(
            id = "scene-morning",
            title = "Утро",
            description = "Свет на кухне, тепло в спальне, шторы открыты.",
            steps = listOf(
                SceneStep("lamp-kitchen", Action.ON, mapOf("brightness" to 90, "color_temp" to 4500)),
                SceneStep("climate-bedroom", Action.ON, mapOf("temperature" to 22)),
                SceneStep("cover-bedroom", Action.OPEN),
            ),
        )
        scenes += Scene(
            id = "scene-all-off",
            title = "Всё выключить",
            description = "Свет и техника выключаются, шторы закрываются.",
            steps = listOf(
                SceneStep("lamp-kitchen", Action.OFF),
                SceneStep("lamp-living", Action.OFF),
                SceneStep("tv-living", Action.OFF),
                SceneStep("climate-bedroom", Action.OFF),
                SceneStep("cover-bedroom", Action.CLOSE),
            ),
        )
        audit += AuditEntry(
            System.currentTimeMillis(), "seed",
            "Демо-дом создан", "5 симуляторов и 3 сцены", false,
        )
    }

    private fun persist() {
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать каталог дома." }
            val root = JSONObject()
                .put("version", 2)
                .put("devices", JSONArray(devices.map { it.toJson() }))
                .put("scenes", JSONArray(scenes.map { it.toJson() }))
                .put("audit", JSONArray(audit.takeLast(120).map { it.toJson() }))
            val output = atomic.startWrite()
            stream = output
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            // Restore the last durable version, never expose unsaved data as success.
            devices.clear()
            scenes.clear()
            audit.clear()
            loaded = false
            _state.value = HomeSnapshot(loading = false, error = "Не удалось сохранить дом. Повторите загрузку.")
            throw IllegalStateException("Не удалось сохранить дом. Повторите загрузку.", error)
        }
    }

    private fun publish(error: String?) {
        _state.value = HomeSnapshot(
            devices = devices.toList(),
            scenes = scenes.toList(),
            audit = audit.takeLast(20).reversed(),
            loading = false,
            error = error,
        )
    }
}

// ── Состояние: преобразования и проверки ─────────────────────────────────────

/** JSON-объект → неизменяемая карта состояния. */
fun jsonToState(json: JSONObject?): Map<String, Any?> {
    if (json == null) return emptyMap()
    val result = LinkedHashMap<String, Any?>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = when (val value = json.opt(key)) {
            null, JSONObject.NULL -> null
            is Int, is Long, is Double, is Boolean, is String -> value
            else -> value.toString()
        }
    }
    return result
}

/** Карта состояния → JSON-объект. */
fun stateToJson(state: Map<String, Any?>): JSONObject {
    val json = JSONObject()
    for ((key, value) in state) {
        json.put(key, value ?: JSONObject.NULL)
    }
    return json
}

/** Числовые параметры и их допустимые диапазоны. */
private val RANGES = mapOf(
    "brightness" to (0.0..100.0),
    "color_temp" to (1500.0..9000.0),
    "temperature" to (5.0..35.0),
    "volume" to (0.0..100.0),
    "position" to (0.0..100.0),
)

/**
 * Приводит параметры к допустимым: отбрасывает мусор и проверяет диапазоны.
 *
 * @throws IllegalArgumentException если параметр вне диапазона или не число:
 *   лучше отказать до отправки, чем включить лампу «на -40%».
 */
fun normalizeParams(
    action: String,
    params: Map<String, Any?>,
    capabilities: Set<String>? = null,
): Map<String, Any?> {
    require(action in Action.all) { "Неизвестное действие." }
    require(params.isEmpty() || action == Action.ON || action == Action.SET) {
        "Параметры допустимы только для on и set."
    }
    val clean = LinkedHashMap<String, Any?>()
    for ((key, raw) in params) {
        val range = RANGES[key] ?: throw IllegalArgumentException("Неизвестный параметр «$key».")
        require(capabilities == null || key in capabilities) { "Устройство не поддерживает «$key»." }
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().replace(',', '.').toDoubleOrNull()
            else -> null
        } ?: throw IllegalArgumentException("«$key» должно быть числом.")
        if (!value.isFinite() || value < range.start || value > range.endInclusive) {
            throw IllegalArgumentException(
                "«$key» вне диапазона ${range.start.toInt()}..${range.endInclusive.toInt()}.",
            )
        }
        clean[key] = if (key == "temperature") value else value.toInt()
    }
    if (action == Action.SET && clean.isEmpty()) {
        throw IllegalArgumentException("Для «set» нужен хотя бы один параметр.")
    }
    return clean
}

/** Ожидаемое состояние после действия — для сравнения с фактом. */
fun projectState(device: HomeDevice, action: String, params: Map<String, Any?>): Map<String, Any?> {
    val desired = LinkedHashMap<String, Any?>()
    when (action) {
        Action.ON -> {
            desired["power"] = true
            for (key in listOf("brightness", "color_temp", "temperature", "volume", "position")) {
                if (params.containsKey(key)) desired[key] = params[key]
            }
        }
        Action.OFF -> desired["power"] = false
        Action.TOGGLE -> desired["power"] = !device.power()
        Action.OPEN -> desired["position"] = 100
        Action.CLOSE -> desired["position"] = 0
        Action.SET -> desired.putAll(params)
    }
    return desired
}

/**
 * Совпадает ли фактическое состояние с ожидаемым.
 *
 * Допуск нужен потому, что мосты округляют: лампа, которой сказали 25%,
 * вполне может отчитаться 24%.
 */
fun matches(desired: Map<String, Any?>, actual: Map<String, Any?>, tolerance: Double = 2.0): Boolean {
    for ((key, expected) in desired) {
        if (!actual.containsKey(key)) return false
        val got = actual[key]
        val expectedNumber = (expected as? Number)?.toDouble()
        val gotNumber = (got as? Number)?.toDouble()
        if (expectedNumber != null && gotNumber != null) {
            if (!expectedNumber.isFinite() || !gotNumber.isFinite()) return false
            val allowed = when (key) {
                "temperature" -> 0.5
                "color_temp" -> 100.0
                else -> tolerance
            }
            if (kotlin.math.abs(expectedNumber - gotNumber) > allowed) return false
        } else if (expected?.toString() != got?.toString()) {
            return false
        }
    }
    return true
}

/**
 * Числовая выжимка состояния без слов: «80% · 3000 K», «21 °C», «40%».
 *
 * Слова намеренно не здесь: их выбирает экран по языку интерфейса, а питание
 * показывает переключатель — состояние читается движением, а не подписью.
 */
fun stateSummary(device: HomeDevice): String = when (device.kind) {
    Kind.LIGHT -> buildList {
        device.number("brightness")?.let { add("${it.toInt()}%") }
        device.number("color_temp")?.let { add("${it.toInt()} K") }
    }.joinToString(" · ")
    Kind.CLIMATE -> buildList {
        device.number("temperature")?.let { add("${it.toInt()} °C") }
        device.number("current_temperature")?.let { add("сейчас ${it.toInt()} °C") }
    }.joinToString(" · ")
    Kind.MEDIA -> device.number("volume")?.let { "${it.toInt()}%" }.orEmpty()
    Kind.COVER -> device.number("position")?.let { "${it.toInt()}%" }.orEmpty()
    Kind.SENSOR -> device.number("value")?.let { it.toString() }.orEmpty()
    else -> ""
}

/** Короткий уникальный идентификатор с читаемым префиксом. */
fun newId(prefix: String): String = "${prefix}_" + UUID.randomUUID().toString()