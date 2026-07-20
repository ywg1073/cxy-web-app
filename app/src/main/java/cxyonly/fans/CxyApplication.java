package cxyonly.fans;

import android.app.Application;
import android.content.Context;
import android.webkit.WebView;

/**
 * Application 入口：初始化全局配置
 */
public class CxyApplication extends Application {

    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();

        // 提前初始化 WebView 内核，减少首次打开白屏时间
        try {
            WebView webView = new WebView(this);
            webView.destroy();
        } catch (Exception ignored) {
            // 某些设备上提前初始化可能抛出异常，忽略即可
        }
    }

    public static Context getAppContext() {
        return appContext;
    }
}