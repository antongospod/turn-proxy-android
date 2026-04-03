@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaWebViewDialog(
    captchaUrl: String,
    onCaptchaSolved: () -> Unit,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(captchaUrl) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "Проверка VK",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Пройдите проверку и прокси перезапустится",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = onDismiss) {
                            Text("Отмена", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = onCaptchaSolved) {
                            Text("Готово")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                setSupportMultipleWindows(true)
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                            }

                            // Включаем cookies — VK использует их для трекинга прохождения капчи
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(this@apply as? WebView ?: return@apply, true)
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onCloseWindow(window: WebView?) {
                                    super.onCloseWindow(window)
                                    onCaptchaSolved()
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    url?.let { currentUrl = it }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    url?.let { currentUrl = it }

                                    // Определяем что капча пройдена:
                                    // После "Я не робот" VK перенаправляет в лобби звонка
                                    if (url != null && isCaptchaSolved(url)) {
                                        onCaptchaSolved()
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    // Если VK перенаправляет на страницу звонка — капча пройдена
                                    if (isCaptchaSolved(url)) {
                                        onCaptchaSolved()
                                        return true
                                    }
                                    return false
                                }
                            }

                            loadUrl(captchaUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/**
 * Определяет, что капча была пройдена по URL перенаправления.
 * VK после прохождения "Я не робот" перенаправляет на:
 * - Страницу звонка (vk.com/call/)
 * - Или просто закрывает страницу
 */
private fun isCaptchaSolved(url: String): Boolean {
    val lower = url.lowercase()
    return (lower.contains("vk.com/call/") || lower.contains("vk.ru/call/")) &&
           !lower.contains("captcha") && !lower.contains("not_robot")
}
