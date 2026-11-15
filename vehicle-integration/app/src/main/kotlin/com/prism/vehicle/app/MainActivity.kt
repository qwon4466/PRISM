package com.prism.vehicle.app

import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prism.vehicle.app.ui.PrismApp
import com.prism.vehicle.app.ui.PrismTheme

class MainActivity : ComponentActivity() {
    private val vm: PrismViewModel by viewModels()

    // FUSED_LOCATION 은 dangerous 권한. 동의하면 SDK 세션을 다시 연다.
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.restartSdk()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrismTheme.load(this) // 저장된 테마 선택. 없으면(최초 실행) LIGHT
        setContent {
            val s by vm.ui.collectAsStateWithLifecycle()
            LaunchedEffect(s.mode) {
                if (s.mode == AppMode.PLEOS_CONTEXT &&
                    checkSelfPermission(FUSED_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) locationPermission.launch(FUSED_LOCATION)
            }
            PrismApp(vm)
        }
        openDebugScenario(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDebugScenario(intent)
    }

    private fun openDebugScenario(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent?.getStringExtra("scenario_id")?.takeIf { it.isNotBlank() }?.let { id ->
            vm.openScenario(
                id,
                initialPositionMs = intent.getLongExtra("seek_ms", 0L),
                autoPlay = intent.getBooleanExtra("auto_play", false),
            )
        }
    }

    // AUTO 테마: 분 단위 시계(18:59→19:00 등), 시스템 시간 변경, 시간대 변경 때 기기 시각을 다시 읽는다
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = PrismTheme.refreshClock()
    }

    override fun onStart() {
        super.onStart()
        PrismTheme.refreshClock() // 백그라운드에서 돌아올 때 시간 재확인
        registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        vm.onForeground()
    }

    override fun onStop() {
        unregisterReceiver(clockReceiver)
        vm.onBackground()
        super.onStop()
    }

    private companion object { const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION" }
}
