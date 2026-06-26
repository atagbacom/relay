package com.relay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.relay.app.mesh.MeshService

class MainActivity : ComponentActivity() {

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startMeshService()
        } else {
            Log.e("MainActivity", "Permissions refusées")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (permissions.all {
            ContextCompat.checkSelfPermission(this, it) ==
                PackageManager.PERMISSION_GRANTED
        }) {
            startMeshService()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun startMeshService() {
        val intent = Intent(this, MeshService::class.java)
        startForegroundService(intent)
        Log.d("MainActivity", "MeshService lancé")
    }
}