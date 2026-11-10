package io.github.daddymean.agentickeyboard

import android.app.Application
import android.util.Log
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.network.GeminiManager
import io.github.daddymean.agentickeyboard.util.GeminiKeyStore
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.LearnedRuleCleanup
import io.github.daddymean.agentickeyboard.ondevice.CascadingOnDeviceAi
import io.github.daddymean.agentickeyboard.ondevice.LocalGemmaController
import io.github.daddymean.agentickeyboard.ondevice.OnDeviceEngineRouter
import io.github.daddymean.agentickeyboard.util.MlKitOnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.SafeLog
import io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class AgenticKeyboardApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { KeyboardRepository(database) }
    val settings by lazy { KeyboardSettings(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val localGemma: LocalGemmaController by lazy { LocalGemmaController(this, settings, appScope) }

    private val nanoAi: OnDeviceAi by lazy { MlKitOnDeviceAi(this, appScope) }

    // Nano first, then a user-downloaded Gemma 4 E2B model, then heuristics.
    // Inference for Nano still runs in AICore; Gemma loads only after opt-in.
    val onDeviceAi: OnDeviceAi by lazy {
        CascadingOnDeviceAi(
            nano = nanoAi,
            local = localGemma.asOnDeviceAi,
            context = {
                OnDeviceEngineRouter.Context(
                    privacyBlocked = GeminiManager.privacyBlocked,
                    nanoStatus = nanoAi.status.value,
                    localEnabled = localGemma.isEnabled(),
                    localReady = localGemma.isReady()
                )
            }
        )
    }

    private fun readWords(id: Int): List<String> =
        resources.openRawResource(id).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

    override fun onCreate() {
        super.onCreate()
        GeminiManager.onDeviceAi = onDeviceAi
        GeminiManager.userApiKey = GeminiKeyStore.load(this)
        appScope.launch {
            nanoAi.status.collect { (onDeviceAi as? CascadingOnDeviceAi)?.refreshStatus() }
        }
        // Load the frequency-ranked swipe dictionary off the main thread.
        appScope.launch {
            try {
                resources.openRawResource(R.raw.wordlist).bufferedReader().useLines { lines ->
                    val words = lines.toList()
                    SwipeToTypeEngine.loadDictionary(words)
                    // KEYBOARD-020: common words missing from the frequency list come
                    // from SCOWL (see assets/third_party/SCOWL-Copyright.txt).
                    // KEYBOARD-011: everyday modern words missing from both lists (hand-written).
                    val extra = readWords(R.raw.spelling_modern) + readWords(R.raw.spelling_extra)
                    val knownOnly = readWords(R.raw.spelling_known_only)
                    io.github.daddymean.agentickeyboard.util.LocalSpelling.shared =
                        io.github.daddymean.agentickeyboard.util.LocalSpelling(words.take(10_000), knownOnly, extra)
                }
            } catch (e: Exception) {
                SafeLog.w("AgenticKeyboardApp", "Swipe dictionary unavailable, using built-in fallback", e)
            }
            // KEYBOARD-008: once, remove harmful rules learned before the fix (backed up first).
            try {
                val spelling = io.github.daddymean.agentickeyboard.util.LocalSpelling.shared
                if (spelling.isLoaded) {
                    val removed = LearnedRuleCleanup.runOnce(
                        getSharedPreferences(LearnedRuleCleanup.PREFS_NAME, MODE_PRIVATE),
                        repository,
                        spelling::isKnownWord
                    )
                    if (removed > 0) Log.i("AgenticKeyboardApp", "Removed $removed harmful learned rules (backed up)")
                }
            } catch (e: Exception) {
                SafeLog.w("AgenticKeyboardApp", "Learned-rule cleanup skipped", e)
            }
        }
    }
}
