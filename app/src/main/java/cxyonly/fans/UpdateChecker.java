package cxyonly.fans;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class UpdateChecker {

    private static final String CHECK_URL = "https://gx.cxyweb-app.space/";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static class UpdateInfo {
        public String latestVersion;
        public String downloadUrl;
        public boolean hasUpdate;
        public String error;
    }

    public static void check(Context context) {
        check(context, false);
    }

    public static void checkWithDialog(Context context) {
        check(context, true);
    }

    private static void check(Context context, boolean forceShow) {
        if (forceShow) {
            Toast.makeText(context, "正在检查更新...", Toast.LENGTH_SHORT).show();
        }
        executor.execute(() -> {
            try {
                UpdateInfo info = fetchUpdateInfo(context);
                mainHandler.post(() -> {
                    if (info.error != null) {
                        if (forceShow) {
                            Toast.makeText(context, "检查更新失败: " + info.error, Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    if (info.hasUpdate) {
                        showUpdateDialog(context, info);
                    } else if (forceShow) {
                        Toast.makeText(context, "当前已是最新版本 (" + info.latestVersion + ")", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (forceShow) {
                        Toast.makeText(context, "检查更新异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private static String stripV(String version) {
        if (version != null && version.toLowerCase().startsWith("v")) {
            return version.substring(1);
        }
        return version == null ? "" : version;
    }
    
    private static float parseVersionFloat(String version) {
        try {
            String clean = version.replaceAll("[^0-9.]", "");
            String[] parts = clean.split("\\.");
            if (parts.length >= 2) {
                return Float.parseFloat(parts[0] + "." + parts[1]);
            } else if (parts.length == 1) {
                return Float.parseFloat(parts[0]);
            }
        } catch (Exception e) {}
        return 0f;
    }

    private static UpdateInfo fetchUpdateInfo(Context context) {
        UpdateInfo info = new UpdateInfo();
        try {
            String currentVersion = "";
            try {
                currentVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                currentVersion = "1.0.0";
            }
            String currentVer = stripV(currentVersion);

            HttpURLConnection conn = (HttpURLConnection) new URL(CHECK_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Android-UpdateChecker/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                info.error = "服务器返回 " + responseCode;
                return info;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
            reader.close();
            conn.disconnect();

            String body = response.toString().trim();
            String latestVersion = "";
            String downloadUrl = "";

            for (String l : body.split("\n")) {
                l = l.trim();
                if (l.startsWith("版本:")) {
                    latestVersion = l.substring(3).trim();
                } else if (l.startsWith("链接:")) {
                    downloadUrl = l.substring(3).trim();
                }
            }

            if (latestVersion.isEmpty()) {
                info.error = "无法解析版本号";
                return info;
            }
            if (downloadUrl.isEmpty()) {
                info.error = "无法解析下载链接";
                return info;
            }

            info.latestVersion = latestVersion;
            info.downloadUrl = downloadUrl;

            String latestVer = stripV(latestVersion);
            
            float currentF = parseVersionFloat(currentVer);
            float latestF = parseVersionFloat(latestVer);
            
            if (currentF > 0 && latestF > 0) {
                info.hasUpdate = latestF > currentF;
            } else {
                info.hasUpdate = !latestVer.equals(currentVer);
            }

            return info;

        } catch (java.net.SocketTimeoutException e) {
            info.error = "连接超时，请检查网络";
            return info;
        } catch (java.net.UnknownHostException e) {
            info.error = "无法解析服务器地址";
            return info;
        } catch (Exception e) {
            info.error = "发生错误：" + e.getMessage();
            return info;
        }
    }

    private static void showUpdateDialog(Context context, UpdateInfo info) {
        Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.dialog_update);
        dialog.setCancelable(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setDimAmount(0.6f);
        }

        TextView tvTitle = dialog.findViewById(R.id.tvUpdateTitle);
        tvTitle.setText("发现新版本 " + info.latestVersion);

        Button btnUpdateNow = dialog.findViewById(R.id.btnUpdateNow);
        btnUpdateNow.setOnClickListener(v -> {
            dialog.dismiss();
            startDownload(context, info);
        });

        Button btnCancel = dialog.findViewById(R.id.btnUpdateCancel);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private static void startDownload(Context context, UpdateInfo info) {
        File apkFile = new File(context.getExternalFilesDir(null), "update_" + info.latestVersion + ".apk");
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        
        Dialog progressDialog = new Dialog(context);
        progressDialog.setContentView(R.layout.dialog_update_progress);
        progressDialog.setCancelable(false);
        if (progressDialog.getWindow() != null) {
            progressDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            progressDialog.getWindow().setDimAmount(0.6f);
        }

        ProgressBar progressBar = progressDialog.findViewById(R.id.updateProgressBar);
        TextView tvProgressText = progressDialog.findViewById(R.id.tvProgressText);
        TextView tvProgressTitle = progressDialog.findViewById(R.id.tvProgressTitle);
        TextView tvProgressMessage = progressDialog.findViewById(R.id.tvProgressMessage);
        Button btnInstall = progressDialog.findViewById(R.id.btnInstall);
        Button btnCancel = progressDialog.findViewById(R.id.btnProgressCancel);

        btnCancel.setOnClickListener(v -> {
            isCancelled.set(true);
            progressDialog.dismiss();
        });

        progressDialog.show();

        executor.execute(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            RandomAccessFile raf = null;
            try {
                URL url = new URL(info.downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                long downloadedSize = 0;
                if (apkFile.exists()) {
                    downloadedSize = apkFile.length();
                    conn.setRequestProperty("Range", "bytes=" + downloadedSize + "-");
                }

                int responseCode = conn.getResponseCode();
                long totalSize = conn.getContentLength();
                if (responseCode == 206) {
                    totalSize += downloadedSize;
                } else if (responseCode == 200) {
                    downloadedSize = 0;
                    if (apkFile.exists()) apkFile.delete();
                } else {
                    throw new Exception("服务器不支持下载 (HTTP " + responseCode + ")");
                }

                is = conn.getInputStream();
                raf = new RandomAccessFile(apkFile, "rw");
                raf.seek(downloadedSize);

                byte[] buffer = new byte[8192];
                int len;
                long lastTime = System.currentTimeMillis();
                
                while ((len = is.read(buffer)) != -1) {
                    if (isCancelled.get()) {
                        break;
                    }
                    raf.write(buffer, 0, len);
                    downloadedSize += len;

                    long now = System.currentTimeMillis();
                    if (now - lastTime > 100) { // update UI frequently for smooth progress
                        lastTime = now;
                        long finalDownloadedSize = downloadedSize;
                        long finalTotalSize = totalSize;
                        mainHandler.post(() -> {
                            if (finalTotalSize > 0) {
                                int progress = (int) (finalDownloadedSize * 100 / finalTotalSize);
                                progressBar.setProgress(progress);
                                tvProgressText.setText(String.format("%d%%", progress));
                            } else {
                                progressBar.setIndeterminate(true);
                                tvProgressText.setText(String.format("%.2f MB", finalDownloadedSize / 1024.0 / 1024.0));
                            }
                        });
                    }
                }

                if (!isCancelled.get()) {
                    mainHandler.post(() -> {
                        progressBar.setProgress(100);
                        tvProgressText.setText("100%");
                        tvProgressTitle.setText("下载完成");
                        tvProgressMessage.setText("即将开始安装新版本");
                        btnCancel.setVisibility(View.GONE);
                        btnInstall.setVisibility(View.VISIBLE);
                        btnInstall.setOnClickListener(v -> installApk(context, apkFile));
                        installApk(context, apkFile);
                    });
                } else {
                    mainHandler.post(() -> Toast.makeText(context, "已取消下载", Toast.LENGTH_SHORT).show());
                }

            } catch (Exception e) {
                final String err = e.getMessage();
                mainHandler.post(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(context, "下载失败: " + err, Toast.LENGTH_SHORT).show();
                });
            } finally {
                try { if (is != null) is.close(); } catch (Exception ignored) {}
                try { if (raf != null) raf.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static void installApk(Context context, File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        }
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
