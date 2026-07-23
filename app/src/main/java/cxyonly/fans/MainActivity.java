package cxyonly.fans;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import cxyonly.fans.view.ProgressWebViewClient;
import cxyonly.fans.view.WebViewConfig;

public class MainActivity extends AppCompatActivity {

    private static final String HOME_URL = "https://cxyonly.fans/";
    private static final String LOGIN_URL = "https://cxyonly.fans/m/login?redirect=/m/home";
    private static final String HOME_PAGE_URL = "https://cxyonly.fans/m/home";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String PREFS_AUTH = "auth_prefs";
    private static final String KEY_TOKEN = "cached_token";
    private static final String KEY_CSRF = "cached_csrf";

    private WebView webView;
    private WebView backgroundWebView;
    private String cachedAuthToken = ""; // 缓存 JWT token，避免从 WebView 异步读取
    private String cachedCsrfToken = ""; // 缓存 CSRF token，API PATCH/PUT 必需
    private SwipeRefreshLayout swipeRefresh;
    private View progressBar;
    private FrameLayout loadingOverlay;
    private FrameLayout errorOverlay;
    private TextView errorMsg;
    private Button retryBtn;
    private LinearLayout topBar;
    private TextView btnFavorites;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isLoading = false;
    private boolean isPageLoaded = false;
    private long lastBackPressTime = 0;
    private long loadingStartTime = 0;
    private boolean isAutoRedirect = false;

    private ClipboardLoginHelper loginHelper;
    private boolean isFirstLoad = true;
    private boolean loginGuideShown = false;
    private boolean weChatOpening = false;
    private boolean isLoggedIn = false;
    private boolean loginCheckInProgress = false;

    private java.util.Stack<String> pageHistory = new java.util.Stack<>();
    private FavoritesManager favoritesManager;

    private int currentProgress = 0;
    private static final int PROGRESS_MAX = 100;
    private static final int PROGRESS_ANIM_DELAY = 16;
    private static final long FAV_SYNC_INTERVAL_MS = 5 * 1000;
    private Runnable favoritesSyncRunnable;

    private Runnable loginWatchdogRunnable;
    private Runnable networkRetryRunnable;
    private FavoritesManager.SyncListener originalFavListener;

    // ==================== Token/CSRF 持久化 ====================
    private void restoreAuthCache() {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE);
        String tok = sp.getString(KEY_TOKEN, "");
        String cs = sp.getString(KEY_CSRF, "");
        if (!tok.isEmpty()) cachedAuthToken = tok;
        if (!cs.isEmpty()) cachedCsrfToken = cs;
    }
    private void saveAuthCache() {
        getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, cachedAuthToken != null ? cachedAuthToken : "")
            .putString(KEY_CSRF, cachedCsrfToken != null ? cachedCsrfToken : "")
            .apply();
    }
    private void clearAuthCache() {
        getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE).edit().clear().apply();
    }

    @SuppressLint({"SetJavaScriptEnabled", "MissingInflatedId"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupStatusBar();
        initViews();
        setupTopBar();
        setupWebView();
        setupBackgroundWebView();
        setupSwipeRefresh();
        setupErrorRetry();

        loginHelper = new ClipboardLoginHelper(this, webView);
        loginHelper.setListener(new ClipboardLoginHelper.OnLoginCodeListener() {
            @Override public void onCodeDetected(String code) { }
            @Override public void onCodeFilled() { }
        });

        // 从持久化存储恢复 token/csrf（避免重启后 WebView localStorage 丢失）
        restoreAuthCache();

        startSilentStartupCheck();

        // 启动时自动检测更新（有网且静默检测）
        if (WebViewConfig.isNetworkAvailable()) {
            UpdateChecker.check(this);
        }

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void closeFavorites() {
                mainHandler.post(() -> {
                    if (!pageHistory.isEmpty()) {
                        String prev = pageHistory.pop();
                        if (prev != null && !prev.startsWith("file:///android_asset/favorites_viewer")
                                && !prev.startsWith("file:///android_asset/practice_frontend")) {
                            webView.loadUrl(prev);
                            return;
                        }
                    }
                    loadAppFrontend();
                });
            }

            @android.webkit.JavascriptInterface
            public String getFavoritesData() {
                if (favoritesManager == null) return "[]";
                return favoritesManager.getData().toString();
            }

            @android.webkit.JavascriptInterface
            public void openFavorites() {
                mainHandler.post(MainActivity.this::openFavoritesViewer);
            }

            @android.webkit.JavascriptInterface
            public void checkUpdate() {
                mainHandler.post(() -> UpdateChecker.checkWithDialog(MainActivity.this));
            }

            @android.webkit.JavascriptInterface
            public void syncFavorites() {
                mainHandler.post(() -> {
                    if (!WebViewConfig.isNetworkAvailable() || !isLoggedIn || favoritesManager == null || getAuthWebView() == null) {
                        return;
                    }
                    favoritesManager.setListener(new FavoritesManager.SyncListener() {
                        @Override public void onProgress(String message) { }
                        @Override public void onComplete(int count) { favoritesManager.setListener(originalFavListener); if (isFavoritesPageVisible()) { webView.evaluateJavascript("javascript:(function(){if(window.updateFavoritesData){window.updateFavoritesData();}})()", null); } }
                        @Override public void onError(String error) { favoritesManager.setListener(originalFavListener); }
                    });
                    favoritesManager.startSync(getAuthWebView());
                });
            }

            @android.webkit.JavascriptInterface
            public void openPractice(String categoryId) {
                mainHandler.post(() -> loadPracticeFrontend(categoryId, null));
            }

            @android.webkit.JavascriptInterface
            public void openPracticeWithQuestion(String categoryId, String questionId) {
                mainHandler.post(() -> loadPracticeFrontend(categoryId, questionId));
            }

            @android.webkit.JavascriptInterface
            public void requestAppData() {
                mainHandler.post(MainActivity.this::fetchAppFrontendData);
            }

            @android.webkit.JavascriptInterface
            public void requestPracticeData(String categoryId) {
                mainHandler.post(() -> fetchPracticeData(categoryId));
            }

            @android.webkit.JavascriptInterface
            public void practiceAction(String data) {
                mainHandler.post(() -> performPracticeAction(data, null));
            }

            @android.webkit.JavascriptInterface
            public void recordPractice(String categoryId) {
                mainHandler.post(() -> {
                    if (categoryId == null || categoryId.trim().isEmpty() || getAuthWebView() == null || !WebViewConfig.isNetworkAvailable()) return;
                    warmAuthWebView(() -> {
                        String safeId = escapeJsString(categoryId.trim());
                        String js = "javascript:fetch('/api/user/practice_events',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'practice',category_id:" + safeId + "})}).catch(function(){});";
                        getAuthWebView().evaluateJavascript(js, null);
                    });
                });
            }

            @android.webkit.JavascriptInterface
            public void requestHistoryData() {
                mainHandler.post(MainActivity.this::fetchHistoryData);
            }

            @android.webkit.JavascriptInterface
            public void submitLoginCode(String code) {
                mainHandler.post(() -> submitLoginCodeFromLocalPage(code));
            }

            @android.webkit.JavascriptInterface
            public void fillClipboardCode() {
                mainHandler.post(MainActivity.this::sendClipboardCodeToLoginPage);
            }

            @android.webkit.JavascriptInterface
            public void openWeChatFromLogin() {
                mainHandler.post(MainActivity.this::openWeChat);
            }

            @android.webkit.JavascriptInterface
            public void goHome() {
                mainHandler.post(MainActivity.this::loadAppFrontend);
            }

            @android.webkit.JavascriptInterface
public void goBackFromPractice() {
mainHandler.post(() -> {
if (!pageHistory.isEmpty()) {
String prev = pageHistory.pop();
if (prev != null && !prev.startsWith("file:///android_asset/practice_frontend")
&& !prev.startsWith("file:///android_asset/favorites_viewer")) {
webView.loadUrl(prev);
return;
}
}
loadAppFrontend();
});
}

            @android.webkit.JavascriptInterface
            public void logout() {
                mainHandler.post(MainActivity.this::logout);
            }

            @android.webkit.JavascriptInterface
            public void openWebHome() {
                // 保留桥接方法但不被 UI 调用，避免编译错误
                mainHandler.post(() -> {
                    isAutoRedirect = true;
                    loadingStartTime = System.currentTimeMillis();
                    showLoadingOverlay(true);
                    webView.loadUrl(HOME_PAGE_URL);
                });
            }
        }, "Android");

        favoritesManager = new FavoritesManager(this);
        originalFavListener = new FavoritesManager.SyncListener() {
            @Override public void onProgress(String message) { }
            @Override public void onComplete(int count) { updateTopBarVisibility(); }
            @Override public void onError(String error) { }
        };
        favoritesManager.setListener(originalFavListener);
        updateTopBarVisibility();
    }

    // ==================== 状态栏 ====================
    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) ctrl.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.topBar), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }

    private void initViews() {
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        webView = findViewById(R.id.webView);
        webView.setBackgroundColor(0xFF1A1A2E); // 与 splash_bg 一致，避免启动时白屏过渡
        backgroundWebView = findViewById(R.id.backgroundWebView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        errorOverlay = findViewById(R.id.errorOverlay);
        errorMsg = findViewById(R.id.errorMsg);
        retryBtn = findViewById(R.id.retryBtn);
        topBar = findViewById(R.id.topBar);
        btnFavorites = findViewById(R.id.btnFavorites);
        progressBar.setVisibility(View.GONE);
    }

    private void setupTopBar() {
        if (btnFavorites != null) btnFavorites.setVisibility(View.GONE);
    }

    private void updateTopBarVisibility() {
        if (btnFavorites != null) btnFavorites.setVisibility(View.GONE);
    }

    // ==================== WebView ====================
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebViewConfig.configure(webView);
        // 允许 file:// 资产页面直接 fetch https:// API（避免跨域限制）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
            webView.getSettings().setAllowFileAccessFromFileURLs(true);
        }
        webView.setLayerType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_SOFTWARE, null);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) { super.onProgressChanged(view, newProgress); animateProgressBar(newProgress); }
        });
        webView.setWebViewClient(new ProgressWebViewClient(new ProgressWebViewClient.OnPageCallback() {
            @Override public void onProgressChanged(int progress) { animateProgressBar(progress); }
            @Override
            public void onPageStarted(String url) {
                isLoading = true; isPageLoaded = false; loadingStartTime = System.currentTimeMillis();
                if (url == null || !url.startsWith("file:///android_asset/")) {
                    showLoadingOverlay(true); 
                }
                progressBar.setVisibility(View.VISIBLE); errorOverlay.setVisibility(View.GONE);
                if (!isLoggedIn && url.contains("/login")) {
                    mainHandler.postDelayed(() -> { if (loginHelper != null) loginHelper.checkAndFillLoginCode(); }, 800);
                }
            }
            @Override
            public void onPageFinished(String url) {
                isLoading = false; isPageLoaded = true;
                if (loginCheckInProgress) { } else {
                    long elapsed = System.currentTimeMillis() - loadingStartTime;
                    long delay = isAutoRedirect ? Math.max(80, 1000 - elapsed) : 80;
                    mainHandler.postDelayed(() -> { showLoadingOverlay(false); animateProgressBar(PROGRESS_MAX); mainHandler.postDelayed(() -> progressBar.setVisibility(View.GONE), 300); }, delay);
                }
                isAutoRedirect = false;
                if (isFirstLoad && url.startsWith("http")) { isFirstLoad = false; handleHomePageLoaded(); }
            }
            @Override
            public void onPageError(String errorInfo) { isLoading = false; showLoadingOverlay(false); progressBar.setVisibility(View.GONE); toast("\u26a0\ufe0f 网络连接异常"); }
        }));
    }

    private void setupBackgroundWebView() {
        if (backgroundWebView == null) return;
        WebViewConfig.configure(backgroundWebView);
        backgroundWebView.setWebChromeClient(new WebChromeClient());
        backgroundWebView.setWebViewClient(new android.webkit.WebViewClient());
        backgroundWebView.loadUrl(HOME_URL);
    }

    private void startSilentStartupCheck() {
        isAutoRedirect = true;
        isFirstLoad = false;
        WebView authView = getAuthWebView();
        if (authView != null) authView.loadUrl(HOME_URL);

        // 【优化】：如果有缓存，直接静默加载主页，不再展示 Loading 过渡
        if (!cachedAuthToken.isEmpty()) {
            isLoggedIn = true;
            loginGuideShown = false;
            showLoadingOverlay(false);
            loadAppFrontend();
            
            // 后台静默检测维护和 Token 更新
            mainHandler.postDelayed(this::silentMaintenanceCheck, 1500);
        } else {
            // 没有缓存时，稍微走一下正常的加载检查逻辑并展示 Loading
            showLoadingOverlay(true);
            loadingStartTime = System.currentTimeMillis();
            mainHandler.postDelayed(this::handleHomePageLoaded, 900);
        }
    }

    private void silentMaintenanceCheck() {
        if (!WebViewConfig.isNetworkAvailable()) return;
        String mJs = "javascript:(function(){var b=document.body?document.body.innerText:'';if(b.indexOf('\u7ef4\u62a4')>=0||b.indexOf('\u5347\u7ea7')>=0)return'maintenance';return'ok';})()";
        WebView authView = getAuthWebView();
        if (authView == null) return;
        authView.evaluateJavascript(mJs, mResult -> {
            String mr = mResult != null ? mResult.replaceAll("\"", "") : "ok";
            if ("maintenance".equals(mr)) { 
                loadMaintenanceFrontend(); 
                return; 
            }
            // 维护检查通过后，静默验证Token
            checkLoginSilent();
        });
    }

    private void checkLoginSilent() {
        warmAuthWebView(() -> {
            String js = "javascript:(function(){var token=localStorage.getItem('daguan_token')||'';var csrf=localStorage.getItem('csrf_token')||'';return JSON.stringify({logged_in:!!token,token:token,csrf:csrf});})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    org.json.JSONObject info = new org.json.JSONObject(result != null ? result : "{}");
                    boolean loggedIn = info.optBoolean("logged_in", false);
                    if (loggedIn) {
                        String tok = info.optString("token", "");
                        String cs = info.optString("csrf", "");
                        if (!tok.isEmpty()) cachedAuthToken = tok;
                        if (!cs.isEmpty()) cachedCsrfToken = cs;
                        saveAuthCache();
                        if (!tok.isEmpty()) {
                            mainHandler.postDelayed(this::fetchAndCacheCsrf, 500);
                        }
                        mainHandler.postDelayed(() -> { if (WebViewConfig.isNetworkAvailable()) { startLoginWatchdog(); startFavoritesPeriodicSync(); } }, 1000);
                    } else {
                        // 同步我们存储的缓存到 WebView
                        warmAuthWebView(() -> {
                            WebView av = getAuthWebView();
                            if (av != null) {
                                String j = "javascript:(function(){localStorage.setItem('daguan_token','"+escapeJsString(cachedAuthToken)+"');})()";
                                av.evaluateJavascript(j, null);
                                fetchAndCacheCsrf();
                            }
                        });
                    }
                } catch (Exception ignored) {}
            });
        });
    }

    // ==================== 首页 → 维护检测 → 自动路由 ====================
    private void handleHomePageLoaded() {
        String mJs = "javascript:(function(){var b=document.body?document.body.innerText:'';if(b.indexOf('\u7ef4\u62a4')>=0||b.indexOf('\u5347\u7ea7')>=0)return'maintenance';return'ok';})()";
        WebView authView = getAuthWebView();
        if (authView == null) return;
        authView.evaluateJavascript(mJs, mResult -> {
            String mr = mResult != null ? mResult.replaceAll("\"", "") : "ok";
            if ("maintenance".equals(mr)) { loadMaintenanceFrontend(); return; }
            checkLoginAndRoute();
        });
    }

    private void checkLoginAndRoute() {
        if (!WebViewConfig.isNetworkAvailable()) {
            showLoadingOverlay(false);
            toast("\u26a0\ufe0f 网络连接异常");
            // 断网时仍然加载本地前端，保证用户能看到 UI（收藏、底部功能栏等）
            loadAppFrontend();
            startNetworkRetry();
            return;
        }
        warmAuthWebView(() -> {
            String js = "javascript:(function(){var token=localStorage.getItem('daguan_token')||'';var csrf=localStorage.getItem('csrf_token')||'';return JSON.stringify({logged_in:!!token,token:token,csrf:csrf});})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    org.json.JSONObject info = new org.json.JSONObject(result != null ? result : "{}");
                    boolean loggedIn = info.optBoolean("logged_in", false);
                    if (loggedIn) {
                        // 缓存 token 和 csrf，避免后续 @JavascriptInterface 中死锁
String tok = info.optString("token", "");
String cs = info.optString("csrf", "");
if (!tok.isEmpty()) cachedAuthToken = tok;
if (!cs.isEmpty()) cachedCsrfToken = cs;
saveAuthCache();
// 每次启动都主动从 WebView 获取最新 csrf（可能被刷新）
if (!tok.isEmpty()) {
  mainHandler.postDelayed(() -> fetchAndCacheCsrf(), 500);
}
                        isLoggedIn = true; loginGuideShown = false; showLoadingOverlay(false); isFirstLoad = false; isAutoRedirect = false;
                        loadAppFrontend();
                        mainHandler.postDelayed(() -> { if (WebViewConfig.isNetworkAvailable()) { startLoginWatchdog(); startFavoritesPeriodicSync(); } }, 1000);
                    } else {
                        // WebView localStorage 无 token，但 SharedPreferences 可能有缓存
                        if (!cachedAuthToken.isEmpty()) {
                            // 使用缓存的 token，尝试加载前端（token 可能仍有效）
                            isLoggedIn = true; loginGuideShown = false; showLoadingOverlay(false);
                            loadAppFrontend();
                            // 异步刷新 WebView 中的 token（后台加载页面写入 localStorage）
                            warmAuthWebView(() -> {
                                WebView av = getAuthWebView();
                                if (av != null) {
                                    String j = "javascript:(function(){localStorage.setItem('daguan_token','"+escapeJsString(cachedAuthToken)+"');})()";
                                    av.evaluateJavascript(j, null);
                                    fetchAndCacheCsrf();
                                }
                            });
                        } else {
                            startSilentLoginCheck();
                        }
                    }
                } catch (Exception e) {
                    startSilentLoginCheck();
                }
            });
        });
    }

    private WebView getAuthWebView() {
        return backgroundWebView != null ? backgroundWebView : webView;
    }

    private void warmAuthWebView(Runnable afterWarm) {
        WebView authView = getAuthWebView();
        if (authView == null) return;
        // 断网时不尝试加载页面或发请求
        if (!WebViewConfig.isNetworkAvailable()) { if (afterWarm != null) afterWarm.run(); return; }
        String url = authView.getUrl();
        if (url == null || !url.startsWith("https://cxyonly.fans")) {
            authView.loadUrl(HOME_PAGE_URL);
            mainHandler.postDelayed(() -> warmAuthWebView(afterWarm), 900);
        } else {
            authView.evaluateJavascript("javascript:(function(){fetch('/api/site/math-home-config').catch(function(){});return localStorage.getItem('daguan_token')?'warm_token':'warm';})()", r -> mainHandler.postDelayed(afterWarm, 300));
        }
    }

    // 启动时做一次轻量 auth 检测：取消收藏同步 → 发测试请求 → 有问题弹窗 → 没问题恢复同步
    private void performStartupAuthCheck() {
        if (!WebViewConfig.isNetworkAvailable() || getAuthWebView() == null) {
            // 断网时跳过检测，直接启动定时收藏同步和看门狗
            startFavoritesPeriodicSync();
            return;
        }
        // 先取消定时的收藏同步（避免检测过程中冲突）
        stopFavoritesPeriodicSync();
        // 先预热后台 WebView，确保在 cxyonly.fans 域上执行 JS
        warmAuthWebView(() -> {
            // 用轻量 API 验证 token 是否有效（GET /api/user/total_stats 需要认证）
            String js = "javascript:(function(){"
                + "var token=localStorage.getItem('daguan_token')||'';"
                + "var h=token?{'Authorization':'Bearer '+token}:{};"
                + "try{var x=new XMLHttpRequest();"
                + "x.open('GET','/api/user/total_stats',false);"
                + "Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});"
                + "x.send();"
                + "return JSON.stringify({status:x.status,ok:x.status>=200&&x.status<300});"
                + "}catch(e){return JSON.stringify({status:0,ok:false,error:e.message});}"
                + "})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    org.json.JSONObject r = new org.json.JSONObject(result != null ? result : "{}");
                    if (r.optBoolean("ok", false)) {
                        // token 有效 → 恢复定时收藏同步和看门狗
                        startFavoritesPeriodicSync();
                    } else {
                        // token 无效 → 弹窗引导重新登录
                        showSessionExpiredDialog();
                    }
                } catch (Exception ignored) {
                    // JSON 解析异常 → 依然尝试恢复同步
                    startFavoritesPeriodicSync();
                }
            });
        });
    }

    private void loadAppFrontend() {
        if (webView == null) return;
        isAutoRedirect = false;
        showLoadingOverlay(false);
        pageHistory.clear();
        int favoriteCount = favoritesManager != null ? favoritesManager.getCount() : 0;
        String json = "{\"favoritesCount\":" + favoriteCount + "}";
        String b64 = android.util.Base64.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.loadUrl("file:///android_asset/app_frontend.html#data=" + b64);
    }

    private void loadLoginFrontend() {
        if (webView == null) return;
        isAutoRedirect = false;
        showLoadingOverlay(false);
        webView.loadUrl("file:///android_asset/login_frontend.html");
        mainHandler.postDelayed(this::sendClipboardCodeToLoginPage, 700);
    }

    private void loadMaintenanceFrontend() {
        if (webView == null) return;
        isAutoRedirect = false;
        showLoadingOverlay(false);
        stopLoginWatchdog();
        stopFavoritesPeriodicSync();
        int favCount = favoritesManager != null ? favoritesManager.getCount() : 0;
        String json = "{\"favoritesCount\":" + favCount + "}";
        String b64 = android.util.Base64.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.loadUrl("file:///android_asset/maintenance_frontend.html#data=" + b64);
    }

    private void fetchAppFrontendData() {
        if (webView == null || getAuthWebView() == null || !WebViewConfig.isNetworkAvailable()) {
            sendAppFrontendData("{\"success\":false,\"error\":\"offline\"}");
            return;
        }
        warmAuthWebView(() -> {
            // 同步 XMLHttpRequest + 从 localStorage 取 token/csrf + 加 Authorization 头
            String js = "javascript:(function(){"
                + "var token=localStorage.getItem('daguan_token')||'';"
                + "var csrf=localStorage.getItem('csrf_token')||'';"
                + "var r={success:true,access_token:token,csrf_token:csrf,auth_failed:false};"
                + "var h=token?{'Authorization':'Bearer '+token}:{};"
                + "if(csrf)h['X-CSRF-Token']=csrf;"
                + "try{var x=new XMLHttpRequest();"
                + "x.open('GET','/api/site/math-home-config',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();"
                + "if(x.status==401||x.status==403){r.auth_failed=true;}else{r.home=JSON.parse(x.responseText);}"
                + "if(!r.auth_failed){"
                + "x.open('GET','/api/categories?include_stats=true',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();if(x.status==401||x.status==403){r.auth_failed=true;}else{r.categories=JSON.parse(x.responseText);}"
                + "}"
                + "if(!r.auth_failed){"
                + "x.open('GET','/api/questions/user/last_study',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();if(x.status==401||x.status==403){r.auth_failed=true;}else{r.last_study=JSON.parse(x.responseText);}"
                + "}"
                + "if(!r.auth_failed){"
                + "x.open('GET','/api/user/daily_stats',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();if(x.status==401||x.status==403){r.auth_failed=true;}else{r.daily_stats=JSON.parse(x.responseText);}"
                + "}"
                + "if(!r.auth_failed){"
                + "x.open('GET','/api/user/total_stats',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();if(x.status==401||x.status==403){r.auth_failed=true;}else{r.total_stats=JSON.parse(x.responseText);}"
                + "}"
                + "}catch(e){r.success=false;r.error=e.message;}"
                + "return JSON.stringify(r);"
                + "})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    Object parsed = new org.json.JSONTokener(result).nextValue();
                    String jsonStr = parsed instanceof String ? (String) parsed : String.valueOf(parsed);
                    // 从返回数据中提取 & 缓存 token 和 csrf_token（每次 requestAppData 都刷新）
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
                        // 检测 token 失效（API 返回 401）
                        if (obj.optBoolean("auth_failed", false)) {
                            cachedAuthToken = "";
                            cachedCsrfToken = "";
                            clearAuthCache();
                            showSessionExpiredDialog();
                            return;
                        }
                        // 检查响应中的 token 和本地缓存的 token（可能 Vue 已清除 localStorage）
                        String sentToken = obj.optString("access_token", "");
                        boolean hadTokenBefore = !cachedAuthToken.isEmpty();
                        // 如果之前有 token 但响应中 token 为空 → Vue 已清除 localStorage → session 肯定已过期
                        if (hadTokenBefore && sentToken.isEmpty()) {
                            cachedAuthToken = "";
                            cachedCsrfToken = "";
                            clearAuthCache();
                            showSessionExpiredDialog();
                            return;
                        }
                        // 注意：不再通过数据字段检测 token 过期（stats 可能为 0 而非过期），
                        // 401/403 已在 JS 侧通过 auth_failed 检测。
                        // 更新缓存的 token
                        if (obj.has("access_token")) {
                            String t = obj.optString("access_token", "");
                            if (!t.isEmpty()) { cachedAuthToken = t; }
                        }
                        // 更新缓存的 csrf_token
                        if (obj.has("csrf_token")) {
                            String c = obj.optString("csrf_token", "");
                            if (!c.isEmpty()) { cachedCsrfToken = c; }
                        }
                        if (obj.has("access_token") || obj.has("csrf_token")) {
                            saveAuthCache();
                        }
                        // 注入收藏数
                        obj.put("favoritesCount", favoritesManager != null ? favoritesManager.getCount() : 0);
                        jsonStr = obj.toString();
                    } catch (Exception ignored) {}
                    sendAppFrontendData(jsonStr);
                } catch (Exception e) {
                    sendAppFrontendData("{\"success\":false,\"error\":\"parse\"}");
                }
            });
        });
    }

    private void sendAppFrontendData(String json) {
        if (webView == null) return;
        String b64 = android.util.Base64.encodeToString((json == null ? "{}" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.evaluateJavascript("javascript:(function(){if(window.onAppData){window.onAppData('" + b64 + "');}})()", null);
    }

    private void submitLoginCodeFromLocalPage(String code) {
        if (code == null || code.trim().isEmpty()) {
            notifyLoginPage("请输入登录码", false);
            return;
        }
        if (!WebViewConfig.isNetworkAvailable()) {
            notifyLoginPage("网络连接异常", false);
            return;
        }
        final String finalCode = code.trim();
        notifyLoginPage("正在登录…", true);
        // 用新线程执行 HTTP 请求，不阻塞 UI
        new Thread(() -> {
            try {
                URL url = new URL("https://cxyonly.fans/api/auth/login");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                String body = "{\"verification_code\":\"" + escapeJsonString(finalCode) + "\",\"login_mode\":\"new\"}";
                OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), "UTF-8");
                writer.write(body);
                writer.flush();
                writer.close();
                int httpCode = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();
                conn.disconnect();
                String json = response.toString();
                // 解析 JSON 响应
                org.json.JSONObject root = new org.json.JSONObject(json);
                int codeVal = root.optInt("code", -1);
                if (codeVal == 0) {
                    org.json.JSONObject data = root.optJSONObject("data");
                    if (data == null) data = root;
                    String token = data.optString("access_token", "");
                    if (token.isEmpty()) token = data.optString("token", "");
                    if (!token.isEmpty()) {
                        final String finalToken = token;
                        final String finalUser = data.has("user") ? data.get("user").toString() : "";
                        final String finalCsrf = data.optString("csrf_token", "");
                        // 回到主线程：先预热后台 WebView，写入 token，再走统一检测路由
                        mainHandler.post(() -> finishLoginWithToken(finalToken, finalUser, finalCsrf));
                        return;
                    }
                }
                // 登录失败
                final String errMsg = root.optString("message", root.optString("error", "登录失败（" + httpCode + "）"));
                mainHandler.post(() -> notifyLoginPage(errMsg, false));
            } catch (Exception e) {
                final String err = e.getMessage() != null ? e.getMessage() : "网络请求异常";
                mainHandler.post(() -> notifyLoginPage(err, false));
            }
        }).start();
    }

    private void finishLoginWithToken(String token, String user, String csrf) {
// 0. 缓存 token 和 csrf 供后续直连 HTTP 使用
cachedAuthToken = token != null ? token : "";
cachedCsrfToken = csrf != null ? csrf : "";
saveAuthCache();
        // 1. 预热：确保后台 WebView 已在 cxyonly.fans 域
        warmAuthWebView(() -> {
            // 2. 现在域已正确，写入 token 到 localStorage
            WebView authView = getAuthWebView();
            if (authView == null) {
                notifyLoginPage("登录环境未就绪", false);
                return;
            }
            StringBuilder js = new StringBuilder();
            js.append("javascript:(function(){");
            js.append("localStorage.setItem('daguan_token','").append(escapeJsString(token)).append("');");
            if (user != null && !user.isEmpty()) {
                js.append("try{localStorage.setItem('daguan_user',").append(user).append(");}catch(e){}");
            }
            if (csrf != null && !csrf.isEmpty()) {
                js.append("localStorage.setItem('csrf_token','").append(escapeJsString(csrf)).append("');");
            }
            js.append("return 'ok';})()");
            authView.evaluateJavascript(js.toString(), null);
            // 3. 登录后立即获取 csrf token 并缓存
            mainHandler.postDelayed(() -> fetchAndCacheCsrf(), 600);
            // 4. 写入后稍等片刻，调用 checkLoginAndRoute 检测 token 并跳转
            mainHandler.postDelayed(this::checkLoginAndRoute, 400);
        });
    }

    private void notifyLoginPage(String message, boolean success) {
        if (webView == null) return;
        String js = "javascript:(function(){if(window.onNativeLoginResult){window.onNativeLoginResult('"
                + escapeJsString(message == null ? "" : message) + "'," + success + ");}})()";
        webView.evaluateJavascript(js, null);
    }

    private void sendClipboardCodeToLoginPage() {
        if (webView == null) return;
        String code = readLoginCodeFromClipboard();
        if (code == null || code.isEmpty()) {
            notifyLoginPage("剪贴板未检测到登录码", false);
            return;
        }
        String js = "javascript:(function(){if(window.fillLoginCode){window.fillLoginCode('" + escapeJsString(code) + "');}})()";
        webView.evaluateJavascript(js, null);
    }

    private String readLoginCodeFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return null;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) return null;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("登录码[：:]\\s*([a-zA-Z0-9]+)").matcher(clip.getItemAt(0).getText().toString());
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void loadPracticeFrontend(String categoryId, String questionId) {
        if (webView == null || categoryId == null || categoryId.trim().isEmpty()) return;
        String cur = webView.getUrl();
        if (cur != null && !cur.startsWith("file:///android_asset/practice_frontend")) {
            pageHistory.push(cur);
        }
        isAutoRedirect = false;
        showLoadingOverlay(false);
        // 先从 auth WebView 获取 access_token 和 csrf_token，再加载练习页
        final WebView authView = getAuthWebView();
        if (authView != null) {
            String js = "javascript:(function(){return JSON.stringify({token:localStorage.getItem('daguan_token')||'',csrf:localStorage.getItem('csrf_token')||''});})()";
            authView.evaluateJavascript(js, tokenResult -> {
                String token = "";
                String csrf = "";
                try {
                    org.json.JSONObject info = new org.json.JSONObject(tokenResult != null ? tokenResult : "{}");
                    token = info.optString("token", "");
                    csrf = info.optString("csrf", "");
                } catch (Exception ignored) {}
                // ★ 关键修复：WebView 的 token 被 Vue 清掉，但 Java 有缓存 → 回注回 WebView
                if (token.isEmpty() && !cachedAuthToken.isEmpty()) {
                    token = cachedAuthToken;
                    String injectJs = "javascript:(function(){"
                        + "localStorage.setItem('daguan_token','" + escapeJsString(token) + "');"
                        + "return 'ok';})()";
                    authView.evaluateJavascript(injectJs, null);
                }
                // ★ 双重保障：如果都为空，从 SharedPreferences 再读一次
                if (token.isEmpty()) {
                    restoreAuthCache();
                    if (!cachedAuthToken.isEmpty()) {
                        token = cachedAuthToken;
                    }
                }
                if (!token.isEmpty()) cachedAuthToken = token;
                if (!csrf.isEmpty()) { cachedCsrfToken = csrf; saveAuthCache(); }
                StringBuilder json = new StringBuilder();
                json.append("{\"categoryId\":\"").append(escapeJsonString(categoryId.trim())).append("\"");
                json.append(",\"access_token\":\"").append(escapeJsonString(token)).append("\"");
                if (questionId != null && !questionId.trim().isEmpty()) {
                    json.append(",\"questionId\":\"").append(escapeJsonString(questionId.trim())).append("\"");
                }
                json.append("}");
                String b64 = android.util.Base64.encodeToString(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                webView.loadUrl("file:///android_asset/practice_frontend.html#data=" + b64);
            });
        } else {
            StringBuilder json = new StringBuilder();
            json.append("{\"categoryId\":\"").append(escapeJsonString(categoryId.trim())).append("\"");
            if (questionId != null && !questionId.trim().isEmpty()) {
                json.append(",\"questionId\":\"").append(escapeJsonString(questionId.trim())).append("\"");
            }
            json.append("}");
            String b64 = android.util.Base64.encodeToString(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            webView.loadUrl("file:///android_asset/practice_frontend.html#data=" + b64);
        }
    }

    private void fetchPracticeData(String categoryId) {
        if (categoryId == null || categoryId.trim().isEmpty() || getAuthWebView() == null) return;
        if (!WebViewConfig.isNetworkAvailable()) {
            sendPracticeData("{\"success\":false,\"error\":\"网络连接异常\"}");
            return;
        }
        String safeId = escapeJsString(categoryId.trim());
        final WebView authView = getAuthWebView();
        Runnable doFetch = () -> {
            // 确保 WebView 中有 token（可能被 Vue 清掉）
            String ensureTokenJs = "javascript:(function(){"
                + "var tk=localStorage.getItem('daguan_token');"
                + "if(!tk || tk==''){localStorage.setItem('daguan_token','" + escapeJsString(cachedAuthToken) + "');}"
                + "return 'ok';})()";
            authView.evaluateJavascript(ensureTokenJs, null);
            // 同步 XMLHttpRequest + Authorization header
            String js = "javascript:(function(){"
                + "var token=localStorage.getItem('daguan_token')||'';"
                + "var h=token?{'Authorization':'Bearer '+token}:{};"
                + "try{var x=new XMLHttpRequest();"
                + "x.open('GET','/api/questions?category_id=" + safeId + "&include_children=true&page=1&per_page=20&sort=mobile',false);"
                + "Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});"
                + "x.send();return x.responseText;"
                + "}catch(e){return JSON.stringify({success:false,error:e.message});}"
                + "})()";
            authView.evaluateJavascript(js, result -> {
                try {
                    Object parsed = new org.json.JSONTokener(result).nextValue();
                    sendPracticeData(parsed instanceof String ? (String) parsed : String.valueOf(parsed));
                } catch (Exception e) {
                    sendPracticeData("{\"success\":false,\"error\":\"题目数据解析失败\"}");
                }
            });
        };
        String url = authView.getUrl();
        if (url != null && url.startsWith("https://cxyonly.fans")) {
            doFetch.run();
        } else {
            authView.loadUrl(HOME_PAGE_URL);
            mainHandler.postDelayed(doFetch, 900);
        }
    }

    private void sendPracticeData(String json) {
        if (webView == null) return;
        String b64 = android.util.Base64.encodeToString((json == null ? "{}" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.evaluateJavascript("javascript:(function(){if(window.onPracticeData){window.onPracticeData('" + b64 + "');}})()", null);
    }

    // ==================== 交互按钮状态操作 ====================
    private void notifyActionComplete() {
        // Java → JS 回调：通知前端刷新按钮状态
        if (webView != null) {
            webView.evaluateJavascript("javascript:(function(){try{if(window.onPracticeActionComplete)window.onPracticeActionComplete();}catch(e){}})()", null);
        }
    }

    private void notifyActionCompleteWithData(String responseBody) {
        if (webView != null) {
            if (responseBody != null && !responseBody.isEmpty()) {
                String b64 = android.util.Base64.encodeToString(responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                webView.evaluateJavascript("javascript:(function(){try{if(window.onPracticeActionCompleteWithData)window.onPracticeActionCompleteWithData('" + b64 + "');}catch(e){}})()", null);
            } else {
                notifyActionComplete();
            }
        }
    }

    private void performPracticeAction(String action, String questionId) {
        if (action == null || action.trim().isEmpty() || !WebViewConfig.isNetworkAvailable()) {
            notifyActionComplete();
            return;
        }
        String safe = action.trim();
        String token = cachedAuthToken;
        String csrf = cachedCsrfToken;
        // ⚠️ 不要在 @JavascriptInterface 中调用 getTokenFromAuthWebView（会导致死锁）
        // 笔记操作：/api/v1/ 端点只认 cookie 中的 CSRF，不走直连 HTTP
        if (safe.startsWith("note|")) {
            executeInAuthWebView(safe);
            return;
        }
        // API 强制要求 X-CSRF-Token 头（无则 403），所以必须 csrf 非空才能直连
        if (token == null || token.isEmpty() || csrf == null || csrf.isEmpty()) {
            // csrf 为空时走页面刷新 + 重试
            refreshPageAndRetry(safe);
            return;
        }

        // 使用直连 HTTP（更快，不依赖 WebView 状态）
        executePracticeHttp(safe, token, csrf, false);
    }

    // 执行直连 HTTP 交互请求，onFailureRefresh=true 时失败后自动刷新页面重试
    private void executePracticeHttp(String safeAction, String token, String csrf, boolean onFailureRefresh) {
        new Thread(() -> {
            try {
                String[] parts = safeAction.split("\\|");
                if (parts.length < 2) { mainHandler.post(MainActivity.this::notifyActionComplete); return; }
                String act = parts[0];
                String id = parts[1];

                java.net.URL url;
                String jsonBody;
                String method;
                if ("note".equals(act) && parts.length >= 3) {
                    String txt = java.net.URLDecoder.decode(parts[2], "UTF-8");
                    url = new java.net.URL("https://cxyonly.fans/api/v1/user/questions/" + id + "/note");
                    jsonBody = "{\"note\":" + org.json.JSONObject.quote(txt) + "}";
                    method = "PUT";
                } else {
                    url = new java.net.URL("https://cxyonly.fans/api/questions/" + id + "/state");
                    if ("fav_true".equals(act)) jsonBody = "{\"is_favorite\":true}";
                    else if ("fav_false".equals(act)) jsonBody = "{\"is_favorite\":false}";
                    else if ("mastered".equals(act)) jsonBody = "{\"mastery\":\"mastered\"}";
                    else if ("needs_practice".equals(act)) jsonBody = "{\"mastery\":\"needs_practice\"}";
                    else if ("not_known".equals(act)) jsonBody = "{\"mastery\":\"not_known\"}";
                    else if ("not_started".equals(act)) jsonBody = "{\"mastery\":\"not_started\"}";
                    else { mainHandler.post(MainActivity.this::notifyActionComplete); return; }
                    method = "PATCH";
                }
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("X-CSRF-Token", csrf);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.getOutputStream().write(jsonBody.getBytes("UTF-8"));
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code >= 200 && code < 300) {
                    // 成功 → 读取响应体，传给前端更新云端状态
                    String responseBody = "";
                    try {
                        java.io.InputStream is = conn.getInputStream();
                        java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
                        responseBody = s.hasNext() ? s.next() : "";
                        is.close();
                    } catch (Exception ignored) {}
                    final String resp = responseBody;
                    mainHandler.post(() -> notifyActionCompleteWithData(resp));
                    return;
                }
                // 401 → token 过期，弹窗引导重新登录
                if (code == 401) {
                    cachedAuthToken = "";
                    cachedCsrfToken = "";
                    clearAuthCache();
                    mainHandler.post(MainActivity.this::showSessionExpiredDialog);
                    return;
                }
                // 403 或其他错误 → 清空 csrf 缓存
                cachedCsrfToken = "";
                saveAuthCache();
            } catch (Exception ignored) {
                cachedCsrfToken = "";
                saveAuthCache();
            }
            // 直连失败 → 刷新页面获取新鲜 csrf 再重试（仅首次）
            if (onFailureRefresh) {
                // 已经用新鲜 csrf 重试过仍失败 → token 很可能已过期
                mainHandler.post(MainActivity.this::showSessionExpiredDialog);
            } else {
                mainHandler.post(() -> refreshPageAndRetry(safeAction));
            }
        }).start();
    }

    // 刷新 background WebView 页面获取新鲜 csrf，然后重试直连 HTTP
    private void refreshPageAndRetry(String safeAction) {
        WebView av = getAuthWebView();
        if (av == null || !WebViewConfig.isNetworkAvailable()) { notifyActionComplete(); return; }
        // 记住当前 csrf，用于后续检测是否已刷新
        final String oldCsrf = cachedCsrfToken;
        // 强制加载最新页面，Vue 初始化后会写入新鲜 csrf_token（时间戳会变）
        av.loadUrl(HOME_PAGE_URL);
        // 轮询等待 csrf 变化（Vue 完成后 csrf 的时间戳改变），最长约 6s
        pollForFreshCsrf(safeAction, oldCsrf, 0);
    }

    // 轮询检测 csrf_token 是否已被 Vue 刷新（通过比较新旧值变化）
    private void pollForFreshCsrf(String safeAction, String oldCsrf, int attempt) {
        if (!WebViewConfig.isNetworkAvailable()) { notifyActionComplete(); return; }
        if (attempt > 12) { // ~6s 超时：12 * 500ms
            // csrf 一直没有变化 → Vue 未生成新 csrf → token 很可能已过期
            showSessionExpiredDialog();
            return;
        }
        WebView av = getAuthWebView();
        if (av == null) { notifyActionComplete(); return; }
        String js = "javascript:(function(){"
            + "var csrf=localStorage.getItem('csrf_token')||'';"
            + "if(!csrf){var c=document.cookie.split(';');for(var i=0;i<c.length;i++){var t=c[i].trim();if(t.indexOf('csrf_token=')===0){csrf=t.substring(11);break;}}}"
            + "return csrf;})()";
        av.evaluateJavascript(js, r -> {
            String cs = (r != null && !"null".equals(r)) ? r.replaceAll("^\"|\"$", "") : "";
            // csrf 已变化（Vue 写入新值） 或 之前为空现在有值 → 认为刷新成功
            boolean isFresh = !cs.isEmpty() && (oldCsrf.isEmpty() || !cs.equals(oldCsrf));
            if (isFresh) {
                cachedCsrfToken = cs;
                saveAuthCache();
                executePracticeHttp(safeAction, cachedAuthToken, cs, true);
            } else {
                // 还没变化 → 继续轮询
                mainHandler.postDelayed(() -> pollForFreshCsrf(safeAction, oldCsrf, attempt + 1), 500);
            }
        });
    }

    // 异步从 WebView 获取 csrf_token 并缓存到 cachedCsrfToken
    private void fetchAndCacheCsrf() {
        WebView av = getAuthWebView();
        if (av == null || !WebViewConfig.isNetworkAvailable()) return;
        String url = av.getUrl();
        if (url == null || !url.startsWith("https://cxyonly.fans")) {
            av.loadUrl(HOME_PAGE_URL);
            mainHandler.postDelayed(this::fetchAndCacheCsrf, 900);
            return;
        }
        String js = "javascript:(function(){"
            + "var csrf=localStorage.getItem('csrf_token')||'';"
            + "if(!csrf){var c=document.cookie.split(';');for(var i=0;i<c.length;i++){var t=c[i].trim();if(t.indexOf('csrf_token=')===0){csrf=t.substring(11);break;}}}"
            + "return csrf;})()";
        av.evaluateJavascript(js, r -> {
            if (r != null && !"null".equals(r)) {
                String cs = r.replaceAll("^\"|\"$", "");
                if (!cs.isEmpty()) { cachedCsrfToken = cs; saveAuthCache(); }
            }
        });
    }

    // 在后台 WebView 中执行 action JS（回退方案）
    private void executeInAuthWebView(String safeAction) {
        WebView authView = getAuthWebView();
        if (authView == null) return;
        String safe = escapeJsString(safeAction);
        String js = "javascript:(function(){"
            + "var token=localStorage.getItem('daguan_token')||'';"
            + "var csrf=localStorage.getItem('csrf_token')||'';"
            + "if(!csrf){var c=document.cookie.split(';');for(var i=0;i<c.length;i++){var t=c[i].trim();if(t.indexOf('csrf_token=')===0){csrf=t.substring(11);break;}}}"
            + "var h=token?{'Authorization':'Bearer '+token}:{};"
            + "if(csrf)h['X-CSRF-Token']=csrf;"
            + "var act='',id='';var p='" + safe + "'.split('|');"
            + "act=p[0];id=p[1];"
            + "if(act==='note'&&p.length>=3){"
            +   "var txt=decodeURIComponent(p.slice(2).join('|'));"
            +   "var x=new XMLHttpRequest();"
            +   "x.open('PUT','/api/v1/user/questions/'+id+'/note',false);"
            +   "x.setRequestHeader('Content-Type','application/json');"
            +   "Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});"
            +   "x.send(JSON.stringify({note:txt}));"
            +   "return x.status;"
            + "}"
            + "var body={};"
            + "if(act==='fav_true'){body={is_favorite:true};}"
            + "else if(act==='fav_false'){body={is_favorite:false};}"
            + "else if(act==='mastered'){body={mastery:'mastered'};}"
            + "else if(act==='needs_practice'){body={mastery:'needs_practice'};}"
            + "else if(act==='not_known'){body={mastery:'not_known'};}"
            + "else if(act==='not_started'){body={mastery:'not_started'};}"
            + "else{return;}"
            + "var x=new XMLHttpRequest();x.open('PATCH','/api/questions/'+id+'/state',false);"
            + "x.setRequestHeader('Content-Type','application/json');"
            + "Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});"
            + "x.send(JSON.stringify(body));"
            + "return x.status;"
            + "})()";
        // 检查 WebView 域名，确保能执行 JS
        String url = authView.getUrl();
        if (url != null && url.startsWith("https://cxyonly.fans")) {
            authView.evaluateJavascript(js, null);
        } else {
            authView.loadUrl("https://cxyonly.fans/m/home");
            mainHandler.postDelayed(() -> authView.evaluateJavascript(js, null), 1200);
        }
    }

    private String getTokenFromAuthWebView() {
        WebView authView = getAuthWebView();
        if (authView == null) return "";
        final String[] result = {""};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mainHandler.post(() -> {
            authView.evaluateJavascript("javascript:(function(){return localStorage.getItem('daguan_token')||'';})()", r -> {
                if (r != null && !"null".equals(r)) {
                    result[0] = r.replaceAll("^\"|\"$", "");
                }
                latch.countDown();
            });
        });
        try { latch.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return result[0];
    }

    private String getCsrfFromAuthWebView() {
  // ⚠️ 同步版本已弃用（会死锁），使用 asyncCsrfFetch 替代
  return cachedCsrfToken != null ? cachedCsrfToken : "";
}
// 异步从 WebView 获取 csrf_token（localStorage + cookie 双重检查）
private void asyncCsrfFetch() {
  WebView authView = getAuthWebView();
  if (authView == null) return;
  mainHandler.post(() -> {
    String js = "javascript:(function(){"
      + "var csrf=localStorage.getItem('csrf_token')||'';"
      + "if(!csrf){var c=document.cookie.split(';');for(var i=0;i<c.length;i++){var t=c[i].trim();if(t.indexOf('csrf_token=')===0){csrf=t.substring(11);break;}}}"
      + "return csrf;})()";
    authView.evaluateJavascript(js, r -> {
      if (r != null && !"null".equals(r)) {
        String cs = r.replaceAll("^\"|\"$", "");
        if (!cs.isEmpty()) cachedCsrfToken = cs;
      }
    });
  });
}

    private void fetchHistoryData() {
        if (webView == null || getAuthWebView() == null || !WebViewConfig.isNetworkAvailable()) {
            sendHistoryData("{\"success\":false,\"error\":\"offline\"}");
            return;
        }
        warmAuthWebView(() -> {
            String js = "javascript:(function(){"
                + "var token=localStorage.getItem('daguan_token')||'';"
                + "var r={success:true,access_token:token,events:[],last_study:{},daily_stats:{}};"
                + "var h=token?{'Authorization':'Bearer '+token}:{};"
                + "try{"
                + "var x=new XMLHttpRequest();"
                + "x.open('GET','/api/user/practice_events/recent?per_page=50',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();r.events=JSON.parse(x.responseText);"
                + "x.open('GET','/api/questions/user/last_study',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();r.last_study=JSON.parse(x.responseText);"
                + "x.open('GET','/api/user/daily_stats',false);Object.keys(h).forEach(function(k){x.setRequestHeader(k,h[k]);});x.send();r.daily_stats=JSON.parse(x.responseText);"
                + "}catch(e){r.success=false;r.error=e.message;}"
                + "return JSON.stringify(r);"
                + "})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                try {
                    Object parsed = new org.json.JSONTokener(result).nextValue();
                    sendHistoryData(parsed instanceof String ? (String) parsed : String.valueOf(parsed));
                } catch (Exception e) {
                    sendHistoryData("{\"success\":false,\"error\":\"parse\"}");
                }
            });
        });
    }

    private void sendHistoryData(String json) {
        if (webView == null) return;
        String b64 = android.util.Base64.encodeToString((json == null ? "{}" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.evaluateJavascript("javascript:(function(){if(window.onHistoryData){window.onHistoryData('" + b64 + "');}})()", null);
    }

    private static String escapeJsString(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escapeJsonString(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ==================== 登录检测 ====================
    private void startSilentLoginCheck() {
        if (loginCheckInProgress || isLoggedIn) return;
        if (!WebViewConfig.isNetworkAvailable()) return;
        loginCheckInProgress = true;
        warmAuthWebView(() -> {
            String js = "javascript:(function(){var token=localStorage.getItem('daguan_token');return token?'logged_in':'not_logged_in';})()";
            getAuthWebView().evaluateJavascript(js, result -> {
                loginCheckInProgress = false;
                String r = result != null ? result.replaceAll("\"", "") : "";
                if ("logged_in".equals(r)) {
                    isLoggedIn = true; loginGuideShown = false; showLoadingOverlay(false); isAutoRedirect = true; loadingStartTime = System.currentTimeMillis();
                    mainHandler.postDelayed(MainActivity.this::loadAppFrontend, 1500);
                    mainHandler.postDelayed(() -> { startLoginWatchdog(); startFavoritesPeriodicSync(); }, 2000);
                } else {
                    goToLoginFlow();
                }
            });
        });
    }

    private void goToLoginFlow() {
        isLoggedIn = false;
        loginGuideShown = false;
        showLoadingOverlay(false);
        isAutoRedirect = false;
        mainHandler.postDelayed(this::loadLoginFrontend, 300);
    }

    private void logout() {
isLoggedIn = false;
loginGuideShown = false;
loginCheckInProgress = false;
cachedAuthToken = "";
cachedCsrfToken = "";
clearAuthCache();
stopLoginWatchdog();
        stopFavoritesPeriodicSync();
        WebView authView = getAuthWebView();
        if (authView != null) {
            authView.evaluateJavascript("javascript:(function(){localStorage.removeItem('daguan_token');localStorage.removeItem('daguan_user');localStorage.removeItem('csrf_token');return 'ok';})()", null);
        }
        clearAuthCookies();
        loadLoginFrontend();
    }

    private void clearAuthCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setCookie("https://cxyonly.fans", "daguan_token=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "csrf_token=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "session=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "access_token=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "refresh_token=; Max-Age=0; Path=/");
            cm.setCookie("https://cxyonly.fans", "daguan_token=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.setCookie("https://cxyonly.fans", "csrf_token=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.setCookie("https://cxyonly.fans", "session=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.setCookie("https://cxyonly.fans", "access_token=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.setCookie("https://cxyonly.fans", "refresh_token=; Max-Age=0; Path=/; Domain=.cxyonly.fans");
            cm.removeSessionCookies(null);
            cm.flush();
        } catch (Exception ignored) { }
    }

    // ==================== 网络恢复重试 ====================
    private void startNetworkRetry() {
        stopNetworkRetry();
        networkRetryRunnable = () -> {
            if (WebViewConfig.isNetworkAvailable()) {
                warmAuthWebView(() -> mainHandler.postDelayed(this::checkLoginAndRoute, 800));
                return;
            }
            mainHandler.postDelayed(networkRetryRunnable, 1500);
        };
        mainHandler.postDelayed(networkRetryRunnable, 1500);
    }
    private void stopNetworkRetry() { if (networkRetryRunnable != null) { mainHandler.removeCallbacks(networkRetryRunnable); networkRetryRunnable = null; } }

    // ==================== Watchdog ====================
    private void startLoginWatchdog() {
        stopLoginWatchdog();
        loginWatchdogRunnable = new Runnable() {
            int missCount = 0;
            @Override
            public void run() {
                if (getAuthWebView() == null) return;
                if (!WebViewConfig.isNetworkAvailable()) { mainHandler.postDelayed(this, 1500); return; }
                String js = "javascript:(function(){var token=localStorage.getItem('daguan_token');return token?'logged_in':'not_logged_in';})()";
                getAuthWebView().evaluateJavascript(js, result -> {
                    String r = result != null ? result.replaceAll("\"", "") : "";
                    if ("logged_in".equals(r)) { missCount = 0; }
                    else if (isLoggedIn) { missCount++; if (missCount >= 3) { missCount = 0; handleTokenLost(); return; } }
                    mainHandler.postDelayed(this, 1500);
                });
            }
        };
        mainHandler.postDelayed(loginWatchdogRunnable, 1500);
    }
    private void stopLoginWatchdog() { if (loginWatchdogRunnable != null) { mainHandler.removeCallbacks(loginWatchdogRunnable); loginWatchdogRunnable = null; } }

    private void handleTokenLost() {
        showSessionExpiredDialog();
    }

    // 弹窗提示登录状态已失效，让用户选择是否重新登录
    private void showSessionExpiredDialog() {
        if (!isLoggedIn) return;
        // 断网时不弹窗（可能只是网络错误，不是 session 过期）
        if (!WebViewConfig.isNetworkAvailable()) {
            return;
        }
        isLoggedIn = false;
        loginGuideShown = false;
        stopLoginWatchdog();
        stopFavoritesPeriodicSync();
        cachedAuthToken = "";
        cachedCsrfToken = "";
        clearAuthCache();
        // 清除后台 WebView 中的 token/csrf
        WebView av = getAuthWebView();
        if (av != null) {
            av.evaluateJavascript("javascript:(function(){localStorage.removeItem('daguan_token');localStorage.removeItem('csrf_token');return 'ok';})()", null);
        }
        clearAuthCookies();
        mainHandler.post(() -> {
            Dialog dialog = new Dialog(MainActivity.this);
            dialog.setContentView(R.layout.dialog_session_expired);
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setDimAmount(0.6f);
            }
            Button btnRelogin = dialog.findViewById(R.id.btnRelogin);
            Button btnCancel = dialog.findViewById(R.id.btnCancel);
            if (btnRelogin != null) {
                btnRelogin.setOnClickListener(v -> {
                    dialog.dismiss();
                    isAutoRedirect = true;
                    loadingStartTime = System.currentTimeMillis();
                    showLoadingOverlay(true);
                    loadLoginFrontend();
                });
            }
            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> {
                    dialog.dismiss();
                    loadAppFrontend();
                });
            }
            dialog.show();
        });
    }

    // ==================== 弹窗 / 微信 ====================
    private void showLoginCodeGuide() {
        if (isLoggedIn || loginGuideShown) return;
        loginGuideShown = true;
        mainHandler.post(() -> {
            Dialog dialog = new Dialog(MainActivity.this);
            dialog.setContentView(R.layout.dialog_login_guide);
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) { dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); dialog.getWindow().setDimAmount(0.6f); }
            Button btnW = dialog.findViewById(R.id.btnWechat);
            Button btnC = dialog.findViewById(R.id.btnCancel);
            if (btnW != null) btnW.setOnClickListener(v -> { dialog.dismiss(); openWeChat(); });
            if (btnC != null) btnC.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        });
    }
    private void openWeChat() {
        if (weChatOpening) return;
        if (!isPackageInstalled(WECHAT_PACKAGE)) { toast("\u26a0\ufe0f 未检测到微信客户端"); return; }
        weChatOpening = true; mainHandler.postDelayed(() -> weChatOpening = false, 3000);
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(WECHAT_PACKAGE);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(i); toast("请在微信中搜索「澄潇宇」公众号"); return; }
        } catch (Exception ignored) { }
        weChatOpening = false; toast("\u26a0\ufe0f 无法打开微信");
    }
    private boolean isPackageInstalled(String pkg) { try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; } }

    // ==================== 下拉 / 重试 ====================
    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(getColor(R.color.accent), getColor(R.color.accent_light));
        swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.dark_card));
        swipeRefresh.setOnRefreshListener(() -> {
            if (!isLoading) { errorOverlay.setVisibility(View.GONE); if (loginHelper != null) loginHelper.reset(); loginGuideShown = false; isLoggedIn = false; loginCheckInProgress = false; stopFavoritesPeriodicSync(); stopLoginWatchdog(); startSilentStartupCheck(); }
            mainHandler.postDelayed(() -> { if (swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(false); }, 5000);
        });
    }
    private void setupErrorRetry() {
        retryBtn.setOnClickListener(v -> { errorOverlay.setVisibility(View.GONE); WebViewConfig.clearCache(webView); loginGuideShown = false; isLoggedIn = false; loginCheckInProgress = false; stopFavoritesPeriodicSync(); stopLoginWatchdog(); startSilentStartupCheck(); });
    }

    // 优化：动画复用，避免频繁创建对象
    private android.animation.ValueAnimator progressAnimator;
    private int fullProgressWidth = -1;

    // ==================== 进度条 / 加载 / Toast ====================
    private void animateProgressBar(int target) {
        if (target < currentProgress) currentProgress = target;
        if (target >= PROGRESS_MAX) { currentProgress = PROGRESS_MAX; updateProgressWidth(PROGRESS_MAX); return; }
        
        // 优化：使用属性动画替代频繁的 Handler 消息轮询，平滑更新并避免主线程阻塞
        if (progressAnimator != null && progressAnimator.isRunning()) {
            progressAnimator.cancel();
        }
        
        progressAnimator = android.animation.ValueAnimator.ofInt(currentProgress, target);
        progressAnimator.setDuration((target - currentProgress) * 5L);
        progressAnimator.addUpdateListener(animation -> {
            int val = (int) animation.getAnimatedValue();
            currentProgress = val;
            updateProgressWidth(val);
        });
        progressAnimator.start();
    }
    private void updateProgressWidth(int p) {
        if (progressBar == null) return;
        
        // 优化：通过 scaleX 替代修改 LayoutParams 导致的全屏 relayout，极大解决过度绘制和卡顿
        if (fullProgressWidth <= 0) {
            fullProgressWidth = progressBar.getParent() instanceof View ? ((View) progressBar.getParent()).getWidth() : getResources().getDisplayMetrics().widthPixels;
            if (fullProgressWidth <= 0) fullProgressWidth = getResources().getDisplayMetrics().widthPixels;
            
            ViewGroup.LayoutParams lp = progressBar.getLayoutParams();
            if (lp != null) {
                lp.width = fullProgressWidth;
                progressBar.setLayoutParams(lp);
                progressBar.setPivotX(0f);
            }
        }
        
        float scale = (float) p / PROGRESS_MAX;
        progressBar.setScaleX(scale);
    }
    private void showLoadingOverlay(boolean show) {
        if (show) {
            if (loadingOverlay.getVisibility() != View.VISIBLE || loadingOverlay.getAlpha() < 1f) {
                loadingOverlay.setVisibility(View.VISIBLE);
                loadingOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null); // 优化：动画期间开启硬件加速
                loadingOverlay.animate().alpha(1f).setDuration(200).withEndAction(() -> loadingOverlay.setLayerType(View.LAYER_TYPE_NONE, null)).start();
            }
            View logo = findViewById(R.id.loadingLogo);
            if (logo != null && logo.getAnimation() == null) { Animation anim = AnimationUtils.loadAnimation(this, R.anim.rotate_loader); logo.startAnimation(anim); }
        } else {
            if (loadingOverlay.getVisibility() == View.VISIBLE) {
                loadingOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null); // 优化：动画期间开启硬件加速
                loadingOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> { loadingOverlay.setVisibility(View.GONE); loadingOverlay.setLayerType(View.LAYER_TYPE_NONE, null); }).start();
            }
            View logo = findViewById(R.id.loadingLogo); if (logo != null) logo.clearAnimation();
        }
    }
    private void showErrorPage(String info) { 
        if (errorMsg != null && info != null) errorMsg.setText(info); 
        errorOverlay.setVisibility(View.VISIBLE); errorOverlay.setAlpha(0f); 
        errorOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null); // 优化：动画期间开启硬件加速
        errorOverlay.animate().alpha(1f).setDuration(300).withEndAction(() -> errorOverlay.setLayerType(View.LAYER_TYPE_NONE, null)).start(); 
    }
    private void toast(String msg) { cxyonly.fans.util.DarkToast.show(this, msg); }

    // ==================== 返回拦截 ====================
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { if (keyCode == KeyEvent.KEYCODE_BACK) return handleBackPress(); return super.onKeyDown(keyCode, event); }
    private boolean handleBackPress() {
        if (errorOverlay.getVisibility() == View.VISIBLE) { errorOverlay.setVisibility(View.GONE); loginGuideShown = false; isLoggedIn = false; loginCheckInProgress = false; stopFavoritesPeriodicSync(); stopLoginWatchdog(); startSilentStartupCheck(); return true; }
        if (webView != null && webView.canGoBack()) { webView.goBack(); return true; }
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime > 2000) { lastBackPressTime = now; toast("再按一次退出"); return true; }
        finishAffinity(); return true;
    }

    // ==================== 收藏 ====================
    private void startFavoritesSync() { if (favoritesManager != null && getAuthWebView() != null) favoritesManager.startSync(getAuthWebView()); }
    private void startFavoritesPeriodicSync() { stopFavoritesPeriodicSync(); favoritesSyncRunnable = () -> { syncFavoritesInBackground(false); mainHandler.postDelayed(favoritesSyncRunnable, FAV_SYNC_INTERVAL_MS); }; mainHandler.post(favoritesSyncRunnable); }
    private void stopFavoritesPeriodicSync() { if (favoritesSyncRunnable != null) { mainHandler.removeCallbacks(favoritesSyncRunnable); favoritesSyncRunnable = null; } }

    private void openFavoritesViewer() {
        if (webView == null || favoritesManager == null) return;
        String cur = webView.getUrl();
        if (cur != null && !cur.startsWith("file:///android_asset/favorites_viewer")) {
            pageHistory.push(cur);
        }
        loadFavoritesViewer();
        syncFavoritesInBackground(true);
        // 在线时刷新 csrf_token，供收藏页交互按钮使用（断网不刷新）
        if (WebViewConfig.isNetworkAvailable()) {
            warmAuthWebView(() -> mainHandler.postDelayed(this::fetchAndCacheCsrf, 200));
        }
    }

    private void loadFavoritesViewer() {
        if (webView == null || favoritesManager == null) return;
        webView.loadUrl("file:///android_asset/favorites_viewer.html");
    }

    private void syncFavoritesInBackground(boolean refreshIfVisible) {
        if (!isLoggedIn || !WebViewConfig.isNetworkAvailable() || favoritesManager == null || getAuthWebView() == null || favoritesManager.isSyncing()) return;
        favoritesManager.setListener(new FavoritesManager.SyncListener() {
            @Override public void onProgress(String message) { }
            @Override public void onComplete(int count) {
                favoritesManager.setListener(originalFavListener);
                if (refreshIfVisible && isFavoritesPageVisible()) { webView.evaluateJavascript("javascript:(function(){if(window.updateFavoritesData){window.updateFavoritesData();}})()", null); }
                else updateTopBarVisibility();
            }
            @Override public void onError(String error) {
                favoritesManager.setListener(originalFavListener);
            }
        });
        favoritesManager.startSync(getAuthWebView());
    }

    private boolean isFavoritesPageVisible() {
        return webView != null && webView.getUrl() != null && webView.getUrl().startsWith("file:///android_asset/favorites_viewer.html");
    }

    // ==================== 生命周期 ====================
    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        if (backgroundWebView != null) {
            backgroundWebView.onResume();
            backgroundWebView.resumeTimers();
        }
        if (!isLoggedIn && loginHelper != null) {
            mainHandler.postDelayed(() -> loginHelper.checkAndFillLoginCode(), 500);
        }
        if (isLoggedIn) {
            startLoginWatchdog();
            startFavoritesPeriodicSync();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        if (backgroundWebView != null) {
            backgroundWebView.onPause();
            backgroundWebView.pauseTimers();
        }
        stopLoginWatchdog();
        stopFavoritesPeriodicSync();
    }

    @Override
    protected void onDestroy() {
        stopLoginWatchdog();
        stopNetworkRetry();
        stopFavoritesPeriodicSync();
        if (webView != null) {
            webView.stopLoading();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        if (backgroundWebView != null) {
            backgroundWebView.stopLoading();
            backgroundWebView.removeAllViews();
            backgroundWebView.destroy();
            backgroundWebView = null;
        }
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (webView != null) webView.freeMemory();
        if (backgroundWebView != null) backgroundWebView.freeMemory();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (webView != null) {
            webView.requestLayout();
            if (isPageLoaded) {
                webView.evaluateJavascript("javascript:(function(){window.dispatchEvent(new Event('orientationchange'));})()", null);
            }
        }
        if (currentProgress > 0) updateProgressWidth(currentProgress);
    }
}