package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.db.KeyboardRepository
import com.example.db.ShortcutTemplate
import com.example.db.WritingLog
import com.example.network.GeminiManager
import com.example.network.GrammarCorrectionResponse
import com.example.network.SuggestionsResponse
import com.example.network.ToneAnalysisResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class KeyboardViewModel(private val repository: KeyboardRepository) : ViewModel() {

    // Shortcuts and logs from local Room DB
    val shortcuts: StateFlow<List<ShortcutTemplate>> = repository.allShortcuts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<WritingLog>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // On-device Personalization state flows
    val topVocabulary: StateFlow<List<com.example.db.UserVocabulary>> = repository.topVocabulary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val learnedCorrections: StateFlow<List<com.example.db.LearnedCorrection>> = repository.allCorrections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected style persona preference (Match history, Professional, Joyful, Empathetic, Casual)
    private val _userPersonaPreference = MutableStateFlow("Match my history")
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Settings
    private val _isOfflineMode = MutableStateFlow(false)
    val isOfflineMode = _isOfflineMode.asStateFlow()

    private val _sourceLanguage = MutableStateFlow("English")
    val sourceLanguage = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow("Spanish")
    val targetLanguage = _targetLanguage.asStateFlow()

    init {
        // Seed default templates if database is empty
        viewModelScope.launch {
            repository.allShortcuts.collect { list ->
                if (list.isEmpty()) {
                    repository.insertShortcut(ShortcutTemplate(shortcut = "omw", template = "On my way! Running late."))
                    repository.insertShortcut(ShortcutTemplate(shortcut = "gr", template = "Great! Talk to you soon."))
                    repository.insertShortcut(ShortcutTemplate(shortcut = "ty", template = "Thank you so much! Really appreciate it."))
                    repository.insertShortcut(ShortcutTemplate(shortcut = "brb", template = "Be right back! Just in a quick meeting."))
                }
            }
        }

        // Seed default vocabulary for on-device personalized stats display
        viewModelScope.launch {
            repository.topVocabulary.collect { list ->
                if (list.isEmpty()) {
                    repository.insertWord(com.example.db.UserVocabulary(word = "fantastic", count = 12))
                    repository.insertWord(com.example.db.UserVocabulary(word = "awesome", count = 9))
                    repository.insertWord(com.example.db.UserVocabulary(word = "absolutely", count = 7))
                    repository.insertWord(com.example.db.UserVocabulary(word = "cheers", count = 5))
                    repository.insertWord(com.example.db.UserVocabulary(word = "meeting", count = 4))
                }
            }
        }

        // Seed default learned corrections
        viewModelScope.launch {
            repository.allCorrections.collect { list ->
                if (list.isEmpty()) {
                    repository.insertCorrection(com.example.db.LearnedCorrection(typo = "teh", correction = "the", count = 15))
                    repository.insertCorrection(com.example.db.LearnedCorrection(typo = "tomorow", correction = "tomorrow", count = 8))
                    repository.insertCorrection(com.example.db.LearnedCorrection(typo = "definately", correction = "definitely", count = 5))
                }
            }
        }
    }

    fun setInputText(text: String) {
        _inputText.value = text
        updatePredictiveSuggestions(text)
    }

    fun setLanguages(source: String, target: String) {
        _sourceLanguage.value = source
        _targetLanguage.value = target
    }

    fun toggleOfflineMode() {
        _isOfflineMode.value = !_isOfflineMode.value
    }

    fun setUserPersonaPreference(persona: String) {
        _userPersonaPreference.value = persona
    }

    /**
     * Compute dynamic personalization context to inject into AI requests
     */
    fun getPersonalizationContext(): String {
        val logList = logs.value
        val dominantTone = if (logList.isNotEmpty()) {
            logList.filter { it.sentiment != "Corrected" && it.sentiment.isNotEmpty() }
                .groupBy { it.sentiment }
                .maxByOrNull { it.value.size }?.key ?: "Casual"
        } else {
            "Casual"
        }
        
        val vocabularyList = topVocabulary.value.take(5).joinToString(", ") { it.word }
        val personaPref = _userPersonaPreference.value
        val selectedPersona = if (personaPref == "Match my history") dominantTone else personaPref
        
        return buildString {
            append("User selected style persona: $selectedPersona. ")
            if (vocabularyList.isNotEmpty()) {
                append("User frequently uses these personalized keywords: [$vocabularyList]. ")
            }
            append("Make recommendations and response suggestions match this vocabulary and tone.")
        }
    }

    /**
     * Parse word usage and count them on-device
     */
    fun recordWordUsage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val words = text.split("\\s+".toRegex())
            val stopWords = setOf("the", "and", "a", "of", "to", "in", "is", "that", "it", "for", "on", "with", "as", "at", "by", "an", "be", "this", "are", "from")
            for (w in words) {
                val cleaned = w.replace("[^a-zA-Z]".toRegex(), "").lowercase().trim()
                if (cleaned.length > 2 && !stopWords.contains(cleaned)) {
                    val existing = repository.getWord(cleaned)
                    if (existing != null) {
                        repository.insertWord(existing.copy(count = existing.count + 1, lastUsed = System.currentTimeMillis()))
                    } else {
                        repository.insertWord(com.example.db.UserVocabulary(word = cleaned, count = 1))
                    }
                }
            }
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
                repository.insertCorrection(com.example.db.LearnedCorrection(typo = cleanTypo, correction = correction.trim()))
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
        }
    }

    fun clearCorrections() {
        viewModelScope.launch {
            repository.clearCorrections()
        }
    }

    /**
     * Compute predictive autocomplete options based on top vocabulary
     */
    fun updatePredictiveSuggestions(activeText: String) {
        viewModelScope.launch {
            if (activeText.isEmpty()) {
                // Show user's frequent personalized words as prompt suggestions
                val topWords = topVocabulary.value.take(3).map { it.word }
                if (topWords.isNotEmpty()) {
                    _predictiveSuggestions.value = topWords.map { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
                } else {
                    _predictiveSuggestions.value = listOf("Hello", "Awesome", "Got it")
                }
            } else {
                val words = activeText.split("\\s+".toRegex())
                val lastWord = words.lastOrNull()?.lowercase() ?: ""
                if (lastWord.length >= 1) {
                    val matches = topVocabulary.value
                        .filter { it.word.startsWith(lastWord) && it.word != lastWord }
                        .take(3)
                        .map { it.word }
                    _predictiveSuggestions.value = matches
                } else {
                    _predictiveSuggestions.value = emptyList()
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

    /**
     * Trigger grammar correction
     */
    fun fixGrammar(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
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
                extractAndLearnCorrections(text, result.corrected)
                recordWordUsage(result.corrected)
                
                // Save log for personalized model export
                repository.insertLog(
                    WritingLog(
                        originalText = text,
                        sentiment = "Corrected",
                        toneScore = 0.9f,
                        wordCount = text.split("\\s+".toRegex()).size
                    )
                )
            } catch (e: Exception) {
                _grammarCorrection.value = GrammarCorrectionResponse(text, text, "Error: ${e.localizedMessage}", 0)
            } finally {
                _isLoading.value = false
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
                        repository.insertCorrection(com.example.db.LearnedCorrection(typo = oWord, correction = cWord))
                    }
                }
            }
        }
    }

    /**
     * Suggest quick replies
     */
    fun suggestReplies(contextMessage: String) {
        if (contextMessage.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _suggestions.value = emptyList()
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    getOfflineSuggestions(contextMessage, personalization)
                } else {
                    GeminiManager.suggestReplies(contextMessage, personalization)
                }
                _suggestions.value = result.suggestions
            } catch (e: Exception) {
                _suggestions.value = listOf("Sounds good!", "Sure thing", "Let me check.")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Summarize long text
     */
    fun summarizeMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _summary.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    getOfflineSummary(text)
                } else {
                    GeminiManager.summarizeMessage(text, personalization)
                }
                _summary.value = result
            } catch (e: Exception) {
                _summary.value = "Failed to summarize text: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Translate text
     */
    fun translateText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _translation.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (_isOfflineMode.value) {
                    "[Offline] $text"
                } else {
                    GeminiManager.translateText(text, _sourceLanguage.value, _targetLanguage.value, personalization)
                }
                _translation.value = result
            } catch (e: Exception) {
                _translation.value = "Translation error: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Analyze text sentiment & communication tone
     */
    fun analyzeTone(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
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
            } catch (e: Exception) {
                _toneAnalysis.value = ToneAnalysisResponse("Neutral", 0.5f, listOf("Error during analysis."))
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Shortcut management
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

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
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

    private fun getOfflineSuggestions(context: String, personalization: String = ""): SuggestionsResponse {
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

class KeyboardViewModelFactory(private val repository: KeyboardRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(KeyboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return KeyboardViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
