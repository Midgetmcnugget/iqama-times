package tv.projectivy.plugin.iqama

import android.os.Bundle
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
                .id(ID_POSITION)
                .title(R.string.setting_position_title)
                .description(positionText())
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
            ID_POSITION -> {
                Prefs.positionBottom = !Prefs.positionBottom
                setDescription(ID_POSITION, positionText())
                WallpaperProviderService.notifyProjectivy(requireContext(), prefsChanged = true)
            }
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

    private fun positionText() = if (Prefs.positionBottom) "Bottom of the screen" else "Top of the screen"
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
        private const val ID_POSITION = 2L
        private const val ID_CLOCK = 3L
        private const val ID_BACKGROUND = 4L
        private const val ID_REFRESH = 5L
    }
}
