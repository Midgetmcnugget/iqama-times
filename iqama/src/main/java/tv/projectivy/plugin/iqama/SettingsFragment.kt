package tv.projectivy.plugin.iqama

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import java.util.Calendar

class SettingsFragment : GuidedStepSupportFragment() {

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
                .id(ID_BACKGROUND)
                .title(R.string.setting_background_title)
                .description(backgroundText())
                .editDescription(Prefs.backgroundUrl)
                .descriptionEditable(true)
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
                    val app = requireContext().applicationContext
                    Thread {
                        BackgroundImage.refresh(app)
                        WallpaperProviderService.notifyProjectivy(app, prefsChanged = true)
                    }.start()
                }
                setDescription(ID_BACKGROUND, backgroundText())
            }
            ID_REFRESH -> refreshNow()
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val i = (action.id - ID_FONT_CHOICE_BASE).toInt()
        if (i == 0) applyFont("")
        else if (i in 1..Fonts.CHOICES.size) applyFont(Fonts.CHOICES[i - 1])
        return true  // close the list
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
        }
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
    }
}
