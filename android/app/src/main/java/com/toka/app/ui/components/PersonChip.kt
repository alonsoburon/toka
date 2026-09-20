package com.toka.app.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toka.app.data.api.PersonDTO
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.TextMuted
import com.toka.app.ui.theme.colorForPerson

@Composable
fun PersonChip(
    person: PersonDTO,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val personColor = if (person.color.isNotBlank()) parseHexColor(person.color)
    else colorForPerson((person.id % 7).toInt())

    PersonChip(
        emoji = person.avatarEmoji,
        name = person.name,
        color = personColor,
        selected = selected,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun PersonChip(
    emoji: String,
    name: String,
    color: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PersonChip(
        emoji = emoji,
        name = name,
        color = parseHexColor(color),
        selected = selected,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun PersonChip(
    emoji: String,
    name: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val elevation = if (selected) 4.dp else 0.dp
    val borderModifier = if (selected) {
        Modifier.border(2.dp, Pink, RoundedCornerShape(50))
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .then(borderModifier)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        shadowElevation = elevation,
        color = color.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun AnyoneChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val elevation = if (selected) 4.dp else 0.dp
    val borderModifier = if (selected) {
        Modifier.border(2.dp, Pink, RoundedCornerShape(50))
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .then(borderModifier)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        shadowElevation = elevation,
        color = Color.Gray.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "\uD83D\uDC64", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Cualquiera",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun CompactPersonChip(
    person: PersonDTO,
    modifier: Modifier = Modifier
) {
    val personColor = if (person.color.isNotBlank()) parseHexColor(person.color)
    else colorForPerson((person.id % 7).toInt())

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(personColor),
            contentAlignment = Alignment.Center
        ) {
            Text(text = person.avatarEmoji, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = person.name,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }
}

private fun parseHexColor(hex: String): Color {
    if (hex.isBlank()) return Color.Gray
    return try {
        Color(AndroidColor.parseColor(hex))
    } catch (_: Exception) {
        Color.Gray
    }
}
