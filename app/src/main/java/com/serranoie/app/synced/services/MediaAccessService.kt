package com.serranoie.app.synced.services

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
