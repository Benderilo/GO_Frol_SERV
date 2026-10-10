package com.example.frolovsistems.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.frolovsistems.ui.theme.Blue600
import java.util.Locale

/**
 * Куда ехать: адрес текстом и точка на карте. Точка обычно приходит из MAX
 * кнопкой «Отправить геопозицию», адрес вписывают руками — у них разная
 * жизнь, поэтому хранятся порознь. 0, 0 — точки нет.
 */
data class Place(val address: String = "", val lat: Double = 0.0, val lon: Double = 0.0) {
    val hasGeo: Boolean get() = hasGeo(lat, lon)

    /** Есть хоть что-то, по чему строится маршрут. */
    val routable: Boolean get() = hasGeo || address.isNotBlank()
}

fun hasGeo(lat: Double, lon: Double): Boolean =
    (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0

/**
 * Открывает маршрут до места. По точке — сразу «поехали» в Яндекс Навигаторе;
 * по адресу текстом — поиск адреса в нём же. Нет Навигатора — Яндекс Карты,
 * нет и их — карта в браузере.
 */
fun openRoute(context: Context, place: Place) {
    val candidates = if (place.hasGeo) {
        val lat = "%.6f".format(Locale.US, place.lat)
        val lon = "%.6f".format(Locale.US, place.lon)
        listOf(
            "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon",
            "yandexmaps://maps.yandex.ru/?rtext=~$lat,$lon&rtt=auto",
            "https://yandex.ru/maps/?rtext=~$lat,$lon&rtt=auto",
        )
    } else {
        val text = Uri.encode(place.address.trim())
        listOf(
            "yandexnavi://map_search?text=$text",
            "yandexmaps://maps.yandex.ru/?text=$text",
            "https://yandex.ru/maps/?text=$text",
        )
    }
    for (uri in candidates) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            return
        } catch (_: ActivityNotFoundException) {
            // Приложения нет — пробуем следующее.
        }
    }
    Toast.makeText(context, "Не нашлось приложения для карт", Toast.LENGTH_SHORT).show()
}

/** Синяя кнопка «Маршрут» — рядом с зелёной «Позвонить». */
@Composable
fun RouteButton(place: Place, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    Button(
        onClick = { openRoute(context, place) },
        enabled = place.routable,
        colors = ButtonDefaults.buttonColors(containerColor = Blue600, contentColor = Color.White),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = if (compact) 8.dp else 14.dp),
        modifier = modifier,
    ) {
        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            if (place.hasGeo) "Маршрут по точке" else "Маршрут по адресу",
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** Маленькая кнопка маршрута для карточек в списке, рядом со звонком. */
@Composable
fun RouteIconButton(place: Place, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FilledTonalIconButton(onClick = { openRoute(context, place) }, modifier = modifier) {
        Icon(Icons.Default.Directions, contentDescription = "Маршрут в навигаторе")
    }
}

/** Звонок и маршрут одной строкой — для карточек в списках. */
@Composable
fun CallRouteRow(phone: String, place: Place, modifier: Modifier = Modifier) {
    if (phone.isBlank() && !place.routable) return
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (phone.isNotBlank()) CallButton(phone, Modifier.weight(1f), compact = true)
        else Spacer(Modifier.weight(1f))
        if (place.routable) RouteIconButton(place)
    }
}

/**
 * Блок «где это» в карточке: поле адреса (или адрес текстом, если править
 * нельзя), плашка с точкой из MAX и кнопка маршрута.
 *
 * [route] — куда на самом деле вести, если своего места нет: заказ без
 * адреса ведёт к клиенту; [routeNote] объясняет это человеку.
 */
@Composable
fun PlaceBlock(
    place: Place,
    label: String = "Адрес",
    onAddressChange: ((String) -> Unit)? = null,
    onClearGeo: (() -> Unit)? = null,
    route: Place = place,
    routeNote: String? = null,
) {
    Column {
        if (onAddressChange != null) {
            DialogField(label, place.address, onChange = onAddressChange)
        } else if (place.address.isNotBlank()) {
            SummaryRow(label, place.address, Modifier.padding(bottom = 6.dp))
        }
        if (place.hasGeo) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Row(
                    Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = Blue600, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                        Text("Точка на карте из MAX", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "%.5f, %.5f".format(Locale.US, place.lat, place.lon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onClearGeo != null) TextButton(onClick = onClearGeo) { Text("Убрать") }
                }
            }
        }
        if (routeNote != null && route.routable) {
            Text(
                routeNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (route.routable) RouteButton(route, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    }
}
