package com.example.frolovsistems.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ------------------------------- Авторизация -------------------------------

@Serializable
data class LoginRequest(val login: String, val password: String)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: String = "",
    val user: UserDto = UserDto(),
)

@Serializable
data class UserDto(
    val id: Long = 0,
    val login: String = "",
    val displayName: String = "",
    val role: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

// ------------------------------ Контент сайта ------------------------------

@Serializable
data class SiteContentDto(
    val siteName: String = "",
    val tagline: String = "",
    val ticker: TickerDto = TickerDto(),
    val hero: HeroDto = HeroDto(),
    val about: AboutDto = AboutDto(),
    val services: List<ServiceDto> = emptyList(),
    val advantages: List<AdvantageDto> = emptyList(),
    val contacts: ContactsDto = ContactsDto(),
    val appearance: AppearanceDto = AppearanceDto(),
    val footerNote: String = "",
    val revision: Long = 0,
    val updatedAt: String = "",
)

@Serializable
data class TickerDto(
    val enabled: Boolean = true,
    val text: String = "",
    val speedSec: Int = 25,
)

@Serializable
data class HeroDto(
    val title: String = "",
    val subtitle: String = "",
    val primaryCta: String = "",
    val secondaryCta: String = "",
    val badge: String = "",
)

@Serializable
data class AboutDto(val title: String = "", val text: String = "")

@Serializable
data class ServiceDto(
    val icon: String = "bolt",
    val title: String = "",
    val description: String = "",
    val price: String = "",
)

@Serializable
data class AdvantageDto(val value: String = "", val label: String = "")

@Serializable
data class ContactsDto(
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val telegram: String = "",
    val whatsapp: String = "",
    val workHours: String = "",
)

@Serializable
data class AppearanceDto(
    val accent: String = "#F5A524",
    val accentAlt: String = "#2563EB",
    val defaultMode: String = "auto",
)

// ---------------------------------- CRM ------------------------------------

@Serializable
data class ClientDto(
    val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val note: String = "",
    val tag: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    /** Открыт ли клиенту вход в кабинет на сайте. */
    val portalEnabled: Boolean = false,
    val portalLastLogin: String = "",
)

/** Ответ на выдачу доступа: код показывается один раз и больше не хранится. */
@Serializable
data class AccessCodeDto(val code: String = "", val phone: String = "")

@Serializable
data class OrderDto(
    val id: Long = 0,
    val clientId: Long? = null,
    val clientName: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "new",
    val price: Double = 0.0,
    val dueDate: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val photos: List<PhotoDto> = emptyList(),
    val photoCount: Int = 0,
)

/** Снимок по заказу. url и thumbUrl приходят от сервера готовыми. */
@Serializable
data class PhotoDto(
    val id: Long = 0,
    val orderId: Long = 0,
    val token: String = "",
    val mime: String = "",
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val caption: String = "",
    val sort: Int = 0,
    val createdAt: String = "",
    val url: String = "",
    val thumbUrl: String = "",
)

@Serializable
data class PhotoCaptionBody(val caption: String)

@Serializable
data class RequestDto(
    val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val message: String = "",
    val source: String = "site",
    val status: String = "new",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class StatusUpdate(val status: String)

@Serializable
data class StatsDto(
    val clients: Long = 0,
    val orders: Long = 0,
    val ordersActive: Long = 0,
    val ordersDone: Long = 0,
    val requestsNew: Long = 0,
    val revenueTotal: Double = 0.0,
    val revenueActive: Double = 0.0,
)

@Serializable
data class HealthDto(
    val status: String = "",
    val service: String = "",
    val version: String = "",
)

@Serializable
data class ListResponse<T>(
    val items: List<T> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class ApiErrorBody(
    @SerialName("error") val code: String = "",
    val message: String = "",
)
