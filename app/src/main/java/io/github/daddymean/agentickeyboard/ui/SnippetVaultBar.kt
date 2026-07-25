package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.KeyboardTheme
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors
import io.github.daddymean.agentickeyboard.util.SnippetVaultSelectionPlan
import io.github.daddymean.agentickeyboard.util.SnippetVaultSelectionPlanner
import io.github.daddymean.agentickeyboard.util.SnippetVaultSource
import io.github.daddymean.agentickeyboard.util.SnippetVaultSearch
import io.github.daddymean.agentickeyboard.util.savedSnippetIdOrNull
import io.github.daddymean.agentickeyboard.util.toVaultEntry
import kotlinx.coroutines.launch

/**
 * Local-only `/v` and `/find` recall surface shown above the keyboard.
 *
 * Results never insert or run merely by matching. A tap is always required, and
 * secure fields suppress the entire surface. The manager action opens a local
 * companion screen; no clipboard, network, account, or analytics path exists.
 */
@Composable
fun SnippetVaultBar(
    viewModel: KeyboardViewModel,
    repository: KeyboardRepository,
    onReplaceDraft: (String) -> Unit,
    onOpenManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val draft by viewModel.inputText.collectAsState()
    val isSensitiveField by viewModel.isSensitiveField.collectAsState()
    val themeOverride by viewModel.themeOverride.collectAsState()
    val shortcuts by viewModel.shortcuts.collectAsState()
    val customCommands by viewModel.customCommands.collectAsState()
    val savedSnippets by repository.allSavedSnippets.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val request = remember(draft, isSensitiveField) {
        if (isSensitiveField) null else SnippetVaultSearch.parseRecall(draft)
    } ?: return

    val entries = remember(savedSnippets, shortcuts, customCommands) {
        buildList {
            addAll(savedSnippets.map { it.toVaultEntry() })
            addAll(shortcuts.map { it.toVaultEntry() })
            addAll(customCommands.map { it.toVaultEntry() })
        }
    }
    val matches = remember(entries, request.query) {
        SnippetVaultSearch.search(entries, request.query)
    }

    KeyboardTheme(
        darkTheme = when (themeOverride) {
            "Light" -> false
            "Dark" -> true
            else -> isSystemInDarkTheme()
        }
    ) {
        val colors = LocalKeyboardColors.current
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .testTag("snippet_vault_bar"),
            color = colors.panel
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🧰 Snippet Vault",
                            color = colors.text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                request.body.isNotBlank() -> "Tap a command to rewrite the text after ::"
                                request.query.isBlank() -> "Most-used local items"
                                else -> "Local matches for “${request.query}”"
                            },
                            color = colors.textMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    VaultActionChip("Manage") { onOpenManager() }
                }

                Spacer(modifier = Modifier.width(4.dp))

                if (matches.isEmpty()) {
                    Text(
                        text = "No local matches. Tap Manage to save a reusable snippet.",
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                } else {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp)
                            .testTag("snippet_vault_results"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(matches, key = { it.entry.stableId }) { match ->
                            val entry = match.entry
                            val icon = when (entry.source) {
                                SnippetVaultSource.SAVED_SNIPPET -> "📌"
                                SnippetVaultSource.SHORTCUT -> "⚡"
                                SnippetVaultSource.CUSTOM_COMMAND -> "↻"
                            }
                            Surface(
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .clickable {
                                        when (val plan = SnippetVaultSelectionPlanner.plan(request, entry)) {
                                            is SnippetVaultSelectionPlan.InsertText -> {
                                                onReplaceDraft(plan.text)
                                                entry.savedSnippetIdOrNull()?.let { id ->
                                                    scope.launch { repository.recordSavedSnippetUse(id) }
                                                }
                                                if (entry.source == SnippetVaultSource.SHORTCUT) {
                                                    viewModel.recordShortcutExpansionStat()
                                                }
                                            }
                                            is SnippetVaultSelectionPlan.StageCommand -> {
                                                onReplaceDraft("${plan.token} ")
                                            }
                                            is SnippetVaultSelectionPlan.RunRewrite -> {
                                                onReplaceDraft(plan.sourceText)
                                                viewModel.rewriteWithStyle(
                                                    plan.sourceText,
                                                    plan.instruction
                                                )
                                            }
                                        }
                                    }
                                    .testTag("snippet_result_${entry.stableId}"),
                                color = colors.chip,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "$icon ${entry.title}",
                                        color = colors.onChip,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = entry.content.replace('\n', ' '),
                                        color = colors.textMuted,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultActionChip(
    label: String,
    onClick: () -> Unit
) {
    val colors = LocalKeyboardColors.current
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = colors.accent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = label,
            color = colors.onAccent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
