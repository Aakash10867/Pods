package com.aakash.pods

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder

object Notifier {
    const val CH_MSG = "pods_messages"
    const val CH_KEEP = "pods_keepalive"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Site notifications", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_KEEP, "Staying connected", NotificationManager.IMPORTANCE_LOW))
    }

    fun openTileIntent(ctx: Context, tileId: String): Intent =
        Intent(ctx, WebActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("pods://tile/$tileId")
            putExtra("id", tileId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

    fun post(ctx: Context, tile: Tile, title: String, body: String, tag: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx,
            tile.id.hashCode(),
            openTileIntent(ctx, tile.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Locked sites never put their content on the lock screen or in the shade.
        val shownTitle = if (tile.locked) tile.name else title.ifBlank { tile.name }
        val shownBody = if (tile.locked) "New activity" else body
        val n = Notification.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(shownTitle)
            .setContentText(shownBody)
            .setSubText(tile.name)
            .setStyle(Notification.BigTextStyle().bigText(shownBody))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup(tile.id)
            .build()
        val id = if (tag.isNotEmpty()) (tile.id + tag).hashCode() else (tile.id + System.nanoTime()).hashCode()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

/** Ids of open tiles that asked to stay connected in the background. Main thread only. */
object KeepAlive {
    val active = LinkedHashSet<String>()

    fun sync(ctx: Context) {
        try {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (active.isEmpty()) ctx.stopService(i) else ctx.startForegroundService(i)
        } catch (e: Exception) {
            // Starting is only allowed while the app is visible; stopping always works.
        }
    }
}

/** Keeps the app process alive so open sites can keep receiving messages. */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val n = KeepAlive.active.size
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, Notifier.CH_KEEP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pods")
            .setContentText(if (n == 1) "1 site staying connected" else "$n sites staying connected")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val NOTIF_ID = 4242
    }
}
