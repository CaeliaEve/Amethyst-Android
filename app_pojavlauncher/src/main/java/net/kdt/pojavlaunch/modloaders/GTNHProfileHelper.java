package net.kdt.pojavlaunch.modloaders;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GTNHProfileHelper {

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
            }
            return;
        }

        List<String> lines = new ArrayList<>();
        boolean foundShared = false;
        boolean foundHiDpi = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(cfg))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("B:sharedContext=")) {
                    lines.add("    B:sharedContext=false");
                    foundShared = true;
                } else if (line.trim().startsWith("B:hidpiRendering=")) {
                    lines.add("    B:hidpiRendering=true");
                    foundHiDpi = true;
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

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(cfg))) {
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

        // 清理过时与冲突参数（如旧版 ZGC、已废弃的 -Xverify:none、分层编译下失效的 CompileThreshold）
        if (recommendedArgs.contains("-XX:+UseG1GC")) {
            current = current.replace("-XX:+UseZGC", "")
                             .replace("-XX:+ZGenerational", "");
        }
        current = current.replace("-Xverify:none", "")
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
