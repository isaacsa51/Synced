package com.serranoie.app.synced

import android.service.notification.NotificationListenerService

class MediaAccessService : NotificationListenerService() {
    override fun onListenerConnected() {
        onConnected?.invoke()
    }

    companion object {
        @Volatile
        var onConnected: (() -> Unit)? = null
    }
}
