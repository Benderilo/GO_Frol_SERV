package com.example.frolovsistems

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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frolovsistems.core.prefs.AppPreferences
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.MainScaffold
import com.example.frolovsistems.ui.screens.LoginScreen
import com.example.frolovsistems.ui.theme.FrolovTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(applicationContext)

        setContent {
            val prefs by ServiceLocator.settings.preferences
                .collectAsStateWithLifecycle(initialValue = AppPreferences())

            FrolovTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Вход и основной экран меняются плавным кроссфейдом.
                    AnimatedContent(
                        targetState = prefs.isAuthorized,
                        transitionSpec = {
                            fadeIn(tween(320)) togetherWith fadeOut(tween(220))
                        },
                        label = "authGate",
                    ) { authorized ->
                        if (authorized) MainScaffold() else LoginScreen()
                    }
                }
            }
        }
    }
}
