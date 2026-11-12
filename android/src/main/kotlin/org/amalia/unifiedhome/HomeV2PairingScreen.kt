package org.amalia.unifiedhome

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.my.amali.ui.components.GhostButton
import com.my.amali.ui.components.GlassCard
import com.my.amali.ui.theme.Radius
import com.my.amali.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.amalia.unifiedhome.v2.HomeV2Exception
import org.amalia.unifiedhome.v2.HomeV2Pairing

private fun pairingCopy(language: String): List<String> = (when (language) {
    "ru" -> "Подключение устройств|Разрешить новое устройство на 120 с|Приём новых устройств открыт|Приём закрыт|Запрос от|Код проверки|Сравните коды на обоих устройствах. Одобряйте только знакомое устройство с совпадающим кодом.|Коды совпадают — одобрить|Отклонить устройство|Обнаруженные дома|Дома пока не найдены. Проверьте общую сеть.|Дом без названия|Готов к подключению|Приём закрыт — откройте его на хосте|Подключиться к|Подключение…|Сравните этот код с кодом на ХОСТЕ. Затем владелец должен одобрить подключение на хосте.|Коды совпадают — завершить|Ожидание одобрения хостом…|Отменить подключение|Подключение не выполнено. Проверьте сеть и повторите вручную.|Время ожидания истекло. Начните подключение заново.|Обнаружение не означает доверия. Вступайте только в доверенный дом: участники получают доступ к данным и действиям в пределах прав устройства."
    "de" -> "Geräte koppeln|Neues Gerät für 120 s zulassen|Kopplung geöffnet|Kopplung geschlossen|Anfrage von|Prüfcode|Codes auf beiden Geräten vergleichen. Nur bekannte Geräte mit gleichem Code zulassen.|Codes stimmen überein — zulassen|Gerät ablehnen|Gefundene Zuhause|Noch keine Zuhause gefunden. Gemeinsames Netzwerk prüfen.|Unbenanntes Zuhause|Bereit zum Koppeln|Geschlossen — am Host öffnen|Verbinden mit|Verbindung läuft…|Diesen Code am HOST vergleichen. Danach muss der Besitzer die Verbindung am Host zulassen.|Codes stimmen überein — abschließen|Warte auf Freigabe am Host…|Kopplung abbrechen|Kopplung fehlgeschlagen. Netzwerk prüfen und manuell erneut versuchen.|Zeit abgelaufen. Kopplung neu starten.|Entdeckung bedeutet kein Vertrauen. Nur einem vertrauten Zuhause beitreten: Mitglieder erhalten Zugriff auf Daten und Aktionen im Rahmen der Geräterechte."
    "es" -> "Vincular dispositivos|Permitir nuevo dispositivo durante 120 s|Vinculación abierta|Vinculación cerrada|Solicitud de|Código de verificación|Compara los códigos en ambos dispositivos. Aprueba solo un dispositivo conocido con el mismo código.|Los códigos coinciden — aprobar|Rechazar dispositivo|Hogares descubiertos|Aún no hay hogares. Comprueba la red compartida.|Hogar sin nombre|Disponible para vincular|Cerrado — ábrelo en el anfitrión|Conectar a|Conectando…|Compara este código en el ANFITRIÓN. Después, el propietario debe aprobar allí la conexión.|Los códigos coinciden — finalizar|Esperando aprobación del anfitrión…|Cancelar vinculación|No se pudo vincular. Revisa la red y vuelve a intentarlo manualmente.|Tiempo agotado. Inicia de nuevo la vinculación.|Descubrir no implica confianza. Únete solo a un hogar de confianza: sus miembros acceden a datos y acciones según los permisos del dispositivo."
    "fr" -> "Associer des appareils|Autoriser un appareil pendant 120 s|Association ouverte|Association fermée|Demande de|Code de vérification|Comparez les codes sur les deux appareils. Autorisez uniquement un appareil connu avec le même code.|Les codes correspondent — autoriser|Refuser l’appareil|Maisons découvertes|Aucune maison trouvée. Vérifiez le réseau commun.|Maison sans nom|Disponible pour association|Fermé — ouvrir sur l’hôte|Se connecter à|Connexion…|Comparez ce code sur l’HÔTE. Le propriétaire doit ensuite y autoriser la connexion.|Les codes correspondent — terminer|En attente de l’autorisation de l’hôte…|Annuler l’association|Échec de l’association. Vérifiez le réseau et réessayez manuellement.|Délai dépassé. Recommencez l’association.|La découverte ne garantit pas la confiance. Rejoignez uniquement une maison fiable : les membres accèdent aux données et actions selon les droits de l’appareil."
    "ja" -> "端末のペアリング|新しい端末を120秒間許可|ペアリング受付中|受付停止中|接続要求元|確認コード|両方の端末でコードを比較してください。コードが一致する既知の端末だけを承認してください。|コード一致 — 承認|端末を拒否|検出したホーム|ホームが見つかりません。同じネットワークを確認してください。|名前のないホーム|接続可能|受付停止中 — ホストで受付を開始|接続先|接続中…|このコードをホスト側で比較してください。その後、所有者がホストで承認する必要があります。|コード一致 — 完了へ|ホストの承認を待っています…|ペアリングをキャンセル|接続できませんでした。ネットワークを確認し、手動で再試行してください。|時間切れです。最初から接続してください。|検出は信頼を意味しません。信頼できるホームだけに参加してください。メンバーは端末の権限内でデータや操作にアクセスできます。"
    "zh" -> "设备配对|允许新设备加入120秒|配对已开放|配对已关闭|请求来自|验证码|比较两台设备的验证码。仅批准验证码一致的已知设备。|验证码一致 — 批准|拒绝设备|发现的家庭|尚未发现家庭。请检查共享网络。|未命名家庭|可以配对|已关闭 — 请在主机开放|连接到|正在连接…|请在主机上比较此验证码。然后由所有者在主机批准连接。|验证码一致 — 完成|正在等待主机批准…|取消配对|配对失败。请检查网络并手动重试。|等待超时。请重新开始配对。|发现不代表信任。仅加入可信的家庭：成员可在设备权限范围内访问数据和操作。"
    "hi" -> "डिवाइस जोड़ें|120 सेकंड के लिए नया डिवाइस अनुमति दें|जोड़ने की अनुमति खुली है|जोड़ने की अनुमति बंद है|अनुरोधकर्ता|सत्यापन कोड|दोनों डिवाइस पर कोड मिलाएँ। समान कोड वाले परिचित डिवाइस को ही अनुमति दें।|कोड समान हैं — अनुमति दें|डिवाइस अस्वीकार करें|खोजे गए घर|अभी कोई घर नहीं मिला। साझा नेटवर्क जाँचें।|बिना नाम का घर|जोड़ने के लिए उपलब्ध|बंद है — होस्ट पर खोलें|इससे जुड़ें|जुड़ रहा है…|इस कोड को होस्ट पर मिलाएँ। फिर मालिक को होस्ट पर अनुमति देनी होगी।|कोड समान हैं — पूरा करें|होस्ट की अनुमति की प्रतीक्षा…|जोड़ना रद्द करें|जोड़ना विफल रहा। नेटवर्क जाँचें और मैन्युअल रूप से फिर प्रयास करें।|समय समाप्त हुआ। दोबारा शुरू करें।|खोज का अर्थ भरोसा नहीं है। केवल विश्वसनीय घर से जुड़ें: सदस्य डिवाइस की अनुमतियों के भीतर डेटा और कार्रवाइयों तक पहुँच पाते हैं।"
    "ar" -> "إقران الأجهزة|السماح بجهاز جديد لمدة 120 ثانية|الإقران مفتوح|الإقران مغلق|طلب من|رمز التحقق|قارن الرمزين على الجهازين. وافق فقط على جهاز معروف برمز مطابق.|الرمزان متطابقان — موافقة|رفض الجهاز|المنازل المكتشفة|لم تُكتشف منازل بعد. تحقق من الشبكة المشتركة.|منزل بلا اسم|متاح للإقران|مغلق — افتحه على المضيف|الاتصال بـ|جارٍ الاتصال…|قارن هذا الرمز على المضيف. بعد ذلك يجب على المالك الموافقة على المضيف.|الرمزان متطابقان — إتمام|بانتظار موافقة المضيف…|إلغاء الإقران|تعذر الإقران. تحقق من الشبكة وحاول مجددًا يدويًا.|انتهت المهلة. ابدأ الإقران من جديد.|الاكتشاف لا يعني الثقة. انضم فقط إلى منزل موثوق: يصل الأعضاء إلى البيانات والإجراءات ضمن أذونات الجهاز."
    else -> "Pair devices|Allow a new device for 120 seconds|Pairing window open|Pairing window closed|Request from|Verification code|Compare codes on both devices. Approve only a known device with a matching code.|Codes match — approve|Reject device|Discovered homes|No homes found yet. Check the shared network.|Unnamed home|Available to pair|Closed — open pairing on the host|Connect to|Connecting…|Compare this code on the HOST. Then the owner must approve the connection on the host.|Codes match — finish|Waiting for host approval…|Cancel pairing|Pairing did not complete. Check the network and try again manually.|Pairing timed out. Start again.|Discovery does not mean trust. Join only a trusted home: members can access data and actions within the device’s permissions."
}).split('|') + when (language) {
    "ru" -> "Одобрено — ожидание подключаемого устройства…"
    "de" -> "Freigegeben — warte auf das beitretende Gerät…"
    "es" -> "Aprobado — esperando al dispositivo que se une…"
    "fr" -> "Autorisé — en attente de l’appareil qui rejoint…"
    "ja" -> "承認済み — 接続する端末を待っています…"
    "zh" -> "已批准 — 正在等待加入的设备…"
    "hi" -> "अनुमति दी गई — जुड़ने वाले डिवाइस की प्रतीक्षा…"
    "ar" -> "تمت الموافقة — بانتظار الجهاز المنضم…"
    else -> "Approved — waiting for the joining device…"
}


private fun completionCopy(language: String): List<String> = (when (language) {
    "ru" -> "Подключение завершено — устройство сохранило членство.|Дом сохранён; подтверждение завершения от хоста не получено.|Закрыть"
    "de" -> "Kopplung abgeschlossen — Gerät hat die Mitgliedschaft gespeichert.|Zuhause gespeichert; Abschluss vom Host nicht bestätigt.|Schließen"
    "es" -> "Vinculación completada — el dispositivo guardó la membresía.|Hogar guardado; el anfitrión no confirmó la finalización.|Cerrar"
    "fr" -> "Association terminée — l’appareil a enregistré son adhésion.|Maison enregistrée ; fin non confirmée par l’hôte.|Fermer"
    "ja" -> "接続完了 — 端末にメンバーシップが保存されました。|ホームは保存済みです。ホストによる完了確認は未受信です。|閉じる"
    "zh" -> "配对完成 — 设备已保存成员资格。|家庭已保存；尚未收到主机的完成确认。|关闭"
    "hi" -> "जोड़ना पूरा हुआ — डिवाइस ने सदस्यता सहेज ली है।|घर सहेजा गया; होस्ट से पूर्ण होने की पुष्टि नहीं मिली।|बंद करें"
    "ar" -> "اكتمل الإقران — حفظ الجهاز العضوية.|تم حفظ المنزل؛ لم يؤكد المضيف اكتمال الإقران.|إغلاق"
    else -> "Pairing completed — device saved membership.|Home saved; completion not confirmed by the host.|Dismiss"
}).split('|')

@Composable
private fun PairingButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.sm)) else Modifier)
    if (enabled) GhostButton(label, onClick, modifier)
    else Text(label, modifier = modifier.padding(Spacing.md), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// Deliberately not saveable: neither a code nor a pending trust decision survives recreation.
private class PairingAttempt(val name: String) {
    var code by mutableStateOf<String?>(null)
    var compared by mutableStateOf(false)
    var active = true
    var completing = false
    var job: Job? = null
}

@Composable
fun HomeV2PairingPanel(pairing: HomeV2Pairing, joined: Boolean, onJoin: suspend (String) -> Unit) {
    val language = LocalConfiguration.current.locales[0].language
    val copy = remember(language) { pairingCopy(language) }
    val completion = remember(language) { completionCopy(language) }
    val peers by pairing.peers.collectAsStateWithLifecycle()
    val status by pairing.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val latestJoin by rememberUpdatedState(onJoin)
    var attempt by remember(pairing) { mutableStateOf<PairingAttempt?>(null) }
    var joinedConfirmation by remember(pairing) { mutableStateOf<Boolean?>(null) }
    var error by remember(pairing) { mutableStateOf<Int?>(null) }
    var errorCode by remember(pairing) { mutableStateOf<String?>(null) }

    fun showError(failure: Exception) {
        errorCode = (failure as? HomeV2Exception)?.code
        error = if (errorCode == "pairing_expired") 21 else 20
    }

    fun cancelClient(client: PairingAttempt) {
        if (!client.active) return
        client.active = false
        client.code = null
        client.compared = false
        client.job?.cancel()
        if (attempt === client) attempt = null
        try { pairing.cancel() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { showError(failure) }
    }
    fun hostAction(action: () -> Unit) {
        error = null; errorCode = null
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { showError(failure) }
    }

    DisposableEffect(pairing) {
        onDispose { attempt?.let { cancelClient(it) } }
    }
    LaunchedEffect(joined) {
        if (joined) attempt?.takeUnless { it.completing }?.let { cancelClient(it) }
        else joinedConfirmation = null
    }
    val client = attempt
    LaunchedEffect(client, client?.compared) {
        if (client == null || !client.active || !client.compared || joined) return@LaunchedEffect
        try {
            // The timeout covers only polling, not the caller's membership operation.
            val invitation = withTimeoutOrNull(120_000L) {
                var invitation: String? = null
                while (invitation == null) {
                    invitation = withContext(Dispatchers.IO) { pairing.finish() }
                    if (invitation == null) delay(1_000L)
                }
                invitation
            }
            if (invitation == null) error = 21
            else if (client.active) {
                val receipt = pairing.captureReceipt()
                // Membership updates may cancel/reset pairing. Retain this local snapshot,
                // and do not let the joined-state effect abort persistence/acknowledgement.
                client.completing = true
                latestJoin(invitation)
                joinedConfirmation = false
                joinedConfirmation = pairing.acknowledge(receipt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            showError(failure)
        } finally {
            cancelClient(client)
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        GlassCard(contentPadding = Spacing.md) {
            Text(copy[0], style = MaterialTheme.typography.titleMedium)
            Text(copy[22], style = MaterialTheme.typography.bodyMedium)
            if (joined) {
                Text(when {
                    status.completed -> completion[0]
                    status.approved -> copy[23]
                    status.pendingName != null -> copy[5]
                    status.open -> copy[2]
                    else -> copy[3]
                }, style = MaterialTheme.typography.bodyMedium)
                PairingButton(copy[1], !status.open && status.pendingName == null) { hostAction { pairing.openWindow() } }
                joinedConfirmation?.let { Text(completion[if (it) 0 else 1], style = MaterialTheme.typography.bodyMedium) }
                val pendingName = status.pendingName
                val hostCode = status.code
                if (pendingName != null) {
                    Text("${copy[4]} · $pendingName", style = MaterialTheme.typography.bodyLarge)
                    if (!status.completed && !hostCode.isNullOrBlank()) {
                        Text("${copy[5]} · $hostCode", style = MaterialTheme.typography.headlineSmall)
                        Text(copy[6], style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!status.completed) PairingButton(copy[7], !status.approved && !hostCode.isNullOrBlank()) { hostAction { pairing.approve() } }
                    PairingButton(if (status.completed) completion[2] else copy[8]) { hostAction { pairing.reject() } }
                }
            }
            if (error != null || status.error != null) {
                val code = if (error != null) errorCode else status.error
                val message = copy[if (code == "pairing_expired") 21 else error ?: 20]
                Text(if (code == null) message else "$message ($code)",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        if (!joined && client != null) {
            GlassCard(contentPadding = Spacing.md) {
                Text("${copy[14]} ${client.name}", style = MaterialTheme.typography.titleMedium)
                val code = client.code
                if (code == null) Text(copy[15], style = MaterialTheme.typography.bodyMedium)
                else {
                    Text("${copy[5]} · $code", style = MaterialTheme.typography.headlineSmall)
                    Text(copy[16], style = MaterialTheme.typography.bodyMedium)
                    if (client.compared) Text(copy[18], style = MaterialTheme.typography.bodyMedium)
                    else PairingButton(copy[17]) { client.compared = true }
                }
                PairingButton(copy[19], !client.completing) { cancelClient(client) }
            }
        }
        GlassCard(contentPadding = Spacing.md) {
            Text(copy[9], style = MaterialTheme.typography.titleMedium)
            if (peers.isEmpty()) Text(copy[10], style = MaterialTheme.typography.bodyMedium)
            for (peer in peers) {
                val name = peer.name.ifBlank { copy[11] }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(if (peer.open) copy[12] else copy[13], style = MaterialTheme.typography.bodyMedium)
                    if (!joined) PairingButton("${copy[14]} $name", peer.open && client == null) {
                        if (attempt == null) {
                            error = null; errorCode = null
                            val next = PairingAttempt(name)
                            attempt = next
                            next.job = scope.launch {
                                try {
                                    val code = withContext(Dispatchers.IO) { pairing.begin(peer) }
                                    check(code.isNotBlank())
                                    if (next.active) next.code = code
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    showError(failure)
                                    cancelClient(next)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
