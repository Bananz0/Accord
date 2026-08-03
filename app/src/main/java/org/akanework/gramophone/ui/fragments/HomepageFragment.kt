package org.akanework.gramophone.ui.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.applyGeneralMenuItem
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.utils.RecommendationFactory
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinItemResolver
import org.akanework.gramophone.ui.JellyfinLoginActivity
import org.akanework.gramophone.ui.LibraryViewModel
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.adapters.HomepageCarouselAdapter
import org.akanework.gramophone.ui.adapters.RecommendAdapter
import org.akanework.gramophone.ui.components.ItemSnapHelper


class HomepageFragment : BaseFragment(null), Observer<RecommendationFactory.RecommendList> {

    private lateinit var appBarLayout: AppBarLayout
    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private lateinit var recommendTitle: TextView
    private lateinit var recommendRecyclerView: RecyclerView
    private lateinit var recommendAdapter: RecommendAdapter

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = layoutInflater.inflate(R.layout.fragment_homepage, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val recyclerView = rootView.findViewById<RecyclerView>(R.id.recyclerview_top)
        val nestedScrollView = rootView.findViewById<NestedScrollView>(R.id.nested)

        recommendRecyclerView = rootView.findViewById(R.id.rv_r)
        recommendTitle = rootView.findViewById(R.id.recommend)
        recommendAdapter = RecommendAdapter(requireActivity() as MainActivity)

        libraryViewModel.recommendList.observeForever(this)

        appBarLayout = rootView.findViewById(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()

        // The header uses the upstream Accord circular actions instead of the stock overflow
        // affordance. The menu itself is unchanged - the ellipsis button just opens it - so
        // applyGeneralMenuItem() below still wires up refresh/equalizer/settings as before.
        val moreButton = rootView.findViewById<ImageButton>(R.id.nav_action_more)
        moreButton.setOnClickListener { topAppBar.showOverflowMenu() }
        rootView.findViewById<ImageButton>(R.id.nav_action_account).setOnClickListener {
            showAccountSheet()
        }

        val syncIndicator = rootView.findViewById<CircularProgressIndicator>(R.id.sync_indicator)
        libraryViewModel.isSyncing.observe(viewLifecycleOwner) { syncing ->
            syncIndicator.visibility = if (syncing == true) View.VISIBLE else View.GONE
        }

        nestedScrollView.enableEdgeToEdgePaddingListener()

        recyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.adapter = HomepageCarouselAdapter(requireActivity() as MainActivity)

        recommendRecyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        recommendRecyclerView.adapter = recommendAdapter

        ItemSnapHelper().attachToRecyclerView(recyclerView)
        ItemSnapHelper().attachToRecyclerView(recommendRecyclerView)

        ViewCompat.setNestedScrollingEnabled(recyclerView, false)
        ViewCompat.setNestedScrollingEnabled(recommendRecyclerView, false)

        topAppBar.applyGeneralMenuItem(this, libraryViewModel)

        return rootView
    }

    /**
     * Shows which server this device is signed in to, and offers to sign out.
     *
     * Sending an already-signed-in user to the login screen would be a dead end, so the account
     * button leads here instead. Reading the credential store opens keystore-backed preferences,
     * which is disk work, so the lookup happens off the main thread.
     */
    private fun showAccountSheet() {
        viewLifecycleOwner.lifecycleScope.launch {
            val server = withContext(Dispatchers.IO) {
                JellyfinClientHolder.credentials.let { it.serverUrl to it.userId }
            }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.jellyfin_account_title)
                .setMessage(getString(R.string.jellyfin_account_message, server.first ?: "-"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.jellyfin_sign_out) { _, _ -> signOut() }
                .show()
        }
    }

    private fun signOut() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                JellyfinClientHolder.credentials.apply {
                    clearSession()
                    publishSessionFlag(requireContext().applicationContext)
                }
                JellyfinClientHolder.invalidate()
                // The cached library belongs to the account being signed out of; leaving it would
                // show another user's collection after the next sign in.
                AppDatabase.getInstance(requireContext()).cachedSongDao().deleteAll()
                JellyfinItemResolver.clear()
            }
            if (!isAdded) return@launch
            startActivity(Intent(requireContext(), JellyfinLoginActivity::class.java))
            requireActivity().finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        libraryViewModel.recommendList.removeObserver(this)
    }

    override fun onChanged(value: RecommendationFactory.RecommendList) {
        value.getTitle(libraryViewModel).let {
            recommendTitle.text = it
        }
        recommendAdapter.updateList(value.recommendationList.toMutableList())
    }
}