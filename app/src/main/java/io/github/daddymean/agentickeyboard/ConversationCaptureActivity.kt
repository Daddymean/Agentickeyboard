package io.github.daddymean.agentickeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.daddymean.agentickeyboard.util.ConversationCapturePreferences

/** Prominent per-app consent before opening Android's separate service grant. */
class ConversationCaptureActivity : Activity() {
    companion object { const val EXTRA_SOURCE_PACKAGE = "source_package" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        val preferences = ConversationCapturePreferences(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(this).apply {
            textSize = 22f
            text = "Use this conversation"
        })
        layout.addView(TextView(this).apply {
            textSize = 16f
            text = "Lumina uses Android Accessibility access to read visible text only when you tap " +
                "Use conversation in the keyboard. You select the message text before attaching it. " +
                "Capture stays in memory for up to one minute and is cleared on editor/window changes. " +
                "It is not saved to history or used to train your writing model.\n\n" +
                "Reply Coach checks locally. If you choose Reply Ideas, Summarize, Translate or Explain, " +
                "cloud mode sends the selected text through Lumina's existing redaction to Gemini. " +
                "Offline mode uses existing local tools, whose capabilities vary.\n\n" +
                "Android grants broad accessibility capability; Lumina limits capture to apps you allow. " +
                "It does not scroll, send messages or read password/incognito editors. " +
                "Disable this app below or revoke the service in Android settings at any time.\n\n" +
                "App: ${source ?: "Open setup from the keyboard in the app you want to allow."}"
        })
        // KEYBOARD-022: a separate, explicit yes for the automatic read.
        layout.addView(android.widget.CheckBox(this).apply {
            textSize = 16f
            text = "Show the mood of incoming messages automatically"
            isChecked = preferences.isAutoMoodEnabled
            setOnCheckedChangeListener { _, checked -> preferences.isAutoMoodEnabled = checked }
        })
        layout.addView(TextView(this).apply {
            textSize = 14f
            text = "When this is on and the keyboard opens in an app you allowed above, Lumina reads " +
                "the visible conversation once, without a tap. It guesses which message is the latest " +
                "one from the other person (left-side bubble) and estimates its mood (positive, neutral, " +
                "upset or tense) on this phone. Only the mood is shown, for up to one minute. The message " +
                "text is discarded right away: it is not sent anywhere, saved or logged. It never runs in " +
                "password or incognito fields."
        })
        if (!source.isNullOrBlank() && source != packageName) {
            layout.addView(Button(this).apply {
                text = "Allow capture in this app and open settings"
                setOnClickListener {
                    preferences.setAllowed(source, true)
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            })
            layout.addView(Button(this).apply {
                text = "Disable capture in this app"
                setOnClickListener {
                    preferences.setAllowed(source, false)
                    finish()
                }
            })
        }
        layout.addView(Button(this).apply {
            text = "Android accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        layout.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        setContentView(android.widget.ScrollView(this).apply { addView(layout) })
    }
}
