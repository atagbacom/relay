package com.relay.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object RelayColors {
    val Background    = Color(0xFF0A0A0A)
    val Surface       = Color(0xFF141414)
    val SurfaceHigh   = Color(0xFF1E1E1E)
    val Border        = Color(0xFF2A2A2A)
    val BorderAccent  = Color(0xFF333333)

    val Green         = Color(0xFF00C853)
    val GreenDim      = Color(0xFF1A3D2B)
    val Yellow        = Color(0xFFFFB800)
    val YellowDim     = Color(0xFF3D2E00)
    val Orange        = Color(0xFFFF6B00)
    val OrangeDim     = Color(0xFF3D1A00)
    val Red           = Color(0xFFFF3D3D)

    val TextPrimary   = Color(0xFFEEEEEE)
    val TextSecondary = Color(0xFF888888)
    val TextMuted     = Color(0xFF444444)
}

object RelayType {
    val Mono = FontFamily.Monospace
}

object RelayDimens {
    val PaddingS  = 8.dp
    val PaddingM  = 16.dp
    val PaddingL  = 24.dp
    val RadiusS   = 4.dp
    val RadiusM   = 8.dp
    val RadiusL   = 16.dp

    val FontXS    = 10.sp
    val FontS     = 12.sp
    val FontM     = 14.sp
    val FontL     = 16.sp
    val FontTitle = 20.sp
}