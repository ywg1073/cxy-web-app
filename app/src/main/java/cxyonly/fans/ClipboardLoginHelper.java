package cxyonly.fans;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剪贴板登录码监听与自动填入
 *
 * 检测格式：
 *   登录码：hmuc6wsh
 *   有效期至：2026-07-20 05:43
 *
 * 有效期使用 12 小时制，需根据当前系统时间的 AM/PM 自动推断：
 * - 当前 PM → 有效期小时按 PM 处理（小时+12，12点除外）
 * - 当前 AM → 有效期小时按 AM 处理（小时不变，12点变0）
 */
public class ClipboardLoginHelper {

    /** 登录码正则：匹配"登录码：xxx"或"登录码:xxx" */
    private static final Pattern LOGIN_CODE_PATTERN =
            Pattern.compile("登录码[：:]\\s*([a-zA-Z0-9]+)");

    private final Context context;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OnLoginCodeListener listener;
    private String lastFilledCode = null;

    public interface OnLoginCodeListener {
        void onCodeDetected(String code);
        void onCodeFilled();
    }

    public ClipboardLoginHelper(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
    }

    public void setListener(OnLoginCodeListener listener) {
        this.listener = listener;
    }

    /**
     * 检测剪贴板是否包含登录码，如果有则通过 JS 填入登录页
     */
    public void checkAndFillLoginCode() {
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;

            CharSequence text = clip.getItemAt(0).getText();
            if (text == null) return;

            String content = text.toString().trim();
            Matcher matcher = LOGIN_CODE_PATTERN.matcher(content);

            if (matcher.find()) {
                String code = matcher.group(1);
                if (code == null || code.isEmpty() || code.equals(lastFilledCode)) return;

                lastFilledCode = code;
                if (listener != null) {
                    mainHandler.post(() -> listener.onCodeDetected(code));
                }
                fillCodeToWebView(code);
            }
        } catch (Exception e) {
            // 某些设备剪贴板访问受限，静默处理
        }
    }

    /**
     * 通过 JS 将登录码填入 WebView 中登录页的输入框
     */
    private void fillCodeToWebView(String code) {
        mainHandler.post(() -> {
            String js = "javascript:(function(){" +
                    "var inputs = document.querySelectorAll('input[name=\"captcha\"], " +
                    "input[placeholder*=\"验证码\"], " +
                    "input[placeholder*=\"登录码\"], " +
                    "input[placeholder*=\"登入码\"], " +
                    "input[type=\"text\"][name*=\"code\"], " +
                    "input[type=\"text\"][name*=\"captcha\"], " +
                    "input[type=\"text\"][name*=\"verification\"], " +
                    "input.captcha, " +
                    "input.code');" +
                    "if (inputs.length > 0) {" +
                    "  var input = inputs[0];" +
                    "  var nativeInputValueSetter = Object.getOwnPropertyDescriptor(" +
                    "    window.HTMLInputElement.prototype, 'value').set;" +
                    "  nativeInputValueSetter.call(input, '" + escapeJsString(code) + "');" +
                    "  input.dispatchEvent(new Event('input', {bubbles: true}));" +
                    "  input.dispatchEvent(new Event('change', {bubbles: true}));" +
                    "  return 'filled';" +
                    "}" +
                    "return 'not_found';" +
                    "})()";

            webView.evaluateJavascript(js, result -> {
                if (listener != null) {
                    listener.onCodeFilled();
                }
            });
        });
    }

    private static String escapeJsString(String s) {
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public void reset() {
        lastFilledCode = null;
    }
}