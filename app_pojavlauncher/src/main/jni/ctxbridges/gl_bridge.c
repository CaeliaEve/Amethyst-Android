#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

#define TAG __FILE_NAME__
#include <unistd.h>
#include <log.h>

//
// Created by maks on 17.09.2022.
//

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;
static volatile bool g_in_background = false;
static EGLSurface g_shared_pbuffer = EGL_NO_SURFACE;

bool gl_init() {
    if(!dlsym_EGL()) return false;
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGE("%s", "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        LOGE("eglInitialize_p() failed: %04x", eglGetError_p());
        return false;
    }
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if(currentBundle == NULL) goto zero;
    if((currentBundle->surface == g_shared_pbuffer || g_in_background) &&
       pojav_environ != NULL && pojav_environ->savedWidth > 0 && pojav_environ->savedHeight > 0) {
        *width = pojav_environ->savedWidth;
        *height = pojav_environ->savedHeight;
        return;
    }
    EGLSurface surface = currentBundle->surface;
    // Fetch dimensions from the EGL surface - the most reliable way
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if(!result_width || !result_height) goto zero;
    return;

    zero:
    // No idea what to do, but feeding gl4es incorrect or non-initialized dimensions may be
    // a bad idea. Set to zero in case of errors.
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    memset(bundle, 0, sizeof(gl_render_window_t));
    // Should fix old Angelica wanting no ES bit. It'll still get it in opengles_nothing :p
    int glBit = strncmp(getenv("AMETHYST_RENDERER"), "opengles3_desktopgl", 19) ?  EGL_OPENGL_ES3_BIT : EGL_OPENGL_BIT;
    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT|EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLint num_configs = 0;

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE) {
        LOGE("eglChooseConfig_p() failed: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    if (num_configs == 0) {
        LOGE("%s", "eglChooseConfig_p() found no matching config");
        free(bundle);
        return NULL;
    }

    // Get the first matching config
    eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        EGLBoolean bindResult;
        if (strncmp(getenv("AMETHYST_RENDERER"), "opengles3_desktopgl", 19) == 0) {
            printf("EGLBridge: Binding to desktop OpenGL\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
        } else {
            printf("EGLBridge: Binding to OpenGL ES\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) printf("EGLBridge: bind failed: %d\n", eglGetError_p());
    }

    int libgl_es = strtol(getenv("LIBGL_ES"), NULL, 0);
    if(libgl_es < 0 || libgl_es > INT16_MAX) libgl_es = 2;
    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, egl_context_attributes);

    if (bundle->context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext_p() finished with error: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    return bundle;
}

static EGLSurface get_or_create_shared_pbuffer(gl_render_window_t* bundle) {
    if (g_shared_pbuffer != EGL_NO_SURFACE) {
        return g_shared_pbuffer;
    }
    const EGLint pbuffer_attrs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    g_shared_pbuffer = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
    if (g_shared_pbuffer == EGL_NO_SURFACE) {
        LOGE("Failed to create shared 1x1 PBuffer surface: 0x%04x", eglGetError_p());
    } else {
        LOGI("Created shared 1x1 headless PBuffer surface: %p", g_shared_pbuffer);
    }
    return g_shared_pbuffer;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_nativeSetBackgroundMode(JNIEnv* env, jclass clazz, jboolean inBackground) {
    LOGI("nativeSetBackgroundMode called: %d", inBackground);
    g_in_background = inBackground ? true : false;
    if (pojav_environ != NULL && pojav_environ->mainWindowBundle != NULL) {
        if (inBackground) {
            pojav_environ->mainWindowBundle->newNativeSurface = NULL;
            pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        } else {
            if (pojav_environ->pojavWindow != NULL) {
                int32_t w = ANativeWindow_getWidth(pojav_environ->pojavWindow);
                int32_t h = ANativeWindow_getHeight(pojav_environ->pojavWindow);
                if (w > 0 && h > 0) {
                    pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
                    pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
                }
            }
        }
    }
}

void gl_swap_surface(gl_render_window_t* bundle) {
    if (bundle == NULL) return;

    // Safely destroy old window surface (retaining shared PBuffer)
    if (bundle->surface != EGL_NO_SURFACE && bundle->surface != NULL) {
        if (bundle->surface == g_shared_pbuffer) {
            bundle->surface = EGL_NO_SURFACE;
        } else {
            LOGI("gl_swap_surface: Destroying old window EGL surface");
            eglDestroySurface_p(g_EglDisplay, bundle->surface);
            bundle->surface = EGL_NO_SURFACE;
        }
    }
    if (bundle->nativeSurface != NULL) {
        ANativeWindow_release(bundle->nativeSurface);
        bundle->nativeSurface = NULL;
    }

    // Attach to active ANativeWindow (foreground) or 1x1 PBuffer (background)
    if (!g_in_background && bundle->newNativeSurface != NULL) {
        int32_t w = ANativeWindow_getWidth(bundle->newNativeSurface);
        int32_t h = ANativeWindow_getHeight(bundle->newNativeSurface);
        if (w <= 0 || h <= 0) {
            LOGW("gl_swap_surface: newNativeSurface has invalid dimensions (%d x %d), falling back to PBuffer", w, h);
            bundle->nativeSurface = NULL;
            bundle->newNativeSurface = NULL;
            bundle->surface = get_or_create_shared_pbuffer(bundle);
        } else {
            LOGI("gl_swap_surface: Switching to active ANativeWindow (%d x %d)", w, h);
            bundle->nativeSurface = bundle->newNativeSurface;
            bundle->newNativeSurface = NULL;
            ANativeWindow_acquire(bundle->nativeSurface);
            ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
            bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, bundle->nativeSurface, NULL);
            if (bundle->surface == EGL_NO_SURFACE) {
                LOGE("eglCreateWindowSurface_p failed: 0x%04x, falling back to PBuffer", eglGetError_p());
                bundle->surface = get_or_create_shared_pbuffer(bundle);
            } else if (pojav_environ != NULL) {
                pojav_environ->savedWidth = w;
                pojav_environ->savedHeight = h;
            }
        }
    } else {
        LOGI("gl_swap_surface: Attaching to 1x1 PBuffer (background=%d)", g_in_background);
        bundle->nativeSurface = NULL;
        bundle->newNativeSurface = NULL;
        bundle->surface = get_or_create_shared_pbuffer(bundle);
    }
}

void gl_make_current(gl_render_window_t* bundle) {

    if(bundle == NULL) {
        if(eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;
        }
        return;
    }
    bool hasSetMainWindow = false;
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        LOGI("Main window bundle is now %p", pojav_environ->mainWindowBundle);
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
    }
    LOGI("Making current, surface=%p, nativeSurface=%p, newNativeSurface=%p", bundle->surface, bundle->nativeSurface, bundle->newNativeSurface);
    if(bundle->surface == NULL) { //it likely will be on the first run
        gl_swap_surface(bundle);
    }
    if(eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context)) {
        currentBundle = bundle;
    }else {
        if(hasSetMainWindow) {
            pojav_environ->mainWindowBundle->newNativeSurface = NULL;
            gl_swap_surface((gl_render_window_t*)pojav_environ->mainWindowBundle);
            pojav_environ->mainWindowBundle = NULL;
        }
        LOGE("eglMakeCurrent returned with error: %04x", eglGetError_p());
    }

}

void gl_swap_buffers() {
    if (currentBundle == NULL) return;

    if (currentBundle->state == STATE_RENDERER_NEW_WINDOW ||
        (!g_in_background && currentBundle->surface == g_shared_pbuffer && (currentBundle->newNativeSurface != NULL || (pojav_environ != NULL && pojav_environ->pojavWindow != NULL)))) {

        // If in foreground and currently on PBuffer, and newNativeSurface is NULL but pojavWindow is valid, pick it up
        if (!g_in_background && currentBundle->newNativeSurface == NULL && pojav_environ != NULL && pojav_environ->pojavWindow != NULL) {
            int32_t pw = ANativeWindow_getWidth(pojav_environ->pojavWindow);
            int32_t ph = ANativeWindow_getHeight(pojav_environ->pojavWindow);
            if (pw > 0 && ph > 0) {
                currentBundle->newNativeSurface = pojav_environ->pojavWindow;
            }
        }

        if (currentBundle->state == STATE_RENDERER_NEW_WINDOW || currentBundle->newNativeSurface != NULL) {
            currentBundle->state = STATE_RENDERER_ALIVE;
            eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); //detach everything to destroy the old EGLSurface
            gl_swap_surface(currentBundle);
            eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
        }
    }

    if (currentBundle->surface != NULL && currentBundle->surface != EGL_NO_SURFACE) {
        if (currentBundle->surface != g_shared_pbuffer) {
            if (!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface)) {
                EGLint err = eglGetError_p();
                LOGW("eglSwapBuffers failed: 0x%04x", err);
                // Catch any eglSwapBuffers failure to trigger emergency detach to PBuffer
                eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                currentBundle->newNativeSurface = NULL;
                gl_swap_surface(currentBundle);
                eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
                currentBundle->state = STATE_RENDERER_ALIVE;
                LOGI("Window died or error, emergency detached to shared PBuffer");
            }
        }
    }

    // In background mode throttle by usleep(50000) (50ms / 20 FPS, aligned with Minecraft 20 TPS)
    if (g_in_background) {
        usleep(50000);
    }
}

void gl_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        LOGI("Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void gl_swap_interval(int swapInterval) {
    if(pojav_environ->force_vsync) swapInterval = 1;

    eglSwapInterval_p(g_EglDisplay, swapInterval);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    LOGI("GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        LOGI("GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}
