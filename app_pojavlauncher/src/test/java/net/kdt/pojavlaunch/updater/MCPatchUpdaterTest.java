package net.kdt.pojavlaunch.updater;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MCPatchUpdaterTest {

    @Test
    public void testLongToBytesLEAndReadLongLE() throws IOException {
        long[] testValues = {0L, 1L, -1L, -2L, 665088L, 34623488L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long val : testValues) {
            byte[] bytes = MCPatchUpdater.longToBytesLE(val);
            Assert.assertEquals(8, bytes.length);

            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            long decoded = MCPatchUpdater.readLongLE(bais);
            Assert.assertEquals(val, decoded);
        }
    }

    @Test
    public void testCalculateMissingVersions() {
        List<MCPatchUpdater.VersionIndex> versions = new ArrayList<>();
        versions.add(new MCPatchUpdater.VersionIndex("2.1", "2.1.tar", 100, 20, "hash1"));
        versions.add(new MCPatchUpdater.VersionIndex("2.1.1", "2.1.1.tar", 200, 20, "hash2"));
        versions.add(new MCPatchUpdater.VersionIndex("2.2.0", "2.2.0.tar", 300, 20, "hash3"));
        versions.add(new MCPatchUpdater.VersionIndex("2.2.1", "2.2.1.tar", 400, 20, "hash4"));

        // 1. Current version is 2.2.0 -> missing should be [2.2.1]
        List<MCPatchUpdater.VersionIndex> missing1 = MCPatchUpdater.calculateMissingVersions(versions, "2.2.0");
        Assert.assertEquals(1, missing1.size());
        Assert.assertEquals("2.2.1", missing1.get(0).label);

        // 2. Current version is 2.2.1 -> missing should be empty
        List<MCPatchUpdater.VersionIndex> missing2 = MCPatchUpdater.calculateMissingVersions(versions, "2.2.1");
        Assert.assertTrue(missing2.isEmpty());

        // 3. Current version is 2.1 -> missing should be [2.1.1, 2.2.0, 2.2.1]
        List<MCPatchUpdater.VersionIndex> missing3 = MCPatchUpdater.calculateMissingVersions(versions, "2.1");
        Assert.assertEquals(3, missing3.size());
        Assert.assertEquals("2.1.1", missing3.get(0).label);
        Assert.assertEquals("2.2.0", missing3.get(1).label);
        Assert.assertEquals("2.2.1", missing3.get(2).label);

        // 4. Current version is empty/null -> missing should be all (fresh install)
        List<MCPatchUpdater.VersionIndex> missing4 = MCPatchUpdater.calculateMissingVersions(versions, "");
        Assert.assertEquals(4, missing4.size());

        List<MCPatchUpdater.VersionIndex> missingNull = MCPatchUpdater.calculateMissingVersions(versions, null);
        Assert.assertEquals(4, missingNull.size());

        // 5. Unknown version string -> must throw IllegalStateException to guard against corruption
        try {
            MCPatchUpdater.calculateMissingVersions(versions, "unknown-custom-version");
            Assert.fail("Expected IllegalStateException for unknown version");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("unknown-custom-version"));
        }
    }

    @Test
    public void testBuildUpdatePlanLifecycleAndDeduplication() throws Exception {
        // Construct simulated multi-version progression matching real server:
        // Version 2.1.1 adds A
        // Version 2.1.2 adds B
        // Version 2.1.4 deletes B and adds C
        // Version 2.2.0 deletes A and adds D
        // Version 2.2.1 adds E
        List<MCPatchUpdater.VersionIndex> missing = new ArrayList<>();
        missing.add(new MCPatchUpdater.VersionIndex("2.1.1", "2.1.1.tar", 0, 100, ""));
        missing.add(new MCPatchUpdater.VersionIndex("2.1.2", "2.1.2.tar", 0, 100, ""));
        missing.add(new MCPatchUpdater.VersionIndex("2.1.4", "2.1.4.tar", 0, 100, ""));
        missing.add(new MCPatchUpdater.VersionIndex("2.2.0", "2.2.0.tar", 0, 100, ""));
        missing.add(new MCPatchUpdater.VersionIndex("2.2.1", "2.2.1.tar", 0, 100, ""));

        MCPatchUpdater.MetaFetcher mockFetcher = (filename, offset, len) -> {
            if ("2.1.1.tar".equals(filename)) {
                return "[{\"label\":\"2.1.1\",\"logs\":\"Update 2.1.1\",\"changes\":[{\"operation\":\"update-file\",\"path\":\"mods/modA-v1.jar\",\"len\":1000}]}]";
            } else if ("2.1.2.tar".equals(filename)) {
                return "[{\"label\":\"2.1.2\",\"logs\":\"Update 2.1.2\",\"changes\":[{\"operation\":\"update-file\",\"path\":\"mods/modB-v1.jar\",\"len\":2000}]}]";
            } else if ("2.1.4.tar".equals(filename)) {
                return "[{\"label\":\"2.1.4\",\"logs\":\"Update 2.1.4\",\"changes\":[{\"operation\":\"delete-file\",\"path\":\"mods/modB-v1.jar\"},{\"operation\":\"update-file\",\"path\":\"mods/modC-v1.jar\",\"len\":3000}]}]";
            } else if ("2.2.0.tar".equals(filename)) {
                return "[{\"label\":\"2.2.0\",\"logs\":\"Update 2.2.0\",\"changes\":[{\"operation\":\"delete-file\",\"path\":\"mods/modA-v1.jar\"},{\"operation\":\"update-file\",\"path\":\"mods/modD-v1.jar\",\"len\":4000}]}]";
            } else if ("2.2.1.tar".equals(filename)) {
                return "[{\"label\":\"2.2.1\",\"logs\":\"Update 2.2.1\",\"changes\":[{\"operation\":\"update-file\",\"path\":\"mods/modE-v1.jar\",\"len\":5000}]}]";
            }
            return "[]";
        };

        MCPatchUpdater.UpdatePlan plan = MCPatchUpdater.buildUpdatePlan(missing, mockFetcher);

        // CRITICAL VERIFICATION:
        // modA-v1 and modB-v1 were deleted by later versions.
        // Therefore, neither modA-v1 nor modB-v1 should be in updateFiles!
        Assert.assertEquals(3, plan.updateFiles.size());
        List<String> paths = new ArrayList<>();
        for (MCPatchUpdater.UpdateFileOp op : plan.updateFiles) {
            paths.add(op.path);
        }
        Assert.assertTrue(paths.contains("mods/modC-v1.jar"));
        Assert.assertTrue(paths.contains("mods/modD-v1.jar"));
        Assert.assertTrue(paths.contains("mods/modE-v1.jar"));
        Assert.assertFalse(paths.contains("mods/modA-v1.jar"));
        Assert.assertFalse(paths.contains("mods/modB-v1.jar"));

        // Download size should only sum the kept updates: 3000 + 4000 + 5000 = 12000
        Assert.assertEquals(12000L, plan.totalDownloadBytes);

        // Changelogs should have newest on top
        String logs = plan.changelogs.toString();
        int idx221 = logs.indexOf("[2.2.1]");
        int idx211 = logs.indexOf("[2.1.1]");
        Assert.assertTrue(idx221 >= 0 && idx211 >= 0 && idx221 < idx211);
    }

    @Test
    public void testCrcCalculation() {
        MCPatchUpdater.Crc64_XZ crc64 = new MCPatchUpdater.Crc64_XZ();
        MCPatchUpdater.Crc16_IBM_SDLC crc16 = new MCPatchUpdater.Crc16_IBM_SDLC();

        byte[] testData = "Hello MCPatch!".getBytes(StandardCharsets.UTF_8);
        crc64.update(testData, 0, testData.length);
        crc16.update(testData, 0, testData.length);

        long c64 = crc64.getValue();
        int c16 = crc16.getValue();

        Assert.assertNotEquals(0, c64);
        Assert.assertNotEquals(0, c16);

        String hash = String.format("%016x_%04x", c64, c16);
        Assert.assertEquals(21, hash.length());
        Assert.assertEquals('_', hash.charAt(16));
    }

    @Test
    public void testParseYamlConfigBase64() {
        MCPatchUpdater.UpdateContext ctx = new MCPatchUpdater.UpdateContext();
        String yml = "urls:\n  - bWNwYXRjaDovL3NoZ3Quc3RvcmU6NjcwMA== # comment\nversion-file-path: version-label.txt\nprivate-timeout: 15000\n";
        MCPatchUpdater.parseYamlConfig(yml, ctx);

        Assert.assertEquals("shgt.store", ctx.serverHost);
        Assert.assertEquals(6700, ctx.serverPort);
        Assert.assertEquals(15000, ctx.timeoutMs);
    }

    @Test
    public void testParseYamlConfigRaw() {
        MCPatchUpdater.UpdateContext ctx = new MCPatchUpdater.UpdateContext();
        String yml = "urls:\n  - mcpatch://127.0.0.1:8888\n";
        MCPatchUpdater.parseYamlConfig(yml, ctx);

        Assert.assertEquals("127.0.0.1", ctx.serverHost);
        Assert.assertEquals(8888, ctx.serverPort);
    }

    @Test
    public void testResolveTargetPath() {
        MCPatchUpdater.UpdateContext ctx = new MCPatchUpdater.UpdateContext();
        ctx.dotMinecraftDir = new File("/sdcard/games/Amethyst/.minecraft");
        ctx.activeVersionDirName = "GT New Horizons 2.9.0-lwjgl3ify";

        // Path from server metadata
        String serverPath = ".minecraft/versions/GT New Horizons 2.9.0/mods/shgtcore.jar";
        File resolved = MCPatchUpdater.resolveTargetPath(ctx, serverPath);

        // Should be mapped into the active version directory
        String expected = new File("/sdcard/games/Amethyst/.minecraft/versions/GT New Horizons 2.9.0-lwjgl3ify/mods/shgtcore.jar").getAbsolutePath();
        Assert.assertEquals(expected, resolved.getAbsolutePath());
    }
}
