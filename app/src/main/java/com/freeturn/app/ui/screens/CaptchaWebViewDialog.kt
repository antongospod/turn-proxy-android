@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
    onCaptchaSolved: (successToken: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

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
                        TextButton(onClick = { onCaptchaSolved(null) }) {
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
                            val webViewRef = this
                            CookieManager.getInstance().apply {
                                setAcceptCookie(true)
                                setAcceptThirdPartyCookies(webViewRef, true)
                            }
                            
                            addJavascriptInterface(object {
                                @android.webkit.JavascriptInterface
                                fun onToken(token: String) {
                                    android.util.Log.d("CaptchaWV", "onToken: $token")
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        onCaptchaSolved(if (token == "closed" || token == "null") null else token)
                                    }
                                }
                                @android.webkit.JavascriptInterface
                                fun onDebug(msg: String) {
                                    android.util.Log.d("CaptchaWV", "JS: $msg")
                                }
                            }, "AndroidCaptcha")

                            // Перехват XHR и fetch — ловим success_token из ответов VK API
                            val openerScript = """
                                (function() {
                                    function tryExtract(text) {
                                        try {
                                            var d = typeof text === 'string' ? JSON.parse(text) : text;
                                            var token = d.success_token
                                                || (d.data && d.data.success_token)
                                                || (d.payload && d.payload.success_token)
                                                || (d.response && d.response.success_token)
                                                || null;
                                            if (token) {
                                                AndroidCaptcha.onDebug('token found: ' + token.substring(0, 20));
                                                AndroidCaptcha.onToken(token);
                                            }
                                        } catch(e) {}
                                    }

                                    // Перехват XHR
                                    var _open = XMLHttpRequest.prototype.open;
                                    var _send = XMLHttpRequest.prototype.send;
                                    XMLHttpRequest.prototype.open = function(m, url) {
                                        this._xUrl = url;
                                        return _open.apply(this, arguments);
                                    };
                                    XMLHttpRequest.prototype.send = function() {
                                        this.addEventListener('load', function() {
                                            AndroidCaptcha.onDebug('XHR ' + this._xUrl + ' => ' + (this.responseText||'').substring(0, 120));
                                            tryExtract(this.responseText);
                                        });
                                        return _send.apply(this, arguments);
                                    };

                                    // Перехват fetch
                                    var _fetch = window.fetch;
                                    window.fetch = function(input, init) {
                                        return _fetch.apply(this, arguments).then(function(resp) {
                                            var clone = resp.clone();
                                            clone.text().then(function(t) {
                                                AndroidCaptcha.onDebug('fetch ' + input + ' => ' + t.substring(0, 120));
                                                tryExtract(t);
                                            }).catch(function(){});
                                            return resp;
                                        });
                                    };

                                    // window.opener как запасной вариант
                                    window.opener = {
                                        postMessage: function(data, origin) {
                                            try {
                                                var d = typeof data === 'string' ? JSON.parse(data) : data;
                                                AndroidCaptcha.onDebug('opener.pm: ' + JSON.stringify(d).substring(0, 120));
                                                tryExtract(d);
                                            } catch(e) {}
                                        }
                                    };
                                    window.close = function() {
                                        AndroidCaptcha.onDebug('window.close');
                                        AndroidCaptcha.onToken('closed');
                                    };
                                    window.addEventListener('message', function(e) {
                                        AndroidCaptcha.onDebug('msg ' + e.origin + ': ' + JSON.stringify(e.data).substring(0,120));
                                        tryExtract(e.data);
                                    });
                                })();
                            """.trimIndent()

                            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                WebViewCompat.addDocumentStartJavaScript(this, openerScript, setOf("*"))
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    android.util.Log.d("CaptchaWV", "start: $url")
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    android.util.Log.d("CaptchaWV", "finish: $url")
                                    // Фолбэк если DOCUMENT_START_SCRIPT не поддерживается
                                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                        view?.evaluateJavascript(openerScript, null)
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url ?: return false
                                    android.util.Log.d("CaptchaWV", "nav: $url")
                                    val token = url.getQueryParameter("success_token")
                                    if (token != null) {
                                        android.util.Log.d("CaptchaWV", "token in url: $token")
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            onCaptchaSolved(token)
                                        }
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
