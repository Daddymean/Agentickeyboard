from pathlib import Path

vm_path = Path('app/src/main/java/io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt')
layout_path = Path('app/src/main/java/io/github/daddymean/agentickeyboard/ui/AgenticKeyboardLayout.kt')
main_path = Path('app/src/main/java/io/github/daddymean/agentickeyboard/MainActivity.kt')


def one(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise RuntimeError(f'{label}: expected 1 match, found {n}')
    return text.replace(old, new, 1)


def between(text, start, end, new, label):
    a = text.find(start)
    b = text.find(end, a + 1)
    if a < 0 or b < 0:
        raise RuntimeError(f'{label}: boundary missing')
    return text[:a] + new + text[b:]


vm = vm_path.read_text()
vm = vm.replace('import io.github.daddymean.agentickeyboard.network.SuggestionsResponse\n', '')
vm = between(vm, '    // AI states\n', '    // Settings-backed state', '''    // Exactly one AI panel can be active at a time.
    private val _aiPanelState = MutableStateFlow<AiPanelState>(AiPanelState.Idle)
    val aiPanelState = _aiPanelState.asStateFlow()

    // Debounced background grammar check result (opt-in; see isProofreadEnabled)
    private val _proofreadHint = MutableStateFlow<GrammarCorrectionResponse?>(null)
    val proofreadHint = _proofreadHint.asStateFlow()

''', 'state block')
vm = between(vm, '    fun refineResult(adjustment: String) {', '    private fun launchAi', '''    fun refineResult(adjustment: String) {
        val current = _aiPanelState.value.refinableText ?: return
        val instruction = RESULT_REFINEMENTS[adjustment] ?: adjustment
        dismissResults()
        rewriteWithStyle(current, instruction, bypassCache = true)
    }

''', 'refine')
vm = between(vm, '    private fun launchAi', '    /**\n     * Clears every pending AI result panel', '''    private fun launchAi(block: suspend () -> Unit) {
        val previous = aiJob
        aiJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            _aiPanelState.value = AiPanelState.Loading
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A missed action error must not crash the IME process.
            } finally {
                if (_aiPanelState.value == AiPanelState.Loading) {
                    _aiPanelState.value = AiPanelState.Idle
                }
            }
        }
    }

''', 'launch')
vm = between(vm, '    /**\n     * Clears every pending AI result panel', '    fun setInputText', '''    /** Clears the active AI panel and any armed send warning. */
    fun dismissResults() {
        _aiPanelState.value = AiPanelState.Idle
        _sendGuardWarning.value = null
    }

''', 'dismiss')
vm = one(vm, '''    fun promoteProofreadHint() {
        _proofreadHint.value?.let {
            dismissResults()
            _grammarCorrection.value = it
            _proofreadHint.value = null
        }
    }
''', '''    fun promoteProofreadHint() {
        _proofreadHint.value?.let {
            dismissResults()
            _aiPanelState.value = AiPanelState.Grammar(it)
            _proofreadHint.value = null
        }
    }
''', 'proofread promotion')

for old in (
    '            _grammarCorrection.value = null\n',
    '            _suggestions.value = emptyList()\n',
    '            _summary.value = null\n',
    '            _translation.value = null\n',
    '            _composeResult.value = null\n',
    '            _explanation.value = null\n',
    '            _continuation.value = null\n',
    '            _toneAnalysis.value = null\n',
    '        _aiResultSource.value = text\n',
    '        _aiResultSource.value = null\n',
):
    vm = vm.replace(old, '')
if vm.count('            _rewrite.value = null\n') != 2:
    raise RuntimeError('rewrite clears changed')
vm = vm.replace('            _rewrite.value = null\n', '')

vm = one(vm, '                _grammarCorrection.value = result\n', '                _aiPanelState.value = AiPanelState.Grammar(result)\n', 'grammar result')
vm = one(vm, '                _grammarCorrection.value = GrammarCorrectionResponse(text, text, "Error: ${e.localizedMessage}", 0)\n', '                _aiPanelState.value = AiPanelState.Grammar(\n                    GrammarCorrectionResponse(text, text, "Error: ${e.localizedMessage}", 0)\n                )\n', 'grammar error')
vm = one(vm, '                _suggestions.value = result.suggestions\n', '                _aiPanelState.value = AiPanelState.Replies(result.suggestions)\n', 'reply result')
vm = one(vm, '''                _suggestions.value = ReplyIntents.offlineReplies(intent)
                    ?: listOf("Sounds good!", "Sure thing", "Let me check.")
''', '''                _aiPanelState.value = AiPanelState.Replies(
                    ReplyIntents.offlineReplies(intent)
                        ?: listOf("Sounds good!", "Sure thing", "Let me check.")
                )
''', 'reply fallback')
vm = one(vm, '''        dismissResults()
        _replyIntentContext.value = contextMessage
''', '''        dismissResults()
        _aiPanelState.value = AiPanelState.ReplyIntent(contextMessage)
''', 'reply intent request')
vm = one(vm, '''        val contextMessage = _replyIntentContext.value ?: return
        _replyIntentContext.value = null
''', '''        val contextMessage = (_aiPanelState.value as? AiPanelState.ReplyIntent)?.contextMessage ?: return
''', 'reply intent choice')
vm = one(vm, '                _summary.value = result\n', '                _aiPanelState.value = AiPanelState.Summary(result, text)\n', 'summary result')
vm = one(vm, '                _summary.value = "Failed to summarize text: ${e.localizedMessage}"\n', '                _aiPanelState.value = AiPanelState.Summary(\n                    "Failed to summarize text: ${e.localizedMessage}", text\n                )\n', 'summary error')
vm = one(vm, '                _translation.value = result\n', '                _aiPanelState.value = AiPanelState.Translation(result, text)\n', 'translation result')
vm = one(vm, '                _translation.value = "Translation error: ${e.localizedMessage}"\n', '                _aiPanelState.value = AiPanelState.Translation(\n                    "Translation error: ${e.localizedMessage}", text\n                )\n', 'translation error')
vm = one(vm, '                _rewrite.value = result\n', '                _aiPanelState.value = AiPanelState.Rewrite(result, text, targetTone)\n', 'persona rewrite result')
vm = one(vm, '                _rewrite.value = "Rewrite error: ${e.localizedMessage}"\n', '                _aiPanelState.value = AiPanelState.Rewrite(\n                    "Rewrite error: ${e.localizedMessage}", text, targetTone\n                )\n', 'persona rewrite error')
vm = one(vm, '                _rewrite.value = result\n', '                _aiPanelState.value = AiPanelState.Rewrite(result, text, styleInstruction)\n', 'style rewrite result')
vm = one(vm, '                _rewrite.value = "Rewrite error: ${e.localizedMessage}"\n', '                _aiPanelState.value = AiPanelState.Rewrite(\n                    "Rewrite error: ${e.localizedMessage}", text, styleInstruction\n                )\n', 'style rewrite error')
vm = one(vm, '                _composeResult.value = result\n', '                _aiPanelState.value = AiPanelState.Compose(result)\n', 'compose result')
vm = one(vm, '                _composeResult.value = "Compose error: ${e.localizedMessage}"\n', '                _aiPanelState.value = AiPanelState.Compose("Compose error: ${e.localizedMessage}")\n', 'compose error')
vm = one(vm, '                _explanation.value = result\n', '                _aiPanelState.value = AiPanelState.Explanation(result)\n', 'explanation result')
vm = one(vm, '                _explanation.value = "Explanation error: ${e.localizedMessage}"\n', '                _aiPanelState.value = AiPanelState.Explanation("Explanation error: ${e.localizedMessage}")\n', 'explanation error')
vm = one(vm, '                _continuation.value = result.takeIf { it.isNotBlank() }\n', '''                _aiPanelState.value = result.takeIf { it.isNotBlank() }
                    ?.let(AiPanelState::Continuation)
                    ?: AiPanelState.Idle
''', 'continuation result')
vm = one(vm, '                _continuation.value = null\n', '                _aiPanelState.value = AiPanelState.Idle\n', 'continuation error')
vm = one(vm, '                _toneAnalysis.value = result\n', '                _aiPanelState.value = AiPanelState.Tone(result)\n', 'tone result')
vm = one(vm, '                _toneAnalysis.value = ToneAnalysisResponse("Neutral", 0.5f, listOf("Error during analysis."))\n', '                _aiPanelState.value = AiPanelState.Tone(\n                    ToneAnalysisResponse("Neutral", 0.5f, listOf("Error during analysis."))\n                )\n', 'tone error')

for forbidden in ('_grammarCorrection', '_toneAnalysis', '_summary', '_translation', '_rewrite', '_composeResult', '_explanation', '_continuation', '_suggestions', '_replyIntentContext', '_aiResultSource', '_isLoading'):
    if forbidden in vm:
        raise RuntimeError(f'legacy state remains: {forbidden}')
vm_path.write_text(vm)

layout = layout_path.read_text()
layout = one(layout, '''    val isLoading by viewModel.isLoading.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val grammarCorrection by viewModel.grammarCorrection.collectAsState()
    val toneAnalysis by viewModel.toneAnalysis.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val translation by viewModel.translation.collectAsState()
    val rewrite by viewModel.rewrite.collectAsState()
    val composeResult by viewModel.composeResult.collectAsState()
    val explanation by viewModel.explanation.collectAsState()
    val continuation by viewModel.continuation.collectAsState()
    val aiResultSource by viewModel.aiResultSource.collectAsState()
''', '''    val aiPanelState by viewModel.aiPanelState.collectAsState()
    val isLoading = aiPanelState == AiPanelState.Loading
    val suggestions = (aiPanelState as? AiPanelState.Replies)?.suggestions.orEmpty()
    val grammarCorrection = (aiPanelState as? AiPanelState.Grammar)?.result
    val toneAnalysis = (aiPanelState as? AiPanelState.Tone)?.result
    val summary = (aiPanelState as? AiPanelState.Summary)?.text
    val translation = (aiPanelState as? AiPanelState.Translation)?.text
    val rewriteState = aiPanelState as? AiPanelState.Rewrite
    val rewrite = rewriteState?.text
    val composeResult = (aiPanelState as? AiPanelState.Compose)?.text
    val explanation = (aiPanelState as? AiPanelState.Explanation)?.text
    val continuation = (aiPanelState as? AiPanelState.Continuation)?.text
    val aiResultSource = aiPanelState.sourceText
    val replyIntentContext = (aiPanelState as? AiPanelState.ReplyIntent)?.contextMessage
''', 'keyboard collections')
layout = one(layout, '    val replyIntentContext by viewModel.replyIntentContext.collectAsState()\n', '', 'old reply collection')
layout = one(layout, 'label = "Rewritten (${viewModel.effectivePersona()}):",', 'label = "Rewritten (${rewriteState?.styleLabel ?: viewModel.effectivePersona()}):",', 'rewrite label')
layout_path.write_text(layout)

main = main_path.read_text()
main = one(main, 'import io.github.daddymean.agentickeyboard.ui.AgenticKeyboardLayout\n', 'import io.github.daddymean.agentickeyboard.ui.AgenticKeyboardLayout\nimport io.github.daddymean.agentickeyboard.ui.AiPanelState\n', 'activity import')
main = one(main, '''    val grammarCorrection by viewModel.grammarCorrection.collectAsState()
    val toneAnalysis by viewModel.toneAnalysis.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val translation by viewModel.translation.collectAsState()
''', '''    val aiPanelState by viewModel.aiPanelState.collectAsState()
    val grammarCorrection = (aiPanelState as? AiPanelState.Grammar)?.result
    val toneAnalysis = (aiPanelState as? AiPanelState.Tone)?.result
    val summary = (aiPanelState as? AiPanelState.Summary)?.text
    val translation = (aiPanelState as? AiPanelState.Translation)?.text
''', 'activity collections')
main_path.write_text(main)
