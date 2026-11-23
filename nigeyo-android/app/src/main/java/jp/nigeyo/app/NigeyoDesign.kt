package jp.nigeyo.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Paper = Color(0xFFF7F6F0)
internal val Ink = Color(0xFF20241F)
internal val Muted = Color(0xFF656A62)
internal val Orange = Color(0xFFFF6B35)
internal val Peach = Color(0xFFFFE7D9)
internal val GreenSoft = Color(0xFFE6EDE2)

@Composable
internal fun Screen(
    background: Color = Paper,
    navTitle: String? = null,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
        val compact = maxHeight < 450.dp || LocalDensity.current.fontScale >= 1.5f
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(scroll) else Modifier)) {
            if (navTitle != null && onBack != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = requireNotNull(backLabel)
                        },
                    ) {
                        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                        Label(if (rtl) "›" else "‹", size = 26)
                    }
                    Title(navTitle, size = 15)
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .then(if (compact) Modifier else Modifier.weight(1f).verticalScroll(scroll))
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )

            if (actions != null) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = actions,
                )
            }
        }
    }
}

@Composable
internal fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = Ink,
    bold: Boolean = false,
    textAlign: TextAlign? = null,
) {
    Text(
        text, modifier, color = color, fontSize = size.sp, lineHeight = (size * 1.25f).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = textAlign,
    )
}

@Composable
internal fun Title(text: String, size: Int = 28) =
    Label(text, size = size, bold = true, modifier = Modifier.semantics { heading() })

@Composable
internal fun Eyebrow(text: String, color: Color = Muted) =
    Label(text, size = 12, color = color, bold = true)

@Composable
internal fun Badge(text: String, dark: Boolean = false) {
    val fill = if (dark) Ink else Peach
    val color = if (dark) Color.White else Ink
    Label(
        text, size = 11, color = color, bold = true,
        modifier = Modifier.background(fill, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
internal fun ActionButton(
    text: String,
    background: Color = Ink,
    onClick: () -> Unit,
) {
    val color = if (background == Ink) Color.White else Ink
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = color),
    ) {
        Label(text, size = 15, color = color, bold = true, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun Panel(
    background: Color = Color.White,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(background)
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
internal fun RowLabels(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(title, size = 17, bold = true)
        Label(body, size = 13, color = Muted)
    }
}

@Composable
internal fun Pictogram(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val icon = when (name) {
        "protect-head" -> R.drawable.picto_protect_head
        "check-surroundings" -> R.drawable.picto_check_surroundings
        "check-fire" -> R.drawable.picto_check_fire
        "secure-exit" -> R.drawable.picto_secure_exit
        "tsunami-evacuate" -> R.drawable.picto_tsunami_evacuate
        else -> error("Unknown pictogram: $name")
    }
    Image(painterResource(icon), contentDescription = label, modifier = modifier.size(size))
}
