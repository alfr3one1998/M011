package com.tajalmalka.orders

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        const val TARGET_URL = "https://tajalmalka.com/admin/products/list/vendor?status=1"
        private const val NOTIFICATION_PERMISSION_REQUEST = 701
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NotificationHelper.createChannels(this)
        requestNotificationPermission()

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            userAgentString = "$userAgentString TajAlmalkaOrdersAndroid/1.0"
        }

        webView.addJavascriptInterface(OrderJavascriptBridge(), "AndroidOrderBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                CookieManager.getInstance().flush()
                installOrderWatcher()
                startOrderMonitor()
            }
        }

        swipeRefresh.setOnRefreshListener { webView.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(TARGET_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun startOrderMonitor() {
        getSharedPreferences("order_monitor", MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", true)
            .apply()

        val intent = Intent(this, OrderMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    private fun installOrderWatcher() {
        val script = """
            (function() {
              if (window.__tajOrderWatcherInstalled) return;
              window.__tajOrderWatcherInstalled = true;

              function clean(s) {
                return (s || '').replace(/\\s+/g, ' ').trim().slice(0, 600);
              }

              function getKeys() {
                var keys = [];
                var nodes = document.querySelectorAll('[data-order-id]');
                nodes.forEach(function(n) {
                  var id = n.getAttribute('data-order-id');
                  if (id) keys.push('id:' + id);
                });

                if (!keys.length) {
                  var rows = document.querySelectorAll('table tbody tr');
                  rows.forEach(function(r) {
                    var text = clean(r.innerText);
                    if (text) keys.push('row:' + text);
                  });
                }

                if (!keys.length) {
                  var cards = document.querySelectorAll('.order, .order-item, .order-card, [class*="order-"]');
                  cards.forEach(function(c) {
                    var text = clean(c.innerText);
                    if (text) keys.push('card:' + text);
                  });
                }

                return Array.from(new Set(keys));
              }

              var ready = false;
              var previous = [];
              setTimeout(function() {
                previous = getKeys();
                ready = true;
              }, 5000);

              var timer = null;
              var observer = new MutationObserver(function() {
                if (!ready) return;
                clearTimeout(timer);
                timer = setTimeout(function() {
                  var current = getKeys();
                  var before = new Set(previous);
                  var added = current.filter(function(k) { return !before.has(k); });
                  if (added.length > 0) {
                    AndroidOrderBridge.onPotentialNewOrder('طلب جديد أو تحديث جديد في قائمة الطلبات');
                  }
                  previous = current;
                }, 1800);
              });

              if (document.body) {
                observer.observe(document.body, {childList:true, subtree:true, characterData:true});
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    inner class OrderJavascriptBridge {
        @JavascriptInterface
        fun onPotentialNewOrder(message: String?) {
            val prefs = getSharedPreferences("order_monitor", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong("last_web_alert", 0L)
            if (now - last < 12_000L) return
            prefs.edit().putLong("last_web_alert", now).apply()
            NotificationHelper.showNewOrder(this@MainActivity, message ?: "وصل طلب جديد")
        }
    }
}
