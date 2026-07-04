package com.example

import android.app.Application
import android.util.Log
import com.example.db.AppDatabase
import com.example.db.KeyboardRepository
import com.example.util.KeyboardSettings
import com.example.util.SwipeToTypeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgenticKeyboardApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { KeyboardRepository(database) }
    val settings by lazy { KeyboardSettings(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
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
