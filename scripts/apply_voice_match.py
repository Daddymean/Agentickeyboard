from pathlib import Path

VIEW_MODEL = Path("app/src/main/java/io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt")
LAYOUT = Path("app/src/main/java/io/github/daddymean/agentickeyboard/ui/AgenticKeyboardLayout.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def transform_view_model() -> None:
    text = VIEW_MODEL.read_text()

    text = replace_once(
        text,
        "import io.github.daddymean.agentickeyboard.util.SendGuard\n",
        "import io.github.daddymean.agentickeyboard.util.SendGuard\n"
        "import io.github.daddymean.agentickeyboard.util.VoiceMatchScorer\n"
        "import io.github.daddymean.agentickeyboard.util.VoiceSample\n"
        "import io.github.daddymean.agentickeyboard.util.VoiceVocabulary\n",
        "voice scorer imports",
    )

    usage_stats = '''data class UsageStats(
    val autoCorrections: Int = 0,
    val swipeWords: Int = 0,
    val aiApplies: Int = 0,
    val shortcutExpansions: Int = 0
)
'''
    text = replace_once(
        text,
        usage_stats,
        usage_stats + '''
/** Transient, local-only style match shown beside eligible AI writing results. */
data class VoiceMatchState(
    val percent: Int,
    val confidence: Int,
    val label: String,
    val signals: List<String>,
    val delta: Int? = null
)
''',
        "voice match UI state",
    )

    panel_anchor = '''    private val _aiPanelState = aiSession.mutablePanelState
    val aiPanelState = aiSession.panelState
'''
    text = replace_once(
        text,
        panel_anchor,
        panel_anchor + '''
    private val _voiceMatch = MutableStateFlow<VoiceMatchState?>(null)
    val voiceMatch = _voiceMatch.asStateFlow()
    private var pendingVoiceBaseline: Int? = null
''',
        "voice match flow",
    )

    refine_old = '''    fun refineResult(adjustment: String) {
        val current = aiSession.currentState.refinableText ?: return
        val instruction = RESULT_REFINEMENTS[adjustment] ?: adjustment
        recordMastery(MasteryEvent.REFINEMENT)
        dismissResults()
        rewriteWithStyle(current, instruction, bypassCache = true)
    }
'''
    refine_new = '''    fun refineResult(adjustment: String) {
        val current = aiSession.currentState.refinableText ?: return
        val instruction = RESULT_REFINEMENTS[adjustment] ?: adjustment
        val baseline = _voiceMatch.value?.percent
        recordMastery(MasteryEvent.REFINEMENT)
        dismissResults()
        pendingVoiceBaseline = baseline
        rewriteWithStyle(current, instruction, bypassCache = true)
    }
'''
    text = replace_once(text, refine_old, refine_new, "refinement baseline")

    launch_old = '''    private fun launchAi(block: suspend () -> Unit) {
        aiSession.launch { block() }
    }
'''
    launch_new = '''    private fun launchAi(block: suspend () -> Unit) {
        _voiceMatch.value = null
        aiSession.launch { block() }
    }
'''
    text = replace_once(text, launch_old, launch_new, "launch score cleanup")

    dismiss_old = '''    fun dismissResults() {
        aiSession.clear()
        _sendGuardWarning.value = null
    }
'''
    dismiss_new = '''    fun dismissResults() {
        aiSession.clear()
        _voiceMatch.value = null
        pendingVoiceBaseline = null
        _sendGuardWarning.value = null
    }

    private fun publishAiPanel(state: AiPanelState) {
        _aiPanelState.value = state
        val candidate = when (state) {
            is AiPanelState.Grammar -> state.result.corrected.takeUnless {
                state.result.explanation.startsWith("Error", ignoreCase = true)
            }
            is AiPanelState.Rewrite -> state.text
            is AiPanelState.Compose -> state.text
            is AiPanelState.Continuation -> state.text
            else -> null
        }?.takeUnless {
            it.startsWith("Rewrite error", ignoreCase = true) ||
                it.startsWith("Compose error", ignoreCase = true) ||
                it.startsWith("[Offline:", ignoreCase = true)
        }

        val score = candidate
            ?.takeIf { !_isSensitiveField.value }
            ?.let { text ->
                VoiceMatchScorer.score(
                    candidate = text,
                    vocabulary = topVocabulary.value.take(100).map {
                        VoiceVocabulary(word = it.word, count = it.count)
                    },
                    samples = logs.value.asSequence()
                        .filter { it.sentiment != "Corrected" }
                        .take(30)
                        .map { VoiceSample(text = it.originalText, wordCount = it.wordCount) }
                        .toList()
                )
            }

        _voiceMatch.value = score?.let {
            VoiceMatchState(
                percent = it.percent,
                confidence = it.confidence,
                label = it.label,
                signals = it.signals,
                delta = pendingVoiceBaseline?.let { baseline -> it.percent - baseline }
            )
        }
        pendingVoiceBaseline = null
    }
'''
    text = replace_once(text, dismiss_old, dismiss_new, "voice score publication")

    exact_replacements = [
        ("            _aiPanelState.value = AiPanelState.Grammar(it)\n", "            publishAiPanel(AiPanelState.Grammar(it))\n", "proofread promotion"),
        ("                _aiPanelState.value = AiPanelState.Grammar(result)\n", "                publishAiPanel(AiPanelState.Grammar(result))\n", "grammar success"),
        ("                _aiPanelState.value = AiPanelState.Rewrite(result, text, targetTone)\n", "                publishAiPanel(AiPanelState.Rewrite(result, text, targetTone))\n", "persona rewrite success"),
        ("                _aiPanelState.value = AiPanelState.Rewrite(result, text, styleInstruction)\n", "                publishAiPanel(AiPanelState.Rewrite(result, text, styleInstruction))\n", "style rewrite success"),
        ("                _aiPanelState.value = AiPanelState.Compose(result)\n", "                publishAiPanel(AiPanelState.Compose(result))\n", "compose success"),
        ("                _aiPanelState.value = AiPanelState.Compose(\"Compose error: ${e.localizedMessage}\")\n", "                publishAiPanel(AiPanelState.Compose(\"Compose error: ${e.localizedMessage}\"))\n", "compose error"),
    ]
    for old, new, label in exact_replacements:
        text = replace_once(text, old, new, label)

    grammar_error_old = '''                _aiPanelState.value = AiPanelState.Grammar(
                    GrammarCorrectionResponse(text, text, "Error: ${e.localizedMessage}", 0)
                )
'''
    grammar_error_new = '''                publishAiPanel(
                    AiPanelState.Grammar(
                        GrammarCorrectionResponse(text, text, "Error: ${e.localizedMessage}", 0)
                    )
                )
'''
    text = replace_once(text, grammar_error_old, grammar_error_new, "grammar error")

    rewrite_tone_error_old = '''                _aiPanelState.value = AiPanelState.Rewrite(
                    "Rewrite error: ${e.localizedMessage}", text, targetTone
                )
'''
    rewrite_tone_error_new = '''                publishAiPanel(
                    AiPanelState.Rewrite(
                        "Rewrite error: ${e.localizedMessage}", text, targetTone
                    )
                )
'''
    text = replace_once(text, rewrite_tone_error_old, rewrite_tone_error_new, "persona rewrite error")

    rewrite_style_error_old = '''                _aiPanelState.value = AiPanelState.Rewrite(
                    "Rewrite error: ${e.localizedMessage}", text, styleInstruction
                )
'''
    rewrite_style_error_new = '''                publishAiPanel(
                    AiPanelState.Rewrite(
                        "Rewrite error: ${e.localizedMessage}", text, styleInstruction
                    )
                )
'''
    text = replace_once(text, rewrite_style_error_old, rewrite_style_error_new, "style rewrite error")

    continuation_old = '''                _aiPanelState.value = result.takeIf { it.isNotBlank() }
                    ?.let(AiPanelState::Continuation)
                    ?: AiPanelState.Idle
'''
    continuation_new = '''                val panel = result.takeIf { it.isNotBlank() }
                    ?.let(AiPanelState::Continuation)
                    ?: AiPanelState.Idle
                publishAiPanel(panel)
'''
    text = replace_once(text, continuation_old, continuation_new, "continuation publication")

    VIEW_MODEL.write_text(text)


def transform_layout() -> None:
    text = LAYOUT.read_text()

    text = replace_once(
        text,
        "    val aiPanelState by viewModel.aiPanelState.collectAsState()\n",
        "    val aiPanelState by viewModel.aiPanelState.collectAsState()\n"
        "    val voiceMatch by viewModel.voiceMatch.collectAsState()\n",
        "voice match collection",
    )

    text = replace_once(
        text,
        ".then(if (resultExpanded) Modifier.heightIn(min = 64.dp) else Modifier.height(64.dp))",
        ".then(if (resultExpanded || voiceMatch != null) Modifier.heightIn(min = 64.dp) else Modifier.height(64.dp))",
        "voice match shelf height",
    )
    text = replace_once(
        text,
        ".padding(horizontal = 8.dp, vertical = if (resultExpanded) 8.dp else 0.dp)",
        ".padding(horizontal = 8.dp, vertical = if (resultExpanded || voiceMatch != null) 8.dp else 0.dp)",
        "voice match shelf padding",
    )

    column_close = '''                    }
                    }
                    if (hasAiResult) {
'''
    column_replacement = '''                    }
                    voiceMatch?.let { match ->
                        Spacer(modifier = Modifier.height(4.dp))
                        VoiceMatchBadge(match)
                    }
                    }
                    if (hasAiResult) {
'''
    text = replace_once(text, column_close, column_replacement, "voice match badge insertion")

    badge = '''

@Composable
private fun VoiceMatchBadge(match: VoiceMatchState) {
    val keyboardColors = LocalKeyboardColors.current
    val deltaText = when {
        match.delta == null -> ""
        match.delta > 0 -> " · ↑ ${match.delta} after refine"
        match.delta < 0 -> " · ${-match.delta} point shift"
        else -> " · steady after refine"
    }
    val confidenceText = if (match.confidence < 35) " · early estimate" else ""
    val signalText = match.signals.firstOrNull()?.let { " · $it" }.orEmpty()

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(keyboardColors.accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("voice_match_badge"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🎙 ${match.percent}% your voice$deltaText$confidenceText$signalText",
            color = keyboardColors.accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
'''
    if "private fun VoiceMatchBadge(" in text:
        raise SystemExit("voice match badge already exists")
    text = text.rstrip() + badge + "\n"
    LAYOUT.write_text(text)


def main() -> None:
    transform_view_model()
    transform_layout()


if __name__ == "__main__":
    main()
