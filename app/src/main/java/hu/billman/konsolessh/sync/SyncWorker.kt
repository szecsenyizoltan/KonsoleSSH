package hu.billman.konsolessh.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import hu.billman.konsolessh.KonsoleApplication
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a change the app could not upload itself: the process was
 * stopped first, or there was no network. WorkManager runs it once a
 * network is available, also after the app was swiped away.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = (applicationContext as KonsoleApplication).container.syncManager
        return try {
            sync.start()
            sync.syncNow()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (retries(e)) Result.retry() else Result.failure()
        }
    }

    companion object {
        /**
         * Whether trying again later can help: a network or Drive error can
         * pass, but a missing consent, a full Drive or a file the key does
         * not open needs the user, who sees it on the sync screen.
         */
        fun retries(e: Throwable): Boolean = when (e) {
            is DriveAuthorizer.ConsentNeededException,
            is DriveFullException,
            -> false
            is IOException -> true
            else -> false
        }
    }
}

/** Schedules [SyncWorker] as one unique job, so repeated changes keep a single pending upload. */
class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Later than the running app's own short wait, so normally it is withdrawn before it runs.
            .setInitialDelay(DELAY_S, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    private companion object {
        const val NAME = "drive-sync"
        const val DELAY_S = 30L
    }
}
