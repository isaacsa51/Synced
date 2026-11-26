package com.serranoie.app.synced

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.serranoie.app.synced.services.MediaAccessService

class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasMediaAccess(this)) {
            Toast.makeText(this, R.string.setup_ready, Toast.LENGTH_SHORT).show()
        } else {
            val component = ComponentName(this, MediaAccessService::class.java)
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            try {
                startActivity(detail)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            Toast.makeText(this, R.string.setup_prompt, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        fun hasMediaAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, MediaAccessService::class.java))
    }
}
