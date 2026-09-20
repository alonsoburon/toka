package com.toka.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.toka.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toka.app.data.api.TaskDTO
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.components.EmptyState
import com.toka.app.ui.components.LoadingShimmer
import com.toka.app.ui.components.TaskCard
import com.toka.app.ui.theme.CompleteGreen
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.SkipRed
import com.toka.app.ui.theme.SurfaceBg
import com.toka.app.ui.theme.TextPrimary
import com.toka.app.ui.theme.Violet
import com.toka.app.ui.theme.parseHexColor
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    onNavigateToTask: (Long) -> Unit,
    onNavigateToCreateTemplate: () -> Unit
) {
    val viewModel = remember { DashboardViewModel(AppContainer.instance.taskRepository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val userEmoji by AppContainer.instance.tokenStore.userEmoji.collectAsState(initial = "🐣")
    val userName by AppContainer.instance.tokenStore.userName.collectAsState(
        initial = stringResource(R.string.common_you)
    )
    val userColor by AppContainer.instance.tokenStore.userColor.collectAsState(initial = null)
    val pendingSync by AppContainer.instance.taskRepository.pendingSyncCount
        .collectAsState(initial = 0)

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val lastAction = uiState.lastAction
    val readyMessage = lastAction?.let { stringResource(R.string.task_ready_toast, it.name) }
    val undoLabel = stringResource(R.string.task_undo)

    LaunchedEffect(lastAction) {
        if (lastAction == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = readyMessage.orEmpty(),
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undo()
        } else {
            viewModel.consumeLastAction()
        }
    }

    val now = Instant.now()

    val overdueTasks = uiState.tasks.filter { task ->
        task.dueAt != null && try {
            Instant.parse(task.dueAt).isBefore(now)
        } catch (_: Exception) { false }
    }

    val soonTasks = uiState.tasks.filter { task ->
        task.dueAt != null && try {
            val due = Instant.parse(task.dueAt)
            !due.isBefore(now) && due.isBefore(now.plusSeconds(48 * 3600))
        } catch (_: Exception) { false }
    }

    val upcomingTasks = uiState.tasks.filter { task ->
        task.dueAt != null && try {
            val due = Instant.parse(task.dueAt)
            !due.isBefore(now.plusSeconds(48 * 3600))
        } catch (_: Exception) { false }
    } + uiState.tasks.filter { it.dueAt == null }

    Scaffold(
        containerColor = SurfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(parseHexColor(userColor).copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = userEmoji ?: "🐣", fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = userName ?: stringResource(R.string.common_you),
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    // Escrituras locales que todavía no llegaron al servidor.
                    if (pendingSync > 0) {
                        Text(
                            text = "⟳ $pendingSync",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary.copy(alpha = 0.6f),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceBg
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreateTemplate,
                containerColor = Pink,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tasks_create_template))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.tasks.isEmpty() -> {
                    LoadingShimmer()
                }
                uiState.tasks.isEmpty() && uiState.error == null -> {
                    EmptyState(
                        icon = "📋",
                        title = stringResource(R.string.tasks_empty_title),
                        subtitle = stringResource(R.string.tasks_empty_subtitle)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (overdueTasks.isNotEmpty()) {
                            stickyHeader {
                                SectionHeader(
                                    title = stringResource(R.string.tasks_section_overdue),
                                    count = overdueTasks.size,
                                    backgroundColor = Pink.copy(alpha = 0.1f),
                                    textColor = Pink
                                )
                            }
                            items(overdueTasks.size, key = { overdueTasks[it].id }) { index ->
                                SwipeableTaskRow(
                                    task = overdueTasks[index],
                                    onComplete = { viewModel.completeTask(overdueTasks[index].id) },
                                    onSkip = { viewModel.skipTask(overdueTasks[index].id) },
                                    onClick = { onNavigateToTask(overdueTasks[index].id) }
                                )
                            }
                        }

                        if (soonTasks.isNotEmpty()) {
                            stickyHeader {
                                SectionHeader(
                                    title = stringResource(R.string.tasks_section_soon),
                                    count = soonTasks.size,
                                    backgroundColor = Color(0xFFFFF3E0),
                                    textColor = Color(0xFFE65100)
                                )
                            }
                            items(soonTasks.size, key = { soonTasks[it].id }) { index ->
                                SwipeableTaskRow(
                                    task = soonTasks[index],
                                    onComplete = { viewModel.completeTask(soonTasks[index].id) },
                                    onSkip = { viewModel.skipTask(soonTasks[index].id) },
                                    onClick = { onNavigateToTask(soonTasks[index].id) }
                                )
                            }
                        }

                        if (upcomingTasks.isNotEmpty()) {
                            stickyHeader {
                                SectionHeader(
                                    title = stringResource(R.string.tasks_section_upcoming),
                                    count = upcomingTasks.size,
                                    backgroundColor = Violet.copy(alpha = 0.08f),
                                    textColor = Violet
                                )
                            }
                            items(upcomingTasks.size, key = { upcomingTasks[it].id }) { index ->
                                SwipeableTaskRow(
                                    task = upcomingTasks[index],
                                    onComplete = { viewModel.completeTask(upcomingTasks[index].id) },
                                    onSkip = { viewModel.skipTask(upcomingTasks[index].id) },
                                    onClick = { onNavigateToTask(upcomingTasks[index].id) }
                                )
                            }
                        }

                        if (overdueTasks.isEmpty() && soonTasks.isEmpty() && upcomingTasks.isEmpty() && uiState.tasks.isEmpty()) {
                            item {
                                EmptyState(
                                    icon = "📋",
                                    title = stringResource(R.string.tasks_empty_title),
                                    subtitle = stringResource(R.string.tasks_empty_subtitle)
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    backgroundColor: Color,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = textColor.copy(alpha = 0.7f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskRow(
    task: TaskDTO,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onClick: () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onComplete(); false }
                SwipeToDismissBoxValue.EndToStart -> { onSkip(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val completing = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color = if (completing) CompleteGreen else SkipRed
            val label = stringResource(
                if (completing) R.string.task_done_label else R.string.task_skip
            )
            val alignment = if (completing) Alignment.CenterStart else Alignment.CenterEnd
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 5.dp)
                    .background(color.copy(alpha = 0.9f), RoundedCornerShape(18.dp)),
                contentAlignment = alignment
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    ) {
        TaskCard(task = task, onComplete = onComplete, onClick = onClick)
    }
}
