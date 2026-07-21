from pathlib import Path


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


# KeyboardSettings persistence -------------------------------------------------
settings_path = Path("app/src/main/java/io/github/daddymean/agentickeyboard/util/KeyboardSettings.kt")
settings = settings_path.read_text()
settings = one(
    settings,
    '        const val KEY_STAT_SHORTCUT_EXPANSIONS = "stat_shortcut_expansions"\n',
    '        const val KEY_STAT_SHORTCUT_EXPANSIONS = "stat_shortcut_expansions"\n'
    '        const val KEY_MASTERY_ENABLED = "mastery_enabled"\n'
    '        const val KEY_MASTERY_STATE = "mastery_state"\n',
    "mastery settings keys",
)
settings = one(
    settings,
    '''    var statShortcutExpansions: Int
        get() = prefs.getInt(KEY_STAT_SHORTCUT_EXPANSIONS, 0)
        set(value) = prefs.edit().putInt(KEY_STAT_SHORTCUT_EXPANSIONS, value).apply()

''',
    '''    var statShortcutExpansions: Int
        get() = prefs.getInt(KEY_STAT_SHORTCUT_EXPANSIONS, 0)
        set(value) = prefs.edit().putInt(KEY_STAT_SHORTCUT_EXPANSIONS, value).apply()

    var isMasteryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTERY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTERY_ENABLED, value).apply()

    var masteryState: String
        get() = prefs.getString(KEY_MASTERY_STATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MASTERY_STATE, value).apply()

''',
    "mastery settings properties",
)
settings_path.write_text(settings)


# KeyboardViewModel state and event hooks -------------------------------------
vm_path = Path("app/src/main/java/io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt")
vm = vm_path.read_text()
vm = one(
    vm,
    'import io.github.daddymean.agentickeyboard.util.KeyboardSettings\n',
    'import io.github.daddymean.agentickeyboard.util.KeyboardSettings\n'
    'import io.github.daddymean.agentickeyboard.util.mastery.KeyboardMastery\n'
    'import io.github.daddymean.agentickeyboard.util.mastery.MasteryEvent\n'
    'import io.github.daddymean.agentickeyboard.util.mastery.MasteryState\n'
    'import io.github.daddymean.agentickeyboard.util.mastery.MasteryStateCodec\n',
    "mastery imports",
)
vm = one(
    vm,
    '''    val usageStats = _usageStats.asStateFlow()

''',
    '''    val usageStats = _usageStats.asStateFlow()

    private val _isMasteryEnabled = MutableStateFlow(settings?.isMasteryEnabled ?: true)
    val isMasteryEnabled = _isMasteryEnabled.asStateFlow()

    private val initialMasteryState = MasteryStateCodec.decode(
        settings?.masteryState,
        enabledFallback = _isMasteryEnabled.value
    ).copy(enabled = _isMasteryEnabled.value)
    private val _masteryState = MutableStateFlow(initialMasteryState)
    val masteryState = _masteryState.asStateFlow()

''',
    "mastery state flows",
)
vm = one(
    vm,
    '            KeyboardSettings.KEY_TARGET_LANG -> _targetLanguage.value = s.targetLanguage\n',
    '            KeyboardSettings.KEY_TARGET_LANG -> _targetLanguage.value = s.targetLanguage\n'
    '            KeyboardSettings.KEY_MASTERY_ENABLED -> {\n'
    '                _isMasteryEnabled.value = s.isMasteryEnabled\n'
    '                _masteryState.value = _masteryState.value.copy(enabled = s.isMasteryEnabled)\n'
    '            }\n'
    '            KeyboardSettings.KEY_MASTERY_STATE -> {\n'
    '                _masteryState.value = MasteryStateCodec.decode(\n'
    '                    s.masteryState, enabledFallback = s.isMasteryEnabled\n'
    '                ).copy(enabled = s.isMasteryEnabled)\n'
    '            }\n',
    "mastery preference listener",
)
vm = one(
    vm,
    '''        val instruction = RESULT_REFINEMENTS[adjustment] ?: adjustment
        dismissResults()
''',
    '''        val instruction = RESULT_REFINEMENTS[adjustment] ?: adjustment
        recordMastery(MasteryEvent.REFINEMENT)
        dismissResults()
''',
    "refinement mastery event",
)
vm = one(
    vm,
    '''    fun getLogRetentionDays(): Int = settings?.logRetentionDays ?: 30

''',
    '''    fun getLogRetentionDays(): Int = settings?.logRetentionDays ?: 30

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

''',
    "mastery controls",
)
vm = one(
    vm,
    '''    fun recordAutoCorrectionStat() = updateStats { it.copy(autoCorrections = it.autoCorrections + 1) }
    fun recordSwipeWordStat() = updateStats { it.copy(swipeWords = it.swipeWords + 1) }
    fun recordAiApplyStat() = updateStats { it.copy(aiApplies = it.aiApplies + 1) }
    fun recordShortcutExpansionStat() = updateStats { it.copy(shortcutExpansions = it.shortcutExpansions + 1) }
''',
    '''    private fun recordMastery(event: MasteryEvent) {
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
''',
    "usage-stat mastery hooks",
)
vm_path.write_text(vm)


# Style Hub card insertion -----------------------------------------------------
main_path = Path("app/src/main/java/io/github/daddymean/agentickeyboard/MainActivity.kt")
main = main_path.read_text()
main = one(
    main,
    'import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel\n',
    'import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel\n'
    'import io.github.daddymean.agentickeyboard.ui.KeyboardMasteryCard\n',
    "mastery card import",
)
main = one(
    main,
    '''        // Persona Selection Card
''',
    '''        item {
            KeyboardMasteryCard(viewModel)
        }

        // Persona Selection Card
''',
    "mastery card insertion",
)
main_path.write_text(main)
