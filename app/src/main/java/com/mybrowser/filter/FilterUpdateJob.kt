package com.mybrowser.filter

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.mybrowser.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Persisted Android scheduler, constrained to unmetered networks; no polling service. */
class FilterUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var update: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if ((application as App).restoreBlocked) return false
        val subscriptions = (application as App).filterSubscriptions
        if (!subscriptions.autoUpdate.value) return false
        update = scope.launch {
            val success = subscriptions.update()
            jobFinished(params, !success)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        update?.cancel()
        update = null
        return !(application as App).restoreBlocked && (application as App).filterSubscriptions.autoUpdate.value
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = 4107
        fun schedule(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, FilterUpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(TimeUnit.DAYS.toMillis(7), TimeUnit.DAYS.toMillis(1))
                .setBackoffCriteria(TimeUnit.MINUTES.toMillis(30), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build())
        }
    }
}
