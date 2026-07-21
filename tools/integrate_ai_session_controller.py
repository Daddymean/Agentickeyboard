from pathlib import Path

path = Path("app/src/main/java/io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt")
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import kotlinx.coroutines.cancelAndJoin\n",
    "",
    "obsolete cancelAndJoin import",
)

replace_once(
    """    // Exactly one AI panel can be active at a time.\n    private val _aiPanelState = MutableStateFlow<AiPanelState>(AiPanelState.Idle)\n    val aiPanelState = _aiPanelState.asStateFlow()\n""",
    """    // Exactly one AI panel can be active at a time; the controller owns\n    // foreground request lifecycle and the backing result state.\n    private val aiSession = AiSessionController(viewModelScope)\n    private val _aiPanelState = aiSession.mutablePanelState\n    val aiPanelState = aiSession.panelState\n""",
    "AI panel state ownership",
)

replace_once(
    "    private var aiJob: Job? = null\n",
    "",
    "duplicate AI job",
)

replace_once(
    """    override fun onCleared() {\n        settings?.unregisterListener(prefsListener)\n        super.onCleared()\n    }\n""",
    """    override fun onCleared() {\n        aiSession.cancel()\n        settings?.unregisterListener(prefsListener)\n        super.onCleared()\n    }\n""",
    "ViewModel teardown",
)

start = text.index("    /**\n     * Cancels any in-flight AI request")
end = text.index("    fun setInputText(text: String) {", start)
replacement = """    // Re-runs the most recent AI action with the response cache bypassed, so the\n    // ↻ button on a result panel always produces a fresh variant.\n    fun regenerate() {\n        aiSession.regenerate()\n    }\n\n    /**\n     * Iterate on the currently shown text result (Shorter/Longer/Warmer/...):\n     * clears the panels and rewrites the result text with the chip's instruction.\n     */\n    fun refineResult(adjustment: String) {\n        val current = aiSession.currentState.refinableText ?: return\n        val instruction = RESULT_REFINEMENTS[adjustment] ?: adjustment\n        dismissResults()\n        rewriteWithStyle(current, instruction, bypassCache = true)\n    }\n\n    private fun launchAi(block: suspend () -> Unit) {\n        aiSession.launch { block() }\n    }\n\n    /** Clears the active AI panel and any armed send warning. */\n    fun dismissResults() {\n        aiSession.clear()\n        _sendGuardWarning.value = null\n    }\n\n"""
text = text[:start] + replacement + text[end:]

assignment_count = text.count("regenerateAction = {")
if assignment_count < 5:
    raise RuntimeError(f"expected several regenerate assignments, found {assignment_count}")
text = text.replace("regenerateAction = {", "aiSession.setRegenerateAction {")

if "regenerateAction" in text:
    raise RuntimeError("legacy regenerateAction reference remains")
if "aiJob" in text:
    raise RuntimeError("legacy aiJob reference remains")
if "cancelAndJoin" in text:
    raise RuntimeError("legacy cancelAndJoin reference remains")
if text.count("private fun launchAi") != 1:
    raise RuntimeError("launchAi bridge count changed")
if text.count("private val aiSession = AiSessionController(viewModelScope)") != 1:
    raise RuntimeError("AiSessionController binding missing")

path.write_text(text)
