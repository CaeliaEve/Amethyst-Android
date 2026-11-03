package net.kdt.pojavlaunch.updater;

import org.json.JSONException;
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

        // 4. Current version is unknown -> missing should be all
        List<MCPatchUpdater.VersionIndex> missing4 = MCPatchUpdater.calculateMissingVersions(versions, "");
        Assert.assertEquals(4, missing4.size());
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
        String yml = "urls:\n  - bWNwYXRjaDovL3NoZ3Quc3RvcmU6NjcwMA== # comment\nversion-file-path: version-label.txt\n";
        MCPatchUpdater.parseYamlConfig(yml, ctx);

        Assert.assertEquals("shgt.store", ctx.serverHost);
        Assert.assertEquals(6700, ctx.serverPort);
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
