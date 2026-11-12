package org.amalia.unifiedhome

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.my.amali.plugin.ScreenHost
import com.my.amali.ui.components.AmaliaScreen
import com.my.amali.ui.components.GhostButton
import com.my.amali.ui.components.GlassCard
import com.my.amali.ui.components.SectionTitle
import com.my.amali.ui.theme.Radius
import com.my.amali.ui.theme.Spacing
import com.my.amali.ui.theme.glassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amalia.unifiedhome.v2.HomeV2Control
import org.amalia.unifiedhome.v2.HomeV2Data
import org.amalia.unifiedhome.v2.HomeV2Node
import org.json.JSONObject

private enum class ManagementWord {
    TITLE, BACK, COORDINATION, VOTERS, PREFERRED, WARNING, CONFIGURE, ENABLED, DISABLED,
    SCHEDULING, WAITING, VOTES, REFRESH, REMOTE, DEVICES_EMPTY, SELECT, SELECTED,
    LOAD, NEXT, EMPTY, IMPORT, LOCAL, CAPTURE_ID, CAPTURE_LABEL, CAPTURE, APPLY,
    APPLY_WARNING, WORKING, ERROR, RETRY, DONE, UNAVAILABLE, VERSION, CONFIGURED, INVALID,
}

/** Only app copy lives here; provider values never enter a screen model or a string template. */
private fun managementCopy(language: String): List<String> = (when (language) {
    "ru" -> "Управление домом|Назад|Координация|Фиксированные ID голосующих (через запятую)|ID предпочтительного узла|Список и предпочтительный узел должны совпадать на всех узлах. Поиск устройств не задаёт кворум. После сохранения список нельзя менять на месте.|Сохранить и подключить|Включено|Выключено|Планирование разрешено|Ожидание кворума|Голоса / большинство|Обновить|Профили другого устройства|Нет проверенных устройств этого дома.|Выбрать|Выбрано|Загрузить профили|Следующая страница|Профилей нет|Импортировать выбранный профиль|Локальные профили|ID нового профиля|Название снимка|Сохранить текущий полный профиль хоста|Применить выбранный профиль|Применение изменит настройки этого устройства. Обработчик сохраняет резервную копию; секретные значения не показываются.|Выполняется…|Не выполнено. Проверьте сеть, параметры и поддержку хоста. Повторите явно.|Повторить|Готово|Вступите в дом и включите его перед управлением.|Версия|Настройка зафиксирована. Изменение требует нового дома; повторное подключение не меняет список.|Укажите 1–256 уникальных ID из 32 строчных шестнадцатеричных знаков, включая этот узел и предпочтительный узел."
    "ja" -> "ホーム管理|戻る|調整|固定投票端末ID（カンマ区切り）|優先端末ID|全端末で同じ投票リストと優先端末を設定してください。端末検出から定足数を決めません。保存後はリストを直接変更できません。|保存して接続|有効|無効|スケジュール可能|定足数を待機中|票数 / 過半数|更新|他の端末のプロファイル|このホームに確認済み端末がありません。|選択|選択済み|プロファイルを取得|次のページ|プロファイルなし|選択したプロファイルを取り込む|ローカルプロファイル|新しいプロファイルID|スナップショット名|ホストの現在の完全なプロファイルを保存|選択したプロファイルを適用|この端末の設定を変更します。処理側がバックアップを保持します。秘密の値は表示されません。|処理中…|完了できませんでした。ネットワーク、入力、ホスト対応を確認し、明示的に再試行してください。|再試行|完了|管理する前にホームに参加して有効にしてください。|バージョン|設定は固定されています。変更には新しいホームが必要です。再接続でリストは変わりません。|この端末と優先端末を含む、32桁の小文字16進IDを重複なしで1～256個指定してください。"
    "de" -> "Zuhause verwalten|Zurück|Koordination|Feste Wähler-IDs (kommagetrennt)|Bevorzugte Knoten-ID|Liste und bevorzugter Knoten müssen auf allen Knoten gleich sein. Gerätesuche definiert kein Quorum. Nach dem Speichern ist die Liste unveränderlich.|Speichern und verbinden|Aktiv|Inaktiv|Planung erlaubt|Warten auf Quorum|Stimmen / Mehrheit|Aktualisieren|Profile eines anderen Geräts|Keine geprüften Geräte dieses Zuhauses.|Auswählen|Ausgewählt|Profile laden|Nächste Seite|Keine Profile|Gewähltes Profil importieren|Lokale Profile|Neue Profil-ID|Name der Aufnahme|Aktuelles vollständiges Hostprofil sichern|Gewähltes Profil anwenden|Dies ändert die Einstellungen dieses Geräts. Der Handler bewahrt eine Sicherung auf; geheime Werte werden nicht angezeigt.|Wird ausgeführt…|Nicht abgeschlossen. Netzwerk, Eingaben und Host-Unterstützung prüfen und ausdrücklich erneut versuchen.|Erneut versuchen|Fertig|Vor der Verwaltung einem Zuhause beitreten und es aktivieren.|Version|Konfiguration festgelegt. Änderungen erfordern ein neues Zuhause; erneutes Verbinden ändert die Liste nicht.|1–256 eindeutige IDs mit 32 kleinen Hexadezimalzeichen angeben, einschließlich dieses und des bevorzugten Knotens."
    "es" -> "Gestionar hogar|Atrás|Coordinación|ID fijos de votantes (separados por comas)|ID del nodo preferido|La lista y el nodo preferido deben ser iguales en todos los nodos. El descubrimiento no define el cuórum. La lista no puede cambiarse tras guardarla.|Guardar y conectar|Activada|Desactivada|Programación permitida|Esperando cuórum|Votos / mayoría|Actualizar|Perfiles de otro dispositivo|No hay dispositivos verificados de este hogar.|Seleccionar|Seleccionado|Cargar perfiles|Página siguiente|Sin perfiles|Importar perfil seleccionado|Perfiles locales|ID del nuevo perfil|Nombre de la captura|Guardar el perfil completo actual del host|Aplicar perfil seleccionado|Esto cambia los ajustes de este dispositivo. El controlador conserva una copia de seguridad; los valores secretos no se muestran.|Procesando…|No se completó. Revisa red, datos y compatibilidad del host y vuelve a intentarlo explícitamente.|Reintentar|Listo|Únete a un hogar y actívalo antes de gestionarlo.|Versión|Configuración fijada. Cambiarla requiere otro hogar; reconectar no modifica la lista.|Introduce de 1 a 256 ID únicos de 32 caracteres hexadecimales minúsculos, incluidos este nodo y el preferido."
    "fr" -> "Gérer la maison|Retour|Coordination|ID fixes des votants (séparés par des virgules)|ID du nœud préféré|La liste et le nœud préféré doivent être identiques sur tous les nœuds. La découverte ne définit pas le quorum. La liste est immuable après enregistrement.|Enregistrer et connecter|Activée|Désactivée|Planification autorisée|En attente du quorum|Votes / majorité|Actualiser|Profils d’un autre appareil|Aucun appareil vérifié de cette maison.|Choisir|Sélectionné|Charger les profils|Page suivante|Aucun profil|Importer le profil sélectionné|Profils locaux|ID du nouveau profil|Nom de la capture|Enregistrer le profil complet actuel de l’hôte|Appliquer le profil sélectionné|Ceci modifie les paramètres de cet appareil. Le gestionnaire conserve une sauvegarde ; les valeurs secrètes ne sont pas affichées.|En cours…|Opération non terminée. Vérifiez le réseau, les champs et la prise en charge de l’hôte, puis réessayez explicitement.|Réessayer|Terminé|Rejoignez une maison et activez-la avant de la gérer.|Version|Configuration figée. Toute modification exige une nouvelle maison ; reconnecter ne change pas la liste.|Indiquez 1 à 256 ID uniques de 32 caractères hexadécimaux minuscules, incluant ce nœud et le nœud préféré."
    "zh" -> "家庭管理|返回|协调|固定投票节点 ID（以逗号分隔）|首选节点 ID|所有节点的列表和首选节点必须相同。发现设备不会定义法定人数。保存后不可直接修改列表。|保存并连接|已启用|已停用|允许调度|等待法定人数|票数 / 多数门槛|刷新|其他设备的配置|此家庭没有已验证的设备。|选择|已选择|加载配置|下一页|没有配置|导入所选配置|本地配置|新配置 ID|快照名称|保存主机当前的完整配置|应用所选配置|这将更改本机设置。处理程序会保留备份；不会显示秘密值。|处理中…|未完成。请检查网络、输入和主机支持，然后手动重试。|重试|完成|管理前请先加入家庭并启用。|版本|配置已固定。更改需要新建家庭；重新连接不会修改列表。|请输入 1–256 个不重复的 32 位小写十六进制 ID，包括本节点和首选节点。"
    "hi" -> "घर का प्रबंधन|वापस|समन्वय|निश्चित मतदाता ID (अल्पविराम से अलग)|पसंदीदा नोड ID|सभी नोड पर सूची और पसंदीदा नोड समान होने चाहिए। डिवाइस खोज कोरम तय नहीं करती। सहेजने के बाद सूची सीधे नहीं बदल सकती।|सहेजें और जोड़ें|सक्रिय|निष्क्रिय|शेड्यूलिंग अनुमत|कोरम की प्रतीक्षा|मत / बहुमत|ताज़ा करें|दूसरे डिवाइस के प्रोफ़ाइल|इस घर में कोई सत्यापित डिवाइस नहीं।|चुनें|चयनित|प्रोफ़ाइल लोड करें|अगला पृष्ठ|कोई प्रोफ़ाइल नहीं|चयनित प्रोफ़ाइल आयात करें|स्थानीय प्रोफ़ाइल|नए प्रोफ़ाइल की ID|स्नैपशॉट का नाम|होस्ट का मौजूदा पूरा प्रोफ़ाइल सहेजें|चयनित प्रोफ़ाइल लागू करें|इससे इस डिवाइस की सेटिंग बदलेंगी। हैंडलर बैकअप सुरक्षित रखता है; गुप्त मान नहीं दिखाए जाते।|कार्य जारी…|पूरा नहीं हुआ। नेटवर्क, इनपुट और होस्ट समर्थन जाँचें और स्पष्ट रूप से पुनः प्रयास करें।|पुनः प्रयास|पूर्ण|प्रबंधन से पहले घर से जुड़ें और उसे सक्रिय करें।|संस्करण|कॉन्फ़िगरेशन निश्चित है। बदलाव के लिए नया घर चाहिए; दोबारा जोड़ने से सूची नहीं बदलती।|इस नोड और पसंदीदा नोड सहित 32 छोटे हेक्स अक्षरों की 1–256 अद्वितीय ID दें।"
    "ar" -> "إدارة المنزل|رجوع|التنسيق|معرّفات المصوّتين الثابتة (مفصولة بفواصل)|معرّف العقدة المفضلة|يجب أن تتطابق القائمة والعقدة المفضلة في جميع العقد. الاكتشاف لا يحدد النصاب. لا يمكن تعديل القائمة مباشرة بعد الحفظ.|حفظ وربط|مفعّل|معطّل|الجدولة مسموحة|انتظار النصاب|الأصوات / الأغلبية|تحديث|ملفات جهاز آخر|لا توجد أجهزة متحققة في هذا المنزل.|اختيار|محدد|تحميل الملفات|الصفحة التالية|لا ملفات|استيراد الملف المحدد|الملفات المحلية|معرّف الملف الجديد|اسم اللقطة|حفظ ملف المضيف الحالي الكامل|تطبيق الملف المحدد|يغيّر هذا إعدادات هذا الجهاز. يحتفظ المعالج بنسخة احتياطية؛ لا تُعرض القيم السرية.|جارٍ التنفيذ…|لم تكتمل العملية. تحقق من الشبكة والمدخلات ودعم المضيف ثم أعد المحاولة صراحةً.|إعادة المحاولة|تم|انضم إلى منزل وفعّله قبل الإدارة.|الإصدار|الإعداد ثابت. يتطلب تغييره منزلاً جديداً؛ إعادة الربط لا تغيّر القائمة.|أدخل من 1 إلى 256 معرّفاً فريداً من 32 رمزاً سداسياً عشرياً صغيراً، تشمل هذه العقدة والعقدة المفضلة."
    else -> "Home management|Back|Coordination|Fixed voter IDs (comma-separated)|Preferred node ID|The list and preferred node must be identical on every node. Discovery does not define quorum. Once saved, the list cannot be changed in place.|Save and connect|Enabled|Disabled|Scheduling allowed|Waiting for quorum|Votes / majority|Refresh|Another device’s profiles|No verified devices in this home.|Select|Selected|Load profiles|Next page|No profiles|Import selected profile|Local profiles|New profile ID|Snapshot name|Capture current full host profile|Apply selected profile|This changes this device’s settings. The handler preserves a backup; secret values are never displayed.|Working…|Not completed. Check network, inputs and host support, then explicitly retry.|Retry|Done|Join a home and enable it before managing it.|Version|Configuration is fixed. Changes require a new home; reconnecting does not change the list.|Enter 1–256 unique IDs of 32 lowercase hexadecimal characters, including this node and the preferred node."
}).split('|')

private data class ManagementProfile(val id: String, val version: Long, val provider: String, val label: String)
private data class ManagementPage(
    val profiles: List<ManagementProfile>, val next: String?, val selectionRevision: Long,
)
private data class ManagementStatus(val enabled: Boolean, val scheduling: Boolean, val votes: Int, val majority: Int)

private fun managementPage(value: JSONObject): ManagementPage {
    val profiles = value.getJSONArray("profiles")
    return ManagementPage((0 until profiles.length()).map { index ->
        val row = profiles.getJSONObject(index)
        ManagementProfile(row.getString("id"), row.getLong("version"), row.getString("provider"), row.getString("label"))
    }, (value.opt("next_cursor") as? String)?.takeIf { it.isNotEmpty() },
        value.getJSONObject("selection").getLong("revision"))
}

@Composable
private fun ManagementButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm)) else Modifier)
    if (enabled) GhostButton(text, onClick, modifier)
    else Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(Spacing.md))
}

@Composable
private fun ManagementField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.hasFocus }
                .glassSurface(shape = RoundedCornerShape(Radius.sm))
                .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm)) else Modifier)
                .padding(Spacing.md).semantics { contentDescription = label })
    }
}

/**
 * Local human management only; do not register profile transfer/capture as a model tool.
 * onConfigured MUST stop the node, re-register the union of extension operations/handlers,
 * and restart/reconcile it in the parent. It must not configure the voters again.
 * Entry must supply a separate ScreenSpec; this file intentionally owns no navigation route.
 */
@Composable
fun HomeV2Management(
    node: HomeV2Node,
    control: HomeV2Control,
    data: HomeV2Data,
    host: ScreenHost,
    onConfigured: suspend () -> Unit,
) {
    val language = LocalConfiguration.current.locales[0].language
    val words = remember(language) { managementCopy(language) }
    fun text(word: ManagementWord) = words[word.ordinal]
    val membership by node.status.collectAsStateWithLifecycle()
    val peers by node.peers.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var votersCsv by remember(control) { mutableStateOf("") }
    var preferred by remember(control) { mutableStateOf("") }
    var coordination by remember(control) { mutableStateOf<ManagementStatus?>(null) }
    var statusFailed by remember(control) { mutableStateOf(false) }
    var reconnect by remember(control) { mutableStateOf(false) }
    var selectedPeer by remember(data) { mutableStateOf<String?>(null) }
    var remote by remember(data) { mutableStateOf<ManagementPage?>(null) }
    var remoteSelection by remember(data) { mutableStateOf<ManagementProfile?>(null) }
    var local by remember(data) { mutableStateOf<ManagementPage?>(null) }
    var localSelection by remember(data) { mutableStateOf<ManagementProfile?>(null) }
    var captureId by remember(data) { mutableStateOf("") }
    var captureLabel by remember(data) { mutableStateOf("") }
    var busy by remember(data, control) { mutableStateOf(false) }
    var failed by remember(data, control) { mutableStateOf(false) }
    var done by remember(data, control) { mutableStateOf(false) }
    // Retains only an operation closure and metadata, never a profile export or provider values.
    var retry by remember(data, control) { mutableStateOf<(suspend () -> Unit)?>(null) }
    val active = membership.homeId == data.homeId && membership.running
    val trustedPeers = peers.filter { it.authenticated && it.homeId == data.homeId && it.nodeId != membership.nodeId }
    val peer = trustedPeers.firstOrNull { it.nodeId == selectedPeer }
    fun checkHome() { check(node.status.value.homeId == data.homeId && node.status.value.running) }
    suspend fun localRequest(operation: String, args: JSONObject = JSONObject()): JSONObject {
        checkHome()
        return data.handle(operation, args, node.status.value.nodeId)
    }
    suspend fun refreshLocal(cursor: String? = null) {
        val args = JSONObject().put("limit", 50)
        if (cursor != null) args.put("cursor", cursor)
        val page = managementPage(localRequest("profiles.list", args))
        local = page; localSelection = null
    }
    suspend fun refreshStatus() {
        val value = withContext(Dispatchers.IO) { control.status() }
        coordination = ManagementStatus(value.optBoolean("enabled"), value.optBoolean("can_schedule"),
            value.optInt("collected_votes").coerceIn(0, 256), value.optInt("majority").coerceIn(0, 256))
        statusFailed = false
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false; done = false; retry = null
        scope.launch {
            try { checkHome(); block(); done = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; retry = block }
            finally { busy = false }
        }
    }
    // Read-only status polling stops with the screen; it never ticks, votes or retries mutations.
    LaunchedEffect(control, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { refreshStatus() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { statusFailed = true }
                delay(1000)
            }
        }
    }
    LaunchedEffect(membership.homeId, membership.running) {
        if (!active) {
            local = null; localSelection = null; remote = null; remoteSelection = null
            selectedPeer = null; retry = null; failed = false; done = false
        }
    }
    LaunchedEffect(selectedPeer, peer?.authenticated) { remote = null; remoteSelection = null }
    val voterIds = votersCsv.split(',').map { it.trim() }
    val validConfig = voterIds.size in 1..256 && voterIds.toSet().size == voterIds.size &&
        voterIds.all { it.matches(Regex("[0-9a-f]{32}")) } && membership.nodeId in voterIds && preferred.trim() in voterIds
    val enabled = active && !busy
    val configured = coordination?.enabled == true
    val currentOnConfigured by rememberUpdatedState(onConfigured)

    AmaliaScreen(title = text(ManagementWord.TITLE), onBack = host::goBack, backLabel = text(ManagementWord.BACK)) { insets ->
        LazyColumn(contentPadding = insets, modifier = Modifier.fillMaxWidth().imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (!active) item("unavailable") { Text(text(ManagementWord.UNAVAILABLE), style = MaterialTheme.typography.bodyMedium) }
            item("feedback") {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (busy) Text(text(ManagementWord.WORKING), style = MaterialTheme.typography.bodyMedium)
                    if (done) Text(text(ManagementWord.DONE), style = MaterialTheme.typography.bodyMedium)
                    if (failed) {
                        Text(text(ManagementWord.ERROR), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        ManagementButton(text(ManagementWord.RETRY), enabled && retry != null) { retry?.let { action(it) } }
                    }
                }
            }
            item("coordination") { GlassCard(contentPadding = Spacing.md) {
                SectionTitle(text(ManagementWord.COORDINATION))
                Text(text(ManagementWord.WARNING), style = MaterialTheme.typography.bodyMedium)
                coordination?.let { state ->
                    Text(text(if (state.enabled) ManagementWord.ENABLED else ManagementWord.DISABLED), style = MaterialTheme.typography.bodyLarge)
                    if (state.enabled) {
                        Text(text(if (state.scheduling) ManagementWord.SCHEDULING else ManagementWord.WAITING), style = MaterialTheme.typography.bodyMedium)
                        Text("${text(ManagementWord.VOTES)} · ${state.votes} / ${state.majority}", style = MaterialTheme.typography.bodyMedium)
                        Text(text(ManagementWord.CONFIGURED), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (statusFailed) Text(text(ManagementWord.ERROR), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                ManagementButton(text(ManagementWord.REFRESH), enabled) { action { refreshStatus() } }
                if (!configured) {
                    ManagementField(text(ManagementWord.VOTERS), votersCsv, enabled) { votersCsv = it.take(8500) }
                    ManagementField(text(ManagementWord.PREFERRED), preferred, enabled) { preferred = it.take(32) }
                    Text(text(ManagementWord.INVALID), style = MaterialTheme.typography.bodyMedium)
                }
                if (!configured || reconnect) ManagementButton(text(ManagementWord.CONFIGURE), enabled && (configured || validConfig)) {
                    val fixedIds = voterIds.toList(); val fixedPreferred = preferred.trim()
                    action {
                        if (!control.status().optBoolean("enabled")) {
                            control.configure(fixedIds, fixedPreferred)
                            reconnect = true
                        }
                        // Repeated after callback failure, without trying to overwrite fixed membership.
                        currentOnConfigured()
                        reconnect = false
                        refreshStatus()
                    }
                }
            } }
            item("remote-heading") { GlassCard(contentPadding = Spacing.md) {
                SectionTitle(text(ManagementWord.REMOTE))
                if (trustedPeers.isEmpty()) Text(text(ManagementWord.DEVICES_EMPTY), style = MaterialTheme.typography.bodyMedium)
            } }
            items(trustedPeers, key = { "peer:${it.nodeId}" }) { candidate -> GlassCard(contentPadding = Spacing.md) {
                Text(candidate.nodeId, style = MaterialTheme.typography.bodyMedium)
                ManagementButton(text(if (selectedPeer == candidate.nodeId) ManagementWord.SELECTED else ManagementWord.SELECT), enabled) {
                    selectedPeer = candidate.nodeId; remote = null; remoteSelection = null; retry = null; failed = false; done = false
                }
            } }
            if (peer != null) item("remote-profiles") { GlassCard(contentPadding = Spacing.md) {
                fun loadRemote(cursor: String? = null) {
                    val targetId = peer.nodeId
                    action {
                        val route = node.peers.value.first { it.nodeId == targetId && it.authenticated && it.homeId == data.homeId }
                        val args = JSONObject().put("limit", 50)
                        if (cursor != null) args.put("cursor", cursor)
                        val reply = node.request(route.address, route.port, route.nodeId, "profiles.list", args)
                        check(reply.ok); checkHome()
                        if (selectedPeer == targetId) { remote = managementPage(reply.data); remoteSelection = null }
                    }
                }
                ManagementButton(text(ManagementWord.LOAD), enabled) { loadRemote() }
                remote?.let { page ->
                    if (page.profiles.isEmpty()) Text(text(ManagementWord.EMPTY), style = MaterialTheme.typography.bodyMedium)
                    page.profiles.forEach { profile ->
                        ManagementProfileRow(profile, remoteSelection == profile, words, enabled) { remoteSelection = profile }
                    }
                    page.next?.let { cursor -> ManagementButton(text(ManagementWord.NEXT), enabled) { loadRemote(cursor) } }
                }
                ManagementButton(text(ManagementWord.IMPORT), enabled && remoteSelection != null) {
                    val profile = remoteSelection ?: return@ManagementButton
                    val targetId = peer.nodeId
                    var imported = false
                    action {
                        if (!imported) {
                            // Find the latest local version across every metadata page before CAS import.
                            val revision = managementLatestVersion(profile.id) { args -> localRequest("profiles.list", args) }
                            checkHome()
                            val route = node.peers.value.first { it.nodeId == targetId && it.authenticated && it.homeId == data.homeId }
                            val reply = node.request(route.address, route.port, route.nodeId, "profiles.export",
                                JSONObject().put("id", profile.id).put("version", profile.version))
                            check(reply.ok); checkHome()
                            // Secret-bearing data is transient in this coroutine only: no state, logs, UI, or tools.
                            val exported = reply.data
                            check(exported.getString("id") == profile.id && exported.getLong("version") == profile.version)
                            localRequest("profiles.import", JSONObject().put("id", profile.id)
                                .put("provider", exported.getString("provider")).put("label", exported.getString("label"))
                                .put("values", exported.getJSONObject("values")).put("expected_revision", revision))
                            imported = true
                        }
                        remoteSelection = null
                        refreshLocal()
                    }
                }
            } }
            item("local-profiles") { GlassCard(contentPadding = Spacing.md) {
                SectionTitle(text(ManagementWord.LOCAL))
                Text(text(ManagementWord.APPLY_WARNING), style = MaterialTheme.typography.bodyMedium)
                ManagementButton(text(ManagementWord.LOAD), enabled) { action { refreshLocal() } }
                local?.let { page ->
                    if (page.profiles.isEmpty()) Text(text(ManagementWord.EMPTY), style = MaterialTheme.typography.bodyMedium)
                    page.profiles.forEach { profile ->
                        ManagementProfileRow(profile, localSelection == profile, words, enabled) { localSelection = profile }
                    }
                    page.next?.let { cursor -> ManagementButton(text(ManagementWord.NEXT), enabled) { action { refreshLocal(cursor) } } }
                    ManagementButton(text(ManagementWord.APPLY), enabled && localSelection != null && "profiles.apply" in data.operations) {
                        val profile = localSelection ?: return@ManagementButton
                        val revision = page.selectionRevision
                        var applied = false
                        action {
                            // The existing handler owns the backup and revision fencing; never import settings directly.
                            if (!applied) {
                                localRequest("profiles.apply", JSONObject().put("id", profile.id).put("version", profile.version)
                                    .put("expected_revision", revision))
                                applied = true
                            }
                            localSelection = null
                            refreshLocal()
                        }
                    }
                }
                ManagementField(text(ManagementWord.CAPTURE_ID), captureId, enabled) { captureId = it.take(128) }
                ManagementField(text(ManagementWord.CAPTURE_LABEL), captureLabel, enabled) { captureLabel = it.take(128) }
                ManagementButton(text(ManagementWord.CAPTURE), enabled && "profiles.capture" in data.operations &&
                    captureId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) && captureLabel.isNotBlank()) {
                    val id = captureId; val label = captureLabel
                    var captured = false
                    action {
                        if (!captured) {
                            val revision = managementLatestVersion(id) { args -> localRequest("profiles.list", args) }
                            localRequest("profiles.capture", JSONObject().put("id", id).put("label", label).put("expected_revision", revision))
                            captured = true
                        }
                        refreshLocal()
                    }
                }
            } }
        }
    }
}

@Composable
private fun ManagementProfileRow(profile: ManagementProfile, selected: Boolean, words: List<String>, enabled: Boolean, onSelect: () -> Unit) {
    Text("${profile.label} · ${profile.provider}", style = MaterialTheme.typography.bodyLarge)
    Text("${profile.id} · ${words[ManagementWord.VERSION.ordinal]} ${profile.version}", style = MaterialTheme.typography.bodyMedium)
    ManagementButton(words[(if (selected) ManagementWord.SELECTED else ManagementWord.SELECT).ordinal], enabled, onSelect)
}

/** Metadata-only pagination: never export a profile just to find its current revision. */
private suspend fun managementLatestVersion(id: String, list: suspend (JSONObject) -> JSONObject): Long {
    var cursor: String? = null
    var latest = 0L
    val seen = mutableSetOf<String>()
    do {
        val args = JSONObject().put("limit", 100)
        cursor?.let { args.put("cursor", it) }
        val page = managementPage(list(args))
        page.profiles.filter { it.id == id }.forEach { latest = maxOf(latest, it.version) }
        cursor = page.next
        if (cursor != null) check(seen.add(cursor))
    } while (cursor != null)
    return latest
}
