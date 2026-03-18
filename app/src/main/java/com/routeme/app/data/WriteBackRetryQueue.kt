package com.routeme.app.data

import android.util.Log
import com.routeme.app.ClientDao
import com.routeme.app.PendingWriteBackEntity
import com.routeme.app.network.SheetsWriteBack
import com.routeme.app.util.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Simple retry queue for failed Google Sheet write-backs.
 *
 * Failed writes are persisted to Room, then retried on the next successful
 * write-back or when [drainQueue] is called explicitly (e.g. on app resume).
 *
 * Items with more than the configured max retry attempts are dropped.
 */
class WriteBackRetryQueue(
    private val clientDao: ClientDao
) {
    companion object {
        private const val TAG = "WriteBackRetryQueue"
    }

    /** Enqueue a failed write-back for later retry. */
    suspend fun enqueue(clientName: String, column: String, value: String) = withContext(Dispatchers.IO) {
        val entity = PendingWriteBackEntity(
            clientName = clientName,
            column = column,
            value = value,
            createdAtMillis = System.currentTimeMillis()
        )
        clientDao.insertPendingWriteBack(entity)
        Log.d(TAG, "Enqueued write-back: $clientName / $column = $value")
    }

    /** How many items are waiting to be retried. */
    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        clientDao.getPendingWriteBackCount()
    }

    /**
     * Attempt to flush all pending write-backs.
     * Returns a summary of successes and failures.
     *
     * Should be called from a coroutine on a non-UI thread.
     */
    suspend fun drainQueue(): DrainResult = withContext(Dispatchers.IO) {
        if (SheetsWriteBack.webAppUrl.isBlank()) {
            return@withContext DrainResult(0, 0, 0)
        }
        val pending = clientDao.getAllPendingWriteBacks()
        if (pending.isEmpty()) return@withContext DrainResult(0, 0, 0)

        var succeeded = 0
        var failed = 0
        var dropped = 0

        for (item in pending) {
            if (item.retryCount >= AppConfig.RetryQueue.MAX_WRITE_BACK_RETRIES) {
                Log.w(
                    TAG,
                    "Dropping write-back after ${AppConfig.RetryQueue.MAX_WRITE_BACK_RETRIES} retries: ${item.clientName}/${item.column}"
                )
                clientDao.deletePendingWriteBack(item)
                dropped++
                continue
            }

            val result = try {
                // Use postRaw so we send exactly the column + value that was originally queued
                SheetsWriteBack.postRaw(item.clientName, item.column, item.value)
            } catch (e: Exception) {
                SheetsWriteBack.WriteResult(false, e.message ?: "Unknown error")
            }

            if (result.success) {
                clientDao.deletePendingWriteBack(item)
                succeeded++
                Log.d(TAG, "Retry succeeded: ${item.clientName}/${item.column}")
            } else {
                clientDao.incrementRetryCount(item.id)
                failed++
                Log.d(TAG, "Retry failed (attempt ${item.retryCount + 1}): ${item.clientName}/${item.column}: ${result.message}")
            }
        }

        Log.d(TAG, "Drain complete: $succeeded ok, $failed failed, $dropped dropped")
        DrainResult(succeeded, failed, dropped)
    }

    data class DrainResult(
        val succeeded: Int,
        val failed: Int,
        val dropped: Int
    )
}
