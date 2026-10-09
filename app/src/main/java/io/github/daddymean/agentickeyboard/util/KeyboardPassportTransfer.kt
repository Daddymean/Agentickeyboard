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

    /**
     * @param unverifiedReplaceAcknowledged the user explicitly accepted that an
     *   unverified (legacy) file will replace existing data; required for REPLACE
     *   of such a file. See [KeyboardPassportImportPolicy].
     */
    suspend fun apply(
        opened: KeyboardPassportOpenResult.Success,
        mode: KeyboardPassportImportMode,
        unverifiedReplaceAcknowledged: Boolean = false
    ): KeyboardPassportApplyResult = withContext(Dispatchers.IO) {
        check(KeyboardPassportImportPolicy.canConfirm(opened.preview, mode, unverifiedReplaceAcknowledged)) {
            if (opened.preview.compatible) {
                "Replacing data with an unverified file needs explicit confirmation."
            } else {
                "This passport version is not compatible with this app."
            }
        }
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
        // no Room Flow is collected inside the transaction. Each category is
        // written with batch statements rather than one DAO call per record.
        database.withTransaction {
            if (PassportCategory.VOCABULARY in plan.affectedCategories) {
                repository.clearVocabulary()
                repository.insertWords(plan.snapshot.vocabulary)
            }

            if (PassportCategory.CORRECTIONS in plan.affectedCategories) {
                repository.clearCorrections()
                if (plan.snapshot.corrections.isNotEmpty()) {
                    repository.insertCorrections(plan.snapshot.corrections)
                }
            }

            if (PassportCategory.SHORTCUTS in plan.affectedCategories) {
                repository.deleteShortcutsByIds(current.shortcuts.map { it.id })
                repository.insertShortcuts(plan.snapshot.shortcuts)
            }

            if (PassportCategory.CUSTOM_COMMANDS in plan.affectedCategories) {
                repository.deleteCustomCommandsByIds(current.customCommands.map { it.id })
                repository.insertCustomCommands(plan.snapshot.customCommands)
            }

            if (PassportCategory.APP_PERSONAS in plan.affectedCategories) {
                repository.deleteAppPersonas(current.appPersonas.map { it.packageName })
                repository.upsertAppPersonas(plan.snapshot.appPersonas)
            }

            if (PassportCategory.WRITING_LOGS in plan.affectedCategories) {
                repository.clearLogs()
                repository.insertLogs(plan.snapshot.writingLogs)
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