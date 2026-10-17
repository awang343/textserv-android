package com.textserv

import android.app.Application
import com.textserv.data.Prefs
import com.textserv.notify.Notifier
import com.textserv.sync.SyncScheduler
import java.io.File

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
        val prefs: Prefs by lazy { Prefs(instance) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.createChannels(this)
        SyncScheduler.schedulePeriodic(this)
        cleanCache()
    }

    /** Drops stale PDU / attachment temp files left behind by sends that never called back. */
    private fun cleanCache() {
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        listOf("mms", "view", "outbox").forEach { dir ->
            File(cacheDir, dir).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }
}
