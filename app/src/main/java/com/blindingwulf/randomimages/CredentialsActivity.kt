package com.blindingwulf.randomimages

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Credentials live in the app's private store, encrypted with a key from the
 * hardware keystore where the device supports it (see [SecureStore]).
 */
class CredentialsActivity : AppCompatActivity() {

    private lateinit var store: SecureStore

    private lateinit var e621User: TextInputEditText
    private lateinit var e621Key: TextInputEditText
    private lateinit var faUser: TextInputEditText
    private lateinit var faCookie: TextInputEditText
    private lateinit var testButton: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var e621Layout: TextInputLayout
    private lateinit var faLayout: TextInputLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credentials)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = SecureStore(this)
        e621User = findViewById(R.id.e621User)
        e621Key = findViewById(R.id.e621Key)
        faUser = findViewById(R.id.faUser)
        faCookie = findViewById(R.id.faCookie)
        testButton = findViewById(R.id.testButton)
        statusView = findViewById(R.id.credentialStatus)
        e621Layout = findViewById(R.id.e621UserLayout)
        faLayout = findViewById(R.id.faUserLayout)

        val current = store.load()
        e621User.setText(current.e621Username)
        e621Key.setText(current.e621ApiKey)
        faUser.setText(current.faUsername)
        faCookie.setText(current.faCookie)

        findViewById<MaterialSwitch>(R.id.showValues).setOnCheckedChangeListener { _, checked ->
            val type = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            for (field in listOf(e621Key, faCookie)) {
                field.inputType = type
                field.setSelection(field.text?.length ?: 0)
            }
        }

        testButton.setOnClickListener {
            save()
            testConnections()
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            save()
            finish()
        }
        findViewById<MaterialButton>(R.id.clearButton).setOnClickListener {
            store.delete()
            for (field in listOf(e621User, e621Key, faUser, faCookie)) field.setText("")
            statusView.text = getString(R.string.creds_cleared)
        }
    }

    private fun save() {
        store.save(
            SecureStore.Credentials(
                e621Username = e621User.text?.toString()?.trim().orEmpty(),
                e621ApiKey = e621Key.text?.toString()?.trim().orEmpty(),
                faUsername = faUser.text?.toString()?.trim().orEmpty(),
                faCookie = faCookie.text?.toString()?.trim().orEmpty(),
            )
        )
        statusView.text = if (store.encrypted) {
            getString(R.string.creds_saved_encrypted)
        } else {
            getString(R.string.creds_saved_plain)
        }
    }

    private fun testConnections() {
        testButton.isEnabled = false
        statusView.setText(R.string.creds_testing)
        val engine = RivApplication.from(this).engine
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { engine.testConnections() }
            statusView.text = results.entries.joinToString("\n") { (site, auth) ->
                "${if (auth.ok) "OK" else "FAIL"}  $site: ${auth.message}"
            }
            testButton.isEnabled = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            save()
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
