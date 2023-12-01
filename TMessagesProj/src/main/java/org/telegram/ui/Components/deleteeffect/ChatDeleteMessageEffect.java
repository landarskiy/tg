package org.telegram.ui.Components.deleteeffect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.spoilers.SpoilerEffect2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class ChatDeleteMessageEffect extends FrameLayout {

    private Bitmap bufferTexture = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    private Canvas bufferTextureCanvas = new Canvas(bufferTexture);
    private final Paint debugPaint = new Paint();
    private final Paint debugPaint2 = new Paint();
    private int topDiff = 0;

    private final TextureView textureView;
    private RenderThread renderThread;

    public ChatDeleteMessageEffect(@NonNull Context context) {
        super(context);
        setWillNotDraw(false);
        debugPaint.setColor(Color.RED);
        debugPaint.setStyle(Paint.Style.FILL);
        debugPaint2.setColor(Color.GREEN);
        debugPaint2.setStyle(Paint.Style.STROKE);
        debugPaint2.setStrokeWidth(AndroidUtilities.dpf2(1));

        textureView = new TextureView(getContext());
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (renderThread == null) {
                    renderThread = new RenderThread(surface, width, height);
                    renderThread.setInvalidateListener(() -> invalidate());
                    renderThread.start();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (renderThread == null) {
                    renderThread = new RenderThread(surface, width, height);
                    renderThread.setInvalidateListener(() -> invalidate());
                    renderThread.start();
                } else {
                    renderThread.updateSize(width, height);
                }
                FileLog.d(String.format(Locale.ROOT, "Size changed to %dx%d, measured is %dx%d", width, height, textureView.getMeasuredWidth(), textureView.getMeasuredHeight()));
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (renderThread != null) {
                    renderThread.setInvalidateListener(null);
                    renderThread.destroyThread();
                    renderThread = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
        textureView.setOpaque(false);
        addView(textureView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        textureView.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= bufferTexture.getWidth() && h <= bufferTexture.getHeight()) {
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Play child delete: recreate");
        }
        Bitmap newTexture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas newCanvas = new Canvas(newTexture);
        Bitmap oldTexture = bufferTexture;
        bufferTexture = newTexture;
        bufferTextureCanvas = newCanvas;
        oldTexture.recycle();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        debugPaint(canvas);
    }

    private void debugPaint(Canvas canvas) {
        canvas.drawBitmap(bufferTexture, 0f, 0f, debugPaint);
        float size = AndroidUtilities.dpf2(64);
        canvas.drawRect(0, 0, size, size, debugPaint);
        canvas.drawRect(0, 0, size, size, debugPaint2);
        canvas.drawRect(getWidth() - size, 0, getWidth(), size, debugPaint);
        canvas.drawRect(getWidth() - size, 0, getWidth(), size, debugPaint2);
        canvas.drawRect(0, getHeight() - size, size, getHeight(), debugPaint);
        canvas.drawRect(0, getHeight() - size, size, getHeight(), debugPaint2);
        canvas.drawRect(getWidth() - size, getHeight() - size, getWidth(), getHeight(), debugPaint);
        canvas.drawRect(getWidth() - size, getHeight() - size, getWidth(), getHeight(), debugPaint2);
    }

    public void playDeleteEffect(List<View> disappearedChildren, int topDiff) {
        this.topDiff = topDiff;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Play child delete " + disappearedChildren.size());
        }
        if (disappearedChildren.isEmpty()) {
            return;
        }
        bufferTextureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        for (int i = 0; i < disappearedChildren.size(); i++) {
            View childrenView = disappearedChildren.get(i);
            bufferTextureCanvas.save();
            bufferTextureCanvas.translate(0, childrenView.getTop() - topDiff);
            childrenView.draw(bufferTextureCanvas);
            bufferTextureCanvas.restore();
        }
        invalidate();
    }

    private class RenderThread extends Thread {
        private volatile boolean running = true;
        private volatile boolean paused = false;

        private Runnable invalidateListener;
        private final SurfaceTexture surfaceTexture;
        private final Object resizeLock = new Object();
        private boolean resize;
        private int width, height;
        private float radius = AndroidUtilities.dpf2(1.2f);

        public RenderThread(SurfaceTexture surfaceTexture, int width, int height) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d(String.format(Locale.ROOT, "Init with size %dx%d", width, height));
            }
            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
        }

        public void setInvalidateListener(Runnable invalidateListener) {
            this.invalidateListener = invalidateListener;
        }

        public void updateSize(int width, int height) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d(String.format(Locale.ROOT, "Size changed %dx%d", width, height));
            }
            synchronized (resizeLock) {
                resize = true;
                this.width = width;
                this.height = height;
            }
        }

        public void destroyThread() {
            running = false;
        }

        public void pause(boolean paused) {
            this.paused = paused;
        }

        @Override
        public void run() {
            init();
            long lastTime = System.nanoTime();
            while (running) {
                final long now = System.nanoTime();
                double dt = (now - lastTime) / 1_000_000_000.;
                lastTime = now;

                while (paused) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable ignore) {
                    }
                }

                checkResize();
                drawFrame((float) dt);
                Runnable invalidate = invalidateListener;
                if (invalidate != null) {
                    AndroidUtilities.cancelRunOnUIThread(invalidate);
                    AndroidUtilities.runOnUIThread(invalidate);
                }
            }
            releaseResources();
        }

        private EGL10 egl;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLSurface eglSurface;
        private EGLContext eglContext;

        private int positionHandle;
        private int colorHandle;
        private FloatBuffer vertexBuffer;
        private ShortBuffer drawListBuffer;

        private int drawProgram;

        private int currentBuffer = 0;

        static final int COORDS_PER_VERTEX = 3;
        final float squareCoords[] = {
                -1f,  1f, 0.0f,   // top left
                -1f, -1f, 0.0f,   // bottom left
                1f, -1f, 0.0f,   // bottom right
                1f,  1f, 0.0f }; // top right

        private short drawOrder[] = { 0, 1, 2, 0, 2, 3 }; // order to draw vertices
        private final int vertexCount = squareCoords.length / COORDS_PER_VERTEX;
        private final int vertexStride = COORDS_PER_VERTEX * 4; // 4 bytes per vertex

        private void init() {
            egl = (EGL10) javax.microedition.khronos.egl.EGLContext.getEGL();

            eglDisplay = egl.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == egl.EGL_NO_DISPLAY) {
                running = false;
                return;
            }
            int[] version = new int[2];
            if (!egl.eglInitialize(eglDisplay, version)) {
                running = false;
                return;
            }

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
                running = false;
                return;
            }
            eglConfig = eglConfigs[0];

            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
            };
            eglContext = egl.eglCreateContext(eglDisplay, eglConfig, egl.EGL_NO_CONTEXT, contextAttributes);
            if (eglContext == null) {
                running = false;
                return;
            }

            eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            if (eglSurface == null) {
                running = false;
                return;
            }

            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }

            genParticlesData();

            // draw program (vertex and fragment shaders)
            int vertexShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);
            int fragmentShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);
            if (vertexShader == 0 || fragmentShader == 0) {
                running = false;
                return;
            }
            GLES31.glShaderSource(vertexShader, RLottieDrawable.readRes(null, R.raw.delete_msg_vertex));
            GLES31.glCompileShader(vertexShader);
            int[] status = new int[1];
            GLES31.glGetShaderiv(vertexShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("ChatDeleteMessageEffect, compile vertex shader error: " + GLES31.glGetShaderInfoLog(vertexShader));
                GLES31.glDeleteShader(vertexShader);
                running = false;
                return;
            }
            GLES31.glShaderSource(fragmentShader, RLottieDrawable.readRes(null, R.raw.delete_msg_fragment));
            GLES31.glCompileShader(fragmentShader);
            GLES31.glGetShaderiv(fragmentShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("ChatDeleteMessageEffect, compile fragment shader error: " + GLES31.glGetShaderInfoLog(fragmentShader));
                GLES31.glDeleteShader(fragmentShader);
                running = false;
                return;
            }
            drawProgram = GLES31.glCreateProgram();
            if (drawProgram == 0) {
                running = false;
                return;
            }
            GLES31.glAttachShader(drawProgram, vertexShader);
            GLES31.glAttachShader(drawProgram, fragmentShader);

            GLES31.glLinkProgram(drawProgram);
            GLES31.glGetProgramiv(drawProgram, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("ChatDeleteMessageEffect, link draw program error: " + GLES31.glGetProgramInfoLog(drawProgram));
                running = false;
                return;
            }

            positionHandle = GLES31.glGetAttribLocation(drawProgram, "vPosition");
            colorHandle = GLES20.glGetUniformLocation(drawProgram, "vColor");

            GLES31.glViewport(0, 0, width, height);
            GLES31.glEnable(GLES31.GL_BLEND);
            GLES31.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES31.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

            GLES31.glUseProgram(drawProgram);
            //
            initShape();
        }

        private void initShape() {
            // initialize vertex byte buffer for shape coordinates
            ByteBuffer bb = ByteBuffer.allocateDirect(
                    // (# of coordinate values * 4 bytes per float)
                    squareCoords.length * 4);
            bb.order(ByteOrder.nativeOrder());
            vertexBuffer = bb.asFloatBuffer();
            vertexBuffer.put(squareCoords);
            vertexBuffer.position(0);

            // initialize byte buffer for the draw list
            ByteBuffer dlb = ByteBuffer.allocateDirect(
                    // (# of coordinate values * 2 bytes per short)
                    drawOrder.length * 2);
            dlb.order(ByteOrder.nativeOrder());
            drawListBuffer = dlb.asShortBuffer();
            drawListBuffer.put(drawOrder);
            drawListBuffer.position(0);
        }

        private final float[] color = new float[]{1, 0, 0, 1};

        private void drawFrame(float dt) {
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }

            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);

            GLES31.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT, false,
                    vertexStride, vertexBuffer);
            GLES20.glUniform4fv(colorHandle, 1, color, 0);
            GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES, drawOrder.length,
                    GLES20.GL_UNSIGNED_SHORT, drawListBuffer
            );
            GLES20.glDisableVertexAttribArray(positionHandle);

            currentBuffer = 1 - currentBuffer;

            egl.eglSwapBuffers(eglDisplay, eglSurface);
            checkGlErrors();
        }

        private void releaseResources() {
            if (drawProgram != 0) {
                try {
                    GLES31.glDeleteProgram(drawProgram);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                ;
                drawProgram = 0;
            }
            if (egl != null) {
                try {
                    egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                ;
                try {
                    egl.eglDestroySurface(eglDisplay, eglSurface);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                ;
                try {
                    egl.eglDestroyContext(eglDisplay, eglContext);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                ;
            }
            try {
                surfaceTexture.release();
            } catch (Exception e) {
                FileLog.e(e);
            }

            checkGlErrors();
        }

        private void checkResize() {
            synchronized (resizeLock) {
                if (resize) {
                    GLES31.glViewport(0, 0, width, height);
                    resize = false;
                }
            }
        }

        private void genParticlesData() {
            //TODO work with texture
            checkGlErrors();
        }

        private void checkGlErrors() {
            int err;
            while ((err = GLES31.glGetError()) != GLES31.GL_NO_ERROR) {
                FileLog.e("spoiler gles error " + err);
            }
        }
    }
}
