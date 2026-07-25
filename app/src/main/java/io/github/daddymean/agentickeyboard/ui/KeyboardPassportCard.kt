package io.github.daddymean.agentickeyboard.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.AgenticKeyboardApplication
import io.github.daddymean.agentickeyboard.util.DEFAULT_PASSPORT_CATEGORIES
import io.github.daddymean.agentickeyboard.util.KeyboardPassport
import io.github.daddymean.agentickeyboard.util.KeyboardPassportImportMode
import io.github.daddymean.agentickeyboard.util.KeyboardPassportOpenResult
import io.github.daddymean.agentickeyboard.util.KeyboardPassportOptions
import io.github.daddymean.agentickeyboard.util.KeyboardPassportPreview
import io.github.daddymean.agentickeyboard.util.KeyboardPassportTransfer
import io.github.daddymean.agentickeyboard.util.PassportCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_PASSPORT_CHARS = 5_000_000

/** Companion-app file export/import UI. Nothing in this surface renders in the IME. */
@Composable
fun KeyboardPassportCard() {
    val context = LocalContext.current
    val app = context.applicationContext as AgenticKeyboardApplication
    val transfer = remember(app) {
        KeyboardPassportTransfer(app.database, app.repository, app.settings)
    }
    val scope = rememberCoroutineScope()

    var encryptExport by remember { mutableStateOf(true) }
    var exportPassphrase by remember { mutableStateOf("") }
    var includeWritingLogs by remember { mutableStateOf(false) }
    var redactSensitive by remember { mutableStateOf(true) }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    var importedContent by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<KeyboardPassportPreview?>(null) }
    var opened by remember { mutableStateOf<KeyboardPassportOpenResult.Success?>(null) }
    var importPassphrase by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf(KeyboardPassportImportMode.MERGE) }
    var confirmImport by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = runCatching {
                writePassport(context.contentResolver, uri, content)
                "Passport saved. Lumina did not upload or retain the file location."
            }.getOrElse { "Could not save passport: ${it.message ?: "file error"}" }
            busy = false
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = runCatching {
                readPassport(context.contentResolver, uri)
            }
            result.onSuccess { content ->
                importedContent = content
                importPassphrase = ""
                opened = null
                preview = KeyboardPassport.inspect(content)
                when (val openResult = KeyboardPassport.open(content)) {
                    is KeyboardPassportOpenResult.Success -> {
                        opened = openResult
                        preview = openResult.preview
                        status = "Passport verified. Review it before choosing Merge or Replace."
                    }
                    is KeyboardPassportOpenResult.PassphraseRequired -> {
                        preview = openResult.preview
                        status = "Encrypted passport selected. Enter its passphrase to verify the payload."
                    }
                    is KeyboardPassportOpenResult.Invalid -> {
                        status = openResult.reason
                    }
                }
            }.onFailure {
                importedContent = null
                preview = null
                opened = null
                status = it.message ?: "Could not read that file."
            }
            busy = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("keyboard_passport_card"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFC4B5FD))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🛂 KEYBOARD PASSPORT",
                color = Color(0xFF3B0764),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
            Text(
                "Move your learned keyboard between devices with a file you own. No Lumina account or cloud transfer is involved.",
                color = Color(0xFF5B5567),
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text("CREATE PASSPORT", color = Color(0xFF6D28D9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                "Includes persona, full learned vocabulary, corrections, shortcuts, slash commands, and per-app personas.",
                color = Color(0xFF5B5567),
                fontSize = 10.sp
            )

            PassportSwitchRow(
                title = "Encrypt with passphrase",
                description = "AES-256-GCM authenticated encryption. The passphrase cannot be recovered.",
                checked = encryptExport,
                onCheckedChange = {
                    encryptExport = it
                    if (!it) exportPassphrase = ""
                }
            )
            if (encryptExport) {
                OutlinedTextField(
                    value = exportPassphrase,
                    onValueChange = { exportPassphrase = it },
                    label = { Text("Export passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("passport_export_passphrase")
                )
            }
            PassportSwitchRow(
                title = "Redact sensitive identifiers",
                description = "Scrubs credential-shaped data before the passport is created.",
                checked = redactSensitive,
                onCheckedChange = { redactSensitive = it }
            )
            PassportSwitchRow(
                title = "Include raw writing logs",
                description = "Off by default. Logs can contain full text you previously analyzed.",
                checked = includeWritingLogs,
                onCheckedChange = { includeWritingLogs = it }
            )

            Button(
                onClick = {
                    if (encryptExport && exportPassphrase.isBlank()) {
                        status = "Enter a passphrase, or turn encryption off."
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        val categories = if (includeWritingLogs) {
                            DEFAULT_PASSPORT_CATEGORIES + PassportCategory.WRITING_LOGS
                        } else {
                            DEFAULT_PASSPORT_CATEGORIES
                        }
                        runCatching {
                            transfer.createPassport(
                                KeyboardPassportOptions(
                                    includedCategories = categories,
                                    passphrase = exportPassphrase.takeIf { encryptExport },
                                    redactSensitiveText = redactSensitive
                                )
                            )
                        }.onSuccess { content ->
                            pendingExport = content
                            status = null
                            createDocument.launch("lumina-keyboard-passport-${System.currentTimeMillis()}.json")
                        }.onFailure {
                            status = "Could not create passport: ${it.message ?: "export error"}"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D28D9)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("passport_export_file")
            ) {
                Text(if (busy) "Working…" else "Save Passport File", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text("RESTORE PASSPORT", color = Color(0xFF6D28D9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                "Selecting a file only reads its preview. Nothing changes until you unlock it, choose a mode, and confirm.",
                color = Color(0xFF5B5567),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF6D28D9)
                ),
                border = BorderStroke(1.dp, Color(0xFFA78BFA)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("passport_choose_file")
            ) {
                Text("Choose Passport File", fontWeight = FontWeight.Bold)
            }

            preview?.let { selected ->
                Spacer(modifier = Modifier.height(10.dp))
                PassportPreviewPanel(selected)

                if (selected.requiresPassphrase && opened == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importPassphrase,
                        onValueChange = { importPassphrase = it },
                        label = { Text("Passport passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("passport_import_passphrase")
                    )
                    Button(
                        onClick = {
                            val content = importedContent ?: return@Button
                            when (val result = KeyboardPassport.open(content, importPassphrase)) {
                                is KeyboardPassportOpenResult.Success -> {
                                    opened = result
                                    preview = result.preview
                                    status = "Passport decrypted and verified. No data has been imported yet."
                                }
                                is KeyboardPassportOpenResult.PassphraseRequired -> {
                                    status = "Enter the passport passphrase."
                                }
                                is KeyboardPassportOpenResult.Invalid -> {
                                    status = result.reason
                                }
                            }
                        },
                        enabled = importPassphrase.isNotBlank() && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C1D95)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("passport_unlock")
                    ) {
                        Text("Unlock and Verify", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            opened?.let { verified ->
                Spacer(modifier = Modifier.height(10.dp))
                Text("IMPORT MODE", color = Color(0xFF6D28D9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PassportModeChip(
                        label = "Merge",
                        selected = importMode == KeyboardPassportImportMode.MERGE,
                        modifier = Modifier.weight(1f)
                    ) { importMode = KeyboardPassportImportMode.MERGE }
                    PassportModeChip(
                        label = "Replace included",
                        selected = importMode == KeyboardPassportImportMode.REPLACE,
                        modifier = Modifier.weight(1f)
                    ) { importMode = KeyboardPassportImportMode.REPLACE }
                }
                Text(
                    if (importMode == KeyboardPassportImportMode.MERGE) {
                        "Merge combines counts and lets imported shortcuts, commands, corrections, and app personas win matching-key conflicts."
                    } else {
                        "Replace clears only the categories listed in this passport. Categories it does not contain remain untouched."
                    },
                    color = Color(0xFF5B5567),
                    fontSize = 9.sp,
                    lineHeight = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { confirmImport = true },
                    enabled = !busy && verified.preview.compatible,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D28D9)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("passport_review_import")
                ) {
                    Text("Review and Confirm Import", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            status?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    it,
                    color = if (it.startsWith("Could not") || it.startsWith("Wrong") || it.startsWith("Enter")) {
                        Color(0xFFB3261E)
                    } else {
                        Color(0xFF4C1D95)
                    },
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("passport_status")
                )
            }
        }
    }

    if (confirmImport) {
        val verified = opened
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import this Keyboard Passport?") },
            text = {
                Text(
                    when (importMode) {
                        KeyboardPassportImportMode.MERGE ->
                            "Lumina will merge the verified passport into the categories shown in the preview. Existing records outside those categories stay unchanged."
                        KeyboardPassportImportMode.REPLACE ->
                            "Lumina will replace only the categories shown in the preview. This cannot be undone automatically. Categories absent from the file stay unchanged."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmImport = false
                        if (verified == null) return@Button
                        scope.launch {
                            busy = true
                            runCatching { transfer.apply(verified, importMode) }
                                .onSuccess { result ->
                                    status = "Imported ${result.incomingRecordCount} record(s) across ${result.affectedCategories.size} category area(s) using ${result.mode.name.lowercase()}."
                                    importedContent = null
                                    preview = null
                                    opened = null
                                    importPassphrase = ""
                                }
                                .onFailure {
                                    status = "Could not apply passport: ${it.message ?: "import error"}"
                                }
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (importMode == KeyboardPassportImportMode.REPLACE) Color(0xFFB3261E) else Color(0xFF6D28D9)
                    ),
                    modifier = Modifier.testTag("passport_confirm_import")
                ) {
                    Text(if (importMode == KeyboardPassportImportMode.REPLACE) "Replace Included" else "Merge Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PassportSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color(0xFF3B0764), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(description, color = Color(0xFF6B6473), fontSize = 9.sp, lineHeight = 12.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PassportPreviewPanel(preview: KeyboardPassportPreview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(10.dp)
            .testTag("passport_preview")
    ) {
        Text(
            buildString {
                append(if (preview.encrypted) "🔐 Encrypted" else "🔓 Unencrypted")
                append(" · v${preview.version}")
                if (preview.legacy) append(" · Legacy")
            },
            color = Color(0xFF3B0764),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        val counts = preview.counts
        Text(
            "${counts.vocabulary} vocabulary · ${counts.corrections} corrections · ${counts.shortcuts} shortcuts · " +
                "${counts.customCommands} commands · ${counts.appPersonas} app personas · ${counts.writingLogs} logs",
            color = Color(0xFF5B5567),
            fontSize = 9.sp,
            lineHeight = 12.sp
        )
        Text(
            preview.categories.sortedBy { it.wireName }.joinToString(" · ") { categoryLabel(it) },
            color = Color(0xFF7C3AED),
            fontSize = 9.sp
        )
        if (!preview.compatible) {
            Text("This passport version is not compatible with this app.", color = Color(0xFFB3261E), fontSize = 9.sp)
        }
    }
}

@Composable
private fun PassportModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (selected) Color.White else Color(0xFF4C1D95),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF6D28D9) else Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("passport_mode_${label.lowercase().replace(' ', '_')}")
    )
}

private fun categoryLabel(category: PassportCategory): String = when (category) {
    PassportCategory.PERSONA_PREFERENCE -> "persona"
    PassportCategory.VOCABULARY -> "vocabulary"
    PassportCategory.CORRECTIONS -> "corrections"
    PassportCategory.SHORTCUTS -> "shortcuts"
    PassportCategory.CUSTOM_COMMANDS -> "commands"
    PassportCategory.APP_PERSONAS -> "app personas"
    PassportCategory.WRITING_LOGS -> "writing logs"
}

private suspend fun writePassport(
    resolver: android.content.ContentResolver,
    uri: Uri,
    content: String
) = withContext(Dispatchers.IO) {
    resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
        writer.write(content)
    } ?: error("The selected file could not be opened for writing.")
}

private suspend fun readPassport(
    resolver: android.content.ContentResolver,
    uri: Uri
): String = withContext(Dispatchers.IO) {
    resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (output.length + count > MAX_PASSPORT_CHARS) {
                error("Passport exceeds the 5 MB safety limit.")
            }
            output.append(buffer, 0, count)
        }
        output.toString()
    } ?: error("The selected file could not be opened.")
}