package cxyonly.fans.view;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

/**
 * 自定义 WebViewClient：处理加载进度、错误页面、加载动画
 */
public class ProgressWebViewClient extends WebViewClient {

    private final OnPageCallback callback;
    private boolean hasError = false;

    public interface OnPageCallback {
        void onProgressChanged(int progress);

        void onPageStarted(String url);

        void onPageFinished(String url);

        void onPageError(String errorMsg);
    }

    public ProgressWebViewClient(OnPageCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        hasError = false;
        if (callback != null) {
            callback.onPageStarted(url);
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        if (callback != null) {
            callback.onPageFinished(url);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request,
                                WebResourceError error) {
        super.onReceivedError(view, request, error);

        // 只处理主框架(main frame)的加载错误，忽略子资源错误
        if (request != null && request.isForMainFrame()) {
            hasError = true;
            if (callback != null) {
                int errorCode = error.getErrorCode();
                String description = error.getDescription() != null
                        ? error.getDescription().toString() : "未知错误";
                String errorMsg = "错误代码: " + errorCode + "\n" + description;
                callback.onPageError(errorMsg);
            }
        }
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceResponse errorResponse) {
        super.onReceivedHttpError(view, request, errorResponse);

        if (request != null && request.isForMainFrame()) {
            hasError = true;
            if (callback != null) {
                int statusCode = errorResponse != null ? errorResponse.getStatusCode() : 0;
                String errorMsg = "HTTP " + statusCode + " 错误";
                callback.onPageError(errorMsg);
            }
        }
    }

    public boolean hasError() {
        return hasError;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();

        // 拦截 tel: 和 mailto: 协议，交给系统处理
        if (url.startsWith("tel:") || url.startsWith("mailto:")) {
            return false;
        }

        // 拦截 weixin:// 协议，跳转微信外部应用（不设包名，系统自由匹配）
        if (url.startsWith("weixin://")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(view.getContext().getPackageManager()) != null) {
                    view.getContext().startActivity(intent);
                }
            } catch (Exception e) {
                // 微信未安装或无法处理该 scheme
            }
            return true;
        }

        // 其他链接在 WebView 内打开
        view.loadUrl(url);
        return true;
    }
}