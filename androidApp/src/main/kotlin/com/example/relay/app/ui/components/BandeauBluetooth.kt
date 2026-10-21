package com.relay.app.ui.components

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.relay.app.ui.theme.RelayPalette

/**
 * Bluetooth éteint : sans lui Relay ne peut ni envoyer, ni recevoir, ni
 * relayer. Le bandeau le dit et propose de l'allumer en un geste.
 */
@Composable
fun BandeauBluetooth(modifier: Modifier = Modifier) {
    val activer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    Row(
        modifier
            .fillMaxWidth()
            .background(RelayPalette.BleuNuitProfond)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icones.Alerte, contentDescription = null, tint = RelayPalette.Or, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text("Bluetooth éteint", style = MaterialTheme.typography.titleSmall, color = RelayPalette.Blanc)
            Text(
                "Relay ne peut ni envoyer, ni recevoir, ni relayer.",
                style = MaterialTheme.typography.bodySmall, color = RelayPalette.BleuCiel
            )
        }
        Button(
            onClick = { runCatching { activer.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RelayPalette.Or, contentColor = RelayPalette.BleuNuitProfond),
            modifier = Modifier.heightIn(min = 44.dp)
        ) { Text("Activer") }
    }
}
