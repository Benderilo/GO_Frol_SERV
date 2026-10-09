package com.example.frolovsistems.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.frolovsistems.core.net.AppReleaseDto
import com.example.frolovsistems.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Что сейчас с обновлением — по этому рисуется полоска над экраном. */
sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: AppReleaseDto) : UpdateState
    data class Downloading(val release: AppReleaseDto, val progress: Float) : UpdateState
    data class Ready(val release: AppReleaseDto, val file: File) : UpdateState
    data class Failed(val release: AppReleaseDto, val message: String) : UpdateState
}

/**
 * Автообновление мимо магазина: на сервере лежит свежий APK (его выкладывает
 * server/deploy/publish-apk.sh), приложение сверяет номер сборки со своим,
 * скачивает файл и отдаёт системному установщику. Подпись у сборок одна,
 * поэтому Android ставит новую версию поверх, сохраняя данные и вход.
 */
object AppUpdater {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** «Позже» прячет полоску до следующего запуска, но не навсегда. */
    private var dismissedCode = 0L

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** Тихая проверка: ошибки сети не показываем — обновление не срочное. */
    suspend fun check(context: Context) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return
        val release = ServiceLocator.api.runCatching { appLatest() }.getOrNull() ?: return
        val newer = release.versionCode > installedVersionCode(context) &&
            release.versionCode != dismissedCode
        _state.value = if (newer) UpdateState.Available(release) else UpdateState.None
    }

    fun dismiss() {
        val current = _state.value
        if (current is UpdateState.Downloading) return
        dismissedCode = releaseOf(current)?.versionCode ?: 0
        _state.value = UpdateState.None
    }

    suspend fun download(context: Context) {
        val release = releaseOf(_state.value) ?: return
        _state.value = UpdateState.Downloading(release, 0f)
        val result = runCatching {
            val bytes = ServiceLocator.api.appApk { progress ->
                _state.value = UpdateState.Downloading(release, progress)
            }
            withContext(Dispatchers.IO) {
                // Битый или подменённый по дороге файл установщик всё равно
                // отверг бы, но с невнятной ошибкой — проверяем сами.
                if (release.sha256.isNotBlank() && sha256(bytes) != release.sha256.lowercase()) {
                    error("Файл скачался с ошибкой — попробуйте ещё раз")
                }
                val dir = File(context.cacheDir, "updates")
                dir.listFiles()?.forEach { it.delete() }
                dir.mkdirs()
                File(dir, "frolov-crm-${release.versionCode}.apk").apply { writeBytes(bytes) }
            }
        }
        _state.value = result.fold(
            onSuccess = { UpdateState.Ready(release, it) },
            onFailure = { UpdateState.Failed(release, it.message ?: "Не удалось скачать обновление") },
        )
        if (result.isSuccess) install(context)
    }

    /**
     * Открывает системный установщик. Если ставить приложения из CRM ещё
     * не разрешено, сначала ведём в настройки — после разрешения полоска
     * остаётся на «Установить», и второе нажатие уже ставит.
     */
    fun install(context: Context) {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun releaseOf(state: UpdateState): AppReleaseDto? = when (state) {
        UpdateState.None -> null
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        is UpdateState.Failed -> state.release
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
