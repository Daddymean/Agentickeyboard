package io.github.daddymean.agentickeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.SavedSnippet
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import io.github.daddymean.agentickeyboard.util.SnippetListCodec
import kotlinx.coroutines.launch

/** Dedicated companion-app surface for local Snippet Vault management. */
class SnippetVaultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as AgenticKeyboardApplication).repository
        setContent {
            MyApplicationTheme {
                SnippetVaultManagerScreen(
                    repository = repository,
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
private fun SnippetVaultManagerScreen(
    repository: KeyboardRepository,
    onClose: () -> Unit
) {
    val snippets by repository.allSavedSnippets.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<SavedSnippet?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun resetForm() {
        editing = null
        title = ""
        content = ""
        aliases = ""
        tags = ""
    }

    fun beginEditing(snippet: SavedSnippet) {
        editing = snippet
        title = snippet.title
        content = snippet.content
        aliases = SnippetListCodec.decode(snippet.aliases).joinToString(", ")
        tags = SnippetListCodec.decode(snippet.tags).joinToString(", ")
        feedback = null
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Snippet Vault",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Reusable text stored only on this device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = onClose) {
                    Text("Done")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Private by construction",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Vault entries and recall queries are not uploaded. Clipboard history is not enabled, and secure fields never show recall results.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (editing == null) "Save a snippet" else "Edit snippet",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Use /v or /find in the keyboard to recall it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        placeholder = { Text("Shipping address") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("snippet_title_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Text to insert") },
                        placeholder = { Text("Your reusable message or information") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("snippet_content_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = aliases,
                        onValueChange = { aliases = it },
                        label = { Text("Aliases") },
                        supportingText = { Text("Comma-separated search names") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text("Tags") },
                        supportingText = { Text("Comma-separated categories") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    feedback?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val cleanTitle = title.trim()
                                val cleanContent = content.trim()
                                if (cleanTitle.isEmpty() || cleanContent.isEmpty()) {
                                    feedback = "Title and text are required."
                                    return@Button
                                }
                                val now = System.currentTimeMillis()
                                val existing = editing
                                val snippet = if (existing == null) {
                                    SavedSnippet(
                                        title = cleanTitle,
                                        content = cleanContent,
                                        aliases = SnippetListCodec.encode(parseListInput(aliases)),
                                        tags = SnippetListCodec.encode(parseListInput(tags)),
                                        createdAt = now,
                                        updatedAt = now
                                    )
                                } else {
                                    existing.copy(
                                        title = cleanTitle,
                                        content = cleanContent,
                                        aliases = SnippetListCodec.encode(parseListInput(aliases)),
                                        tags = SnippetListCodec.encode(parseListInput(tags)),
                                        updatedAt = now
                                    )
                                }
                                scope.launch {
                                    repository.upsertSavedSnippet(snippet)
                                    resetForm()
                                    feedback = null
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_snippet_button")
                        ) {
                            Text(if (editing == null) "Save snippet" else "Save changes")
                        }
                        if (editing != null) {
                            OutlinedButton(
                                onClick = {
                                    resetForm()
                                    feedback = null
                                }
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Saved snippets (${snippets.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Existing quick templates and slash commands also appear in vault search and remain managed in the main Lumina app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        if (snippets.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "No saved snippets yet. Add an address, response template, policy, or any text you reuse often.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(snippets, key = { it.id }) { snippet ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("saved_snippet_${snippet.id}"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = snippet.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = snippet.content.replace('\n', ' '),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        val labels = buildList {
                            addAll(SnippetListCodec.decode(snippet.aliases))
                            addAll(SnippetListCodec.decode(snippet.tags).map { "#$it" })
                        }
                        if (labels.isNotEmpty() || snippet.usageCount > 0) {
                            Text(
                                text = buildString {
                                    if (labels.isNotEmpty()) append(labels.joinToString(" · "))
                                    if (snippet.usageCount > 0) {
                                        if (isNotEmpty()) append("  •  ")
                                        append("used ${snippet.usageCount}×")
                                    }
                                },
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                    }
                    OutlinedButton(onClick = { beginEditing(snippet) }) {
                        Text("Edit")
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                repository.deleteSavedSnippetById(snippet.id)
                                if (editing?.id == snippet.id) resetForm()
                            }
                        },
                        modifier = Modifier.testTag("delete_snippet_${snippet.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete ${snippet.title}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun parseListInput(raw: String): List<String> = raw
    .split(',', '\n')
    .map(String::trim)
    .filter(String::isNotEmpty)
