package net.kdt.pojavlaunch.modloaders;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

public class GTNHProfileHelper {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Avoid the native Mali texture-upload bottleneck observed on XRing O1 with GTNH.
     * Apply once per profile; a saved user choice always takes precedence.
     * @return whether the caller needs to persist the profile
     */
    public static boolean applyXringO1RendererDefaults(MinecraftProfile profile, File gameDir,
                                                      String socManufacturer, String socModel) {
        if (profile.anglePreferenceSet
                || !"XRing".equalsIgnoreCase(socManufacturer)
                || !"O1".equalsIgnoreCase(socModel)
                || (profile.pojavRendererName != null
                    && !"opengles_mobileglues".equals(profile.pojavRendererName))
                || !isGTNHInstance(gameDir)) {
            return false;
        }
        profile.setAnglePreference(true);
        return true;
    }

    /**
     * 检测目标目录是否为 GTNH 实例
     */
    public static boolean isGTNHInstance(File gameDir) {
        if (gameDir == null || !gameDir.exists()) return false;
        File modsDir = new File(gameDir, "mods");
        if (!modsDir.exists() || !modsDir.isDirectory()) return false;

        File[] files = modsDir.listFiles();
        if (files == null) return false;

        boolean hasLwjgl3ify = false;
        boolean hasGTNHSignature = false;

        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.startsWith("lwjgl3ify-")) {
                hasLwjgl3ify = true;
            }
            if (name.contains("gt5-unofficial") || name.contains("newhorizonscoremod") || name.contains("dreamassembler")) {
                hasGTNHSignature = true;
            }
        }
        return hasLwjgl3ify && hasGTNHSignature;
    }

    /**
     * 自动清洗与修正 lwjgl3ify.cfg，防止在移动端因共享 GL 上下文崩溃
     */
    public static void sanitizeLwjgl3ifyConfig(File gameDir) throws IOException {
        File configDir = new File(gameDir, "config");
        if (!configDir.exists()) configDir.mkdirs();
        File cfg = new File(configDir, "lwjgl3ify.cfg");
        if (!cfg.exists()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(cfg))) {
                writer.write("openglcontext {\n");
                writer.write("    B:sharedContext=false\n");
                writer.write("}\n");
                writer.write("window {\n");
                writer.write("    B:hidpiRendering=true\n");
                writer.write("}\n");
                writer.write("linux {\n");
                writer.write("    B:linuxCreateAppDesktopEntry=false\n");
                writer.write("}\n");
            }
            return;
        }

        List<String> lines = new ArrayList<>();
        boolean foundShared = false;
        boolean foundHiDpi = false;
        boolean foundLinuxDesktop = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(cfg))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("B:sharedContext=")) {
                    lines.add("    B:sharedContext=false");
                    foundShared = true;
                } else if (line.trim().startsWith("B:hidpiRendering=")) {
                    lines.add("    B:hidpiRendering=true");
                    foundHiDpi = true;
                } else if (line.trim().startsWith("B:linuxCreateAppDesktopEntry=")) {
                    lines.add("    B:linuxCreateAppDesktopEntry=false");
                    foundLinuxDesktop = true;
                } else {
                    lines.add(line);
                }
            }
        }

        if (!foundShared) {
            lines.add("openglcontext {");
            lines.add("    B:sharedContext=false");
            lines.add("}");
        }
        if (!foundHiDpi) {
            lines.add("window {");
            lines.add("    B:hidpiRendering=true");
            lines.add("}");
        }
        if (!foundLinuxDesktop) {
            lines.add("linux {");
            lines.add("    B:linuxCreateAppDesktopEntry=false");
            lines.add("}");
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(cfg))) {
            for (String l : lines) {
                writer.write(l);
                writer.newLine();
            }
        }
    }

    /**
     * 自动清洗与修正 betterloadingscreen.cfg，防止在移动端因共享 GL 上下文多线程渲染崩溃
     */
    public static void sanitizeBetterLoadingScreenConfig(File gameDir) throws IOException {
        File configDir = new File(gameDir, "config/Betterloadingscreen");
        if (!configDir.exists()) configDir.mkdirs();
        File cfg = new File(configDir, "betterloadingscreen.cfg");
        if (!cfg.exists()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(cfg))) {
                writer.write("general {\n");
                writer.write("    B:threadedRendering=false\n");
                writer.write("}\n");
            }
            return;
        }

        List<String> lines = new ArrayList<>();
        boolean foundThreaded = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(cfg))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("B:threadedRendering=")) {
                    lines.add("    B:threadedRendering=false");
                    foundThreaded = true;
                } else {
                    lines.add(line);
                }
            }
        }

        if (!foundThreaded) {
            lines.add("general {");
            lines.add("    B:threadedRendering=false");
            lines.add("}");
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(cfg))) {
            for (String l : lines) {
                writer.write(l);
                writer.newLine();
            }
        }
    }

    /**
     * 自动清洗与修正 FpsReducer 配置并禁用模组 jar，防止在移动端因无触控/键鼠活跃事件被强制限制为 10 FPS
     */
    public static void sanitizeFpsReducerConfig(File gameDir) throws IOException {
        if (gameDir == null) return;
        File configDir = new File(gameDir, "config/FpsReducer");
        if (!configDir.exists()) configDir.mkdirs();
        File cfg = new File(configDir, "FpsReducer.cfg");
        if (!cfg.exists()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(cfg))) {
                writer.write("common {\n");
                writer.write("    B:enableFpsReducer=false\n");
                writer.write("}\n");
            }
        } else {
            List<String> lines = new ArrayList<>();
            boolean foundEnable = false;
            try (BufferedReader reader = new BufferedReader(new FileReader(cfg))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("B:enableFpsReducer=")) {
                        lines.add("    B:enableFpsReducer=false");
                        foundEnable = true;
                    } else {
                        lines.add(line);
                    }
                }
            }
            if (!foundEnable) {
                lines.add("common {");
                lines.add("    B:enableFpsReducer=false");
                lines.add("}");
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(cfg))) {
                for (String l : lines) {
                    writer.write(l);
                    writer.newLine();
                }
            }
        }

        // 同时禁用 mods 目录下的 FpsReducer jar 包
        File modsDir = new File(gameDir, "mods");
        if (modsDir.exists() && modsDir.isDirectory()) {
            File[] files = modsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    if (name.contains("fpsreducer") && name.endsWith(".jar")) {
                        File disabled = new File(modsDir, file.getName() + ".disabled");
                        file.renameTo(disabled);
                    }
                }
            }
        }
    }

    /**
     * 自动清洗与修正 Angelica 配置与模块设置，避免移动端 Mali GPU 上的 256MB 缓冲区映射抖动与间接绘制卡顿
     */
    public static void sanitizeAngelicaConfig(File gameDir) throws IOException {
        if (gameDir == null) return;
        File configDir = new File(gameDir, "config");
        if (!configDir.exists()) configDir.mkdirs();

        // 1. 清洗 config/angelica-options.json
        File optionsJson = new File(configDir, "angelica-options.json");
        try {
            JsonObject root;
            if (optionsJson.exists()) {
                String content = new String(Files.readAllBytes(optionsJson.toPath()), "UTF-8");
                root = JsonParser.parseString(content).getAsJsonObject();
            } else {
                root = new JsonObject();
            }

            JsonObject advanced = root.has("advanced") && root.get("advanced").isJsonObject()
                    ? root.getAsJsonObject("advanced") : new JsonObject();
            advanced.addProperty("streaming_upload_strategy", "BUFFER_SUB_DATA");
            advanced.addProperty("vsync_mode", "OFF");
            advanced.addProperty("multi_draw_mode", "DIRECT");
            advanced.addProperty("allow_direct_memory_access", false);
            root.add("advanced", advanced);

            JsonObject reducer = root.has("reducer") && root.get("reducer").isJsonObject()
                    ? root.getAsJsonObject("reducer") : new JsonObject();
            reducer.addProperty("enabled", false);
            reducer.addProperty("limit_menu_frame_rate", false);
            root.add("reducer", reducer);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(optionsJson))) {
                writer.write(GSON.toJson(root));
            }
        } catch (Exception ignored) {
        }

        // 2. 清洗 config/angelica-modules.cfg
        File modulesCfg = new File(configDir, "angelica-modules.cfg");
        if (modulesCfg.exists()) {
            List<String> lines = new ArrayList<>();
            boolean foundDsa = false;
            try (BufferedReader reader = new BufferedReader(new FileReader(modulesCfg))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("B:enableDSA=")) {
                        lines.add("    B:enableDSA=false");
                        foundDsa = true;
                    } else {
                        lines.add(line);
                    }
                }
            }
            if (!foundDsa) {
                lines.add("general {");
                lines.add("    B:enableDSA=false");
                lines.add("}");
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(modulesCfg))) {
                for (String l : lines) {
                    writer.write(l);
                    writer.newLine();
                }
            }
        }
    }

    /**
     * 自动清洗 options.txt，解除垂直同步并将最大帧率上限调整为 260
     */
    public static void sanitizeOptionsTxt(File gameDir) throws IOException {
        if (gameDir == null) return;
        File optionsFile = new File(gameDir, "options.txt");
        if (!optionsFile.exists()) return;

        List<String> lines = new ArrayList<>();
        boolean foundVsync = false;
        boolean foundMaxFps = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(optionsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("enableVsync:")) {
                    lines.add("enableVsync:false");
                    foundVsync = true;
                } else if (line.startsWith("maxFps:")) {
                    lines.add("maxFps:260");
                    foundMaxFps = true;
                } else {
                    lines.add(line);
                }
            }
        }
        if (!foundVsync) {
            lines.add("enableVsync:false");
        }
        if (!foundMaxFps) {
            lines.add("maxFps:260");
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(optionsFile))) {
            for (String l : lines) {
                writer.write(l);
                writer.newLine();
            }
        }
    }

    /**
     * 获取专为玄戒 O1 / 8 Gen 2 调优的 Java 25 GTNH 黄金参数
     */
    public static List<String> getRecommendedJvmArgs(long maxMemoryMb) {
        long xms = Math.max(2048, Math.min(maxMemoryMb, maxMemoryMb - 2048));
        return new ArrayList<>(Arrays.asList(
                "-Dfile.encoding=UTF-8",
                "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader",
                "--enable-native-access=ALL-UNNAMED",
                "-Xms" + xms + "M",
                "-Xmx" + maxMemoryMb + "M",
                "-XX:+UseG1GC",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseCompactObjectHeaders",
                "-XX:+ParallelRefProcEnabled",
                "-XX:MaxGCPauseMillis=20",
                "-XX:G1ReservePercent=15",
                "-XX:InitiatingHeapOccupancyPercent=45",
                "-XX:+TieredCompilation",
                "-XX:+UseDynamicNumberOfCompilerThreads",
                "-XX:CICompilerCount=6",
                "-XX:Tier3InvocationThreshold=100",
                "-XX:Tier3CompileThreshold=1000",
                "-XX:Tier4InvocationThreshold=4000",
                "-XX:+SegmentedCodeCache",
                "-XX:ReservedCodeCacheSize=256M",
                "-XX:+PerfDisableSharedMem",
                "-Djava.util.zip.ZipFile.useShortEntries=true"
        ));
    }

    /**
     * 健壮地合并现有 JVM 参数与推荐参数：
     * 1. 针对 -Xmx 与 -Xms 使用正则 (-Xmx[0-9]+[mMkKgG]?) 替换现有值（若推荐参数中有指定），若不存在则追加；
     * 2. 对其余推荐参数按前缀排重追加，避免覆盖用户自定义配置或重复前缀。
     */
    public static String mergeJvmArgs(String existingArgs, List<String> recommendedArgs) {
        if (recommendedArgs == null || recommendedArgs.isEmpty()) {
            return existingArgs != null ? existingArgs.trim() : "";
        }
        if (existingArgs == null || existingArgs.trim().isEmpty()) {
            return String.join(" ", recommendedArgs);
        }

        String current = existingArgs.trim();

        // 清理过时与冲突参数（如旧版 ZGC、ParallelGC、SerialGC、ShenandoahGC、CMS、已废弃的 -Xverify:none、-noverify、分层编译下失效的 CompileThreshold）
        if (recommendedArgs.contains("-XX:+UseG1GC")) {
            current = current.replaceAll("-XX:[+-]Use(ZGC|ParallelGC|SerialGC|ShenandoahGC|ConcMarkSweepGC)\\b", "")
                             .replaceAll("-XX:[+-]ZGenerational\\b", "")
                             .replaceAll("-XX:[+-]UseG1GC\\b", "");
        }
        current = current.replaceAll("-Xverify:none\\b", "")
                         .replaceAll("-noverify\\b", "")
                         .replaceAll("-XX:CompileThreshold=\\S+", "");

        // 提取推荐参数中的 -Xmx 与 -Xms
        String recXmx = null;
        String recXms = null;
        for (String arg : recommendedArgs) {
            if (arg.startsWith("-Xmx")) {
                recXmx = arg;
            } else if (arg.startsWith("-Xms")) {
                recXms = arg;
            }
        }

        Pattern xmxPattern = Pattern.compile("(-Xmx[0-9]+[mMkKgG]?)");
        if (recXmx != null) {
            Matcher m = xmxPattern.matcher(current);
            if (m.find()) {
                current = m.replaceAll(recXmx);
            } else {
                current = current + " " + recXmx;
            }
        }

        Pattern xmsPattern = Pattern.compile("(-Xms[0-9]+[mMkKgG]?)");
        if (recXms != null) {
            Matcher m = xmsPattern.matcher(current);
            if (m.find()) {
                current = m.replaceAll(recXms);
            } else {
                current = current + " " + recXms;
            }
        }

        // 其余参数按前缀排重追加
        for (String arg : recommendedArgs) {
            if (arg.startsWith("-Xmx") || arg.startsWith("-Xms")) {
                continue;
            }
            String prefix = getArgPrefix(arg);
            if (!hasArgWithPrefix(current, prefix)) {
                current = current + " " + arg;
            }
        }

        return current.trim().replaceAll("\\s+", " ");
    }

    private static String getArgPrefix(String arg) {
        if (arg.contains("=")) {
            return arg.substring(0, arg.indexOf('=') + 1);
        }
        if (arg.startsWith("-Xverify:")) {
            return "-Xverify:";
        }
        if (arg.startsWith("-XX:+") || arg.startsWith("-XX:-")) {
            return arg.substring(5);
        }
        return arg;
    }

    private static boolean hasArgWithPrefix(String currentArgs, String prefix) {
        if (currentArgs == null || currentArgs.isEmpty()) {
            return false;
        }
        String[] tokens = currentArgs.split("\\s+");
        for (String token : tokens) {
            if (token.startsWith(prefix) || token.equals(prefix)) {
                return true;
            }
            if ((token.startsWith("-XX:+") || token.startsWith("-XX:-")) && token.substring(5).equals(prefix)) {
                return true;
            }
        }
        return false;
    }
}
