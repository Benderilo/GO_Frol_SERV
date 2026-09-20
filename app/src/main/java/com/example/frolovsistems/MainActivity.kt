package com.example.frolovsistems

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frolovsistems.core.diagnostics.Diagnostics
import com.example.frolovsistems.core.notify.NewRequestsWorker
import com.example.frolovsistems.core.prefs.AppPreferences
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.MainScaffold
import com.example.frolovsistems.ui.components.LocalHintsEnabled
import com.example.frolovsistems.ui.screens.LoginScreen
import com.example.frolovsistems.ui.screens.SplashScreen
import com.example.frolovsistems.ui.theme.FrolovTheme

class MainActivity : ComponentActivity() {

    /** Раздел, который просит открыть уведомление; null — обычный запуск. */
    private val pendingSection = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(applicationContext)
        // Журнал ошибок: поднимаем историю и перехватываем краши — с полной
        // трассировкой в журнал, после чего отдаём системе как обычно.
        Diagnostics.init(applicationContext)
        installCrashHook()
        pendingSection.value = intent.getStringExtra(EXTRA_OPEN_SECTION)
        askNotificationPermission()
        NewRequestsWorker.schedule(applicationContext)

        setContent {
            val prefs by ServiceLocator.settings.preferences
                .collectAsStateWithLifecycle(initialValue = AppPreferences())
            // Неоновая вывеска показывается один раз, при старте процесса.
            var showSplash by remember { mutableStateOf(true) }

            FrolovTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                // Режим подсказок читается всеми экранами отсюда — из настроек.
                CompositionLocalProvider(LocalHintsEnabled provides prefs.hintsEnabled) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (showSplash) {
                        SplashScreen(onFinished = { showSplash = false })
                        return@Surface
                    }
                    // Вход и основной экран меняются плавным кроссфейдом.
                    AnimatedContent(
                        targetState = prefs.isAuthorized,
                        transitionSpec = {
                            fadeIn(tween(320)) togetherWith fadeOut(tween(220))
                        },
                        label = "authGate",
                    ) { authorized ->
                        if (authorized) {
                            MainScaffold(
                                pendingSection = pendingSection.value,
                                onSectionOpened = { pendingSection.value = null },
                            )
                        } else {
                            LoginScreen()
                        }
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingSection.value = intent.getStringExtra(EXTRA_OPEN_SECTION)
    }

    /**
     * Краши пишутся в журнал до того, как система покажет свой диалог:
     * после перезапуска запись видна в «Диагностике» вместе с трассировкой.
     */
    private fun installCrashHook() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Diagnostics.error(
                "краш",
                "Приложение упало (поток ${thread.name}): " +
                    (throwable.message ?: throwable::class.simpleName.orEmpty()),
                throwable,
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Уведомления на Android 13+ требуют разрешения, спрашиваем при запуске. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_SECTION = "open_section"
        const val SECTION_REQUESTS = "requests"
        private const val REQUEST_NOTIFICATIONS = 42
    }
}
