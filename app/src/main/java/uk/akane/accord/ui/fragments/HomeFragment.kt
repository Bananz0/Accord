package uk.akane.accord.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import uk.akane.accord.Accord
import uk.akane.accord.R
import uk.akane.accord.ui.components.NavigationBar

class HomeFragment: Fragment() {
    private lateinit var navigationBar: NavigationBar
    private lateinit var subtitle: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_home, container, false)
        navigationBar = rootView.findViewById(R.id.navigation_bar)
        subtitle = rootView.findViewById(R.id.subtitle)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }

        observeLibrarySync()
        return rootView
    }

    /**
     * A Jellyfin sync takes the better part of a minute on a large library and, until now, gave no
     * sign it was happening - the library simply sat there looking empty. The home subtitle doubles
     * as that indicator, and goes back to its normal text when the sync finishes.
     */
    private fun observeLibrarySync() {
        val jellyfin = (requireActivity().application as Accord).jellyfinReader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                jellyfin.syncProgress.collect { progress ->
                    subtitle.text = when {
                        progress == null -> getString(R.string.recommendations)
                        progress.second > 0 ->
                            getString(R.string.sync_progress, progress.first, progress.second)
                        else -> getString(R.string.sync_in_progress)
                    }
                }
            }
        }
    }
}
