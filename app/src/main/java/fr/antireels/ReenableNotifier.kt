package fr.antireels

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlin.math.abs

/**
 * Notification « minuterie » affichée tant que le blocage est désactivé :
 * elle montre le temps restant avant la réactivation automatique (compte à rebours dans la
 * notification) et disparaît toute seule à la réactivation (délai d'expiration de la notification
 * + annulation explicite).
 *
 * Nécessite l'autorisation d'afficher des notifications (demandée quand on confirme une
 * désactivation). Sans cette autorisation, rien n'est affiché et le reste fonctionne pareil.
 */
object ReenableNotifier {

    private const val CHANNEL_ID = "reenable_countdown"
    private const val NOTIFICATION_ID = 1

    /** Heure de réactivation (horloge réelle) de la notification actuellement affichée, 0 si aucune. */
    @Volatile
    private var postedEndWall = 0L

    /**
     * Met la notification en accord avec l'état du blocage (idempotent, peu coûteux).
     * @param cleanup à vrai : retire la notification même si on ne se souvient pas de l'avoir affichée
     */
    fun sync(context: Context, state: BlockSettings.State, cleanup: Boolean = false) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return

        if (state.phase != BlockSettings.Phase.DISABLED) {
            if (postedEndWall != 0L || cleanup) {
                manager.cancel(NOTIFICATION_ID)
                postedEndWall = 0L
            }
            return
        }

        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val endWall = System.currentTimeMillis() + state.remainingMs
        if (abs(endWall - postedEndWall) < 3_000L) return   // déjà affichée pour cette échéance

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Réactivation du blocage", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Anti-Reels : blocage désactivé")
            .setContentText("Réactivation automatique dans")
            .setWhen(endWall)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(state.remainingMs)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        postedEndWall = endWall
    }
}
