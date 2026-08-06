/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic


import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.media.ThumbnailUtils
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.NullRequestDataException
import coil3.request.allowHardware
import coil3.size.pxOrElse
import coil3.util.Logger
import uk.akane.accord.BuildConfig
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.ui.BugHandlerActivity
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * GramophoneApplication
 *
 * @author AkaneTan, nift4
 */
class GramophoneApplication : Application(), SingletonImageLoader.Factory, Thread.UncaughtExceptionHandler {

    lateinit var prefs: SharedPreferences
        private set

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Cheap: only records the application context. The credential store and SDK behind it are
        // built lazily, off the main thread.
        JellyfinClientHolder.init(this)

        if (BuildConfig.DEBUG) {
            // Use StrictMode to find anti-pattern issues
            // penaltyLog() without penaltyDialog(): One UI's own IdsController.openIdsWindow()
            // calls deleteSharedPreferences() on the main thread during every single activity
            // resume, so the dialog fired constantly with a stack containing no app frames at all.
            // Violations are still detected and logged, they just no longer block the UI.
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                    .detectAll().permitDiskReads() // permit disk reads due to media3 setMetadata() TODO extra player thread
                    .penaltyLog().build())
            // Deliberately not detectAll(): it bundles detectCleartextNetwork() and
            // detectUntaggedSockets(), which combined with penaltyDeath() killed the process on
            // every single HTTP request once the library moved to Jellyfin. Plain HTTP to a
            // self-hosted server on the LAN is the normal case here, and OkHttp does not tag its
            // sockets. Everything else worth catching is kept, still fatal.
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .detectLeakedRegistrationObjects()
                    .detectFileUriExposure()
                    .detectContentUriWithoutPermission()
                    .detectImplicitDirectBoot()
                    .detectCredentialProtectedWhileLocked()
                    .detectIncorrectContextUse()
                    .detectUnsafeIntentLaunch()
                    .penaltyLog().penaltyDeath().build())
        }

        // This is a separate thread to avoid disk read on main thread and improve startup time
        Thread {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            // Set application theme when launching.
            when (prefs.getString("theme_mode", "0")) {
                "0" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }

                "1" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }

                "2" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
            }

            // https://github.com/androidx/media/issues/805
            if (needsMissingOnDestroyCallWorkarounds()) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
            }
        }.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            // Artwork now comes over the network, so it has to survive process death or every
            // scroll re-downloads it. Jellyfin image URLs carry api_key in the query, so a plain
            // client is enough - no auth interceptor needed.
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_artwork"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            )
            .allowHardware(false)
            .components {
                add(OkHttpNetworkFetcherFactory())
                if (hasScopedStorageV1()) {
                    add(Fetcher.Factory { data, options, _ ->
                        if (data !is Pair<*, *>) return@Factory null
                        val size = data.second
                        if (size !is Size?) return@Factory null
                        val file = data.first as? File ?: return@Factory null
                        return@Factory Fetcher {
                            ImageFetchResult(
                                ThumbnailUtils.createAudioThumbnail(file, options.size.let {
                                    Size(it.width.pxOrElse { size?.width ?: 10000 },
                                        it.height.pxOrElse { size?.height ?: 10000 })
                                }, null).asImage(), true, DataSource.DISK
                            )
                        }
                    })
                }
            }
            .run {
                if (!BuildConfig.DEBUG) this else
                    logger(object : Logger {
                        override var minLevel = Logger.Level.Verbose
                        override fun log(
                            tag: String,
                            level: Logger.Level,
                            message: String?,
                            throwable: Throwable?
                        ) {
                            if (level < minLevel) return
                            val priority = level.ordinal + 2 // obviously the best way to do it
                            if (message != null) {
                                Log.println(priority, tag, message)
                            }
                            // Let's keep the log readable and ignore normal events' stack traces.
                            if (throwable != null && throwable !is NullRequestDataException && (throwable !is IOException || throwable.message != "No album art found")) {
                                Log.println(priority, tag, Log.getStackTraceString(throwable))
                            }
                        }
                    })
            }
            .build()
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val exceptionMessage = Log.getStackTraceString(e)
        val threadName = t.name
        // Also emit to logcat. Handing the trace to BugHandlerActivity and calling exitProcess()
        // means Android never writes it to the crash buffer, so without this a crash is invisible
        // to adb and can only be read off the device screen.
        Log.e("GramophoneApplication", "Uncaught exception on thread $threadName", e)
        val intent = Intent(this, BugHandlerActivity::class.java)
        intent.putExtra("exception_message", exceptionMessage)
        intent.putExtra("thread", threadName)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        exitProcess(10)
    }
}
