package com.example.frolovsistems.di

import android.content.Context
import com.example.frolovsistems.core.net.ApiClient
import com.example.frolovsistems.core.prefs.AppSettings
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.data.SessionRepository
import com.example.frolovsistems.data.SiteRepository

/**
 * Ручной контейнер зависимостей. Для приложения такого размера этого хватает,
 * а сборка остаётся без кодогенерации.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val settings: AppSettings by lazy { AppSettings(appContext) }
    val api: ApiClient by lazy { ApiClient(settings) }

    val session: SessionRepository by lazy { SessionRepository(api, settings) }
    val site: SiteRepository by lazy { SiteRepository(api) }
    val crm: CrmRepository by lazy { CrmRepository(api) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
