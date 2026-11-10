package com.crylo.metronome.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.crylo.metronome.MainActivity
import com.crylo.metronome.MetronomeApp
import com.crylo.metronome.R
import com.crylo.metronome.core.MetronomeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Keeps the metronome playing with the screen off. A `mediaPlayback` foreground service is the
 * type Android intends for continuous audio; the media-style notification (backed by a
 * platform MediaSession, so it also appears in the media controls and answers headset
 * buttons) is how the user stops it from anywhere.
 */
class MetronomeService : Service() {
    private val metronome get() = (application as MetronomeApp).controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "Metronome").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPause() = metronome.stop()
                    override fun onStop() = metronome.stop()
                },
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            metronome.stop()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(metronome.settings.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        metronome.startEngine()
        scope.launch {
            metronome.settings
                .distinctUntilChangedBy { it.bpm to it.timeSignature }
                .collect { s ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s))
                }
        }
        return START_NOT_STICKY
    }

    private fun summary(s: MetronomeSettings) = getString(R.string.notification_text, s.bpm, s.timeSignature.toString())

    private fun buildNotification(s: MetronomeSettings): Notification {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.app_name))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, summary(s))
                .build(),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MetronomeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(summary(s))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.stop), stop).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        metronome.onServiceDestroyed()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.crylo.metronome.STOP"
    }
}
