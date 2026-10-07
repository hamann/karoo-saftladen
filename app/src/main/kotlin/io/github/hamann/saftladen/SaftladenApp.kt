package io.github.hamann.saftladen

import android.app.Application
import android.content.Context
import io.github.hamann.saftladen.report.BatteryReporter
import io.github.hamann.saftladen.report.ReportFlushWorker
import io.github.hamann.saftladen.report.ReportStore
import io.github.hamann.saftladen.report.ReportUploader
import io.github.hamann.saftladen.settings.SettingsRepository
import io.hammerhead.karooext.KarooSystemService
import timber.log.Timber
import java.io.File

/**
 * Single place the extension service, the worker and the UI get their collaborators from.
 *
 * Each of those runs in the same process but with independent lifetimes, so they share
 * the settings and the report buffer, and each takes its own Karoo System binding.
 */
class Container(private val context: Context, extensionVersion: String) {
    val settings = SettingsRepository(context)

    val store = ReportStore(File(context.filesDir, "reports"))

    val uploader = ReportUploader(
        store = store,
        settingsRepository = settings,
        karooSystemFactory = { KarooSystemService(context) },
    )

    val reporter = BatteryReporter(
        store = store,
        settingsRepository = settings,
        extensionVersion = extensionVersion,
        karooSystemFactory = { KarooSystemService(context) },
    )
}

class SaftladenApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        // Planted in release builds too: this is a side-loaded extension, and `adb logcat`
        // is the only way to see what it did during a ride.
        Timber.plant(Timber.DebugTree())
        container = Container(this, BuildConfig.VERSION_NAME)
        ReportFlushWorker.schedulePeriodicFlush(this)
    }
}

/** The app's [Container], reachable from any context in the process. */
val Context.container: Container
    get() = (applicationContext as SaftladenApp).container
