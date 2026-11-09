package org.amalia.unifiedhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.my.amali.plugin.ScreenHost
import com.my.amali.ui.components.AmaliaScreen
import com.my.amali.ui.components.EmptyState
import com.my.amali.ui.components.ErrorState
import com.my.amali.ui.components.GlassCard
import com.my.amali.ui.components.GlassDialog
import com.my.amali.ui.components.GlassDialogShell
import com.my.amali.ui.components.GlassGroup
import com.my.amali.ui.components.GlassDivider
import com.my.amali.ui.components.GlassSlider
import com.my.amali.ui.components.GhostButton
import com.my.amali.ui.components.LoadingState
import com.my.amali.ui.components.SkeletonList
import com.my.amali.ui.components.PrimaryButton
import com.my.amali.ui.components.SectionTitle
import com.my.amali.ui.components.SettingsActionRow
import com.my.amali.ui.components.SettingsToggleRow
import com.my.amali.ui.components.SettingsValueRow
import com.my.amali.ui.components.StatusBanner
import com.my.amali.ui.theme.Radius
import com.my.amali.ui.theme.Spacing
import com.my.amali.ui.theme.glassSurface
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  Экран «Единый дом».
//
//  Один главный элемент — дом: сколько устройств, что с ними и что можно
//  сделать. Сцены показывают план до исполнения, а результат — по каждому
//  устройству, а не одной галочкой «готово».
//
//  Все поверхности — стекло хоста, все размеры и цвета — токены темы.
//  Состояния: загрузка, пустой дом, ошибка чтения, нет сети — каждое своё.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(engine: HomeEngine, host: ScreenHost) {
    val language = LocalConfiguration.current.locales[0].language
    val copy = remember(language) { copyFor(language) }
    val coordinationCopy = remember(language) { coordinationCopyFor(language) }

    val snapshot by engine.store.state.collectAsStateWithLifecycle()
    val nodes by engine.mesh.nodes.collectAsStateWithLifecycle()
    val meshError by engine.mesh.error.collectAsStateWithLifecycle()
    val maySleep by engine.mesh.maySleep.collectAsStateWithLifecycle()
    val vote by engine.mesh.lastVote.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<Plan?>(null) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sheetDevice by remember { mutableStateOf<HomeDevice?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf(false) }
    var membershipRefresh by remember { mutableStateOf(0) }
    var deviceResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun reload() {
        membershipRefresh++
        scope.launch {
            try {
                withContext(Dispatchers.IO) { engine.store.reload() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // HomeStore публикует ошибку чтения; не теряем и ошибки вне публикации.
                actionError = true
            }
        }
    }

    AmaliaScreen(
        title = copy.title,
        onBack = host::goBack,
        backLabel = copy.back,
        actions = {
            GhostButton(copy.refresh, onClick = { reload() })
        },
    ) { insets ->
        val error = snapshot.error
        when {
            snapshot.loading -> LoadingState(copy.loading, Modifier.padding(insets))
            error != null -> ErrorState(
                title = copy.errorTitle,
                description = error,
                retryLabel = copy.retry,
                onRetry = { reload() },
                modifier = Modifier.padding(insets),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = insets,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (snapshot.devices.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = copy.emptyTitle,
                            description = copy.emptyBody,
                            icon = Icons.Rounded.Home,
                            actionLabel = copy.addDevice,
                            onAction = { addOpen = true },
                        )
                    }
                }

                item(key = "summary") {
                    SummaryCard(snapshot.devices, snapshot.scenes.size, copy)
                }

                if (meshError.isNotBlank()) {
                    item(key = "mesh-error") {
                        StatusBanner(
                            icon = Icons.Rounded.WifiOff,
                            title = copy.network,
                            message = meshError,
                            positive = false,
                        )
                    }
                }

                if (actionError) {
                    item(key = "action-error") {
                        Text(
                            text = copy.failed,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }

                item(key = "scenes-title") { SectionTitle(copy.scenes) }
                item(key = "scenes") {
                    SceneSection(
                        scenes = snapshot.scenes,
                        busy = busy,
                        copy = copy,
                        onPlan = { scene ->
                            if (!busy) {
                                busy = true
                                actionError = false
                                summary = null
                                plan = null
                                scope.launch {
                                    try {
                                        plan = engine.planScene(scene.title)
                                        actionError = plan == null
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        actionError = true
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                    )
                }

                val current = plan
                if (current != null) {
                    item(key = "plan") {
                        PlanCard(
                            plan = current,
                            copy = copy,
                            busy = busy,
                            onApply = {
                                if (!busy) {
                                    busy = true
                                    actionError = false
                                    scope.launch {
                                        try {
                                            summary = engine.apply(current.token)
                                            actionError = summary == null
                                            plan = null
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            actionError = true
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            },
                            onCancel = { if (!busy) plan = null },
                        )
                    }
                }

                val result = summary
                if (result != null) {
                    item(key = "summary-result") {
                        ResultCard(result = result, copy = copy, onDismiss = { summary = null })
                    }
                }

                item(key = "rooms-title") { SectionTitle(copy.rooms) }
                snapshot.devices.groupBy { it.room.ifBlank { "—" } }.forEach { (room, devices) ->
                    item(key = "room-$room") {
                        RoomGroup(
                            room = room,
                            devices = devices,
                            copy = copy,
                            results = deviceResults,
                            onOpen = { sheetDevice = it },
                            onToggle = { device, target ->
                                scope.launch {
                                    val result = try {
                                        val outcome = engine.deviceAction(
                                            device.id,
                                            if (target) Action.ON else Action.OFF,
                                        )
                                        outcomeText(outcome, copy)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        copy.failed
                                    }
                                    deviceResults = deviceResults + (device.id to result)
                                }
                            },
                        )
                    }
                }

                item(key = "network-title") { SectionTitle(copy.network) }
                item(key = "network") {
                    NetworkSection(
                        engine = engine,
                        refresh = membershipRefresh,
                        nodes = nodes,
                        copy = copy,
                        maySleep = maySleep,
                        vote = vote,
                        onVote = { scope.launch { engine.mesh.vote(50.0) } },
                    )
                }

                item(key = "private-sharing", contentType = "settings") {
                    SharingSection(engine.sharing, sharingCopyFor(language), copy)
                }

                item(key = "coordination", contentType = "coordination") {
                    CoordinationSection(engine, nodes, copy, coordinationCopy)
                }

                item(key = "add") {
                    GlassCard(contentPadding = Spacing.md) {
                        SettingsActionRow(
                            title = copy.addDevice,
                            subtitle = null,
                            icon = Icons.Rounded.Add,
                            onClick = { addOpen = true },
                        )
                    }
                }

                if (snapshot.audit.isNotEmpty()) {
                    item(key = "journal-title") { SectionTitle(copy.journal) }
                    item(key = "journal") {
                        GlassGroup {
                            snapshot.audit.take(6).forEach { entry ->
                                SettingsActionRow(
                                    title = entry.title,
                                    subtitle = entry.details.ifBlank { null },
                                    value = null,
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    sheetDevice?.let { device ->
        val fresh = snapshot.devices.firstOrNull { it.id == device.id } ?: device
        DeviceSheet(
            device = fresh,
            engine = engine,
            copy = copy,
            onDismiss = { sheetDevice = null },
        )
    }

    if (addOpen) {
        AddDeviceSheet(
            engine = engine,
            copy = copy,
            onDismiss = { addOpen = false },
        )
    }
}

// ── Сводка ──────────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(devices: List<HomeDevice>, scenes: Int, copy: Copy) {
    GlassCard(elevated = true) {
        Text(
            text = "${devices.size} ${copy.devices} · $scenes ${copy.scenes}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = devices.map { it.room }.filter { it.isNotBlank() }.distinct()
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Сцены ───────────────────────────────────────────────────────────────────

@Composable
private fun SceneSection(
    scenes: List<Scene>,
    busy: Boolean,
    copy: Copy,
    onPlan: (Scene) -> Unit,
) {
    if (scenes.isEmpty()) {
        GlassCard(contentPadding = Spacing.md) {
            Text(
                text = copy.planEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    GlassGroup {
        scenes.forEach { scene ->
            SettingsActionRow(
                title = scene.title,
                subtitle = scene.description.ifBlank { "${scene.steps.size}" },
                value = copy.plan,
                onClick = { if (!busy) onPlan(scene) },
            )
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, copy: Copy, busy: Boolean, onApply: () -> Unit, onCancel: () -> Unit) {
    GlassCard(elevated = true) {
        Text(
            text = plan.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = copy.planHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        plan.steps.forEach { step ->
            PlanStepRow(step, copy)
            Spacer(Modifier.height(Spacing.xs))
        }
        Spacer(Modifier.height(Spacing.xs))
        PrimaryButton(
            text = copy.apply,
            onClick = onApply,
            enabled = !busy && plan.steps.any { it.status != StepStatus.BLOCKED },
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xxs))
        GhostButton(copy.cancel, onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PlanStepRow(step: PlannedStep, copy: Copy) {
    val status = when (step.status) {
        StepStatus.CHANGE -> copy.statusChange
        StepStatus.SKIP -> copy.statusSkip
        else -> copy.statusBlocked
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = step.deviceName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = buildString {
                append(actionText(step, copy))
                if (step.reason.isNotBlank()) append(" · ").append(step.reason)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = if (step.status == StepStatus.BLOCKED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

/** «выкл → вкл», «80% → 25%» — без глаголов, которые надо переводить отдельно. */
private fun actionText(step: PlannedStep, copy: Copy): String {
    val before = step.before
    val after = step.after
    val parts = mutableListOf<String>()
    if (after.containsKey("power") || before.containsKey("power")) {
        val from = if (before["power"] == true) copy.powerOn else copy.powerOff
        val to = if (after["power"] == true) copy.powerOn else copy.powerOff
        parts += "$from → $to"
    }
    for (key in listOf("brightness", "color_temp", "temperature", "volume", "position")) {
        if (!after.containsKey(key)) continue
        val from = (before[key] as? Number)?.toInt()
        val to = (after[key] as? Number)?.toInt()
        val suffix = when (key) {
            "color_temp" -> " K"
            "temperature" -> " °C"
            else -> "%"
        }
        parts += if (from != null) "$from$suffix → $to$suffix" else "$to$suffix"
    }
    return if (parts.isEmpty()) step.action else parts.joinToString(" · ")
}

@Composable
private fun ResultCard(result: JSONObject, copy: Copy, onDismiss: () -> Unit) {
    GlassCard(elevated = true) {
        Text(
            text = result.optString("title"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        val entries = result.optJSONArray("results")
        if (entries != null) {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val status = entry.optString("result")
                Text(
                    text = "${entry.optString("device_name")} · ${statusLabel(status, copy)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status == ApplyStatus.FAILED || status == ApplyStatus.UNKNOWN) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                val reason = entry.optString("error")
                if (reason.isNotBlank()) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        GhostButton(copy.cancel, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

private fun outcomeText(outcome: ApplyOutcome, copy: Copy): String =
    listOf(statusLabel(outcome.status, copy), outcome.error)
        .filter { it.isNotBlank() }.joinToString(" · ")

private fun statusLabel(status: String, copy: Copy): String = when (status) {
    ApplyStatus.CONFIRMED -> copy.confirmed
    ApplyStatus.SENT -> copy.sent
    ApplyStatus.UNKNOWN -> copy.unknown
    ApplyStatus.FAILED -> copy.failed
    else -> copy.skipped
}

// ── Комнаты ─────────────────────────────────────────────────────────────────

@Composable
private fun RoomGroup(
    room: String,
    devices: List<HomeDevice>,
    copy: Copy,
    results: Map<String, String>,
    onOpen: (HomeDevice) -> Unit,
    onToggle: (HomeDevice, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(room)
        GlassGroup {
            devices.forEach { device ->
                val canToggle = "power" in device.capabilities
                if (canToggle) {
                    SettingsActionRow(
                        title = device.name,
                        subtitle = stateSummary(device).ifBlank { copy.kindLabel(device.kind) },
                        onClick = { onOpen(device) },
                    )
                    SettingsToggleRow(
                        title = device.name,
                        subtitle = if (device.power()) copy.powerOn else copy.powerOff,
                        checked = device.power(),
                        onCheckedChange = { target -> onToggle(device, target) },
                    )
                } else {
                    SettingsActionRow(
                        title = device.name,
                        subtitle = stateSummary(device).ifBlank { copy.kindLabel(device.kind) },
                        value = null,
                        onClick = { onOpen(device) },
                    )
                }
                results[device.id]?.let { result ->
                    Text(
                        text = "${device.name} · $result",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

// ── Local-only private sharing consent ───────────────────────────────────────

@Composable
private fun SharingSection(sharing: HomeSharing, copy: SharingCopy, common: Copy) {
    val state by sharing.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember(sharing) { mutableStateOf(false) }
    var failed by remember(sharing) { mutableStateOf(false) }
    var pending by remember(sharing) { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun save(key: String, enabled: Boolean) {
        if (saving) return
        saving = true
        failed = false
        pending = key to enabled
        // This callback is only reachable through local settings controls, never a tool.
        scope.launch {
            try {
                withContext(Dispatchers.IO) { sharing.update(JSONObject().put(key, enabled)) }
                pending = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionTitle(copy.title)
        Text(copy.hint, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassGroup {
            SettingsToggleRow(
                title = copy.context, subtitle = copy.contextHint, checked = state.shareContext,
                enabled = !saving, modifier = Modifier.heightIn(min = 48.dp),
                onCheckedChange = { save("shareContext", it) },
            )
            GlassDivider()
            SettingsToggleRow(
                title = copy.brain, subtitle = copy.brainHint, checked = state.allowBrain,
                enabled = !saving, modifier = Modifier.heightIn(min = 48.dp),
                onCheckedChange = { save("allowBrain", it) },
            )
            GlassDivider()
            SettingsToggleRow(
                title = copy.speaker, subtitle = copy.speakerHint, checked = state.allowSpeaker,
                enabled = !saving, modifier = Modifier.heightIn(min = 48.dp),
                onCheckedChange = { save("allowSpeaker", it) },
            )
            GlassDivider()
            SettingsToggleRow(
                title = copy.listener, subtitle = copy.listenerHint, checked = state.allowListener,
                enabled = !saving, modifier = Modifier.heightIn(min = 48.dp),
                onCheckedChange = { save("allowListener", it) },
            )
        }
        Text(copy.limits, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (saving || failed) {
            Text(
                text = if (saving) copy.saving else copy.failed,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (failed && !saving) {
            GhostButton(common.retry, onClick = {
                pending?.let { (key, enabled) -> save(key, enabled) }
            })
        }
    }
}

// ── Сеть ────────────────────────────────────────────────────────────────────

@Composable
private fun NetworkSection(
    engine: HomeEngine,
    refresh: Int,
    nodes: List<MeshNode>,
    copy: Copy,
    maySleep: Boolean,
    vote: VoteResult?,
    onVote: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var membership by remember(engine) { mutableStateOf<JSONObject?>(null) }
    var invitation by remember(engine) { mutableStateOf("") }
    var exportedInvite by remember(engine) { mutableStateOf<String?>(null) }
    var updating by remember(engine) { mutableStateOf(false) }
    var failed by remember(engine) { mutableStateOf(false) }
    var confirmLeave by remember(engine) { mutableStateOf(false) }

    // Only this local human UI can export a secret. Never save it across recreation.
    suspend fun readMembership() {
        if (updating) return
        updating = true
        failed = false
        exportedInvite = null
        try {
            membership = withContext(Dispatchers.IO) { engine.mesh.membership() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            membership = null
            failed = true
        } finally {
            updating = false
        }
    }

    LaunchedEffect(engine, refresh) { readMembership() }

    fun perform(operation: () -> String?) {
        if (updating) return
        updating = true
        failed = false
        exportedInvite = null
        scope.launch {
            try {
                val (current, revealed) = withContext(Dispatchers.IO) {
                    val result = operation()
                    engine.mesh.membership() to result
                }
                // Refresh before re-enabling actions: creation is visible without waiting for announce.
                membership = current
                invitation = ""
                exportedInvite = revealed.takeIf { current.optBoolean("joined") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Exceptions may contain an invitation. Only show localized, generic text.
                failed = true
                try {
                    membership = withContext(Dispatchers.IO) { engine.mesh.membership() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    membership = null
                }
            } finally {
                updating = false
                confirmLeave = false
            }
        }
    }

    val joined = membership?.optBoolean("joined") == true
    GlassCard(contentPadding = Spacing.md) {
        if (updating) {
            Text(
                text = copy.membershipBusy,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (failed) {
            Text(
                text = copy.membershipFailed,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (membership == null && !updating) {
            PrimaryButton(copy.retry, onClick = { scope.launch { readMembership() } }, modifier = Modifier.fillMaxWidth())
        } else if (membership != null && !joined) {
            Text(copy.noMembership, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Spacing.sm))
            PrimaryButton(
                copy.createHome,
                onClick = { perform { engine.mesh.createHome(); null } },
                enabled = !updating,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassField(
                label = copy.invitation,
                value = invitation,
                secret = true,
                enabled = !updating,
                onValueChange = { invitation = it },
            )
            Text(copy.inviteWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.sm))
            PrimaryButton(
                copy.joinHome,
                onClick = {
                    val invite = invitation.trim()
                    perform { engine.mesh.joinHome(invite); null }
                },
                enabled = !updating && invitation.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (joined) {
            SelectionContainer {
                Text(
                    text = "${copy.homeId}: ${membership?.optString("home_id").orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "${copy.nodeName}: ${membership?.optString("name").orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(copy.inviteWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.sm))
            if (exportedInvite == null) {
                PrimaryButton(
                    copy.revealInvite,
                    onClick = { perform { engine.mesh.exportInvite() } },
                    enabled = !updating,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Long-press selection allows manual transfer, with no automatic clipboard access.
                SelectionContainer {
                    Text(exportedInvite.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                GhostButton(copy.hideInvite, onClick = { exportedInvite = null }, modifier = Modifier.fillMaxWidth())
            }
            PrimaryButton(
                copy.leaveHome,
                onClick = { exportedInvite = null; confirmLeave = true },
                destructive = true,
                enabled = !updating,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (joined) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = copy.meshHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            nodes.forEach { node ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
                    Icon(
                        imageVector = if (node.online()) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                        contentDescription = if (node.online()) copy.online else copy.offline,
                        tint = if (node.online()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text(
                            text = if (node.self) "${node.name} · ${copy.thisNode}" else node.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = listOf("${copy.priority} ${node.priority}", node.role, node.platform)
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (node.services.isNotEmpty()) {
                            Text(
                                text = node.services.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            vote?.let { result ->
                Text(
                    text = "${result.winnerName} · ${result.participants}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (result.localOnly) {
                    Text(
                        text = copy.meshLocalOnly,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
            }
            if (maySleep) {
                Text(
                    text = copy.meshLocalOnly,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
            }
            GhostButton(copy.checkVote, onClick = { if (!updating) onVote() }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (confirmLeave) {
        GlassDialog(
            title = copy.leaveHome,
            message = copy.leaveConfirm,
            confirmLabel = copy.leaveHome,
            dismissLabel = copy.cancel,
            destructive = true,
            loading = updating,
            onConfirm = { perform { engine.mesh.leaveHome(); null } },
            onDismiss = { if (!updating) confirmLeave = false },
        )
    }
}

// ── Coordination preferences (metadata, not remote voice activation) ─────────

@Composable
private fun CoordinationSection(
    engine: HomeEngine,
    nodes: List<MeshNode>,
    copy: Copy,
    text: CoordinationCopy,
) {
    val scope = rememberCoroutineScope()
    var open by rememberSaveable(engine) { mutableStateOf(false) }
    // JSON is saveable across recreation. Never key a draft to node announcements or refreshes.
    var draftJson by rememberSaveable(engine) { mutableStateOf<String?>(null) }
    var baselineJson by rememberSaveable(engine) { mutableStateOf<String?>(null) }
    var choosingRole by rememberSaveable(engine) { mutableStateOf<String?>(null) }
    var saved by rememberSaveable(engine) { mutableStateOf(false) }
    var saving by remember(engine) { mutableStateOf(false) }
    var reading by remember(engine) { mutableStateOf(false) }
    var failed by remember(engine) { mutableStateOf(false) }

    suspend fun readPreferences() {
        if (reading || saving || draftJson != null) return
        reading = true
        failed = false
        try {
            val loaded = withContext(Dispatchers.IO) {
                CoordinationPreferences.fromJson(engine.mesh.coordination.preferences()).toJson().toString()
            }
            baselineJson = loaded
            draftJson = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            reading = false
        }
    }

    LaunchedEffect(engine, open) { if (open) readPreferences() }
    val draft = remember(draftJson) { draftJson?.let { CoordinationPreferences.fromJson(JSONObject(it)) } }

    fun edit(next: CoordinationPreferences) {
        draftJson = next.toJson().toString()
        saved = false
        // Keep a save error visible until the next explicit save attempt.
    }

    fun cancel() {
        if (!saving) {
            open = false
            choosingRole = null
            draftJson = null
            baselineJson = null
            failed = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SectionTitle(text.title)
        GlassGroup {
            SettingsActionRow(
                title = text.title,
                subtitle = text.pipelineNotice,
                onClick = { saved = false; open = true },
            )
        }
        if (saved) {
            Text(
                text.saved,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (open && choosingRole == null) {
        GlassDialogShell(title = text.title, onDismiss = { cancel() }) {
            Text(text.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xs))
            Text(text.pipelineNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reading) {
                SkeletonList(items = 2, modifier = Modifier.padding(vertical = Spacing.sm)
                    .semantics { contentDescription = copy.loading })
            }
            if (failed) {
                Text(
                    text.failed,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = Spacing.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (draft == null) {
                    PrimaryButton(copy.retry, onClick = { scope.launch { readPreferences() } }, enabled = !reading)
                }
            }
            if (draft != null) {
                SectionTitle(text.mode)
                GlassGroup(Modifier.selectableGroup()) {
                    CoordinationRadioRow(text.automatic, null, draft.mode == "automatic", !saving) {
                        edit(draft.copy(mode = "automatic"))
                    }
                    GlassDivider()
                    CoordinationRadioRow(text.manual, null, draft.mode == "manual", !saving) {
                        edit(draft.copy(mode = "manual"))
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                GlassGroup {
                    CoordinationPreferences.ROLE_NAMES.forEachIndexed { index, role ->
                        if (index > 0) GlassDivider()
                        val target = draft.target(role)
                        val node = nodes.firstOrNull { it.id == target }
                        val subtitle = when {
                            target.isEmpty() -> text.automatic
                            node == null -> "$target · ${copy.offline}"
                            else -> listOf(
                                node.name,
                                if (node.self) copy.thisNode else node.id,
                                if (node.online()) copy.online else copy.offline,
                                text.incompatible.takeIf { !node.self && node.coordinationVersion != HomeCoordination.VERSION },
                            ).filterNotNull().joinToString(" · ")
                        }
                        // No disabled-looking no-op rows while a write is in flight.
                        if (saving) {
                            Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                                Text(text.roleLabel(role), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            SettingsActionRow(
                                title = text.roleLabel(role), subtitle = subtitle,
                                onClick = { choosingRole = role },
                            )
                        }
                        if (target.isNotEmpty() && (node == null || !node.online())) {
                            Text(
                                text = "${text.roleLabel(role)}: ${text.unavailable}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton(
                    text = copy.save,
                    enabled = !saving && draftJson != baselineJson,
                    loading = saving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!saving) {
                            saving = true
                            failed = false
                            // Snapshot the draft, then merge only selector fields into fresh preferences.
                            // Other clients may edit roles while this human is choosing nodes.
                            val submitted = draft
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        synchronized(engine.mesh) {
                                            val current = CoordinationPreferences.fromJson(engine.mesh.coordination.preferences())
                                            val next = current.copy(
                                                mode = submitted.mode, brain = submitted.brain,
                                                listener = submitted.listener, executor = submitted.executor,
                                                responder = submitted.responder,
                                            )
                                            CoordinationPreferences.fromJson(
                                                engine.mesh.coordination.updatePreferences(next.toJson()),
                                            ).toJson().toString()
                                        }
                                    }
                                    saved = true
                                    open = false
                                    choosingRole = null
                                    // Next open reads fresh state; errors leave the draft untouched.
                                    draftJson = null
                                    baselineJson = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    failed = true
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                )
            }
            if (!saving) GhostButton(copy.cancel, onClick = { cancel() }, modifier = Modifier.fillMaxWidth())
        }
    }

    val role = choosingRole
    if (open && draft != null && role != null) {
        val target = draft.target(role)
        fun select(id: String) {
            val next = draft.toJson().put(role, id)
            edit(CoordinationPreferences.fromJson(next))
            choosingRole = null
        }
        GlassDialogShell(title = text.roleLabel(role), onDismiss = { choosingRole = null }) {
            // The shell scrolls its text; the bounded keyed list virtualizes up to 128 peers.
            GlassGroup {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = Spacing.huge * 6).selectableGroup(),
                ) {
                    item(key = "automatic", contentType = "choice") {
                        CoordinationRadioRow(text.automatic, null, target.isEmpty(), !saving) { select("") }
                        GlassDivider()
                    }
                    if (target.isNotEmpty() && nodes.none { it.id == target }) {
                        item(key = "missing-$target", contentType = "choice") {
                            CoordinationRadioRow(target, text.unavailable, true, false) {}
                            GlassDivider()
                        }
                    }
                    items(nodes, key = { "node-${it.id}" }, contentType = { "choice" }) { node ->
                        val compatible = node.self || node.coordinationVersion == HomeCoordination.VERSION
                        CoordinationRadioRow(
                            title = node.name,
                            subtitle = listOf(
                                node.id,
                                copy.thisNode.takeIf { node.self },
                                if (node.online()) copy.online else copy.offline,
                                text.incompatible.takeIf { !compatible },
                            ).filterNotNull().joinToString(" · "),
                            selected = target == node.id,
                            enabled = !saving && node.online() && compatible,
                            onClick = { select(node.id) },
                        )
                        GlassDivider()
                    }
                }
            }
            if (target.isNotEmpty() && nodes.none { it.id == target && it.online() }) {
                Text(text.unavailable, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            GhostButton(copy.back, onClick = { choosingRole = null }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Flat radio rows share one glass group, with native selection and disabled semantics. */
@Composable
private fun CoordinationRadioRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Карточка устройства ─────────────────────────────────────────────────────

@Composable
private fun DeviceSheet(device: HomeDevice, engine: HomeEngine, copy: Copy, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember(device.id) { mutableStateOf("") }
    var confirmDelete by remember(device.id) { mutableStateOf(false) }
    var deleting by remember(device.id) { mutableStateOf(false) }

    fun command(action: String, params: Map<String, Any?> = emptyMap()) {
        scope.launch {
            status = try {
                outcomeText(engine.deviceAction(device.id, action, params), copy)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                copy.failed
            }
        }
    }

    GlassDialogShell(title = device.name, onDismiss = { if (!deleting) onDismiss() }) {
        Text(
            text = listOf(copy.kindLabel(device.kind), copy.adapterLabel(device.adapter))
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))

        if ("power" in device.capabilities) {
            SettingsToggleRow(
                title = if (device.power()) copy.powerOn else copy.powerOff,
                subtitle = null,
                checked = device.power(),
                onCheckedChange = { target ->
                    command(if (target) Action.ON else Action.OFF)
                },
            )
        }

        SliderBlock(
            key = "brightness",
            device = device,
            label = "0..100",
            suffix = "%",
            range = 0f..100f,
            onApply = { value ->
                command(Action.SET, mapOf("brightness" to value))
            },
        )
        SliderBlock(
            key = "color_temp",
            device = device,
            label = "K",
            suffix = " K",
            range = 1500f..9000f,
            onApply = { value ->
                command(Action.SET, mapOf("color_temp" to value))
            },
        )
        SliderBlock(
            key = "temperature",
            device = device,
            label = "°C",
            suffix = " °C",
            range = 5f..35f,
            onApply = { value ->
                command(Action.SET, mapOf("temperature" to value))
            },
        )
        SliderBlock(
            key = "volume",
            device = device,
            label = "%",
            suffix = "%",
            range = 0f..100f,
            onApply = { value ->
                command(Action.SET, mapOf("volume" to value))
            },
        )
        SliderBlock(
            key = "position",
            device = device,
            label = "%",
            suffix = "%",
            range = 0f..100f,
            onApply = { value ->
                command(Action.SET, mapOf("position" to value))
            },
        )

        if (status.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        GhostButton(copy.delete, onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth())
    }

    if (confirmDelete) {
        GlassDialog(
            title = copy.delete,
            message = device.name,
            confirmLabel = copy.delete,
            dismissLabel = copy.cancel,
            destructive = true,
            loading = deleting,
            onConfirm = {
                if (!deleting) {
                    deleting = true
                    scope.launch {
                        try {
                            val removed = withContext(Dispatchers.IO) { engine.removeDevice(device.id) }
                            if (removed) onDismiss() else status = copy.failed
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = copy.failed
                        } finally {
                            deleting = false
                            confirmDelete = false
                        }
                    }
                }
            },
            onDismiss = { if (!deleting) confirmDelete = false },
        )
    }
}

@Composable
private fun SliderBlock(
    key: String,
    device: HomeDevice,
    label: String,
    suffix: String,
    range: ClosedFloatingPointRange<Float>,
    onApply: (Int) -> Unit,
) {
    if (key !in device.capabilities) return
    var value by remember(device.id, key, device.state[key]) {
        mutableStateOf((device.number(key)?.toFloat() ?: range.start).coerceIn(range.start, range.endInclusive))
    }
    GlassSlider(
        label = label,
        valueText = "${value.toInt()}$suffix",
        value = value,
        onValueChange = { value = it },
        valueRange = range,
        onValueChangeFinished = { onApply(value.toInt()) },
        description = null,
    )
    Spacer(Modifier.height(Spacing.xs))
}

// ── Добавление устройства ───────────────────────────────────────────────────

@Composable
private fun AddDeviceSheet(engine: HomeEngine, copy: Copy, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.LIGHT) }
    var adapter by remember { mutableStateOf(Adapter.SIM) }
    var address by remember { mutableStateOf("") }
    var entity by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }

    GlassDialogShell(title = copy.addDevice, onDismiss = { if (!saving) onDismiss() }) {
        GlassField(copy.name, name) { name = it }
        GlassField(copy.room, room) { room = it }

        Text(
            text = copy.kind,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xxs),
        )
        GlassGroup {
            Kind.all.forEach { option ->
                SettingsValueRow(
                    title = copy.kindLabel(option),
                    subtitle = null,
                    selected = kind == option,
                    onClick = { kind = option },
                )
            }
        }

        Text(
            text = copy.adapter,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xxs),
        )
        GlassGroup {
            Adapter.all.forEach { option ->
                SettingsValueRow(
                    title = copy.adapterLabel(option),
                    subtitle = null,
                    selected = adapter == option,
                    onClick = { adapter = option },
                )
            }
        }

        if (adapter != Adapter.SIM) {
            GlassField(copy.address, address) { address = it }
        }
        if (adapter == Adapter.HOME_ASSISTANT) {
            GlassField(copy.entity, entity) { entity = it }
        }
        if (adapter == Adapter.HOME_ASSISTANT || adapter == Adapter.HTTP) {
            GlassField(copy.token, token, secret = true) { token = it }
        }

        if (saveError) {
            Text(
                text = copy.failed,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        PrimaryButton(
            text = copy.save,
            onClick = {
                if (!saving) {
                    saving = true
                    saveError = false
                    val newName = name
                    val newRoom = room
                    val newKind = kind
                    val newAdapter = adapter
                    val newAddress = address
                    val newEntity = entity
                    val newToken = token
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                engine.addDevice(
                                    name = newName,
                                    room = newRoom,
                                    kind = newKind,
                                    adapter = newAdapter,
                                    address = newAddress,
                                    entity = newEntity,
                                    token = newToken,
                                )
                            }
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            saveError = true
                        } finally {
                            saving = false
                        }
                    }
                }
            },
            enabled = name.isNotBlank() && !saving,
            loading = saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xxs))
        if (!saving) {
            GhostButton(copy.cancel, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Текстовое поле в языке стекла.
 *
 * В хосте нет публичного «glass text field», а Material-поле выпадало бы из
 * визуальной системы плагина. BasicTextField + токены типографики дают то же
 * поведение без чужих цветов и размеров.
 */
@Composable
private fun GlassField(
    label: String,
    value: String,
    secret: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
            value = value,
            enabled = enabled,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xxs)
                .glassSurface(shape = RoundedCornerShape(Radius.sm))
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                if (value.isBlank()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                inner()
            },
        )
    }
}