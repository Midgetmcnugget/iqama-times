package tv.projectivy.plugin.iqama

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import java.util.Calendar

class SettingsFragment : GuidedStepSupportFragment() {

    /** The picture picked in a file explorer, kept in case storage permission is needed first. */
    private var pendingUri: Uri? = null

    private val pickPicture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        pendingUri = uri
        importPicture(uri, askedPermission = false)
    }

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val uri = pendingUri ?: return@registerForActivityResult
        if (granted) importPicture(uri, askedPermission = true)
        else setDescription(ID_BACKGROUND_FILE, getString(R.string.background_permission_denied))
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        getString(R.string.plugin_name),
        "v${BuildConfig.VERSION_NAME}\n\n${getString(R.string.plugin_description)}\n\n${timesSummary()}",
        getString(R.string.settings),
        AppCompatResources.getDrawable(requireActivity(), R.drawable.ic_launcher)
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        Prefs.init(requireContext())
        val ctx = requireContext()

        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_MOSQUE)
                .title(R.string.setting_mosque_title)
                .description(Prefs.mosque)
                .editDescription(Prefs.mosque)
                .descriptionEditable(true)
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_SHOW_NAME)
                .title(R.string.setting_show_name_title)
                .description(showNameText())
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_FONT)
                .title(R.string.setting_font_title)
                .description(fontText())
                .subActions(fontChoices())
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_FONT_NAME)
                .title(R.string.setting_font_name_title)
                .description(getString(R.string.setting_font_name_desc))
                .editDescription(Prefs.fontName)
                .descriptionEditable(true)
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_MOVE_DOWN)
                .title(R.string.setting_move_down_title)
                .description(positionText())
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_MOVE_UP)
                .title(R.string.setting_move_up_title)
                .description(positionText())
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_POSITION_RESET)
                .title(R.string.setting_position_reset_title)
                .description(getString(R.string.setting_position_reset_desc, Prefs.PANEL_TOP_DEFAULT))
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_CLOCK)
                .title(R.string.setting_clock_title)
                .description(clockText())
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_IQAMAH_OFFSET)
                .title(R.string.setting_iqamah_offset_title)
                .description(iqamahOffsetText())
                .subActions(minuteChoices(ID_IQAMAH_OFFSET_BASE, listOf(-1, 5, 10, 15, 20, 25, 30)))
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_MAGHRIB_OFFSET)
                .title(R.string.setting_maghrib_offset_title)
                .description(maghribOffsetText())
                .subActions(minuteChoices(ID_MAGHRIB_OFFSET_BASE, listOf(0, 5, 10, 15)))
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_BACKGROUND_FILE)
                .title(R.string.setting_background_file_title)
                .description(backgroundFileText())
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_BACKGROUND)
                .title(R.string.setting_background_title)
                .description(backgroundText())
                .editDescription(Prefs.backgroundUrl)
                .descriptionEditable(true)
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_BACKGROUND_CLEAR)
                .title(R.string.setting_background_clear_title)
                .description(getString(R.string.setting_background_clear_desc))
                .build()
        )
        actions.add(
            GuidedAction.Builder(ctx)
                .id(ID_REFRESH)
                .title(R.string.setting_refresh_title)
                .description(statusText())
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_MOSQUE -> {
                val value = action.editDescription?.toString()?.trim().orEmpty()
                if (value.isNotEmpty() && value != Prefs.mosque) {
                    Prefs.mosque = value
                    Prefs.timesJson = ""   // old mosque's times no longer apply
                    Prefs.fetchedDay = ""
                    refreshNow()
                }
                setDescription(ID_MOSQUE, Prefs.mosque)
            }
            ID_SHOW_NAME -> {
                Prefs.showMosqueName = !Prefs.showMosqueName
                setDescription(ID_SHOW_NAME, showNameText())
                // The panel changes height, so refresh the position text too.
                movePanel(Prefs.panelTop)
            }
            ID_FONT_NAME -> {
                val value = action.editDescription?.toString()?.trim().orEmpty()
                if (value != Prefs.fontName) applyFont(value)
                else setDescription(ID_FONT_NAME, getString(R.string.setting_font_name_desc))
            }
            ID_MOVE_DOWN -> movePanel(Prefs.panelTop + Prefs.PANEL_STEP)
            ID_MOVE_UP -> movePanel(Prefs.panelTop - Prefs.PANEL_STEP)
            ID_POSITION_RESET -> movePanel(Prefs.PANEL_TOP_DEFAULT)
            ID_CLOCK -> {
                Prefs.clock12h = !Prefs.clock12h
                setDescription(ID_CLOCK, clockText())
                WallpaperProviderService.notifyProjectivy(requireContext(), prefsChanged = true)
            }
            ID_BACKGROUND -> {
                val value = action.editDescription?.toString()?.trim().orEmpty()
                if (value != Prefs.backgroundUrl) {
                    Prefs.backgroundUrl = value
                    Prefs.backgroundFile = ""   // a link replaces a chosen file
                    val app = requireContext().applicationContext
                    Thread {
                        BackgroundImage.refresh(app)
                        WallpaperProviderService.notifyProjectivy(app, prefsChanged = true)
                    }.start()
                }
                setDescription(ID_BACKGROUND, backgroundText())
                setDescription(ID_BACKGROUND_FILE, backgroundFileText())
            }
            ID_BACKGROUND_FILE -> openFileExplorer()
            ID_BACKGROUND_CLEAR -> {
                BackgroundImage.clear(requireContext())
                setDescription(ID_BACKGROUND, backgroundText())
                setDescription(ID_BACKGROUND_FILE, backgroundFileText())
                WallpaperProviderService.notifyProjectivy(requireContext(), prefsChanged = true)
            }
            ID_REFRESH -> refreshNow()
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val id = action.id
        if (id >= ID_IQAMAH_OFFSET_BASE && id < ID_IQAMAH_OFFSET_BASE + 1000) {
            Prefs.iqamahOffset = (id - ID_IQAMAH_OFFSET_BASE - 100).toInt()
            setDescription(ID_IQAMAH_OFFSET, iqamahOffsetText())
            WallpaperProviderService.notifyProjectivy(requireContext(), prefsChanged = true)
            return true
        }
        if (id >= ID_MAGHRIB_OFFSET_BASE && id < ID_MAGHRIB_OFFSET_BASE + 1000) {
            Prefs.maghribOffset = (id - ID_MAGHRIB_OFFSET_BASE - 100).toInt()
            setDescription(ID_MAGHRIB_OFFSET, maghribOffsetText())
            WallpaperProviderService.notifyProjectivy(requireContext(), prefsChanged = true)
            return true
        }
        val i = (action.id - ID_FONT_CHOICE_BASE).toInt()
        if (i == 0) applyFont("")
        else if (i in 1..Fonts.CHOICES.size) applyFont(Fonts.CHOICES[i - 1])
        return true  // close the list
    }

    /** Sub-options for a minutes setting; -1 means "Off". The value is encoded in the id. */
    private fun minuteChoices(base: Long, values: List<Int>): List<GuidedAction> {
        val ctx = requireContext()
        return values.map { v ->
            GuidedAction.Builder(ctx)
                .id(base + 100 + v)
                .title(
                    when {
                        v < 0 -> getString(R.string.iqamah_offset_off)
                        v == 0 -> getString(R.string.iqamah_same_as_adhan)
                        else -> getString(R.string.iqamah_minutes_after, v)
                    }
                )
                .build()
        }
    }

    private fun iqamahOffsetText() =
        if (Prefs.iqamahOffset < 0) getString(R.string.setting_iqamah_offset_off_desc)
        else getString(R.string.setting_iqamah_offset_on_desc, Prefs.iqamahOffset)

    private fun maghribOffsetText() =
        if (Prefs.maghribOffset == 0) getString(R.string.iqamah_same_as_adhan)
        else getString(R.string.iqamah_minutes_after, Prefs.maghribOffset)

    /** Opens a file explorer app on the TV to pick a picture. */
    private fun openFileExplorer() {
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val pm = requireContext().packageManager
        val chosen = when {
            get.resolveActivity(pm) != null -> Intent.createChooser(get, getString(R.string.background_pick_with))
            open.resolveActivity(pm) != null -> open
            else -> null
        }
        if (chosen == null) {
            setDescription(ID_BACKGROUND_FILE, getString(R.string.background_no_explorer))
            Toast.makeText(requireContext(), R.string.background_no_explorer, Toast.LENGTH_LONG).show()
            return
        }
        try {
            pickPicture.launch(chosen)
        } catch (_: ActivityNotFoundException) {
            setDescription(ID_BACKGROUND_FILE, getString(R.string.background_no_explorer))
        }
    }

    /** Copies the picked picture in on a background thread, asking for storage access if needed. */
    private fun importPicture(uri: Uri, askedPermission: Boolean) {
        setDescription(ID_BACKGROUND_FILE, getString(R.string.background_copying))
        val app = requireContext().applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val error = BackgroundImage.importFile(app, uri)
            main.post {
                if (error == null) {
                    pendingUri = null
                    WallpaperProviderService.notifyProjectivy(app, prefsChanged = true)
                    if (isAdded) {
                        setDescription(ID_BACKGROUND_FILE, backgroundFileText())
                        setDescription(ID_BACKGROUND, backgroundText())
                    }
                } else if (isAdded) {
                    // Some file explorers hand over a plain file path, which needs storage access.
                    val needsPermission = error == "PERMISSION" || uri.scheme == "file"
                    if (needsPermission && !askedPermission) {
                        askStorage.launch(
                            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                            else Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                    } else {
                        setDescription(ID_BACKGROUND_FILE, getString(R.string.background_import_failed,
                            if (error == "PERMISSION") getString(R.string.background_permission_denied) else error))
                    }
                }
            }
        }.start()
    }

    private fun fontChoices(): List<GuidedAction> {
        val ctx = requireContext()
        val names = listOf(getString(R.string.font_default)) + Fonts.CHOICES
        return names.mapIndexed { i, name ->
            GuidedAction.Builder(ctx)
                .id(ID_FONT_CHOICE_BASE + i)
                .title(name)
                .build()
        }
    }

    /**
     * Downloads the font first, and only switches to it if it worked, so a typo never leaves
     * the wallpaper without a font.
     */
    private fun applyFont(name: String) {
        val app = requireContext().applicationContext
        if (name.isBlank()) {
            Prefs.fontName = ""
            setDescription(ID_FONT, fontText())
            WallpaperProviderService.notifyProjectivy(app, prefsChanged = true)
            return
        }
        setDescription(ID_FONT, getString(R.string.font_downloading, name))
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = Fonts.prepare(app, name)
            main.post {
                if (ok) {
                    Prefs.fontName = name
                    WallpaperProviderService.notifyProjectivy(app, prefsChanged = true)
                }
                if (isAdded) {
                    setDescription(ID_FONT, if (ok) fontText() else getString(R.string.font_failed, name, fontText()))
                    findActionById(ID_FONT_NAME)?.editDescription = Prefs.fontName
                    setDescription(ID_FONT_NAME, getString(R.string.setting_font_name_desc))
                }
            }
        }.start()
    }

    private fun refreshNow() {
        setDescription(ID_REFRESH, getString(R.string.refreshing))
        val app = requireContext().applicationContext
        MasjidboxFetcher.refresh(app) { ok ->
            WallpaperProviderService.notifyProjectivy(app, prefsChanged = true)
            if (isAdded) {
                setDescription(ID_REFRESH, if (ok) statusText() else getString(R.string.refresh_failed) + " (" + Prefs.lastError + ")")
                guidanceStylist.descriptionView?.text =
                    "v${BuildConfig.VERSION_NAME}\n\n${getString(R.string.plugin_description)}\n\n${timesSummary()}"
            }
        }
    }

    private fun setDescription(id: Long, text: CharSequence) {
        val a = findActionById(id) ?: return
        a.description = text
        notifyActionChanged(findActionPositionById(id))
    }

    /** Moves the panel and asks Projectivy to redraw, so the change shows straight away. */
    private fun movePanel(newTop: Int) {
        Prefs.panelTop = newTop
        val text = positionText()
        setDescription(ID_MOVE_DOWN, text)
        setDescription(ID_MOVE_UP, text)
        WallpaperProviderService.notifyProjectivy(requireContext(), prefsChanged = true)
    }

    private fun positionText(): String {
        val top = Prefs.panelTop
        val where = when (top) {
            Prefs.PANEL_TOP_MIN -> " (as high as it goes)"
            Prefs.panelTopMax -> " (as low as it goes)"
            else -> ""
        }
        return "Now $top px from the top$where. Each click moves it ${Prefs.PANEL_STEP} px."
    }
    private fun showNameText() = if (Prefs.showMosqueName) "Shown above the times" else "Hidden – the panel shrinks to fit"
    private fun fontText() = if (Prefs.fontName.isBlank()) getString(R.string.font_default) else "Using ${Prefs.fontName}"
    private fun clockText() = if (Prefs.clock12h) "12-hour (1:30)" else "24-hour (13:30)"
    private fun backgroundText() = Prefs.backgroundUrl.ifBlank { getString(R.string.setting_background_empty) }
    private fun backgroundFileText() =
        if (Prefs.backgroundFile.isNotBlank()) getString(R.string.background_using_file, Prefs.backgroundFile)
        else getString(R.string.setting_background_file_desc)

    private fun statusText(): String {
        if (Prefs.fetchedAt == 0L) return "Not fetched yet. Click to fetch."
        val cal = Calendar.getInstance().apply { timeInMillis = Prefs.fetchedAt }
        val at = PrayerTimes.format(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), false)
        return "Last updated ${Prefs.fetchedDay} at $at. Click to refresh."
    }

    private fun timesSummary(): String {
        val prayers = PrayerTimes.fromJson(Prefs.timesJson)
        if (prayers.isEmpty()) return "No times yet."
        val twelve = Prefs.clock12h
        return prayers.joinToString("\n") { p ->
            val iq = p.iqamah?.let { "iqamah " + PrayerTimes.format(it, twelve) }
            val ad = p.athan?.let { "adhan " + PrayerTimes.format(it, twelve) }
            PrayerTimes.label(p.key) + (if (p.key == "JUMUAH2") " 2" else "") + ": " + listOfNotNull(iq, ad).joinToString(", ")
        } + troubleshooting(prayers)
    }

    /**
     * If any daily prayer is missing its adhan or iqamah, show the page text the plugin read,
     * so a photo of this screen is enough to fix the reader for a new page layout.
     */
    private fun troubleshooting(prayers: List<Prayer>): String {
        val incomplete = PrayerTimes.DAILY.any { k ->
            val p = prayers.firstOrNull { it.key == k }
            p == null || p.athan == null || p.iqamah == null
        }
        if (!incomplete || Prefs.pageSnippet.isBlank()) return ""
        return "\n\nSome times are missing. Page text read:\n" + Prefs.pageSnippet
    }

    companion object {
        private const val ID_MOSQUE = 1L
        private const val ID_MOVE_DOWN = 2L
        private const val ID_MOVE_UP = 6L
        private const val ID_POSITION_RESET = 7L
        private const val ID_CLOCK = 3L
        private const val ID_BACKGROUND = 4L
        private const val ID_REFRESH = 5L
        private const val ID_SHOW_NAME = 8L
        private const val ID_FONT = 9L
        private const val ID_FONT_NAME = 10L
        private const val ID_FONT_CHOICE_BASE = 100L
        private const val ID_IQAMAH_OFFSET = 11L
        private const val ID_MAGHRIB_OFFSET = 12L
        private const val ID_BACKGROUND_FILE = 13L
        private const val ID_BACKGROUND_CLEAR = 14L
        private const val ID_IQAMAH_OFFSET_BASE = 10_000L
        private const val ID_MAGHRIB_OFFSET_BASE = 20_000L
    }
}
