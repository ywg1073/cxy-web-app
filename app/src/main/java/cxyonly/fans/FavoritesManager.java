package cxyonly.fans;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 收藏题目管理器
 * 通过 JS fetch 获取收藏列表 API，需要从 localStorage 读取 JWT token 鉴权
 * API: GET /api/user/collections?page=1&per_page=50
 * 认证: Authorization: Bearer <daguan_token from localStorage>
 * 响应: {code:0, data:{items:[{id, stem, options, answer, answer_explanation, source, user_state:{mastery, favorited_at}}]}}
 */
public class FavoritesManager {

    private static final String PREFS_NAME = "favorites_prefs";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_DATA_JSON = "favorites_data";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final int CURRENT_VERSION = 2; // v2: JSONTokener 标准解析，修复 LaTeX 反斜杠损坏
    private static final String STORAGE_FILE = "cxy_favorites.json";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private JSONArray favoritesData = new JSONArray();
    private SyncListener listener;
    private boolean isSyncing = false;

    public interface SyncListener {
        void onProgress(String message);
        void onComplete(int count);
        void onError(String error);
    }

    public FavoritesManager(Context context) {
        this.context = context.getApplicationContext();
        // 优化：将耗时的本地磁盘读取和 JSON 解析迁移到子线程，避免阻塞主线程导致启动卡顿
        new Thread(this::loadFromDisk).start();
    }

    public void setListener(SyncListener listener) { this.listener = listener; }
    public JSONArray getData() { return favoritesData; }
    public int getCount() { return favoritesData.length(); }
    public boolean isSyncing() { return isSyncing; }

    /**
     * 静默同步：从 localStorage 读取 daguan_token，带 Bearer 头调用收藏 API
     */
    public void startSync(WebView webView) {
        if (isSyncing || webView == null) return;
        isSyncing = true;

        String js = "javascript:(function(){\n"
                + "var token = localStorage.getItem('daguan_token');\n"
                + "if (!token) {\n"
                + "  window.__favSyncResult = JSON.stringify({error:'not_logged_in'});\n"
                + "  return;\n"
                + "}\n"
                + "var allItems = [];\n"
                + "var page = 1;\n"
                + "var perPage = 50;\n"
                + "function fetchPage(p) {\n"
                + "  return fetch('/api/user/collections?page='+p+'&per_page='+perPage, {\n"
                + "    headers: { 'Authorization': 'Bearer ' + token }\n"
                + "  })\n"
                + "    .then(function(r) { return r.json(); })\n"
                + "    .then(function(d) {\n"
                + "      if (d.code !== 0) return {success:false, error:'API:'+d.code};\n"
                + "      if (d.data && d.data.items && d.data.items.length > 0) {\n"
                + "        allItems = allItems.concat(d.data.items);\n"
                + "        if (d.data.items.length === perPage) {\n"
                + "          return fetchPage(p+1);\n"
                + "        }\n"
                + "      }\n"
                + "      return {success:true, items:allItems};\n"
                + "    });\n"
                + "}\n"
                + "fetchPage(1).then(function(result) {\n"
                + "  window.__favSyncResult = JSON.stringify(result);\n"
                + "}).catch(function(e) {\n"
                + "  window.__favSyncResult = JSON.stringify({success:false, error:e.message});\n"
                + "});\n"
                + "})()";

        webView.evaluateJavascript(js, null);
        mainHandler.postDelayed(() -> pollSyncResult(webView, 0), 1000);
    }

    private void pollSyncResult(WebView webView, int attempt) {
        if (attempt > 6) {
            isSyncing = false;
            if (listener != null) {
                mainHandler.post(() -> listener.onError("同步超时"));
            }
            return;
        }

        webView.evaluateJavascript("window.__favSyncResult", result -> {
            if (result == null || result.equals("null") || result.isEmpty()) {
                mainHandler.postDelayed(() -> pollSyncResult(webView, attempt + 1), 1000);
                return;
            }
            isSyncing = false;
            processSyncResult(result);
        });
    }

    private void processSyncResult(String rawResult) {
        try {
            // WebView evaluateJavascript 会把 JS 字符串再包一层 JSON 字符串。
            // 必须先用 JSONTokener 解析外层字符串，不能手工 replace 反斜杠，否则复杂 LaTeX 会损坏。
            Object parsed = new JSONTokener(rawResult).nextValue();
            String json = parsed instanceof String ? (String) parsed : String.valueOf(parsed);
            json = json.trim();

            JSONObject root = new JSONObject(json);

            // 检查错误
            if (root.has("error")) {
                String err = root.optString("error", "");
                if ("not_logged_in".equals(err)) {
                    // 未登录，静默处理
                    if (listener != null) mainHandler.post(() -> listener.onComplete(0));
                    return;
                }
                if (listener != null) mainHandler.post(() -> listener.onError("同步失败: " + err));
                return;
            }

            if (!root.optBoolean("success", false)) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onError("同步失败"));
                }
                return;
            }

            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) {
                // API 返回空列表 → 用户取消了所有收藏，清空本地数据
                favoritesData = new JSONArray();
                saveToDisk();
                if (listener != null) mainHandler.post(() -> listener.onComplete(0));
                return;
            }

            // 全量替换：清空旧数据，用 API 最新结果重建
            // 用户在网站上取消收藏的题目不会出现在 API 返回中，自动从本地删除
            favoritesData = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject entry = new JSONObject();

                // 基本信息
                entry.put("id", item.optString("id", ""));
                entry.put("questionNumber", String.valueOf(i + 1));
                entry.put("source", item.optString("source", "未知来源"));

                // 题目：stem 字段是 markdown（含 LaTeX）
                entry.put("stemHTML", item.optString("stem", ""));

                // 题目预览：取 stem 前面的文字部分
                String stem = item.optString("stem", "");
                String preview = stem.replaceAll("\\$\\$[\\s\\S]*?\\$\\$", "")
                        .replaceAll("\\$[\\s\\S]*?\\$", "")
                        .replaceAll("\\\\[a-zA-Z]+", "")
                        .replaceAll("[\\n\\r]+", " ")
                        .trim();
                if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                entry.put("stemPreview", preview.isEmpty() ? "无题目预览" : preview);

                // 选项：组装成 HTML（选择题选项，放在题目下方直接展示）
                JSONArray options = item.optJSONArray("options");
                if (options != null && options.length() > 0) {
                    JSONObject answerObj = item.optJSONObject("answer");
                    StringBuilder correctLabels = new StringBuilder();
                    if (answerObj != null) {
                        JSONArray correctIds = answerObj.optJSONArray("option_ids");
                        if (correctIds != null && correctIds.length() > 0) {
                            for (int j = 0; j < correctIds.length(); j++) {
                                String optId = correctIds.optString(j, "");
                                for (int k = 0; k < options.length(); k++) {
                                    JSONObject opt = options.optJSONObject(k);
                                    if (opt == null) continue;
                                    if (optId.equals(opt.optString("id", ""))) {
                                        if (correctLabels.length() > 0) correctLabels.append(",");
                                        correctLabels.append(opt.optString("label", ""));
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    entry.put("correctLabels", correctLabels.toString());
                    
                    StringBuilder optsHtml = new StringBuilder();
                    for (int j = 0; j < options.length(); j++) {
                        JSONObject opt = options.optJSONObject(j);
                        if (opt == null) continue;
                        String label = opt.optString("label", "");
                        String content = opt.optString("content_md", "");
                        optsHtml.append("<div class=\"option-item\" data-label=\"").append(label).append("\" onclick=\"checkOption(this)\">")
                                .append("<div class=\"opt-label\">").append(label).append(".</div> ")
                                .append("<div class=\"opt-text\">").append(content).append("</div>")
                                .append("</div>");
                    }
                    // 选项单独存储，详情页中直接放在题目下方
                    entry.put("optionsHTML", optsHtml.toString());

                    // 正确答案（折叠区域）
                    if (answerObj != null) {
                        JSONArray correctIds = answerObj.optJSONArray("option_ids");
                        if (correctIds != null && correctIds.length() > 0) {
                            String cLabelsStr = correctLabels.toString().replace(",", "、");
                            entry.put("answerHTML",
                                    "<p><strong>正确答案：" + cLabelsStr + "</strong></p>");
                        }
                    }
                } else {
                    // 主观题：答案直接折叠
                    JSONObject answerObj = item.optJSONObject("answer");
                    if (answerObj != null) {
                        String refAnswer = answerObj.optString("reference_answer_md", "");
                        entry.put("answerHTML", "<p>" + refAnswer + "</p>");
                    }
                    if (entry.optString("answerHTML", "").isEmpty()) {
                        String ca = item.optString("correct_answer", "");
                        if (!ca.isEmpty()) entry.put("answerHTML", "<p>" + ca + "</p>");
                    }
                }

                // 解析
                String explanation = item.optString("answer_explanation", "");
                if (explanation.isEmpty()) {
                    // 尝试从 document 读取
                    JSONObject doc = item.optJSONObject("document");
                    if (doc != null) {
                        explanation = doc.optString("explanation_md",
                                doc.optString("answer_explanation", ""));
                    }
                }
                entry.put("solutionHTML", explanation);

                // 分类标签（mastery 映射为中文）
                JSONObject userState = item.optJSONObject("user_state");
                String masteryRaw = "not_started";
                String favTime = "";
                String noteStr = "";
                if (userState != null) {
                    masteryRaw = userState.optString("mastery", "not_started");
                    favTime = userState.optString("favorited_at", "");
                    noteStr = userState.optString("note", "");
                }
                // 映射 mastery 值为中文标签（用于列表分类）
                String masteryLabel = masteryRaw;
                switch (masteryRaw) {
                    case "not_started": masteryLabel = "收藏"; break;
                    case "familiar": masteryLabel = "不熟练"; break;
                    case "unfamiliar": masteryLabel = "完全不会"; break;
                    case "mastered": masteryLabel = "掌握"; break;
                    default: masteryLabel = "收藏"; break;
                }
                // 标准化 rawMastery：统一为 questions API 的值（needs_practice/not_known）
                String rawMastery = masteryRaw;
                switch (masteryRaw) {
                    case "familiar": rawMastery = "needs_practice"; break;
                    case "unfamiliar": rawMastery = "not_known"; break;
                }
                entry.put("category", masteryLabel);
                entry.put("time", favTime);
                entry.put("rawMastery", rawMastery);
                entry.put("rawFavoritedAt", favTime);
                entry.put("note", noteStr);

                // 分类路径
                String catPath = item.optString("category_name",
                        item.optString("category_full_path", ""));
                entry.put("categoryPath", catPath);

                favoritesData.put(entry);
            }

            saveToDisk();
            if (listener != null) {
                int count = favoritesData.length();
                mainHandler.post(() -> listener.onComplete(count));
            }

        } catch (Exception e) {
            if (listener != null) {
                mainHandler.post(() -> listener.onError("解析失败: " + e.getMessage()));
            }
        }
    }

    // ============ 本地存储 ============

    private void saveToDisk() {
        try {
            String json = favoritesData.toString();
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_DATA_VERSION, CURRENT_VERSION)
                    .putString(KEY_DATA_JSON, json)
                    .putString(KEY_LAST_SYNC,
                            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()))
                    .apply();

            File file = new File(context.getFilesDir(), STORAGE_FILE);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadFromDisk() {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int savedVersion = sp.getInt(KEY_DATA_VERSION, 0);
        if (savedVersion < CURRENT_VERSION) {
            // 旧版本数据格式不兼容（LaTeX 反斜杠损坏），清空缓存等下次同步重建
            favoritesData = new JSONArray();
            sp.edit().putInt(KEY_DATA_VERSION, CURRENT_VERSION).apply();
            return;
        }
        try {
            String json = sp.getString(KEY_DATA_JSON, "[]");
            favoritesData = new JSONArray(json);
        } catch (Exception e) {
            favoritesData = new JSONArray();
        }
    }

    public String getLastSyncTime() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_SYNC, "从未同步");
    }
}