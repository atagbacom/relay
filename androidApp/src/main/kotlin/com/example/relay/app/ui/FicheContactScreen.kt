package com.relay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.relay.app.model.RelayContact
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.app.crypto.Empreinte
import com.relay.app.ui.components.*
import com.relay.app.ui.theme.PlexMono

/**
 * Fiche d'un contact : qui il est, où en est la confiance, et comment la
 * faire monter.
 */
@Composable
fun FicheContactScreen(
    contactId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onEcrire: () -> Unit,
    onVerifier: () -> Unit,
    onSupprime: () -> Unit = onBack,
) {
    val ancien by viewModel.ancienAppareilPossible(contactId).collectAsState(null)
    var confirmerRegroupement by remember { mutableStateOf(false) }
    var confirmerSuppression by remember { mutableStateOf(false) }
    val contact by viewModel.getContact(contactId).collectAsState(null)
    val messages by viewModel.messagesForContact(contactId).collectAsState(emptyList())
    val enAttente = messages.count { it.direction == "OUT" && !it.delivered }
    val confiance = Confiance.depuis(contact?.trustLevel?.name)
    val nom = contact?.nomAffiche
    val titre = nom ?: "Appareil ${contactId.takeLast(4).uppercase()}"
    var renommer by remember { mutableStateOf(false) }
    var ouvert by remember(confiance) { mutableStateOf<Confiance?>(confiance) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icones.Retour, contentDescription = "Retour", tint = MaterialTheme.colorScheme.primary)
            }
            Text("Fiche contact", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── Identité ───────────────────────────────────────────
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(
                texte = nom?.let { initiales(it) } ?: contactId.takeLast(4).uppercase(),
                confiance = confiance, taille = 76.dp, identifiant = nom == null
            )
            Text(titre, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
            contact?.let {
                Text(
                    "empreinte ${Empreinte.de(it.publicKey)}",
                    fontFamily = PlexMono,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(onClick = { renommer = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (nom == null) "Donner un nom" else "Renommer", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }

        // ── Messages en attente ────────────────────────────────
        if (enAttente > 0) {
            EnAttente(enAttente, confiance)
        }

        // ── Échelle de confiance ───────────────────────────────
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 6.dp)) {
                Text("Niveau de confiance", style = MaterialTheme.typography.titleMedium)
                Text("La confiance monte, elle ne redescend jamais.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                EchelleConfiance(actuel = confiance, ouvert = ouvert, onOuvrir = { ouvert = it })
            }
        }

        // ── Même personne, autre clé ? ─────────────────────────
        ancien?.let { a ->
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "C'est peut-être ${a.nomAffiche} qui a réinstallé Relay ou changé de téléphone",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Un contact au même nom existe déjà (appareil ${a.shortId.takeLast(4).uppercase()}), avec une autre clé. Si c'est la même personne, regroupez-les : l'historique passe sur ce contact et l'ancien disparaît.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { confirmerRegroupement = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Regrouper les deux") }
                }
            }
        }

        // ── Actions ────────────────────────────────────────────
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (confiance != Confiance.VERIFIE_PERSONNE) {
                Button(
                    onClick = onVerifier,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icones.Bouclier, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Vérifier en personne", style = MaterialTheme.typography.labelLarge)
                }
            }
            OutlinedButton(onClick = onEcrire, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Écrire", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = { confirmerSuppression = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Supprimer le contact", color = MaterialTheme.colorScheme.error) }
        }
    }

    ancien?.let { a ->
        if (confirmerRegroupement) {
            AlertDialog(
                onDismissRequest = { confirmerRegroupement = false },
                title = { Text("Regrouper avec l'ancien contact ?") },
                text = {
                    Text(
                        "Les messages de l'appareil ${a.shortId.takeLast(4).uppercase()} passent sur ce contact, et l'ancien est supprimé. " +
                            "La confiance ne se transmet pas : la nouvelle clé reste à vérifier en personne, pour être sûr que c'est bien ${a.nomAffiche}."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.regrouper(a.shortId, contactId); confirmerRegroupement = false }) { Text("Regrouper") }
                },
                dismissButton = { TextButton(onClick = { confirmerRegroupement = false }) { Text("Annuler") } }
            )
        }
    }

    if (confirmerSuppression) {
        AlertDialog(
            onDismissRequest = { confirmerSuppression = false },
            title = { Text("Supprimer ce contact ?") },
            text = { Text("La conversation et les messages en attente pour ce contact sont effacés de ce téléphone. Si l'appareil revient à portée, il réapparaîtra comme un nouveau contact.") },
            confirmButton = {
                TextButton(onClick = { viewModel.supprimerContact(contactId); confirmerSuppression = false; onSupprime() }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmerSuppression = false }) { Text("Annuler") } }
        )
    }

    if (renommer) {
        DialogueRenommer(
            actuel = nom.orEmpty(),
            onValider = { viewModel.renommer(contactId, it); renommer = false },
            onAnnuler = { renommer = false },
            homonyme = { viewModel.contactDuMemeNom(contactId, it) },
            // L'autre contact rejoint celui-ci : la fiche ouverte reste valable
            onRegrouper = { d -> viewModel.regrouper(d.shortId, contactId); renommer = false }
        )
    }
}

@Composable
private fun EnAttente(nombre: Int, confiance: Confiance) {
    val c = confiance.couleurs
    val anime = animationsActivees()
    val t = rememberInfiniteTransition(label = "attente")
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .background(c.fond, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icones.Horloge, contentDescription = null, tint = c.texte, modifier = Modifier.size(20.dp))
        Text(
            if (nombre == 1) "1 message en attente. Il sera remis dès que ce contact sera joignable."
            else "$nombre messages en attente. Ils seront remis dès que ce contact sera joignable.",
            style = MaterialTheme.typography.bodyMedium, color = c.texte, modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { i ->
                val a = if (anime) t.animateFloat(
                    0.25f, 1f,
                    infiniteRepeatable(keyframes { durationMillis = 1400; 0.25f at 0; 1f at 560; 0.25f at 1120 }, initialStartOffset = StartOffset(i * 200)),
                    label = "point$i"
                ).value else 1f
                Box(Modifier.size(5.dp).alpha(a).background(c.texte, CircleShape))
            }
        }
    }
}

@Composable
private fun EchelleConfiance(actuel: Confiance, ouvert: Confiance?, onOuvrir: (Confiance?) -> Unit) {
    val anime = animationsActivees()
    // Le trait se remplit jusqu'au niveau actuel à l'ouverture de la fiche
    val cible = actuel.ordinal / 2f
    val remplissage = remember { Animatable(if (anime) 0f else cible) }
    LaunchedEffect(cible) { remplissage.animateTo(cible, tween(1000, delayMillis = 250, easing = FastOutSlowInEasing)) }

    Box {
        // Trait vertical derrière les pastilles
        BoxWithConstraints(Modifier.matchParentSize().padding(start = 21.dp, top = 30.dp, bottom = 34.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
            Box(
                Modifier.width(3.dp).height(maxHeight * remplissage.value)
                    .background(actuel.couleurs.trait, RoundedCornerShape(2.dp))
            )
        }
        Column {
            Confiance.values().forEach { niveau ->
                val atteint = niveau.ordinal <= actuel.ordinal
                val estActuel = niveau == actuel
                val c = niveau.couleurs
                val pulsation = if (anime && estActuel) rememberInfiniteTransition(label = "actuel").animateFloat(
                    1f, 1.08f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p"
                ).value else 1f
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOuvrir(if (ouvert == niveau) null else niveau) }
                        .semantics { stateDescription = if (estActuel) "niveau actuel" else if (atteint) "atteint" else "pas encore atteint" }
                        .padding(top = 10.dp, bottom = 10.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .scale(pulsation)
                            .background(if (atteint) c.fond else MaterialTheme.colorScheme.surface, CircleShape)
                            .border(2.dp, if (atteint) c.trait else c.trait.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(niveau.icone, contentDescription = null, tint = c.texte, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                niveau.libelle, style = MaterialTheme.typography.titleMedium,
                                color = if (atteint) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (estActuel) {
                                Text(
                                    "ACTUEL", style = MaterialTheme.typography.labelSmall, color = c.texte,
                                    modifier = Modifier.background(c.fond, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = ouvert == niveau,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Text(
                                niveau.explication, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogueRenommer(
    actuel: String,
    onValider: (String) -> Unit,
    onAnnuler: () -> Unit,
    /** Contact déjà affiché sous ce nom, pour éviter deux « Koffi » qu'on ne distingue plus. */
    homonyme: suspend (String) -> RelayContact? = { null },
    onRegrouper: (RelayContact) -> Unit = {},
) {
    var texte by remember { mutableStateOf(actuel) }
    var doublon by remember { mutableStateOf<RelayContact?>(null) }
    LaunchedEffect(texte) { doublon = homonyme(texte) }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Nom du contact") },
        text = {
            Column {
                Text(
                    "Ce nom reste sur votre téléphone. Il n'est envoyé à personne.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = texte, onValueChange = { texte = it.take(40) },
                    singleLine = true, label = { Text("Nom") },
                    isError = doublon != null,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth()
                )
                doublon?.let { d ->
                    Text(
                        "Un autre contact s'appelle déjà « ${d.nomAffiche} » (appareil ${d.shortId.takeLast(4).uppercase()}). " +
                            "Choisissez un nom qui les distingue, par exemple avec le téléphone, ou regroupez-les si c'est la même personne.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    TextButton(onClick = { onRegrouper(d) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("C'est la même personne : regrouper")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(texte) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } }
    )
}
