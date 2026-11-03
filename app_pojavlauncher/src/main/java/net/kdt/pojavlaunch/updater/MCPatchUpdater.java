package net.kdt.pojavlaunch.updater;

import android.app.Activity;
import android.app.ProgressDialog;
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

    public static void checkAndPromptUpdate(Activity activity, File gameDir) {
        checkAndPromptUpdate(activity, gameDir, null, null);
    }

    public static void checkAndPromptUpdate(Activity activity, File gameDir, String preferredVersionId, Runnable onComplete) {
        if (activity == null || activity.isFinishing()) return;

        Toast.makeText(activity, R.string.mcpatch_checking, Toast.LENGTH_SHORT).show();

        sExecutor.execute(() -> {
            try {
                // 1. 定位 baseDir 与版本相关文件
                UpdateContext ctx = resolveContext(gameDir, preferredVersionId);
                Log.i(TAG, "Checking update with baseDir: " + ctx.baseDir + ", dotMc: " + ctx.dotMinecraftDir +
                        ", versionFile: " + ctx.versionFile + ", activeVer: " + ctx.activeVersionDirName);

                String currentVersion = "";
                if (ctx.versionFile != null && ctx.versionFile.exists()) {
                    currentVersion = Tools.read(ctx.versionFile.getAbsolutePath()).trim();
                } else if (ctx.versionSubFile != null && ctx.versionSubFile.exists()) {
                    currentVersion = Tools.read(ctx.versionSubFile.getAbsolutePath()).trim();
                    ctx.versionFile = ctx.versionSubFile;
                }
                Log.i(TAG, "Current client version: '" + currentVersion + "'");

                // 2. 连接服务端拉取 index.json
                String indexJsonStr = requestText(ctx.serverHost, ctx.serverPort, "index.json", 0, 0, ctx.timeoutMs);
                JSONArray indexArray = new JSONArray(indexJsonStr);

                if (indexArray.length() == 0) {
                    postDialog(activity, activity.getString(R.string.check_server_update),
                            activity.getString(R.string.mcpatch_no_update, currentVersion.isEmpty() ? "1.0" : currentVersion));
                    return;
                }

                List<VersionIndex> versions = new ArrayList<>();
                for (int i = 0; i < indexArray.length(); i++) {
                    versions.add(new VersionIndex(indexArray.getJSONObject(i)));
                }

                VersionIndex latest = versions.get(versions.size() - 1);
                Log.i(TAG, "Latest server version: " + latest.label);

                if (!currentVersion.isEmpty() && latest.label.equals(currentVersion)) {
                    postDialog(activity, activity.getString(R.string.check_server_update),
                            activity.getString(R.string.mcpatch_no_update, currentVersion));
                    return;
                }

                // 3. 计算缺失的版本 (如果本地版本非空且不在列表中，提示无法识别)
                List<VersionIndex> missing;
                try {
                    missing = calculateMissingVersions(versions, currentVersion);
                } catch (IllegalStateException e) {
                    postDialog(activity, "版本识别异常", e.getMessage());
                    return;
                }

                if (missing.isEmpty()) {
                    postDialog(activity, activity.getString(R.string.check_server_update),
                            activity.getString(R.string.mcpatch_no_update, currentVersion));
                    return;
                }

                // 4. 构建更新计划并合并多版本操作 (防止重复下载与误删/误复原)
                UpdatePlan plan = buildUpdatePlan(missing, (filename, offset, len) ->
                        requestText(ctx.serverHost, ctx.serverPort, filename, offset, offset + len, ctx.timeoutMs)
                );
                plan.latestVersion = latest.label;

                if (plan.updateFiles.isEmpty() && plan.deleteFiles.isEmpty() && plan.moveFiles.isEmpty() && plan.createDirs.isEmpty()) {
                    // 无实质文件变更，仅版本号落后，直接同步版本号
                    if (ctx.versionFile != null) {
                        Tools.write(ctx.versionFile.getAbsolutePath(), latest.label + "\n");
                    }
                    if (ctx.versionSubFile != null && ctx.versionSubFile.getParentFile().exists()) {
                        Tools.write(ctx.versionSubFile.getAbsolutePath(), latest.label + "\n");
                    }
                    postDialog(activity, activity.getString(R.string.check_server_update),
                            activity.getString(R.string.mcpatch_no_update, latest.label));
                    return;
                }

                // 5. 弹窗提示用户确认更新
                final String finalCurrentVersion = currentVersion.isEmpty() ? "未安装/全新" : currentVersion;
                final String finalLatestVersion = latest.label;
                final String finalChangelogs = plan.changelogs.toString().trim();

                String msg = "当前版本: " + finalCurrentVersion + "\n" +
                        "最新版本: " + finalLatestVersion + "\n\n" +
                        "更新日志:\n" + (finalChangelogs.isEmpty() ? "（无日志）" : finalChangelogs) + "\n\n" +
                        "变动详情: 将下载 " + plan.updateFiles.size() + " 个更新文件 (" + formatSize(plan.totalDownloadBytes) + ")，" +
                        "删除 " + plan.deleteFiles.size() + " 个旧文件，" +
                        (plan.moveFiles.isEmpty() ? "" : "移动 " + plan.moveFiles.size() + " 个文件，") +
                        (plan.deleteDirs.isEmpty() ? "" : "删除 " + plan.deleteDirs.size() + " 个旧目录，") +
                        "\n\n是否立即开始下载并应用更新？";

                runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    new AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.mcpatch_update_available, finalLatestVersion))
                            .setMessage(msg)
                            .setPositiveButton(R.string.mcpatch_update_now, (d, w) -> {
                                executeUpdate(activity, ctx, finalLatestVersion, plan, onComplete);
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

    public interface MetaFetcher {
        String fetch(String filename, long offset, long len) throws Exception;
    }

    public static UpdatePlan buildUpdatePlan(List<VersionIndex> missing, MetaFetcher fetcher) throws Exception {
        UpdatePlan plan = new UpdatePlan();
        List<RawMetaItem> allVersionMetas = new ArrayList<>();

        for (VersionIndex ver : missing) {
            String metaJsonStr = fetcher.fetch(ver.filename, ver.offset, ver.len);
            JSONArray metas = new JSONArray(metaJsonStr);
            for (int m = 0; m < metas.length(); m++) {
                JSONObject mObj = metas.getJSONObject(m);
                String vLabel = mObj.optString("label", ver.label);
                // 容错去重: 避免同一 label 重复收集
                boolean exists = false;
                for (RawMetaItem existing : allVersionMetas) {
                    if (vLabel.equals(existing.json.optString("label", ""))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    allVersionMetas.add(new RawMetaItem(ver.filename, mObj));
                }
            }
        }

        // 收集变更并合并操作 (遵循 McPatch Work.java 规范生命周期)
        for (RawMetaItem metaItem : allVersionMetas) {
            JSONObject meta = metaItem.json;
            String vLabel = meta.optString("label", "");
            String tarFilename = metaItem.tarFilename;
            JSONArray chArray = meta.optJSONArray("changes");
            if (chArray == null) continue;

            for (int c = 0; c < chArray.length(); c++) {
                JSONObject cObj = chArray.getJSONObject(c);
                String op = cObj.optString("operation", "");
                String path = cObj.optString("path", "").trim();

                switch (op) {
                    case "create-directory":
                        plan.deleteDirs.remove(path);
                        if (!plan.createDirs.contains(path)) plan.createDirs.add(path);
                        break;

                    case "delete-directory":
                        plan.createDirs.remove(path);
                        if (!plan.deleteDirs.contains(path)) plan.deleteDirs.add(path);
                        break;

                    case "update-file":
                        // 删除旧版本的同名下载任务，以最新版本为准
                        plan.updateFiles.removeIf(u -> u.path.equals(path));
                        // 移除可能存在的删除记录
                        plan.deleteFiles.remove(path);
                        UpdateFileOp updateOp = new UpdateFileOp();
                        updateOp.tarFilename = tarFilename;
                        updateOp.versionLabel = vLabel;
                        updateOp.path = path;
                        updateOp.hash = cObj.optString("hash", "");
                        updateOp.len = cObj.optLong("len", 0);
                        updateOp.offset = cObj.optLong("offset", 0);
                        updateOp.modified = cObj.optLong("modified", 0);
                        plan.updateFiles.add(updateOp);
                        break;

                    case "delete-file":
                        // 若前期版本要下载但后期版本要删除该文件，直接取消下载！
                        plan.updateFiles.removeIf(u -> u.path.equals(path));
                        if (!plan.deleteFiles.contains(path)) plan.deleteFiles.add(path);
                        break;

                    case "move-file":
                        String from = cObj.optString("from", "").trim();
                        String to = cObj.optString("to", "").trim();
                        // 如果在待下载列表中，直接修改其下载路径
                        boolean matched = false;
                        for (UpdateFileOp u : plan.updateFiles) {
                            if (u.path.equals(from)) {
                                u.path = to;
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            plan.moveFiles.add(new MoveOp(from, to));
                        }
                        break;
                }
            }
        }

        // 倒序整理更新日志 (最新版本在最前)
        List<RawMetaItem> reversed = new ArrayList<>(allVersionMetas);
        Collections.reverse(reversed);
        for (RawMetaItem item : reversed) {
            String vLabel = item.json.optString("label", "");
            String logs = item.json.optString("logs", "").trim();
            plan.changelogs.append("[").append(vLabel).append("]\n")
                    .append(logs.isEmpty() ? "（常规维护更新）" : logs).append("\n\n");
        }

        // 计算有效总下载字节数
        long total = 0;
        for (UpdateFileOp u : plan.updateFiles) {
            total += u.len;
        }
        plan.totalDownloadBytes = total;

        return plan;
    }

    /**
     * 执行实际的文件下载与更新应用
     */
    private static void executeUpdate(Activity activity, UpdateContext ctx, String latestVersion,
                                     UpdatePlan plan, Runnable onComplete) {
        ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle(activity.getString(R.string.mcpatch_updating));
        progressDialog.setMessage("正在准备临时更新环境...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        progressDialog.show();

        sExecutor.execute(() -> {
            File tempDir = new File(ctx.dotMinecraftDir, ".mcpatch-temp");
            try {
                if (tempDir.exists()) deleteRecursively(tempDir);
                tempDir.mkdirs();

                // 预先检查本地已存在且 hash 一致的文件，跳过重复下载
                List<UpdateFileOp> filesToDownload = new ArrayList<>();
                for (UpdateFileOp op : plan.updateFiles) {
                    File targetFile = resolveTargetPath(ctx, op.path);
                    if (targetFile.exists() && targetFile.length() == op.len && op.hash != null && !op.hash.isEmpty() && !"no hash".equalsIgnoreCase(op.hash)) {
                        String localHash = calculateMcPatchHash(targetFile);
                        if (op.hash.equalsIgnoreCase(localHash)) {
                            Log.i(TAG, "Skipping download for already up-to-date file: " + op.path);
                            continue;
                        }
                    }
                    filesToDownload.add(op);
                }

                long totalBytes = 0;
                for (UpdateFileOp op : filesToDownload) totalBytes += op.len;
                final long finalTotalBytes = totalBytes;

                runOnUiThread(() -> {
                    progressDialog.setMax(Math.max(1, (int) (finalTotalBytes / 1024)));
                    progressDialog.setProgress(0);
                });

                long currentDownloaded = 0;

                // 1. 下载更新文件到临时目录
                for (int i = 0; i < filesToDownload.size(); i++) {
                    UpdateFileOp op = filesToDownload.get(i);
                    File tempFile = new File(tempDir, op.path.replace('/', File.separatorChar) + ".temp");
                    tempFile.getParentFile().mkdirs();

                    final int fileIndex = i + 1;
                    final int totalFiles = filesToDownload.size();
                    final String fileName = new File(op.path).getName();

                    runOnUiThread(() -> {
                        progressDialog.setMessage(String.format("正在下载 (%d/%d): %s", fileIndex, totalFiles, fileName));
                    });

                    // 从切片协议下载
                    downloadToFile(ctx.serverHost, ctx.serverPort, op.tarFilename,
                            op.offset, op.offset + op.len, tempFile, ctx.timeoutMs, null);

                    currentDownloaded += op.len;
                    final int progressKb = (int) (currentDownloaded / 1024);
                    sMainHandler.post(() -> progressDialog.setProgress(progressKb));

                    // 强校验文件哈希: 避免损坏文件损坏游戏客户端
                    if (op.hash != null && !op.hash.isEmpty() && !"no hash".equalsIgnoreCase(op.hash)) {
                        String calculatedHash = calculateMcPatchHash(tempFile);
                        if (!op.hash.equalsIgnoreCase(calculatedHash)) {
                            throw new IOException(String.format("文件校验失败 (Hash mismatch): %s (预期 %s, 实际 %s)",
                                    op.path, op.hash, calculatedHash));
                        }
                    }
                }

                // 2. 创建目录
                for (String dirPath : plan.createDirs) {
                    File targetDir = resolveTargetPath(ctx, dirPath);
                    targetDir.mkdirs();
                }

                // 3. 移动文件
                for (MoveOp move : plan.moveFiles) {
                    File from = resolveTargetPath(ctx, move.from);
                    File to = resolveTargetPath(ctx, move.to);
                    if (from.exists()) {
                        to.getParentFile().mkdirs();
                        if (to.exists()) to.delete();
                        boolean ok = from.renameTo(to);
                        if (!ok) {
                            copyFile(from, to);
                            from.delete();
                        }
                    }
                }

                // 4. 删除旧文件
                for (String delPath : plan.deleteFiles) {
                    File target = resolveTargetPath(ctx, delPath);
                    if (target.exists()) {
                        target.delete();
                        Log.i(TAG, "Deleted old file: " + target.getAbsolutePath());
                    }
                }

                // 5. 删除废弃目录
                for (String delDir : plan.deleteDirs) {
                    File targetDir = resolveTargetPath(ctx, delDir);
                    if (targetDir.exists()) {
                        deleteRecursively(targetDir);
                        Log.i(TAG, "Deleted old directory: " + targetDir.getAbsolutePath());
                    }
                }

                // 6. 将临时下载的文件原子应用到目标位置
                for (UpdateFileOp op : filesToDownload) {
                    File tempFile = new File(tempDir, op.path.replace('/', File.separatorChar) + ".temp");
                    File targetFile = resolveTargetPath(ctx, op.path);
                    targetFile.getParentFile().mkdirs();
                    if (targetFile.exists()) {
                        targetFile.delete();
                    }
                    if (tempFile.exists()) {
                        boolean ok = tempFile.renameTo(targetFile);
                        if (!ok) {
                            copyFile(tempFile, targetFile);
                            tempFile.delete();
                        }
                        Log.i(TAG, "Applied update file -> " + targetFile.getAbsolutePath());
                    }
                }

                // 7. 清理临时目录
                deleteRecursively(tempDir);

                // 8. 更新本地 version-label.txt
                if (ctx.versionFile != null) {
                    ctx.versionFile.getParentFile().mkdirs();
                    Tools.write(ctx.versionFile.getAbsolutePath(), latestVersion + "\n");
                }
                if (ctx.versionSubFile != null && ctx.versionSubFile.getParentFile().exists()) {
                    Tools.write(ctx.versionSubFile.getAbsolutePath(), latestVersion + "\n");
                }

                // 9. 成功回调与弹窗
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("更新完成")
                            .setMessage(activity.getString(R.string.mcpatch_update_success, latestVersion) +
                                    "\n\n已成功同步所有服务器配置与模组。")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    if (onComplete != null) {
                        onComplete.run();
                    }
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
        while (cleanPath.startsWith("/")) cleanPath = cleanPath.substring(1);

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
                throw new IOException("McPatch server error (" + len + ") for " + path);
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
                throw new IOException("McPatch server error (" + len + ") for " + path);
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
        if (currentVersion == null || currentVersion.trim().isEmpty()) {
            missing.addAll(versions);
            return missing;
        }

        String cur = currentVersion.trim();
        int index = -1;
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).label.equalsIgnoreCase(cur)) {
                index = i;
                break;
            }
        }

        if (index == -1) {
            throw new IllegalStateException("客户端版本 (" + cur + ") 不在服务器版本库中，无法确定增量更新路径。");
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

    public static String formatSize(long bytes) {
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
        return resolveContext(gameDir, null);
    }

    public static UpdateContext resolveContext(File gameDir, String preferredVersionId) {
        UpdateContext ctx = new UpdateContext();
        ctx.serverHost = DEFAULT_SERVER_HOST;
        ctx.serverPort = DEFAULT_SERVER_PORT;
        ctx.timeoutMs = DEFAULT_TIMEOUT_MS;

        File base = gameDir != null ? gameDir : new File(Tools.DIR_GAME_HOME);
        File dotMc;

        // 识别 .minecraft 目录与 baseDir
        if (".minecraft".equalsIgnoreCase(base.getName()) || new File(base, "versions").exists()) {
            dotMc = base;
            base = base.getParentFile() != null ? base.getParentFile() : base;
        } else if (new File(base, ".minecraft").exists()) {
            dotMc = new File(base, ".minecraft");
        } else {
            dotMc = new File(Tools.DIR_GAME_NEW);
            base = new File(Tools.DIR_GAME_HOME);
        }

        ctx.baseDir = base;
        ctx.dotMinecraftDir = dotMc;

        // 智能定位当前活动版本目录
        File versionsDir = new File(dotMc, "versions");
        if (preferredVersionId != null && !preferredVersionId.isEmpty() && new File(versionsDir, preferredVersionId).exists()) {
            ctx.activeVersionDirName = preferredVersionId;
        } else if (versionsDir.exists() && versionsDir.isDirectory()) {
            File[] vList = versionsDir.listFiles();
            if (vList != null) {
                // 优先寻找包含 lwjgl3ify 的目录
                for (File v : vList) {
                    if (v.isDirectory() && v.getName().toLowerCase().contains("lwjgl3ify")) {
                        ctx.activeVersionDirName = v.getName();
                        break;
                    }
                }
                // 其次寻找包含 GT 或 Horizons 的目录
                if (ctx.activeVersionDirName == null) {
                    for (File v : vList) {
                        if (v.isDirectory() && (v.getName().contains("GT") || v.getName().contains("Horizons"))) {
                            ctx.activeVersionDirName = v.getName();
                            break;
                        }
                    }
                }
                // 最后如果只有一个目录，使用它
                if (ctx.activeVersionDirName == null) {
                    for (File v : vList) {
                        if (v.isDirectory()) {
                            ctx.activeVersionDirName = v.getName();
                            break;
                        }
                    }
                }
            }
        }

        // 定位 versionSubFile (版本子目录中的标签)
        if (ctx.activeVersionDirName != null) {
            ctx.versionSubFile = new File(new File(versionsDir, ctx.activeVersionDirName), "version-label.txt");
        }

        // 定位 versionFile (优先使用已存在的文件)
        File f1 = new File(dotMc, "version-label.txt");
        File f2 = ctx.versionSubFile;
        File f3 = new File(base, "version-label.txt");

        if (f1.exists()) {
            ctx.versionFile = f1;
        } else if (f2 != null && f2.exists()) {
            ctx.versionFile = f2;
        } else if (f3.exists()) {
            ctx.versionFile = f3;
        } else {
            ctx.versionFile = f1;
        }

        // 尝试从多处读取 mcpatch.yml 配置
        List<File> ymlCandidates = new ArrayList<>();
        ymlCandidates.add(new File(dotMc, "mcpatch.yml"));
        ymlCandidates.add(new File(base, "mcpatch.yml"));
        if (ctx.activeVersionDirName != null) {
            ymlCandidates.add(new File(new File(versionsDir, ctx.activeVersionDirName), "mcpatch.yml"));
        }
        ymlCandidates.add(new File(new File(base, "自动更新"), "mcpatch.yml"));

        for (File yml : ymlCandidates) {
            if (yml.exists()) {
                try {
                    String ymlContent = Tools.read(yml.getAbsolutePath());
                    parseYamlConfig(ymlContent, ctx);
                    Log.i(TAG, "Loaded McPatch config from: " + yml.getAbsolutePath());
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse " + yml.getAbsolutePath(), e);
                }
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
            } else if (t.startsWith("private-timeout:")) {
                try {
                    ctx.timeoutMs = Integer.parseInt(t.substring("private-timeout:".length()).trim());
                } catch (Exception ignored) {}
            } else if (t.startsWith("version-file-path:")) {
                String p = t.substring("version-file-path:".length()).trim();
                if (!p.isEmpty() && ctx.dotMinecraftDir != null) {
                    File vf = new File(ctx.dotMinecraftDir, p);
                    if (vf.exists()) ctx.versionFile = vf;
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

    public static class RawMetaItem {
        public final String tarFilename;
        public final JSONObject json;
        public RawMetaItem(String tarFilename, JSONObject json) {
            this.tarFilename = tarFilename;
            this.json = json;
        }
    }

    public static class UpdatePlan {
        public final List<String> createDirs = new ArrayList<>();
        public final List<String> deleteDirs = new ArrayList<>();
        public final List<String> deleteFiles = new ArrayList<>();
        public final List<MoveOp> moveFiles = new ArrayList<>();
        public final List<UpdateFileOp> updateFiles = new ArrayList<>();
        public final StringBuilder changelogs = new StringBuilder();
        public String latestVersion = "";
        public long totalDownloadBytes = 0;
    }

    public static class UpdateFileOp {
        public String tarFilename;
        public String versionLabel;
        public String path;
        public String hash;
        public long len;
        public long offset;
        public long modified;
    }

    public static class MoveOp {
        public String from;
        public String to;

        public MoveOp(String from, String to) {
            this.from = from;
            this.to = to;
        }
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
