package com.example.frolovsistems.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.ui.theme.Success

/** Быстрые действия CRM: звонок прямо из карточки контакта. */

/**
 * Большая зелёная кнопка звонка — главное действие с контактом,
 * поэтому выглядит заметнее соседних кнопок.
 */
@Composable
fun CallButton(phone: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Button(
        onClick = { dialPhone(context, phone) },
        enabled = phone.isNotBlank(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Success,
            contentColor = Color.White,
            disabledContainerColor = Success.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.8f),
        ),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        modifier = modifier,
    ) {
        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            "Позвонить: ${phone.trim()}",
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** Открывает звонилку с подставленным номером. */
fun dialPhone(context: Context, phone: String) {
    val clean = phone.trim()
    if (clean.isEmpty()) return
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")))
}
