package net.kdt.pojavlaunch.modloaders;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class GTNHProfileHelperTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testIsGTNHInstance() throws IOException {
        File gameDir = tempFolder.newFolder("gameDir");
        File modsDir = new File(gameDir, "mods");
        modsDir.mkdirs();

        Assert.assertFalse(GTNHProfileHelper.isGTNHInstance(gameDir));

        new File(modsDir, "lwjgl3ify-3.0.30.jar").createNewFile();
        Assert.assertFalse(GTNHProfileHelper.isGTNHInstance(gameDir));

        new File(modsDir, "GT5-Unofficial-5.09.43.jar").createNewFile();
        Assert.assertTrue(GTNHProfileHelper.isGTNHInstance(gameDir));
    }

    @Test
    public void testIsGTNHInstanceWithOtherSignatures() throws IOException {
        File gameDir1 = tempFolder.newFolder("gameDir1");
        File mods1 = new File(gameDir1, "mods");
        mods1.mkdirs();
        new File(mods1, "lwjgl3ify-forgePatches.jar").createNewFile();
        new File(mods1, "NewHorizonsCoreMod-2.0.0.jar").createNewFile();
        Assert.assertTrue(GTNHProfileHelper.isGTNHInstance(gameDir1));

        File gameDir2 = tempFolder.newFolder("gameDir2");
        File mods2 = new File(gameDir2, "mods");
        mods2.mkdirs();
        new File(mods2, "lwjgl3ify-1.5.8.jar").createNewFile();
        new File(mods2, "DreamAssembler-1.0.jar").createNewFile();
        Assert.assertTrue(GTNHProfileHelper.isGTNHInstance(gameDir2));

        Assert.assertFalse(GTNHProfileHelper.isGTNHInstance(null));
        Assert.assertFalse(GTNHProfileHelper.isGTNHInstance(new File("non_existent_folder_xyz")));
    }

    @Test
    public void testSanitizeLwjgl3ifyConfig() throws IOException {
        File gameDir = tempFolder.newFolder("gtnhGame");
        File configDir = new File(gameDir, "config");
        configDir.mkdirs();
        File cfg = new File(configDir, "lwjgl3ify.cfg");

        String badConfig = "openglcontext {\n    B:sharedContext=true\n    B:hidpiRendering=false\n}\n";
        try (FileWriter writer = new FileWriter(cfg)) {
            writer.write(badConfig);
        }

        GTNHProfileHelper.sanitizeLwjgl3ifyConfig(gameDir);

        String result = new String(Files.readAllBytes(cfg.toPath()));
        Assert.assertTrue(result.contains("B:sharedContext=false"));
        Assert.assertTrue(result.contains("B:hidpiRendering=true"));
        Assert.assertTrue(result.contains("B:linuxCreateAppDesktopEntry=false"));
    }

    @Test
    public void testSanitizeBetterLoadingScreenConfig() throws IOException {
        File gameDir = tempFolder.newFolder("gtnhBLSGame");
        File cfgDir = new File(gameDir, "config/Betterloadingscreen");
        cfgDir.mkdirs();
        File cfg = new File(cfgDir, "betterloadingscreen.cfg");

        String badConfig = "general {\n    B:threadedRendering=true\n}\n";
        try (FileWriter writer = new FileWriter(cfg)) {
            writer.write(badConfig);
        }

        GTNHProfileHelper.sanitizeBetterLoadingScreenConfig(gameDir);

        String result = new String(Files.readAllBytes(cfg.toPath()));
        Assert.assertTrue(result.contains("B:threadedRendering=false"));
    }

    @Test
    public void testSanitizeBetterLoadingScreenConfigCreatesIfNotExists() throws IOException {
        File gameDir = tempFolder.newFolder("gtnhBLSNewGame");
        File cfg = new File(gameDir, "config/Betterloadingscreen/betterloadingscreen.cfg");
        Assert.assertFalse(cfg.exists());

        GTNHProfileHelper.sanitizeBetterLoadingScreenConfig(gameDir);

        Assert.assertTrue(cfg.exists());
        String result = new String(Files.readAllBytes(cfg.toPath()));
        Assert.assertTrue(result.contains("B:threadedRendering=false"));
    }

    @Test
    public void testSanitizeLwjgl3ifyConfigCreatesIfNotExists() throws IOException {
        File gameDir = tempFolder.newFolder("gtnhNewGame");
        File cfg = new File(gameDir, "config/lwjgl3ify.cfg");
        Assert.assertFalse(cfg.exists());

        GTNHProfileHelper.sanitizeLwjgl3ifyConfig(gameDir);

        Assert.assertTrue(cfg.exists());
        String result = new String(Files.readAllBytes(cfg.toPath()));
        Assert.assertTrue(result.contains("B:sharedContext=false"));
        Assert.assertTrue(result.contains("B:hidpiRendering=true"));
    }

    @Test
    public void testSanitizeLwjgl3ifyConfigPartialMissing() throws IOException {
        File gameDir = tempFolder.newFolder("gtnhPartialGame");
        File configDir = new File(gameDir, "config");
        configDir.mkdirs();
        File cfg = new File(configDir, "lwjgl3ify.cfg");

        // Only has openglcontext with sharedContext=true, but lacks hidpiRendering entirely
        String partialConfig = "openglcontext {\n    B:sharedContext=true\n}\n";
        try (FileWriter writer = new FileWriter(cfg)) {
            writer.write(partialConfig);
        }

        GTNHProfileHelper.sanitizeLwjgl3ifyConfig(gameDir);

        String result = new String(Files.readAllBytes(cfg.toPath()));
        Assert.assertTrue(result.contains("B:sharedContext=false"));
        Assert.assertTrue(result.contains("B:hidpiRendering=true"));
    }

    @Test
    public void testSanitizeFpsReducerConfig() throws IOException {
        File gameDir = tempFolder.newFolder("gtnhFpsReducerGame");
        File cfgDir = new File(gameDir, "config/FpsReducer");
        cfgDir.mkdirs();
        File cfg = new File(cfgDir, "FpsReducer.cfg");

        String badConfig = "common {\n    B:enableFpsReducer=true\n    I:idleFPS=10\n}\n";
        try (FileWriter writer = new FileWriter(cfg)) {
            writer.write(badConfig);
        }

        GTNHProfileHelper.sanitizeFpsReducerConfig(gameDir);

        String result = new String(Files.readAllBytes(cfg.toPath()));
        Assert.assertTrue(result.contains("B:enableFpsReducer=false"));
        Assert.assertFalse(result.contains("B:enableFpsReducer=true"));
    }

    @Test
    public void testSanitizeFpsReducerConfigCreatesIfNotExists() throws IOException {
        File gameDir = tempFolder.newFolder("gtnhFpsReducerNewGame");
        File cfg = new File(gameDir, "config/FpsReducer/FpsReducer.cfg");
        Assert.assertFalse(cfg.exists());

        GTNHProfileHelper.sanitizeFpsReducerConfig(gameDir);

        Assert.assertTrue(cfg.exists());
        String result = new String(Files.readAllBytes(cfg.toPath()));
        Assert.assertTrue(result.contains("B:enableFpsReducer=false"));
    }

    @Test
    public void testSanitizeFpsReducerDisablesModJar() throws IOException {
        File gameDir = tempFolder.newFolder("gtnhFpsReducerModGame");
        File modsDir = new File(gameDir, "mods");
        modsDir.mkdirs();
        File modJar = new File(modsDir, "[FPS减速器]FpsReducer-mc1.7.10-1.10.3.jar");
        modJar.createNewFile();
        Assert.assertTrue(modJar.exists());

        GTNHProfileHelper.sanitizeFpsReducerConfig(gameDir);

        Assert.assertFalse(modJar.exists());
        File disabledMod = new File(modsDir, "[FPS减速器]FpsReducer-mc1.7.10-1.10.3.jar.disabled");
        Assert.assertTrue(disabledMod.exists());
    }

    @Test
    public void testGetRecommendedJvmArgs() {
        List<String> args = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        Assert.assertTrue(args.contains("-Dfile.encoding=UTF-8"));
        Assert.assertTrue(args.contains("-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader"));
        Assert.assertTrue(args.contains("--enable-native-access=ALL-UNNAMED"));
        Assert.assertTrue(args.contains("-Xms4096M"));
        Assert.assertTrue(args.contains("-Xmx6144M"));
        Assert.assertTrue(args.contains("-XX:+UnlockExperimentalVMOptions"));
        Assert.assertTrue(args.contains("-XX:+UseCompactObjectHeaders"));
        Assert.assertTrue(args.contains("-XX:+UseZGC"));
        Assert.assertTrue(args.contains("-XX:+ZGenerational"));
        Assert.assertTrue(args.contains("-XX:+TieredCompilation"));
        Assert.assertTrue(args.contains("-XX:CICompilerCount=4"));
        Assert.assertTrue(args.contains("-XX:CompileThreshold=1200"));
        Assert.assertTrue(args.contains("-Xverify:none"));

        // Verify G1GC options are not present
        Assert.assertFalse(args.contains("-XX:+UseG1GC"));
        Assert.assertFalse(args.contains("-XX:MaxGCPauseMillis=20"));
        Assert.assertFalse(args.contains("-XX:Tier4InvocationThreshold=4000"));
    }

    @Test
    public void testGetRecommendedJvmArgsEdgeCases() {
        // Test memory bounds
        List<String> argsLow = GTNHProfileHelper.getRecommendedJvmArgs(2048);
        Assert.assertTrue(argsLow.contains("-Xms2048M"));
        Assert.assertTrue(argsLow.contains("-Xmx2048M"));

        List<String> argsMid = GTNHProfileHelper.getRecommendedJvmArgs(4096);
        Assert.assertTrue(argsMid.contains("-Xms2048M"));
        Assert.assertTrue(argsMid.contains("-Xmx4096M"));

        List<String> argsHigh = GTNHProfileHelper.getRecommendedJvmArgs(8192);
        Assert.assertTrue(argsHigh.contains("-Xms6144M"));
        Assert.assertTrue(argsHigh.contains("-Xmx8192M"));
    }

    @Test
    public void testMergeJvmArgsWithEmptyExisting() {
        List<String> recArgs = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        String merged = GTNHProfileHelper.mergeJvmArgs("", recArgs);
        for (String arg : recArgs) {
            Assert.assertTrue("Missing arg: " + arg, merged.contains(arg));
        }

        String mergedNull = GTNHProfileHelper.mergeJvmArgs(null, recArgs);
        for (String arg : recArgs) {
            Assert.assertTrue("Missing arg: " + arg, mergedNull.contains(arg));
        }
    }

    @Test
    public void testMergeJvmArgsReplacesXmxAndXms() {
        String existing = "-Xms1024M -Xmx2048M -Dcustom.flag=hello -XX:+UseG1GC -Xverify:none";
        List<String> recArgs = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        String merged = GTNHProfileHelper.mergeJvmArgs(existing, recArgs);

        Assert.assertTrue(merged.contains("-Xms4096M"));
        Assert.assertTrue(merged.contains("-Xmx6144M"));
        Assert.assertFalse(merged.contains("-Xms1024M"));
        Assert.assertFalse(merged.contains("-Xmx2048M"));
        Assert.assertTrue(merged.contains("-Dcustom.flag=hello"));
        Assert.assertTrue(merged.contains("-XX:+UseCompactObjectHeaders"));
        Assert.assertTrue(merged.contains("-XX:+UseZGC"));
        Assert.assertTrue(merged.contains("-XX:+ZGenerational"));
        Assert.assertFalse(merged.contains("-XX:+UseG1GC"));
        Assert.assertTrue(merged.contains("-Xverify:none"));
    }

    @Test
    public void testMergeJvmArgsPrefixDeduplication() {
        String existing = "-Dfile.encoding=GBK -XX:CICompilerCount=8 -XX:+UseZGC";
        List<String> recArgs = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        String merged = GTNHProfileHelper.mergeJvmArgs(existing, recArgs);

        // User's custom encoding and compiler count should be preserved, not overwritten or duplicated
        Assert.assertTrue(merged.contains("-Dfile.encoding=GBK"));
        Assert.assertFalse(merged.contains("-Dfile.encoding=UTF-8"));
        Assert.assertTrue(merged.contains("-XX:CICompilerCount=8"));
        Assert.assertFalse(merged.contains("-XX:CICompilerCount=4"));

        // -XX:+UseZGC should only appear once
        int firstIndex = merged.indexOf("-XX:+UseZGC");
        int lastIndex = merged.lastIndexOf("-XX:+UseZGC");
        Assert.assertEquals("Duplicate -XX:+UseZGC detected", firstIndex, lastIndex);

        // New flags should still be added
        Assert.assertTrue(merged.contains("-XX:+UseCompactObjectHeaders"));
        Assert.assertTrue(merged.contains("-XX:+ZGenerational"));
        Assert.assertTrue(merged.contains("-Xms4096M"));
        Assert.assertTrue(merged.contains("-Xmx6144M"));
        Assert.assertTrue(merged.contains("-XX:CompileThreshold=1200"));
        Assert.assertTrue(merged.contains("-Xverify:none"));
    }

    @Test
    public void testMergeJvmArgsCleansConflictingGcsAndNoverify() {
        String existing = "-XX:+UseParallelGC -XX:+UseSerialGC -XX:+UseShenandoahGC -XX:+UseG1GC -XX:-UseZGC -XX:MaxGCPauseMillis=20 -XX:Tier4InvocationThreshold=4000 -XX:G1ReservePercent=15 -XX:InitiatingHeapOccupancyPercent=45";
        List<String> recArgs = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        String merged = GTNHProfileHelper.mergeJvmArgs(existing, recArgs);

        Assert.assertFalse(merged.contains("-XX:+UseParallelGC"));
        Assert.assertFalse(merged.contains("-XX:+UseSerialGC"));
        Assert.assertFalse(merged.contains("-XX:+UseShenandoahGC"));
        Assert.assertFalse(merged.contains("-XX:+UseG1GC"));
        Assert.assertFalse(merged.contains("-XX:-UseZGC"));
        Assert.assertFalse(merged.contains("-XX:MaxGCPauseMillis=20"));
        Assert.assertFalse(merged.contains("-XX:Tier4InvocationThreshold=4000"));
        Assert.assertFalse(merged.contains("-XX:G1ReservePercent=15"));
        Assert.assertFalse(merged.contains("-XX:InitiatingHeapOccupancyPercent=45"));
        Assert.assertTrue(merged.contains("-XX:+UseZGC"));
        Assert.assertTrue(merged.contains("-XX:+ZGenerational"));

        int firstIndex = merged.indexOf("-XX:+UseZGC");
        int lastIndex = merged.lastIndexOf("-XX:+UseZGC");
        Assert.assertEquals("Single -XX:+UseZGC required", firstIndex, lastIndex);
    }
}
