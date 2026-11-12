package org.amalia.unifiedhome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.my.amali.ui.components.GlassDialog
import com.my.amali.ui.components.SkeletonList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.my.amali.plugin.ScreenHost
import com.my.amali.ui.components.AmaliaScreen
import com.my.amali.ui.components.GhostButton
import com.my.amali.ui.components.GlassCard
import com.my.amali.ui.components.SectionTitle
import com.my.amali.ui.theme.Radius
import com.my.amali.ui.theme.Spacing
import com.my.amali.ui.theme.glassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.amalia.unifiedhome.v2.HomeV2Data
import org.amalia.unifiedhome.v2.HomeV2Exception
import org.amalia.unifiedhome.v2.HomeV2Node
import org.json.JSONObject
import java.util.UUID

private enum class NotesWord {
    TITLE, BACK, UNAVAILABLE, SEARCH, REFRESH, MORE, EMPTY, NEW, TEXT, SAVE, DELETE,
    CANCEL, DISCARD, DISCARD_WARNING, DELETE_WARNING, WORKING, READ_ERROR, RETRY,
    WRITE_ERROR, CONFLICT, INVALID, INCOMPATIBLE, SELECT, DONE, LIMIT, LOCAL,
}

private fun notesCopy(language: String): List<String> = (when (language) {
    "ru" -> "Общие заметки|Назад|Вступите в дом и включите его. Данные этого дома пока недоступны.|Поиск в загруженных заметках|Обновить список|Загрузить ещё|Заметки не найдены.|Новая заметка|Текст заметки|Сохранить|Удалить|Отмена|Отбросить черновик|Отбросить несохранённые изменения?|Удалить выбранную заметку? Это изменение будет общим для дома.|Выполняется…|Не удалось прочитать заметки. Повторите чтение или обновите список.|Повторить чтение|Сохранение или удаление не подтверждено. Черновик сохранён в памяти. Обновите список и проверьте результат перед следующей записью.|Заметка изменилась. Черновик сохранён. Обновите список и явно выберите актуальную заметку; автоматического повтора нет.|Введите непустой корректный текст: до 2000 символов Unicode. Объект заметки также должен помещаться в лимит JSON.|Неизвестный формат заметки. Текст нельзя редактировать; остальные поля не изменяются.|Открыть заметку|Готово|Достигнут лимит 200 загруженных заметок. Обновление начинает список заново.|Список хранится на этом устройстве; синхронизация с домом может задерживаться. Черновики очищаются при уходе в фон."
    "ja" -> "共有メモ|戻る|ホームに参加して有効にしてください。このホームのデータはまだ利用できません。|取得済みメモを検索|一覧を更新|さらに取得|メモが見つかりません。|新しいメモ|メモ本文|保存|削除|キャンセル|下書きを破棄|未保存の変更を破棄しますか？|選択したメモを削除しますか？ホーム全体への変更です。|処理中…|メモを読み込めませんでした。再読込するか一覧を更新してください。|読込を再試行|保存または削除を確認できません。下書きはメモリに保持されています。一覧を更新し、次の書込前に結果を確認してください。|メモが変更されました。下書きは保持されています。一覧を更新し、最新のメモを明示的に選択してください。自動再試行はありません。|空でない正しいUnicodeテキストを2000文字以内で入力してください。メモのJSONサイズ制限も適用されます。|未対応のメモ形式です。本文は編集できず、他のフィールドも変更しません。|メモを開く|完了|取得上限の200件に達しました。更新すると先頭から取得します。|この端末の一覧です。ホームとの同期には遅延があります。バックグラウンド移行時に下書きを消去します。"
    "de" -> "Gemeinsame Notizen|Zurück|Einem Zuhause beitreten und es aktivieren. Dessen Daten sind noch nicht verfügbar.|Geladene Notizen durchsuchen|Liste aktualisieren|Mehr laden|Keine Notizen gefunden.|Neue Notiz|Notiztext|Speichern|Löschen|Abbrechen|Entwurf verwerfen|Ungespeicherte Änderungen verwerfen?|Gewählte Notiz löschen? Dies gilt für das ganze Zuhause.|Wird ausgeführt…|Notizen konnten nicht gelesen werden. Lesen wiederholen oder Liste aktualisieren.|Lesen wiederholen|Speichern oder Löschen nicht bestätigt. Der Entwurf bleibt im Arbeitsspeicher. Vor dem nächsten Schreiben die Liste aktualisieren und das Ergebnis prüfen.|Die Notiz wurde geändert. Der Entwurf bleibt erhalten. Liste aktualisieren und die aktuelle Notiz ausdrücklich auswählen; keine automatische Wiederholung.|Gültigen, nicht leeren Unicode-Text mit höchstens 2000 Zeichen eingeben. Auch das JSON-Größenlimit gilt.|Unbekanntes Notizformat. Text kann nicht bearbeitet werden; andere Felder bleiben unverändert.|Notiz öffnen|Fertig|Limit von 200 geladenen Notizen erreicht. Aktualisieren beginnt die Liste neu.|Liste dieses Geräts; die Synchronisierung kann verzögert sein. Entwürfe werden beim Wechsel in den Hintergrund gelöscht."
    "es" -> "Notas compartidas|Atrás|Únete a un hogar y actívalo. Sus datos aún no están disponibles.|Buscar en notas cargadas|Actualizar lista|Cargar más|No se encontraron notas.|Nueva nota|Texto de la nota|Guardar|Eliminar|Cancelar|Descartar borrador|¿Descartar los cambios sin guardar?|¿Eliminar la nota seleccionada? Afecta a todo el hogar.|Procesando…|No se pudieron leer las notas. Reintenta la lectura o actualiza la lista.|Reintentar lectura|No se confirmó el guardado o borrado. El borrador sigue en memoria. Actualiza la lista y comprueba el resultado antes de otra escritura.|La nota cambió. Se conserva el borrador. Actualiza la lista y selecciona explícitamente la nota actual; no hay reintento automático.|Introduce texto Unicode válido y no vacío, de hasta 2000 caracteres. También se aplica el límite de tamaño JSON.|Formato de nota incompatible. No se puede editar el texto; los demás campos no se modifican.|Abrir nota|Listo|Límite de 200 notas cargadas. Actualizar reinicia la lista.|Lista de este dispositivo; la sincronización puede tardar. Los borradores se borran al pasar a segundo plano."
    "fr" -> "Notes partagées|Retour|Rejoignez une maison et activez-la. Ses données ne sont pas encore disponibles.|Rechercher dans les notes chargées|Actualiser la liste|Charger davantage|Aucune note trouvée.|Nouvelle note|Texte de la note|Enregistrer|Supprimer|Annuler|Abandonner le brouillon|Abandonner les modifications non enregistrées ?|Supprimer la note sélectionnée ? Ceci concerne toute la maison.|En cours…|Lecture des notes impossible. Réessayez la lecture ou actualisez la liste.|Réessayer la lecture|Enregistrement ou suppression non confirmé. Le brouillon reste en mémoire. Actualisez la liste et vérifiez le résultat avant une autre écriture.|La note a changé. Le brouillon est conservé. Actualisez la liste et sélectionnez explicitement la note actuelle ; aucun nouvel essai automatique.|Saisissez un texte Unicode valide non vide de 2000 caractères maximum. La limite de taille JSON s’applique aussi.|Format de note incompatible. Le texte ne peut pas être modifié ; les autres champs restent inchangés.|Ouvrir la note|Terminé|Limite de 200 notes chargées atteinte. Actualiser reprend la liste au début.|Liste de cet appareil ; la synchronisation peut être retardée. Les brouillons sont effacés au passage en arrière-plan."
    "zh" -> "共享笔记|返回|请加入家庭并启用。此家庭的数据尚不可用。|搜索已加载的笔记|刷新列表|加载更多|未找到笔记。|新建笔记|笔记正文|保存|删除|取消|放弃草稿|放弃未保存的更改？|删除所选笔记？这会影响整个家庭。|处理中…|无法读取笔记。请重试读取或刷新列表。|重试读取|保存或删除尚未确认。草稿保留在内存中。再次写入前请刷新列表并核实结果。|笔记已更改。草稿已保留。请刷新列表并手动选择最新笔记；不会自动重试。|请输入非空的有效 Unicode 文本，最多 2000 个字符。同时受 JSON 大小限制。|笔记格式不兼容。无法编辑正文；其他字段不会更改。|打开笔记|完成|已达到 200 条已加载笔记上限。刷新可从头加载。|这是本机列表；家庭同步可能延迟。转入后台时会清除草稿。"
    "hi" -> "साझा नोट्स|वापस|घर से जुड़ें और उसे सक्रिय करें। इस घर का डेटा अभी उपलब्ध नहीं है।|लोड किए नोट्स में खोजें|सूची ताज़ा करें|और लोड करें|कोई नोट नहीं मिला।|नया नोट|नोट का पाठ|सहेजें|हटाएँ|रद्द करें|ड्राफ़्ट छोड़ें|बिना सहेजे बदलाव छोड़ें?|चयनित नोट हटाएँ? यह पूरे घर पर लागू होगा।|कार्य जारी…|नोट पढ़े नहीं जा सके। पढ़ना दोहराएँ या सूची ताज़ा करें।|पढ़ना दोहराएँ|सहेजने या हटाने की पुष्टि नहीं हुई। ड्राफ़्ट मेमोरी में है। अगली लिखाई से पहले सूची ताज़ा करके परिणाम जाँचें।|नोट बदल गया है। ड्राफ़्ट सुरक्षित है। सूची ताज़ा करें और वर्तमान नोट स्पष्ट रूप से चुनें; स्वतः पुनः प्रयास नहीं होगा।|अधिकतम 2000 Unicode वर्णों का मान्य, गैर-रिक्त पाठ दें। JSON आकार सीमा भी लागू है।|असमर्थित नोट प्रारूप। पाठ संपादित नहीं किया जा सकता; अन्य फ़ील्ड नहीं बदले जाएँगे।|नोट खोलें|पूर्ण|200 लोड किए नोट्स की सीमा पूरी हुई। ताज़ा करने से सूची फिर शुरू होगी।|यह इस डिवाइस की सूची है; घर से सिंक में देरी हो सकती है। बैकग्राउंड में जाने पर ड्राफ़्ट मिटा दिए जाते हैं।"
    "ar" -> "ملاحظات مشتركة|رجوع|انضم إلى منزل وفعّله. بيانات هذا المنزل غير متاحة بعد.|البحث في الملاحظات المحمّلة|تحديث القائمة|تحميل المزيد|لم يتم العثور على ملاحظات.|ملاحظة جديدة|نص الملاحظة|حفظ|حذف|إلغاء|تجاهل المسودة|تجاهل التغييرات غير المحفوظة؟|حذف الملاحظة المحددة؟ يسري ذلك على المنزل كله.|جارٍ التنفيذ…|تعذرت قراءة الملاحظات. أعد القراءة أو حدّث القائمة.|إعادة القراءة|لم يتأكد الحفظ أو الحذف. المسودة محفوظة في الذاكرة. حدّث القائمة وتحقق من النتيجة قبل الكتابة التالية.|تغيرت الملاحظة. تم الاحتفاظ بالمسودة. حدّث القائمة واختر الملاحظة الحالية صراحةً؛ لا توجد إعادة تلقائية.|أدخل نص Unicode صالحاً غير فارغ لا يتجاوز 2000 حرف. يسري أيضاً حد حجم JSON.|تنسيق ملاحظة غير مدعوم. لا يمكن تحرير النص؛ لا تتغير الحقول الأخرى.|فتح الملاحظة|تم|تم بلوغ حد 200 ملاحظة محمّلة. التحديث يبدأ القائمة من جديد.|هذه قائمة الجهاز؛ قد تتأخر مزامنة المنزل. تُمحى المسودات عند الانتقال إلى الخلفية."
    else -> "Shared notes|Back|Join a home and enable it. This home’s data is not available yet.|Search loaded notes|Refresh list|Load more|No notes found.|New note|Note text|Save|Delete|Cancel|Discard draft|Discard unsaved changes?|Delete the selected note? This changes it for the whole home.|Working…|Could not read notes. Retry the read or refresh the list.|Retry read|Save or delete is not confirmed. Your draft is kept in memory. Refresh the list and check the outcome before another write.|The note changed. Your draft is kept. Refresh the list and explicitly select the current note; there is no automatic retry.|Enter valid, nonblank Unicode text, at most 2000 code points. The note’s JSON size limit also applies.|Unsupported note format. Text cannot be edited; other fields are not changed.|Open note|Done|Limit of 200 loaded notes reached. Refresh starts the list again.|This device’s list; home synchronization may be delayed. Drafts are cleared when backgrounded."
}).split('|')

/** Navigation and screen share the same nine-locale title. */
internal fun homeV2NotesTitle(language: String): String = notesCopy(language)[NotesWord.TITLE.ordinal]

private data class NotesRecord(val id: String, val revision: Long, val origin: String, val value: JSONObject) {
    val text: String? get() = value.opt("text") as? String
    fun expected(): JSONObject = JSONObject().put("revision", revision).put("origin", origin)
}

private enum class NotesIntent { NEW, SELECT, BACK, CLOSE, DELETE }

/** Memory only: neither text, search, IDs nor pending writes enter saved state or logs. */
private class NotesSession {
    var records by mutableStateOf<List<NotesRecord>>(emptyList())
    var cursor by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var editing by mutableStateOf(false)
    var selected by mutableStateOf<NotesRecord?>(null)
    var draft by mutableStateOf("")
    var newId: String? = null
    var busy by mutableStateOf(false)
    var writeBlocked by mutableStateOf(false)
    var feedback by mutableStateOf<NotesWord?>(null)
    var readFailed by mutableStateOf(false)
    var retryCursor: String? = null
    var pending by mutableStateOf<NotesIntent?>(null)
    var pendingRecord: NotesRecord? = null
    var foreground by mutableStateOf(true)
    var generation = 0
    var job: Job? = null
    val dirty: Boolean get() = editing && (draft != (selected?.text ?: "") || newId != null)
    fun closeEditor() { editing = false; selected = null; draft = ""; newId = null; writeBlocked = false }
    fun clear() {
        generation++; job?.cancel(); job = null; busy = false
        records = emptyList(); cursor = null; query = ""; closeEditor()
        feedback = null; readFailed = false; retryCursor = null; pending = null; pendingRecord = null
    }
}

/** Reject unpaired surrogates; size conservatively includes ASCII JSON escaping and record overhead. */
private fun notesValueValid(text: String, value: JSONObject): Boolean {
    if (text.isBlank() || text.codePointCount(0, text.length) > 2000) return false
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        if (c.isHighSurrogate()) { if (i >= text.length || !text[i++].isLowSurrogate()) return false }
        else if (c.isLowSurrogate()) return false
    }
    val json = value.toString()
    var size = 0L
    for (c in json) size += if (c < ' ' || c > '~') 6 else 1
    // IDs, revision, home, origin, field names and braces comfortably fit in the reserved 1384 bytes.
    return size <= 15000
}

@Composable
private fun NotesButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm)) else Modifier)
    if (enabled) GhostButton(label, onClick, modifier)
    else Text(label, modifier = modifier.padding(Spacing.md), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NotesField(label: String, value: String, enabled: Boolean, multiline: Boolean = false, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value, onChange, enabled = enabled, singleLine = !multiline,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 144.dp else 48.dp)
                .onFocusChanged { focused = it.hasFocus }.glassSurface(shape = RoundedCornerShape(Radius.sm))
                .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm)) else Modifier)
                .padding(Spacing.md).semantics { contentDescription = label })
    }
}

/** Local human UI only. Every call is fenced to this live joined home before and after suspension. */
@Composable
fun HomeV2Notes(node: HomeV2Node?, data: HomeV2Data?, host: ScreenHost) {
    val language = LocalConfiguration.current.locales[0].language
    val words = remember(language) { notesCopy(language) }
    if (node == null) {
        AmaliaScreen(title = words[NotesWord.TITLE.ordinal], onBack = host::goBack, backLabel = words[NotesWord.BACK.ordinal]) { insets ->
            Text(words[NotesWord.UNAVAILABLE.ordinal], modifier = Modifier.padding(insets), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    // Drafts are cleared on STOP; membership changes are fenced again before each call.
    val membership by node.status.collectAsStateWithLifecycle()
    key(node, data, membership.homeId, membership.running) {
        NotesContent(node, data, host, words)
    }
}

@Composable
private fun NotesContent(node: HomeV2Node, data: HomeV2Data?, host: ScreenHost, words: List<String>) {
    fun text(word: NotesWord) = words[word.ordinal]
    val session = remember { NotesSession() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val homeId = node.status.value.homeId
    val nodeId = node.status.value.nodeId
    val joined = data != null && homeId != null && data.homeId == homeId && node.status.value.running
    fun checkContext(generation: Int) {
        check(joined && session.foreground && session.generation == generation &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            node.status.value.homeId == homeId && node.status.value.running && node.status.value.nodeId == nodeId && data?.homeId == homeId)
    }
    suspend fun request(op: String, args: JSONObject, generation: Int): JSONObject {
        checkContext(generation)
        val result = checkNotNull(data).handle(op, args, nodeId)
        checkContext(generation)
        return result
    }
    fun arguments() = JSONObject().put("scope", "home").put("kind", "notes")
    fun parse(row: JSONObject): NotesRecord {
        check(row.getString("home_id") == homeId && row.getString("scope") == "home" &&
            row.getString("kind") == "notes" && !row.getBoolean("tombstone"))
        return NotesRecord(row.getString("id"), row.getLong("revision"), row.getString("origin"), JSONObject(row.getJSONObject("value").toString()))
    }
    fun read(cursor: String? = null) {
        if (session.busy || !joined || !session.foreground) return
        val generation = session.generation
        session.busy = true; session.readFailed = false
        session.job = scope.launch {
            try {
                val args = arguments().put("limit", minOf(25, 200 - if (cursor == null) 0 else session.records.size))
                cursor?.let { args.put("cursor", it) }
                val result = request("data.list", args, generation)
                val rows = result.getJSONArray("records")
                val page = (0 until rows.length()).map { parse(rows.getJSONObject(it)) }
                session.records = if (cursor == null) page else (session.records + page).distinctBy { it.id }
                session.cursor = result.opt("next_cursor") as? String
                session.retryCursor = null
                // Refresh never rebases or replaces the editor snapshot, even after a conflict.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (session.generation == generation) { session.readFailed = true; session.retryCursor = cursor }
            } finally { if (session.generation == generation) session.busy = false }
        }
    }
    fun write(deleting: Boolean) {
        if (session.busy || session.writeBlocked || !joined || !session.editing || !session.foreground) return
        val record = session.selected
        if (deleting && record == null) return
        if (!deleting && record != null && record.text == null) return
        val value = JSONObject(record?.value?.toString() ?: "{}").put("text", session.draft)
        if (!deleting && !notesValueValid(session.draft, value)) { session.feedback = NotesWord.INVALID; return }
        // Retain the same UUID before dispatch, including on ambiguous failure; never allocate on retry.
        val id = record?.id ?: (session.newId ?: UUID.randomUUID().toString().also { session.newId = it })
        val args = arguments().put("id", id).put("expected_revision", record?.expected() ?: 0)
        if (!deleting) args.put("value", value)
        val generation = session.generation
        session.busy = true; session.feedback = null
        session.job = scope.launch {
            try {
                val result = request(if (deleting) "data.delete" else "data.put", args, generation)
                if (deleting) { session.records = session.records.filterNot { it.id == id }; session.closeEditor() }
                else {
                    val saved = parse(result.getJSONObject("record"))
                    session.selected = saved; session.newId = null; session.draft = saved.text.orEmpty()
                    session.records = (session.records.filterNot { it.id == id } + saved).sortedBy { it.id }.take(200)
                }
                // Any mutation invalidates the previous list cursor. Refresh is an explicit read.
                session.cursor = null; session.readFailed = false; session.retryCursor = null
                session.feedback = NotesWord.DONE
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (session.generation == generation) {
                    session.writeBlocked = true
                    session.feedback = if (error is HomeV2Exception && error.code == "revision_conflict") NotesWord.CONFLICT else NotesWord.WRITE_ERROR
                }
            } finally { if (session.generation == generation) session.busy = false }
        }
    }
    fun applyIntent(intent: NotesIntent, record: NotesRecord? = null) {
        session.feedback = null
        when (intent) {
            NotesIntent.NEW -> { session.closeEditor(); session.editing = true }
            NotesIntent.SELECT -> { session.closeEditor(); session.selected = record; session.draft = record?.text.orEmpty(); session.editing = true }
            NotesIntent.CLOSE -> session.closeEditor()
            NotesIntent.BACK -> host.goBack()
            NotesIntent.DELETE -> write(true)
        }
    }
    fun intent(intent: NotesIntent, record: NotesRecord? = null) {
        if (session.busy) return
        if (session.dirty || intent == NotesIntent.DELETE) { session.pending = intent; session.pendingRecord = record }
        else applyIntent(intent, record)
    }
    DisposableEffect(session, lifecycle) {
        session.foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { session.foreground = false; session.clear() }
            if (event == Lifecycle.Event.ON_START) session.foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.foreground = false; session.clear() }
    }
    LaunchedEffect(session, joined) { if (joined && session.foreground) read() }
    BackHandler(enabled = session.dirty || session.busy) { intent(NotesIntent.BACK) }
    val enabled = joined && session.foreground && !session.busy
    val filtered = session.records.filter { session.query.isBlank() || (it.text ?: "").contains(session.query, ignoreCase = true) || it.id.contains(session.query, ignoreCase = true) }

    AmaliaScreen(title = text(NotesWord.TITLE), onBack = { intent(NotesIntent.BACK) }, backLabel = text(NotesWord.BACK)) { insets ->
        LazyColumn(contentPadding = insets, modifier = Modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (!joined) item("unavailable") { Text(text(NotesWord.UNAVAILABLE), style = MaterialTheme.typography.bodyMedium) }
            if (joined) {
                item("feedback") {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        if (session.busy) SkeletonList(items = 1)
                        session.feedback?.let { word -> Text(text(word), style = MaterialTheme.typography.bodyMedium,
                            color = if (word == NotesWord.DONE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                        if (session.readFailed) {
                            Text(text(NotesWord.READ_ERROR), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            NotesButton(text(NotesWord.RETRY), enabled) { read(session.retryCursor) }
                        }
                    }
                }
                item("controls") { GlassCard(contentPadding = Spacing.md) {
                    Text(text(NotesWord.LOCAL), style = MaterialTheme.typography.bodyMedium)
                    NotesField(text(NotesWord.SEARCH), session.query, enabled) { if (it.length <= 256) session.query = it }
                    NotesButton(text(NotesWord.REFRESH), enabled) { read() }
                    NotesButton(text(NotesWord.NEW), enabled) { intent(NotesIntent.NEW) }
                } }
                if (session.editing) item("editor") { GlassCard(contentPadding = Spacing.md) {
                    SectionTitle(text(if (session.selected == null) NotesWord.NEW else NotesWord.TEXT))
                    session.selected?.let { Text(it.id, style = MaterialTheme.typography.labelMedium) }
                    if (session.selected != null && session.selected?.text == null) Text(text(NotesWord.INCOMPATIBLE), style = MaterialTheme.typography.bodyMedium)
                    else {
                        NotesField(text(NotesWord.TEXT), session.draft, enabled, multiline = true) { value ->
                            // Keep invalid input visible with feedback rather than silently truncating pasted text.
                            if (value.length <= 8000) { session.draft = value; session.feedback = null }
                            else session.feedback = NotesWord.INVALID
                        }
                        Text("${session.draft.codePointCount(0, session.draft.length)} / 2000", style = MaterialTheme.typography.labelMedium)
                        NotesButton(text(NotesWord.SAVE), enabled && !session.writeBlocked && session.draft.isNotBlank()) { write(false) }
                    }
                    NotesButton(text(NotesWord.DELETE), enabled && !session.writeBlocked && session.selected != null) { intent(NotesIntent.DELETE) }
                    NotesButton(text(NotesWord.CANCEL), enabled) { intent(NotesIntent.CLOSE) }
                } }
                if (filtered.isEmpty()) item("empty") { Text(text(NotesWord.EMPTY), style = MaterialTheme.typography.bodyMedium) }
                items(filtered, key = { "note:${it.id}" }) { record -> GlassCard(contentPadding = Spacing.md) {
                    Text(record.text ?: text(NotesWord.INCOMPATIBLE), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(record.id, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NotesButton(text(NotesWord.SELECT), enabled) { intent(NotesIntent.SELECT, record) }
                } }
                if (session.cursor != null) item("more") {
                    if (session.records.size >= 200) Text(text(NotesWord.LIMIT), style = MaterialTheme.typography.bodyMedium)
                    else NotesButton(text(NotesWord.MORE), enabled) { read(session.cursor) }
                }
            }
        }
    }
    session.pending?.let { pending ->
        GlassDialog(
            title = text(if (pending == NotesIntent.DELETE) NotesWord.DELETE else NotesWord.DISCARD),
            message = text(if (pending == NotesIntent.DELETE) NotesWord.DELETE_WARNING else NotesWord.DISCARD_WARNING),
            confirmLabel = text(if (pending == NotesIntent.DELETE) NotesWord.DELETE else NotesWord.DISCARD),
            dismissLabel = text(NotesWord.CANCEL),
            destructive = true,
            loading = !enabled,
            onConfirm = {
                val record = session.pendingRecord
                session.pending = null; session.pendingRecord = null; applyIntent(pending, record)
            },
            onDismiss = { session.pending = null; session.pendingRecord = null },
        )
    }
}
