package org.akanework.gramophone.ui.fragments


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import uk.akane.accord.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener

abstract class BaseSettingFragment(
    private val str: Int,
    private val fragmentCreator: () -> BasePreferenceFragment
) : BaseFragment(false) {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_top_settings, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)

        rootView.findViewById<AppBarLayout>(R.id.appbarlayout).enableEdgeToEdgePaddingListener()
        topAppBar.title = getString(str)
        topAppBar.setNavigationOnClickListener {
            // These screens are now reached from the Accord shell, whose back stack is the
            // fragment switcher's rather than the activity's. Fall back to the old behaviour for
            // whatever still hosts them the previous way.
            val activity = requireActivity()
            if (activity is uk.akane.accord.ui.MainActivity) {
                activity.fragmentSwitcherView.popBackTopFragmentIfExists()
            } else {
                activity.supportFragmentManager.popBackStack()
            }
        }

        childFragmentManager
            .beginTransaction()
            .addToBackStack(System.currentTimeMillis().toString())
            .add(R.id.settings, fragmentCreator())
            .commit()

        return rootView
    }
}