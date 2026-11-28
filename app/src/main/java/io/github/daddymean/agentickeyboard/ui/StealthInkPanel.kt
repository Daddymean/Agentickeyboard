package io.github.daddymean.agentickeyboard.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.stealth.PassphraseStrength
import io.github.daddymean.agentickeyboard.stealth.StealthInkCodec
import io.github.daddymean.agentickeyboard.stealth.StealthInkNotices
import io.github.daddymean.agentickeyboard.stealth.StealthMode
import io.github.daddymean.agentickeyboard.stealth.StealthOpenResult
import io.github.daddymean.agentickeyboard.stealth.StealthTtl
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors
import io.github.daddymean.agentickeyboard.util.ClipboardSensitivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StealthTab(val label: String) {
    SEAL("📦 Seal"),
    INVISIBLE("👻 Invisible Ink"),
    PEEK("🔓 Peek")
}

private const val EPHEMERAL_PEEK_SECONDS = 15

/**
 * Keyboard-native StealthInk drawer for passphrase-based AES-256-GCM envelopes,
 * Unicode Variation Selector invisible-ink steganography, and self-wiping
 * ephemeral Peek previews.
 *
 * Security invariants:
 * - Passphrase and decrypted plaintext are kept in memory only (never written to disk) and
 *   are dropped on close, sensitive-field transition, or after 15s of Peek. Best effort:
 *   JVM Strings are immutable, so copies may linger in memory until garbage collected.
 * - Passphrase typing is routed directly from the IME keys below without ever
 *   touching the host app's InputConnection, EditTimeline, or Twin.
 * - Zero network calls and zero disk persistence.
 */
@Composable
fun StealthInkPanel(
    initialTab: StealthTab,
    initialPeekPayload: String?,
    passphrase: String,
    passphraseFocused: Boolean,
    isCompact: Boolean,
    onTogglePassphraseFocus: (Boolean) -> Unit,
    onSetPassphrase: (String) -> Unit,
    readDraftText: () -> String,
    onCommitSealed: (sealedText: String, statusMessage: String) -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalKeyboardColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }
    var selectedTtl by remember { mutableStateOf(StealthTtl.NONE) }
    var selectedCover by remember { mutableStateOf(StealthInkCodec.DEFAULT_COVERS.first()) }
    var showPassphrase by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }

    var peekSource by remember(initialPeekPayload) { mutableStateOf(initialPeekPayload) }
    var peekSuccess by remember { mutableStateOf<StealthOpenResult.Success?>(null) }
    var peekSecondsLeft by remember { mutableIntStateOf(0) }

    // Ephemeral auto-wipe timer for decrypted Peek plaintext.
    LaunchedEffect(peekSuccess) {
        if (peekSuccess == null) {
            peekSecondsLeft = 0
            return@LaunchedEffect
        }
        for (remaining in EPHEMERAL_PEEK_SECONDS downTo 1) {
            peekSecondsLeft = remaining
            delay(1_000L)
        }
        peekSuccess = null
        peekSecondsLeft = 0
    }

    val strength = remember(passphrase) { StealthInkCodec.passphraseStrength(passphrase) }
    // Sealing needs an acceptable (12+ characters, not weak) passphrase; opening only needs the
    // minimum length because the sender already chose the passphrase.
    val sealReady = strength.acceptable
    val openReady = passphrase.codePointCount(0, passphrase.length) >= StealthInkCodec.MIN_PASSPHRASE_CHARS

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("stealth_ink_panel"),
        color = colors.panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (isCompact) 140.dp else 230.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // --- Header & Mode Tabs ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StealthTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        Text(
                            text = tab.label,
                            color = if (selected) colors.accent else colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.chip)
                                .clickable {
                                    activeTab = tab
                                    statusError = null
                                    if (tab != StealthTab.PEEK) peekSuccess = null
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("stealth_tab_${tab.name.lowercase()}")
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "✕",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.chip)
                        .clickable {
                            peekSuccess = null
                            onClose()
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("stealth_close")
                )
            }

            // --- Volatile Passphrase Bar (IME-internal key capture) ---
            val borderColor = if (passphraseFocused) colors.accent else colors.chipAlt
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.shelf)
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .clickable { onTogglePassphraseFocus(!passphraseFocused) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("stealth_passphrase_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (passphraseFocused) "⌨️" else "🔑",
                    fontSize = 12.sp
                )
                val displayText = when {
                    passphrase.isEmpty() && passphraseFocused -> "Type key on keyboard below…"
                    passphrase.isEmpty() -> "Tap to type a shared passphrase (12+ characters)"
                    showPassphrase -> passphrase
                    else -> "•".repeat(passphrase.length.coerceAtMost(24))
                }
                Text(
                    text = displayText,
                    color = if (passphrase.isEmpty()) colors.textMuted else colors.text,
                    fontSize = 11.sp,
                    fontWeight = if (passphrase.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (passphrase.isNotEmpty()) {
                    val strengthColor = when (strength) {
                        PassphraseStrength.TOO_SHORT, PassphraseStrength.WEAK -> colors.error
                        PassphraseStrength.FAIR -> colors.textMuted
                        PassphraseStrength.GOOD -> colors.accent
                        PassphraseStrength.STRONG -> colors.success
                    }
                    Text(
                        text = strength.label,
                        color = strengthColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = if (showPassphrase) "🙈" else "👁",
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showPassphrase = !showPassphrase }
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                    Text(
                        text = "Clear",
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.chip)
                            .clickable {
                                onSetPassphrase("")
                                statusError = null
                            }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                } else {
                    Text(
                        text = "Paste key",
                        color = colors.onChip,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.chip)
                            .clickable {
                                val clip = readClipboardUnflagged(context)
                                if (clip.isNullOrBlank()) {
                                    statusError = "Clipboard is empty"
                                } else {
                                    onSetPassphrase(clip.trim().take(StealthInkCodec.MAX_PASSPHRASE_CHARS))
                                    onTogglePassphraseFocus(false)
                                    statusError = null
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // --- Tab Body ---
            when (activeTab) {
                StealthTab.SEAL, StealthTab.INVISIBLE -> {
                    // Expiry selector (advisory: see StealthInkNotices.EXPIRY)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("Expires:", color = colors.textMuted, fontSize = 10.sp)
                        StealthTtl.entries.forEach { ttl ->
                            val picked = selectedTtl == ttl
                            Text(
                                text = ttl.label,
                                color = if (picked) colors.accent else colors.textMuted,
                                fontSize = 10.sp,
                                fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (picked) colors.accent.copy(alpha = 0.18f) else colors.chip)
                                    .clickable { selectedTtl = ttl }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                                    .testTag("stealth_ttl_${ttl.name.lowercase()}")
                            )
                        }
                    }

                    // Plain-language limitations, always visible next to the controls.
                    val notices = buildList {
                        if (activeTab == StealthTab.INVISIBLE) add(StealthInkNotices.INVISIBLE_INK)
                        if (selectedTtl != StealthTtl.NONE) add(StealthInkNotices.EXPIRY)
                        add(StealthInkNotices.SHARED_KEY)
                        if (!sealReady) add("Use 12+ characters and avoid common words or simple patterns.")
                    }
                    Text(
                        text = notices.joinToString(" "),
                        color = colors.textMuted,
                        fontSize = 9.sp,
                        modifier = Modifier.fillMaxWidth().testTag("stealth_limitations")
                    )

                    // Cover phrase picker for Invisible Ink mode
                    if (activeTab == StealthTab.INVISIBLE) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text("Cover:", color = colors.textMuted, fontSize = 10.sp)
                            StealthInkCodec.DEFAULT_COVERS.forEach { cover ->
                                val picked = selectedCover == cover
                                Text(
                                    text = cover,
                                    color = if (picked) colors.accent else colors.onChip,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (picked) colors.accent.copy(alpha = 0.18f) else colors.chip)
                                        .clickable { selectedCover = cover }
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (activeTab == StealthTab.INVISIBLE) {
                                "Hides the encrypted payload in cover text (detectable)"
                            } else {
                                "Replaces draft with an AES-256-GCM 🔐AK1 envelope"
                            },
                            color = colors.textMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val actionEnabled = sealReady && !isWorking
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (actionEnabled) colors.accent else colors.chip)
                                .clickable(enabled = actionEnabled) {
                                    val draft = readDraftText()
                                    if (draft.isBlank()) {
                                        statusError = "Type your secret in the message box first"
                                        return@clickable
                                    }
                                    if (draft.toByteArray(Charsets.UTF_8).size > StealthInkCodec.MAX_PLAINTEXT_BYTES) {
                                        statusError = "Draft exceeds 2 KB StealthInk limit"
                                        return@clickable
                                    }
                                    onTogglePassphraseFocus(false)
                                    isWorking = true
                                    statusError = null
                                    val tabNow = activeTab
                                    val ttlNow = selectedTtl
                                    val coverNow = selectedCover
                                    val keyNow = passphrase
                                    scope.launch {
                                        val sealed = withContext(Dispatchers.Default) {
                                            runCatching {
                                                if (tabNow == StealthTab.INVISIBLE) {
                                                    StealthInkCodec.sealInvisibleInk(draft, keyNow, coverNow, ttlNow)
                                                } else {
                                                    StealthInkCodec.sealEnvelope(draft, keyNow, ttlNow)
                                                }
                                            }
                                        }
                                        isWorking = false
                                        sealed.onSuccess { output ->
                                            val ttlNote = if (ttlNow == StealthTtl.NONE) "" else " • expires in ${ttlNow.label} (not deletion)"
                                            val msg = if (tabNow == StealthTab.INVISIBLE) {
                                                "👻 Hidden in cover text, apps may detect or strip it$ttlNote"
                                            } else {
                                                "🔐 Sealed AES-256-GCM$ttlNote"
                                            }
                                            onCommitSealed(output, msg)
                                        }.onFailure {
                                            statusError = "Could not seal draft"
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag("stealth_seal_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isWorking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Text(
                                    text = if (activeTab == StealthTab.INVISIBLE) "Hide Draft 👻" else "Seal Draft 🔐",
                                    color = if (actionEnabled) Color.White else colors.textMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                StealthTab.PEEK -> {
                    val activePayload = peekSource?.takeIf { StealthInkCodec.detect(it) != null }
                        ?: readDraftText().takeIf { StealthInkCodec.detect(it) != null }
                    val detectedKind = activePayload?.let(StealthInkCodec::detect)

                    if (peekSuccess != null) {
                        val unlocked = peekSuccess!!
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF103326))
                                .padding(8.dp)
                                .testTag("stealth_peek_result"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val ttlSuffix = unlocked.remainingSeconds?.let {
                                    " • expires in ${StealthInkCodec.formatRemaining(it)}"
                                }.orEmpty()
                                val modeTag = if (unlocked.mode == StealthMode.INVISIBLE_INK) "👻 Invisible" else "📦 Envelope"
                                Text(
                                    text = "$modeTag • wipes in ${peekSecondsLeft}s$ttlSuffix",
                                    color = Color(0xFF86EFAC),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Copy",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.15f))
                                            .clickable {
                                                copySensitiveToClipboard(context, unlocked.plaintext)
                                                statusError = "Copied privately (masked in OS preview)"
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                    Text(
                                        text = "Wipe",
                                        color = Color(0xFFFCA5A5),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .clickable { peekSuccess = null }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                            .testTag("stealth_wipe_now")
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { peekSecondsLeft.toFloat() / EPHEMERAL_PEEK_SECONDS.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = Color(0xFF4ADE80),
                                trackColor = Color.White.copy(alpha = 0.15f)
                            )
                            Text(
                                text = unlocked.plaintext,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = when (detectedKind) {
                                    StealthMode.ENVELOPE -> "📦 Sealed envelope ready to unlock"
                                    StealthMode.INVISIBLE_INK -> "👻 Hidden Invisible Ink detected!"
                                    null -> "Copy a StealthInk message, then tap Clipboard"
                                },
                                color = if (detectedKind != null) colors.accent else colors.textMuted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    text = "📋 Clip",
                                    color = colors.onChip,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.chip)
                                        .clickable {
                                            val clip = readClipboardUnflagged(context)
                                            if (clip.isNullOrBlank()) {
                                                statusError = "Clipboard is empty"
                                            } else if (StealthInkCodec.detect(clip) == null) {
                                                statusError = "No StealthInk payload found in clipboard"
                                            } else {
                                                peekSource = clip
                                                statusError = null
                                            }
                                        }
                                        .padding(horizontal = 7.dp, vertical = 5.dp)
                                )
                                val canUnlock = activePayload != null && openReady && !isWorking
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (canUnlock) colors.accent else colors.chip)
                                        .clickable(enabled = canUnlock) {
                                            val payloadNow = activePayload ?: return@clickable
                                            val keyNow = passphrase
                                            onTogglePassphraseFocus(false)
                                            isWorking = true
                                            statusError = null
                                            scope.launch {
                                                val result = withContext(Dispatchers.Default) {
                                                    StealthInkCodec.open(payloadNow, keyNow)
                                                }
                                                isWorking = false
                                                when (result) {
                                                    is StealthOpenResult.Success -> peekSuccess = result
                                                    is StealthOpenResult.Expired -> {
                                                        statusError = "⏳ Expired: this keyboard won't open it. The message itself is not deleted."
                                                    }
                                                    StealthOpenResult.WrongPassphraseOrTampered -> {
                                                        statusError = "🚫 Wrong key or message was tampered with"
                                                    }
                                                    is StealthOpenResult.UnsupportedVersion -> {
                                                        statusError = "Unsupported StealthInk format v${result.version}"
                                                    }
                                                    StealthOpenResult.Malformed -> {
                                                        statusError = "Not a valid StealthInk payload"
                                                    }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                        .testTag("stealth_unlock_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isWorking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                    } else {
                                        Text(
                                            text = "Unlock 🔓",
                                            color = if (canUnlock) Color.White else colors.textMuted,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            statusError?.let { msg ->
                Text(
                    text = msg,
                    color = colors.error,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("stealth_status")
                )
            }
        }
    }
}

private fun readClipboardUnflagged(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val snapshot = runCatching { ClipboardSensitivity.readPrimaryClip(manager) }.getOrNull() ?: return null
    if (snapshot.flaggedSensitive) return null
    return snapshot.text
}

private fun copySensitiveToClipboard(context: Context, plaintext: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("StealthInk", plaintext)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    manager.setPrimaryClip(clip)
}
