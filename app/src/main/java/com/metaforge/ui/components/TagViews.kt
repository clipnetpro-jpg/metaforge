package com.metaforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.engine.MetadataRepository

private val Accent = Color(0xFF22D3EE)
private val Muted = Color(0xFF8B8BA7)
private val RawTint = Color(0xFFF59E0B)

/**
 * One metadata group, collapsed until asked for.
 *
 * A phone photo carries several hundred tags. Dumping them as one list is the
 * lazy answer; grouping them the way the format actually nests them, with the
 * count on the header, is what makes a large file navigable.
 */
@Composable
fun TagGroupSection(
    group: MetadataRepository.Group,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTagClick: (MetadataRepository.Tag) -> Unit,
) {
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.04f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.name,
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    group.label.removePrefix(group.name).removePrefix(" - ").ifBlank { "metadata block" },
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            Text(
                "${group.tags.size}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.rotate(arrow),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                group.tags.forEach { tag ->
                    TagRow(tag) { onTagClick(tag) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun TagRow(tag: MetadataRepository.Tag, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tag.name,
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (tag.isBinary) Badge("binary", Muted)
            if (tag.structure != null) Badge("struct", Accent)
            if (tag.differs) Badge("raw", RawTint)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tag.printValue.ifBlank { "(empty)" },
            color = Muted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
        )
    }
}

@Composable
private fun Badge(text: String, tint: Color) {
    Text(
        text,
        color = tint,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Classic hex dump: offset, sixteen bytes, printable column. */
@Composable
fun HexView(bytes: ByteArray, modifier: Modifier = Modifier) {
    val text = remember(bytes) { hexDump(bytes) }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            color = Color(0xFF9BE8FF),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 15.sp,
        )
    }
}

private fun hexDump(bytes: ByteArray): String = buildString {
    if (bytes.isEmpty()) {
        append("(no bytes returned)")
        return@buildString
    }
    var i = 0
    while (i < bytes.size) {
        append("%08X  ".format(i))
        val end = minOf(i + 16, bytes.size)
        for (j in i until i + 16) {
            if (j < end) append("%02X ".format(bytes[j])) else append("   ")
            if (j - i == 7) append(" ")
        }
        append(" ")
        for (j in i until end) {
            val c = bytes[j].toInt() and 0xFF
            append(if (c in 32..126) c.toChar() else '.')
        }
        append("\n")
        i += 16
    }
}
