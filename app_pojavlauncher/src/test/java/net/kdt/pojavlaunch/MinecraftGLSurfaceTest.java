package net.kdt.pojavlaunch;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftGLSurfaceTest {
    @Test
    public void defaultRendererStillProtectsLwjgl3ifyFromSurfaceDestruction() {
        assertFalse(MinecraftGLSurface.shouldUseSurfaceView(true, null, false, true));
    }

    @Test
    public void defaultRendererStillProtectsAngelicaFromSurfaceDestruction() {
        assertFalse(MinecraftGLSurface.shouldUseSurfaceView(true, null, true, false));
    }

    @Test
    public void explicitMobileGluesPreservesTheSameModCompatibilityRules() {
        assertFalse(MinecraftGLSurface.shouldUseSurfaceView(true, "opengles_mobileglues", false, true));
        assertFalse(MinecraftGLSurface.shouldUseSurfaceView(true, "opengles_mobileglues", true, false));
    }

    @Test
    public void kopperUsesTextureViewForOrientationCompatibility() {
        assertFalse(MinecraftGLSurface.shouldUseSurfaceView(
                true, "opengles3_desktopgl_zink_kopper", false, false));
    }

    @Test
    public void compatibleGamesKeepTheRequestedSurfaceView() {
        assertTrue(MinecraftGLSurface.shouldUseSurfaceView(true, null, false, false));
        assertTrue(MinecraftGLSurface.shouldUseSurfaceView(true, "opengles_mobileglues", false, false));
    }

    @Test
    public void textureViewPreferenceIsAlwaysPreserved() {
        assertFalse(MinecraftGLSurface.shouldUseSurfaceView(false, null, false, false));
        assertFalse(MinecraftGLSurface.shouldUseSurfaceView(false, "opengles_mobileglues", false, false));
    }
}
