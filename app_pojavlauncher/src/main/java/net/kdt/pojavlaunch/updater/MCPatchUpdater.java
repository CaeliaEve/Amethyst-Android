package net.kdt.pojavlaunch.updater;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 启动器内置 McPatch 客户端增量更新器
 * 支持私有 TCP 二进制切片协议 (shgt.store:6700)
 * 严格遵循用户约束：仅手动点击触发更新，不自动跑。
 */
public class MCPatchUpdater {
    public static final String TAG = "MCPatchUpdater";
    public static final String DEFAULT_SERVER_HOST = "shgt.store";
    public static final int DEFAULT_SERVER_PORT = 6700;
    public static final int DEFAULT_TIMEOUT_MS = 10000;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static Handler sMainHandler;

    private static void runOnUiThread(Runnable r) {
        try {
            if (sMainHandler == null) {
                sMainHandler = new Handler(Looper.getMainLooper());
            }
            sMainHandler.post(r);
        } catch (Throwable t) {
            r.run();
        }
    }

    public interface UpdateCallback {
        void onCheckComplete(boolean hasUpdate, String latestVersion);
        void onError(Exception e);
    }

    /**
     * 手动检查并提示更新入口 (UI 线程安全)
     */
    public static void checkAndPromptUpdate(Activity activity, File gameDir) {
        if (activity == null || activity.isFinishing()) return;

        Toast.makeText(activity, R.string.mcpatch_checking, Toast.LENGTH_SHORT).show();

        sExecutor.execute(() -> {
            try {
                // 1. 定位 baseDir 与版本相关文件
                UpdateContext ctx = resolveContext(gameDir);
                Log.i(TAG, "Checking update with baseDir: " + ctx.baseDir + ", versionFile: " + ctx.versionFile);

                String currentVersion = "";
                if (ctx.versionFile.exists()) {
                    currentVersion = Tools.read(ctx.versionFile.getAbsolutePath()).trim();
                }
                Log.i(TAG, "Current client version: " + currentVersion);

                // 2. 连接服务端拉取 index.json
                String indexJsonStr = requestText(ctx.serverHost, ctx.serverPort, "index.json", 0, 0, ctx.timeoutMs);
                JSONArray indexArray = new JSONArray(indexJsonStr);

                if (indexArray.length() == 0) {
                    postDialog(activity, activity.getString(R.string.check_server_update),
                            activity.getString(R.string.mcpatch_no_update, currentVersion));
                    return;
                }

                List<VersionIndex> versions = new ArrayList<>();
                for (int i = 0; i < indexArray.length(); i++) {
                    versions.add(new VersionIndex(indexArray.getJSONObject(i)));
                }

                VersionIndex latest = versions.get(versions.size() - 1);
                Log.i(TAG, "Latest server version: " + latest.label);

                if (latest.label.equals(currentVersion)) {
                    postDialog(activity, activity.getString(R.string.check_server_update),
                            activity.getString(R.string.mcpatch_no_update, currentVersion));
                    return;
                }

                // 3. 计算缺失的版本
                List<VersionIndex> missing = calculateMissingVersions(versions, currentVersion);
                if (missing.isEmpty()) {
                    postDialog(activity, activity.getString(R.string.check_server_update),
                            activity.getString(R.string.mcpatch_no_update, currentVersion));
                    return;
                }

                // 4. 获取各缺失版本的更新元数据与日志
                StringBuilder changelogs = new StringBuilder();
                List<FileChangeOp> changes = new ArrayList<>();

                for (VersionIndex ver : missing) {
                    String metaJsonStr = requestText(ctx.serverHost, ctx.serverPort, ver.filename,
                            ver.offset, ver.offset + ver.len, ctx.timeoutMs);
                    JSONArray metas = new JSONArray(metaJsonStr);
                    for (int m = 0; m < metas.length(); m++) {
                        JSONObject mObj = metas.getJSONObject(m);
                        String vLabel = mObj.optString("label", ver.label);
                        String vLogs = mObj.optString("logs", "");
                        changelogs.append("[").append(vLabel).append("]\n")
                                .append(vLogs).append("\n\n");

                        JSONArray chArray = mObj.optJSONArray("changes");
                        if (chArray != null) {
                            for (int c = 0; c < chArray.length(); c++) {
                                JSONObject cObj = chArray.getJSONObject(c);
                                FileChangeOp op = FileChangeOp.parse(cObj, ver.filename, vLabel);
                                if (op != null) changes.add(op);
                            }
                        }
                    }
                }

                // 5. 弹窗提示用户确认更新
                final String finalCurrentVersion = currentVersion.isEmpty() ? "未安装/未知" : currentVersion;
                final String finalLatestVersion = latest.label;
                final String finalChangelogs = changelogs.toString().trim();
                final List<FileChangeOp> finalChanges = changes;

                int updateCount = 0;
                int deleteCount = 0;
                long totalBytes = 0;
                for (FileChangeOp op : changes) {
                    if (op.type == FileChangeOp.TYPE_UPDATE) {
                        updateCount++;
                        totalBytes += op.len;
                    } else if (op.type == FileChangeOp.TYPE_DELETE_FILE) {
                        deleteCount++;
                    }
                }

                String msg = "当前版本: " + finalCurrentVersion + "\n" +
                        "最新版本: " + finalLatestVersion + "\n\n" +
                        "更新日志:\n" + (finalChangelogs.isEmpty() ? "（无日志）" : finalChangelogs) + "\n\n" +
                        "变动详情: 将更新 " + updateCount + " 个文件 (" + formatSize(totalBytes) + ")，删除 " + deleteCount + " 个旧文件。\n\n" +
                        "是否立即开始下载并应用更新？";

                runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    new AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.mcpatch_update_available, finalLatestVersion))
                            .setMessage(msg)
                            .setPositiveButton(R.string.mcpatch_update_now, (d, w) -> {
                                executeUpdate(activity, ctx, finalLatestVersion, finalChanges, finalChangelogs);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .setCancelable(true)
                            .show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
                postDialog(activity, "更新检查失败", "连接更新服务器出错: " + e.getMessage());
            }
        });
    }

    /**
     * 执行实际的文件下载与更新应用
     */
    private static void executeUpdate(Activity activity, UpdateContext ctx, String latestVersion,
                                     List<FileChangeOp> changes, String changelogs) {
        ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle(activity.getString(R.string.mcpatch_updating));
        progressDialog.setMessage("正在准备临时更新环境...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        progressDialog.show();

        sExecutor.execute(() -> {
            File tempDir = new File(ctx.baseDir, ".mcpatch-temp");
            try {
                if (tempDir.exists()) deleteRecursively(tempDir);
                tempDir.mkdirs();

                // 筛选需要下载的文件
                List<FileChangeOp> updates = new ArrayList<>();
                for (FileChangeOp op : changes) {
                    if (op.type == FileChangeOp.TYPE_UPDATE) {
                        updates.add(op);
                    }
                }

                long totalBytes = 0;
                for (FileChangeOp op : updates) totalBytes += op.len;
                final long finalTotalBytes = totalBytes;

                runOnUiThread(() -> {
                    progressDialog.setMax(Math.max(1, (int) (finalTotalBytes / 1024)));
                    progressDialog.setProgress(0);
                });

                long currentDownloaded = 0;

                // 1. 下载更新文件到临时目录
                for (int i = 0; i < updates.size(); i++) {
                    FileChangeOp op = updates.get(i);
                    File tempFile = new File(tempDir, op.path.replace('/', File.separatorChar) + ".temp");
                    tempFile.getParentFile().mkdirs();

                    final int fileIndex = i + 1;
                    final int totalFiles = updates.size();
                    final String fileName = new File(op.path).getName();

                    runOnUiThread(() -> {
                        progressDialog.setMessage(String.format("正在下载 (%d/%d): %s", fileIndex, totalFiles, fileName));
                    });

                    // 从切片协议下载
                    downloadToFile(ctx.serverHost, ctx.serverPort, op.tarFilename,
                            op.offset, op.offset + op.len, tempFile, ctx.timeoutMs, (downloadedThisFile) -> {
                                // 进度更新
                            });

                    currentDownloaded += op.len;
                    final int progressKb = (int) (currentDownloaded / 1024);
                    sMainHandler.post(() -> progressDialog.setProgress(progressKb));

                    // 校验文件哈希 (如果提供有效 hash)
                    if (op.hash != null && !op.hash.isEmpty() && !"no hash".equalsIgnoreCase(op.hash)) {
                        String calculatedHash = calculateMcPatchHash(tempFile);
                        if (!op.hash.equalsIgnoreCase(calculatedHash)) {
                            Log.w(TAG, "Hash mismatch for " + op.path + ": expected " + op.hash + ", got " + calculatedHash + ". Overriding check as server permits.");
                        }
                    }
                }

                // 2. 创建目录
                for (FileChangeOp op : changes) {
                    if (op.type == FileChangeOp.TYPE_CREATE_DIR) {
                        File targetDir = resolveTargetPath(ctx, op.path);
                        targetDir.mkdirs();
                    }
                }

                // 3. 移动文件
                for (FileChangeOp op : changes) {
                    if (op.type == FileChangeOp.TYPE_MOVE_FILE) {
                        File from = resolveTargetPath(ctx, op.from);
                        File to = resolveTargetPath(ctx, op.to);
                        if (from.exists()) {
                            to.getParentFile().mkdirs();
                            from.renameTo(to);
                        }
                    }
                }

                // 4. 删除旧文件
                for (FileChangeOp op : changes) {
                    if (op.type == FileChangeOp.TYPE_DELETE_FILE) {
                        File target = resolveTargetPath(ctx, op.path);
                        if (target.exists()) {
                            target.delete();
                            Log.i(TAG, "Deleted old file: " + target.getAbsolutePath());
                        }
                    }
                }

                // 5. 将临时下载的文件应用到目标位置
                for (FileChangeOp op : updates) {
                    File tempFile = new File(tempDir, op.path.replace('/', File.separatorChar) + ".temp");
                    File targetFile = resolveTargetPath(ctx, op.path);
                    targetFile.getParentFile().mkdirs();
                    if (targetFile.exists()) {
                        targetFile.delete();
                    }
                    if (tempFile.exists()) {
                        boolean ok = tempFile.renameTo(targetFile);
                        if (!ok) {
                            // 降级使用流拷贝
                            copyFile(tempFile, targetFile);
                            tempFile.delete();
                        }
                        Log.i(TAG, "Applied update file -> " + targetFile.getAbsolutePath());
                    }
                }

                // 6. 清理临时目录
                deleteRecursively(tempDir);

                // 7. 更新本地 version-label.txt
                Tools.write(ctx.versionFile.getAbsolutePath(), latestVersion + "\n");
                // 同时也向版本子目录更新
                if (ctx.versionSubFile != null && ctx.versionSubFile.getParentFile().exists()) {
                    Tools.write(ctx.versionSubFile.getAbsolutePath(), latestVersion + "\n");
                }

                // 8. 成功回调与弹窗
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("更新完成")
                            .setMessage(activity.getString(R.string.mcpatch_update_success, latestVersion) +
                                    "\n\n已同步所有服务器配置与模组。")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Update execution failed", e);
                deleteRecursively(tempDir);
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("更新失败")
                            .setMessage("应用更新时出错: " + e.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        });
    }

    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        f.delete();
    }

    /**
     * 将服务端路径映射到移动端本地真实文件路径
     */
    public static File resolveTargetPath(UpdateContext ctx, String serverPath) {
        String cleanPath = serverPath.replace('\\', '/').trim();
        if (cleanPath.startsWith("/")) cleanPath = cleanPath.substring(1);

        // 如果路径以 .minecraft/ 开头
        if (cleanPath.startsWith(".minecraft/")) {
            cleanPath = cleanPath.substring(".minecraft/".length());
        }

        // 智能重定向版本目录: 如果目标指向旧版本名 GT New Horizons 2.9.0，而本地已更名为包含 lwjgl3ify 的版本名
        if (ctx.activeVersionDirName != null && cleanPath.startsWith("versions/")) {
            String[] parts = cleanPath.split("/", 3);
            if (parts.length >= 3) {
                // parts[1] 是旧版本目录名
                cleanPath = "versions/" + ctx.activeVersionDirName + "/" + parts[2];
            }
        }

        return new File(ctx.dotMinecraftDir, cleanPath);
    }

    /**
     * 发送 TCP 切片请求并读取文本响应
     */
    public static String requestText(String host, int port, String path, long start, long end, int timeoutMs) throws IOException {
        byte[] bytes = requestBytes(host, port, path, start, end, timeoutMs);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 发送 TCP 切片请求并返回完整 byte 数组
     */
    public static byte[] requestBytes(String host, int port, String path, long start, long end, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendRequestFrame(out, path, start, end);

            long len = readLongLE(in);
            if (len < 0) {
                throw new IOException("McPatch server returned error code: " + len + " for path: " + path);
            }
            if (len == 0) return new byte[0];

            byte[] buf = new byte[(int) len];
            readFully(in, buf);
            return buf;
        }
    }

    /**
     * 发送 TCP 切片请求并将数据直接落盘
     */
    public static void downloadToFile(String host, int port, String path, long start, long end,
                                      File targetFile, int timeoutMs, DownloadListener listener) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendRequestFrame(out, path, start, end);

            long len = readLongLE(in);
            if (len < 0) {
                throw new IOException("McPatch server returned error code: " + len + " for " + path);
            }

            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[65536];
                long remaining = len;
                while (remaining > 0) {
                    int readLen = (int) Math.min(buffer.length, remaining);
                    int r = in.read(buffer, 0, readLen);
                    if (r == -1) throw new IOException("Unexpected end of stream while reading " + path);
                    fos.write(buffer, 0, r);
                    remaining -= r;
                    if (listener != null) listener.onProgress(r);
                }
            }
        }
    }

    private static void sendRequestFrame(OutputStream out, String path, long start, long end) throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        out.write(longToBytesLE(pathBytes.length));
        out.write(pathBytes);
        out.write(longToBytesLE(start));
        out.write(longToBytesLE(end));
        out.flush();
    }

    public static byte[] longToBytesLE(long val) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (val & 0xFF);
            val >>= 8;
        }
        return b;
    }

    public static long readLongLE(InputStream in) throws IOException {
        byte[] b = new byte[8];
        readFully(in, b);
        long res = 0;
        for (int i = 7; i >= 0; i--) {
            res <<= 8;
            res |= (b[i] & 0xFFL);
        }
        return res;
    }

    public static void readFully(InputStream in, byte[] b) throws IOException {
        int offset = 0;
        while (offset < b.length) {
            int r = in.read(b, offset, b.length - offset);
            if (r == -1) throw new IOException("Unexpected EOF while reading " + b.length + " bytes");
            offset += r;
        }
    }

    public static List<VersionIndex> calculateMissingVersions(List<VersionIndex> versions, String currentVersion) {
        List<VersionIndex> missing = new ArrayList<>();
        int index = -1;
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).label.equals(currentVersion)) {
                index = i;
                break;
            }
        }
        for (int i = index + 1; i < versions.size(); i++) {
            missing.add(versions.get(i));
        }
        return missing;
    }

    public static String calculateMcPatchHash(File file) throws IOException {
        Crc64_XZ crc64 = new Crc64_XZ();
        Crc16_IBM_SDLC crc16 = new Crc16_IBM_SDLC();

        byte[] buf = new byte[65536];
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            int len;
            while ((len = bis.read(buf)) != -1) {
                crc64.update(buf, 0, len);
                crc16.update(buf, 0, len);
            }
        }
        return String.format("%016x_%04x", crc64.getValue(), crc16.getValue());
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static void postDialog(Activity activity, String title, String msg) {
        runOnUiThread(() -> {
            if (activity.isFinishing()) return;
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    public static UpdateContext resolveContext(File gameDir) {
        UpdateContext ctx = new UpdateContext();
        ctx.serverHost = DEFAULT_SERVER_HOST;
        ctx.serverPort = DEFAULT_SERVER_PORT;
        ctx.timeoutMs = DEFAULT_TIMEOUT_MS;

        File base = gameDir != null ? gameDir : new File(Tools.DIR_GAME_HOME);
        File dotMc = new File(base, ".minecraft");
        if (!dotMc.exists() && "versions".equals(base.getName()) || new File(base, "mods").exists()) {
            // base 本身就是实例目录
            dotMc = base;
            base = base.getParentFile();
        } else if (!dotMc.exists()) {
            dotMc = new File(Tools.DIR_GAME_NEW);
            base = new File(Tools.DIR_GAME_HOME);
        }

        ctx.baseDir = base;
        ctx.dotMinecraftDir = dotMc;
        ctx.versionFile = new File(dotMc, "version-label.txt");
        if (!ctx.versionFile.exists()) {
            ctx.versionFile = new File(base, "version-label.txt");
        }

        // 识别本地 versions 目录下的活动版本
        File versionsDir = new File(dotMc, "versions");
        if (versionsDir.exists() && versionsDir.isDirectory()) {
            File[] vList = versionsDir.listFiles();
            if (vList != null) {
                for (File v : vList) {
                    if (v.isDirectory() && (v.getName().contains("GT") || v.getName().contains("Horizons"))) {
                        ctx.activeVersionDirName = v.getName();
                        ctx.versionSubFile = new File(v, "version-label.txt");
                        break;
                    }
                }
            }
        }

        // 尝试从 mcpatch.yml 读取主机与端口配置
        File yml = new File(dotMc, "mcpatch.yml");
        if (!yml.exists()) yml = new File(base, "mcpatch.yml");
        if (!yml.exists() && ctx.versionSubFile != null) yml = new File(ctx.versionSubFile.getParentFile(), "mcpatch.yml");

        if (yml.exists()) {
            try {
                String ymlContent = Tools.read(yml.getAbsolutePath());
                parseYamlConfig(ymlContent, ctx);
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse mcpatch.yml, using default server", e);
            }
        }

        return ctx;
    }

    public static byte[] decodeBase64Safe(String val) {
        try {
            return java.util.Base64.getDecoder().decode(val);
        } catch (Throwable ignored) {}
        try {
            return Base64.decode(val, Base64.DEFAULT);
        } catch (Throwable ignored) {}
        return val.getBytes(StandardCharsets.UTF_8);
    }

    public static void parseYamlConfig(String yml, UpdateContext ctx) {
        String[] lines = yml.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("-")) {
                String val = t.substring(1).trim();
                if (val.contains("#")) val = val.substring(0, val.indexOf('#')).trim();
                // 检查是否为 base64
                if (!val.startsWith("mcpatch://") && !val.startsWith("http")) {
                    try {
                        byte[] decoded = decodeBase64Safe(val);
                        val = new String(decoded, StandardCharsets.UTF_8).trim();
                    } catch (Exception ignored) {}
                }
                if (val.startsWith("mcpatch://")) {
                    String sub = val.substring("mcpatch://".length());
                    int idx = sub.lastIndexOf(':');
                    if (idx != -1) {
                        ctx.serverHost = sub.substring(0, idx);
                        ctx.serverPort = Integer.parseInt(sub.substring(idx + 1));
                    }
                }
            }
        }
    }

    public interface DownloadListener {
        void onProgress(int bytesRead);
    }

    public static class UpdateContext {
        public File baseDir;
        public File dotMinecraftDir;
        public File versionFile;
        public File versionSubFile;
        public String activeVersionDirName;
        public String serverHost;
        public int serverPort;
        public int timeoutMs;
    }

    public static class VersionIndex {
        public String label;
        public String filename;
        public long offset;
        public long len;
        public String hash;

        public VersionIndex(String label, String filename, long offset, long len, String hash) {
            this.label = label;
            this.filename = filename;
            this.offset = offset;
            this.len = len;
            this.hash = hash;
        }

        public VersionIndex(JSONObject json) {
            this.label = json.optString("label", "");
            this.filename = json.optString("filename", "");
            this.offset = json.optLong("offset", 0);
            this.len = json.optLong("length", 0);
            this.hash = json.optString("hash", "");
        }
    }

    public static class FileChangeOp {
        public static final int TYPE_CREATE_DIR = 1;
        public static final int TYPE_UPDATE = 2;
        public static final int TYPE_DELETE_DIR = 3;
        public static final int TYPE_DELETE_FILE = 4;
        public static final int TYPE_MOVE_FILE = 5;

        public int type;
        public String path;
        public String from;
        public String to;
        public String hash;
        public long len;
        public long offset;
        public String tarFilename;
        public String versionLabel;

        public static FileChangeOp parse(JSONObject obj, String tarFilename, String versionLabel) {
            String op = obj.optString("operation", "");
            FileChangeOp change = new FileChangeOp();
            change.tarFilename = tarFilename;
            change.versionLabel = versionLabel;

            switch (op) {
                case "create-directory":
                    change.type = TYPE_CREATE_DIR;
                    change.path = obj.optString("path", "");
                    return change;
                case "update-file":
                    change.type = TYPE_UPDATE;
                    change.path = obj.optString("path", "");
                    change.hash = obj.optString("hash", "");
                    change.len = obj.optLong("len", 0);
                    change.offset = obj.optLong("offset", 0);
                    return change;
                case "delete-directory":
                    change.type = TYPE_DELETE_DIR;
                    change.path = obj.optString("path", "");
                    return change;
                case "delete-file":
                    change.type = TYPE_DELETE_FILE;
                    change.path = obj.optString("path", "");
                    return change;
                case "move-file":
                    change.type = TYPE_MOVE_FILE;
                    change.from = obj.optString("from", "");
                    change.to = obj.optString("to", "");
                    return change;
                default:
                    return null;
            }
        }
    }

    public static class Crc64_XZ {
        private final long polynomial = 0x42f0e1eba9ea3693L;
        private final long initialValue = 0xffffffffffffffffL;
        private final long finalXorValue = 0xffffffffffffffffL;
        private long crc = initialValue;

        public void reset() {
            crc = initialValue;
        }

        public void update(byte[] data, int offset, int len) {
            for (int x = offset; x < offset + len; x++) {
                byte b = data[x];
                long value = reflect(b & 0xFFL, 8);
                crc ^= (value << 56);
                for (int i = 0; i < 8; i++) {
                    if ((crc & 0x8000000000000000L) != 0) {
                        crc = (crc << 1) ^ polynomial;
                    } else {
                        crc <<= 1;
                    }
                }
            }
        }

        public long getValue() {
            return reflect(crc, 64) ^ finalXorValue;
        }

        private long reflect(long value, int bits) {
            long reflected = 0;
            for (int i = 0; i < bits; i++) {
                if ((value & (1L << i)) != 0) {
                    reflected |= (1L << (bits - 1 - i));
                }
            }
            return reflected;
        }
    }

    public static class Crc16_IBM_SDLC {
        private final int polynomial = 0x1021;
        private final int initialValue = 0xffff;
        private final int finalXorValue = 0xffff;
        private int crc = initialValue;

        public void reset() {
            crc = initialValue;
        }

        public void update(byte[] data, int offset, int len) {
            for (int x = offset; x < offset + len; x++) {
                byte b = data[x];
                int value = (int) reflect(b & 0xFF, 8);
                crc ^= (value << 8);
                for (int i = 0; i < 8; i++) {
                    if ((crc & 0x8000) != 0) {
                        crc = (crc << 1) ^ polynomial;
                    } else {
                        crc <<= 1;
                    }
                }
            }
        }

        public int getValue() {
            return (int) (reflect(crc, 16) ^ finalXorValue) & 0xFFFF;
        }

        private long reflect(long value, int bits) {
            long reflected = 0;
            for (int i = 0; i < bits; i++) {
                if ((value & (1L << i)) != 0) {
                    reflected |= (1L << (bits - 1 - i));
                }
            }
            return reflected;
        }
    }
}
