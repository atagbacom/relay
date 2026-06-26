package com.relay.app.ui

import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.relay.app.crypto.QrKeyExchange
import com.relay.app.ui.theme.RelayColors
import com.relay.app.ui.theme.RelayDimens
import com.relay.app.ui.theme.RelayType
import java.util.concurrent.Executors

/**
 * Écran QR — deux onglets :
 * MON QR   — affiche notre QR code à faire scanner par l'autre
 * SCANNER  — caméra pour scanner le QR de l'autre
 */
@Composable
fun QrScreen(
    viewModel: MainViewModel,
    onContactAdded: (String) -> Unit,
    onBack: () -> Unit
) {
    val shortId by viewModel.shortId.collectAsState()
    val qrBitmap by viewModel.qrBitmap.collectAsState()

    var activeTab by remember { mutableStateOf(0) }
    var scanResult by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayColors.Background)
            .systemBarsPadding()
    ) {

        // ── Header ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RelayColors.Surface)
                .padding(RelayDimens.PaddingM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Retour",
                    color = RelayColors.Green,
                    fontFamily = RelayType.Mono,
                    fontSize = RelayDimens.FontM
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "ÉCHANGE DE CLÉ",
                color = RelayColors.TextPrimary,
                fontFamily = RelayType.Mono,
                fontSize = RelayDimens.FontM,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(1f))
        }

        // ── Explication ──────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RelayDimens.PaddingM),
            color = RelayColors.GreenDim,
            shape = RoundedCornerShape(RelayDimens.RadiusM)
        ) {
            Row(
                modifier = Modifier.padding(RelayDimens.PaddingM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(RelayColors.Green,
                            RoundedCornerShape(4.dp))
                )
                Text(
                    "L'échange QR établit une confiance maximale (●). " +
                    "Scannez mutuellement vos codes.",
                    color = RelayColors.Green,
                    fontFamily = RelayType.Mono,
                    fontSize = RelayDimens.FontS
                )
            }
        }

        // ── Onglets ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RelayDimens.PaddingM),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("MON QR", "SCANNER").forEachIndexed { index, label ->
                val isActive = activeTab == index
                Surface(
                    onClick = { activeTab = index },
                    modifier = Modifier.weight(1f),
                    color = if (isActive) RelayColors.GreenDim
                            else RelayColors.Surface,
                    shape = RoundedCornerShape(RelayDimens.RadiusS),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isActive) RelayColors.Green
                        else RelayColors.Border
                    )
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(
                            horizontal = RelayDimens.PaddingM,
                            vertical = RelayDimens.PaddingS
                        ),
                        color = if (isActive) RelayColors.Green
                                else RelayColors.TextMuted,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontS,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(RelayDimens.PaddingM))

        // ── Contenu selon onglet ─────────────────────────────
        when (activeTab) {
            0 -> MyQrTab(shortId, qrBitmap)
            1 -> ScannerTab(
                onScanned = { json ->
                    val contact = QrKeyExchange.parsePayload(json)
                    if (contact != null) {
                        viewModel.addGreenContact(contact)
                        scanResult = contact.shortId
                        scanError = null
                    } else {
                        scanError = "QR invalide ou expiré"
                    }
                },
                scanResult = scanResult,
                scanError = scanError
            )
        }
    }
}

@Composable
fun MyQrTab(shortId: String, bitmap: Bitmap?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(RelayDimens.PaddingL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (bitmap != null) {
            Surface(
                shape = RoundedCornerShape(RelayDimens.RadiusL),
                color = Color.White,
                modifier = Modifier
                    .size(280.dp)
                    .border(
                        2.dp,
                        RelayColors.Green,
                        RoundedCornerShape(RelayDimens.RadiusL)
                    )
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Mon QR Relay",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .background(
                        RelayColors.Surface,
                        RoundedCornerShape(RelayDimens.RadiusL)
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = RelayColors.Green)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "VOTRE IDENTITÉ",
                color = RelayColors.TextMuted,
                fontFamily = RelayType.Mono,
                fontSize = RelayDimens.FontXS,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                shortId,
                color = RelayColors.Green,
                fontFamily = RelayType.Mono,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp
            )
        }

        Text(
            "Présentez ce code à votre contact.\nIl expirera dans 5 minutes.",
            color = RelayColors.TextMuted,
            fontFamily = RelayType.Mono,
            fontSize = RelayDimens.FontXS,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ScannerTab(
    onScanned: (String) -> Unit,
    scanResult: String?,
    scanError: String?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (scanResult != null) {
            // Succès
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(RelayDimens.PaddingL),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                RelayColors.GreenDim,
                                RoundedCornerShape(40.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", color = RelayColors.Green, fontSize = 36.sp)
                    }
                    Text(
                        "Contact ajouté",
                        color = RelayColors.Green,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontL,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        scanResult,
                        color = RelayColors.TextSecondary,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontS
                    )
                    Text(
                        "Niveau de confiance : ●  Maximal",
                        color = RelayColors.Green,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontS
                    )
                }
            }
        } else {
            // Caméra
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture =
                            ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val barcodeScanner = BarcodeScanning.getClient()
                            val executor = Executors.newSingleThreadExecutor()

                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(Size(1280, 720))
                                .setBackpressureStrategy(
                                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                )
                                .build()

                            analysis.setAnalyzer(executor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val image = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                    barcodeScanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            barcodes.firstOrNull {
                                                it.format == Barcode.FORMAT_QR_CODE
                                            }?.rawValue?.let { onScanned(it) }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("QrScreen", "Camera error: $e")
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Viseur
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .align(Alignment.Center)
                        .border(2.dp, RelayColors.Green,
                            RoundedCornerShape(RelayDimens.RadiusM))
                )
            }

            if (scanError != null) {
                Spacer(Modifier.height(RelayDimens.PaddingM))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = RelayDimens.PaddingM),
                    color = RelayColors.OrangeDim,
                    shape = RoundedCornerShape(RelayDimens.RadiusS)
                ) {
                    Text(
                        scanError,
                        modifier = Modifier.padding(RelayDimens.PaddingM),
                        color = RelayColors.Orange,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontS
                    )
                }
            }

            Spacer(Modifier.height(RelayDimens.PaddingM))
            Text(
                "Pointez la caméra vers le QR de votre contact",
                color = RelayColors.TextMuted,
                fontFamily = RelayType.Mono,
                fontSize = RelayDimens.FontS,
                textAlign = TextAlign.Center
            )
        }
    }
}