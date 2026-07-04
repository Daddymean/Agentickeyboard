package com.example.ui

import android.content.SharedPreferences
import android.text.InputType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.db.KeyboardRepository
import com.example.db.LearnedCorrection
import com.example.db.ShortcutTemplate
import com.example.db.UserVocabulary
import com.example.db.WritingLog
import com.example.network.GeminiManager
import com.example.network.GrammarCorrectionResponse
import com.example.network.SuggestionsResponse
import com.example.network.ToneAnalysisResponse
import com.example.util.KeyboardSettings
import com.example.util.PersonalModelSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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

/** Local, on-device usage statistics shown in the Style Hub dashboard. */
data class UsageStats(
    val autoCorrections: Int = 0,
    val swipeWords: Int = 0,
    val aiApplies: Int = 0,
    val shortcutExpansions: Int = 0
)

class KeyboardViewModel(
    private val repository: KeyboardRepository,
    private val settings: KeyboardSettings? = null
) : ViewModel() {

    companion object {
        val PERSONAS = listOf("Match my history", "Professional", "Joyful", "Empathetic", "Casual")
        private val STOP_WORDS = setOf(
            "the", "and", "a", "of", "to", "in", "is", "that", "it", "for",
            "on", "with", "as", "at", "by", "an", "be", "this", "are", "from"
        )
        private const val DAY_MS = 86_400_000L
    }

    // Shortcuts and logs from local Room DB
    val shortcuts: StateFlow<List<ShortcutTemplate>> = repository.allShortcuts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<WritingLog>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // On-device Personalization state flows
    val topVocabulary: StateFlow<List<UserVocabulary>> = repository.topVocabulary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val learnedCorrections: StateFlow<List<LearnedCorrection>> = repository.allCorrections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected style persona preference (Match history, Professional, Joyful, Empathetic, Casual)
    private val _userPersonaPreference = MutableStateFlow(settings?.persona ?: "Match my history")
    val userPersonaPreference = _userPersonaPreference.asStateFlow()

    // Real-time predictive autocomplete completions
    private val _predictiveSuggestions = MutableStateFlow<List<String>>(emptyList())
    val predictiveSuggestions = _predictiveSuggestions.asStateFlow()

    // Active input text state
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    // AI states
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    private val _grammarCorrection = MutableStateFlow<GrammarCorrectionResponse?>(null)
    val grammarCorrection = _grammarCorrection.asStateFlow()

    private val _toneAnalysis = MutableStateFlow<ToneAnalysisResponse?>(null)
    val toneAnalysis = _toneAnalysis.asStateFlow()

    private val _summary = MutableStateFlow<String?>(null)
    val summary = _summary.asStateFlow()

    private val _translation = MutableStateFlow<String?>(null)
    val translation = _translation.asStateFlow()

    private val _rewrite = MutableStateFlow<String?>(null)
    val rewrite = _rewrite.asStateFlow()

    private val _composeResult = MutableStateFlow<String?>(null)
    val composeResult = _composeResult.asStateFlow()

    private val _explanation = MutableStateFlow<String?>(null)
    val explanation = _explanation.asStateFlow()

    private val _continuation = MutableStateFlow<String?>(null)
    val continuation = _continuation.asStateFlow()

    // Debounced background grammar check result (opt-in; see isProofreadEnabled)
    private val _proofreadHint = MutableStateFlow<GrammarCorrectionResponse?>(null)
    val proofreadHint = _proofreadHint.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Settings-backed state (persisted when a KeyboardSettings store is provided)
    private val _isOfflineMode = MutableStateFlow(settings?.isOfflineMode ?: false)
    val isOfflineMode = _isOfflineMode.asStateFlow()

    private val _themeMode = MutableStateFlow(settings?.themeMode ?: KeyboardSettings.THEME_SYSTEM)
    val themeMode = _themeMode.asStateFlow()

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

    private var activeAppPackage: String? = null
    private var previousCommittedWord: String? = null
    private var pendingUndo: AutoCorrectionUndo? = null
    private val correctionReverts = mutableMapOf<String, Int>()
    private var proofreadJob: Job? = null
    private var aiJob: Job? = null

    // Keeps this ViewModel in sync when the other component (companion app vs IME
    // service) changes a shared setting — they hold separate ViewModel instances.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val s = settings ?: return@OnSharedPreferenceChangeListener
        when (key) {
            KeyboardSettings.KEY_OFFLINE_MODE -> _isOfflineMode.value = s.isOfflineMode
            KeyboardSettings.KEY_THEME_MODE -> _themeMode.value = s.themeMode
            KeyboardSettings.KEY_SWIPE_ENABLED -> _isSwipeEnabled.value = s.isSwipeEnabled
            KeyboardSettings.KEY_AUTO_CAPITALIZE -> _isAutoCapitalizeEnabled.value = s.isAutoCapitalizeEnabled
            KeyboardSettings.KEY_NUMBER_ROW -> _isNumberRowEnabled.value = s.isNumberRowEnabled
            KeyboardSettings.KEY_PROOFREAD -> _isProofreadEnabled.value = s.isProofreadEnabled
            KeyboardSettings.KEY_LEARNING_PAUSED -> _isLearningPaused.value = s.isLearningPaused
            KeyboardSettings.KEY_HAPTICS -> _isHapticsEnabled.value = s.isHapticsEnabled
            KeyboardSettings.KEY_PERSONA -> _userPersonaPreference.value = s.persona
            KeyboardSettings.KEY_SOURCE_LANG -> _sourceLanguage.value = s.sourceLanguage
            KeyboardSettings.KEY_TARGET_LANG -> _targetLanguage.value = s.targetLanguage
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
        settings?.unregisterListener(prefsListener)
        super.onCleared()
    }

    /**
     * Cancels any in-flight AI request and starts a new one, so rapid taps never
     * stack requests or leave a stale spinner.
     */
    private fun launchAi(block: suspend () -> Unit) {
        val previous = aiJob
        aiJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            _isLoading.value = true
            try {
                block()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clears every pending AI result panel (grammar, tone, summary, translation,
     * rewrite, compose, explanation, continuation, and reply suggestions).
     */
    fun dismissResults() {
        _grammarCorrection.value = null
        _toneAnalysis.value = null
        _summary.value = null
        _translation.value = null
        _rewrite.value = null
        _composeResult.value = null
        _explanation.value = null
        _continuation.value = null
        _suggestions.value = emptyList()
    }

    fun setInputText(text: String) {
        _inputText.value = text
        updatePredictiveSuggestions(text)
        scheduleProofread(text)
    }

    /**
     * Called by the IME service whenever a new editor gains focus: detects
     * password/secure fields and restores the persona last used in this app.
     */
    fun onEditorStarted(packageName: String?, inputType: Int) {
        _isSensitiveField.value = isPasswordInputType(inputType)
        activeAppPackage = packageName
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

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        settings?.themeMode = mode
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

    fun setLogRetentionDays(days: Int) {
        settings?.logRetentionDays = days
    }

    fun getLogRetentionDays(): Int = settings?.logRetentionDays ?: 30

    fun setUserPersonaPreference(persona: String) {
        _userPersonaPreference.value = persona
        settings?.persona = persona
        activeAppPackage?.let { pkg ->
            viewModelScope.launch { repository.setAppPersona(pkg, persona) }
        }
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
            val words = text.split("\\s+".toRegex())
            for (w in words) {
                val cleaned = w.replace("[^a-zA-Z]".toRegex(), "").lowercase().trim()
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
        val cleaned = rawWord.replace("[^a-zA-Z]".toRegex(), "").lowercase()
        val previous = previousCommittedWord
        previousCommittedWord = if (cleaned.length in 2..24) cleaned else null
        viewModelScope.launch {
            if (cleaned.length > 2 && !STOP_WORDS.contains(cleaned)) {
                repository.recordWordUsage(cleaned)
            }
            // Stop words stay in bigrams: pairs like "on my" carry the signal
            if (previous != null && cleaned.length >= 2) {
                repository.recordBigram(previous, cleaned)
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

    fun recordAutoCorrectionStat() = updateStats { it.copy(autoCorrections = it.autoCorrections + 1) }
    fun recordSwipeWordStat() = updateStats { it.copy(swipeWords = it.swipeWords + 1) }
    fun recordAiApplyStat() = updateStats { it.copy(aiApplies = it.aiApplies + 1) }
    fun recordShortcutExpansionStat() = updateStats { it.copy(shortcutExpansions = it.shortcutExpansions + 1) }

    // --- Prediction ------------------------------------------------------------

    /**
     * Compute predictive options: next-word predictions from the bigram model when
     * a word was just committed, prefix completions from personal vocabulary while
     * a word is being typed, and frequent words as prompts when the field is empty.
     */
    fun updatePredictiveSuggestions(activeText: String) {
        viewModelScope.launch {
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
                    val previous = activeText.trim().split("\\s+".toRegex()).lastOrNull()
                        ?.replace("[^a-zA-Z]".toRegex(), "")?.lowercase().orEmpty()
                    _predictiveSuggestions.value = if (previous.length >= 2) {
                        repository.nextWords(previous, 3)
                    } else {
                        emptyList()
                    }
                }
                else -> {
                    val lastWord = activeText.split("\\s+".toRegex()).lastOrNull()?.lowercase() ?: ""
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
        val words = text.split("\\s+".toRegex())
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
            _grammarCorrection.value = it
            _proofreadHint.value = null
        }
    }

    // --- AI actions --------------------------------------------------------------

    /**
     * Trigger grammar correction
     */
    fun fixGrammar(text: String) {
        if (text.isBlank() || _isSensitiveField.value) return
        launchAi {
            _grammarCorrection.value = null
            try {
                // Incorporate personalization preferences inside grammar suggestions
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    getOfflineGrammarFix(text)
                } else {
                    GeminiManager.fixGrammar(text, personalization)
                }
                _grammarCorrection.value = result

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
                        wordCount = text.split("\\s+".toRegex()).size
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _grammarCorrection.value = GrammarCorrectionResponse(text, text, "Error: ${e.localizedMessage}", 0)
            }
        }
    }

    /**
     * Dynamic alignment of original and corrected text to extract custom spelling fixes
     */
    private suspend fun extractAndLearnCorrections(original: String, corrected: String) {
        val origWords = original.split("\\s+".toRegex()).map { it.replace("[^a-zA-Z]".toRegex(), "").lowercase() }
        val corrWords = corrected.split("\\s+".toRegex()).map { it.replace("[^a-zA-Z]".toRegex(), "").lowercase() }

        if (origWords.size == corrWords.size) {
            for (i in origWords.indices) {
                val oWord = origWords[i]
                val cWord = corrWords[i]
                if (oWord.isNotEmpty() && cWord.isNotEmpty() && oWord != cWord && oWord.length > 2) {
                    val existing = repository.getCorrectionForTypo(oWord)
                    if (existing != null) {
                        repository.insertCorrection(existing.copy(correction = cWord, count = existing.count + 1))
                    } else {
                        repository.insertCorrection(LearnedCorrection(typo = oWord, correction = cWord))
                    }
                }
            }
        }
    }

    /**
     * Suggest quick replies (short, medium, and detailed variants), optionally
     * steered toward a chosen intent (Accept, Decline, Negotiate, ...).
     */
    fun suggestReplies(contextMessage: String, intent: String = "") {
        if (contextMessage.isBlank() || _isSensitiveField.value) return
        launchAi {
            _suggestions.value = emptyList()
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    getOfflineSuggestions(contextMessage, personalization, intent)
                } else {
                    GeminiManager.suggestReplies(contextMessage, personalization, intent)
                }
                _suggestions.value = result.suggestions
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _suggestions.value = listOf("Sounds good!", "Sure thing", "Let me check.")
            }
        }
    }

    /**
     * Summarize long text
     */
    fun summarizeMessage(text: String) {
        if (text.isBlank() || _isSensitiveField.value) return
        launchAi {
            _summary.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    getOfflineSummary(text)
                } else {
                    GeminiManager.summarizeMessage(text, personalization)
                }
                _summary.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _summary.value = "Failed to summarize text: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Translate text
     */
    fun translateText(text: String) {
        if (text.isBlank() || _isSensitiveField.value) return
        launchAi {
            _translation.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    "[Offline] $text"
                } else {
                    GeminiManager.translateText(text, _sourceLanguage.value, _targetLanguage.value, personalization)
                }
                _translation.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _translation.value = "Translation error: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Rewrite text to match the selected style persona
     */
    fun rewriteTone(text: String) {
        if (text.isBlank() || _isSensitiveField.value) return
        launchAi {
            _rewrite.value = null
            try {
                val targetTone = effectivePersona()
                val result = if (_isOfflineMode.value) {
                    "[Offline: rewrite needs cloud mode] $text"
                } else {
                    GeminiManager.rewriteWithTone(text, targetTone, getPersonalizationContext())
                }
                _rewrite.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _rewrite.value = "Rewrite error: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Compose a full message from a typed instruction (e.g. "tell her I'll be
     * 20 minutes late, apologetic").
     */
    fun composeFromInstruction(instruction: String) {
        if (instruction.isBlank() || _isSensitiveField.value) return
        launchAi {
            _composeResult.value = null
            try {
                val result = if (_isOfflineMode.value) {
                    "[Offline: compose needs cloud mode]"
                } else {
                    GeminiManager.composeMessage(instruction, effectivePersona(), getPersonalizationContext())
                }
                _composeResult.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _composeResult.value = "Compose error: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Explain dense/jargon-heavy text (usually from the clipboard) in plain language.
     */
    fun explainText(text: String) {
        if (text.isBlank() || _isSensitiveField.value) return
        launchAi {
            _explanation.value = null
            try {
                val result = if (_isOfflineMode.value) {
                    "[Offline: explanations need cloud mode]"
                } else {
                    GeminiManager.explainText(text)
                }
                _explanation.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _explanation.value = "Explanation error: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Continue the user's draft mid-thought in their own voice.
     */
    fun continueDraft(text: String) {
        if (text.isBlank() || _isSensitiveField.value) return
        launchAi {
            _continuation.value = null
            try {
                val result = if (_isOfflineMode.value) {
                    "[Offline: continue needs cloud mode]"
                } else {
                    GeminiManager.continueText(text, getPersonalizationContext())
                }
                _continuation.value = result.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _continuation.value = null
            }
        }
    }

    /**
     * Analyze text sentiment & communication tone
     */
    fun analyzeTone(text: String) {
        if (text.isBlank() || _isSensitiveField.value) return
        launchAi {
            _toneAnalysis.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    getOfflineToneAnalysis(text)
                } else {
                    GeminiManager.analyzeTone(text, personalization)
                }
                _toneAnalysis.value = result

                // Record word usage of what was analyzed
                recordWordUsage(text)

                // Save log for personalized style insights
                repository.insertLog(
                    WritingLog(
                        originalText = text,
                        sentiment = result.sentiment,
                        toneScore = result.toneScore,
                        wordCount = text.split("\\s+".toRegex()).size
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toneAnalysis.value = ToneAnalysisResponse("Neutral", 0.5f, listOf("Error during analysis."))
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
                            wordCount = item.text.split("\\s+".toRegex()).size,
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

    // --- Local Helpers for Offline Mode Isolation ---

    private fun getOfflineGrammarFix(text: String): GrammarCorrectionResponse {
        var corrected = text
        var count = 0
        val replacements = mapOf("\\bteh\\b" to "the", "\\bi\\b" to "I", "\\bcant\\b" to "can't")
        for ((typo, fix) in replacements) {
            val r = typo.toRegex(RegexOption.IGNORE_CASE)
            if (r.containsMatchIn(corrected)) {
                corrected = corrected.replace(r, fix)
                count++
            }
        }
        return GrammarCorrectionResponse(text, corrected, "Offline grammar spellchecker applied.", count)
    }

    private fun getOfflineSuggestions(context: String, personalization: String = "", intent: String = ""): SuggestionsResponse {
        GeminiManager.intentOfflineReplies(intent)?.let { return SuggestionsResponse(it) }
        val isProfessional = personalization.contains("Professional", ignoreCase = true)
        val isJoyful = personalization.contains("Joyful", ignoreCase = true) || personalization.contains("Friendly", ignoreCase = true)
        val replies = when {
            isProfessional -> listOf("Understood, thank you.", "I will review and follow up.", "Acknowledged.")
            isJoyful -> listOf("Amazing! 🎉", "Awesome, thank you!", "Sounds super great!")
            else -> listOf("Okay", "Sounds good!", "I'll reply soon.")
        }
        return SuggestionsResponse(replies)
    }

    private fun getOfflineSummary(text: String): String {
        return "Offline Local Summary: " + text.take(30) + "..."
    }

    private fun getOfflineToneAnalysis(text: String): ToneAnalysisResponse {
        return ToneAnalysisResponse("Neutral (Offline)", 0.8f, listOf("Connect online for deep sentiment models."))
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
