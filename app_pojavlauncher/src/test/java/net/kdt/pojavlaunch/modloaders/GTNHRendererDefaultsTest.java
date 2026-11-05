package net.kdt.pojavlaunch.modloaders;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

public class GTNHRendererDefaultsTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void legacyO1ProfileEnablesAngleWithoutChangingOtherSettings() throws IOException {
        MinecraftProfile profile = GSON.fromJson("{\"name\":\"GTNH\",\"useANGLE\":false,"
                + "\"javaDir\":\"amethyst://External-25\",\"javaArgs\":\"-Xmx5832M -XX:+UseG1GC\","
                + "\"lastVersionId\":\"1.7.10-LWJGL3ify-3.0.42\"}", MinecraftProfile.class);

        assertTrue(GTNHProfileHelper.applyXringO1RendererDefaults(
                profile, gtnhInstance(), "XRing", "O1"));

        assertTrue(profile.useANGLE);
        assertTrue(profile.anglePreferenceSet);
        assertNull(profile.pojavRendererName);
        assertEquals("GTNH", profile.name);
        assertEquals("amethyst://External-25", profile.javaDir);
        assertEquals("-Xmx5832M -XX:+UseG1GC", profile.javaArgs);
        assertEquals("1.7.10-LWJGL3ify-3.0.42", profile.lastVersionId);
    }

    @Test
    public void explicitlySelectedMobileGluesAlsoGetsTheDefault() throws IOException {
        MinecraftProfile profile = new MinecraftProfile();
        profile.pojavRendererName = "opengles_mobileglues";

        assertTrue(GTNHProfileHelper.applyXringO1RendererDefaults(
                profile, gtnhInstance(), "XRing", "O1"));
        assertTrue(profile.useANGLE);
        assertEquals("opengles_mobileglues", profile.pojavRendererName);
    }

    @Test
    public void rendererDefaultDoesNotDependOnAnInstalledJava25() throws IOException {
        MinecraftProfile profile = new MinecraftProfile();
        profile.javaDir = "amethyst://Internal-17";

        assertTrue(GTNHProfileHelper.applyXringO1RendererDefaults(
                profile, gtnhInstance(), "XRing", "O1"));
        assertTrue(profile.useANGLE);
        assertEquals("amethyst://Internal-17", profile.javaDir);
        assertNull(profile.javaArgs);
    }

    @Test
    public void unrelatedOrUnknownHardwareKeepsItsSettings() throws IOException {
        File gameDir = gtnhInstance();
        String[][] devices = {{"Qualcomm", "SM8550"}, {"MediaTek", "O1"},
                {"XRing", "O2"}, {"unknown", "unknown"}, {null, "O1"}, {"XRing", null}};
        for (String[] device : devices) {
            MinecraftProfile profile = new MinecraftProfile();
            assertFalse(GTNHProfileHelper.applyXringO1RendererDefaults(
                    profile, gameDir, device[0], device[1]));
            assertFalse(profile.useANGLE);
            assertFalse(profile.anglePreferenceSet);
        }
    }

    @Test
    public void anotherRendererIsNeverOverridden() throws IOException {
        File gameDir = gtnhInstance();
        for (String renderer : new String[]{"opengles2", "opengles3_ltw",
                "opengles3_desktopgl_zink_kopper", "opengles_system_gles"}) {
            MinecraftProfile profile = new MinecraftProfile();
            profile.pojavRendererName = renderer;
            assertFalse(GTNHProfileHelper.applyXringO1RendererDefaults(
                    profile, gameDir, "XRing", "O1"));
            assertEquals(renderer, profile.pojavRendererName);
            assertFalse(profile.useANGLE);
            assertFalse(profile.anglePreferenceSet);
        }
    }

    @Test
    public void nonGtnhInstanceDoesNotConsumeTheMigration() throws IOException {
        MinecraftProfile profile = new MinecraftProfile();
        assertFalse(GTNHProfileHelper.applyXringO1RendererDefaults(
                profile, tempFolder.newFolder(), "XRing", "O1"));
        assertFalse(profile.useANGLE);
        assertFalse(profile.anglePreferenceSet);

        assertTrue(GTNHProfileHelper.applyXringO1RendererDefaults(
                profile, gtnhInstance(), "XRing", "O1"));
        assertTrue(profile.useANGLE);
    }

    @Test
    public void alreadyEnabledAngleIsPreservedAndMigratedOnlyOnce() throws IOException {
        MinecraftProfile profile = GSON.fromJson("{\"useANGLE\":true}", MinecraftProfile.class);
        File gameDir = gtnhInstance();
        assertTrue(GTNHProfileHelper.applyXringO1RendererDefaults(profile, gameDir, "XRing", "O1"));
        assertTrue(profile.useANGLE);

        MinecraftProfile reloaded = roundTrip(profile);
        assertTrue(reloaded.anglePreferenceSet);
        assertFalse(GTNHProfileHelper.applyXringO1RendererDefaults(reloaded, gameDir, "XRing", "O1"));
        assertTrue(reloaded.useANGLE);
    }

    @Test
    public void manualOptOutBeforeFirstLaunchSurvivesSaving() throws IOException {
        MinecraftProfile profile = new MinecraftProfile();
        profile.setAnglePreference(false);
        MinecraftProfile reloaded = roundTrip(profile);

        assertFalse(GTNHProfileHelper.applyXringO1RendererDefaults(
                reloaded, gtnhInstance(), "XRing", "O1"));
        assertFalse(reloaded.useANGLE);
        assertTrue(reloaded.anglePreferenceSet);
    }

    @Test
    public void manualOptOutAfterMigrationSurvivesProfileEditingAndRelaunch() throws IOException {
        File gameDir = gtnhInstance();
        MinecraftProfile profile = new MinecraftProfile();
        assertTrue(GTNHProfileHelper.applyXringO1RendererDefaults(profile, gameDir, "XRing", "O1"));

        MinecraftProfile edited = new MinecraftProfile(roundTrip(profile));
        edited.setAnglePreference(false);
        MinecraftProfile reloaded = roundTrip(new MinecraftProfile(edited));

        assertFalse(GTNHProfileHelper.applyXringO1RendererDefaults(reloaded, gameDir, "XRing", "O1"));
        assertFalse(reloaded.useANGLE);
        assertTrue(reloaded.anglePreferenceSet);
    }

    @Test
    public void editingAnotherFieldDoesNotLoseTheSavedOptOut() throws IOException {
        MinecraftProfile saved = GSON.fromJson(
                "{\"useANGLE\":false,\"anglePreferenceSet\":true,\"name\":\"GTNH\"}",
                MinecraftProfile.class);
        MinecraftProfile edited = new MinecraftProfile(saved);
        edited.name = "Renamed GTNH";
        MinecraftProfile reloaded = roundTrip(edited);

        assertFalse(GTNHProfileHelper.applyXringO1RendererDefaults(
                reloaded, gtnhInstance(), "XRing", "O1"));
        assertFalse(reloaded.useANGLE);
        assertTrue(reloaded.anglePreferenceSet);
        assertEquals("Renamed GTNH", reloaded.name);
    }

    private MinecraftProfile roundTrip(MinecraftProfile profile) {
        return GSON.fromJson(GSON.toJson(profile), MinecraftProfile.class);
    }

    private File gtnhInstance() throws IOException {
        File gameDir = tempFolder.newFolder();
        File mods = new File(gameDir, "mods");
        assertTrue(mods.mkdir());
        assertTrue(new File(mods, "lwjgl3ify-3.0.42.jar").createNewFile());
        assertTrue(new File(mods, "GT5-Unofficial-5.09.52.jar").createNewFile());
        return gameDir;
    }
}
