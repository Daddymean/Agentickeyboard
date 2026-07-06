package com.example.ui

import com.example.network.GeminiManager
import com.example.network.GrammarCorrectionResponse
import com.example.network.SuggestionsResponse
import com.example.network.ToneAnalysisResponse
import com.example.util.ReplyIntents
import kotlinx.coroutines.CancellationException

/**
 * Refactored AiActionHandlers.kt
 * One-shot aggressive extraction and cleanup from KeyboardViewModel.kt.
 * Encapsulates all AI orchestration, Gemini calls, offline fallbacks, and result management.
 * Reduces ViewModel bloat significantly. ViewModel now delegates AI actions here.
 * Improved: better dependency injection via lambdas, clearer separation, reusable regenerate.
 */
class AiActionHandlers(
    private val viewModel: KeyboardViewModel,
    private val isOfflineMode: () -> Boolean = { viewModel._isOfflineMode.value },
    private val isSensitiveField: () -> Boolean = { viewModel._isSensitiveField.value },
    private val getPersonalizationContext: () -> String = { viewModel.getPersonalizationContext() },
    private val effectivePersona: () -> String = { viewModel.effectivePersona() },
    private val isVoiceLockEnabled: () -> Boolean = { viewModel._isVoiceLockEnabled.value },
    private val launchAiBlock: (suspend () -> Unit) -> Unit = { block -> viewModel.viewModelScope.launch { viewModel._isLoading.value = true; try { block() } finally { viewModel._isLoading.value = false } } }
) {

    private var regenerateAction: (() -> Unit)? = null

    fun regenerate() {
        regenerateAction?.invoke()
    }

    fun fixGrammar(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || isSensitiveField()) return
        regenerateAction = { fixGrammar(text, bypassCache = true) }
        launchAiBlock {
            viewModel._grammarCorrection.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (isOfflineMode()) {
                    getOfflineGrammarFix(text)
                } else {
                    GeminiManager.fixGrammar(text, personalization, bypassCache)
                }
                viewModel._grammarCorrection.value = result

                if (viewModel.isLearningAllowed()) {
                    viewModel.extractAndLearnCorrections(text, result.corrected)
                    viewModel.recordWordUsage(result.corrected)
                }

                viewModel.repository.insertLog(
                    com.example.db.WritingLog(
                        originalText = text,
                        sentiment = "Corrected",
                        toneScore = 0.9f,
                        wordCount = text.split("\\s+".toRegex()).size
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._grammarCorrection.value = GrammarCorrectionResponse(text, text, "Error: ${e.localizedMessage}", 0)
            }
        }
    }

    fun suggestReplies(contextMessage: String, intent: String = "", bypassCache: Boolean = false) {
        if (contextMessage.isBlank() || isSensitiveField()) return
        regenerateAction = { suggestReplies(contextMessage, intent, bypassCache = true) }
        launchAiBlock {
            viewModel._suggestions.value = emptyList()
            try {
                val personalization = getPersonalizationContext()
                val result = if (isOfflineMode()) {
                    getOfflineSuggestions(contextMessage, personalization, intent)
                } else {
                    GeminiManager.suggestReplies(contextMessage, personalization, intent, bypassCache)
                }
                viewModel._suggestions.value = result.suggestions
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._suggestions.value = ReplyIntents.offlineReplies(intent) ?: listOf("Sounds good!", "Sure thing", "Let me check.")
            }
        }
    }

    fun summarizeMessage(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || isSensitiveField()) return
        regenerateAction = { summarizeMessage(text, bypassCache = true) }
        viewModel._aiResultSource.value = text
        launchAiBlock {
            viewModel._summary.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (isOfflineMode()) {
                    getOfflineSummary(text)
                } else {
                    GeminiManager.summarizeMessage(text, personalization, bypassCache)
                }
                viewModel._summary.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._summary.value = "Failed to summarize text: ${e.localizedMessage}"
            }
        }
    }

    fun translateText(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || isSensitiveField()) return
        regenerateAction = { translateText(text, bypassCache = true) }
        viewModel._aiResultSource.value = text
        launchAiBlock {
            viewModel._translation.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (isOfflineMode()) {
                    "[Offline] $text"
                } else {
                    GeminiManager.translateText(text, viewModel._sourceLanguage.value, viewModel._targetLanguage.value, personalization, bypassCache)
                }
                viewModel._translation.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._translation.value = "Translation error: ${e.localizedMessage}"
            }
        }
    }

    fun rewriteTone(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || isSensitiveField()) return
        regenerateAction = { rewriteTone(text, bypassCache = true) }
        viewModel._aiResultSource.value = text
        launchAiBlock {
            viewModel._rewrite.value = null
            try {
                val targetTone = effectivePersona()
                val result = if (isOfflineMode()) {
                    "[Offline: rewrite needs cloud mode] $text"
                } else {
                    GeminiManager.rewriteWithTone(text, targetTone, getPersonalizationContext(), isVoiceLockEnabled(), bypassCache)
                }
                viewModel._rewrite.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._rewrite.value = "Rewrite error: ${e.localizedMessage}"
            }
        }
    }

    fun rewriteWithStyle(text: String, styleInstruction: String, bypassCache: Boolean = false) {
        if (text.isBlank() || isSensitiveField()) return
        regenerateAction = { rewriteWithStyle(text, styleInstruction, bypassCache = true) }
        viewModel._aiResultSource.value = text
        launchAiBlock {
            viewModel._rewrite.value = null
            try {
                val result = if (isOfflineMode()) {
                    "[Offline: rewrite needs cloud mode] $text"
                } else {
                    GeminiManager.rewriteWithTone(text, styleInstruction, getPersonalizationContext(), isVoiceLockEnabled(), bypassCache)
                }
                viewModel._rewrite.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._rewrite.value = "Rewrite error: ${e.localizedMessage}"
            }
        }
    }

    fun composeFromInstruction(instruction: String, bypassCache: Boolean = false) {
        if (instruction.isBlank() || isSensitiveField()) return
        regenerateAction = { composeFromInstruction(instruction, bypassCache = true) }
        viewModel._aiResultSource.value = null
        launchAiBlock {
            viewModel._composeResult.value = null
            try {
                val result = if (isOfflineMode()) {
                    "[Offline: compose needs cloud mode]"
                } else {
                    GeminiManager.composeMessage(instruction, effectivePersona(), getPersonalizationContext(), isVoiceLockEnabled(), bypassCache)
                }
                viewModel._composeResult.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._composeResult.value = "Compose error: ${e.localizedMessage}"
            }
        }
    }

    fun explainText(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || isSensitiveField()) return
        regenerateAction = { explainText(text, bypassCache = true) }
        viewModel._aiResultSource.value = null
        launchAiBlock {
            viewModel._explanation.value = null
            try {
                val result = if (isOfflineMode()) {
                    "[Offline: explanations need cloud mode]"
                } else {
                    GeminiManager.explainText(text, bypassCache)
                }
                viewModel._explanation.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._explanation.value = "Explanation error: ${e.localizedMessage}"
            }
        }
    }

    fun continueDraft(text: String, bypassCache: Boolean = false) {
        if (text.isBlank() || isSensitiveField()) return
        regenerateAction = { continueDraft(text, bypassCache = true) }
        viewModel._aiResultSource.value = null
        launchAiBlock {
            viewModel._continuation.value = null
            try {
                val result = if (isOfflineMode()) {
                    "[Offline: continue needs cloud mode]"
                } else {
                    GeminiManager.continueText(text, getPersonalizationContext(), isVoiceLockEnabled(), bypassCache)
                }
                viewModel._continuation.value = result.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._continuation.value = null
            }
        }
    }

    fun analyzeTone(text: String) {
        if (text.isBlank() || isSensitiveField()) return
        launchAiBlock {
            viewModel._toneAnalysis.value = null
            try {
                val personalization = getPersonalizationContext()
                val result = if (isOfflineMode()) {
                    getOfflineToneAnalysis(text)
                } else {
                    GeminiManager.analyzeTone(text, personalization)
                }
                viewModel._toneAnalysis.value = result

                viewModel.recordWordUsage(text)

                viewModel.repository.insertLog(
                    com.example.db.WritingLog(
                        originalText = text,
                        sentiment = result.sentiment,
                        toneScore = result.toneScore,
                        wordCount = text.split("\\s+".toRegex()).size
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel._toneAnalysis.value = ToneAnalysisResponse("Neutral", 0.5f, listOf("Error during analysis."))
            }
        }
    }

    fun dismissResults() {
        viewModel.dismissResults()
    }

    // Offline helpers (moved and cleaned up)
    private fun getOfflineGrammarFix(text: String): GrammarCorrectionResponse {
        var corrected = text
        var count = 0
        val replacements = listOf("\\bteh\\b".toRegex(RegexOption.IGNORE_CASE) to "the")
        for ((typo, fix) in replacements) {
            if (typo.containsMatchIn(corrected)) {
                corrected = corrected.replace(typo, fix)
                count++
            }
        }
        return GrammarCorrectionResponse(text, corrected, "Offline grammar spellchecker applied.", count)
    }

    private fun getOfflineSuggestions(context: String, personalization: String = "", intent: String = ""): SuggestionsResponse {
        if (intent.isNotEmpty()) {
            ReplyIntents.offlineReplies(intent)?.let { return SuggestionsResponse(it) }
        }
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
        // Simplified offline tone (use WritingQualityMeter if available)
        return ToneAnalysisResponse("Neutral (Offline)", 0.8f, listOf("Connect online for deep sentiment models."))
    }

    // Emojis and other helpers can be added here too.
}
