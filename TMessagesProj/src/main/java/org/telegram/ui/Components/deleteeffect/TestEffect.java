package org.telegram.ui.Components.deleteeffect;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES31;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import java.util.List;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class TestEffect extends FrameLayout {

    private final TextureView textureView;
    private RendererThread rendererThread;

    public TestEffect(@NonNull Context context) {
        super(context);
        textureView = new TextureView(getContext());
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (rendererThread == null) {
                    rendererThread = new RendererThread(surface);
                    rendererThread.start();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (rendererThread == null) {
                    rendererThread = new RendererThread(surface);
                    rendererThread.start();
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (rendererThread != null) {
                    rendererThread.isActive = false;
                    rendererThread = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
        textureView.setOpaque(false);
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void playDeleteEffect(List<View> disappearedChildren, int topDiff) {
        logD(String.format("Play effect A %d,%d,%d,%d.", getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom()));
        logD(String.format("Play effect B %d,%d,%d,%d.", textureView.getPaddingLeft(), textureView.getPaddingTop(), textureView.getPaddingRight(), textureView.getPaddingBottom()));
        logD(String.format("Play effect C %d,%d.", textureView.getMeasuredWidth(), textureView.getMeasuredHeight()));
    }

    class RendererThread extends Thread {
        private final SurfaceTexture surface;
        boolean isActive = true;

        RendererThread(SurfaceTexture surface) {
            this.surface = surface;
        }

        @Override
        public void run() {
            OpenGlContext context = OpenGlContext.tryCreate(surface);
            if (context == null) {
                return;
            }
            while (isActive) {
                drawFrame(context);
            }
            context.destroy();
        }

        private void drawFrame(OpenGlContext context) {
            if (!context.makeCurrent()) {
                return;
            }
            GLES31.glClearColor(0.5f, 0f, 0f, 1.0f);
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
            context.swapBuffers();

            try {
                Thread.sleep((long) (1f / 60f * 1000f));
            } catch (InterruptedException ignored) {
            }
        }
    }

    static class OpenGlContext {
        final SurfaceTexture surface;
        final EGL10 egl;
        final EGLDisplay eglDisplay;
        final EGLConfig eglConfig;
        final EGLSurface eglSurface;
        final EGLContext eglContext;

        public OpenGlContext(SurfaceTexture surface, EGL10 egl, EGLDisplay eglDisplay, EGLConfig eglConfig, EGLSurface eglSurface, EGLContext eglContext) {
            this.surface = surface;
            this.egl = egl;
            this.eglDisplay = eglDisplay;
            this.eglConfig = eglConfig;
            this.eglSurface = eglSurface;
            this.eglContext = eglContext;
        }

        boolean makeCurrent() {
            return egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }

        boolean swapBuffers() {
            return egl.eglSwapBuffers(eglDisplay, eglSurface);
        }

        void destroy() {
            if (egl != null) {
                try {
                    egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                } catch (Exception e) {
                    logE(e);
                }
                try {
                    egl.eglDestroySurface(eglDisplay, eglSurface);
                } catch (Exception e) {
                    logE(e);
                }
                try {
                    egl.eglDestroyContext(eglDisplay, eglContext);
                } catch (Exception e) {
                    logE(e);
                }
            }
            try {
                surface.release();
            } catch (Exception e) {
                logE(e);
            }
        }

        static OpenGlContext tryCreate(SurfaceTexture surface) {
            if (surface == null) {
                return null;
            }
            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == egl.EGL_NO_DISPLAY) {
                return null;
            }
            int[] version = new int[2];
            if (!egl.eglInitialize(eglDisplay, version)) {
                return null;
            }
            EGLConfig eglConfig = chooseEglConfig(egl, eglDisplay);
            if (eglConfig == null) {
                return null;
            }
            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
            };
            EGLContext eglContext = egl.eglCreateContext(
                    eglDisplay, eglConfig, egl.EGL_NO_CONTEXT, contextAttributes
            );
            if (eglContext == null) {
                return null;
            }
            EGLSurface eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surface, null);
            if (eglSurface == null) {
                return null;
            }
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                return null;
            }
            return new OpenGlContext(surface, egl, eglDisplay, eglConfig, eglSurface, eglContext);
        }

        private static EGLConfig chooseEglConfig(EGL10 egl, EGLDisplay eglDisplay) {
            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE
            };

            EGLConfig[] eglConfigs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!egl.eglChooseConfig(eglDisplay, configAttributes, eglConfigs, 1, numConfigs)) {
                return null;
            }
            return eglConfigs[0];
        }
    }

    private static void logD(String message) {
        Log.d("EFFECT_RENDERER", message);
    }

    private static void logE(Throwable e) {
        Log.e("EFFECT_RENDERER", e.getMessage(), e);
    }

    private static void logE(String message) {
        Log.e("EFFECT_RENDERER", message);
    }
}
