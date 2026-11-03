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
        long xms = Math.min(maxMemoryMb, Math.max(3072, maxMemoryMb - 2048));
        return new ArrayList<>(Arrays.asList(
                "-Dfile.encoding=UTF-8",
                "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader",
                "--enable-native-access=ALL-UNNAMED",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseCompactObjectHeaders",
                "-XX:+UseZGC",
                "-XX:+ZGenerational",
                "-Xms" + xms + "M",
                "-Xmx" + maxMemoryMb + "M",
                "-XX:+TieredCompilation",
                "-XX:CICompilerCount=4",
                "-XX:CompileThreshold=1200",
                "-Xverify:none"
        ));
    }
}
