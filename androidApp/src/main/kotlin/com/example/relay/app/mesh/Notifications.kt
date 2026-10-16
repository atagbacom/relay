package com.relay.app.mesh

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.relay.R
import com.relay.app.MainActivity

/** Notifications des messages et des alertes reçus. */
object Notifications {
    const val CANAL_MESSAGES = "messages"
    const val CANAL_ALERTES = "alertes"
    /** Extra de l'intent : identifiant du contact dont ouvrir la conversation. */
    const val EXTRA_OUVRIR_CONTACT = "ouvrir_contact"
    private const val PREFIXE_ALERTE = "ALERTE · "

    fun creerCanaux(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nouveaux messages reçus par le réseau Relay"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ALERTES, "Alertes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alertes envoyées par vos contacts (besoin médical, danger…)"
                enableVibration(true)
            }
        )
    }

    /**
     * Le contenu n'apparaît pas sur l'écran verrouillé : seulement « Nouveau
     * message chiffré ». Toucher la notification ouvre la conversation.
     */
    /** Quelqu'un vient de nous vérifier : ouvrir l'application pour comparer les mots. */
    @SuppressLint("MissingPermission")
    fun verification(context: Context, contactId: String, nom: String) {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return
        val ouvrir = PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CANAL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF13294B.toInt())
            .setContentTitle("$nom vient de vous vérifier")
            .setContentText("Ouvrez Relay et comparez les quatre mots ensemble.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        nmc.notify(("verif" + contactId).hashCode(), notif)
    }

    @SuppressLint("MissingPermission") // vérifié par areNotificationsEnabled()
    fun message(context: Context, contactId: String, nom: String, texte: String) {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return

        val alerte = texte.startsWith(PREFIXE_ALERTE)
        val ouvrir = PendingIntent.getActivity(
            context,
            contactId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OUVRIR_CONTACT, contactId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val titre = if (alerte) "Alerte de $nom" else nom
        val corps = if (alerte) texte.removePrefix(PREFIXE_ALERTE) else texte
        val publique = NotificationCompat.Builder(context, if (alerte) CANAL_ALERTES else CANAL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (alerte) "Alerte reçue" else "Nouveau message chiffré")
            .build()

        val notif = NotificationCompat.Builder(context, if (alerte) CANAL_ALERTES else CANAL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF13294B.toInt())
            .setContentTitle(titre)
            .setContentText(corps)
            .setStyle(NotificationCompat.BigTextStyle().bigText(corps))
            .setCategory(if (alerte) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publique)
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        nmc.notify(contactId.hashCode(), notif)
    }
}
