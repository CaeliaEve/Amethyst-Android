package net.kdt.pojavlaunch.modloaders;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
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
        Assert.assertTrue(args.contains("-XX:+UnlockExperimentalVMOptions"));
        Assert.assertTrue(args.contains("-XX:+UseCompactObjectHeaders"));
        Assert.assertTrue(args.contains("-XX:+UseZGC"));
        Assert.assertTrue(args.contains("-XX:+ZGenerational"));
        Assert.assertTrue(args.contains("-Xms4096M"));
        Assert.assertTrue(args.contains("-Xmx6144M"));
        Assert.assertTrue(args.contains("-XX:+TieredCompilation"));
        Assert.assertTrue(args.contains("-XX:CICompilerCount=4"));
        Assert.assertTrue(args.contains("-XX:CompileThreshold=1200"));
        Assert.assertTrue(args.contains("-Xverify:none"));
    }

    @Test
    public void testGetRecommendedJvmArgsEdgeCases() {
        // Test memory bounds
        List<String> argsLow = GTNHProfileHelper.getRecommendedJvmArgs(2048);
        Assert.assertTrue(argsLow.contains("-Xms2048M"));
        Assert.assertTrue(argsLow.contains("-Xmx2048M"));

        List<String> argsHigh = GTNHProfileHelper.getRecommendedJvmArgs(8192);
        Assert.assertTrue(argsHigh.contains("-Xms6144M"));
        Assert.assertTrue(argsHigh.contains("-Xmx8192M"));
    }
}
