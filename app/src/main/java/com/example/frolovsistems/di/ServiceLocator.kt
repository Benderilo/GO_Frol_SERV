package com.example.frolovsistems.di

import android.content.Context
import com.example.frolovsistems.core.net.ApiClient
import com.example.frolovsistems.core.offline.Offline
import com.example.frolovsistems.core.offline.OfflinePrefetch
import com.example.frolovsistems.core.prefs.AppSettings
import com.example.frolovsistems.data.CrmRepository
import com.example.frolovsistems.data.FilesRepository
import com.example.frolovsistems.data.PhoneBackupRepository
import com.example.frolovsistems.data.SessionRepository
import com.example.frolovsistems.data.SiteRepository
import com.example.frolovsistems.data.WorkersRepository

/**
 * Ручной контейнер зависимостей. Для приложения такого размера этого хватает,
 * а сборка остаётся без кодогенерации.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val settings: AppSettings by lazy { AppSettings(appContext) }
    val offline: Offline by lazy { Offline(appContext, settings) }
    val api: ApiClient by lazy { ApiClient(settings, offline) }
    val prefetch: OfflinePrefetch by lazy { OfflinePrefetch(api, offline, settings) }

    val session: SessionRepository by lazy { SessionRepository(api, settings) }
    val site: SiteRepository by lazy { SiteRepository(api) }
    val crm: CrmRepository by lazy { CrmRepository(api) }
    val workers: WorkersRepository by lazy { WorkersRepository(api) }
    val files: FilesRepository by lazy { FilesRepository(api) }
    val phoneBackups: PhoneBackupRepository by lazy { PhoneBackupRepository(api, appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
