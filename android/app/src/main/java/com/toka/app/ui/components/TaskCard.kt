package com.toka.app.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toka.app.data.api.TaskDTO
import com.toka.app.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private enum class TaskDueStatus { OVERDUE, DUE_SOON, NORMAL }

private fun parseDate(dateString: String?): LocalDate? {
    if (dateString == null) return null
    return try {
        LocalDate.parse(dateString.substringBefore("T"))
    } catch (_: Exception) { null }
}

/**
 * Atrasada = el instante de vencimiento ya pasó, igual que agrupa el Dashboard. Antes
 * esto comparaba solo fechas y una tarea de hoy a las 08:00 salía "Hoy" en la tarjeta
 * pero "Atrasada" en su sección.
 */
private fun dueStatus(dateString: String?): TaskDueStatus {
    if (dateString == null) return TaskDueStatus.NORMAL
    val instant = try {
        Instant.parse(dateString)
    } catch (_: Exception) {
        return TaskDueStatus.NORMAL
    }
    if (instant.isBefore(Instant.now())) return TaskDueStatus.OVERDUE

    val dueDate = LocalDate.ofInstant(instant, ZoneId.systemDefault())
    val diff = ChronoUnit.DAYS.between(LocalDate.now(), dueDate)
    return if (diff in 0..1) TaskDueStatus.DUE_SOON else TaskDueStatus.NORMAL
}

private fun relativeDateText(dateString: String?): String {
    val date = parseDate(dateString) ?: return ""
    val today = LocalDate.now()
    val diff = ChronoUnit.DAYS.between(today, date)
    return when {
        diff == 0L -> "Hoy" ; diff == 1L -> "Mañana" ; diff == -1L -> "Ayer"
        diff > 0 -> "en $diff días"
        else -> "hace ${-diff} días"
    }
}

@Composable
fun TaskCard(
    task: TaskDTO,
    onComplete: () -> Unit = {},
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Una tarea ya resuelta no está atrasada: usar su fecha de completado y el color
    // neutro evita pintarla de rojo por un vencimiento que ya pasó.
    val isPending = task.status == "pending"
    val status = if (isPending) dueStatus(task.dueAt) else TaskDueStatus.NORMAL
    val dateString = if (isPending) task.dueAt else task.completedAt
    val bgColor = when (status) {
        TaskDueStatus.OVERDUE -> OverdueBg
        TaskDueStatus.DUE_SOON -> DueSoonBg
        TaskDueStatus.NORMAL -> CardBg
    }
    val dateColor = when (status) {
        TaskDueStatus.OVERDUE -> SkipRed
        TaskDueStatus.DUE_SOON -> Amber
        TaskDueStatus.NORMAL -> TextSecondary
    }
    val avatarColor = if (!task.assignedToColor.isNullOrBlank())
        try { Color(AndroidColor.parseColor(task.assignedToColor)) } catch (_: Exception) { Pink }
    else Pink

    val emoji = task.assignedToEmoji ?: "👤"
    val title = task.templateName ?: "Tarea"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(avatarColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarToday, null,
                        modifier = Modifier.size(14.dp), tint = dateColor
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        relativeDateText(dateString),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (status == TaskDueStatus.OVERDUE) FontWeight.Bold else FontWeight.Normal,
                        color = dateColor
                    )
                    if (task.assignedToName != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(task.assignedToEmoji ?: "👤", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(3.dp))
                        Text(
                            task.assignedToName,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }
            if (isPending) {
                IconButton(onClick = onComplete) {
                    Icon(
                        Icons.Default.CheckCircle, "Completar",
                        tint = CompleteGreen,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
