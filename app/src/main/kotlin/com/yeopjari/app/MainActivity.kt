package com.yeopjari.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.ui.MainScreen
import com.yeopjari.app.ui.YeopjariTheme

class MainActivity : ComponentActivity() {
    private val controller get() = (application as YeopjariApp).controller

    /** AAOS 에서 차속(CAR_SPEED)은 런타임 권한이다. 받으면 차량 신호 구독을 다시 시작한다. */
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) controller.onPermissionsChanged()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())

        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            YeopjariTheme {
                MainScreen(state = state, controller = controller)
            }
        }
    }

    private companion object {
        val RUNTIME_PERMISSIONS = listOf("android.car.permission.CAR_SPEED")
    }
}
