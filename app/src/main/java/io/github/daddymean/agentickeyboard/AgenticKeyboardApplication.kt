package io.github.daddymean.agentickeyboard

import android.app.Application
import android.util.Log
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.network.GeminiManager
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.MlKitOnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgenticKeyboardApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { KeyboardRepository(database) }
    val settings by lazy { KeyboardSettings(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // On-device Gemini Nano (via AICore) for the offline AI path. Constructing it
    // kicks off the async feature-status check / model download; inference itself
    // runs out-of-process in AICore, so the keyboard process stays lean.
    val onDeviceAi: OnDeviceAi by lazy { MlKitOnDeviceAi(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        GeminiManager.onDeviceAi = onDeviceAi
        // Load the frequency-ranked swipe dictionary off the main thread.
        appScope.launch {
            try {
                resources.openRawResource(R.raw.wordlist).bufferedReader().useLines { lines ->
                    SwipeToTypeEngine.loadDictionary(lines.toList())
                }
            } catch (e: Exception) {
                Log.w("AgenticKeyboardApp", "Swipe dictionary unavailable, using built-in fallback", e)
            }
        }
    }
}
