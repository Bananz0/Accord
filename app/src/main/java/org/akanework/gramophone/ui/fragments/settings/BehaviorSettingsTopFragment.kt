package org.akanework.gramophone.ui.fragments.settings

import android.os.Bundle
import uk.akane.accord.R
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment


class BehaviorSettingsFragment : BaseSettingFragment(R.string.settings_category_behavior,
    { BehaviorSettingsTopFragment() })

/**
 * Two switches, and nothing to wire up behind them.
 *
 * This screen used to carry a MediaStore length filter, an album-cover compatibility toggle that
 * routed to the system permission page for READ_MEDIA_IMAGES, a play-on-launch switch nothing
 * read, and a blacklist row duplicating the one in the main settings list. The first two described
 * a local library this app no longer has - and the cover toggle asked for a permission that is no
 * longer even declared, so it opened a page showing nothing to grant. A control that does nothing
 * is worse than a missing one: it invites the user to change it and then to distrust the rest.
 */
class BehaviorSettingsTopFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
    }
}
