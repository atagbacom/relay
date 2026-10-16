package com.relay.app.ui

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.relay.app.crypto.Empreinte
import com.relay.app.crypto.QrKeyExchange
import com.relay.app.ui.components.Icones
import com.relay.app.ui.components.animationsActivees
import com.relay.app.ui.theme.PlexMono
import com.relay.app.ui.theme.RelayPalette
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private enum class Mode { CODE, SCAN, VERIFIE }

/**
 * Vérifier en personne : chacun scanne le code de l'autre, face à face.
 * C'est le seul moyen d'atteindre le niveau de confiance maximal.
 */
@Composable
fun QrScreen(
    viewModel: MainViewModel,
    onContactAdded: (String) -> Unit,
    onBack: () -> Unit
) {
    val qrBitmap by viewModel.qrBitmap.collectAsState()
    val empreinte by viewModel.empreinte.collectAsState()
    var mode by remember { mutableStateOf(Mode.CODE) }
    var verifie by remember { mutableStateOf<QrKeyExchange.QrContact?>(null) }
    var erreur by remember { mutableStateOf<String?>(null) }

    // Le code expire après 5 minutes : on le renouvelle chaque minute
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            viewModel.regenererQr()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RelayPalette.BleuNuitProfond)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icones.Retour, contentDescription = "Retour", tint = RelayPalette.BleuBrume)
            }
            Text("Vérifier en personne", style = MaterialTheme.typography.titleLarge, color = RelayPalette.Blanc)
        }
        Text(
            "Face à face, chacun scanne le code de l'autre. C'est la seule façon d'être certain de parler à la bonne personne.",
            style = MaterialTheme.typography.bodyMedium,
            color = RelayPalette.BleuCiel,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 16.dp)
        )

        if (mode != Mode.VERIFIE) {
            Onglets(mode, onChoix = { mode = it; erreur = null })
            Spacer(Modifier.height(18.dp))
        }

        AnimatedContent(
            targetState = mode,
            transitionSpec = { (fadeIn(tween(250)) + slideInVertically { it / 12 }) togetherWith fadeOut(tween(150)) },
            label = "mode"
        ) { m ->
            when (m) {
                Mode.CODE -> MonCode(qrBitmap, empreinte)
                Mode.SCAN -> Scanner(
                    erreur = erreur,
                    onScanne = { json ->
                        val contact = QrKeyExchange.parsePayload(json)
                        if (contact != null) {
                            viewModel.addGreenContact(contact)
                            verifie = contact
                            erreur = null
                            mode = Mode.VERIFIE
                        } else {
                            erreur = "Ce code n'est pas valide ou a expiré. Demandez à votre contact de rouvrir son code."
                        }
                    }
                )
                Mode.VERIFIE -> verifie?.let { c ->
                    Verifie(
                        identifiant = c.shortId,
                        mots = viewModel.motsDeSecurite(c.publicKey),
                        onEcrire = { onContactAdded(c.shortId) },
                        onAutre = { verifie = null; mode = Mode.SCAN }
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Onglets(mode: Mode, onChoix: (Mode) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .background(RelayPalette.BleuCiel.copy(alpha = 0.1f), RoundedCornerShape(13.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(Mode.CODE to "Mon code", Mode.SCAN to "Scanner").forEach { (m, libelle) ->
            val choisi = m == mode
            val fond by animateColorAsStateCompat(if (choisi) RelayPalette.Blanc else RelayPalette.BleuNuitProfond.copy(alpha = 0f))
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .background(fond, RoundedCornerShape(10.dp))
                    .semantics { selected = choisi }
                    .clickable(role = Role.Tab) { onChoix(m) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    libelle,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (choisi) RelayPalette.BleuNuit else RelayPalette.BleuCiel
                )
            }
        }
    }
}

@Composable
private fun animateColorAsStateCompat(cible: androidx.compose.ui.graphics.Color) =
    androidx.compose.animation.animateColorAsState(cible, tween(250), label = "onglet")

@Composable
private fun MonCode(qr: android.graphics.Bitmap?, empreinte: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.padding(horizontal = 28.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = RelayPalette.Blanc,
            shadowElevation = 16.dp
        ) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vous", style = MaterialTheme.typography.titleMedium, color = RelayPalette.Encre)
                        Text("Relay · clé d'identité", style = MaterialTheme.typography.bodySmall, color = RelayPalette.Gris)
                    }
                    LogoRelay(Modifier.size(28.dp))
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                    if (qr != null) {
                        Image(
                            qr.asImageBitmap(),
                            contentDescription = "Code QR de votre clé, renouvelé chaque minute",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = RelayPalette.Or)
                    }
                }
                HorizontalDivider(Modifier.padding(top = 14.dp), color = RelayPalette.GrisFond)
                Text(
                    "EMPREINTE DE VOTRE CLÉ",
                    style = MaterialTheme.typography.labelSmall,
                    color = RelayPalette.Gris,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    empreinte,
                    fontFamily = PlexMono,
                    style = MaterialTheme.typography.titleMedium,
                    color = RelayPalette.BleuNuit,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Text(
            "Montrez ce code à votre contact. Il le scanne depuis l'onglet « Scanner ». Le code se renouvelle chaque minute.",
            style = MaterialTheme.typography.bodySmall,
            color = RelayPalette.BleuGris,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp)
        )
    }
}

@Composable
private fun Scanner(erreur: String?, onScanne: (String) -> Unit) {
    val contexte = LocalContext.current
    // La caméra est demandée ici, au moment où elle sert
    var cameraOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(contexte, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val demande = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { cameraOk = it }
    LaunchedEffect(Unit) { if (!cameraOk) demande.launch(android.Manifest.permission.CAMERA) }
    if (!cameraOk) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Pour scanner le code de votre contact, Relay a besoin de la caméra. L'image reste sur le téléphone.",
                style = MaterialTheme.typography.bodyLarge, color = RelayPalette.BleuCiel, textAlign = TextAlign.Center
            )
            Button(
                onClick = { demande.launch(android.Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = RelayPalette.Or, contentColor = RelayPalette.Encre),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(top = 16.dp).height(52.dp)
            ) { Text("Autoriser la caméra", style = MaterialTheme.typography.labelLarge) }
        }
        return
    }
    ScannerCamera(erreur, onScanne)
}

@Composable
private fun ScannerCamera(erreur: String?, onScanne: (String) -> Unit) {
    val contexte = LocalContext.current
    val cycleDeVie = LocalLifecycleOwner.current
    val anime = animationsActivees()
    val ligne = if (anime) {
        rememberInfiniteTransition(label = "scan").animateFloat(
            0.06f, 0.92f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ligne"
        ).value
    } else 0.5f
    // Un code n'est traité qu'une fois, même s'il reste dans le champ de la caméra
    val dejaTraite = remember { AtomicBoolean(false) }
    LaunchedEffect(erreur) { if (erreur != null) { delay(1500); dejaTraite.set(false) } }
    val executeur = remember { Executors.newSingleThreadExecutor() }
    var fournisseur by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            fournisseur?.unbindAll()
            executeur.shutdown()
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(286.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(RelayPalette.Nuit)
        ) {
            AndroidView(
                factory = { ctx ->
                    val vue = PreviewView(ctx)
                    val futur = ProcessCameraProvider.getInstance(ctx)
                    futur.addListener({
                        val camera = futur.get().also { fournisseur = it }
                        val apercu = Preview.Builder().build().also { it.setSurfaceProvider(vue.surfaceProvider) }
                        val lecteur = BarcodeScanning.getClient()
                        val analyse = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analyse.setAnalyzer(executeur) { image ->
                            val media = image.image
                            if (media == null || dejaTraite.get()) {
                                image.close()
                                return@setAnalyzer
                            }
                            lecteur.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                                .addOnSuccessListener { codes ->
                                    codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue?.let {
                                        if (dejaTraite.compareAndSet(false, true)) onScanne(it)
                                    }
                                }
                                .addOnCompleteListener { image.close() }
                        }
                        try {
                            camera.unbindAll()
                            camera.bindToLifecycle(cycleDeVie, CameraSelector.DEFAULT_BACK_CAMERA, apercu, analyse)
                        } catch (e: Exception) {
                            android.util.Log.e("QrScreen", "Caméra indisponible : $e")
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    vue
                },
                modifier = Modifier.fillMaxSize()
            )
            // Viseur : coins or et ligne de balayage
            Canvas(Modifier.fillMaxSize()) {
                val m = 18.dp.toPx()
                val l = 46.dp.toPx()
                val r = 12.dp.toPx()
                val trait = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val w = size.width
                val h = size.height
                listOf(
                    Triple(Offset(m, m + l), Offset(m, m), Offset(m + l, m)),
                    Triple(Offset(w - m - l, m), Offset(w - m, m), Offset(w - m, m + l)),
                    Triple(Offset(w - m, h - m - l), Offset(w - m, h - m), Offset(w - m - l, h - m)),
                    Triple(Offset(m + l, h - m), Offset(m, h - m), Offset(m, h - m - l)),
                ).forEach { (a, coin, b) ->
                    val p = Path().apply {
                        moveTo(a.x, a.y)
                        lineTo(coin.x + (a.x - coin.x).coerceIn(-r, r), coin.y + (a.y - coin.y).coerceIn(-r, r))
                        quadraticTo(coin.x, coin.y, coin.x + (b.x - coin.x).coerceIn(-r, r), coin.y + (b.y - coin.y).coerceIn(-r, r))
                        lineTo(b.x, b.y)
                    }
                    drawPath(p, RelayPalette.Or, style = trait)
                }
                val y = h * ligne
                drawLine(RelayPalette.Or.copy(alpha = 0.3f), Offset(w * 0.08f, y), Offset(w * 0.92f, y), 10.dp.toPx(), StrokeCap.Round)
                drawLine(RelayPalette.Or, Offset(w * 0.08f, y), Offset(w * 0.92f, y), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        if (erreur != null) {
            Text(
                erreur,
                style = MaterialTheme.typography.bodyMedium,
                color = RelayPalette.Blanc,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(start = 28.dp, end = 28.dp, top = 16.dp)
                    .background(RelayPalette.AlerteRouge.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
        Text(
            "Visez le code de votre contact. La comparaison des clés se fait sur le téléphone, rien ne passe par internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = RelayPalette.BleuCiel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 18.dp)
        )
    }
}

/** Le sceau qui se dessine, puis les mots à lire à voix haute. */
@Composable
private fun Verifie(identifiant: String, mots: List<String>, onEcrire: () -> Unit, onAutre: () -> Unit) {
    val anime = animationsActivees()
    val anneau = remember { Animatable(if (anime) 0f else 1f) }
    val coche = remember { Animatable(if (anime) 0f else 1f) }
    val eclat = remember { Animatable(if (anime) 0f else 1f) }
    val apparition = remember { Animatable(if (anime) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!anime) return@LaunchedEffect
        anneau.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        coche.animateTo(1f, spring(dampingRatio = 0.5f))
        eclat.animateTo(1f, tween(900))
        apparition.animateTo(1f, tween(450))
    }
    val vert = RelayPalette.Remis
    val nom = "Appareil ${identifiant.takeLast(4).uppercase()}"

    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(132.dp).semantics { contentDescription = "Vérifié" }) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension * 0.36f
            drawCircle(vert.copy(alpha = 0.6f * (1f - eclat.value)), radius = r * (1f + eclat.value * 0.9f), center = c, style = Stroke(2.dp.toPx()))
            drawCircle(vert.copy(alpha = 0.12f), radius = r, center = c)
            drawArc(
                vert, startAngle = -90f, sweepAngle = 360f * anneau.value, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
            )
            if (coche.value > 0f) {
                val u = r / 48f
                val p1 = Offset(c.x - 20 * u, c.y + 1 * u)
                val p2 = Offset(c.x - 7 * u, c.y + 14 * u)
                val p3 = Offset(c.x + 20 * u, c.y - 15 * u)
                val t = coche.value.coerceIn(0f, 1f)
                val chemin = Path().apply {
                    moveTo(p1.x, p1.y)
                    if (t < 0.35f) lineTo(p1.x + (p2.x - p1.x) * t / 0.35f, p1.y + (p2.y - p1.y) * t / 0.35f)
                    else {
                        lineTo(p2.x, p2.y)
                        val k = (t - 0.35f) / 0.65f
                        lineTo(p2.x + (p3.x - p2.x) * k, p2.y + (p3.y - p2.y) * k)
                    }
                }
                drawPath(chemin, vert, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Column(
            Modifier.graphicsLayer { alpha = apparition.value; translationY = (1f - apparition.value) * 16.dp.toPx() },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$nom est vérifié", style = MaterialTheme.typography.headlineSmall, color = RelayPalette.Blanc, textAlign = TextAlign.Center)
            Text(
                "Confiance maximale. Personne ne pourra se faire passer pour lui dans Relay.",
                style = MaterialTheme.typography.bodyMedium, color = RelayPalette.BleuCiel, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
            Column(
                Modifier
                    .padding(top = 22.dp)
                    .fillMaxWidth()
                    .background(RelayPalette.BleuCiel.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .border(1.dp, RelayPalette.BleuCiel.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    "Lisez ces mots à voix haute. Votre contact les voit aussi sur son téléphone : s'ils sont identiques, il confirme, et vous êtes vérifiés tous les deux.",
                    style = MaterialTheme.typography.bodySmall, color = RelayPalette.BleuCiel
                )
                Spacer(Modifier.height(10.dp))
                mots.chunked(2).forEach { paire ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        paire.forEach { mot ->
                            Text(
                                mot, style = MaterialTheme.typography.titleMedium, color = RelayPalette.Blanc, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).background(RelayPalette.BleuNuit, RoundedCornerShape(10.dp)).padding(vertical = 9.dp)
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onEcrire,
                colors = ButtonDefaults.buttonColors(containerColor = RelayPalette.Or, contentColor = RelayPalette.Encre),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(top = 20.dp).fillMaxWidth().height(52.dp)
            ) { Text("Écrire à $nom", style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(
                onClick = onAutre,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, RelayPalette.BleuCiel.copy(alpha = 0.3f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RelayPalette.BleuBrume),
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(52.dp)
            ) { Text("Vérifier quelqu'un d'autre", style = MaterialTheme.typography.labelLarge) }
        }
    }
}
