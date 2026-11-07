package com.akoya.home.ui.components

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.akoya.home.data.AppArt
import com.akoya.home.data.AppInfo
import com.akoya.home.data.AppRepository
import com.akoya.home.data.ContentItem
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalConfig
import com.akoya.home.ui.theme.LocalPalette

private val SELECT_KEYS = setOf(
    NativeKeyEvent.KEYCODE_DPAD_CENTER,
    NativeKeyEvent.KEYCODE_ENTER,
    NativeKeyEvent.KEYCODE_NUMPAD_ENTER,
    NativeKeyEvent.KEYCODE_BUTTON_A,
)

/**
 * D-pad friendly click handling: OK = click, holding OK (or the Menu key) = long click.
 * A click only fires if the key went down on this element, so the key-up from the press
 * that opened a dialog never "clicks" the dialog's first button.
 */
fun Modifier.tvClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val focusCb by rememberUpdatedState(onFocusChange)
    // [0] key went down here, [1] long press already fired
    val state = remember { BooleanArray(2) }
    this
        .onFocusChanged { focusCb?.invoke(it.isFocused) }
        .onKeyEvent { ev ->
            val native = ev.nativeKeyEvent
            val code = native.keyCode
            when {
                code in SELECT_KEYS && ev.type == KeyEventType.KeyDown -> {
                    if (native.repeatCount == 0) {
                        state[0] = true; state[1] = false
                    } else if (state[0] && !state[1] && longClick != null) {
                        state[1] = true
                        longClick?.invoke()
                    }
                    true
                }
                code in SELECT_KEYS && ev.type == KeyEventType.KeyUp -> {
                    val fire = state[0] && !state[1]
                    state[0] = false; state[1] = false
                    if (fire) click()
                    fire
                }
                code == NativeKeyEvent.KEYCODE_MENU && ev.type == KeyEventType.KeyUp && longClick != null -> {
                    longClick?.invoke(); true
                }
                else -> false
            }
        }
        .focusable()
        .pointerInput(Unit) {
            detectTapGestures(onTap = { click() }, onLongPress = { longClick?.invoke() })
        }
}

fun Color.readableOn(): Color = if (luminance() > 0.45f) Color(0xFF121212) else Color.White

@Composable
fun TvCard(
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    background: Color? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val config = LocalConfig.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) config.focusZoom else 1f,
        animationSpec = if (config.animations) spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "cardScale",
    )
    Box(
        modifier
            .width(width)
            .height(height)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 20.dp else 0.dp, shape, ambientColor = palette.accent, spotColor = palette.accent)
            .clip(shape)
            .background(background ?: palette.surface)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) palette.accent else palette.text.copy(alpha = 0.08f),
                shape = shape,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvClickable(onClick, onLongClick) { focused = it; onFocus?.invoke(it) },
    ) { content(focused) }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    compact: Boolean = false,
) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> palette.accent
        selected -> palette.text.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val fg = if (focused) palette.accent.readableOn() else palette.text
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvClickable(onClick) { focused = it; onFocus?.invoke(it) }
            .padding(horizontal = if (compact || text.isEmpty()) 12.dp else 18.dp, vertical = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = text.ifEmpty { null }, tint = fg, modifier = Modifier.size(22.dp))
        if (text.isNotEmpty()) Text(text, style = AkoyaType.label, color = fg, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Text(
        title.uppercase(),
        style = AkoyaType.section,
        color = palette.muted,
        modifier = modifier.padding(start = 56.dp, bottom = 4.dp, top = 10.dp),
    )
}

@Composable
fun rememberAppArt(app: AppInfo): AppArt? {
    val ctx = LocalContext.current
    val art by produceState<AppArt?>(initialValue = null, app.packageName) {
        value = AppRepository.get(ctx).art(app)
    }
    return art
}

/** A 16:9 app tile: TV banner when the app has one, otherwise icon on a tinted card. */
@Composable
fun AppTile(
    app: AppInfo,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val showLabels = LocalConfig.current.showAppLabels
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        TvCard(
            width = width,
            height = width * 9f / 16f,
            onClick = onClick,
            onLongClick = onLongClick,
            focusRequester = focusRequester,
            onFocus = { focused = it; onFocus?.invoke(it) },
        ) {
            AppArtwork(app, width)
        }
        if (showLabels) {
            Spacer(Modifier.height(10.dp))
            Text(
                app.label,
                style = AkoyaType.label,
                color = if (focused) palette.text else Color.Transparent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AppArtwork(app: AppInfo, width: Dp) {
    val art = rememberAppArt(app)
    val banner = art?.banner
    if (banner != null) {
        Image(banner, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(art?.tint ?: Color(0xFF2A2F3A)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                art?.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(width * 0.22f)) }
                Spacer(Modifier.height(6.dp))
                Text(
                    app.label,
                    style = AkoyaType.small,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** Poster card for programmes from the TV provider. */
@Composable
fun ContentCard(
    item: ContentItem,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    TvCard(
        width = width,
        height = width * 9f / 16f,
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        onFocus = onFocus,
    ) { focused ->
        AsyncImage(
            model = item.imageUri,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        ) {
            Text(item.title, style = AkoyaType.label, color = Color.White, maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (focused && item.subtitle != null) {
                Text(item.subtitle, style = AkoyaType.small, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item.progress?.let { p ->
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(p)
                        .height(4.dp)
                        .background(palette.accent),
                )
            }
        }
    }
}
