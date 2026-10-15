package com.stockpile.volume.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stockpile.volume.data.ProjectStore
import com.stockpile.volume.data.Settings
import com.stockpile.volume.net.ApiException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a project's photos to the server and starts processing.
 * Resumable: photos already uploaded are skipped, so retries after a dropped
 * connection only send what is missing.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_PROJECT) ?: return Result.failure()
        val store = ProjectStore.get(applicationContext)
        val api = Settings(applicationContext).client()
            ?: return Result.failure(workDataOf(KEY_ERROR to "Nejdřív nastavte adresu serveru."))

        var project = store.get(id) ?: return Result.failure(workDataOf(KEY_ERROR to "Projekt nenalezen"))
        val photos = store.photos(id)
        runCatching { setForeground(foregroundInfo(project.name, project.uploaded.size, photos.size)) }

        return try {
            if (project.remoteJobId == null) {
                val job = api.createJob(project.name, project.densityTM3, project.id)
                project = store.update(id) { it.copy(remoteJobId = job.id, remote = job, uploaded = emptySet()) }!!
            }
            val jobId = project.remoteJobId!!
            var done = photos.count { it.name in project.uploaded }
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to photos.size))

            for (file in photos) {
                if (file.name in project.uploaded) continue
                if (isStopped) return Result.retry()
                api.uploadImage(jobId, file)
                project = store.update(id) { it.copy(uploaded = it.uploaded + file.name) }!!
                done++
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to photos.size))
                runCatching { setForeground(foregroundInfo(project.name, done, photos.size)) }
            }

            val job = api.startJob(jobId)
            store.update(id) { it.copy(remote = job) }
            Result.success()
        } catch (e: ApiException) {
            when {
                // Server lost the job (e.g. data wiped): start over with a new one.
                e.code == 404 -> {
                    store.update(id) { it.copy(remoteJobId = null, remote = null, uploaded = emptySet()) }
                    retryOrFail(e)
                }
                // Already started (e.g. we were killed right after starting it): just refresh.
                e.code == 409 -> {
                    store.get(id)?.remoteJobId?.let { jid ->
                        runCatching { api.getJob(jid) }.getOrNull()?.let { job -> store.update(id) { it.copy(remote = job) } }
                    }
                    Result.success()
                }
                e.code == 401 -> Result.failure(workDataOf(KEY_ERROR to "Špatný API klíč serveru."))
                e.code in 400..499 && e.code != 408 && e.code != 429 ->
                    Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Server nahrávání odmítl")))
                else -> retryOrFail(e)
            }
        } catch (e: IOException) {
            retryOrFail(e)
        }
    }

    private fun retryOrFail(e: Exception): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
        else Result.failure(workDataOf(KEY_ERROR to "Nahrávání selhalo: ${e.message}"))

    private fun foregroundInfo(name: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nahrávání fotek", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Nahrávám $name")
            .setContentText("$done / $total fotek")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val notificationId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_PROJECT = "project"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "uploads"
        private const val MAX_ATTEMPTS = 8

        fun workName(projectId: String) = "upload-$projectId"

        fun enqueue(context: Context, projectId: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_PROJECT to projectId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(projectId), ExistingWorkPolicy.REPLACE, request)
        }
    }
}
