package com.example

import android.app.Application
import com.example.db.AppDatabase
import com.example.db.KeyboardRepository

class AgenticKeyboardApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { KeyboardRepository(database) }
}
