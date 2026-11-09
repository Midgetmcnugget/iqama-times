package uk.iqama.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.Calendar

/**
 * Settings for one widget (when opened by tapping or reconfiguring it) or the defaults (when
 * opened from the app icon). Each widget has its own mosque; offsets are kept per mosque.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var mosqueField: MaterialAutoCompleteTextView
    private lateinit var status: TextView
    private lateinit var iqamahSpinner: Spinner
    private lateinit var maghribSpinner: Spinner

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    /** The mosque currently selected on this screen. */
    private var slug = Prefs.DEFAULT_MOSQUE

    private val iqamahChoices = listOf(-1, 5, 10, 15, 20, 25, 30)
    private val maghribChoices = listOf(0, 5, 10, 15)
    private var bindingSpinners = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)  // use the phone's own colours
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        // Draw edge to edge and pad for the status bar, navigation bar and keyboard ourselves,
        // so the top of the settings is never hidden under the bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.app_name)

        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // When opened to set up a new widget, say OK straight away so it's added even if the
            // person just presses back (it then uses the default mosque).
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        slug = if (forWidget()) prefs.mosqueFor(widgetId) else prefs.defaultMosque

        findViewById<TextView>(R.id.heading).setText(if (forWidget()) R.string.heading_widget else R.string.heading_default)
        mosqueField = findViewById(R.id.mosque)
        status = findViewById(R.id.status)
        iqamahSpinner = findViewById(R.id.iqamah_offset)
        maghribSpinner = findViewById(R.id.maghrib_offset)

        // Mosque picker: recent mosques in the list, or type/paste a new one.
        mosqueField.setText(slug)
        refreshMosqueList()
        mosqueField.setOnItemClickListener { parent, _, pos, _ ->
            val chosen = slugFromItem(parent.getItemAtPosition(pos).toString())
            mosqueField.setText(chosen, false)
            selectMosque(chosen, refresh = true)
        }
        // Always offer the whole list when the field is tapped.
        mosqueField.setOnClickListener { mosqueField.showDropDown() }
        mosqueField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveAndRefresh(); true } else false
        }

        bindSwitch(R.id.show_name, prefs.showName) { prefs.showName = it }
        bindSwitch(R.id.show_sunrise, prefs.showSunrise) { prefs.showSunrise = it }
        bindSwitch(R.id.clock12, prefs.clock12h) { prefs.clock12h = it }

        iqamahSpinner.adapter = adapter(iqamahChoices.map { if (it < 0) "Off (show adhan only)" else "$it minutes after adhan" })
        maghribSpinner.adapter = adapter(maghribChoices.map { if (it == 0) "Same time as adhan" else "$it minutes after adhan" })
        iqamahSpinner.onItemSelectedListener = OnSelected { pos ->
            if (!bindingSpinners) { prefs.mosque(slug).iqamahOffset = iqamahChoices[pos]; changed() }
        }
        maghribSpinner.onItemSelectedListener = OnSelected { pos ->
            if (!bindingSpinners) { prefs.mosque(slug).maghribOffset = maghribChoices[pos]; changed() }
        }
        bindOffsets()

        bindAppearance()

        findViewById<Button>(R.id.save).setOnClickListener { saveAndRefresh() }
        findViewById<Button>(R.id.exact).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }

        showStatus()
        RefreshWorker.schedule(this)
        if (Fetcher.isStale(prefs.mosque(slug))) refresh()
    }

    override fun onResume() {
        super.onResume()
        // Hide the "exact timing" button once it's allowed (or not needed on this Android).
        val exactNeeded = !IqamaWidget.canBeExact(this)
        findViewById<View>(R.id.exact).visibility = if (exactNeeded) View.VISIBLE else View.GONE
        findViewById<View>(R.id.exact_help).visibility = if (exactNeeded) View.VISIBLE else View.GONE
        IqamaWidget.updateAll(this)
    }

    private fun forWidget() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    /** Colour swatches and the transparency slider; changes show on the widget straight away. */
    private fun bindAppearance() {
        val row = findViewById<LinearLayout>(R.id.swatches)
        val colourName = findViewById<TextView>(R.id.colour_name)
        val dp = resources.displayMetrics.density
        val choices = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Appearance.THEME to "Phone theme")
            Appearance.SWATCHES.forEach { add(it.id to it.label) }
        }
        val circles = mutableListOf<View>()

        fun paint() {
            choices.forEachIndexed { i, (id, _) ->
                val selected = id == prefs.background
                (circles[i].background as GradientDrawable).setStroke(
                    ((if (selected) 3 else 1) * dp).toInt(),
                    if (selected) MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF3F7F74.toInt())
                    else 0x55888888
                )
            }
            colourName.text = choices.firstOrNull { it.first == prefs.background }?.second ?: ""
        }

        choices.forEach { (id, label) ->
            val circle = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Appearance.colorFor(this@SettingsActivity, id))
                }
                contentDescription = label
                layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
                    marginEnd = (10 * dp).toInt()
                }
                setOnClickListener {
                    prefs.background = id
                    paint()
                    changed()
                }
            }
            circles.add(circle)
            row.addView(circle)
        }
        paint()

        val label = findViewById<TextView>(R.id.opacity_label)
        val slider = findViewById<Slider>(R.id.opacity)
        slider.value = (prefs.opacity / 5 * 5).toFloat()
        label.text = getString(R.string.opacity_label, 100 - prefs.opacity)
        slider.addOnChangeListener { _, value, _ ->
            prefs.opacity = value.toInt()
            label.text = getString(R.string.opacity_label, 100 - prefs.opacity)
            IqamaWidget.updateAll(this)
        }
    }

    private fun bindSwitch(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        val s = findViewById<MaterialSwitch>(id)
        s.isChecked = value
        s.setOnCheckedChangeListener { _, on -> set(on); changed() }
    }

    private fun refreshMosqueList() {
        val items = prefs.recentMosques.map { s ->
            val name = prefs.mosque(s).name
            if (name.equals(Prefs.titleFromSlug(s), ignoreCase = true)) s else "$name  ·  $s"
        }
        mosqueField.setAdapter(AllItemsAdapter(items))
    }

    /** "Chingford Mosque  ·  chingford-mosque" -> "chingford-mosque". */
    private fun slugFromItem(item: String) = Prefs.slug(item.substringAfterLast("·").trim())

    /** List adapter that never filters, so every saved mosque shows whatever is typed. */
    private inner class AllItemsAdapter(val items: List<String>) :
        ArrayAdapter<String>(this@SettingsActivity, android.R.layout.simple_list_item_1, items) {
        private val all = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = items; count = items.size }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }
        override fun getFilter(): android.widget.Filter = all
    }

    /** Switches this screen (and the widget) to [newSlug]. */
    private fun selectMosque(newSlug: String, refresh: Boolean) {
        slug = Prefs.slug(newSlug)
        if (forWidget()) prefs.setMosqueFor(widgetId, slug) else prefs.defaultMosque = slug
        prefs.rememberMosque(slug)
        refreshMosqueList()
        bindOffsets()
        changed()
        if (refresh && Fetcher.isStale(prefs.mosque(slug))) refresh()
    }

    private fun bindOffsets() {
        val m = prefs.mosque(slug)
        bindingSpinners = true
        iqamahSpinner.setSelection(iqamahChoices.indexOf(m.iqamahOffset).coerceAtLeast(0), false)
        maghribSpinner.setSelection(maghribChoices.indexOf(m.maghribOffset).coerceAtLeast(0), false)
        iqamahSpinner.post { bindingSpinners = false }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun changed() {
        IqamaWidget.updateAll(this)
        showStatus()
    }

    private fun saveAndRefresh() {
        val typed = mosqueField.text.toString().trim()
        if (typed.isNotEmpty() && slugFromItem(typed) != slug) selectMosque(slugFromItem(typed), refresh = false)
        else prefs.rememberMosque(slug)
        mosqueField.setText(slug, false)
        refresh()
    }

    private fun refresh() {
        val m = prefs.mosque(slug)
        status.text = getString(R.string.refreshing, m.name)
        val fetching = slug
        Fetcher.fetch(this, fetching) { _ ->
            IqamaWidget.updateAll(applicationContext)
            if (!isFinishing && !isDestroyed) {
                refreshMosqueList()  // the page may have given the mosque's proper name
                if (fetching == slug) showStatus()
            }
        }
    }

    private fun showStatus() {
        val m = prefs.mosque(slug)
        val prayers = PrayerTimes.fromJson(m.timesJson)
        val sb = StringBuilder()
        if (m.lastError.isNotBlank()) sb.append("Last attempt failed: ${m.lastError}\n\n")
        if (prayers.isEmpty()) {
            sb.append("No times yet for ${m.name}.")
        } else {
            val cal = Calendar.getInstance().apply { timeInMillis = m.fetchedAt }
            sb.append("${m.name}, updated ${m.fetchedDay} at ")
                .append(PrayerTimes.format(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), false))
                .append(":\n")
            IqamaWidget.columns(m, Calendar.getInstance()).forEach { c ->
                val ad = c.athan?.let { "adhan " + PrayerTimes.format(it, prefs.clock12h) }
                val iq = c.iqamah?.let { "iqamah " + PrayerTimes.format(it, prefs.clock12h) }
                sb.append("${c.label}: ").append(listOfNotNull(ad, iq).joinToString(", ")).append('\n')
            }
            val incomplete = PrayerTimes.DAILY.any { k ->
                val p = prayers.firstOrNull { it.key == k }
                p == null || p.athan == null || p.iqamah == null
            }
            if (incomplete && m.iqamahOffset < 0 && m.pageSnippet.isNotBlank()) {
                sb.append("\nThis mosque's page doesn't list some iqamah times. Set \"Iqamah after adhan\" above, ")
                    .append("or send a screenshot of this page text to fix the reader:\n")
                    .append(m.pageSnippet)
            }
        }
        status.text = sb.toString().trimEnd()
    }

    /** Small adapter so a lambda can be used as a Spinner listener. */
    private class OnSelected(val block: (Int) -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
