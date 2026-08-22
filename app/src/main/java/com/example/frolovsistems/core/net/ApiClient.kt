package com.example.frolovsistems.core.net

import com.example.frolovsistems.core.prefs.AppSettings
import com.example.frolovsistems.core.prefs.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/** Ошибка запроса, уже переведённая в человекочитаемый текст. */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
) : Exception(message) {
    val isUnauthorized: Boolean get() = status == 401
}

/**
 * Тонкий клиент над Ktor. Адрес сервера и токен читаются из настроек
 * на каждом запросе — благодаря этому смена адреса применяется сразу,
 * без пересоздания клиента.
 */
class ApiClient(private val settings: AppSettings) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(this@ApiClient.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    // ------------------------------ Авторизация ------------------------------

    suspend fun login(login: String, password: String): LoginResponse =
        call(HttpMethod.Post, "/api/v1/auth/login", body = LoginRequest(login, password), auth = false)

    suspend fun me(): UserDto = call(HttpMethod.Get, "/api/v1/auth/me")

    suspend fun changePassword(current: String, new: String) {
        callUnit(HttpMethod.Post, "/api/v1/auth/password", body = ChangePasswordRequest(current, new))
    }

    /** Проверка доступности сервера — не требует токена. */
    suspend fun health(config: ServerConfig? = null): HealthDto =
        call(HttpMethod.Get, "/api/v1/health", auth = false, overrideConfig = config)

    // ------------------------------ Контент сайта ----------------------------

    suspend fun siteContent(): SiteContentDto = call(HttpMethod.Get, "/api/v1/site", auth = false)

    suspend fun saveSiteContent(content: SiteContentDto): SiteContentDto =
        call(HttpMethod.Put, "/api/v1/admin/site", body = content)

    suspend fun resetSiteContent(): SiteContentDto = call(HttpMethod.Post, "/api/v1/admin/site/reset")

    // --------------------------------- CRM -----------------------------------

    suspend fun stats(): StatsDto = call(HttpMethod.Get, "/api/v1/admin/stats")

    suspend fun clients(query: String = ""): List<ClientDto> =
        call<ListResponse<ClientDto>>(
            HttpMethod.Get, "/api/v1/admin/clients",
            params = mapOf("q" to query),
        ).items

    suspend fun createClient(client: ClientDto): ClientDto =
        call(HttpMethod.Post, "/api/v1/admin/clients", body = client)

    suspend fun updateClient(id: Long, client: ClientDto): ClientDto =
        call(HttpMethod.Put, "/api/v1/admin/clients/$id", body = client)

    suspend fun deleteClient(id: Long) = callUnit(HttpMethod.Delete, "/api/v1/admin/clients/$id")

    suspend fun orders(status: String = ""): List<OrderDto> =
        call<ListResponse<OrderDto>>(
            HttpMethod.Get, "/api/v1/admin/orders",
            params = mapOf("status" to status),
        ).items

    suspend fun createOrder(order: OrderDto): OrderDto =
        call(HttpMethod.Post, "/api/v1/admin/orders", body = order)

    suspend fun updateOrder(id: Long, order: OrderDto): OrderDto =
        call(HttpMethod.Put, "/api/v1/admin/orders/$id", body = order)

    suspend fun deleteOrder(id: Long) = callUnit(HttpMethod.Delete, "/api/v1/admin/orders/$id")

    suspend fun requests(status: String = ""): List<RequestDto> =
        call<ListResponse<RequestDto>>(
            HttpMethod.Get, "/api/v1/admin/requests",
            params = mapOf("status" to status),
        ).items

    suspend fun updateRequestStatus(id: Long, status: String): RequestDto =
        call(HttpMethod.Patch, "/api/v1/admin/requests/$id", body = StatusUpdate(status))

    suspend fun deleteRequest(id: Long) = callUnit(HttpMethod.Delete, "/api/v1/admin/requests/$id")

    // ------------------------------- Внутреннее ------------------------------

    private suspend inline fun <reified T> call(
        method: HttpMethod,
        path: String,
        body: Any? = null,
        params: Map<String, String> = emptyMap(),
        auth: Boolean = true,
        overrideConfig: ServerConfig? = null,
    ): T = json.decodeFromString(execute(method, path, body, params, auth, overrideConfig))

    private suspend fun callUnit(
        method: HttpMethod,
        path: String,
        body: Any? = null,
        auth: Boolean = true,
    ) {
        execute(method, path, body, emptyMap(), auth, null)
    }

    private suspend fun execute(
        method: HttpMethod,
        path: String,
        body: Any?,
        params: Map<String, String>,
        auth: Boolean,
        overrideConfig: ServerConfig?,
    ): String {
        val prefs = settings.preferences.first()
        val config = overrideConfig ?: prefs.server
        if (!config.isValid) {
            throw ApiException(0, "no_server", "Не задан адрес сервера — откройте настройки подключения")
        }

        val url = config.baseUrl + path
        val response: HttpResponse = try {
            client.request(url) {
                this.method = method
                if (auth && prefs.token.isNotBlank()) {
                    header("Authorization", "Bearer ${prefs.token}")
                }
                params.forEach { (key, value) -> if (value.isNotBlank()) parameter(key, value) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                applyTimeout(config)
            }
        } catch (e: CancellationException) {
            // Отмена корутины — не сетевая ошибка, пробрасываем как есть.
            throw e
        } catch (e: Exception) {
            throw ApiException(0, "network", networkErrorMessage(url, e))
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val parsed = runCatching { json.decodeFromString<ApiErrorBody>(text) }.getOrNull()
            throw ApiException(
                status = response.status.value,
                code = parsed?.code ?: "http_${response.status.value}",
                message = parsed?.message?.takeIf { it.isNotBlank() }
                    ?: "Ошибка ${response.status.value}",
            )
        }
        // 204 No Content и подобные — отдаём пустой JSON-объект, чтобы decode не падал.
        return text.ifBlank { "{}" }
    }

    private fun HttpRequestBuilder.applyTimeout(config: ServerConfig) {
        val millis = config.timeoutSec.coerceIn(5, 120) * 1000L
        timeout {
            requestTimeoutMillis = millis
            connectTimeoutMillis = millis
            socketTimeoutMillis = millis
        }
    }

    /** Не забываем закрывать движок, когда приложение завершает работу. */
    fun close() = client.close()

    private companion object {
        /**
         * Текст сетевой ошибки: без адреса и типа исключения понять причину
         * невозможно, поэтому пишем и то, и другое.
         */
        fun networkErrorMessage(url: String, e: Throwable): String {
            val hint = when (e) {
                is UnknownHostException -> "не удалось разрешить адрес — проверьте хост и интернет на устройстве"
                is ConnectException -> "сервер не принял соединение — проверьте порт и что служба запущена"
                is SocketTimeoutException -> "истекло время ожидания — сервер не ответил"
                is UnknownServiceException -> "запрос заблокирован политикой сети (обычно это запрет HTTP без TLS)"
                is SSLException -> "ошибка TLS — для http выберите схему http, а не https"
                else -> e.message?.takeIf { it.isNotBlank() } ?: "нет соединения"
            }
            return "Не удалось связаться с $url\n${e::class.simpleName}: $hint"
        }
    }
}
