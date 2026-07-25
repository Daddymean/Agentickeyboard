package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog

/** Import behavior selected explicitly by the user after previewing a passport. */
enum class KeyboardPassportImportMode { MERGE, REPLACE }

/** Complete local model snapshot used to plan an import before any database write. */
data class KeyboardPassportSnapshot(
    val personaPreference: String,
    val vocabulary: List<UserVocabulary> = emptyList(),
    val corrections: List<LearnedCorrection> = emptyList(),
    val shortcuts: List<ShortcutTemplate> = emptyList(),
    val customCommands: List<CustomCommand> = emptyList(),
    val appPersonas: List<AppPersona> = emptyList(),
    val writingLogs: List<WritingLog> = emptyList()
)

data class KeyboardPassportImportPlan(
    val snapshot: KeyboardPassportSnapshot,
    val affectedCategories: Set<PassportCategory>,
    val incomingRecordCount: Int
)

/**
 * Pure, deterministic merge/replace rules for Keyboard Passport imports.
 *
 * Only categories explicitly present in the passport may change. Replace means
 * "replace the included categories," never "wipe the entire personal model."
 */
object KeyboardPassportImportPlanner {

    fun plan(
        current: KeyboardPassportSnapshot,
        incoming: PassportPayload,
        categories: Set<PassportCategory>,
        mode: KeyboardPassportImportMode
    ): KeyboardPassportImportPlan {
        val importedVocabulary = normalizeVocabulary(incoming.vocabulary)
        val importedCorrections = normalizeCorrections(incoming.corrections)
        val importedShortcuts = normalizeShortcuts(incoming.shortcuts)
        val importedCommands = normalizeCommands(incoming.customCommands)
        val importedAppPersonas = normalizeAppPersonas(incoming.appPersonas)
        val importedLogs = normalizeLogs(incoming.writingLogs)

        val output = KeyboardPassportSnapshot(
            personaPreference = when {
                PassportCategory.PERSONA_PREFERENCE !in categories -> current.personaPreference
                incoming.personaPreference.isNullOrBlank() -> current.personaPreference
                else -> incoming.personaPreference.trim()
            },
            vocabulary = choose(
                category = PassportCategory.VOCABULARY,
                categories = categories,
                current = normalizeVocabulary(current.vocabulary.map {
                    ImportedVocabulary(it.word, it.count, it.lastUsed)
                }),
                incoming = importedVocabulary,
                mode = mode,
                merge = ::mergeVocabulary
            ).map { UserVocabulary(word = it.word, count = it.count, lastUsed = it.lastUsed) },
            corrections = choose(
                category = PassportCategory.CORRECTIONS,
                categories = categories,
                current = normalizeCorrections(current.corrections.map {
                    ImportedCorrection(it.typo, it.correction, it.count)
                }),
                incoming = importedCorrections,
                mode = mode,
                merge = ::mergeCorrections
            ).map { LearnedCorrection(typo = it.typo, correction = it.correction, count = it.count) },
            shortcuts = choose(
                category = PassportCategory.SHORTCUTS,
                categories = categories,
                current = normalizeShortcuts(current.shortcuts.map {
                    PassportShortcut(it.shortcut, it.template)
                }),
                incoming = importedShortcuts,
                mode = mode,
                merge = ::mergeShortcuts
            ).map { ShortcutTemplate(shortcut = it.shortcut, template = it.template) },
            customCommands = choose(
                category = PassportCategory.CUSTOM_COMMANDS,
                categories = categories,
                current = normalizeCommands(current.customCommands.map {
                    PassportCustomCommand(it.token, it.instruction)
                }),
                incoming = importedCommands,
                mode = mode,
                merge = ::mergeCommands
            ).map { CustomCommand(token = it.token, instruction = it.instruction) },
            appPersonas = choose(
                category = PassportCategory.APP_PERSONAS,
                categories = categories,
                current = normalizeAppPersonas(current.appPersonas.map {
                    PassportAppPersona(it.packageName, it.persona, it.appLabel)
                }),
                incoming = importedAppPersonas,
                mode = mode,
                merge = ::mergeAppPersonas
            ).map { AppPersona(packageName = it.packageName, persona = it.persona, appLabel = it.appLabel) },
            writingLogs = choose(
                category = PassportCategory.WRITING_LOGS,
                categories = categories,
                current = normalizeLogs(current.writingLogs.map {
                    ImportedLog(it.originalText, it.sentiment, it.toneScore, it.timestamp)
                }),
                incoming = importedLogs,
                mode = mode,
                merge = ::mergeLogs
            ).map {
                WritingLog(
                    originalText = it.text,
                    sentiment = it.sentiment,
                    toneScore = it.toneScore,
                    wordCount = it.text.split(Regex("\\s+")).count { word -> word.isNotBlank() },
                    timestamp = it.timestamp
                )
            }
        )

        return KeyboardPassportImportPlan(
            snapshot = output,
            affectedCategories = categories,
            incomingRecordCount = importedVocabulary.size + importedCorrections.size +
                importedShortcuts.size + importedCommands.size + importedAppPersonas.size + importedLogs.size
        )
    }

    private fun <T> choose(
        category: PassportCategory,
        categories: Set<PassportCategory>,
        current: List<T>,
        incoming: List<T>,
        mode: KeyboardPassportImportMode,
        merge: (List<T>, List<T>) -> List<T>
    ): List<T> = when {
        category !in categories -> current
        mode == KeyboardPassportImportMode.REPLACE -> incoming
        else -> merge(current, incoming)
    }

    private fun normalizeVocabulary(items: List<ImportedVocabulary>): List<ImportedVocabulary> = items
        .asSequence()
        .mapNotNull { item ->
            val word = item.word.trim().lowercase()
            word.takeIf { it.isNotBlank() }?.let {
                ImportedVocabulary(it, item.count.coerceAtLeast(1), item.lastUsed.coerceAtLeast(0L))
            }
        }
        .groupBy { it.word }
        .map { (word, values) ->
            ImportedVocabulary(
                word = word,
                count = values.fold(0) { total, value -> safeAdd(total, value.count) }.coerceAtLeast(1),
                lastUsed = values.maxOf { it.lastUsed }
            )
        }
        .sortedBy { it.word }

    private fun normalizeCorrections(items: List<ImportedCorrection>): List<ImportedCorrection> = items
        .asSequence()
        .mapNotNull { item ->
            val typo = item.typo.trim().lowercase()
            val correction = item.correction.trim()
            if (typo.isBlank() || correction.isBlank()) null
            else ImportedCorrection(typo, correction, item.count.coerceAtLeast(1))
        }
        .groupBy { it.typo }
        .map { (typo, values) ->
            val winner = values.last()
            ImportedCorrection(
                typo = typo,
                correction = winner.correction,
                count = values.fold(0) { total, value -> safeAdd(total, value.count) }.coerceAtLeast(1)
            )
        }
        .sortedBy { it.typo }

    private fun normalizeShortcuts(items: List<PassportShortcut>): List<PassportShortcut> = items
        .asSequence()
        .mapNotNull { item ->
            val shortcut = item.shortcut.trim().lowercase()
            val template = item.template.trim()
            if (shortcut.isBlank() || template.isBlank()) null else PassportShortcut(shortcut, template)
        }
        .associateBy { it.shortcut }
        .values
        .sortedBy { it.shortcut }

    private fun normalizeCommands(items: List<PassportCustomCommand>): List<PassportCustomCommand> = items
        .asSequence()
        .mapNotNull { item ->
            val token = CommandPalette.normalizeToken(item.token) ?: return@mapNotNull null
            val instruction = item.instruction.trim()
            if (instruction.isBlank()) null else PassportCustomCommand(token, instruction)
        }
        .associateBy { it.token }
        .values
        .sortedBy { it.token }

    private fun normalizeAppPersonas(items: List<PassportAppPersona>): List<PassportAppPersona> = items
        .asSequence()
        .mapNotNull { item ->
            val packageName = item.packageName.trim()
            val persona = item.persona.trim()
            if (packageName.isBlank() || persona.isBlank()) null
            else PassportAppPersona(packageName, persona, item.appLabel.trim())
        }
        .associateBy { it.packageName }
        .values
        .sortedBy { it.packageName }

    private fun normalizeLogs(items: List<ImportedLog>): List<ImportedLog> = items
        .asSequence()
        .mapNotNull { item ->
            val text = item.text.trim()
            if (text.isBlank()) null else item.copy(text = text, timestamp = item.timestamp.coerceAtLeast(0L))
        }
        .distinctBy { listOf(it.text, it.sentiment, it.toneScore.toString(), it.timestamp.toString()) }
        .sortedWith(compareBy<ImportedLog> { it.timestamp }.thenBy { it.text })
        .toList()

    private fun mergeVocabulary(current: List<ImportedVocabulary>, incoming: List<ImportedVocabulary>): List<ImportedVocabulary> =
        normalizeVocabulary(current + incoming)

    private fun mergeCorrections(current: List<ImportedCorrection>, incoming: List<ImportedCorrection>): List<ImportedCorrection> =
        normalizeCorrections(current + incoming)

    private fun mergeShortcuts(current: List<PassportShortcut>, incoming: List<PassportShortcut>): List<PassportShortcut> =
        (current + incoming).associateBy { it.shortcut }.values.sortedBy { it.shortcut }

    private fun mergeCommands(current: List<PassportCustomCommand>, incoming: List<PassportCustomCommand>): List<PassportCustomCommand> =
        (current + incoming).associateBy { it.token }.values.sortedBy { it.token }

    private fun mergeAppPersonas(current: List<PassportAppPersona>, incoming: List<PassportAppPersona>): List<PassportAppPersona> =
        (current + incoming).associateBy { it.packageName }.values.sortedBy { it.packageName }

    private fun mergeLogs(current: List<ImportedLog>, incoming: List<ImportedLog>): List<ImportedLog> =
        normalizeLogs(current + incoming)

    private fun safeAdd(left: Int, right: Int): Int {
        val value = left.toLong() + right.toLong()
        return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}