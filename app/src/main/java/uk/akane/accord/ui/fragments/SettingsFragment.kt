package uk.akane.accord.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import org.akanework.gramophone.ui.JellyfinLoginActivity
import org.akanework.gramophone.ui.fragments.settings.BlacklistSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.AppearanceSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.AudioSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.BehaviorSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.DownloadsSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.ExperimentalSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.LidarrSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.ScrobblingSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.SpotifySettingsFragment
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar

/**
 * Settings, reachable again.
 *
 * The old shell's settings navigated by adding fragments to a container that only existed in that
 * activity, so once the Accord shell became the entry point every settings screen - Jellyfin,
 * scrobbling, Spotify, Lidarr, downloads - was stranded with no way in. This is a plain list of
 * rows in Accord's navigation that pushes those same screens onto the switcher, so their contents
 * and behaviour are untouched.
 */
class SettingsFragment : Fragment() {

    private val mainActivity
        get() = requireActivity() as MainActivity

    private class Row(
        @param:StringRes val title: Int,
        @param:StringRes val summary: Int? = null,
        val open: (SettingsFragment) -> Unit
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_accord_settings, container, false)

        val navigationBar = rootView.findViewById<NavigationBar>(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            mainActivity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
        navigationBar.attach(rootView.findViewById<NestedScrollView>(R.id.scrollContainer))

        val rows = rootView.findViewById<LinearLayout>(R.id.settings_rows)
        ROWS.forEach { row ->
            val view = inflater.inflate(R.layout.layout_settings_row, rows, false)
            view.findViewById<TextView>(R.id.row_title).setText(row.title)
            row.summary?.let {
                view.findViewById<TextView>(R.id.row_summary).apply {
                    setText(it)
                    visibility = View.VISIBLE
                }
            }
            view.setOnClickListener { row.open(this) }
            rows.addView(view)
        }

        return rootView
    }

    private fun push(fragment: Fragment) {
        mainActivity.fragmentSwitcherView.addFragmentToCurrentStack(fragment)
    }

    companion object {
        private val ROWS = listOf(
            Row(R.string.jellyfin_login_title, R.string.settings_jellyfin_summary) {
                it.startActivity(Intent(it.requireContext(), JellyfinLoginActivity::class.java))
            },
            Row(R.string.settings_category_appearance) { it.push(AppearanceSettingsFragment()) },
            Row(R.string.settings_category_behavior) { it.push(BehaviorSettingsFragment()) },
            Row(R.string.settings_audio) { it.push(AudioSettingsFragment()) },
            Row(R.string.settings_category_downloads) { it.push(DownloadsSettingsFragment()) },
            Row(R.string.settings_category_scrobbling) { it.push(ScrobblingSettingsFragment()) },
            Row(R.string.settings_category_spotify) { it.push(SpotifySettingsFragment()) },
            Row(R.string.settings_category_lidarr) { it.push(LidarrSettingsFragment()) },
            Row(R.string.settings_blacklist) { it.push(BlacklistSettingsFragment()) },
            Row(R.string.settings_experimental_settings) {
                it.push(ExperimentalSettingsFragment())
            },
        )
    }
}
