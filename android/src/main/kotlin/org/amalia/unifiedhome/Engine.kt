package org.amalia.unifiedhome

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
//  Движок дома: план → согласие → исполнение → сверка.
//
//  Ни один шаг не считается выполненным по факту «мы отправили». Каждый шаг
//  заканчивается одним из честных статусов:
//
//    confirmed — устройство отчиталось состоянием, совпавшим с командой;
//    sent      — мост принял команду, но состояние не подтвердил;
//    unknown   — ответ потерян после отправки; повторять вслепую нельзя;
//    failed    — команда не ушла или устройство её не умеет;
//    skipped   — дом уже был в нужном состоянии.
//
//  План собирается по текущему состоянию и живёт три минуты: старый план
//  описывает уже другой дом.
// ─────────────────────────────────────────────────────────────────────────────

class HomeEngine(context: Context, private val scope: CoroutineScope) {

    private val operations = Mutex()
    val store = HomeStore(context)
    val sharing = HomeSharing(context)
    val plans = PlanStore()
    private data class RemoteOwner(val node: String, val session: String, val home: String, val receiver: String, val expires: Long)
    private val remotePlans = ConcurrentHashMap<String, RemoteOwner>()
    private val remoteExecutable = PlanStore()
    val mesh = MeshService(context, scope) { operation, args, from, session ->
        onRemoteRequest(operation, args, from, session)
    }

    /** Чтение дома с диска — единственная тяжёлая работа при загрузке. */
    fun load() {
        scope.launch(Dispatchers.IO) {
            try { store.ensureLoaded() } catch (error: IllegalStateException) {
                android.util.Log.e("UnifiedHome", "Home storage could not be loaded")
            }
        }
    }

    private var meshGeneration = 0L

    @Synchronized
    fun startMesh() {
        val generation = ++meshGeneration
        scope.launch(Dispatchers.IO) {
            synchronized(this@HomeEngine) {
                if (meshGeneration == generation) mesh.start()
            }
        }
    }

    @Synchronized
    fun stopMesh() {
        meshGeneration++
        remotePlans.clear()
        mesh.stop()
    }

    // ── Планы ───────────────────────────────────────────────────────────────

    /** Собирает план по сцене (по id, названию или фрагменту названия). */
    suspend fun planScene(query: String): Plan? {
        val scene = store.scene(query) ?: return null
        return planSteps(scene.title, scene.steps, scene.id)
    }

    /**
     * Собирает план по произвольным шагам.
     *
     * Чтение состояний идёт параллельно: пять лампочек не должны складываться
     * в пять последовательных таймаутов.
     */
    suspend fun planSteps(
        title: String,
        steps: List<SceneStep>,
        sceneId: String = "",
        remote: Boolean = false,
    ): Plan = coroutineScope {
        val planned = steps
            .map { step -> async(Dispatchers.IO) { planStep(step) } }
            .awaitAll()
        (if (remote) remoteExecutable else plans).add(
            Plan(token = newId("plan"), title = title, steps = planned, sceneId = sceneId))
    }

    private suspend fun planStep(step: SceneStep): PlannedStep {
        val device = store.device(step.deviceId)
            ?: return PlannedStep(
                step.deviceId, step.deviceId, step.action, step.params,
                status = StepStatus.BLOCKED, reason = "Устройство не найдено.",
            )
        if (!device.supports(step.action)) {
            return PlannedStep(
                device.id, device.name, step.action, step.params,
                status = StepStatus.BLOCKED,
                reason = "«${device.name}» не умеет «${step.action}».",
            )
        }
        val params = try {
            normalizeParams(step.action, step.params, device.capabilities)
        } catch (error: IllegalArgumentException) {
            return PlannedStep(
                device.id, device.name, step.action, step.params,
                status = StepStatus.BLOCKED, reason = error.message ?: "Неверные параметры.",
            )
        }
        val snapshot = readDevice(device)
        if (!snapshot.ok) {
            return PlannedStep(
                device.id, device.name, step.action, params,
                status = StepStatus.BLOCKED,
                reason = "Состояние неизвестно: ${snapshot.error}",
            )
        }
        if (snapshot.state.isNotEmpty()) {
            store.saveDevice(device.copy(state = snapshot.state))
        }
        val desired = projectState(device.copy(state = snapshot.state), step.action, params)
        val alreadyThere = step.action != Action.TOGGLE && matches(desired, snapshot.state)
        return PlannedStep(
            deviceId = device.id,
            deviceName = device.name,
            action = step.action,
            params = params,
            before = snapshot.state,
            after = desired,
            status = if (alreadyThere) StepStatus.SKIP else StepStatus.CHANGE,
            reason = if (alreadyThere) "Уже в нужном состоянии." else "",
        )
    }

    /** Исполняет план по токену. null — токена нет или он истёк. */
    suspend fun apply(token: String): JSONObject? = applyOwned(token, null)

    private suspend fun applyOwned(token: String, owner: RemoteOwner?): JSONObject? = operations.withLock { withContext(Dispatchers.IO) {
        val registered = remotePlans[token]
        if (registered != owner || (owner != null && (owner.expires <= System.currentTimeMillis() ||
                owner.home != mesh.membership().optString("home_id") ||
                owner.receiver != mesh.nodes.value.firstOrNull { it.self }?.session))) return@withContext null
        remotePlans.remove(token)
        val plan = (if (owner == null) plans else remoteExecutable).take(token) ?: return@withContext null
        val results = JSONArray()
        var confirmed = 0
        var sent = 0
        var unknown = 0
        var failed = 0
        var skipped = 0

        plan.steps.forEachIndexed { index, step ->
            currentCoroutineContext().ensureActive()
            if (owner != null) {
                val peer = mesh.nodes.value.firstOrNull { it.id == owner.node && !it.self }
                if (peer == null || peer.session != owner.session || !peer.online() ||
                    mesh.membership().optString("home_id") != owner.home ||
                    mesh.nodes.value.firstOrNull { it.self }?.session != owner.receiver) {
                    throw CancellationException("Household session changed")
                }
            }
            val result = try { when (step.status) {
                StepStatus.BLOCKED -> StepResult(ApplyStatus.FAILED, step.reason)
                else -> executeStep(step, "${plan.token}:$index")
            } } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                // Execution may already have reached hardware before persistence failed.
                StepResult(ApplyStatus.UNKNOWN, "Не удалось завершить проверку или сохранить результат.")
            }
            when (result.status) {
                "skipped" -> skipped++
                ApplyStatus.CONFIRMED -> confirmed++
                ApplyStatus.SENT -> sent++
                ApplyStatus.UNKNOWN -> unknown++
                else -> failed++
            }
            results.put(
                step.toJson()
                    .put("result", result.status)
                    .put("error", result.error),
            )
        }

        val summary = JSONObject()
            .put("title", plan.title)
            .put("total", plan.steps.size)
            .put("confirmed", confirmed)
            .put("sent", sent)
            .put("unknown", unknown)
            .put("failed", failed)
            .put("skipped", skipped)
            .put("honest", failed == 0 && unknown == 0 && sent == 0)
            .put("results", results)

        store.record(
            kind = "scene",
            title = plan.title,
            details = "confirmed=$confirmed, sent=$sent, unknown=$unknown, failed=$failed, skipped=$skipped",
            failed = failed > 0 || unknown > 0,
        )
        summary
    } }

    private suspend fun executeStep(step: PlannedStep, key: String): StepResult {
        val device = store.device(step.deviceId)
            ?: return StepResult(ApplyStatus.FAILED, "Устройство исчезло из дома.")
        // Re-read at application time, including steps previously marked "already so".
        val current = readDevice(device)
        if (!current.ok) return StepResult(ApplyStatus.FAILED, current.error)
        val fresh = device.copy(state = current.state)
        if (matches(step.after, current.state)) {
            store.saveDevice(fresh)
            return StepResult("skipped", "")
        }
        if (!matches(step.before, current.state, tolerance = 0.0)) {
            return StepResult(ApplyStatus.FAILED, "Дом изменился после планирования. Соберите новый план.")
        }
        // Resolve toggle to an explicit target: never toggle a changed state blindly.
        val action = if (step.action == Action.TOGGLE) {
            if (step.after["power"] == true) Action.ON else Action.OFF
        } else step.action
        val outcome = applyDevice(fresh, action, step.params, key)
        if (outcome.state.isNotEmpty()) store.saveDevice(fresh.copy(state = outcome.state))
        return StepResult(outcome.status, outcome.error)
    }

    // ── Прямые действия ─────────────────────────────────────────────────────

    /** Действие над одним устройством (кнопка в интерфейсе, инструмент модели). */
    suspend fun deviceAction(
        deviceRef: String,
        action: String,
        params: Map<String, Any?> = emptyMap(),
    ): ApplyOutcome = operations.withLock { withContext(Dispatchers.IO) {
        val device = store.device(deviceRef)
            ?: return@withContext ApplyOutcome(false, ApplyStatus.FAILED, error = "Устройство не найдено.")
        val snapshot = readDevice(device)
        if (!snapshot.ok) return@withContext ApplyOutcome(false, ApplyStatus.FAILED, error = snapshot.error)
        val fresh = device.copy(state = snapshot.state)
        val resolvedAction = if (action == Action.TOGGLE) {
            if (fresh.power()) Action.OFF else Action.ON
        } else action
        val outcome = applyDevice(fresh, resolvedAction, params, "ui:" + UUID.randomUUID().toString())
        if (outcome.state.isNotEmpty()) {
            store.saveDevice(fresh.copy(state = outcome.state))
        }
        store.record(
            kind = "device",
            title = "${device.name}: $action",
            details = outcome.status,
            failed = !outcome.ok || outcome.status == ApplyStatus.UNKNOWN,
        )
        outcome
    } }

    /** Перечитывает состояние устройства и сохраняет его. */
    suspend fun refresh(device: HomeDevice): ReadResult = withContext(Dispatchers.IO) {
        val snapshot = readDevice(device)
        if (snapshot.ok && snapshot.state.isNotEmpty()) {
            store.saveDevice(device.copy(state = snapshot.state))
        }
        snapshot
    }

    // ── Устройства и сцены ──────────────────────────────────────────────────

    fun addDevice(
        name: String,
        room: String,
        kind: String,
        adapter: String,
        address: String = "",
        entity: String = "",
        token: String = "",
    ): HomeDevice {
        require(name.isNotBlank()) { "Укажите название устройства." }
        require(kind in Kind.all && adapter in Adapter.all) { "Неизвестный тип или адаптер." }
        if (adapter != Adapter.SIM) {
            val uri = try { URI(address.trim()) } catch (error: Exception) {
                throw IllegalArgumentException("Некорректный адрес моста.")
            }
            require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawFragment == null) { "Некорректный адрес моста." }
        }
        require(!token.contains('\r') && !token.contains('\n')) { "Некорректный токен." }
        if (adapter == Adapter.HOME_ASSISTANT) {
            require(entity.isNotBlank() && token.isNotBlank()) { "Нужны entity_id и токен Home Assistant." }
        }
        val device = HomeDevice(
            id = newId("dev"),
            name = name.trim().ifBlank { "Устройство" },
            room = room.trim(),
            kind = Kind.normalize(kind),
            adapter = Adapter.normalize(adapter),
            address = address.trim(),
            entity = entity.trim(),
            token = token.trim(),
        )
        store.saveDevice(device)
        store.record("device", "Добавлено: ${device.name}", device.adapter)
        return device
    }

    fun removeDevice(id: String): Boolean {
        val device = store.device(id) ?: return false
        val removed = store.removeDevice(id)
        if (removed) store.record("device", "Удалено: ${device.name}")
        return removed
    }

    fun saveScene(title: String, description: String, steps: List<SceneStep>): Scene {
        require(title.isNotBlank() && steps.isNotEmpty()) { "Укажите название и шаги сцены." }
        val existing = store.scenes().firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }
        val scene = Scene(
            id = existing?.id ?: newId("scene"),
            title = title.trim().ifBlank { "Сцена" },
            description = description.trim(),
            steps = steps,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        store.saveScene(scene)
        store.record("scene", "Сцена сохранена: ${scene.title}", "шагов: ${steps.size}")
        return scene
    }

    // ── Сеть ────────────────────────────────────────────────────────────────

    /** Узлы предоставляют только узкий каталог и план/применение, не произвольный tool RPC. */
    private suspend fun onRemoteRequest(
        operation: String,
        args: JSONObject,
        from: String,
        session: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val homeBefore = mesh.membership().optString("home_id")
        val receiverBefore = mesh.nodes.value.firstOrNull { it.self }?.session.orEmpty()
        require(homeBefore.isNotEmpty() && receiverBefore.isNotEmpty()) { "Нет общего дома." }
        val data = when (operation) {
            "catalog" -> {
                require(args.length() == 0) { "Некорректный запрос." }
                val devices = store.devices()
                val scenes = store.scenes()
                JSONObject()
                    .put("devices", JSONArray(devices.take(40).map { device ->
                        JSONObject().put("id", device.id).put("name", device.name.take(80))
                            .put("room", device.room.take(60)).put("kind", device.kind)
                            .put("adapter", device.adapter)
                            .put("actions", JSONArray(actionsFor(device.kind).toList()))
                    }))
                    .put("scenes", JSONArray(scenes.take(40).map { scene ->
                        JSONObject().put("id", scene.id).put("title", scene.title.take(80))
                            .put("description", scene.description.take(120)).put("steps", scene.steps.size)
                    }))
                    .put("truncated", devices.size > 40 || scenes.size > 40)
            }
            "plan_scene" -> {
                require(args.length() == 1 && args.opt("scene") is String) { "Некорректный запрос." }
                val query = args.getString("scene")
                require(query.isNotEmpty() && query.length <= 256) { "Укажите сцену." }
                val scene = store.scenes().firstOrNull { it.id == query }
                    ?: store.scenes().filter { it.title == query }.singleOrNull()
                    ?: throw IllegalArgumentException("Сцена не найдена или имя неоднозначно.")
                remotePlans.entries.removeAll { it.value.expires <= System.currentTimeMillis() }
                require(remotePlans.size < 64) { "Слишком много ожидающих планов." }
                val plan = planSteps(scene.title, scene.steps, scene.id, remote = true)
                require(homeBefore == mesh.membership().optString("home_id") &&
                    receiverBefore == mesh.nodes.value.firstOrNull { it.self }?.session) { "Дом изменился." }
                val view = remoteView(plan.toJson())
                require(view.toString().toByteArray(Charsets.UTF_8).size <= 9_000) { "План слишком большой." }
                remotePlans[plan.token] = RemoteOwner(from, session, homeBefore, receiverBefore, plan.expiresAt)
                view
            }
            "apply_plan" -> {
                require(args.length() == 1 && args.opt("token") is String) { "Некорректный запрос." }
                val token = args.getString("token")
                val owner = remotePlans[token] ?: throw IllegalArgumentException("План не найден.")
                require(owner.node == from && owner.session == session) { "План принадлежит другому узлу." }
                remoteView(applyOwned(token, owner) ?: throw IllegalArgumentException("План истёк или дом изменился."))
            }
            else -> throw IllegalArgumentException("Недопустимое поручение.")
        }
        if (operation == "catalog") {
            val devices = data.getJSONArray("devices")
            val scenes = data.getJSONArray("scenes")
            while (data.toString().toByteArray(Charsets.UTF_8).size > 9_000) {
                val rows = if (devices.length() >= scenes.length()) devices else scenes
                rows.remove(rows.length() - 1)
                data.put("truncated", true)
            }
        }
        JSONObject().put("ok", true).put("data", data).put("error", "")
    }

    /** Never send arbitrary adapter state/errors over the household channel. */
    private fun remoteView(data: JSONObject): JSONObject {
        val rows = data.optJSONArray("steps") ?: data.optJSONArray("results") ?: return data
        val keys = setOf("power", "brightness", "color_temp", "temperature", "current_temperature", "volume", "position")
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            for (field in listOf("before", "after", "params")) {
                val original = row.optJSONObject(field) ?: continue
                val clean = JSONObject()
                for (key in keys) {
                    val value = original.opt(key)
                    if (value is Boolean || (value is Number && value.toDouble().isFinite())) clean.put(key, value)
                }
                row.put(field, clean)
            }
            if (row.optString("reason").isNotEmpty()) row.put("reason", "Шаг недоступен. Проверьте устройство локально.")
            if (row.optString("error").isNotEmpty()) row.put("error", "Не удалось подтвердить действие.")
        }
        return data
    }
}

private data class StepResult(val status: String, val error: String)
