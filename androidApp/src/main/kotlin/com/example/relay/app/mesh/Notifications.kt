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
    const val CANAL_ETAT = "etat_reseau"
    private const val ID_BLUETOOTH = 9001
    /** Extra de l'intent : identifiant du contact dont ouvrir la conversation. */
    const val EXTRA_OUVRIR_CONTACT = "ouvrir_contact"
    /** Action de l'intent du raccourci et de la notification « Urgence ». */
    const val ACTION_URGENCE = "com.relay.app.URGENCE"
    private const val PREFIXE_ALERTE = "ALERTE · "

    fun creerCanaux(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ETAT, "État du réseau", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Bluetooth éteint : Relay ne peut plus envoyer ni relayer"
            }
        )
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
    /**
     * Bluetooth éteint : sans lui, rien ne passe. Toucher la notification
     * demande au téléphone de l'allumer. Retirée dès qu'il est rallumé.
     */
    @SuppressLint("MissingPermission")
    fun bluetoothEteint(context: Context) {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return
        val activer = PendingIntent.getActivity(
            context, 9,
            Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CANAL_ETAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF13294B.toInt())
            .setContentTitle("Bluetooth éteint")
            .setContentText("Relay ne peut ni envoyer, ni recevoir, ni relayer. Touchez pour l'allumer.")
            .setContentIntent(activer)
            .addAction(0, "Activer le Bluetooth", activer)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        nmc.notify(ID_BLUETOOTH, notif)
    }

    /** Un appareil croisé a une version plus récente : la lui demander. */
    @SuppressLint("MissingPermission")
    fun nouvelleVersion(context: Context, version: String, chez: String) {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return
        val ouvrir = PendingIntent.getActivity(
            context, 11, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CANAL_ETAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF13294B.toInt())
            .setContentTitle("Relay $version est disponible")
            .setContentText("$chez l'a déjà : demandez-lui de vous la partager (Réglages › Partager Relay).")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$chez a la version $version de Relay. Demandez-lui de vous l'envoyer : Réglages › Partager Relay, puis Xender, Quick Share ou Bluetooth."
            ))
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        nmc.notify(9002, notif)
    }

    /** Version publiée en ligne : proposer de la télécharger depuis les réglages. */
    @SuppressLint("MissingPermission")
    fun nouvelleVersionEnLigne(context: Context, version: String, taille: Long) {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return
        val ouvrir = PendingIntent.getActivity(
            context, 12, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mo = (taille / 1_000_000).coerceAtLeast(1)
        val notif = NotificationCompat.Builder(context, CANAL_ETAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF13294B.toInt())
            .setContentTitle("Relay $version est disponible")
            .setContentText("Téléchargement d'environ $mo Mo, depuis Réglages › À propos.")
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        nmc.notify(9003, notif)
    }

    fun retirerBluetoothEteint(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_BLUETOOTH)
    }

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
