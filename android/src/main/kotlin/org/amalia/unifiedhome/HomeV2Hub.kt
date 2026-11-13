package org.amalia.unifiedhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.my.amali.plugin.ScreenHost
import com.my.amali.ui.components.AmaliaScreen
import com.my.amali.ui.components.GhostButton
import com.my.amali.ui.components.GlassDivider
import com.my.amali.ui.components.GlassGroup
import com.my.amali.ui.components.SectionTitle
import com.my.amali.ui.components.SettingsActionRow
import com.my.amali.ui.theme.Spacing
import org.amalia.unifiedhome.v2.HomeV2Node
import org.amalia.unifiedhome.v2.HomeV2Status

private const val HUB_CONNECTION = "amalia://screen/house-v2"
private const val HUB_DEVICES = "amalia://screen/house-v2-devices"
private const val HUB_NOTES = "amalia://screen/house-v2-notes"
private const val HUB_PROFILES = "amalia://screen/house-v2-profiles"
private const val HUB_COORDINATION = "amalia://screen/house-v2-manage"
private const val HUB_LEGACY = "amalia://screen/house"

// Like the other plugin screens, copy is bundled here: plugin resources are not merged into the host.
private enum class HubWord {
    TITLE, BACK, CONNECTION, DEVICES, PROFILES, COORDINATION, LEGACY,
    CONNECT, CONNECT_HINT, DEVICE_LIST, DEVICE_HINT, NOTES, NOTES_HINT,
    PROFILE_LIST, PROFILE_HINT, MANAGE, MANAGE_HINT, ADVANCED, ADVANCED_HINT,
    MEMBERSHIP, JOINED, NOT_JOINED, NETWORK, LOCAL_READY, INITIALIZING, NETWORK_ERROR,
    DEVICE_NAME, JOIN_HINT, INIT_HINT, ERROR_HINT, READY_HINT, RETRY,
}

private class HubCopy(private val words: List<String>) {
    operator fun get(word: HubWord): String = words[word.ordinal]
}

private fun hubCopy(language: String): HubCopy = HubCopy((when (language) {
    "ru" -> "Умный дом|Назад|Подключение|Устройства и заметки|Профили|Координация|Дополнительно|Подключение к дому|Создать дом, вступить или проверить связь|Устройства|Устройства дома и их возможности|Общие заметки|Заметки для участников дома|Профили|Общие настройки и профили|Координация|Роли устройств и совместные задачи|Прежний дом · расширенные настройки|Отдельная сеть v1, не связанная с домом v2|Участие|В доме|Не в доме|Локальная сеть|Локальный сервер работает|Запуск или остановка|Локальный сервер недоступен|Это устройство|Это устройство ещё не в доме. Открой подключение, чтобы создать дом или вступить по приглашению.|Участие сохранено, но локальный сервер ещё не готов или остановлен. Проверь подключение или повтори попытку.|Участие сохранено, но локальный сервер не запустился. Проверь подключение и повтори попытку.|Локальный сервер готов. Если раздел не загрузился, повтори попытку.|Повторить"
    "ja" -> "スマートホーム|戻る|接続|端末とメモ|プロファイル|連携|詳細|ホームへの接続|作成・参加・接続確認|端末|ホームの端末と機能|共有メモ|メンバー間のメモ|プロファイル|共有設定とプロファイル|連携|端末の役割と共同タスク|旧ホーム・詳細設定|v2とは別のv1ネットワーク|参加状態|参加済み|未参加|ローカルネットワーク|ローカルサーバー稼働中|起動中または停止中|ローカルサーバー利用不可|この端末|この端末は未参加です。接続画面でホームを作成するか招待で参加してください。|参加情報は保存されていますが、ローカルサーバーは準備中または停止中です。接続を確認するか再試行してください。|参加情報は保存されていますが、ローカルサーバーを起動できませんでした。接続を確認して再試行してください。|ローカルサーバーは準備完了です。表示されない場合は再試行してください。|再試行"
    "de" -> "Smart Home|Zurück|Verbindung|Geräte und Notizen|Profile|Koordination|Erweitert|Zuhause verbinden|Erstellen, beitreten oder Verbindung prüfen|Geräte|Geräte und ihre Funktionen|Geteilte Notizen|Notizen für Mitglieder|Profile|Gemeinsame Einstellungen und Profile|Koordination|Geräterollen und gemeinsame Aufgaben|Altes Zuhause · erweitert|Separates v1-Netz, unabhängig von v2|Mitgliedschaft|Beigetreten|Nicht beigetreten|Lokales Netzwerk|Lokaler Server läuft|Startet oder ist gestoppt|Lokaler Server nicht verfügbar|Dieses Gerät|Dieses Gerät ist noch nicht beigetreten. Unter Verbindung ein Zuhause erstellen oder per Einladung beitreten.|Mitgliedschaft gespeichert, lokaler Server noch nicht bereit oder gestoppt. Verbindung prüfen oder erneut versuchen.|Mitgliedschaft gespeichert, lokaler Server konnte nicht starten. Verbindung prüfen und erneut versuchen.|Lokaler Server bereit. Falls der Bereich nicht geladen wurde, erneut versuchen.|Erneut versuchen"
    "es" -> "Hogar inteligente|Atrás|Conexión|Dispositivos y notas|Perfiles|Coordinación|Avanzado|Conectar al hogar|Crear, unirse o comprobar la conexión|Dispositivos|Dispositivos del hogar y sus funciones|Notas compartidas|Notas para los miembros|Perfiles|Ajustes y perfiles compartidos|Coordinación|Roles de dispositivos y tareas conjuntas|Hogar anterior · avanzado|Red v1 independiente del hogar v2|Pertenencia|Miembro del hogar|Sin hogar|Red local|Servidor local activo|Iniciando o detenido|Servidor local no disponible|Este dispositivo|Este dispositivo aún no pertenece a un hogar. Abre Conexión para crear uno o unirte con una invitación.|Pertenencia guardada, pero el servidor local aún no está listo o está detenido. Comprueba la conexión o reintenta.|Pertenencia guardada, pero el servidor local no pudo iniciarse. Comprueba la conexión y reintenta.|Servidor local listo. Si la sección no se cargó, reintenta.|Reintentar"
    "fr" -> "Maison connectée|Retour|Connexion|Appareils et notes|Profils|Coordination|Avancé|Connexion à la maison|Créer, rejoindre ou vérifier la connexion|Appareils|Appareils de la maison et fonctions|Notes partagées|Notes entre membres|Profils|Réglages et profils partagés|Coordination|Rôles des appareils et tâches communes|Ancienne maison · avancé|Réseau v1 distinct de la maison v2|Appartenance|Membre|Non membre|Réseau local|Serveur local actif|Démarrage ou arrêt|Serveur local indisponible|Cet appareil|Cet appareil n’a pas encore rejoint de maison. Ouvrez Connexion pour en créer une ou rejoindre sur invitation.|Appartenance enregistrée, mais le serveur local n’est pas prêt ou est arrêté. Vérifiez la connexion ou réessayez.|Appartenance enregistrée, mais le serveur local n’a pas pu démarrer. Vérifiez la connexion et réessayez.|Serveur local prêt. Si la section ne s’est pas chargée, réessayez.|Réessayer"
    "zh" -> "智能家居|返回|连接|设备与笔记|配置|协作|高级|连接家庭|创建、加入或检查连接|设备|家庭设备及其功能|共享笔记|家庭成员的笔记|配置|共享设置与配置|协作|设备角色与共同任务|旧版家庭 · 高级设置|独立的 v1 网络，与 v2 家庭分开|成员状态|已加入|未加入|本地网络|本地服务器运行中|启动中或已停止|本地服务器不可用|本机|本机尚未加入家庭。打开连接以创建家庭或通过邀请加入。|成员信息已保存，但本地服务器尚未就绪或已停止。请检查连接或重试。|成员信息已保存，但本地服务器未能启动。请检查连接后重试。|本地服务器已就绪。如果页面未加载，请重试。|重试"
    "hi" -> "स्मार्ट होम|वापस|कनेक्शन|डिवाइस और नोट्स|प्रोफ़ाइल|समन्वय|उन्नत|घर से कनेक्ट करें|बनाएँ, जुड़ें या कनेक्शन जाँचें|डिवाइस|घर के डिवाइस और उनकी क्षमताएँ|साझा नोट्स|घर के सदस्यों के नोट्स|प्रोफ़ाइल|साझा सेटिंग और प्रोफ़ाइल|समन्वय|डिवाइस की भूमिकाएँ और साझा कार्य|पुराना घर · उन्नत सेटिंग|v2 घर से अलग v1 नेटवर्क|सदस्यता|घर से जुड़े|नहीं जुड़े|स्थानीय नेटवर्क|स्थानीय सर्वर चालू|शुरू हो रहा है या बंद है|स्थानीय सर्वर उपलब्ध नहीं|यह डिवाइस|यह डिवाइस अभी किसी घर से नहीं जुड़ा है। घर बनाने या आमंत्रण से जुड़ने के लिए कनेक्शन खोलें।|सदस्यता सहेजी गई है, लेकिन स्थानीय सर्वर अभी तैयार नहीं है या बंद है। कनेक्शन जाँचें या फिर प्रयास करें।|सदस्यता सहेजी गई है, लेकिन स्थानीय सर्वर शुरू नहीं हुआ। कनेक्शन जाँचें और फिर प्रयास करें।|स्थानीय सर्वर तैयार है। यदि अनुभाग लोड नहीं हुआ, तो फिर प्रयास करें।|फिर प्रयास करें"
    "ar" -> "المنزل الذكي|رجوع|الاتصال|الأجهزة والملاحظات|الملفات الشخصية|التنسيق|متقدم|الاتصال بالمنزل|إنشاء أو انضمام أو فحص الاتصال|الأجهزة|أجهزة المنزل وقدراتها|ملاحظات مشتركة|ملاحظات لأعضاء المنزل|الملفات الشخصية|إعدادات وملفات مشتركة|التنسيق|أدوار الأجهزة والمهام المشتركة|المنزل القديم · إعدادات متقدمة|شبكة v1 مستقلة عن منزل v2|العضوية|منضم|غير منضم|الشبكة المحلية|الخادم المحلي يعمل|قيد البدء أو متوقف|الخادم المحلي غير متاح|هذا الجهاز|لم ينضم هذا الجهاز إلى منزل بعد. افتح الاتصال لإنشاء منزل أو الانضمام بدعوة.|العضوية محفوظة، لكن الخادم المحلي لم يجهز بعد أو متوقف. تحقق من الاتصال أو أعد المحاولة.|العضوية محفوظة، لكن تعذر بدء الخادم المحلي. تحقق من الاتصال وأعد المحاولة.|الخادم المحلي جاهز. إذا لم يتم تحميل القسم، أعد المحاولة.|إعادة المحاولة"
    else -> "Smart home|Back|Connection|Devices and notes|Profiles|Coordination|Advanced|Connect to home|Create, join or check the connection|Devices|Home devices and their capabilities|Shared notes|Notes for home members|Profiles|Shared settings and profiles|Coordination|Device roles and shared tasks|Legacy home · advanced|Separate v1 network, independent of the v2 home|Membership|Joined|Not joined|Local network|Local server running|Starting or stopped|Local server unavailable|This device|This device has not joined a home. Open Connection to create a home or join by invitation.|Membership is saved, but the local server is not ready yet or is stopped. Check Connection or retry.|Membership is saved, but the local server could not start. Check Connection and retry.|The local server is ready. If the section did not load, retry.|Retry"
}).split('|'))

internal fun homeV2HubTitle(language: String): String = hubCopy(language)[HubWord.TITLE]

internal fun homeV2SectionTitle(language: String, section: String): String {
    val word = when (section) {
        "connection" -> HubWord.CONNECTION
        "devices" -> HubWord.DEVICE_LIST
        "profiles" -> HubWord.PROFILES
        "coordination" -> HubWord.COORDINATION
        "legacy" -> HubWord.LEGACY
        else -> HubWord.TITLE
    }
    return hubCopy(language)[word]
}

@Composable
private fun HubSummary(node: HomeV2Node, status: HomeV2Status, copy: HubCopy) {
    val membership = copy[if (status.homeId == null) HubWord.NOT_JOINED else HubWord.JOINED]
    // Listener readiness is local diagnostics, not proof of peer trust or LAN reachability.
    val network = copy[when {
        status.error != null -> HubWord.NETWORK_ERROR
        status.running -> HubWord.LOCAL_READY
        else -> HubWord.INITIALIZING
    }]
    GlassGroup {
        Column(
            modifier = Modifier.padding(Spacing.md).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("${copy[HubWord.MEMBERSHIP]}: $membership", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text("${copy[HubWord.NETWORK]}: $network", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${copy[HubWord.DEVICE_NAME]}: ${node.deviceName}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HubLink(copy: HubCopy, title: HubWord, hint: HubWord, route: String, host: ScreenHost) {
    SettingsActionRow(title = copy[title], subtitle = copy[hint], onClick = { host.navigate(route) })
}

/** The catalogue never changes with membership; every destination stays discoverable. */
@Composable
fun HomeV2Hub(node: HomeV2Node, host: ScreenHost) {
    val language = LocalConfiguration.current.locales[0].language
    val copy = remember(language) { hubCopy(language) }
    val status by node.status.collectAsStateWithLifecycle()
    AmaliaScreen(title = copy[HubWord.TITLE], onBack = host::goBack, backLabel = copy[HubWord.BACK]) { insets ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = insets,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            item(key = "summary", contentType = "summary") { HubSummary(node, status, copy) }
            item(key = "connection", contentType = "section") {
                SectionTitle(homeV2SectionTitle(language, "connection"))
                GlassGroup { HubLink(copy, HubWord.CONNECT, HubWord.CONNECT_HINT, HUB_CONNECTION, host) }
            }
            item(key = "devices", contentType = "section") {
                SectionTitle(homeV2SectionTitle(language, "devices"))
                GlassGroup {
                    HubLink(copy, HubWord.DEVICE_LIST, HubWord.DEVICE_HINT, HUB_DEVICES, host)
                    GlassDivider()
                    HubLink(copy, HubWord.NOTES, HubWord.NOTES_HINT, HUB_NOTES, host)
                }
            }
            item(key = "profiles", contentType = "section") {
                SectionTitle(homeV2SectionTitle(language, "profiles"))
                GlassGroup {
                    HubLink(copy, HubWord.PROFILE_LIST, HubWord.PROFILE_HINT, HUB_PROFILES, host)
                    GlassDivider()
                    SettingsActionRow(title = providerSettingsLabel(language), subtitle = "LLM · STT · TTS",
                        onClick = { host.navigate("settings/api") })
                }
            }
            item(key = "coordination", contentType = "section") {
                SectionTitle(homeV2SectionTitle(language, "coordination"))
                GlassGroup { HubLink(copy, HubWord.MANAGE, HubWord.MANAGE_HINT, HUB_COORDINATION, host) }
            }
            item(key = "legacy", contentType = "section") {
                SectionTitle(homeV2SectionTitle(language, "legacy"))
                GlassGroup { HubLink(copy, HubWord.ADVANCED, HubWord.ADVANCED_HINT, HUB_LEGACY, host) }
            }
        }
    }
}

/** Read-only fallback: retry is owned by the caller, connection is always an explicit action. */
@Composable
fun HomeV2Unavailable(node: HomeV2Node, host: ScreenHost, retry: () -> Unit) {
    val language = LocalConfiguration.current.locales[0].language
    val copy = remember(language) { hubCopy(language) }
    val status by node.status.collectAsStateWithLifecycle()
    val explanation = when {
        status.homeId == null -> HubWord.JOIN_HINT
        status.error != null -> HubWord.ERROR_HINT
        !status.running -> HubWord.INIT_HINT
        else -> HubWord.READY_HINT
    }
    AmaliaScreen(title = copy[HubWord.TITLE], onBack = host::goBack, backLabel = copy[HubWord.BACK]) { insets ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = insets,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item(key = "summary", contentType = "summary") { HubSummary(node, status, copy) }
            item(key = "explanation", contentType = "message") {
                Text(copy[explanation], style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            item(key = "connection", contentType = "section") {
                SectionTitle(homeV2SectionTitle(language, "connection"))
                GlassGroup { HubLink(copy, HubWord.CONNECT, HubWord.CONNECT_HINT, HUB_CONNECTION, host) }
            }
            item(key = "retry", contentType = "action") {
                GhostButton(text = copy[HubWord.RETRY], onClick = retry, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun providerSettingsLabel(language: String): String = when (language) {
    "ru" -> "Настройки API и моделей этого устройства"
    "ja" -> "この端末のAPIとモデル設定"
    "de" -> "API und Modelle dieses Geräts"
    "es" -> "API y modelos de este dispositivo"
    "fr" -> "API et modèles de cet appareil"
    "zh" -> "本机 API 与模型设置"
    "hi" -> "इस डिवाइस की API और मॉडल सेटिंग"
    "ar" -> "إعدادات API والنماذج لهذا الجهاز"
    else -> "This device's API and model settings"
}
