package uk.iqama.widget

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Calendar

/** Opened from the app icon or by tapping the widget. Changes apply to the widget straight away. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var mosque: EditText
    private lateinit var status: TextView

    // Spinner choices: label to minutes (-1 = off)
    private val iqamahChoices = listOf(-1, 5, 10, 15, 20, 25, 30)
    private val maghribChoices = listOf(0, 5, 10, 15)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        mosque = findViewById(R.id.mosque)
        status = findViewById(R.id.status)
        val showName = findViewById<MaterialSwitch>(R.id.show_name)
        val clock12 = findViewById<MaterialSwitch>(R.id.clock12)
        val iqamah = findViewById<Spinner>(R.id.iqamah_offset)
        val maghrib = findViewById<Spinner>(R.id.maghrib_offset)

        mosque.setText(prefs.mosque)
        mosque.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveAndRefresh(); true } else false
        }

        showName.isChecked = prefs.showName
        showName.setOnCheckedChangeListener { _, on -> prefs.showName = on; IqamaWidget.updateAll(this) }
        clock12.isChecked = prefs.clock12h
        clock12.setOnCheckedChangeListener { _, on -> prefs.clock12h = on; IqamaWidget.updateAll(this); showStatus() }

        iqamah.adapter = adapter(iqamahChoices.map { if (it < 0) "Off (show adhan only)" else "$it minutes after adhan" })
        iqamah.setSelection(iqamahChoices.indexOf(prefs.iqamahOffset).coerceAtLeast(0))
        iqamah.onItemSelectedListener = OnSelected { pos ->
            prefs.iqamahOffset = iqamahChoices[pos]; IqamaWidget.updateAll(this); showStatus()
        }

        maghrib.adapter = adapter(maghribChoices.map { if (it == 0) "Same time as adhan" else "$it minutes after adhan" })
        maghrib.setSelection(maghribChoices.indexOf(prefs.maghribOffset).coerceAtLeast(0))
        maghrib.onItemSelectedListener = OnSelected { pos ->
            prefs.maghribOffset = maghribChoices[pos]; IqamaWidget.updateAll(this); showStatus()
        }

        findViewById<Button>(R.id.save).setOnClickListener { saveAndRefresh() }
        showStatus()

        // Opening the app is a good moment to catch up if the times are old.
        if (Fetcher.isStale(prefs)) saveAndRefresh()
        RefreshWorker.schedule(this)
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun saveAndRefresh() {
        val m = mosque.text.toString().trim()
        if (m.isNotEmpty() && m != prefs.mosque) {
            prefs.mosque = m
            prefs.timesJson = ""    // the old mosque's times no longer apply
            prefs.fetchedDay = ""
        }
        status.text = getString(R.string.refreshing)
        Fetcher.fetch(this) { _ ->
            IqamaWidget.updateAll(applicationContext)
            if (!isFinishing && !isDestroyed) showStatus()
        }
    }

    private fun showStatus() {
        val prayers = PrayerTimes.fromJson(prefs.timesJson)
        val sb = StringBuilder()
        if (prefs.lastError.isNotBlank()) sb.append("Last attempt failed: ${prefs.lastError}\n\n")
        if (prayers.isEmpty()) {
            sb.append("No times yet.")
        } else {
            val cal = Calendar.getInstance().apply { timeInMillis = prefs.fetchedAt }
            sb.append("Times for ${prefs.mosqueTitle}, updated ${prefs.fetchedDay} at ")
                .append(PrayerTimes.format(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), false))
                .append(":\n")
            IqamaWidget.columns(prefs, Calendar.getInstance()).forEach { c ->
                val iq = c.iqamah?.let { "iqamah " + PrayerTimes.format(it, prefs.clock12h) }
                val ad = c.athan?.let { "adhan " + PrayerTimes.format(it, prefs.clock12h) }
                sb.append("${c.label}: ").append(listOfNotNull(iq, ad).joinToString(", ")).append('\n')
            }
            // If something's missing, show what the page said so the reader can be fixed.
            val incomplete = PrayerTimes.DAILY.any { k ->
                val p = prayers.firstOrNull { it.key == k }
                p == null || p.athan == null || p.iqamah == null
            }
            if (incomplete && prefs.iqamahOffset < 0 && prefs.pageSnippet.isNotBlank()) {
                sb.append("\nSome iqamah times aren't on the mosque's page. Page text read:\n")
                    .append(prefs.pageSnippet)
            }
        }
        status.text = sb.toString().trimEnd()
    }

    /** Small adapter so a lambda can be used as a Spinner listener. */
    private class OnSelected(val block: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) =
            block(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }
}
