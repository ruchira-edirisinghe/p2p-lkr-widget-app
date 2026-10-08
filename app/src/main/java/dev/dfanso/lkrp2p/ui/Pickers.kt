package dev.dfanso.lkrp2p.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dfanso.lkrp2p.core.Currencies
import dev.dfanso.lkrp2p.render.ColorTheme

/** A searchable list of every currency the rate source knows. */
@Composable
fun CurrencyPickerDialog(
    title: String,
    currencies: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query, currencies) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) currencies
        else currencies.filter { (code, name) -> code.startsWith(q) || name.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(30) },
                    label = { Text("Search code or name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.first }) { (code, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (code == selected) Ui.Up.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { onPick(code) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(Currencies.badge(code), fontSize = 18.sp, modifier = Modifier.width(30.dp))
                            Text(code.uppercase(), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(58.dp))
                            Text(name, color = Ui.Dim, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * A row of theme swatches, each a small glow card in that theme's colours.
 * With [followLabel], a first swatch means "no theme of its own".
 */
@Composable
fun ThemeSwatches(selected: ColorTheme?, onPick: (ColorTheme?) -> Unit, followLabel: String? = null) {
    val context = LocalContext.current
    val options: List<ColorTheme?> = (if (followLabel != null) listOf(null) else emptyList()) + ColorTheme.entries
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(options) { theme ->
            val isSelected = theme == selected
            Column(
                Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(theme) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(shape)
                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Ui.Text else Ui.Line, shape),
                ) {
                    if (theme == null) {
                        Box(Modifier.size(64.dp).background(Ui.Raised), contentAlignment = Alignment.Center) {
                            Text("Auto", color = Ui.Dim, fontSize = 13.sp)
                        }
                    } else {
                        val c = theme.colors(context)
                        Box(
                            Modifier
                                .size(64.dp)
                                .background(Color(c.base or 0xFF000000.toInt()))
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(c.glow), Color(c.glowMid), Color.Transparent),
                                        center = androidx.compose.ui.geometry.Offset(60f, 0f),
                                        radius = 170f,
                                    )
                                ),
                        )
                        Box(
                            Modifier.padding(10.dp).align(Alignment.BottomEnd).size(16.dp)
                                .background(Color(c.up), CircleShape),
                        )
                    }
                }
                Text(
                    theme?.label?.substringBefore(" (") ?: followLabel.orEmpty(),
                    color = if (isSelected) Ui.Text else Ui.Dim,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A tappable currency chip: flag, code and a chevron. */
@Composable
fun CurrencyChip(code: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Ui.Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Currencies.badge(code), fontSize = 17.sp)
        Spacer(Modifier.width(7.dp))
        Text(code.uppercase(), color = Ui.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("  ▾", color = Ui.Dim, fontSize = 13.sp)
    }
}
