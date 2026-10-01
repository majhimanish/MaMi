package app.mami.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mami.ui.theme.Mami

/** The main call-to-action: a pill filled with the theme gradient that squishes when pressed. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    val active = enabled && !busy
    val glow = Mami.colors.gradient.first()
    Box(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (active) 14.dp else 0.dp, CircleShape, ambientColor = glow, spotColor = glow)
            .clip(CircleShape)
            .background(if (active) Mami.colors.brush else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White),
                enabled = active,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = Color.White)
        } else {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = if (active) Mami.colors.onGradient else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A frosted card that lets the aurora behind it shine through. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (Mami.colors.isDark) 0.62f else 0.78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(22.dp), content = content)
    }
}

/** A titled group of settings or details. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

/** An icon in a soft, tinted circle. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 40) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** A row with an icon, a title, a description and a switch. */
@Composable
fun ToggleRow(icon: ImageVector, title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A row that opens something else. */
@Composable
fun NavRow(icon: ImageVector, title: String, detail: String?, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint = tint)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (tint == MaterialTheme.colorScheme.error) tint else Color.Unspecified)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * One box per character, like a bank's one-time-code field. [separatorAfter]
 * draws a dash after that many characters (for "ABCD-EFGH").
 */
@Composable
fun CodeBoxes(
    value: String,
    length: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    letters: Boolean = false,
    separatorAfter: Int? = null,
    isError: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = { input ->
            val clean = if (letters) input.filter(Char::isLetterOrDigit).uppercase() else input.filter(Char::isDigit)
            onValueChange(clean.take(length))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (letters) KeyboardType.Ascii else KeyboardType.NumberPassword,
            capitalization = if (letters) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
        ),
        modifier = modifier.fillMaxWidth(),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(length) { index ->
                    if (separatorAfter != null && index == separatorAfter) {
                        Text("–", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    val char = value.getOrNull(index)
                    val focused = index == value.length
                    val borderColor by animateColorAsState(
                        when {
                            isError -> MaterialTheme.colorScheme.error
                            focused -> MaterialTheme.colorScheme.primary
                            char != null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                        label = "border",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.82f)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(if (focused) 2.dp else 1.5.dp, borderColor, MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(char, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "char") { c ->
                            Text(
                                c?.toString() ?: "",
                                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun ErrorMessage(error: String?) {
    AnimatedVisibility(visible = error != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun VerticalSpace(height: Int) = Spacer(Modifier.height(height.dp))
