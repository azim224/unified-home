package org.amalia.unifiedhome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder

// ─────────────────────────────────────────────────────────────────────────────
//  Транспорт дома: как команда попадает в железо и что оно отвечает.
//
//  Два честных уровня:
//    * `sim`  — симулятор: состояние меняется здесь же, команда подтверждена;
//    * `http` — мост в локальной сети (свой сервер, ESP, Home Assistant рядом);
//    * `homeassistant` — REST API Home Assistant с проверкой состояния после
//      команды: «отправила» и «подтверждено» — разные вещи.
//
//  Почему свой HTTP-клиент, а не HttpURLConnection для `http://`: приложение
//  собрано с targetSdk 36, и системный клиент запрещает cleartext-трафик.
//  Плагин не может ни добавить networkSecurityConfig, ни изменить манифест,
//  поэтому обычный LAN-мост ходит через сокет, а `https://` — через системный
//  клиент с проверкой сертификата (без «доверять всему»).
// ─────────────────────────────────────────────────────────────────────────────

/** Результат чтения состояния. */
data class ReadResult(
    val ok: Boolean,
    val state: Map<String, Any?> = emptyMap(),
    val error: String = "",
)

/** Результат применения команды: `status` — из [ApplyStatus]. */
data class ApplyOutcome(
    val ok: Boolean,
    val status: String,
    val state: Map<String, Any?> = emptyMap(),
    val error: String = "",
)

// ── HTTP ─────────────────────────────────────────────────────────────────────

internal object LanHttp {

    data class Response(val code: Int, val body: String)

    /** Защита от «умного» устройства, решившего прислать гигабайт JSON. */
    private const val MAX_BODY_BYTES = 512 * 1024

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 5000,
    ): Response {
        val uri = try {
            URI(url.trim())
        } catch (error: Exception) {
            throw IllegalArgumentException("Некорректный адрес моста.")
        }
        require(uri.rawUserInfo == null && uri.rawFragment == null) {
            "Адрес не должен содержать учётные данные или фрагмент."
        }
        require(!uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)) {
            "Некорректный хост или порт моста."
        }
        require(method.matches(Regex("[A-Za-z]+"))) { "Некорректный HTTP-метод." }
        headers.forEach { (key, value) ->
            require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "Некорректный HTTP-заголовок."
            }
        }
        return when (uri.scheme?.lowercase()) {
            "http" -> rawSocket(method, uri, headers, body, timeoutMs)
            "https" -> secure(method, url.trim(), headers, body, timeoutMs)
            else -> throw IllegalArgumentException("Адрес должен начинаться с http:// или https://")
        }
    }

    /**
     * HTTP/1.1 поверх сокета — единственный способ для cleartext в локальной сети.
     * `Connection: close` избавляет от пула соединений: одна команда, один сокет.
     */
    private fun rawSocket(
        method: String,
        uri: URI,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int,
    ): Response {
        val host = uri.host ?: throw IllegalArgumentException("В адресе нет хоста.")
        val port = if (uri.port > 0) uri.port else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val payload = body?.toByteArray(Charsets.UTF_8)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val head = buildString {
                append(method.uppercase()).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host)
                if (port != 80) append(':').append(port)
                append("\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n")
                if (payload != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(payload.size).append("\r\n")
                }
                headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            if (payload != null) out.write(payload)
            out.flush()
            return parse(readLimited(socket.getInputStream()))
        }
    }

    private fun secure(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int,
    ): Response {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { connection.outputStream.use { stream -> stream.write(it.toByteArray(Charsets.UTF_8)) } }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { readLimited(it) } ?: ByteArray(0)
            val length = connection.getHeaderField("Content-Length")
            val encoding = connection.getHeaderField("Transfer-Encoding")
            require(encoding == null || (encoding.equals("chunked", ignoreCase = true) && length == null)) {
                "Неподдерживаемое кодирование HTTP-ответа."
            }
            if (length != null) {
                val expected = length.toLongOrNull()
                require(length.matches(Regex("[0-9]+")) && expected == bytes.size.toLong()) {
                    "Неполный или некорректный HTTP-ответ."
                }
            }
            require(code in 100..599) { "Некорректный HTTP-статус." }
            return Response(code, String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            require(read <= MAX_BODY_BYTES - buffer.size()) { "HTTP-ответ слишком велик." }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /** Разбирает ответ: статус, заголовки и тело (с поддержкой chunked). */
    private fun parse(raw: ByteArray): Response {
        val text = String(raw, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        require(split >= 0) { "В HTTP-ответе нет заголовков." }
        val head = text.substring(0, split).split("\r\n")
        val status = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: .*)?").matchEntire(head.first())
            ?: throw IllegalArgumentException("Некорректный HTTP-статус.")
        val code = status.groupValues[1].toInt()
        val headers = LinkedHashMap<String, String>()
        for (line in head.drop(1)) {
            val colon = line.indexOf(':')
            require(colon > 0 && line.substring(0, colon).matches(
                Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"),
            )) { "Некорректный заголовок HTTP-ответа." }
            val name = line.substring(0, colon).lowercase()
            val value = line.substring(colon + 1).trim()
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "Некорректный заголовок HTTP-ответа."
            }
            require(!headers.containsKey(name) || name !in setOf("content-length", "transfer-encoding")) {
                "Неоднозначная длина HTTP-ответа."
            }
            headers[name] = value
        }
        val encoding = headers["transfer-encoding"]
        val length = headers["content-length"]
        require(encoding == null || (encoding.equals("chunked", ignoreCase = true) && length == null)) {
            "Неподдерживаемое кодирование HTTP-ответа."
        }
        val bodyBytes = raw.copyOfRange(split + 4, raw.size)
        val body = if (encoding != null) dechunk(bodyBytes) else {
            if (length != null) {
                require(length.matches(Regex("[0-9]+")) && length.toLongOrNull() == bodyBytes.size.toLong()) {
                    "Неполный или некорректный HTTP-ответ."
                }
            }
            bodyBytes
        }
        return Response(code, String(body, Charsets.UTF_8))
    }

    private fun dechunk(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (true) {
            val lineEnd = indexOfCrLf(raw, index)
            require(lineEnd >= 0) { "Неполный chunked-ответ." }
            val line = String(raw, index, lineEnd - index, Charsets.ISO_8859_1)
            val sizeText = line.substringBefore(';')
            require(sizeText.matches(Regex("[0-9A-Fa-f]+"))) { "Некорректный размер HTTP-блока." }
            val size = sizeText.toLongOrNull(16)
                ?: throw IllegalArgumentException("Некорректный размер HTTP-блока.")
            index = lineEnd + 2
            if (size == 0L) {
                // После последнего блока обязательны трейлеры и пустая строка.
                while (true) {
                    val end = indexOfCrLf(raw, index)
                    require(end >= 0) { "Неполный chunked-ответ." }
                    if (end == index) {
                        require(end + 2 == raw.size) { "Лишние данные после HTTP-ответа." }
                        return out.toByteArray()
                    }
                    val trailer = String(raw, index, end - index, Charsets.ISO_8859_1)
                    val colon = trailer.indexOf(':')
                    require(colon > 0 && trailer.substring(0, colon).matches(
                        Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"),
                    ) && trailer.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                        "Некорректный трейлер HTTP-ответа."
                    }
                    index = end + 2
                }
            }
            require(size <= MAX_BODY_BYTES - out.size() && size <= raw.size.toLong() - index - 2) {
                "Неполный или слишком большой HTTP-блок."
            }
            val end = index + size.toInt()
            require(raw[end] == '\r'.code.toByte() && raw[end + 1] == '\n'.code.toByte()) {
                "Некорректное окончание HTTP-блока."
            }
            out.write(raw, index, size.toInt())
            index = end + 2
        }
    }

    private fun indexOfCrLf(raw: ByteArray, from: Int): Int {
        for (index in from until raw.size - 1) {
            if (raw[index] == '\r'.code.toByte() && raw[index + 1] == '\n'.code.toByte()) return index
        }
        return -1
    }
}

// ── Чтение состояния ─────────────────────────────────────────────────────────

suspend fun readDevice(device: HomeDevice): ReadResult = withContext(Dispatchers.IO) {
    when (device.adapter) {
        Adapter.SIM -> ReadResult(true, device.state)
        Adapter.HTTP -> readHttp(device)
        Adapter.HOME_ASSISTANT -> readHomeAssistant(device)
        else -> ReadResult(false, error = "Неизвестный адаптер «${device.adapter}».")
    }
}

private fun readHttp(device: HomeDevice): ReadResult {
    val address = device.address.trim()
    if (!address.startsWith("http://") && !address.startsWith("https://")) {
        return ReadResult(false, error = "У моста не задан адрес.")
    }
    return try {
        val separator = if (address.contains('?')) '&' else '?'
        val url = address + separator + "device=" + URLEncoder.encode(device.id, "UTF-8")
        val response = LanHttp.request("GET", url, authHeaders(device.token))
        if (response.code !in 200..299) {
            return ReadResult(false, error = "Мост ответил HTTP ${response.code}.")
        }
        val state = JSONObject(response.body).optJSONObject("state")
            ?: return ReadResult(false, error = "В ответе моста нет поля state.")
        ReadResult(true, jsonToState(state))
    } catch (error: Exception) {
        ReadResult(false, error = "Мост недоступен или вернул некорректный ответ.")
    }
}

private fun readHomeAssistant(device: HomeDevice): ReadResult {
    if (device.address.isBlank() || device.entity.isBlank() || device.token.isBlank()) {
        return ReadResult(false, error = "Для Home Assistant нужны адрес, entity_id и токен.")
    }
    return try {
        val url = device.address.trimEnd('/') + "/api/states/" + URLEncoder.encode(device.entity, "UTF-8")
        val response = LanHttp.request("GET", url, headers = authHeaders(device.token))
        when {
            response.code == 401 || response.code == 403 ->
                ReadResult(false, error = "Home Assistant отклонил токен.")
            response.code !in 200..299 ->
                ReadResult(false, error = "Home Assistant ответил HTTP ${response.code}.")
            else -> {
                val state = mapHomeAssistant(device, JSONObject(response.body))
                    ?: return ReadResult(false, error = "Состояние Home Assistant недоступно или неизвестно.")
                ReadResult(true, state)
            }
        }
    } catch (error: Exception) {
        ReadResult(false, error = "Home Assistant недоступен или вернул некорректный ответ.")
    }
}

// ── Применение команды ───────────────────────────────────────────────────────

suspend fun applyDevice(
    device: HomeDevice,
    action: String,
    params: Map<String, Any?>,
    idempotencyKey: String,
): ApplyOutcome = withContext(Dispatchers.IO) {
    if (!device.supports(action)) {
        return@withContext ApplyOutcome(
            ok = false,
            status = ApplyStatus.FAILED,
            error = "«${device.name}» не умеет «$action».",
        )
    }
    val clean = try {
        normalizeParams(action, params, device.capabilities)
    } catch (error: IllegalArgumentException) {
        return@withContext ApplyOutcome(
            ok = false,
            status = ApplyStatus.FAILED,
            error = "Неверные параметры команды.",
        )
    }
    when (device.adapter) {
        Adapter.SIM -> {
            val desired = projectState(device, action, clean)
            val merged = LinkedHashMap<String, Any?>(device.state)
            merged.putAll(desired)
            ApplyOutcome(true, ApplyStatus.CONFIRMED, merged)
        }
        Adapter.HTTP -> applyHttp(device, action, clean, idempotencyKey)
        Adapter.HOME_ASSISTANT -> applyHomeAssistant(device, action, clean)
        else -> ApplyOutcome(false, ApplyStatus.FAILED, error = "Неизвестный адаптер «${device.adapter}».")
    }
}

private fun applyHttp(
    device: HomeDevice,
    action: String,
    params: Map<String, Any?>,
    idempotencyKey: String,
): ApplyOutcome {
    val address = device.address.trim()
    if (!address.startsWith("http://") && !address.startsWith("https://")) {
        return ApplyOutcome(false, ApplyStatus.FAILED, error = "У моста не задан адрес.")
    }
    val payload = JSONObject()
        .put("device", device.id)
        .put("action", action)
        .put("params", stateToJson(params))
        .toString()
    var sent = false
    return try {
        sent = true
        val response = LanHttp.request(
            method = "POST",
            url = address,
            headers = authHeaders(device.token) + ("Idempotency-Key" to idempotencyKey),
            body = payload,
        )
        when {
            response.code !in 200..299 -> ApplyOutcome(
                ok = false,
                status = ApplyStatus.FAILED,
                error = "Мост ответил HTTP ${response.code}.",
            )
            else -> {
                val reply = if (response.body.isBlank()) null else JSONObject(response.body)
                val actual = reply?.optJSONObject("state")
                require(reply == null || !reply.has("state") || actual != null) {
                    "Некорректное состояние в ответе моста."
                }
                if (actual == null) {
                    ApplyOutcome(true, ApplyStatus.SENT)
                } else {
                    val state = jsonToState(actual)
                    val confirmed = matches(projectState(device, action, params), state)
                    ApplyOutcome(
                        ok = true,
                        status = if (confirmed) ApplyStatus.CONFIRMED else ApplyStatus.UNKNOWN,
                        state = state,
                        error = if (confirmed) "" else "Мост ответил состоянием, которое не совпало с командой.",
                    )
                }
            }
        }
    } catch (error: Exception) {
        // Отправка могла дойти: после обрыва связи состояние неизвестно, а не «провал».
        val status = if (sent) ApplyStatus.UNKNOWN else ApplyStatus.FAILED
        ApplyOutcome(false, status, error = "Мост недоступен или вернул некорректный ответ.")
    }
}

private fun applyHomeAssistant(
    device: HomeDevice,
    action: String,
    params: Map<String, Any?>,
): ApplyOutcome {
    if (device.address.isBlank() || device.entity.isBlank() || device.token.isBlank()) {
        return ApplyOutcome(
            false, ApplyStatus.FAILED,
            error = "Для Home Assistant нужны адрес, entity_id и токен.",
        )
    }
    val service = when (device.kind) {
        Kind.LIGHT -> when (action) {
            Action.ON -> "light/turn_on"
            Action.OFF -> "light/turn_off"
            Action.TOGGLE -> "light/toggle"
            else -> "light/turn_on"
        }
        Kind.SWITCH -> when (action) {
            Action.ON -> "switch/turn_on"
            Action.OFF -> "switch/turn_off"
            else -> "switch/toggle"
        }
        Kind.CLIMATE -> when (action) {
            Action.SET -> "climate/set_temperature"
            Action.ON -> "climate/turn_on"
            else -> "climate/turn_off"
        }
        Kind.MEDIA -> when (action) {
            Action.SET -> "media_player/volume_set"
            Action.ON -> "media_player/turn_on"
            Action.TOGGLE -> "media_player/toggle"
            else -> "media_player/turn_off"
        }
        Kind.COVER -> when (action) {
            Action.OPEN -> "cover/open_cover"
            Action.CLOSE -> "cover/close_cover"
            else -> "cover/set_cover_position"
        }
        else -> return ApplyOutcome(
            false, ApplyStatus.FAILED,
            error = "Home Assistant не управляет типом «${device.kind}».",
        )
    }
    val data = JSONObject().put("entity_id", device.entity)
    when (device.kind) {
        Kind.LIGHT -> {
            (params["brightness"] as? Number)?.let { data.put("brightness_pct", it.toInt()) }
            (params["color_temp"] as? Number)?.let { data.put("color_temp_kelvin", it.toInt()) }
        }
        Kind.CLIMATE -> if (action == Action.SET) {
            (params["temperature"] as? Number)?.let { data.put("temperature", it.toDouble()) }
        }
        Kind.MEDIA -> (params["volume"] as? Number)?.let { data.put("volume_level", it.toDouble() / 100.0) }
        Kind.COVER -> if (action == Action.SET) {
            data.put("position", (params["position"] as? Number)?.toInt() ?: 50)
        }
        else -> Unit
    }

    val url = device.address.trimEnd('/') + "/api/services/" + service
    var sent = false
    return try {
        sent = true
        val response = LanHttp.request("POST", url, authHeaders(device.token), data.toString())
        when {
            response.code == 401 || response.code == 403 ->
                ApplyOutcome(false, ApplyStatus.FAILED, error = "Home Assistant отклонил токен.")
            response.code !in 200..299 ->
                ApplyOutcome(false, ApplyStatus.FAILED, error = "Home Assistant ответил HTTP ${response.code}.")
            else -> {
                if (device.kind == Kind.CLIMATE && action == Action.ON && params.containsKey("temperature")) {
                    val temperature = JSONObject().put("entity_id", device.entity)
                        .put("temperature", params["temperature"])
                    val temperatureResponse = LanHttp.request(
                        "POST", device.address.trimEnd('/') + "/api/services/climate/set_temperature",
                        authHeaders(device.token), temperature.toString(),
                    )
                    if (temperatureResponse.code !in 200..299) {
                        return ApplyOutcome(
                            false, ApplyStatus.UNKNOWN,
                            error = "Команда включения отправлена, но температура не задана: HTTP ${temperatureResponse.code}.",
                        )
                    }
                }
                val verification = readHomeAssistant(device)
                when {
                    !verification.ok -> ApplyOutcome(
                        true, ApplyStatus.UNKNOWN, error = verification.error,
                    )
                    matches(projectState(device, action, params), verification.state) ->
                        ApplyOutcome(true, ApplyStatus.CONFIRMED, verification.state)
                    else -> ApplyOutcome(
                        true, ApplyStatus.UNKNOWN, verification.state,
                        "Команда отправлена, но состояние не совпало.",
                    )
                }
            }
        }
    } catch (error: Exception) {
        ApplyOutcome(
            ok = false,
            status = if (sent) ApplyStatus.UNKNOWN else ApplyStatus.FAILED,
            error = "Home Assistant недоступен или вернул некорректный ответ.",
        )
    }
}

private fun authHeaders(token: String): Map<String, String> =
    if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

/** Ответ Home Assistant → состояние дома. null, если сущность недоступна. */
private fun mapHomeAssistant(device: HomeDevice, payload: JSONObject): Map<String, Any?>? {
    val raw = payload.opt("state") as? String ?: return null
    if (raw.isBlank() || raw in setOf("unavailable", "unknown")) return null
    if (device.kind in setOf(Kind.LIGHT, Kind.SWITCH) && raw !in setOf("on", "off")) return null
    if (device.kind == Kind.CLIMATE && raw !in setOf(
        "off", "heat", "cool", "heat_cool", "auto", "dry", "fan_only",
    )) return null
    if (device.kind == Kind.MEDIA && raw !in setOf(
        "off", "on", "idle", "playing", "paused", "standby", "buffering",
    )) return null
    val attributes = payload.optJSONObject("attributes") ?: JSONObject()
    fun number(key: String): Double? = (attributes.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
    val power = raw == "on"
    val state = LinkedHashMap<String, Any?>()
    when (device.kind) {
        Kind.LIGHT -> {
            state["power"] = power
            number("brightness")?.takeIf { it in 0.0..255.0 }?.let {
                state["brightness"] = (it * 100 / 255).toInt()
            }
            number("color_temp_kelvin")?.takeIf { it > 0 }?.let { state["color_temp"] = it }
        }
        Kind.SWITCH -> state["power"] = power
        Kind.CLIMATE -> {
            state["power"] = raw != "off"
            number("temperature")?.let { state["temperature"] = it }
            number("current_temperature")?.let { state["current_temperature"] = it }
        }
        Kind.MEDIA -> {
            state["power"] = raw != "off"
            number("volume_level")?.takeIf { it in 0.0..1.0 }?.let {
                state["volume"] = (it * 100).toInt()
            }
        }
        Kind.COVER -> {
            number("current_position")?.takeIf { it in 0.0..100.0 }?.let { state["position"] = it }
        }
        else -> state["value"] = raw
    }
    return state
}
