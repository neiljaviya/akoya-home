package com.akoya.home.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.akoya.home.data.AppInfo
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.delay

data class DialogOption(
    val label: String,
    val icon: ImageVector? = null,
    /** Keep the dialog open after clicking (e.g. Move left / Move right). */
    val keepOpen: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun AkoyaDialog(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val p = LocalPalette.current
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .widthIn(min = 420.dp, max = if (wide) 900.dp else 560.dp)
                .clip(shape)
                .background(p.backgroundAlt)
                .border(1.dp, p.accent.copy(alpha = 0.35f), shape)
                .padding(28.dp),
        ) {
            Text(title, style = AkoyaType.title, color = p.text)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = AkoyaType.body, color = p.muted)
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

@Composable
fun rememberInitialFocus(): FocusRequester {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { fr.requestFocus() }
    }
    return fr
}

@Composable
fun OptionsDialog(
    title: String,
    options: List<DialogOption>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    AkoyaDialog(title = title, subtitle = subtitle, onDismiss = onDismiss) {
        val fr = rememberInitialFocus()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { i, o ->
                TvButton(
                    text = o.label,
                    icon = o.icon,
                    onClick = {
                        o.onClick()
                        if (!o.keepOpen) onDismiss()
                    },
                    focusRequester = if (i == 0) fr else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun AppPickerDialog(
    title: String,
    apps: List<AppInfo>,
    onPick: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AkoyaDialog(title = title, onDismiss = onDismiss, wide = true) {
        val fr = rememberInitialFocus()
        if (apps.isEmpty()) {
            Text("No apps to choose from.", style = AkoyaType.body, color = LocalPalette.current.muted)
            TvButton("Close", onClick = onDismiss, focusRequester = fr)
        } else LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.heightIn(max = 480.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
                AppTile(
                    app = app,
                    width = 170.dp,
                    onClick = { onPick(app); onDismiss() },
                    focusRequester = if (i == 0) fr else null,
                )
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(initial) }
    AkoyaDialog(title = title, onDismiss = onDismiss) {
        val fr = rememberInitialFocus()
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(40) },
            singleLine = true,
            textStyle = AkoyaType.body.copy(color = p.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(text.trim()); onDismiss() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = p.accent,
                unfocusedBorderColor = p.muted,
                cursorColor = p.accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fr),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Save", onClick = { onDone(text.trim()); onDismiss() })
            TvButton("Cancel", onClick = onDismiss)
        }
    }
}
