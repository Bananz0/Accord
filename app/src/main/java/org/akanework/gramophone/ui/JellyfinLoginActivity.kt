package org.akanework.gramophone.ui


import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import coil3.dispose
import coil3.load
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import uk.akane.accord.ui.components.enablePasteInto
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlugins
import org.akanework.gramophone.logic.data.jellyfin.JellyfinUserImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.UserDto

/**
 * First-run sign in, in two steps: pick a server, then pick a user.
 *
 * Splitting it that way is what Jellyfin's own clients do, and it earns its keep. Servers announce
 * themselves on the local network, so the usual case needs no typing at all; and by the time a
 * password is asked for, the address has already been proven to answer - which means a failure at
 * that point really is the password, rather than the one error that used to stand in for every
 * possible problem.
 */
class JellyfinLoginActivity : AppCompatActivity() {

    private lateinit var stepServer: View
    private lateinit var stepUser: View

    private lateinit var discoveryEmpty: TextView
    private lateinit var discoveredList: RecyclerView
    private lateinit var discoveryProgress: CircularProgressIndicator
    private lateinit var serverUrlField: TextInputEditText
    private lateinit var connectButton: MaterialButton
    private lateinit var serverProgress: LinearProgressIndicator
    private lateinit var serverStatus: TextView

    private lateinit var serverNameLabel: TextView
    private lateinit var usersLabel: TextView
    private lateinit var userList: RecyclerView
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var usernameField: TextInputEditText
    private lateinit var passwordField: TextInputEditText
    private lateinit var signInButton: MaterialButton
    private lateinit var quickConnectButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView

    /** Set once a server has been resolved; every step-two action needs it. */
    private var serverUrl: String? = null
    private var quickConnectJob: Job? = null

    private val discoveredAdapter = ServerAdapter { connectTo(it.address) }
    private val userAdapter = UserAdapter { user ->
        usernameField.setText(user.name)
        passwordField.requestFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin_login)
        bindViews()

        discoveredList.layoutManager = LinearLayoutManager(this)
        discoveredList.adapter = discoveredAdapter
        userList.layoutManager = LinearLayoutManager(this)
        userList.adapter = userAdapter

        connectButton.setOnClickListener {
            connectTo(serverUrlField.text?.toString()?.trim().orEmpty())
        }
        signInButton.setOnClickListener { signInWithPassword() }
        quickConnectButton.setOnClickListener { startQuickConnect() }
        findViewById<TextView>(R.id.change_server).setOnClickListener { showServerStep() }

        startDiscovery()
    }

    private fun bindViews() {
        stepServer = findViewById(R.id.step_server)
        stepUser = findViewById(R.id.step_user)
        discoveryEmpty = findViewById(R.id.discovery_empty)
        discoveredList = findViewById(R.id.discovered_servers)
        discoveryProgress = findViewById(R.id.discovery_progress)
        serverUrlField = findViewById(R.id.server_url)
        findViewById<TextInputLayout>(R.id.server_url_layout).enablePasteInto(serverUrlField)
        connectButton = findViewById(R.id.connect)
        serverProgress = findViewById(R.id.server_progress)
        serverStatus = findViewById(R.id.server_status)
        serverNameLabel = findViewById(R.id.server_name)
        usersLabel = findViewById(R.id.users_label)
        userList = findViewById(R.id.users)
        usernameLayout = findViewById(R.id.username_layout)
        usernameField = findViewById(R.id.username)
        passwordField = findViewById(R.id.password)
        signInButton = findViewById(R.id.sign_in)
        quickConnectButton = findViewById(R.id.quick_connect)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
    }

    /**
     * Listens for servers announcing themselves on the local network.
     *
     * Jellyfin answers a UDP broadcast on port 7359, which is how its own clients find a server
     * without being told where it is. Results arrive one at a time, so the list fills in as they
     * reply rather than after a fixed wait.
     */
    private fun startDiscovery() {
        JellyfinClientHolder.discovery()
            .discoverLocalServers()
            .onEach { found ->
                discoveredAdapter.add(found.name.orEmpty(), found.address.orEmpty())
                discoveredList.visibility = View.VISIBLE
                discoveryEmpty.visibility = View.GONE
                discoveryProgress.visibility = View.GONE
            }
            .catch { e ->
                // A network that blocks broadcast traffic is ordinary, not an error worth showing;
                // the address field is still there.
                Log.d(TAG, "Local discovery unavailable", e)
                discoveryProgress.visibility = View.GONE
            }
            .launchIn(lifecycleScope)

        // Discovery has no completion signal beyond its timeout, so stop the spinner on our own
        // rather than leaving it turning forever on a network with no Jellyfin on it.
        lifecycleScope.launch {
            delay(DISCOVERY_SPINNER_MS)
            discoveryProgress.visibility = View.GONE
            if (discoveredAdapter.itemCount == 0) discoveryEmpty.visibility = View.VISIBLE
        }
    }

    /** Resolves [input] to a working address, then moves to the user step. */
    private fun connectTo(input: String) {
        if (input.isEmpty()) {
            showServerError(getString(R.string.jellyfin_error_no_server))
            return
        }
        setServerBusy(true)
        serverStatus.visibility = View.VISIBLE
        serverStatus.setText(R.string.jellyfin_finding_server)

        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) { resolveServer(input) }
            if (resolved == null) {
                setServerBusy(false)
                showServerError(getString(R.string.jellyfin_error_no_server_found, input))
                return@launch
            }
            serverUrl = resolved
            val api = JellyfinClientHolder.createUnauthenticatedApi(resolved)
            val users = withContext(Dispatchers.IO) { publicUsers(api) }
            val quickConnectAvailable = withContext(Dispatchers.IO) { quickConnectEnabled(api) }
            setServerBusy(false)
            serverStatus.visibility = View.GONE

            serverNameLabel.text = resolved.toUri().host ?: resolved
            userAdapter.submit(users, resolved)
            // With no public users the server is hiding them, so a name has to be typed. With some,
            // the field is still there for hidden accounts but starts out of the way.
            usersLabel.visibility = if (users.isEmpty()) View.GONE else View.VISIBLE
            quickConnectButton.visibility =
                if (quickConnectAvailable) View.VISIBLE else View.GONE
            showUserStep()
        }
    }

    private fun showServerStep() {
        quickConnectJob?.cancel()
        stepUser.visibility = View.GONE
        stepServer.visibility = View.VISIBLE
        status.visibility = View.GONE
    }

    private fun showUserStep() {
        stepServer.visibility = View.GONE
        stepUser.visibility = View.VISIBLE
    }

    /**
     * Turns what the user typed into an address that actually answers, or null if none does.
     *
     * The SDK expands the input into the candidates worth probing - adding schemes and the default
     * port - and grades each. Score comes first and response time only breaks ties: picking the
     * fastest reply instead resolves a bare IP to whatever else is on port 80, which then returns an
     * HTML page where the API was expected.
     */
    private suspend fun resolveServer(input: String): String? = try {
        JellyfinClientHolder.discovery()
            .getRecommendedServers(input, RecommendedServerInfoScore.OK)
            .minWithOrNull(
                compareBy<RecommendedServerInfo> { it.score.ordinal }.thenBy { it.responseTime }
            )
            ?.address
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve '$input'", e)
        null
    }

    /** The accounts the server chooses to advertise. Empty is valid - it just means type a name. */
    private suspend fun publicUsers(api: ApiClient): List<UserDto> = try {
        api.userApi.getPublicUsers().content
    } catch (e: Exception) {
        Log.d(TAG, "Server does not advertise its users", e)
        emptyList()
    }

    private suspend fun quickConnectEnabled(api: ApiClient): Boolean = try {
        api.quickConnectApi.getQuickConnectEnabled().content
    } catch (e: Exception) {
        Log.d(TAG, "Quick Connect unavailable", e)
        false
    }

    private fun signInWithPassword() {
        val server = serverUrl ?: return
        val username = usernameField.text?.toString()?.trim().orEmpty()
        val password = passwordField.text?.toString().orEmpty()
        if (username.isEmpty()) {
            showError(getString(R.string.jellyfin_error_no_username))
            return
        }
        setBusy(true)
        status.visibility = View.VISIBLE
        status.setText(R.string.jellyfin_signing_in)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runAuthentication(server) {
                    it.userApi.authenticateUserByName(username, password).content
                }
            }
            finishAuthentication(result)
        }
    }

    /**
     * Quick Connect: the server shows a code, the user approves it from a session they are already
     * signed in to, and no password is ever typed here.
     *
     * The code is polled rather than pushed - Jellyfin offers no callback - so this loops until the
     * server reports it authorised or the dialog is dismissed.
     */
    private fun startQuickConnect() {
        val server = serverUrl ?: return
        val api = JellyfinClientHolder.createUnauthenticatedApi(server)
        quickConnectJob?.cancel()
        quickConnectJob = lifecycleScope.launch {
            val initiated = withContext(Dispatchers.IO) {
                try {
                    api.quickConnectApi.initiateQuickConnect().content
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start Quick Connect", e)
                    null
                }
            }
            val secret = initiated?.secret
            val code = initiated?.code
            if (secret.isNullOrBlank() || code.isNullOrBlank()) {
                showError(getString(R.string.jellyfin_quick_connect_failed))
                return@launch
            }

            val dialog = MaterialAlertDialogBuilder(this@JellyfinLoginActivity)
                .setTitle(R.string.jellyfin_quick_connect)
                .setView(buildCodeView(code))
                .setNegativeButton(android.R.string.cancel) { _, _ -> quickConnectJob?.cancel() }
                .setCancelable(false)
                .show()

            try {
                while (true) {
                    delay(QUICK_CONNECT_POLL_MS)
                    val state = withContext(Dispatchers.IO) {
                        try {
                            api.quickConnectApi.getQuickConnectState(secret).content
                        } catch (e: Exception) {
                            // The code expires server-side; treat that as still waiting and let the
                            // user cancel rather than failing under them.
                            Log.d(TAG, "Quick Connect not ready", e)
                            null
                        }
                    }
                    if (state?.authenticated == true) break
                }
                dialog.dismiss()
                setBusy(true)
                status.visibility = View.VISIBLE
                status.setText(R.string.jellyfin_signing_in)
                val result = withContext(Dispatchers.IO) {
                    runAuthentication(server) {
                        it.userApi.authenticateWithQuickConnect(QuickConnectDto(secret)).content
                    }
                }
                finishAuthentication(result)
            } finally {
                dialog.dismiss()
            }
        }
    }

    /**
     * Lays the code out as one large boxed character each.
     *
     * The code has to be read off this screen and typed into another device, so it is set as
     * separated cells rather than a run of digits inside a sentence - the same reason a verification
     * code is never presented as prose.
     */
    private fun buildCodeView(code: String): View {
        val view = layoutInflater.inflate(R.layout.dialog_quick_connect, null)
        val boxes = view.findViewById<android.widget.LinearLayout>(R.id.code_boxes)
        code.forEachIndexed { index, character ->
            val cell = layoutInflater.inflate(R.layout.item_quick_connect_digit, boxes, false)
                    as TextView
            cell.text = character.toString()
            if (index > 0) {
                (cell.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                    resources.getDimensionPixelSize(R.dimen.quick_connect_digit_gap)
            }
            boxes.addView(cell)
        }
        return view
    }

    private fun finishAuthentication(result: LoginResult) = when (result) {
        is LoginResult.Success -> {
            startActivity(Intent(this, uk.akane.accord.ui.MainActivity::class.java))
            finish()
        }

        is LoginResult.Failure -> {
            setBusy(false)
            showError(result.message)
        }
    }

    /**
     * Runs an authentication call and stores whatever session comes back.
     *
     * Password and Quick Connect differ only in the call itself; everything after - checking the
     * token, persisting it, dropping the stale client - is identical, and the error handling is
     * worth having in one place.
     */
    private suspend fun runAuthentication(
        server: String,
        authenticate: suspend (ApiClient) -> AuthenticationResult
    ): LoginResult = try {
        val api = JellyfinClientHolder.createUnauthenticatedApi(server)
        val result = authenticate(api)
        val token = result.accessToken
        val userId = result.user?.id?.toString()
        if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
            LoginResult.Failure(getString(R.string.jellyfin_error_credentials))
        } else {
            JellyfinClientHolder.credentials.saveSession(
                context = this,
                serverUrl = server,
                accessToken = token,
                userId = userId,
                serverName = result.serverId,
            )
            JellyfinClientHolder.invalidate()
            JellyfinPlugins.invalidate()
            // The signed-in user's picture is what the navigation bar draws, so it has to be known
            // before the first screen that has one is shown.
            JellyfinUserImage.refresh()
            LoginResult.Success
        }
    } catch (e: TimeoutException) {
        Log.w(TAG, "Timed out reaching $server", e)
        LoginResult.Failure(getString(R.string.jellyfin_error_unreachable))
    } catch (e: InvalidStatusException) {
        Log.w(TAG, "Server rejected sign in with HTTP ${e.status}", e)
        // The address is already known to answer by this point, so an auth status really does mean
        // the credentials; anything else is still worth reporting as itself.
        if (e.status == 401 || e.status == 403) {
            LoginResult.Failure(getString(R.string.jellyfin_error_credentials))
        } else {
            LoginResult.Failure(getString(R.string.jellyfin_error_http_status, e.status))
        }
    } catch (e: SecureConnectionException) {
        Log.w(TAG, "TLS problem talking to $server", e)
        LoginResult.Failure(getString(R.string.jellyfin_error_tls))
    } catch (e: ApiClientException) {
        Log.w(TAG, "Could not sign in to $server", e)
        LoginResult.Failure(
            getString(
                R.string.jellyfin_error_detail,
                e.cause?.message ?: e.message.orEmpty()
            )
        )
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected failure signing in to $server", e)
        LoginResult.Failure(
            getString(R.string.jellyfin_error_detail, e.message.orEmpty())
        )
    }

    private fun setServerBusy(busy: Boolean) {
        connectButton.isEnabled = !busy
        serverProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun setBusy(busy: Boolean) {
        signInButton.isEnabled = !busy
        quickConnectButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showServerError(message: String) {
        serverStatus.visibility = View.VISIBLE
        serverStatus.text = message
    }

    private fun showError(message: String) {
        setBusy(false)
        status.visibility = View.VISIBLE
        status.text = message
    }

    private sealed interface LoginResult {
        data object Success : LoginResult
        data class Failure(val message: String) : LoginResult
    }

    private class ServerAdapter(
        private val onClick: (Server) -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.Holder>() {

        data class Server(val name: String, val address: String)

        private val servers = mutableListOf<Server>()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val address: TextView = view.findViewById(R.id.address)
        }

        /** Ignores repeats: a server answers the broadcast more than once. */
        fun add(name: String, address: String) {
            if (address.isBlank() || servers.any { it.address == address }) return
            servers += Server(name.ifBlank { address }, address)
            notifyItemInserted(servers.size - 1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_jellyfin_server, parent, false)
        )

        override fun getItemCount() = servers.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val server = servers[position]
            holder.name.text = server.name
            holder.address.text = server.address
            holder.itemView.setOnClickListener { onClick(server) }
        }
    }

    private class UserAdapter(
        private val onClick: (UserDto) -> Unit
    ) : RecyclerView.Adapter<UserAdapter.Holder>() {

        private val users = mutableListOf<UserDto>()
        private var serverUrl: String = ""

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: ImageView = view.findViewById(R.id.avatar)
            val name: TextView = view.findViewById(R.id.name)
        }

        fun submit(newUsers: List<UserDto>, server: String) {
            users.clear()
            users.addAll(newUsers)
            serverUrl = server
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_jellyfin_user, parent, false)
        )

        override fun getItemCount() = users.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val user = users[position]
            holder.name.text = user.name
            val tag = user.primaryImageTag
            // Both branches set every property they depend on. Rows are recycled, so anything left
            // over from the previous user - a photo behind a glyph, a glyph stretched edge to edge -
            // shows up as a rendering bug that only appears after scrolling.
            if (tag != null) {
                holder.avatar.setPadding(0, 0, 0, 0)
                holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.avatar.imageTintList = null
                // Circle-cropped by the loader rather than by the view. An ImageView draws its own
                // src, so clipToOutline against a round background does not touch it - the photo
                // keeps its square corners and spills outside the circle.
                holder.avatar.load(
                    "$serverUrl/Users/${user.id}/Images/Primary?tag=$tag"
                ) {
                    transformations(CircleCropTransformation())
                }
            } else {
                val inset = holder.itemView.resources
                    .getDimensionPixelSize(R.dimen.jellyfin_avatar_glyph_inset)
                holder.avatar.dispose()
                holder.avatar.setImageResource(R.drawable.ic_person_small)
                holder.avatar.imageTintList =
                    ColorStateList.valueOf(
                        holder.itemView.context.getColor(R.color.onSurfaceColorInactive)
                    )
                holder.avatar.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.avatar.setPadding(inset, inset, inset, inset)
            }
            holder.itemView.setOnClickListener { onClick(user) }
        }
    }

    companion object {
        private const val TAG = "JellyfinLoginActivity"

        /** How long to keep the discovery spinner up before assuming nothing will answer. */
        private const val DISCOVERY_SPINNER_MS = 4_000L

        /** Jellyfin's own clients poll Quick Connect at about this rate. */
        private const val QUICK_CONNECT_POLL_MS = 2_000L
    }
}
