package io.github.daddymean.agentickeyboard.util

import androidx.room.withTransaction
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Result shown after a user-confirmed passport import. */
data class KeyboardPassportApplyResult(
    val incomingRecordCount: Int,
    val affectedCategories: Set<PassportCategory>,
    val mode: KeyboardPassportImportMode
)

/**
 * Local companion-app bridge from the pure passport format to Room/settings.
 * File selection and confirmation remain in the UI; this class never opens a
 * picker, stores a passphrase, uploads data, or applies an unconfirmed preview.
 */
class KeyboardPassportTransfer(
    private val database: AppDatabase,
    private val repository: KeyboardRepository,
    private val settings: KeyboardSettings
) {

    suspend fun createPassport(options: KeyboardPassportOptions): String = withContext(Dispatchers.IO) {
        KeyboardPassport.create(
            input = snapshot().toInput(),
            options = options
        )
    }

    suspend fun apply(
        opened: KeyboardPassportOpenResult.Success,
        mode: KeyboardPassportImportMode
    ): KeyboardPassportApplyResult = withContext(Dispatchers.IO) {
        val current = snapshot()
        val plan = KeyboardPassportImportPlanner.plan(
            current = current,
            incoming = opened.payload,
            categories = opened.preview.categories,
            mode = mode
        )

        // One transaction for every category: an import either lands whole or
        // leaves the existing personal model untouched. Without this, a failure
        // or process death between a clear and its re-insert would destroy
        // learned data that the passport was only meant to update.
        //
        // The rows to delete come from `current`, captured before the write, so
        // no Room Flow is collected inside the transaction.
        database.withTransaction {
            if (PassportCategory.VOCABULARY in plan.affectedCategories) {
                repository.clearVocabulary()
                plan.snapshot.vocabulary.forEach { repository.insertWord(it) }
            }

            if (PassportCategory.CORRECTIONS in plan.affectedCategories) {
                repository.clearCorrections()
                if (plan.snapshot.corrections.isNotEmpty()) {
                    repository.insertCorrections(plan.snapshot.corrections)
                }
            }

            if (PassportCategory.SHORTCUTS in plan.affectedCategories) {
                current.shortcuts.forEach { repository.deleteShortcut(it) }
                plan.snapshot.shortcuts.forEach { repository.insertShortcut(it) }
            }

            if (PassportCategory.CUSTOM_COMMANDS in plan.affectedCategories) {
                current.customCommands.forEach { repository.deleteCustomCommandById(it.id) }
                plan.snapshot.customCommands.forEach { repository.insertCustomCommand(it) }
            }

            if (PassportCategory.APP_PERSONAS in plan.affectedCategories) {
                current.appPersonas.forEach { repository.deleteAppPersona(it.packageName) }
                plan.snapshot.appPersonas.forEach {
                    repository.setAppPersona(it.packageName, it.persona, it.appLabel)
                }
            }

            if (PassportCategory.WRITING_LOGS in plan.affectedCategories) {
                repository.clearLogs()
                plan.snapshot.writingLogs.forEach { repository.insertLog(it) }
            }
        }

        if (PassportCategory.PERSONA_PREFERENCE in plan.affectedCategories) {
            settings.persona = plan.snapshot.personaPreference
        }

        KeyboardPassportApplyResult(
            incomingRecordCount = plan.incomingRecordCount,
            affectedCategories = plan.affectedCategories,
            mode = mode
        )
    }

    private suspend fun snapshot(): KeyboardPassportSnapshot = KeyboardPassportSnapshot(
        personaPreference = settings.persona,
        vocabulary = readAllVocabulary(),
        corrections = repository.allCorrections.first(),
        shortcuts = repository.allShortcuts.first(),
        customCommands = repository.allCustomCommands.first(),
        appPersonas = repository.allAppPersonas.first(),
        writingLogs = repository.allLogs.first()
    )

    /**
     * Passport export must move the complete dictionary, not only the 150-word
     * prediction shelf exposed by KeyboardRepository.topVocabulary.
     */
    private fun readAllVocabulary(): List<UserVocabulary> {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT word, count, lastUsed FROM user_vocabulary ORDER BY word COLLATE NOCASE ASC"
        )
        return cursor.use {
            val wordIndex = it.getColumnIndexOrThrow("word")
            val countIndex = it.getColumnIndexOrThrow("count")
            val lastUsedIndex = it.getColumnIndexOrThrow("lastUsed")
            buildList {
                while (it.moveToNext()) {
                    add(
                        UserVocabulary(
                            word = it.getString(wordIndex),
                            count = it.getInt(countIndex),
                            lastUsed = it.getLong(lastUsedIndex)
                        )
                    )
                }
            }
        }
    }

    private fun KeyboardPassportSnapshot.toInput(): KeyboardPassportInput = KeyboardPassportInput(
        personaPreference = personaPreference,
        vocabulary = vocabulary,
        corrections = corrections,
        shortcuts = shortcuts,
        customCommands = customCommands,
        appPersonas = appPersonas,
        writingLogs = writingLogs
    )
}