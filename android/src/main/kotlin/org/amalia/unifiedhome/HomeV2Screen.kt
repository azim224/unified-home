package org.amalia.unifiedhome

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.my.amali.plugin.ScreenHost
import com.my.amali.ui.components.*
import com.my.amali.ui.theme.Radius
import com.my.amali.ui.theme.Spacing
import com.my.amali.ui.theme.glassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amalia.unifiedhome.v2.HomeV2Node

private data class V2Copy(val words: List<String>) {
    operator fun get(index: Int) = words[index]
}
private fun v2Copy(language: String): V2Copy = V2Copy((when (language) {
    "ru" -> "Дом v2|Назад|Создать дом|Вступить в дом|Приглашение|Показать приглашение|Скрыть приглашение|Покинуть дом|Подтвердить|Отмена|Все участники имеют полный доступ ко всем возможностям Амалии в этом доме, включая приватные данные и действия с последствиями, в пределах прав этого устройства. Передавай секрет только доверенным людям, не в чат. Отзыв доступа требует нового дома и повторного приглашения остальных. Это отдельный дом, не старое доверие v1.|Не в доме|Дом|Это устройство|Сеть работает|Сеть недоступна|Кандидаты поиска не являются доверенными. Проверка использует зашифрованное соединение; адрес сам по себе не даёт доступа.|Устройства|Нет устройств. Проверь сеть или введи адрес вручную.|ID устройства|Адрес|Порт|Проверить зашифрованно|Проверено|Не проверено|Каталог|Загрузить каталог|Следующая страница|Задача|ID задачи|Прочитать задачу|Операция не выполнена. Проверь параметры, сеть и поддержку хоста. Повтора нет.|Выполняется…|Страница загружена|Записей нет|Обнаружение недоступно; используй ручную проверку.|Название возможности|Состояние|Выбрать"
    "ja" -> "ホーム v2|戻る|ホームを作成|参加|招待|招待を表示|招待を隠す|退出|確認|キャンセル|全メンバーは、この端末の権限内でプライベートデータや重大な操作を含む全機能にアクセスできます。秘密は信頼する人にのみ渡し、チャットに貼らないでください。失効には新しいホームと再招待が必要です。旧v1とは別の信頼です。|未参加|ホーム|この端末|接続中|未接続|検出候補は未信頼です。暗号化接続で確認します。アドレスだけでは権限を与えません。|端末|端末なし。ネットワークを確認するか手動で入力してください。|端末ID|アドレス|ポート|暗号化で確認|確認済み|未確認|カタログ|カタログを取得|次のページ|タスク|タスクID|タスクを確認|操作できません。入力、ネットワーク、ホスト対応を確認してください。再試行しません。|処理中…|ページ取得済み|項目なし|検出不可。手動確認を使用してください。|機能名|状態|選択"
    "de" -> "Zuhause v2|Zurück|Zuhause erstellen|Beitreten|Einladung|Einladung anzeigen|Einladung verbergen|Verlassen|Bestätigen|Abbrechen|Alle Mitglieder erhalten vollen Zugriff auf alle Amalia-Funktionen einschließlich privater Daten und folgenreicher Aktionen innerhalb der Rechte dieses Geräts. Geheimnis nur vertrauenswürdigen Personen geben, nie im Chat. Widerruf erfordert ein neues Zuhause und neue Einladungen. Dieses Vertrauen ist von v1 getrennt.|Nicht beigetreten|Zuhause|Dieses Gerät|Verbunden|Nicht verbunden|Suchkandidaten sind nicht vertrauenswürdig. Prüfung erfolgt verschlüsselt; eine Adresse gewährt keinen Zugriff.|Geräte|Keine Geräte. Netzwerk prüfen oder Adresse eingeben.|Geräte-ID|Adresse|Port|Verschlüsselt prüfen|Geprüft|Ungeprüft|Katalog|Katalog laden|Nächste Seite|Aufgabe|Aufgaben-ID|Aufgabe lesen|Aktion fehlgeschlagen. Eingaben, Netzwerk und Host prüfen. Keine Wiederholung.|Wird ausgeführt…|Seite geladen|Keine Einträge|Suche nicht verfügbar; manuell prüfen.|Funktionsname|Status|Auswählen"
    "es" -> "Hogar v2|Atrás|Crear hogar|Unirse|Invitación|Mostrar invitación|Ocultar invitación|Salir|Confirmar|Cancelar|Todos los miembros tienen acceso completo a las funciones de Amalia, incluidos datos privados y acciones con consecuencias, dentro de los permisos de este dispositivo. Comparte el secreto solo con personas de confianza, nunca en el chat. Revocarlo requiere otro hogar y nuevas invitaciones. Esta confianza es independiente de v1.|Sin hogar|Hogar|Este dispositivo|Conectado|Sin conexión|Los candidatos no son de confianza. La comprobación es cifrada; una dirección no otorga acceso.|Dispositivos|Sin dispositivos. Revisa la red o introduce una dirección.|ID del dispositivo|Dirección|Puerto|Comprobar con cifrado|Verificado|Sin verificar|Catálogo|Cargar catálogo|Página siguiente|Tarea|ID de tarea|Consultar tarea|No se completó la operación. Revisa los datos, la red y el host. Sin reintentos.|Procesando…|Página cargada|Sin entradas|Descubrimiento no disponible; comprueba manualmente.|Nombre de función|Estado|Seleccionar"
    "fr" -> "Maison v2|Retour|Créer une maison|Rejoindre|Invitation|Afficher l’invitation|Masquer l’invitation|Quitter|Confirmer|Annuler|Tous les membres ont accès à toutes les fonctions d’Amalia, données privées et actions à conséquences comprises, dans les droits de cet appareil. Ne partagez le secret qu’avec des personnes de confiance, jamais dans le chat. Révoquer exige une nouvelle maison et de nouvelles invitations. Cette confiance est distincte de v1.|Non membre|Maison|Cet appareil|Connecté|Hors ligne|Les candidats ne sont pas fiables. La vérification est chiffrée ; une adresse ne donne aucun droit.|Appareils|Aucun appareil. Vérifiez le réseau ou saisissez une adresse.|ID de l’appareil|Adresse|Port|Vérifier avec chiffrement|Vérifié|Non vérifié|Catalogue|Charger le catalogue|Page suivante|Tâche|ID de tâche|Consulter la tâche|Opération non aboutie. Vérifiez les champs, le réseau et l’hôte. Aucun nouvel essai.|En cours…|Page chargée|Aucune entrée|Découverte indisponible ; vérifiez manuellement.|Nom de fonction|État|Sélectionner"
    "zh" -> "家庭 v2|返回|创建家庭|加入家庭|邀请|显示邀请|隐藏邀请|退出家庭|确认|取消|所有成员在本机权限范围内可完整访问 Amalia 的所有功能，包括私人数据和可能产生后果的操作。只向可信的人传递密钥，切勿发到聊天中。撤销权限需要新建家庭并重新邀请其他成员。这与旧版 v1 的信任独立。|未加入|家庭|本机|已连接|未连接|发现的候选设备尚不可信。验证使用加密连接，地址本身不授予权限。|设备|没有设备。检查网络或手动输入地址。|设备 ID|地址|端口|加密验证|已验证|未验证|目录|加载目录|下一页|任务|任务 ID|查询任务|操作未完成。检查输入、网络及主机支持。不会重试。|处理中…|页面已加载|没有条目|发现不可用，请手动验证。|功能名称|状态|选择"
    "hi" -> "घर v2|वापस|घर बनाएँ|जुड़ें|आमंत्रण|आमंत्रण दिखाएँ|आमंत्रण छिपाएँ|घर छोड़ें|पुष्टि|रद्द करें|सभी सदस्यों को इस डिवाइस की अनुमतियों के भीतर निजी डेटा और प्रभाव वाली कार्रवाइयों सहित Amalia की सभी क्षमताओं का पूरा उपयोग मिलता है। गुप्त जानकारी केवल विश्वसनीय लोगों को दें, चैट में नहीं। पहुँच हटाने के लिए नया घर और नए आमंत्रण चाहिए। यह पुराने v1 से अलग भरोसा है।|नहीं जुड़े|घर|यह डिवाइस|जुड़ा हुआ|ऑफ़लाइन|खोज के उम्मीदवार विश्वसनीय नहीं हैं। जाँच एन्क्रिप्टेड है; पता अपने आप पहुँच नहीं देता।|डिवाइस|कोई डिवाइस नहीं। नेटवर्क जाँचें या पता दर्ज करें।|डिवाइस ID|पता|पोर्ट|एन्क्रिप्टेड जाँच|सत्यापित|असत्यापित|कैटलॉग|कैटलॉग लोड करें|अगला पृष्ठ|कार्य|कार्य ID|कार्य पढ़ें|कार्रवाई पूरी नहीं हुई। इनपुट, नेटवर्क और होस्ट समर्थन जाँचें। पुनः प्रयास नहीं होगा।|कार्य जारी…|पृष्ठ लोड हुआ|कोई प्रविष्टि नहीं|खोज उपलब्ध नहीं; मैन्युअल जाँच करें।|क्षमता का नाम|स्थिति|चुनें"
    "ar" -> "المنزل v2|رجوع|إنشاء منزل|انضمام|دعوة|إظهار الدعوة|إخفاء الدعوة|مغادرة|تأكيد|إلغاء|لكل الأعضاء وصول كامل إلى قدرات أماليا بما فيها البيانات الخاصة والإجراءات ذات العواقب ضمن أذونات هذا الجهاز. شارك السر مع الموثوقين فقط وليس في المحادثة. يتطلب سحب الوصول منزلاً جديداً وإعادة دعوة الآخرين. هذه ثقة منفصلة عن v1 القديم.|غير منضم|المنزل|هذا الجهاز|متصل|غير متصل|مرشحو الاكتشاف غير موثوقين. الفحص مشفر؛ العنوان وحده لا يمنح الوصول.|الأجهزة|لا أجهزة. تحقق من الشبكة أو أدخل العنوان يدوياً.|معرّف الجهاز|العنوان|المنفذ|فحص مشفر|تم التحقق|غير متحقق|الكتالوج|تحميل الكتالوج|الصفحة التالية|المهمة|معرّف المهمة|قراءة المهمة|لم تكتمل العملية. تحقق من المدخلات والشبكة ودعم المضيف. لا إعادة تلقائية.|جارٍ التنفيذ…|تم تحميل الصفحة|لا عناصر|الاكتشاف غير متاح؛ استخدم الفحص اليدوي.|اسم القدرة|الحالة|اختيار"
    else -> "Home v2|Back|Create home|Join home|Invitation|Show invitation|Hide invitation|Leave home|Confirm|Cancel|Every member has full access to all Amalia capabilities, including private data and consequential actions, within this device’s existing permissions. Share the secret only with trusted people, never in chat. Revocation requires a new home and reinviting the remaining members. This trust is separate from legacy v1.|Not joined|Home|This device|Connected|Offline|Discovery candidates are not trusted. Verification uses an encrypted connection; an address alone grants no access.|Devices|No devices. Check the network or enter an address manually.|Device ID|Address|Port|Verify encrypted connection|Verified|Unverified|Catalogue|Load catalogue|Next page|Task|Task ID|Read task|Operation did not complete. Check inputs, network and host support. No automatic retry.|Working…|Page loaded|No entries|Discovery unavailable; use manual verification.|Capability name|State|Select"
}).split('|'))

/** Focus outline supplements host glass buttons for keyboard and TV navigation. */
@Composable
private fun V2Button(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm)) else Modifier)
    if (enabled) GhostButton(text, onClick, modifier)
    else Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(Spacing.md))
}

@Composable
private fun V2Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value, onChange, singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.hasFocus }
                .glassSurface(shape = RoundedCornerShape(Radius.sm))
                .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm)) else Modifier)
                .padding(Spacing.md).semantics { contentDescription = label })
    }
}

@Composable
fun HomeV2Screen(node: HomeV2Node, host: ScreenHost,
    pairing: org.amalia.unifiedhome.v2.HomeV2Pairing? = null, membershipChanged: suspend () -> Unit) {
    val language = LocalConfiguration.current.locales[0].language
    val copy = remember(language) { v2Copy(language) }
    val status by node.status.collectAsStateWithLifecycle()
    val peers by node.peers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47655") }
    var selected by remember { mutableStateOf<String?>(null) }
    var taskId by remember { mutableStateOf("") }
    var catalogue by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var task by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val peer = peers.firstOrNull { it.nodeId == selected && it.authenticated }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    suspend fun request(operation: String, args: org.json.JSONObject): org.json.JSONObject {
        val trusted = peer ?: error("no_peer")
        val result = node.request(trusted.address, trusted.port, trusted.nodeId, operation, args)
        check(result.ok)
        return result.data
    }
    // No secret in saved state, no automatic export, and clear export on route disposal/backgrounding.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { exported = null; invite = ""; confirmation = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); exported = null; invite = "" }
    }
    LaunchedEffect(status.homeId) { exported = null; selected = null; catalogue = null; task = null }
    LaunchedEffect(selected) { catalogue = null; task = null; taskId = "" }
    AmaliaScreen(title = copy[0], onBack = host::goBack, backLabel = copy[1]) { insets ->
        LazyColumn(contentPadding = insets, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (pairing != null) item("automatic-pairing") {
                HomeV2PairingPanel(pairing, status.homeId != null) { invitation ->
                    node.joinHome(invitation)
                    membershipChanged()
                }
            }
            item("membership") {
                GlassCard(contentPadding = Spacing.md) {
                    Text(if (status.homeId == null) copy[11] else "${copy[12]} · ${status.homeId}", style = MaterialTheme.typography.bodyLarge)
                    Text("${copy[13]} · ${node.deviceName}", style = MaterialTheme.typography.bodyLarge)
                    Text(status.nodeId, style = MaterialTheme.typography.bodyMedium)
                    Text(if (status.running) copy[14] else copy[15], style = MaterialTheme.typography.bodyMedium)
                    if (status.homeId == null) {
                        V2Field(copy[4], invite, secret = true) { invite = it }
                        V2Button(copy[2], !busy) { confirmation = "create" }
                        V2Button(copy[3], !busy && invite.isNotBlank()) { confirmation = "join" }
                    } else {
                        Text(copy[10], style = MaterialTheme.typography.bodyMedium)
                        V2Button(if (exported == null) copy[5] else copy[6], !busy) {
                            if (exported != null) exported = null else action { exported = node.exportInvitation() }
                        }
                        if (exported != null) SelectionContainer { Text(exported.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
                        else Text("••••••••", style = MaterialTheme.typography.bodyMedium)
                        V2Button(copy[7], !busy) { exported = null; confirmation = "leave" }
                    }
                }
            }
            if (confirmation != null) item("confirmation") {
                GlassCard(contentPadding = Spacing.md) {
                    Text(copy[10], style = MaterialTheme.typography.bodyMedium)
                    V2Button(copy[8], !busy) { val operation = confirmation; confirmation = null
                        action {
                            when (operation) { "create" -> node.createHome(); "join" -> node.joinHome(invite.trim()); "leave" -> node.leaveHome() }
                            invite = ""; exported = null; membershipChanged()
                        }
                    }
                    V2Button(copy[9], !busy) { confirmation = null }
                }
            }
            if (busy) item("loading") { SkeletonList(items = 2) }
            if (failed || status.error != null) item("error") {
                Text(copy[31], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (status.homeId != null) {
                item("discovery") { GlassCard(contentPadding = Spacing.md) {
                    SectionTitle(copy[17]); Text(copy[16], style = MaterialTheme.typography.bodyMedium)
                    if (!status.discoveryAvailable) Text(copy[35], style = MaterialTheme.typography.bodyMedium)
                    if (peers.isEmpty()) Text(copy[18], style = MaterialTheme.typography.bodyMedium)
                    V2Field(copy[19], target) { target = it }
                    V2Field(copy[20], address) { address = it }
                    V2Field(copy[21], port) { port = it }
                    V2Button(copy[22], !busy && target.matches(Regex("[0-9a-f]{32}")) && (port.toIntOrNull() ?: 0) in 1..65535 && address.isNotBlank()) {
                        action { val result = node.request(address.trim(), port.toInt(), target, "node.describe")
                            check(result.ok); selected = target }
                    }
                } }
                items(peers, key = { it.nodeId }, contentType = { "peer" }) { candidate ->
                    GlassCard(contentPadding = Spacing.md) {
                        Text(candidate.name.ifBlank { candidate.platform.ifBlank { copy[19] } }, style = MaterialTheme.typography.bodyLarge)
                        Text("${candidate.nodeId} · ${candidate.address}:${candidate.port}", style = MaterialTheme.typography.bodyMedium)
                        Text(if (candidate.authenticated) copy[23] else copy[24], style = MaterialTheme.typography.bodyMedium)
                        V2Button(if (candidate.authenticated) copy[38] else copy[22], !busy) {
                            if (candidate.authenticated) selected = candidate.nodeId else action {
                                val result = node.request(candidate.address, candidate.port, candidate.nodeId, "node.describe")
                                check(result.ok); selected = candidate.nodeId
                            }
                        }
                    }
                }
                if (peer != null) item("peer-data") { GlassCard(contentPadding = Spacing.md) {
                    Text(peer.nodeId, style = MaterialTheme.typography.bodyLarge)
                    SectionTitle(copy[25])
                    V2Button(copy[26], !busy) { action { catalogue = request("capabilities.list", org.json.JSONObject()) } }
                    val entries = catalogue?.optJSONArray("capabilities")
                    if (catalogue != null) Text(copy[33], style = MaterialTheme.typography.bodyMedium)
                    if (entries != null) {
                        if (entries.length() == 0) Text(copy[34], style = MaterialTheme.typography.bodyMedium)
                        for (i in 0 until entries.length()) {
                            // Only capability identity, never arbitrary descriptions, results or secret-bearing payloads.
                            Text("${copy[36]} · ${entries.optJSONObject(i)?.optString("name").orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val cursor = catalogue?.opt("next_cursor") as? String
                    if (!cursor.isNullOrEmpty()) V2Button(copy[27], !busy) {
                        action { catalogue = request("capabilities.list", org.json.JSONObject().put("cursor", cursor)) }
                    }
                    SectionTitle(copy[28]); V2Field(copy[29], taskId) { taskId = it }
                    V2Button(copy[30], !busy && taskId.matches(Regex("[0-9a-f]{32}"))) {
                        action { task = request("tasks.get", org.json.JSONObject().put("task_id", taskId)) }
                    }
                    task?.let { Text("${copy[37]} · ${v2TaskState(it.optString("state"), language)}", style = MaterialTheme.typography.bodyMedium) }
                } }
            }
        }
    }
}

private fun v2TaskState(state: String, language: String): String {
    val states = when (language) {
        "ru" -> "В очереди|Выполняется|Завершено|Ошибка|Отменено|Неизвестно"
        "ja" -> "待機中|実行中|成功|エラー|取消済み|不明"
        "de" -> "Wartend|Läuft|Erfolgreich|Fehler|Abgebrochen|Unbekannt"
        "es" -> "En cola|En curso|Completada|Error|Cancelada|Desconocido"
        "fr" -> "En attente|En cours|Réussie|Erreur|Annulée|Inconnu"
        "zh" -> "排队中|运行中|已成功|错误|已取消|未知"
        "hi" -> "कतार में|जारी|सफल|त्रुटि|रद्द|अज्ञात"
        "ar" -> "في الانتظار|قيد التنفيذ|نجحت|خطأ|ملغاة|غير معروف"
        else -> "Queued|Running|Succeeded|Error|Cancelled|Unknown"
    }
    return states.split('|')[listOf("queued", "running", "succeeded", "error", "cancelled", "unknown").indexOf(state).takeIf { it >= 0 } ?: 5]
}

internal fun homeV2Title(language: String): String = v2Copy(language)[0]
