package de.fgna.library

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class LibraryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        LocalBookInference.install(this)

        Thread {
            runCatching { BookBackupManager.createBackupIfNeeded(this) }
        }.start()

        val request = PeriodicWorkRequestBuilder<BookBackupWorker>(24, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        lateinit var instance: LibraryApplication
            private set

        private const val WORK_NAME = "daily-books-json-backup"
    }
}
