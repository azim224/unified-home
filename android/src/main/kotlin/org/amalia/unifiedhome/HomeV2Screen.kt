package org.amalia.unifiedhome

import android.os.SystemClock
import androidx.compose.ui.platform.LocalContext
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
import org.amalia.unifiedhome.v2.HomeV2Peer
import org.json.JSONObject
import java.util.UUID

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

// Localized additions keep the same compact copy approach as the existing screen.
private fun v2ToolsCopy(language: String): V2Copy = V2Copy((when (language) {
    "ru" -> "Локальный сервер работает|Локальный сервер остановлен|Проверить снова|Последняя зашифрованная проверка|Ответ получен|Код ошибки|Время обмена (мс)|Поиск на этой странице|Входная схема|Только просмотр: запуск недоступен|Запросить сведения о системе|Запрос только читает сведения об ОС и Python на выбранном устройстве. Новый UUID будет сохранён до отправки. Очередь не означает успех. При неопределённом результате только обнови задачу, не отправляй заново.|Обновить задачу|UUID сохранён; результат может быть неизвестен. Автоповтора нет.|Завершить отслеживание|Выбранное устройство|Аргументы не требуются|Сохранённая задача|Ничего не найдено"
    "ja" -> "ローカルサーバー稼働中|ローカルサーバー停止中|再確認|直近の暗号化確認|応答を受信|エラーコード|往復時間（ミリ秒）|このページを検索|入力スキーマ|閲覧のみ：実行不可|システム情報を要求|選択端末のOSとPython情報のみ読み取ります。送信前に新しいUUIDを保存します。待機中は成功ではありません。結果不明時はタスク確認のみ行い、再送しないでください。|タスクを更新|UUID保存済み。結果は不明の場合があります。自動再送なし。|追跡を終了|選択端末|引数不要|保存済みタスク|一致なし"
    "de" -> "Lokaler Server läuft|Lokaler Server gestoppt|Erneut prüfen|Letzte verschlüsselte Prüfung|Antwort erhalten|Fehlercode|Hin- und Rückzeit (ms)|Diese Seite durchsuchen|Eingabeschema|Nur Ansicht: Ausführung nicht verfügbar|Systeminformationen anfordern|Liest nur OS- und Python-Fakten des gewählten Geräts. Eine neue UUID wird vor dem Senden gespeichert. Wartend bedeutet nicht erfolgreich. Bei unklarem Ergebnis nur den Aufgabenstatus abfragen, nicht erneut senden.|Aufgabe aktualisieren|UUID gespeichert; Ergebnis möglicherweise unbekannt. Keine automatische Wiederholung.|Verfolgung beenden|Gewähltes Gerät|Keine Argumente erforderlich|Gespeicherte Aufgabe|Keine Treffer"
    "es" -> "Servidor local activo|Servidor local detenido|Volver a comprobar|Última comprobación cifrada|Respuesta recibida|Código de error|Tiempo de ida y vuelta (ms)|Buscar en esta página|Esquema de entrada|Solo consulta: ejecución no disponible|Solicitar información del sistema|Solo lee datos del SO y Python del dispositivo seleccionado. Se guardará un UUID nuevo antes de enviar. En cola no significa éxito. Si el resultado es incierto, consulta la tarea sin reenviarla.|Actualizar tarea|UUID guardado; el resultado puede ser desconocido. Sin reenvío automático.|Dejar de seguir|Dispositivo seleccionado|No requiere argumentos|Tarea guardada|Sin coincidencias"
    "fr" -> "Serveur local actif|Serveur local arrêté|Vérifier à nouveau|Dernière vérification chiffrée|Réponse reçue|Code d’erreur|Temps aller-retour (ms)|Rechercher dans cette page|Schéma d’entrée|Consultation seule : exécution indisponible|Demander les informations système|Lit uniquement les informations OS et Python de l’appareil choisi. Un nouvel UUID est enregistré avant l’envoi. En attente ne signifie pas réussite. Si le résultat est incertain, consultez la tâche sans la renvoyer.|Actualiser la tâche|UUID enregistré ; résultat éventuellement inconnu. Aucun renvoi automatique.|Arrêter le suivi|Appareil sélectionné|Aucun argument requis|Tâche enregistrée|Aucun résultat"
    "zh" -> "本地服务器运行中|本地服务器已停止|重新验证|上次加密验证|已收到响应|错误代码|往返耗时（毫秒）|搜索本页|输入架构|仅查看：不可执行|请求系统信息|仅读取所选设备的操作系统和 Python 信息。发送前保存新的 UUID。排队不代表成功。结果不明确时仅刷新任务，不要重新发送。|刷新任务|UUID 已保存，结果可能未知。不会自动重发。|结束跟踪|所选设备|无需参数|已保存的任务|无匹配项"
    "hi" -> "स्थानीय सर्वर चालू|स्थानीय सर्वर बंद|फिर जाँचें|पिछली एन्क्रिप्टेड जाँच|उत्तर मिला|त्रुटि कोड|आने-जाने का समय (मिलीसेकंड)|इस पृष्ठ पर खोजें|इनपुट स्कीमा|केवल देखें: चलाना उपलब्ध नहीं|सिस्टम जानकारी माँगें|केवल चुने हुए डिवाइस के OS और Python तथ्य पढ़ता है। भेजने से पहले नया UUID सहेजा जाएगा। कतार में होना सफलता नहीं है। अनिश्चित परिणाम पर केवल कार्य की स्थिति पढ़ें, दोबारा न भेजें।|कार्य ताज़ा करें|UUID सहेजा गया; परिणाम अज्ञात हो सकता है। स्वतः दोबारा नहीं भेजेंगे।|निगरानी समाप्त करें|चुना हुआ डिवाइस|तर्क आवश्यक नहीं|सहेजा गया कार्य|कोई मेल नहीं"
    "ar" -> "الخادم المحلي يعمل|الخادم المحلي متوقف|إعادة الفحص|آخر فحص مشفر|تم استلام الرد|رمز الخطأ|زمن الذهاب والعودة (مللي ثانية)|بحث في هذه الصفحة|مخطط الإدخال|للعرض فقط: التنفيذ غير متاح|طلب معلومات النظام|يقرأ فقط معلومات نظام التشغيل وPython للجهاز المحدد. يحفظ UUID جديداً قبل الإرسال. الانتظار لا يعني النجاح. إذا كانت النتيجة غير مؤكدة فحدّث المهمة فقط ولا تعاود الإرسال.|تحديث المهمة|تم حفظ UUID؛ قد تكون النتيجة مجهولة. لا إعادة إرسال تلقائية.|إنهاء التتبع|الجهاز المحدد|لا تتطلب معاملات|المهمة المحفوظة|لا نتائج"
    else -> "Local server running|Local server stopped|Check again|Last encrypted check|Reply received|Error code|Round-trip elapsed (ms)|Search this page|Input schema|View only: execution unavailable|Request system information|Only reads OS and Python facts on the selected device. A new UUID will be saved before sending. Queued does not mean succeeded. If the outcome is uncertain, only refresh the task; do not resubmit.|Refresh task|UUID saved; outcome may be unknown. No automatic replay.|Finish tracking|Selected device|No arguments required|Saved task|No matches"
}).split('|'))

private fun v2SafeCode(value: String?): String? = value?.takeIf { Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}").matches(it) }

/** Fail closed: only the known low-risk PC operation, with its exact zero-argument contract. */
private fun v2SystemInfo(entry: JSONObject): Boolean {
    if (entry.opt("name") != "pc.system.info" || entry.opt("read_only") != true) return false
    val schema = entry.optJSONObject("input_schema") ?: return false
    val keys = schema.keys().asSequence().toSet()
    return keys == setOf("type", "properties", "required", "additionalProperties") &&
        schema.opt("type") == "object" && schema.optJSONObject("properties")?.length() == 0 &&
        schema.optJSONArray("required")?.length() == 0 && schema.opt("additionalProperties") == false
}

private data class V2Diagnostic(val node: String, val address: String, val port: Int,
    val elapsedMs: Long, val ok: Boolean, val code: String?)

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
    val tools = remember(language) { v2ToolsCopy(language) }
    // Only public home/device IDs and task UUIDs; never invitations, payloads or results.
    val preferences = LocalContext.current.applicationContext.getSharedPreferences("home_v2_ui_tasks", 0)
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
    var busy by remember { mutableStateOf(false) }
    val peer = peers.firstOrNull { it.nodeId == selected && it.homeId == status.homeId && it.authenticated }
    val contextToken = remember(status.homeId, selected, peer?.address, peer?.port) { Any() }
    var failed by remember(contextToken) { mutableStateOf(false) }
    val currentToken = rememberUpdatedState(contextToken)
    var taskId by remember(contextToken) { mutableStateOf("") }
    var catalogue by remember(contextToken) { mutableStateOf<JSONObject?>(null) }
    var task by remember(contextToken) { mutableStateOf<JSONObject?>(null) }
    var diagnostic by remember(contextToken) { mutableStateOf<V2Diagnostic?>(null) }
    var query by remember(contextToken) { mutableStateOf("") }
    var detail by remember(contextToken) { mutableStateOf<JSONObject?>(null) }
    var submitConfirmation by remember(contextToken) { mutableStateOf(false) }
    var savedTask by remember(contextToken) { mutableStateOf<String?>(null) }
    var trackingLoaded by remember(contextToken) { mutableStateOf(false) }
    val trackingKey = status.homeId?.let { home -> selected?.let { "$home.$it" } }
    LaunchedEffect(contextToken) {
        try {
            val id = trackingKey?.let { withContext(Dispatchers.IO) { preferences.getString(it, null) } }
            check(id == null || id.matches(Regex("[0-9a-f]{32}")))
            if (currentToken.value === contextToken) {
                savedTask = id; taskId = id.orEmpty(); trackingLoaded = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (currentToken.value === contextToken) failed = true }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false
        val token = contextToken
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (currentToken.value === token) failed = true }
            finally { busy = false }
        }
    }
    suspend fun request(trusted: HomeV2Peer, operation: String, args: JSONObject): JSONObject {
        val token = contextToken
        if (currentToken.value !== token || node.status.value.homeId != trusted.homeId) throw CancellationException("selection_changed")
        val start = SystemClock.elapsedRealtime()
        val result = node.request(trusted.address, trusted.port, trusted.nodeId, operation, args)
        val elapsed = SystemClock.elapsedRealtime() - start
        if (currentToken.value !== token || node.status.value.homeId != trusted.homeId) throw CancellationException("selection_changed")
        if (!result.ok) {
            diagnostic = V2Diagnostic(trusted.nodeId, trusted.address, trusted.port, elapsed, false, v2SafeCode(result.error))
            error("request_failed")
        }
        if (operation == "tasks.get" || operation == "tasks.submit") {
            check(result.data.optString("task_id") == args.optString("task_id"))
        }
        return result.data
    }
    fun checkLink(id: String, route: String, routePort: Int) {
        val home = status.homeId
        diagnostic = null
        action {
            val start = SystemClock.elapsedRealtime()
            val result = node.request(route, routePort, id, "node.describe")
            val elapsed = SystemClock.elapsedRealtime() - start
            if (node.status.value.homeId == home && currentToken.value === contextToken) {
                diagnostic = V2Diagnostic(id, route, routePort, elapsed, result.ok, v2SafeCode(result.error))
            }
        }
    }
    // No secret in saved state, no automatic export, and clear export on route disposal/backgrounding.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { exported = null; invite = ""; confirmation = null; submitConfirmation = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); exported = null; invite = "" }
    }
    LaunchedEffect(status.homeId) { exported = null; selected = null; catalogue = null; task = null; failed = false }
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
                    Text(if (status.running) tools[0] else tools[1], style = MaterialTheme.typography.bodyMedium)
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
                        checkLink(target, address.trim(), port.toInt())
                    }
                } }
                items(peers.sortedByDescending { it.authenticated }.distinctBy { it.nodeId }, key = { it.nodeId }, contentType = { "peer" }) { candidate ->
                    GlassCard(contentPadding = Spacing.md) {
                        Text(candidate.name.ifBlank { candidate.platform.ifBlank { copy[19] } }, style = MaterialTheme.typography.bodyLarge)
                        Text("${candidate.nodeId} · ${candidate.address}:${candidate.port}", style = MaterialTheme.typography.bodyMedium)
                        Text(if (candidate.authenticated) copy[23] else copy[24], style = MaterialTheme.typography.bodyMedium)
                        V2Button(if (candidate.authenticated) copy[38] else copy[22], !busy) {
                            if (candidate.authenticated) selected = candidate.nodeId
                            else checkLink(candidate.nodeId, candidate.address, candidate.port)
                        }
                    }
                }
                diagnostic?.let { result -> item("link-result") { GlassCard(contentPadding = Spacing.md) {
                    SectionTitle(tools[3])
                    Text("${result.node} · ${result.address}:${result.port}", style = MaterialTheme.typography.bodyMedium)
                    Text(if (result.ok) tools[4] else copy[31], style = MaterialTheme.typography.bodyMedium)
                    Text("${tools[6]} · ${result.elapsedMs}", style = MaterialTheme.typography.bodyMedium)
                    result.code?.let { Text("${tools[5]} · $it", style = MaterialTheme.typography.bodyMedium) }
                } } }
                if (peer != null) item("peer-data") { GlassCard(contentPadding = Spacing.md) {
                    SectionTitle(tools[15])
                    Text(peer.name.ifBlank { peer.nodeId }, style = MaterialTheme.typography.bodyLarge)
                    Text("${peer.nodeId} · ${peer.address}:${peer.port}", style = MaterialTheme.typography.bodyMedium)
                    V2Button(tools[2], !busy) { checkLink(peer.nodeId, peer.address, peer.port) }
                    SectionTitle(copy[25])
                    V2Button(copy[26], !busy) { detail = null; submitConfirmation = false
                        action { catalogue = request(peer, "capabilities.list", org.json.JSONObject()) } }
                    val entries = catalogue?.optJSONArray("capabilities")
                    if (catalogue != null) Text(copy[33], style = MaterialTheme.typography.bodyMedium)
                    V2Field(tools[7], query) { query = it }
                    if (entries != null) {
                        if (entries.length() == 0) Text(copy[34], style = MaterialTheme.typography.bodyMedium)
                        val matches = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
                            .filter { it.optString("name").contains(query.trim(), ignoreCase = true) }
                        if (entries.length() > 0 && matches.isEmpty()) Text(tools[18], style = MaterialTheme.typography.bodyMedium)
                        matches.forEach { entry ->
                            val name = entry.optString("name").take(128).filterNot { it.isISOControl() }
                            V2Button("${copy[36]} · $name", !busy) { detail = entry; submitConfirmation = false }
                        }
                    }
                    detail?.let { entry ->
                        Text(entry.optString("name").take(128).filterNot { it.isISOControl() }, style = MaterialTheme.typography.bodyLarge)
                        Text(tools[8], style = MaterialTheme.typography.labelLarge)
                        // Schema only, bounded for layout; never display arbitrary provider descriptions or results.
                        Text(entry.optJSONObject("input_schema")?.toString()?.take(2000).orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        val eligible = v2SystemInfo(entry)
                        Text(if (eligible) tools[16] else tools[9], style = MaterialTheme.typography.bodyMedium)
                        if (eligible) V2Button(tools[10], !busy && trackingLoaded && savedTask == null) { submitConfirmation = true }
                        if (submitConfirmation && eligible) {
                            Text(tools[11], style = MaterialTheme.typography.bodyMedium)
                            Text("${peer.nodeId} · pc.system.info", style = MaterialTheme.typography.bodyMedium)
                            V2Button(copy[8], !busy && savedTask == null && trackingLoaded) {
                                val trusted = peer
                                val key = trackingKey
                                val token = contextToken
                                val id = UUID.randomUUID().toString().replace("-", "")
                                submitConfirmation = false
                                action {
                                    check(key != null)
                                    // Fail closed on a storage error; do not allocate another UUID in this view.
                                    trackingLoaded = false
                                    // Durable BEFORE first send. On cancellation/timeout, never submit this record again.
                                    check(withContext(Dispatchers.IO) { preferences.edit().putString(key, id).commit() })
                                    if (currentToken.value !== token) throw CancellationException("selection_changed")
                                    trackingLoaded = true; savedTask = id; taskId = id; task = null
                                    task = request(trusted, "tasks.submit", JSONObject().put("task_id", id)
                                        .put("capability", "pc.system.info").put("arguments", JSONObject()))
                                }
                            }
                            V2Button(copy[9], !busy) { submitConfirmation = false }
                        }
                    }
                    val cursor = catalogue?.opt("next_cursor") as? String
                    if (!cursor.isNullOrEmpty()) V2Button(copy[27], !busy) {
                        detail = null; submitConfirmation = false
                        action { catalogue = request(peer, "capabilities.list", org.json.JSONObject().put("cursor", cursor)) }
                    }
                    SectionTitle(copy[28])
                    savedTask?.let { id ->
                        Text("${tools[17]} · $id", style = MaterialTheme.typography.bodyMedium)
                        Text(tools[13], style = MaterialTheme.typography.bodyMedium)
                        V2Button(tools[12], !busy) { taskId = id; task = null
                            action { task = request(peer, "tasks.get", JSONObject().put("task_id", id)) }
                        }
                    }
                    if (!busy) V2Field(copy[29], taskId) { taskId = it; task = null }
                    else Text("${copy[29]} · $taskId", style = MaterialTheme.typography.bodyMedium)
                    V2Button(copy[30], !busy && taskId.matches(Regex("[0-9a-f]{32}"))) {
                        val id = taskId; task = null
                        action { task = request(peer, "tasks.get", org.json.JSONObject().put("task_id", id)) }
                    }
                    task?.let { result ->
                        Text("${copy[37]} · ${v2TaskState(result.optString("state"), language)}", style = MaterialTheme.typography.bodyMedium)
                        v2SafeCode(result.opt("error") as? String ?: result.opt("error_code") as? String)?.let {
                            Text("${tools[5]} · $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (result.optString("task_id") == savedTask && result.optString("state") in setOf("succeeded", "error", "cancelled")) {
                            V2Button(tools[14], !busy) { val key = trackingKey; val token = contextToken
                                action {
                                    check(key != null)
                                    check(withContext(Dispatchers.IO) { preferences.edit().remove(key).commit() })
                                    if (currentToken.value === token) { savedTask = null; taskId = ""; task = null }
                                }
                            }
                        }
                    }
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
