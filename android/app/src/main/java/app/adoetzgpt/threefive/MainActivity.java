package app.adoetzgpt.threefive;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.ValueCallback;
import android.webkit.PermissionRequest;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on for live conversation
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Configure WebView for optimal performance and memory
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            configureWebView(webView);
        }
    }

    private void configureWebView(WebView webView) {
        // Enable necessary features
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setBuiltInZoomControls(false);
        webView.getSettings().setDisplayZoomControls(false);

        // Performance optimizations
        webView.getSettings().setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.getSettings().setSafeBrowsingEnabled(false);
        }

        // Cache mode for better performance
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);

        // Set WebViewClient to handle URL navigation
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();

                // Allow internal routing
                if (host == null || host.isEmpty()) {
                    return false;
                }

                // Open external links in browser
                String scheme = uri.getScheme();
                if ("adoetzgpt".equals(scheme)) {
                    return false; // Handle internally as deep link
                }

                // Let WebView handle http/https for the configured backend
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return false;
                }

                // Open all other schemes externally
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (Exception e) {
                    // No app to handle
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Log errors but don't show native error page
                android.util.Log.e("AdoetzGPT", "WebView error: " + errorCode + " - " + description);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Grant microphone permission for WebView
                String[] resources = request.getResources();
                for (String resource : resources) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        // Forward to Android permission system
                        request.grant(resources);
                        return;
                    }
                }
                request.grant(resources);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                              FileChooserParams fileChooserParams) {
                // File upload handling through Capacitor
                return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
            }
        });

        // Enable mixed content for dev scenarios
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Don't destroy WebView state on pause
    }

    @Override
    public void onResume() {
        super.onResume();
        // Restore WebView state
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Clean up foreground service if running
        Intent serviceIntent = new Intent(this, MicrophoneForegroundService.class);
        stopService(serviceIntent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Handle orientation changes smoothly
    }
}
