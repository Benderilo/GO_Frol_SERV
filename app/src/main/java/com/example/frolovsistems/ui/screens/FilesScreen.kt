package com.example.frolovsistems.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frolovsistems.core.net.FolderDto
import com.example.frolovsistems.core.net.FolderListingDto
import com.example.frolovsistems.core.net.StoredFileDto
import com.example.frolovsistems.data.FilesRepository
import com.example.frolovsistems.di.ServiceLocator
import com.example.frolovsistems.ui.components.EmptyState
import com.example.frolovsistems.ui.components.ErrorBanner
import com.example.frolovsistems.ui.components.LoadingBox
import com.example.frolovsistems.ui.theme.Success
import com.example.frolovsistems.ui.theme.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FilesUiState(
    val loading: Boolean = true,
    /** null — открыт верхний уровень. */
    val folderId: Long? = null,
    val listing: FolderListingDto = FolderListingDto(),
    /** Идёт отправка или скачивание — на это время действия недоступны. */
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = listing.folders.isEmpty() && listing.files.isEmpty()
}

class FilesViewModel(
    private val files: FilesRepository = ServiceLocator.files,
) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val folderId = _state.value.folderId
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            files.listing(folderId)
                .onSuccess { listing ->
                    _state.update { it.copy(loading = false, listing = listing) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun open(folderId: Long?) {
        _state.update { it.copy(folderId = folderId, listing = FolderListingDto(), message = null) }
        refresh()
    }

    /** На уровень вверх; из корня возвращает false — экран отдаёт жест системе. */
    fun goUp(): Boolean {
        val current = _state.value.listing.folder ?: return false
        open(current.parentId)
        return true
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            files.createFolder(name, _state.value.folderId)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun renameFolder(id: Long, name: String) {
        viewModelScope.launch {
            files.renameFolder(id, name)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            files.deleteFolder(id)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun renameFile(id: Long, name: String) {
        viewModelScope.launch {
            files.renameFile(id, name)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun deleteFile(id: Long) {
        viewModelScope.launch {
            files.deleteFile(id)
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Читает выбранный документ и кладёт его в текущую папку. */
    fun upload(context: Context, source: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, error = null) }

            val picked = withContext(Dispatchers.IO) { readPicked(context, source) }
            if (picked == null) {
                _state.update { it.copy(busy = false, error = "Не удалось прочитать выбранный файл") }
                return@launch
            }

            files.upload(_state.value.folderId, picked.bytes, picked.name, picked.mime)
                .onSuccess { stored ->
                    _state.update { it.copy(busy = false, message = "Загружен «${stored.name}»") }
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    /** Скачивает файл и отдаёт его системному просмотрщику. */
    fun openExternally(context: Context, file: StoredFileDto) {
        withDownloadedBytes(file) { bytes ->
            val shared = withContext(Dispatchers.IO) { runCatching { cacheShared(context, file, bytes) } }
            shared.fold(
                onSuccess = { uri -> viewIntent(context, uri, file.mime) },
                onFailure = { e -> "Не удалось открыть файл: ${e.message}" },
            )
        }
    }

    /** Скачивает файл и пишет его в выбранное пользователем место. */
    fun saveTo(context: Context, file: StoredFileDto, target: Uri) {
        withDownloadedBytes(file) { bytes ->
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                        ?: error("файл недоступен для записи")
                }
            }
            written.fold(
                onSuccess = { "Сохранено ${formatFileSize(bytes.size.toLong())}" },
                onFailure = { e -> "Не удалось сохранить файл: ${e.message}" },
            )
        }
    }

    /**
     * Общая часть «открыть» и «скачать»: качаем содержимое, а [andThen]
     * решает, что с ним сделать, и возвращает текст для плашки.
     */
    private fun withDownloadedBytes(
        file: StoredFileDto,
        andThen: suspend (ByteArray) -> String,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, error = null) }
            files.download(file.id)
                .onSuccess { bytes ->
                    val message = andThen(bytes)
                    _state.update { it.copy(busy = false, message = message) }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}

private class PickedFile(val bytes: ByteArray, val name: String, val mime: String)

/** Читает выбранный через системный диалог файл вместе с именем и типом. */
private fun readPicked(context: Context, source: Uri): PickedFile? {
    val bytes = runCatching {
        context.contentResolver.openInputStream(source)?.use { it.readBytes() }
    }.getOrNull()
    if (bytes == null || bytes.isEmpty()) return null

    val name = queryDisplayName(context, source) ?: "файл"
    val mime = context.contentResolver.getType(source).orEmpty()
    return PickedFile(bytes, name, mime)
}

/** Имя файла у content://-адреса известно только провайдеру — спрашиваем его. */
private fun queryDisplayName(context: Context, source: Uri): String? {
    val column = android.provider.OpenableColumns.DISPLAY_NAME
    return runCatching {
        context.contentResolver.query(source, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * Кладёт скачанное в кеш и возвращает content://-адрес. Каталог чистится
 * при каждом открытии: файлы здесь нужны ровно на время просмотра.
 */
private fun cacheShared(context: Context, file: StoredFileDto, bytes: ByteArray): Uri {
    val dir = File(context.cacheDir, "shared")
    dir.listFiles()?.forEach { it.delete() }
    dir.mkdirs()

    // Имя чистим от разделителей: оно приходит с сервера и попадает в путь.
    val safeName = file.name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "файл" }
    val target = File(dir, safeName)
    target.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
}

private fun viewIntent(context: Context, uri: Uri, mime: String): String {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        "Файл открыт"
    } catch (_: ActivityNotFoundException) {
        "На устройстве нет приложения для этого типа файлов — сохраните его кнопкой «Скачать»"
    }
}

@Composable
fun FilesScreen(
    refreshTick: Int = 0,
    viewModel: FilesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var newFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<FolderDto?>(null) }
    var renamingFile by remember { mutableStateOf<StoredFileDto?>(null) }
    var deletingFolder by remember { mutableStateOf<FolderDto?>(null) }
    var deletingFile by remember { mutableStateOf<StoredFileDto?>(null) }
    // Диалог сохранения открывается асинхронно — помним, какой файл ждёт места.
    var savingFile by remember { mutableStateOf<StoredFileDto?>(null) }

    LaunchedEffect(refreshTick) { if (refreshTick > 0) viewModel.refresh() }

    // Внутри папки системное «назад» поднимает на уровень выше, а не выходит из раздела.
    BackHandler(enabled = state.listing.folder != null) { viewModel.goUp() }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.upload(context, uri) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val file = savingFile
        savingFile = null
        if (uri != null && file != null) viewModel.saveTo(context, file, uri)
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Файлы", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Breadcrumbs(path = state.listing.path, onOpen = viewModel::open)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    ErrorBanner(state.error)
                    AnimatedVisibility(
                        visible = state.message != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        TransferMessage(
                            text = state.message.orEmpty(),
                            onDismiss = viewModel::dismissMessage,
                        )
                    }
                }
            }

            when {
                state.loading && state.isEmpty -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingBox() }
                state.isEmpty -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = "Папка пуста",
                        subtitle = "Создайте папку или загрузите документ кнопками внизу справа",
                    )
                }
                else -> {
                    items(state.listing.folders, key = { "d${it.id}" }) { folder ->
                        FolderTile(
                            folder = folder,
                            onOpen = { viewModel.open(folder.id) },
                            onRename = { renamingFolder = folder },
                            onDelete = { deletingFolder = folder },
                        )
                    }
                    items(state.listing.files, key = { "f${it.id}" }) { file ->
                        FileTile(
                            file = file,
                            enabled = !state.busy,
                            onOpen = { viewModel.openExternally(context, file) },
                            onSave = {
                                savingFile = file
                                saveLauncher.launch(file.name)
                            },
                            onRename = { renamingFile = file },
                            onDelete = { deletingFile = file },
                        )
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloatingActionButton(
                onClick = { newFolder = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(3.dp),
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Новая папка")
            }
            FloatingActionButton(onClick = { if (!state.busy) uploadLauncher.launch(arrayOf("*/*")) }) {
                if (state.busy) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(Icons.Default.UploadFile, contentDescription = "Загрузить файл")
                }
            }
        }
    }

    if (newFolder) {
        NameDialog(
            title = "Новая папка",
            initial = "",
            confirm = "Создать",
            onDismiss = { newFolder = false },
            onConfirm = { name ->
                newFolder = false
                viewModel.createFolder(name)
            },
        )
    }

    renamingFolder?.let { folder ->
        NameDialog(
            title = "Переименовать папку",
            initial = folder.name,
            confirm = "Сохранить",
            onDismiss = { renamingFolder = null },
            onConfirm = { name ->
                renamingFolder = null
                viewModel.renameFolder(folder.id, name)
            },
        )
    }

    renamingFile?.let { file ->
        NameDialog(
            title = "Переименовать файл",
            initial = file.name,
            confirm = "Сохранить",
            onDismiss = { renamingFile = null },
            onConfirm = { name ->
                renamingFile = null
                viewModel.renameFile(file.id, name)
            },
        )
    }

    deletingFolder?.let { folder ->
        ConfirmDeleteDialog(
            title = "Удалить папку?",
            text = "«${folder.name}» и всё, что внутри, будет удалено безвозвратно.",
            onDismiss = { deletingFolder = null },
            onConfirm = {
                deletingFolder = null
                viewModel.deleteFolder(folder.id)
            },
        )
    }

    deletingFile?.let { file ->
        ConfirmDeleteDialog(
            title = "Удалить файл?",
            text = "«${file.name}» будет удалён безвозвратно.",
            onDismiss = { deletingFile = null },
            onConfirm = {
                deletingFile = null
                viewModel.deleteFile(file.id)
            },
        )
    }
}

/** Путь до текущей папки: домик — верхний уровень, дальше звенья через стрелку. */
@Composable
private fun Breadcrumbs(path: List<FolderDto>, onOpen: (Long?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Home,
            contentDescription = "Верхний уровень",
            tint = if (path.isEmpty()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .clickable(enabled = path.isNotEmpty()) { onOpen(null) }
                .padding(4.dp)
                .size(20.dp),
        )
        path.forEachIndexed { index, folder ->
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
            val last = index == path.lastIndex
            Text(
                folder.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .clickable(enabled = !last) { onOpen(folder.id) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/** Плитка папки: крупная иконка, имя и сколько внутри. */
@Composable
private fun FolderTile(
    folder: FolderDto,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    TileFrame(
        icon = Icons.Default.Folder,
        accent = Warning,
        name = folder.name,
        caption = folderContents(folder),
        onClick = onOpen,
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text("Открыть") },
            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
            onClick = { dismiss(); onOpen() },
        )
        DropdownMenuItem(
            text = { Text("Переименовать") },
            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
            onClick = { dismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text("Удалить") },
            leadingIcon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = { dismiss(); onDelete() },
        )
    }
}

/** Плитка файла: иконка по типу, имя и размер. */
@Composable
private fun FileTile(
    file: StoredFileDto,
    enabled: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val look = fileLook(file)
    TileFrame(
        icon = look.icon,
        accent = look.color,
        name = file.name,
        caption = formatFileSize(file.size),
        onClick = { if (enabled) onOpen() },
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text("Открыть") },
            leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
            enabled = enabled,
            onClick = { dismiss(); onOpen() },
        )
        DropdownMenuItem(
            text = { Text("Скачать") },
            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
            enabled = enabled,
            onClick = { dismiss(); onSave() },
        )
        DropdownMenuItem(
            text = { Text("Переименовать") },
            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
            onClick = { dismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text("Удалить") },
            leadingIcon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            onClick = { dismiss(); onDelete() },
        )
    }
}

/**
 * Общая рамка плитки. Меню действий висит на кнопке «⋮» в углу, а нажатие
 * на саму плитку сразу открывает — так же, как в файловом менеджере.
 */
@Composable
private fun TileFrame(
    icon: ImageVector,
    accent: Color,
    name: String,
    caption: String,
    onClick: () -> Unit,
    menu: TileMenu,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(52.dp)
                        .background(accent.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Действия",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        menu { expanded = false }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Содержимое меню плитки; параметр закрывает меню перед действием. */
private typealias TileMenu = @Composable (dismiss: () -> Unit) -> Unit

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Название") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private class FileLook(val icon: ImageVector, val color: Color)

/**
 * Вид файла по типу: PDF красный, документ синий, таблица зелёная,
 * снимок сиреневый. Тип берём и из mime, и из расширения — сервер мог
 * не узнать формат и записать поток байт.
 */
@Composable
private fun fileLook(file: StoredFileDto): FileLook {
    val mime = file.mime.lowercase()
    val ext = file.name.substringAfterLast('.', "").lowercase()
    return when {
        mime == "application/pdf" || ext == "pdf" ->
            FileLook(Icons.Default.PictureAsPdf, MaterialTheme.colorScheme.error)
        mime.contains("word") || ext in setOf("doc", "docx", "rtf", "odt") ->
            FileLook(Icons.Default.Description, MaterialTheme.colorScheme.primary)
        mime.contains("sheet") || mime.contains("excel") || ext in setOf("xls", "xlsx", "csv", "ods") ->
            FileLook(Icons.Default.TableChart, Success)
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "gif") ->
            FileLook(Icons.Default.Image, MaterialTheme.colorScheme.tertiary)
        else ->
            FileLook(Icons.AutoMirrored.Filled.InsertDriveFile, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «3 папки, 12 файлов» — что лежит внутри, не заходя. */
private fun folderContents(folder: FolderDto): String {
    if (folder.folderCount == 0 && folder.fileCount == 0) return "пусто"
    return buildList {
        if (folder.folderCount > 0) add("папок ${folder.folderCount}")
        if (folder.fileCount > 0) add("файлов ${folder.fileCount}")
    }.joinToString(", ")
}

/** Размер человеку: байты только у совсем мелких файлов. */
private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f МБ".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}

/** Плашка с итогом загрузки или скачивания. */
@Composable
private fun TransferMessage(text: String, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Понятно") }
        }
    }
}
