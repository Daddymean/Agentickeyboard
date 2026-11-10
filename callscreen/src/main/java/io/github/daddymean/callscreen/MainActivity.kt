package io.github.daddymean.callscreen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** No connection to the IME, twin, or any cloud-backed component. */
class MainActivity : Activity() {
    private lateinit var rules: RuleStore
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules = RuleStore(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun add(view: View) { container.addView(view) }
        fun button(label: Int, action: () -> Unit) {
            add(Button(this).apply { setText(label); setOnClickListener { action() } })
        }
        add(TextView(this).apply { setText(R.string.app_name); textSize = 24f })
        add(TextView(this).apply { setText(R.string.limitations) })
        status = TextView(this)
        add(status)
        button(R.string.role_request) {
            val role = getSystemService(RoleManager::class.java)
            if (role != null && role.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 1)
            } else toast(R.string.role_unavailable)
        }
        button(R.string.contacts_request) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            button(R.string.notifications_request) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
        }
        val actions = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.action_allow), getString(R.string.action_silence), getString(R.string.action_reject)))
            setSelection(rules.unknownAction.ordinal)
        }
        add(actions)
        button(R.string.activate) {
            if (!roleHeld() || !contactsGranted()) {
                toast(R.string.need_permissions)
            } else {
                val selected = Action.entries[actions.selectedItemPosition]
                AlertDialog.Builder(this)
                    .setTitle(R.string.confirm_title).setMessage(R.string.confirm_body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        rules.activate(selected)
                        refresh()
                    }.show()
            }
        }
        button(R.string.observe) { rules.active = false; refresh() }
        val allowlist = EditText(this).apply {
            setHint(R.string.allowlist_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            // Explicit rules may persist; no incoming call ever populates this field.
            setText(rules.allowlist.sorted().joinToString("\n"))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
        }
        add(allowlist)
        button(R.string.save_allowlist) {
            val lines = allowlist.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val normalized = lines.map(::normalize)
            if (normalized.any { it == null }) {
                toast(R.string.invalid_allowlist)
            } else {
                rules.allowlist = normalized.filterNotNull().toSet()
                toast(R.string.saved)
            }
        }
        setContentView(ScrollView(this).apply { addView(container) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
    private fun contactsGranted() = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    private fun roleHeld(): Boolean {
        val role = getSystemService(RoleManager::class.java)
        return role != null && role.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && role.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }
    private fun refresh() {
        val mode = if (rules.active) getString(when (rules.unknownAction) {
            Action.ALLOW -> R.string.action_allow
            Action.SILENCE -> R.string.action_silence
            Action.REJECT -> R.string.action_reject
        }) else getString(R.string.observation)
        status.text = getString(R.string.status, mode,
            getString(if (roleHeld()) R.string.enabled else R.string.disabled),
            getString(if (contactsGranted()) R.string.enabled else R.string.disabled))
    }
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
