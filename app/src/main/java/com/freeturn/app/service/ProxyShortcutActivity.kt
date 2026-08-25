package com.freeturn.app.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.domain.proxy.ProxyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Прозрачный трамплин внешних входов (ярлыки, виджет, тайл): единственное место, где
 * START может спросить согласие на VPN - системный диалог поднимается только из activity,
 * а без него в WG-режиме `establish()` вернёт null и сессия умрёт с "откройте приложение".
 */
class ProxyShortcutActivity : ComponentActivity() {

    private val prefs: AppPreferences by inject()
    private val launcher: ProxyServiceLauncher by inject()

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) launcher.start()
        else ProxyStore.fail(getString(R.string.notif_proxy_vpn_denied))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) return
        when (intent?.action) {
            ProxyActions.START -> lifecycleScope.launch { startWithConsent() }
            ProxyActions.STOP -> {
                launcher.stop()
                finish()
            }
            else -> finish()
        }
    }

    private suspend fun startWithConsent() {
        val vpnIntent = if (prefs.clientConfigFlow.first().wireGuardActive) {
            VpnService.prepare(this)
        } else null

        if (vpnIntent == null) {
            launcher.start()
            finish()
        } else {
            consent.launch(vpnIntent)
        }
    }

    companion object {
        fun startIntent(context: Context): Intent =
            Intent(context, ProxyShortcutActivity::class.java)
                .setAction(ProxyActions.START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
