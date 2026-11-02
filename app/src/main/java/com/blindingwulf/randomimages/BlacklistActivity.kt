package com.blindingwulf.randomimages

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Edits the content-filter term list. Saving an empty field stores an empty
 * string, which Settings.effectiveBlacklist() treats as "use the shipped
 * defaults" — that is also what Restore defaults does explicitly.
 */
class BlacklistActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var input: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = RivApplication.from(this)
        settings = app.settings
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blacklist)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        input = findViewById(R.id.termsInput)
        val stored = settings.blacklistTerms
        input.setText(stored.ifEmpty { Blacklist.DEFAULT.joinToString("\n") })

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            settings.blacklistTerms = input.text?.toString()?.trim().orEmpty()
            // The next fetch re-reads the list; a running loop must not keep
            // serving results picked under the old one.
            app.engine.pendingRetarget = true
            toast(getString(R.string.blacklist_saved))
            finish()
        }
        findViewById<MaterialButton>(R.id.restoreButton).setOnClickListener {
            input.setText(Blacklist.DEFAULT.joinToString("\n"))
            settings.blacklistTerms = ""
            app.engine.pendingRetarget = true
            toast(getString(R.string.blacklist_restored))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
