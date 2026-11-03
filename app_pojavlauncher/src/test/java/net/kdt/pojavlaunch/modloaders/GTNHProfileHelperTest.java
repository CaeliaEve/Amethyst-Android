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
    public void testGetRecommendedJvmArgs() {
        List<String> args = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        Assert.assertTrue(args.contains("-Dfile.encoding=UTF-8"));
        Assert.assertTrue(args.contains("-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader"));
        Assert.assertTrue(args.contains("--enable-native-access=ALL-UNNAMED"));
        Assert.assertTrue(args.contains("-Xms4096M"));
        Assert.assertTrue(args.contains("-Xmx6144M"));
        Assert.assertTrue(args.contains("-XX:+UseG1GC"));
        Assert.assertTrue(args.contains("-XX:+UnlockExperimentalVMOptions"));
        Assert.assertTrue(args.contains("-XX:+UseCompactObjectHeaders"));
        Assert.assertTrue(args.contains("-XX:+ParallelRefProcEnabled"));
        Assert.assertTrue(args.contains("-XX:MaxGCPauseMillis=20"));
        Assert.assertTrue(args.contains("-XX:G1ReservePercent=15"));
        Assert.assertTrue(args.contains("-XX:InitiatingHeapOccupancyPercent=45"));
        Assert.assertTrue(args.contains("-XX:+TieredCompilation"));
        Assert.assertTrue(args.contains("-XX:+UseDynamicNumberOfCompilerThreads"));
        Assert.assertTrue(args.contains("-XX:CICompilerCount=6"));
        Assert.assertTrue(args.contains("-XX:Tier3InvocationThreshold=100"));
        Assert.assertTrue(args.contains("-XX:Tier3CompileThreshold=1000"));
        Assert.assertTrue(args.contains("-XX:Tier4InvocationThreshold=4000"));
        Assert.assertTrue(args.contains("-XX:+SegmentedCodeCache"));
        Assert.assertTrue(args.contains("-XX:ReservedCodeCacheSize=256M"));
        Assert.assertTrue(args.contains("-XX:+PerfDisableSharedMem"));
        Assert.assertTrue(args.contains("-Djava.util.zip.ZipFile.useShortEntries=true"));

        // Verify deprecated or conflicting options are removed
        Assert.assertFalse(args.contains("-XX:+UseZGC"));
        Assert.assertFalse(args.contains("-XX:+ZGenerational"));
        Assert.assertFalse(args.contains("-XX:CompileThreshold=1200"));
        Assert.assertFalse(args.contains("-Xverify:none"));
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
        String existing = "-Xms1024M -Xmx2048M -Dcustom.flag=hello -XX:+UseZGC -Xverify:none";
        List<String> recArgs = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        String merged = GTNHProfileHelper.mergeJvmArgs(existing, recArgs);

        Assert.assertTrue(merged.contains("-Xms4096M"));
        Assert.assertTrue(merged.contains("-Xmx6144M"));
        Assert.assertFalse(merged.contains("-Xms1024M"));
        Assert.assertFalse(merged.contains("-Xmx2048M"));
        Assert.assertTrue(merged.contains("-Dcustom.flag=hello"));
        Assert.assertTrue(merged.contains("-XX:+UseCompactObjectHeaders"));
        Assert.assertTrue(merged.contains("-XX:+UseG1GC"));
        Assert.assertFalse(merged.contains("-XX:+UseZGC"));
        Assert.assertFalse(merged.contains("-Xverify:none"));
    }

    @Test
    public void testMergeJvmArgsPrefixDeduplication() {
        String existing = "-Dfile.encoding=GBK -XX:CICompilerCount=8 -XX:+UseG1GC";
        List<String> recArgs = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        String merged = GTNHProfileHelper.mergeJvmArgs(existing, recArgs);

        // User's custom encoding and compiler count should be preserved, not overwritten or duplicated
        Assert.assertTrue(merged.contains("-Dfile.encoding=GBK"));
        Assert.assertFalse(merged.contains("-Dfile.encoding=UTF-8"));
        Assert.assertTrue(merged.contains("-XX:CICompilerCount=8"));
        Assert.assertFalse(merged.contains("-XX:CICompilerCount=6"));

        // -XX:+UseG1GC should only appear once
        int firstIndex = merged.indexOf("-XX:+UseG1GC");
        int lastIndex = merged.lastIndexOf("-XX:+UseG1GC");
        Assert.assertEquals("Duplicate -XX:+UseG1GC detected", firstIndex, lastIndex);

        // New flags should still be added
        Assert.assertTrue(merged.contains("-XX:+UseCompactObjectHeaders"));
        Assert.assertTrue(merged.contains("-Xms4096M"));
        Assert.assertTrue(merged.contains("-Xmx6144M"));
        Assert.assertTrue(merged.contains("-XX:+PerfDisableSharedMem"));
        Assert.assertTrue(merged.contains("-Djava.util.zip.ZipFile.useShortEntries=true"));
    }

    @Test
    public void testMergeJvmArgsCleansConflictingGcsAndNoverify() {
        String existing = "-XX:+UseParallelGC -XX:+UseSerialGC -XX:+UseShenandoahGC -XX:-UseG1GC -noverify -XX:CompileThreshold=5000";
        List<String> recArgs = GTNHProfileHelper.getRecommendedJvmArgs(6144);
        String merged = GTNHProfileHelper.mergeJvmArgs(existing, recArgs);

        Assert.assertFalse(merged.contains("-XX:+UseParallelGC"));
        Assert.assertFalse(merged.contains("-XX:+UseSerialGC"));
        Assert.assertFalse(merged.contains("-XX:+UseShenandoahGC"));
        Assert.assertFalse(merged.contains("-XX:-UseG1GC"));
        Assert.assertFalse(merged.contains("-noverify"));
        Assert.assertFalse(merged.contains("-XX:CompileThreshold"));
        Assert.assertTrue(merged.contains("-XX:+UseG1GC"));

        int firstIndex = merged.indexOf("-XX:+UseG1GC");
        int lastIndex = merged.lastIndexOf("-XX:+UseG1GC");
        Assert.assertEquals("Single -XX:+UseG1GC required", firstIndex, lastIndex);
    }
}
