package com.akoya.home.ui.settings

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.akoya.home.ui.components.TvCard
import com.akoya.home.ui.components.drawThemeGradient
import com.akoya.home.ui.components.drawMist
import com.akoya.home.ui.components.TvLazyRow
import com.akoya.home.ui.components.tvClickable
import com.akoya.home.ui.theme.AkoyaPalette
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import com.akoya.home.ui.theme.Palettes

@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    onClick: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    valueColor: Color? = null,
) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) p.accent.copy(alpha = 0.18f) else p.surface)
            .then(if (focused) Modifier.border(2.dp, p.accent, shape) else Modifier)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.let { it(); true } ?: false
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> onRight?.let { it(); true } ?: false
                    else -> false
                }
            }
            .tvClickable(onClick) { focused = it }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AkoyaType.body, color = p.text)
            if (subtitle != null) Text(subtitle, style = AkoyaType.small, color = p.muted)
        }
        if (value != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onLeft != null) Icon(Icons.Filled.ChevronLeft, null, tint = if (focused) p.accent else Color.Transparent)
                Text(value, style = AkoyaType.label, color = valueColor ?: p.accent, modifier = Modifier.padding(horizontal = 6.dp))
                if (onRight != null) Icon(Icons.Filled.ChevronRight, null, tint = if (focused) p.accent else Color.Transparent)
            }
        }
    }
}

@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
) {
    val idx = options.indexOf(selected).coerceAtLeast(0)
    fun pick(delta: Int) {
        if (options.isEmpty()) return
        onSelect(options[(idx + delta + options.size) % options.size])
    }
    SettingRow(
        title = title,
        subtitle = subtitle,
        value = if (options.isEmpty()) "—" else label(options[idx]),
        onClick = { pick(1) },
        onLeft = { pick(-1) },
        onRight = { pick(1) },
    )
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        value = if (checked) "On" else "Off",
        onClick = { onChange(!checked) },
        onLeft = { onChange(!checked) },
        onRight = { onChange(!checked) },
    )
}

@Composable
fun SettingsNote(text: String) {
    val p = LocalPalette.current
    Text(text, style = AkoyaType.small, color = p.muted, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp))
}

@Composable
fun ThemePicker(selectedId: String, onPick: (AkoyaPalette) -> Unit) {
    TvLazyRow(contentPadding = PaddingValues(vertical = 14.dp, horizontal = 8.dp), spacing = 18.dp) {
        items(Palettes, key = { it.id }) { pal ->
            TvCard(width = 168.dp, height = 100.dp, onClick = { onPick(pal) }) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawThemeGradient(pal)
                            drawMist(pal, 0f)
                        },
                )
                Text(
                    pal.name,
                    style = AkoyaType.label,
                    color = pal.text,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                )
                if (pal.id == selectedId) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = pal.accent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(22.dp),
                    )
                }
            }
        }
    }
}
