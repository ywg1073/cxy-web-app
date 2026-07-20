package cxyonly.fans;

import android.annotation.SuppressLint;
import android.app.Dialog;
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
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
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

    private WebView webView;
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

    private String lastPageUrl = null;
    private FavoritesManager favoritesManager;

    private int currentProgress = 0;
    private static final int PROGRESS_MAX = 100;
    private static final int PROGRESS_ANIM_DELAY = 16;
    private static final long FAV_SYNC_INTERVAL_MS = 30 * 1000;
    private Runnable favoritesSyncRunnable;

    private Runnable loginWatchdogRunnable;
    private Runnable networkRetryRunnable;
    private FavoritesManager.SyncListener originalFavListener;

    @SuppressLint({"SetJavaScriptEnabled", "MissingInflatedId"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupStatusBar();
        initViews();
        setupTopBar();
        setupWebView();
        setupSwipeRefresh();
        setupErrorRetry();

        loginHelper = new ClipboardLoginHelper(this, webView);
        loginHelper.setListener(new ClipboardLoginHelper.OnLoginCodeListener() {
            @Override public void onCodeDetected(String code) { }
            @Override public void onCodeFilled() { }
        });

        showLoadingOverlay(true);
        loadingStartTime = System.currentTimeMillis();
        isAutoRedirect = true;
        webView.loadUrl(HOME_URL);

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void closeFavorites() {
                mainHandler.post(() -> {
                    isAutoRedirect = true;
                    loadingStartTime = System.currentTimeMillis();
                    showLoadingOverlay(true);
                    mainHandler.postDelayed(() -> {
                        if (lastPageUrl != null && !lastPageUrl.isEmpty() && !lastPageUrl.startsWith("file://")) {
                            webView.loadUrl(lastPageUrl);
                        } else {
                            webView.loadUrl(HOME_URL);
                        }
                    }, 400);
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
        loadingOverlay = findViewById(R.id.loadingOverlay);
        errorOverlay = findViewById(R.id.errorOverlay);
        errorMsg = findViewById(R.id.errorMsg);
        retryBtn = findViewById(R.id.retryBtn);
        topBar = findViewById(R.id.topBar);
        btnFavorites = findViewById(R.id.btnFavorites);
        progressBar.setVisibility(View.GONE);
    }

    private void setupTopBar() {
        if (btnFavorites == null) return;
        btnFavorites.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: v.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); break;
            }
            return false;
        });
        btnFavorites.setOnClickListener(v -> { v.setAlpha(0.5f); v.animate().alpha(1f).setDuration(200).start(); openFavoritesViewer(); });
    }

    private void updateTopBarVisibility() {
        if (btnFavorites != null) btnFavorites.setVisibility(View.VISIBLE);
    }

    // ==================== WebView ====================
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebViewConfig.configure(webView);
        webView.setLayerType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_SOFTWARE, null);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) { super.onProgressChanged(view, newProgress); animateProgressBar(newProgress); }
        });
        webView.setWebViewClient(new ProgressWebViewClient(new ProgressWebViewClient.OnPageCallback() {
            @Override public void onProgressChanged(int progress) { animateProgressBar(progress); }
            @Override
            public void onPageStarted(String url) {
                isLoading = true; isPageLoaded = false; loadingStartTime = System.currentTimeMillis();
                showLoadingOverlay(true); progressBar.setVisibility(View.VISIBLE); errorOverlay.setVisibility(View.GONE);
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

    // ==================== 首页 → 维护检测 → 自动路由 ====================
    private void handleHomePageLoaded() {
        String mJs = "javascript:(function(){var b=document.body?document.body.innerText:'';if(b.indexOf('\u7ef4\u62a4')>=0||b.indexOf('\u5347\u7ea7')>=0)return'maintenance';return'ok';})()";
        webView.evaluateJavascript(mJs, mResult -> {
            String mr = mResult != null ? mResult.replaceAll("\"", "") : "ok";
            if ("maintenance".equals(mr)) { showLoadingOverlay(false); toast("\u26a0\ufe0f 网站正在维护中"); return; }
            checkLoginAndRoute();
        });
    }

    private void checkLoginAndRoute() {
        if (!WebViewConfig.isNetworkAvailable()) { showLoadingOverlay(false); toast("\u26a0\ufe0f 网络连接异常"); startNetworkRetry(); return; }
        mainHandler.postDelayed(() -> {
            String js = "javascript:(function(){var token=localStorage.getItem('daguan_token');return token?'logged_in':'not_logged_in';})()";
            webView.evaluateJavascript(js, result -> {
                String r = result != null ? result.replaceAll("\"", "") : "";
                if ("logged_in".equals(r)) {
                    isLoggedIn = true; loginGuideShown = false; showLoadingOverlay(false); isFirstLoad = false; isAutoRedirect = false;
                    webView.loadUrl(HOME_PAGE_URL);
                    mainHandler.postDelayed(() -> { if (WebViewConfig.isNetworkAvailable()) { startLoginWatchdog(); startFavoritesPeriodicSync(); } }, 1000);
                } else {
                    startSilentLoginCheck();
                }
            });
        }, 300);
    }

    // ==================== 登录检测 ====================
    private void startSilentLoginCheck() {
        if (loginCheckInProgress || isLoggedIn) return;
        if (!WebViewConfig.isNetworkAvailable()) return;
        loginCheckInProgress = true;
        String js = "javascript:(function(){var token=localStorage.getItem('daguan_token');return token?'logged_in':'not_logged_in';})()";
        webView.evaluateJavascript(js, result -> {
            loginCheckInProgress = false;
            String r = result != null ? result.replaceAll("\"", "") : "";
            if ("logged_in".equals(r)) {
                isLoggedIn = true; loginGuideShown = false; showLoadingOverlay(false); isAutoRedirect = true; loadingStartTime = System.currentTimeMillis();
                mainHandler.postDelayed(() -> webView.loadUrl(HOME_PAGE_URL), 1500);
                mainHandler.postDelayed(() -> { startLoginWatchdog(); startFavoritesPeriodicSync(); }, 2000);
            } else {
                goToLoginFlow();
            }
        });
    }

    private void goToLoginFlow() {
        isLoggedIn = false; showLoadingOverlay(false); isAutoRedirect = true; loadingStartTime = System.currentTimeMillis();
        mainHandler.postDelayed(() -> webView.loadUrl(LOGIN_URL), 1500);
        mainHandler.postDelayed(this::showLoginCodeGuide, 3000);
    }

    // ==================== 网络恢复重试 ====================
    private void startNetworkRetry() {
        stopNetworkRetry();
        networkRetryRunnable = () -> {
            if (WebViewConfig.isNetworkAvailable()) {
                webView.evaluateJavascript("javascript:(function(){fetch('/api/user/collections?page=1&per_page=1').then(function(){return'ok'}).catch(function(){return'error'})})()", null);
                mainHandler.postDelayed(() -> checkLoginAndRoute(), 800);
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
                if (webView == null) return;
                if (!WebViewConfig.isNetworkAvailable()) { mainHandler.postDelayed(this, 1500); return; }
                String currentUrl = webView.getUrl();
                if (currentUrl == null || (!currentUrl.startsWith("https://cxyonly.fans") && !currentUrl.startsWith("http://cxyonly.fans"))) { mainHandler.postDelayed(this, 1500); return; }
                String js = "javascript:(function(){var token=localStorage.getItem('daguan_token');return token?'logged_in':'not_logged_in';})()";
                webView.evaluateJavascript(js, result -> {
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
        if (!isLoggedIn) return;
        isLoggedIn = false; loginGuideShown = false; stopLoginWatchdog(); stopFavoritesPeriodicSync(); toast("\u26a0\ufe0f 登录已失效，请重新登录");
        isAutoRedirect = true; loadingStartTime = System.currentTimeMillis(); showLoadingOverlay(true);
        webView.loadUrl(LOGIN_URL); mainHandler.postDelayed(this::showLoginCodeGuide, 1500);
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
            if (!isLoading) { showLoadingOverlay(true); errorOverlay.setVisibility(View.GONE); if (loginHelper != null) loginHelper.reset(); isFirstLoad = true; loginGuideShown = false; isLoggedIn = false; loginCheckInProgress = false; stopFavoritesPeriodicSync(); stopLoginWatchdog(); webView.reload(); }
            mainHandler.postDelayed(() -> { if (swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(false); }, 5000);
        });
    }
    private void setupErrorRetry() {
        retryBtn.setOnClickListener(v -> { errorOverlay.setVisibility(View.GONE); showLoadingOverlay(true); WebViewConfig.clearCache(webView); isFirstLoad = true; loginGuideShown = false; isLoggedIn = false; loginCheckInProgress = false; stopFavoritesPeriodicSync(); stopLoginWatchdog(); webView.loadUrl(HOME_URL); });
    }

    // ==================== 进度条 / 加载 / Toast ====================
    private void animateProgressBar(int target) {
        if (target < currentProgress) currentProgress = target;
        if (target >= PROGRESS_MAX) { currentProgress = PROGRESS_MAX; updateProgressWidth(PROGRESS_MAX); return; }
        final int t = target;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (currentProgress < t) { currentProgress += 2; if (currentProgress > t) currentProgress = t; updateProgressWidth(currentProgress); mainHandler.postDelayed(this, PROGRESS_ANIM_DELAY); }
            }
        });
    }
    private void updateProgressWidth(int p) {
        if (progressBar == null) return;
        ViewGroup.LayoutParams lp = progressBar.getLayoutParams();
        if (lp == null) return;
        int w = progressBar.getParent() instanceof View ? ((View) progressBar.getParent()).getWidth() : getResources().getDisplayMetrics().widthPixels;
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        lp.width = w * p / PROGRESS_MAX; progressBar.setLayoutParams(lp);
    }
    private void showLoadingOverlay(boolean show) {
        if (show) {
            loadingOverlay.setVisibility(View.VISIBLE); loadingOverlay.setAlpha(0f); loadingOverlay.animate().alpha(1f).setDuration(200).start();
            View logo = findViewById(R.id.loadingLogo);
            if (logo != null) { Animation anim = AnimationUtils.loadAnimation(this, R.anim.rotate_loader); logo.startAnimation(anim); }
        } else {
            loadingOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> loadingOverlay.setVisibility(View.GONE)).start();
            View logo = findViewById(R.id.loadingLogo); if (logo != null) logo.clearAnimation();
        }
    }
    private void showErrorPage(String info) { if (errorMsg != null && info != null) errorMsg.setText(info); errorOverlay.setVisibility(View.VISIBLE); errorOverlay.setAlpha(0f); errorOverlay.animate().alpha(1f).setDuration(300).start(); }
    private void toast(String msg) { Toast t = Toast.makeText(this, msg, Toast.LENGTH_SHORT); t.show(); mainHandler.postDelayed(t::cancel, 1000); }

    // ==================== 返回拦截 ====================
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { if (keyCode == KeyEvent.KEYCODE_BACK) return handleBackPress(); return super.onKeyDown(keyCode, event); }
    private boolean handleBackPress() {
        if (errorOverlay.getVisibility() == View.VISIBLE) { errorOverlay.setVisibility(View.GONE); showLoadingOverlay(true); isFirstLoad = true; loginGuideShown = false; isLoggedIn = false; loginCheckInProgress = false; stopFavoritesPeriodicSync(); stopLoginWatchdog(); webView.loadUrl(HOME_URL); return true; }
        if (webView != null && webView.canGoBack()) { webView.goBack(); return true; }
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime > 2000) { lastBackPressTime = now; toast("再按一次退出"); return true; }
        finishAffinity(); return true;
    }

    // ==================== 收藏 ====================
    private void startFavoritesSync() { if (favoritesManager != null && webView != null) favoritesManager.startSync(webView); }
    private void startFavoritesPeriodicSync() { stopFavoritesPeriodicSync(); favoritesSyncRunnable = () -> { if (isLoggedIn && favoritesManager != null && webView != null && !favoritesManager.isSyncing()) favoritesManager.startSync(webView); mainHandler.postDelayed(favoritesSyncRunnable, FAV_SYNC_INTERVAL_MS); }; mainHandler.post(favoritesSyncRunnable); }
    private void stopFavoritesPeriodicSync() { if (favoritesSyncRunnable != null) { mainHandler.removeCallbacks(favoritesSyncRunnable); favoritesSyncRunnable = null; } }

    private void openFavoritesViewer() {
        if (webView == null || favoritesManager == null) return;
        lastPageUrl = webView.getUrl();
        if (isLoggedIn) {
            if (favoritesManager.isSyncing()) {
                mainHandler.postDelayed(this::loadFavoritesViewer, 2000);
            } else {
                favoritesManager.setListener(new FavoritesManager.SyncListener() {
                    @Override public void onProgress(String message) { }
                    @Override public void onComplete(int count) { loadFavoritesViewer(); favoritesManager.setListener(originalFavListener); }
                    @Override public void onError(String error) { loadFavoritesViewer(); favoritesManager.setListener(originalFavListener); }
                });
                favoritesManager.startSync(webView);
            }
        } else {
            loadFavoritesViewer();
        }
    }

    private void loadFavoritesViewer() {
        if (webView == null || favoritesManager == null) return;
        String json = favoritesManager.getData().toString();
        String b64 = android.util.Base64.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        webView.loadUrl("file:///android_asset/favorites_viewer.html#data=" + b64);
    }

    // ==================== 生命周期 ====================
    @Override protected void onResume() { super.onResume(); if (webView != null) { webView.onResume(); webView.resumeTimers(); } if (!isLoggedIn && loginHelper != null) { mainHandler.postDelayed(() -> loginHelper.checkAndFillLoginCode(), 500); } if (isLoggedIn) { startLoginWatchdog(); startFavoritesPeriodicSync(); } }
    @Override protected void onPause() { super.onPause(); if (webView != null) { webView.onPause(); webView.pauseTimers(); } stopLoginWatchdog(); stopFavoritesPeriodicSync(); }
    @Override protected void onDestroy() { stopLoginWatchdog(); stopNetworkRetry(); stopFavoritesPeriodicSync(); if (webView != null) { webView.stopLoading(); webView.removeAllViews(); webView.destroy(); webView = null; } mainHandler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public void onLowMemory() { super.onLowMemory(); if (webView != null) webView.freeMemory(); }
    @Override public void onConfigurationChanged(Configuration newConfig) { super.onConfigurationChanged(newConfig); if (webView != null) { webView.requestLayout(); if (isPageLoaded) webView.evaluateJavascript("javascript:(function(){window.dispatchEvent(new Event('orientationchange'));})()", null); } if (currentProgress > 0) updateProgressWidth(currentProgress); }
}