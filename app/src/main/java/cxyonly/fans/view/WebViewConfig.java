package cxyonly.fans.view;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import cxyonly.fans.CxyApplication;

/**
 * WebView 统一配置：缓存优化、防白屏、性能调优
 */
public class WebViewConfig {

    private static final String CACHE_DIR = "cxywebview_cache";

    /**
     * 对 WebView 进行全面的性能与缓存配置
     */
    public static void configure(WebView webView) {
        WebSettings settings = webView.getSettings();

        // ========== 基础设置 ==========
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setGeolocationEnabled(false);

        // ========== Cookie 持久化（保持登录状态） ==========
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        // ========== 缓存策略 ==========
        // 优先使用缓存，减少网络请求，加快页面加载
        if (isNetworkAvailable()) {
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        } else {
            settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        }

        // 设置应用缓存路径
        Context context = CxyApplication.getAppContext();
        String cachePath = context.getCacheDir().getAbsolutePath() + "/" + CACHE_DIR;
        settings.setDatabasePath(cachePath);

        // ========== 渲染加速（防白屏） ==========
        // 启用硬件加速
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(true);
        }
        // 降低渲染优先级以提升首屏速度
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);

        // 启用平滑滚动
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // 布局与缩放
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // ========== 用户代理伪装（防风控） ==========
        // 使用标准 Chrome Android UA，不含 "wv" 和自定义标识，
        // 让服务端认为这是普通 Chrome 浏览器而非 WebView
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/130.0.6723.108 Mobile Safari/537.36");

        // ========== 内存优化 ==========
        // 移除 WebView 的默认长按选择动作，减少内存开销
        webView.setOnLongClickListener(v -> true);

        // ========== 自动播放媒体 ==========
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }

        // ========== 安全设置 ==========
        settings.setSavePassword(false);
        settings.setSaveFormData(false);

        // 清除 WebView 数据库中的历史记录以防止数据泄露
        WebViewDatabase.getInstance(CxyApplication.getAppContext()).clearFormData();

        // ========== 设置 WebView 内部配置 ==========
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 设置默认字体大小
        settings.setDefaultFontSize(16);
        settings.setMinimumFontSize(10);
    }

    /**
     * 检查网络是否可用
     */
    public static boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager)
                CxyApplication.getAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }

    /**
     * 清除 WebView 缓存（保留 DOM Storage 和 Cookie 以免丢失登录状态）
     */
    public static void clearCache(WebView webView) {
        webView.clearCache(true);
        webView.clearFormData();
        // 不调用 clearHistory() —— 会清除 DOM Storage，丢失 token
        // 不调用 WebStorage.deleteAllData() —— 同上
    }
}