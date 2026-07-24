package io.github.daddymean.agentickeyboard.ui

import android.content.SharedPreferences
import android.text.InputType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog
import io.github.daddymean.agentickeyboard.network.GeminiManager
import io.github.daddymean.agentickeyboard.network.GrammarCorrectionResponse
import io.github.daddymean.agentickeyboard.network.ToneAnalysisResponse
import io.github.daddymean.agentickeyboard.util.CommandPalette
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.mastery.KeyboardMastery
import io.github.daddymean.agentickeyboard.util.mastery.MasteryEvent
import io.github.daddymean.agentickeyboard.util.mastery.MasteryState
import io.github.daddymean.agentickeyboard.util.mastery.MasteryStateCodec
import io.github.daddymean.agentickeyboard.util.PersonalModelSerializer
import io.github.daddymean.agentickeyboard.util.ReplyIntents
import io.github.daddymean.agentickeyboard.util.SendGuard
import io.github.daddymean.agentickeyboard.util.VoiceMatchScorer
import io.github.daddymean.agentickeyboard.util.VoiceSample
import io.github.daddymean.agentickeyboard.util.VoiceVocabulary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a committed word should become, and whether a learned typo rule produced it. */
data class WordReplacement(val replacement: String, val fromLearnedRule: Boolean)

/** A just-applied auto-correction that backspace can revert. */
data class AutoCorrectionUndo(val original: String, val replacement: String, val fromLearnedRule: Boolean)

/** A just-applied AI result that backspace can revert to the original text. */
data class AiApplyUndo(val original: String, val replacement: String)

/** Local, on-device usage statistics shown in the Style Hub dashboard. */
data class UsageStats(
    val autoCorrections: Int = 0,
    val swipeWords: Int = 0,
    val aiApplies: Int = 0,
    val shortcutExpansions: Int = 0
)

/** Transient, local-only style match shown beside eligible AI writing results. */
data class VoiceMatchState(
    val percent: Int,
    val confidence: Int,
    val label: String,
    val signals: List<String>,
    val delta: Int? = null
)

class KeyboardViewModel(
    private val repository: KeyboardRepository,
    private val settings: KeyboardSettings? = null
) : ViewModel() {

    companion object {
        private val NON_ALPHA_REGEX = "[^a-zA-Z]".toRegex()
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        val PERSONAS = listOf("Match my history", "Professional", "Joyful", "Empathetic", "Casual")
        /** Keyboard palette override choices (see KeyboardSettings.themeOverride). */
        val THEME_MODES = listOf("System", "Light", "Dark")
        /** Iterate chips on AI result panels → rewrite instruction they apply. */
        val RESULT_REFINEMENTS = linkedMapOf(
            "Shorter" to "the same message, noticeably shorter and tighter",
            "Longer" to "the same message, expanded with a bit more detail",
            "Warmer" to "the same message with a warmer, friendlier feel",
            "Firmer" to "the same message with a firmer, more assertive edge",
            "More formal" to "the same message in a more formal register"
        )
        private val STOP_WORDS = setOf(
            "the", "and", "a", "of", "to", "in", "is", "that", "it", "for",
            "on", "with", "as", "at", "by", "an", "be", "this", "are", "from"
        )
        private const val DAY_MS = 86_400_000L
    }

    // Shortcuts and logs from local Room DB.
    // NOTE: shortcuts, logs, topVocabulary, and learnedCorrections are read
    // imperatively (.value) from event handlers (word commit, AI context,
    // persona-from-history). The keyboard UI never collects most of them, so
    // under WhileSubscribed their upstream never started and .value stayed an
    // empty list forever — shortcut expansion, learned-correction replacement,
    // and "Match my history" were silently dead in the IME process. They must
    // be shared Eagerly; all are small, bounded queries.
    val shortcuts: StateFlow<List<ShortcutTemplate>> = repository.allShortcuts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val logs: StateFlow<List<WritingLog>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // User-defined "/token" commands extending the built-in command palette;
    // collected by the UI, so subscription-scoped sharing is fine here.
    val customCommands: StateFlow<List<CustomCommand>> = repository.allCustomCommands
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // App→persona mappings, learned automatically as the user picks a persona in
    // each app and surfaced in the Style Hub for review/override. UI-collected only.
    val appPersonas: StateFlow<List<AppPersona>> = repository.allAppPersonas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // On-device Personalization state flows
    val topVocabulary: StateFlow<List<UserVocabulary>> = repository.topVocabulary
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val learnedCorrections: StateFlow<List<LearnedCorrection>> = repository.allCorrections
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Selected style persona preference (Match history, Professional, Joyful, Empathetic, Casual)
    private val _userPersonaPreference = MutableStateFlow(settings?.persona ?: "Match my history")
    val userPersonaPreference = _userPersonaPreference.asStateFlow()

    // Real-time predictive autocomplete completions
    private val _predictiveSuggestions = MutableStateFlow<List<String>>(emptyList())
    val predictiveSuggestions = _predictiveSuggestions.asStateFlow()

    // Active input text state
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    // True while the editor has a non-blank selection; mirrored by the IME
    // service from onUpdateSelection so the AI action row can show that
    // actions will operate on the selection instead of the whole draft.
    private val _hasSelection = MutableStateFlow(false)
    val hasSelection = _hasSelection.asStateFlow()

    fun setSelectionActive(active: Boolean) {
        _hasSelection.value = active
    }

    // Exactly one AI panel can be active at a time; the controller owns
    // foreground request lifecycle and the backing result state.
    private val aiSession = AiSessionController(viewModelScope)
    private val _aiPanelState = aiSession.mutablePanelState
    val aiPanelState = aiSession.panelState

    private val _voiceMatch = MutableStateFlow<VoiceMatchState?>(null)
    val voiceMatch = _voiceMatch.asStateFlow()
    private var pendingVoiceBaseline: Int? = null

    // Debounced background grammar check result (opt-in; see isProofreadEnabled)
    private val _proofreadHint = MutableStateFlow<GrammarCorrectionResponse?>(null)
    val proofreadHint = _proofreadHint.asStateFlow()

    // Settings-backed state (persisted when a KeyboardSettings store is provided)
    private val _isOfflineMode = MutableStateFlow(settings?.isOfflineMode ?: false)
    val isOfflineMode = _isOfflineMode.asStateFlow()

    private val _isSwipeEnabled = MutableStateFlow(settings?.isSwipeEnabled ?: true)
    val isSwipeEnabled = _isSwipeEnabled.asStateFlow()

    private val _isAutoCapitalizeEnabled = MutableStateFlow(settings?.isAutoCapitalizeEnabled ?: true)
    val isAutoCapitalizeEnabled = _isAutoCapitalizeEnabled.asStateFlow()

    private val _isNumberRowEnabled = MutableStateFlow(settings?.isNumberRowEnabled ?: false)
    val isNumberRowEnabled = _isNumberRowEnabled.asStateFlow()

    private val _isProofreadEnabled = MutableStateFlow(settings?.isProofreadEnabled ?: false)
    val isProofreadEnabled = _isProofreadEnabled.asStateFlow()

    private val _isLearningPaused = MutableStateFlow(settings?.isLearningPaused ?: false)
    val isLearningPaused = _isLearningPaused.asStateFlow()

    private val _isHapticsEnabled = MutableStateFlow(settings?.isHapticsEnabled ?: true)
    val isHapticsEnabled = _isHapticsEnabled.asStateFlow()

    private val _isVoiceLockEnabled = MutableStateFlow(settings?.isVoiceLockEnabled ?: false)
    val isVoiceLockEnabled = _isVoiceLockEnabled.asStateFlow()

    private val _isSendGuardEnabled = MutableStateFlow(settings?.isSendGuardEnabled ?: false)
    val isSendGuardEnabled = _isSendGuardEnabled.asStateFlow()

    private val _themeOverride = MutableStateFlow(settings?.themeOverride ?: "System")
    val themeOverride = _themeOverride.asStateFlow()

    // Draft that armed the send-guard; non-null while "Send anyway?" is shown.
    private val _sendGuardWarning = MutableStateFlow<String?>(null)
    val sendGuardWarning = _sendGuardWarning.asStateFlow()

    private val _sourceLanguage = MutableStateFlow(settings?.sourceLanguage ?: "English")
    val sourceLanguage = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(settings?.targetLanguage ?: "Spanish")
    val targetLanguage = _targetLanguage.asStateFlow()

    // Secure/password fields: AI features and learning are suppressed entirely
    private val _isSensitiveField = MutableStateFlow(false)
    val isSensitiveField = _isSensitiveField.asStateFlow()

    private val _usageStats = MutableStateFlow(
        UsageStats(
            autoCorrections = settings?.statAutoCorrections ?: 0,
            swipeWords = settings?.statSwipeWords ?: 0,
            aiApplies = settings?.statAiApplies ?: 0,
            shortcutExpansions = settings?.statShortcutExpansions ?: 0
        )
    )
    val usageStats = _usageStats.asStateFlow()

    private val _isMasteryEnabled = MutableStateFlow(settings?.isMasteryEnabled ?: true)
    val isMasteryEnabled = _isMasteryEnabled.asStateFlow()

    private val initialMasteryState = MasteryStateCodec.decode(
        settings?.masteryState,
        enabledFallback = _isMasteryEnabled.value
    ).copy(enabled = _isMasteryEnabled.value)
    private val _masteryState = MutableStateFlow(initialMasteryState)
    val masteryState = _masteryState.asStateFlow()

    private var activeAppPackage: String? = null
    private var activeAppLabel: String = ""
    private var previousCommittedWord: String? = null
    private var pendingUndo: AutoCorrectionUndo? = null
    private var pendingAiUndo: AiApplyUndo? = null
    private val correctionReverts = mutableMapOf<String, Int>()
    private var proofreadJob: Job? = null
    private var predictionJob: Job? = null

    // Last text pushed through setInputText; null until the first sync so the
    // initial (possibly empty) editor state still seeds predictions.
    private var lastSyncedText: String? = null

    // Keeps this ViewModel in sync when the other component (companion app vs IME
    // service) changes a shared setting — they hold separate ViewModel instances.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val s = settings ?: return@OnSharedPreferenceChangeListener
        when (key) {
            KeyboardSettings.KEY_OFFLINE_MODE -> _isOfflineMode.value = s.isOfflineMode
            KeyboardSettings.KEY_SWIPE_ENABLED -> _isSwipeEnabled.value = s.isSwipeEnabled
            KeyboardSettings.KEY_AUTO_CAPITALIZE -> _isAutoCapitalizeEnabled.value = s.isAutoCapitalizeEnabled
            KeyboardSettings.KEY_NUMBER_ROW -> _isNumberRowEnabled.value = s.isNumberRowEnabled
            KeyboardSettings.KEY_PROOFREAD -> _isProofreadEnabled.value = s.isProofreadEnabled
            KeyboardSettings.KEY_LEARNING_PAUSED -> _isLearningPaused.value = s.isLearningPaused
            KeyboardSettings.KEY_HAPTICS -> _isHapticsEnabled.value = s.isHapticsEnabled
            KeyboardSettings.KEY_VOICE_LOCK -> _isVoiceLockEnabled.value = s.isVoiceLockEnabled
            KeyboardSettings.KEY_SEND_GUARD -> _isSendGuardEnabled.value = s.isSendGuardEnabled
            KeyboardSettings.KEY_THEME_OVERRIDE -> _themeOverride.value = s.themeOverride
            KeyboardSettings.KEY_PERSONA -> _userPersonaPreference.value = s.persona
            KeyboardSettings.KEY_SOURCE_LANG -> _sourceLanguage.value = s.sourceLanguage
            KeyboardSettings.KEY_TARGET_LANG -> _targetLanguage.value = s.targetLanguage
            KeyboardSettings.KEY_MASTERY_ENABLED -> {
                _isMasteryEnabled.value = s.isMasteryEnabled
                _masteryState.value = _masteryState.value.copy(enabled = s.isMasteryEnabled)
            }
            KeyboardSettings.KEY_MASTERY_STATE -> {
                _masteryState.value = MasteryStateCodec.decode(
                    s.masteryState, enabledFallback = s.isMasteryEnabled
                ).copy(enabled = s.isMasteryEnabled)
            }
        }
    }

    init {
        settings?.registerListener(prefsListener)

        // Seed defaults exactly once when the database is empty. Using first()
        // instead of collect{} means the "Clear" actions in the Style Hub are not
        // immediately undone by a re-seeding collector.
        viewModelScope.launch {
            if (repository.allShortcuts.first().isEmpty()) {
                repository.insertShortcut(ShortcutTemplate(shortcut = "omw", template = "On my way! Running late."))
                repository.insertShortcut(ShortcutTemplate(shortcut = "gr", template = "Great! Talk to you soon."))
                repository.insertShortcut(ShortcutTemplate(shortcut = "ty", template = "Thank you so much! Really appreciate it."))
                repository.insertShortcut(ShortcutTemplate(shortcut = "brb", template = "Be right back! Just in a quick meeting."))
            }
        }

        viewModelScope.launch {
            if (repository.allCorrections.first().isEmpty()) {
                repository.insertCorrection(LearnedCorrection(typo = "teh", correction = "the", count = 15))
                repository.insertCorrection(LearnedCorrection(typo = "tomorow", correction = "tomorrow", count = 8))
                repository.insertCorrection(LearnedCorrection(typo = "definately", correction = "definitely", count = 5))
            }
        }

        // Data retention: expire old writing logs
        viewModelScope.launch {
            val days = settings?.logRetentionDays ?: 30
            if (days > 0) {
                repository.deleteLogsOlderThan(System.currentTimeMillis() - days * DAY_MS)
            }
        }
    }

    override fun onCleared() {
        aiSession.cancel()
        settings?.unregisterListener(prefsListener)
        super.onCleared()
    }

    // Re-runs the most recent AI action with the response cache bypassed, so the
    // ↻ button on a result panel always produces a fresh variant.
    fun regenerate() {
        aiSession.regenerate()
    }

    /**
     * Iterate on the currently shown text result (Shorter/Longer/Warmer/...):
     * clears the panels and rewrites the result text with the chip's instruction.
     */
    fun refineResult(adjustment: String) {
        val current = aiSession.currentState.refinableText ?: return
        val instruction = RESULT_REFINEMENTS[adjustment] ?: adjustment
        val baseline = _voiceMatch.value?.percent
        recordMastery(MasteryEvent.REFINEMENT)
        dismissResults()
        pendingVoiceBaseline = baseline
        rewriteWithStyle(current, instruction, bypassCache = true)
    }

    private fun launchAi(block: suspend () -> Unit) {
        _voiceMatch.value = null
        aiSession.launch { block() }
    }

    /** Clears the active AI panel and any armed send warning. */
    fun dismissResults() {
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

    fun setInputText(text: String) {
        // onUpdateSelection also fires for pure cursor moves; predictions and
        // the proofread debounce only depend on the text, so skip the downstream
        // work (and its coroutine churn) when nothing changed.
        if (text == lastSyncedText) return
        lastSyncedText = text
        _inputText.value = text
        updatePredictiveSuggestions(text)
        scheduleProofread(text)
    }

    /**
     * Called by the IME service whenever a new editor gains focus: detects
     * password/secure fields and restores the persona last used in this app.
     */
    fun onEditorStarted(packageName: String?, appLabel: String = "", inputType: Int) {
        _isSensitiveField.value = isPasswordInputType(inputType)
        activeAppPackage = packageName
        activeAppLabel = appLabel
        previousCommittedWord = null
        pendingUndo = null
        _proofreadHint.value = null
        if (packageName != null && !_isSensitiveField.value) {
            viewModelScope.launch {
                repository.getAppPersona(packageName)?.let { stored ->
                    _userPersonaPreference.value = stored
                }
            }
        }
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    // --- Settings setters (persisted when a settings store is attached) ---

    fun setLanguages(source: String, target: String) {
        _sourceLanguage.value = source
        _targetLanguage.value = target
        settings?.sourceLanguage = source
        settings?.targetLanguage = target
    }

    fun toggleOfflineMode() {
        val newValue = !_isOfflineMode.value
        _isOfflineMode.value = newValue
        settings?.isOfflineMode = newValue
    }

    fun setSwipeEnabled(enabled: Boolean) {
        _isSwipeEnabled.value = enabled
        settings?.isSwipeEnabled = enabled
    }

    fun setAutoCapitalizeEnabled(enabled: Boolean) {
        _isAutoCapitalizeEnabled.value = enabled
        settings?.isAutoCapitalizeEnabled = enabled
    }

    fun setNumberRowEnabled(enabled: Boolean) {
        _isNumberRowEnabled.value = enabled
        settings?.isNumberRowEnabled = enabled
    }

    fun setProofreadEnabled(enabled: Boolean) {
        _isProofreadEnabled.value = enabled
        settings?.isProofreadEnabled = enabled
        if (!enabled) {
            proofreadJob?.cancel()
            _proofreadHint.value = null
        }
    }

    fun setLearningPaused(paused: Boolean) {
        _isLearningPaused.value = paused
        settings?.isLearningPaused = paused
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _isHapticsEnabled.value = enabled
        settings?.isHapticsEnabled = enabled
    }

    fun setVoiceLockEnabled(enabled: Boolean) {
        _isVoiceLockEnabled.value = enabled
        settings?.isVoiceLockEnabled = enabled
    }

    fun setThemeOverride(mode: String) {
        _themeOverride.value = mode
        settings?.themeOverride = mode
    }

    fun setSendGuardEnabled(enabled: Boolean) {
        _isSendGuardEnabled.value = enabled
        settings?.isSendGuardEnabled = enabled
        if (!enabled) _sendGuardWarning.value = null
    }

    /**
     * Called by the IME right before a Send editor action fires. Returns true
     * when the action should be held back: the first Enter on a hostile-reading
     * draft arms an inline "Send anyway?" warning; the next Enter (or the
     * shelf's Send button, which re-triggers the action) goes through.
     */
    fun interceptSend(draft: String): Boolean {
        if (_sendGuardWarning.value != null) {
            // Second Enter while the warning is showing: let the send happen.
            _sendGuardWarning.value = null
            return false
        }
        if (!_isSendGuardEnabled.value || _isSensitiveField.value || draft.isBlank()) return false
        if (!SendGuard.shouldWarn(draft)) return false
        _sendGuardWarning.value = draft
        return true
    }

    fun dismissSendGuardWarning() {
        _sendGuardWarning.value = null
    }

    fun setLogRetentionDays(days: Int) {
        settings?.logRetentionDays = days
    }

    fun getLogRetentionDays(): Int = settings?.logRetentionDays ?: 30

    fun setMasteryEnabled(enabled: Boolean) {
        _isMasteryEnabled.value = enabled
        settings?.isMasteryEnabled = enabled
        val updated = _masteryState.value.copy(enabled = enabled)
        _masteryState.value = updated
        settings?.masteryState = MasteryStateCodec.encode(updated)
    }

    fun resetMasteryProgress() {
        val reset = MasteryState.fresh(enabled = _isMasteryEnabled.value)
        _masteryState.value = reset
        settings?.masteryState = MasteryStateCodec.encode(reset)
    }

    fun setUserPersonaPreference(persona: String) {
        _userPersonaPreference.value = persona
        settings?.persona = persona
        activeAppPackage?.let { pkg ->
            viewModelScope.launch { repository.setAppPersona(pkg, persona, activeAppLabel) }
        }
    }

    /** Style Hub: change the stored persona for an app that isn't the active one. */
    fun setAppPersonaOverride(packageName: String, appLabel: String, persona: String) {
        viewModelScope.launch { repository.setAppPersona(packageName, persona, appLabel) }
        if (packageName == activeAppPackage) _userPersonaPreference.value = persona
    }

    /** Style Hub: forget the per-app persona mapping for [packageName]. */
    fun removeAppPersona(packageName: String) {
        viewModelScope.launch { repository.deleteAppPersona(packageName) }
    }

    /** Advances to the next persona (used by long-pressing Rewrite on the keyboard). */
    fun cyclePersona(): String {
        val current = _userPersonaPreference.value
        val index = PERSONAS.indexOf(current)
        val next = PERSONAS[(index + 1).mod(PERSONAS.size)]
        setUserPersonaPreference(next)
        return next
    }

    /**
     * The persona the AI should write as: the explicit user choice, or the dominant
     * tone observed in the writing history when set to "Match my history".
     */
    fun effectivePersona(): String {
        val personaPref = _userPersonaPreference.value
        if (personaPref != "Match my history") return personaPref
        return logs.value
            .filter { it.sentiment != "Corrected" && it.sentiment.isNotEmpty() }
            .groupBy { it.sentiment }
            .maxByOrNull { it.value.size }?.key ?: "Casual"
    }

    /**
     * Compute dynamic personalization context to inject into AI requests
     */
    fun getPersonalizationContext(): String {
        val vocabularyList = topVocabulary.value.take(5).joinToString(", ") { it.word }
        val selectedPersona = effectivePersona()

        return buildString {
            append("User selected style persona: $selectedPersona. ")
            if (vocabularyList.isNotEmpty()) {
                append("User frequently uses these personalized keywords: [$vocabularyList]. ")
            }
            append("Make recommendations and response suggestions match this vocabulary and tone.")
        }
    }

    private fun isLearningAllowed(): Boolean =
        !_isLearningPaused.value && !_isSensitiveField.value

    /**
     * Parse word usage and count them on-device
     */
    fun recordWordUsage(text: String) {
        if (text.isBlank() || !isLearningAllowed()) return
        viewModelScope.launch {
            val words = text.split(WHITESPACE_REGEX)
            for (w in words) {
                val cleaned = w.replace(NON_ALPHA_REGEX, "").lowercase().trim()
                if (cleaned.length > 2 && !STOP_WORDS.contains(cleaned)) {
                    repository.recordWordUsage(cleaned)
                }
            }
        }
    }

    /**
     * Called when the user commits a single word with the space bar or a swipe.
     * Feeds vocabulary learning and the bigram model for next-word prediction.
     */
    fun onWordCommitted(rawWord: String) {
        if (!isLearningAllowed()) {
            previousCommittedWord = null
            return
        }
        val cleaned = rawWord.replace(NON_ALPHA_REGEX, "").lowercase()
        val previous = previousCommittedWord
        previousCommittedWord = if (cleaned.length in 2..24) cleaned else null
        viewModelScope.launch {
            try {
                if (cleaned.length > 2 && !STOP_WORDS.contains(cleaned)) {
                    repository.recordWordUsage(cleaned)
                }
                // Stop words stay in bigrams: pairs like "on my" carry the signal
                if (previous != null && cleaned.length >= 2) {
                    repository.recordBigram(previous, cleaned)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Learning is best-effort; a storage error must not crash typing.
            }
        }
    }

    /**
     * Resolves what a just-typed word should be replaced with when the user commits
     * it (presses space): a shortcut template expansion first, then a learned
     * spelling auto-correction. Returns null when the word should stand as typed.
     */
    fun resolveWordCommit(word: String): WordReplacement? {
        val normalized = word.lowercase().trim()
        if (normalized.isEmpty()) return null
        shortcuts.value.find { it.shortcut == normalized }?.let {
            return WordReplacement(it.template, fromLearnedRule = false)
        }
        learnedCorrections.value.find { it.typo == normalized }?.let { correction ->
            // Preserve leading capitalization of the typed word
            val replacement = if (word.firstOrNull()?.isUpperCase() == true) {
                correction.correction.replaceFirstChar { it.uppercase() }
            } else {
                correction.correction
            }
            return WordReplacement(replacement, fromLearnedRule = true)
        }
        return null
    }

    // --- Auto-correction undo -------------------------------------------------

    fun registerAutoCorrection(original: String, replacement: String, fromLearnedRule: Boolean) {
        pendingUndo = AutoCorrectionUndo(original, replacement, fromLearnedRule)
        pendingAiUndo = null
    }

    fun peekPendingUndo(): AutoCorrectionUndo? = pendingUndo

    /**
     * Marks the pending undo as applied. Reverting the same learned correction
     * twice deletes the rule — the user is telling us we got it wrong.
     */
    fun onUndoApplied() {
        val undo = pendingUndo ?: return
        pendingUndo = null
        if (!undo.fromLearnedRule) return
        val typo = undo.original.lowercase().trim()
        val reverts = (correctionReverts[typo] ?: 0) + 1
        correctionReverts[typo] = reverts
        if (reverts >= 2) {
            viewModelScope.launch {
                repository.getCorrectionForTypo(typo)?.let { repository.deleteCorrectionById(it.id) }
            }
        }
    }

    fun clearPendingUndo() {
        pendingUndo = null
    }

    // --- AI apply undo ---------------------------------------------------------

    /**
     * Remembers a just-applied AI result so backspace pressed right after can
     * restore [original]. Mirrors the auto-correction undo above but for whole
     * drafts/selections replaced through the result panels.
     */
    fun registerAiApply(original: String, replacement: String) {
        pendingUndo = null
        pendingAiUndo = AiApplyUndo(original, replacement).takeIf { original != replacement }
    }

    fun peekPendingAiUndo(): AiApplyUndo? = pendingAiUndo

    fun clearPendingAiUndo() {
        pendingAiUndo = null
    }

    // --- Usage statistics (local only) ----------------------------------------

    private fun updateStats(transform: (UsageStats) -> UsageStats) {
        val updated = transform(_usageStats.value)
        _usageStats.value = updated
        settings?.let {
            it.statAutoCorrections = updated.autoCorrections
            it.statSwipeWords = updated.swipeWords
            it.statAiApplies = updated.aiApplies
            it.statShortcutExpansions = updated.shortcutExpansions
        }
    }

    private fun recordMastery(event: MasteryEvent) {
        val award = KeyboardMastery.record(
            state = _masteryState.value,
            event = event,
            epochDay = System.currentTimeMillis() / DAY_MS,
            isSensitiveField = _isSensitiveField.value
        )
        if (award.state != _masteryState.value) {
            _masteryState.value = award.state
            settings?.masteryState = MasteryStateCodec.encode(award.state)
        }
    }

    fun recordAutoCorrectionStat() {
        updateStats { it.copy(autoCorrections = it.autoCorrections + 1) }
        recordMastery(MasteryEvent.AUTO_CORRECTION)
    }

    fun recordSwipeWordStat() {
        updateStats { it.copy(swipeWords = it.swipeWords + 1) }
        recordMastery(MasteryEvent.SWIPE_WORD)
    }

    fun recordAiApplyStat() {
        updateStats { it.copy(aiApplies = it.aiApplies + 1) }
        val event = when {
            _isOfflineMode.value -> MasteryEvent.OFFLINE_AI_APPLY
            aiSession.currentState is AiPanelState.Translation -> MasteryEvent.TRANSLATION_APPLY
            _isVoiceLockEnabled.value && aiSession.currentState is AiPanelState.Rewrite ->
                MasteryEvent.VOICE_LOCK_APPLY
            else -> MasteryEvent.AI_APPLY
        }
        recordMastery(event)
    }

    fun recordShortcutExpansionStat() {
        updateStats { it.copy(shortcutExpansions = it.shortcutExpansions + 1) }
        recordMastery(MasteryEvent.SHORTCUT_EXPANSION)
    }

    // --- Prediction ------------------------------------------------------------

    /**
     * Compute predictive options: next-word predictions from the bigram model when
     * a word was just committed, prefix completions from personal vocabulary while
     * a word is being typed, and frequent words as prompts when the field is empty.
     */
    fun updatePredictiveSuggestions(activeText: String) {
        // One in-flight computation at a time: a slow bigram lookup for an old
        // keystroke must not land after (and overwrite) a newer result.
        predictionJob?.cancel()
        predictionJob = viewModelScope.launch {
            when {
                activeText.isEmpty() -> {
                    val topWords = topVocabulary.value.take(3).map { it.word }
                    _predictiveSuggestions.value = if (topWords.isNotEmpty()) {
                        topWords.map { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
                    } else {
                        listOf("Hello", "Awesome", "Got it")
                    }
                }
                activeText.last().isWhitespace() -> {
                    val previous = activeText.trim().split(WHITESPACE_REGEX).lastOrNull()
                        ?.replace(NON_ALPHA_REGEX, "")?.lowercase().orEmpty()
                    _predictiveSuggestions.value = if (previous.length >= 2) {
                        repository.nextWords(previous, 3)
                    } else {
                        emptyList()
                    }
                }
                else -> {
                    val lastWord = activeText.split(WHITESPACE_REGEX).lastOrNull()?.lowercase() ?: ""
                    _predictiveSuggestions.value = if (lastWord.isNotEmpty()) {
                        topVocabulary.value
                            .filter { it.word.startsWith(lastWord) && it.word != lastWord }
                            .take(3)
                            .map { it.word }
                    } else {
                        emptyList()
                    }
                }
            }
        }
    }

    /**
     * Expand custom shortcuts in text based on templates
     */
    fun tryExpandAbbreviation(text: String): String {
        if (text.isEmpty()) return text
        val words = text.split(WHITESPACE_REGEX)
        if (words.isEmpty()) return text
        val lastWord = words.last().lowercase().trim()
        val match = shortcuts.value.find { it.shortcut == lastWord }
        return if (match != null) {
            val prefix = text.substring(0, text.length - words.last().length)
            prefix + match.template
        } else {
            text
        }
    }

    // --- Background proofread (opt-in) ------------------------------------------

    private fun scheduleProofread(text: String) {
        proofreadJob?.cancel()
        if (!_isProofreadEnabled.value || _isOfflineMode.value || _isSensitiveField.value) {
            _proofreadHint.value = null
            return
        }
        if (text.trim().length < 20) {
            _proofreadHint.value = null
            return
        }
        if (_proofreadHint.value?.original == text) return
        proofreadJob = viewModelScope.launch {
            delay(2500)
            try {
                val result = GeminiManager.fixGrammar(text, getPersonalizationContext())
                _proofreadHint.value = if (result.correctionsCount > 0 && result.corrected.isNotBlank() && result.corrected != text) {
                    result.copy(original = text)
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Background check: fail silently
            }
        }
    }

    /** Surfaces the background proofread result as an actionable correction. */
    fun promoteProofreadHint() {
        _proofreadHint.value?.let {
            dismissResults()
            publishAiPanel(AiPanelState.Grammar(it))
            _proofreadHint.value = null
        }
    }

    // --- AI actions --------------------------------------------------------------

    /**
     * Trigger grammar correction
     */
    fun fixGrammar(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { fixGrammar(text, bypassCache = true) }
        launchAi {
            try {
                // Incorporate personalization preferences inside grammar suggestions
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    GeminiManager.offlineGrammarFix(text)
                } else {
                    GeminiManager.fixGrammar(text, personalization, bypassCache)
                }
                publishAiPanel(AiPanelState.Grammar(result))

                // On-device learning: auto-extract spelling correction rules!
                if (isLearningAllowed()) {
                    extractAndLearnCorrections(text, result.corrected)
                    recordWordUsage(result.corrected)
                }

                // Save log for personalized model export
                repository.insertLog(
                    WritingLog(
                        originalText = text,
                        sentiment = "Corrected",
                        toneScore = 0.9f,
                        wordCount = text.split(WHITESPACE_REGEX).size
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishAiPanel(
                    AiPanelState.Grammar(
                        GrammarCorrectionResponse(text, text, "Error: ${e.localizedMessage}", 0)
                    )
                )
            }
        }
    }

    /**
     * Dynamic alignment of original and corrected text to extract custom spelling fixes
     */
    private suspend fun extractAndLearnCorrections(original: String, corrected: String) {
        val origWords = original.split(WHITESPACE_REGEX).map { it.replace(NON_ALPHA_REGEX, "").lowercase() }
        val corrWords = corrected.split(WHITESPACE_REGEX).map { it.replace(NON_ALPHA_REGEX, "").lowercase() }

        if (origWords.size != corrWords.size) return

        val toProcess = mutableListOf<Pair<String, String>>()
        for (i in origWords.indices) {
            val oWord = origWords[i]
            val cWord = corrWords[i]
            if (oWord.isNotEmpty() && cWord.isNotEmpty() && oWord != cWord && oWord.length > 2) {
                toProcess.add(oWord to cWord)
            }
        }

        if (toProcess.isEmpty()) return

        val typos = toProcess.map { it.first }
        val existingList = repository.getCorrectionsForTypos(typos)
        val existingMap = existingList.associateBy { it.typo }

        val toInsert = toProcess.map { (oWord, cWord) ->
            val existing = existingMap[oWord]
            if (existing != null) {
                existing.copy(correction = cWord, count = existing.count + 1)
            } else {
                LearnedCorrection(typo = oWord, correction = cWord)
            }
        }

        repository.insertCorrections(toInsert)
    }

    /**
     * Suggest quick replies (short, medium, and detailed variants)
     */
    fun suggestReplies(contextMessage: String, intent: String = "", bypassCache: Boolean = false) {
        if (contextMessage.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { suggestReplies(contextMessage, intent, bypassCache = true) }
        launchAi {
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    GeminiManager.offlineReplies(contextMessage, personalization, intent)
                } else {
                    GeminiManager.suggestReplies(contextMessage, personalization, intent, bypassCache)
                }
                _aiPanelState.value = AiPanelState.Replies(result.suggestions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiPanelState.value = AiPanelState.Replies(
                    ReplyIntents.offlineReplies(intent)
                        ?: listOf("Sounds good!", "Sure thing", "Let me check.")
                )
            }
        }
    }

    /**
     * First step of intent-directed replies: remember the message being replied
     * to and let the UI show intent chips (Accept/Decline/...) before generating.
     */
    fun requestReplyIdeas(contextMessage: String) {
        if (contextMessage.isBlank() || _isSensitiveField.value) return
        dismissResults()
        _aiPanelState.value = AiPanelState.ReplyIntent(contextMessage)
    }

    /** Second step: generate replies steered by [intent], or unsteered when null. */
    fun chooseReplyIntent(intent: String?) {
        val contextMessage = (_aiPanelState.value as? AiPanelState.ReplyIntent)?.contextMessage ?: return
        suggestReplies(contextMessage, intent ?: "")
    }

    /**
     * Summarize long text
     */
    fun summarizeMessage(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { summarizeMessage(text, bypassCache = true) }
        launchAi {
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    GeminiManager.offlineSummary(text)
                } else {
                    GeminiManager.summarizeMessage(text, personalization, bypassCache)
                }
                _aiPanelState.value = AiPanelState.Summary(result, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiPanelState.value = AiPanelState.Summary(
                    "Failed to summarize text: ${e.localizedMessage}", text
                )
            }
        }
    }

    /**
     * Translate text
     */
    fun translateText(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { translateText(text, bypassCache = true) }
        launchAi {
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    "[Offline] $text"
                } else {
                    GeminiManager.translateText(text, _sourceLanguage.value, _targetLanguage.value, personalization, bypassCache)
                }
                _aiPanelState.value = AiPanelState.Translation(result, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiPanelState.value = AiPanelState.Translation(
                    "Translation error: ${e.localizedMessage}", text
                )
            }
        }
    }

    /**
     * Rewrite text to match the selected style persona
     */
    fun rewriteTone(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { rewriteTone(text, bypassCache = true) }
        launchAi {
            try {
                val targetTone = effectivePersona()
                val result = if (_isOfflineMode.value) {
                    GeminiManager.offlineRewrite(text, targetTone)
                } else {
                    GeminiManager.rewriteWithTone(text, targetTone, getPersonalizationContext(), _isVoiceLockEnabled.value, bypassCache)
                }
                publishAiPanel(AiPanelState.Rewrite(result, text, targetTone))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishAiPanel(
                    AiPanelState.Rewrite(
                        "Rewrite error: ${e.localizedMessage}", text, targetTone
                    )
                )
            }
        }
    }

    /**
     * Rewrite with an explicit style instruction (command palette, iterate
     * chips) instead of the selected persona.
     */
    fun rewriteWithStyle(text: String, styleInstruction: String, bypassCache: Boolean = false) {
        if (text.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { rewriteWithStyle(text, styleInstruction, bypassCache = true) }
        launchAi {
            try {
                val result = if (_isOfflineMode.value) {
                    GeminiManager.offlineRewrite(text, styleInstruction)
                } else {
                    GeminiManager.rewriteWithTone(text, styleInstruction, getPersonalizationContext(), _isVoiceLockEnabled.value, bypassCache)
                }
                publishAiPanel(AiPanelState.Rewrite(result, text, styleInstruction))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishAiPanel(
                    AiPanelState.Rewrite(
                        "Rewrite error: ${e.localizedMessage}", text, styleInstruction
                    )
                )
            }
        }
    }

    /**
     * Compose a full message from a typed instruction (e.g. "tell her I'll be
     * 20 minutes late, apologetic").
     */
    fun composeFromInstruction(instruction: String, bypassCache: Boolean = false) {
        if (instruction.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { composeFromInstruction(instruction, bypassCache = true) }
        launchAi {
            try {
                val result = if (_isOfflineMode.value) {
                    GeminiManager.offlineCompose(instruction, effectivePersona(), getPersonalizationContext(), _isVoiceLockEnabled.value)
                } else {
                    GeminiManager.composeMessage(instruction, effectivePersona(), getPersonalizationContext(), _isVoiceLockEnabled.value, bypassCache)
                }
                publishAiPanel(AiPanelState.Compose(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publishAiPanel(AiPanelState.Compose("Compose error: ${e.localizedMessage}"))
            }
        }
    }

    /**
     * Explain dense/jargon-heavy text (usually from the clipboard) in plain language.
     */
    fun explainText(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { explainText(text, bypassCache = true) }
        launchAi {
            try {
                val result = if (_isOfflineMode.value) {
                    "[Offline: explanations need cloud mode]"
                } else {
                    GeminiManager.explainText(text, bypassCache)
                }
                _aiPanelState.value = AiPanelState.Explanation(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiPanelState.value = AiPanelState.Explanation("Explanation error: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Continue the user's draft mid-thought in their own voice.
     */
    fun continueDraft(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || _isSensitiveField.value) return
        aiSession.setRegenerateAction { continueDraft(text, bypassCache = true) }
        launchAi {
            try {
                val result = if (_isOfflineMode.value) {
                    GeminiManager.offlineContinue(text, getPersonalizationContext(), _isVoiceLockEnabled.value)
                } else {
                    GeminiManager.continueText(text, getPersonalizationContext(), _isVoiceLockEnabled.value, bypassCache)
                }
                val panel = result.takeIf { it.isNotBlank() }
                    ?.let(AiPanelState::Continuation)
                    ?: AiPanelState.Idle
                publishAiPanel(panel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiPanelState.value = AiPanelState.Idle
            }
        }
    }

    /**
     * Analyze text sentiment & communication tone
     */
    fun analyzeTone(text: String) {
        if (text.isBlank() || _isSensitiveField.value) return
        launchAi {
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    GeminiManager.offlineTone(text, personalization)
                } else {
                    GeminiManager.analyzeTone(text, personalization)
                }
                _aiPanelState.value = AiPanelState.Tone(result)

                // Record word usage of what was analyzed
                recordWordUsage(text)

                // Save log for personalized style insights
                repository.insertLog(
                    WritingLog(
                        originalText = text,
                        sentiment = result.sentiment,
                        toneScore = result.toneScore,
                        wordCount = text.split(WHITESPACE_REGEX).size
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiPanelState.value = AiPanelState.Tone(
                    ToneAnalysisResponse("Neutral", 0.5f, listOf("Error during analysis."))
                )
            }
        }
    }

    /** Emoji suggestions matching an analyzed tone — fully offline. */
    fun emojisForSentiment(sentiment: String): List<String> {
        val s = sentiment.lowercase()
        return when {
            "joy" in s || "happy" in s || "positive" in s -> listOf("😊", "🎉", "😄")
            "professional" in s || "formal" in s -> listOf("👍", "🤝", "📅")
            "urgent" in s -> listOf("⏰", "🚨", "❗")
            "apolog" in s -> listOf("🙏", "😔", "💐")
            "empath" in s || "love" in s -> listOf("❤️", "🤗", "💛")
            "aggress" in s || "anger" in s || "negative" in s -> listOf("😤", "🙂", "🌿")
            "sarcas" in s -> listOf("😏", "🙃", "😜")
            else -> listOf("🙂", "👍", "✨")
        }
    }

    // --- Shortcut & data management -----------------------------------------------

    fun addShortcut(shortcut: String, template: String) {
        if (shortcut.isBlank() || template.isBlank()) return
        viewModelScope.launch {
            repository.insertShortcut(ShortcutTemplate(shortcut = shortcut.lowercase().trim(), template = template.trim()))
        }
    }

    fun deleteShortcut(id: Int) {
        viewModelScope.launch {
            repository.deleteShortcutById(id)
        }
    }

    /** Saves a user-defined slash command; invalid tokens are silently ignored. */
    fun addCustomCommand(token: String, instruction: String) {
        val normalized = CommandPalette.normalizeToken(token) ?: return
        if (instruction.isBlank()) return
        viewModelScope.launch {
            repository.insertCustomCommand(
                CustomCommand(token = normalized, instruction = instruction.trim())
            )
        }
    }

    fun deleteCustomCommand(id: Int) {
        viewModelScope.launch {
            repository.deleteCustomCommandById(id)
        }
    }

    /**
     * Save an on-device spelling auto-correction rule
     */
    fun addCorrection(typo: String, correction: String) {
        if (typo.isBlank() || correction.isBlank()) return
        viewModelScope.launch {
            val cleanTypo = typo.lowercase().trim()
            val existing = repository.getCorrectionForTypo(cleanTypo)
            if (existing != null) {
                repository.insertCorrection(existing.copy(correction = correction.trim(), count = existing.count + 1))
            } else {
                repository.insertCorrection(LearnedCorrection(typo = cleanTypo, correction = correction.trim()))
            }
        }
    }

    fun deleteCorrection(id: Int) {
        viewModelScope.launch {
            repository.deleteCorrectionById(id)
        }
    }

    fun clearVocabulary() {
        viewModelScope.launch {
            repository.clearVocabulary()
            repository.clearBigrams()
        }
    }

    fun clearCorrections() {
        viewModelScope.launch {
            repository.clearCorrections()
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    /**
     * Imports a previously exported personalization payload (JSON or Base64),
     * merging counts with existing on-device data. Reports the number of records
     * imported, or -1 when the content is not a valid export.
     */
    fun importPersonalModel(content: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val model = PersonalModelSerializer.parseImport(content)
            if (model == null) {
                onResult(-1)
                return@launch
            }
            var imported = 0
            model.typingPatterns?.vocabulary?.forEach { item ->
                if (item.word.isNotBlank()) {
                    val existing = repository.getWord(item.word)
                    repository.insertWord(
                        UserVocabulary(
                            word = item.word,
                            count = (existing?.count ?: 0) + item.count,
                            lastUsed = maxOf(existing?.lastUsed ?: 0L, item.lastUsed, 1L)
                        )
                    )
                    imported++
                }
            }
            model.correctionHistory.forEach { item ->
                if (item.typo.isNotBlank() && item.correction.isNotBlank()) {
                    val typo = item.typo.lowercase().trim()
                    val existing = repository.getCorrectionForTypo(typo)
                    if (existing != null) {
                        repository.insertCorrection(existing.copy(correction = item.correction, count = existing.count + item.count))
                    } else {
                        repository.insertCorrection(LearnedCorrection(typo = typo, correction = item.correction, count = item.count))
                    }
                    imported++
                }
            }
            model.writingLogs.forEach { item ->
                if (item.text.isNotBlank()) {
                    repository.insertLog(
                        WritingLog(
                            originalText = item.text,
                            sentiment = item.sentiment,
                            toneScore = item.toneScore,
                            wordCount = item.text.split(WHITESPACE_REGEX).size,
                            timestamp = if (item.timestamp > 0) item.timestamp else System.currentTimeMillis()
                        )
                    )
                    imported++
                }
            }
            model.exportMetadata?.userPersonaPreference?.let { persona ->
                if (persona in PERSONAS) setUserPersonaPreference(persona)
            }
            onResult(imported)
        }
    }

}

class KeyboardViewModelFactory(
    private val repository: KeyboardRepository,
    private val settings: KeyboardSettings? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(KeyboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return KeyboardViewModel(repository, settings) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
