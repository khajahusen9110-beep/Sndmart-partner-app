package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.remote.SupabaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** "Decline" button on the new-order notification. */
class OrderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val assignmentId = intent.getStringExtra(EXTRA_ASSIGNMENT_ID).orEmpty()
        if (assignmentId.isBlank()) return

        // Stop the ringing immediately; the server call can take a moment.
        OrderAlertNotifier.cancel(context, assignmentId)

        val pending = goAsync()
        scope.launch {
            try {
                val result = SupabaseRepository.getInstance(context)
                    .respondToAssignment(assignmentId, accept = false)
                result.onFailure { Log.w(TAG, "Decline failed: ${it.message}") }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "OrderActionReceiver"
        const val ACTION_DECLINE = "com.sndmart.delivery.action.DECLINE_ORDER"
        const val EXTRA_ASSIGNMENT_ID = "assignment_id"
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
