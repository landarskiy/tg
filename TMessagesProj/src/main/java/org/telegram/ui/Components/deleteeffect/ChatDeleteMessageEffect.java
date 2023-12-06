package org.telegram.ui.Components.deleteeffect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.opengl.GLUtils;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.RLottieDrawable;

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
    private final Paint debugPaint = new Paint();
    private final Paint debugPaint2 = new Paint();
    private final TextureView textureView;
    private RenderThread renderThread;

    public ChatDeleteMessageEffect(@NonNull Context context) {
        super(context);
        setWillNotDraw(false);
        debugPaint.setColor(Color.RED);
        debugPaint.setStyle(Paint.Style.FILL);
        debugPaint2.setColor(Color.GREEN);
        debugPaint2.setStyle(Paint.Style.STROKE);
        debugPaint2.setStrokeWidth(2);

        textureView = new TextureView(getContext());
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (renderThread == null) {
                    renderThread = new RenderThread(surface, width, height);
                    renderThread.start();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (renderThread == null) {
                    renderThread = new RenderThread(surface, width, height);
                    renderThread.start();
                } else {
                    renderThread.updateSize(width, height);
                }
                logD(String.format(Locale.ROOT, "Size changed to %dx%d, measured is %dx%d", width, height, textureView.getMeasuredWidth(), textureView.getMeasuredHeight()));
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (renderThread != null) {
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
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        textureView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        debugPaint(canvas);
    }

    private void debugPaint(Canvas canvas) {
        float size = px(64);
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
        if (disappearedChildren.isEmpty()) {
            return;
        }
        Rect bounds = new Rect();
        Bitmap viewBitmap = null;
        long startTile = System.nanoTime();
        for (int i = 0; i < Math.min(disappearedChildren.size(), 1); i++) {
            View childrenView = disappearedChildren.get(i);
            if (!(childrenView instanceof ChatMessageCell)) {
                continue;
            }
            ChatMessageCell cell = (ChatMessageCell) childrenView;
            int left = (cell.getLeft() + cell.getBackgroundDrawableLeft());
            int right = (cell.getLeft() + cell.getBackgroundDrawableRight());
            int top = (cell.getTop() + cell.getBackgroundDrawableTop()) - topDiff;
            int bottom = (cell.getTop() + cell.getBackgroundDrawableBottom()) - topDiff;
            bounds.set(left, top, right, bottom);
            viewBitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(viewBitmap);
            c.translate(-left, 0);
            cell.drawForBlur = true;
            if (cell.drawBackgroundInParent()) {
                cell.drawBackgroundInternal(c, true);
            }
            cell.draw(c);
            if (cell.hasOutboundsContent()) {
                cell.drawOutboundsContent(c);
            }
            cell.drawForBlur = false;
        }
        if (renderThread == null) {
            return;
        }
        if(viewBitmap != null) {
            renderThread.updateTexture(viewBitmap, bounds);
        }
        long endTime = System.nanoTime();
        double deltaTimeMs = (double) (endTime - startTile) / 1000000.0;
        logD(String.format(Locale.ROOT, "Update time is %.4f", deltaTimeMs));
    }

    private class RenderThread extends Thread {
        private volatile boolean running = true;
        private volatile boolean paused = false;

        private final SurfaceTexture surfaceTexture;
        private final Object resizeLock = new Object();
        private final Object textureLock = new Object();
        private boolean resize = true;
        private boolean init = true;
        private int width, height;
        private int particlesCount;
        private int textureId;
        private Rect textureBoundsPx = new Rect();
        private double progress = 0.0;
        private long effectTime = 5 * 1000;
        private long startTime;

        private boolean updateProgress() {
            long currentTime = System.currentTimeMillis();
            double actualProgress = (currentTime - startTime) / (double) effectTime;
            boolean finished = actualProgress > 1;
            progress = Math.min(actualProgress, 1);
            return finished;
        }

        public RenderThread(SurfaceTexture surfaceTexture, int width, int height) {
            logD(String.format(Locale.ROOT, "Init with size %dx%d", width, height));
            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
        }

        public void updateSize(int width, int height) {
            logD(String.format(Locale.ROOT, "Size changed %dx%d", width, height));
            synchronized (resizeLock) {
                this.resize = true;
                this.width = width;
                this.height = height;
            }
        }

        private int getParticlesCount(Rect bounds) {
            int square = bounds.width() * bounds.height();
            //optimal count is 6000 for 1008x288=290304
            return (int) (3000f * square / 290304f);
        }

        public void updateTexture(Bitmap bitmap, Rect bounds) {
            //optimal count is 6000 for 1008x288=290304
            synchronized (textureLock) {
                this.pendingBitmap = bitmap;
                this.textureBoundsPx = bounds;
                this.particlesCount = getParticlesCount(bounds);
                this.init = true;
                int bmpW = bitmap.getWidth();
                int bmpH = bitmap.getHeight();
                logD(String.format("Display bounds size %dx%d (%d,%d,%d,%d), bmp %dx%d, texture view size %dx%d", bounds.width(), bounds.height(), bounds.left, bounds.top, bounds.right, bounds.bottom, bmpW, bmpH, width, height));
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
            while (running) {
                checkTexture();
                while (paused || textureId == 0) {
                    try {
                        checkTexture();
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable ignore) {
                    }
                }

                boolean clearPath = updateProgress();
                drawFrame(clearPath);
                if(clearPath) {
                    deleteTexture(textureId);
                    textureId = 0;
                    logD("Move to pause stage");
                }
            }
            releaseResources();
        }

        private EGL10 egl;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLSurface eglSurface;
        private EGLContext eglContext;

        private int sldTextureHandle;
        private int sldTexturePositionHandle;
        private int sldPositionHandle;
        private int sldProgressHandle;

        private int ptcTextureHandle;
        private int ptcParticlesCountHandle;
        private int ptcParticleRadiusHandle;
        private int ptcMinOffsetHandle;
        private int ptcMaxOffsetHandle;
        private int ptcProgressHandle;
        // vec2
        private int ptcScreenSizeHandle;
        // vec4
        private int ptcParticleBoundsHandle;
        private int ptcInitHandle;

        private int drawOriginalProgram;
        private int drawParticlesProgram;

        static final int COORDS_PER_VERTEX = 2;
        static final int VERTEX_STRIDE = COORDS_PER_VERTEX * 4;
        private short DRAW_ORDER[] = {0, 1, 2, 0, 2, 3}; // order to draw vertices
        private float[] QUADRANT_COORDINATES = new float[]{
                //x,    y
                -1f, 1f,    // top left
                -1f, -1f,   // bottom left
                1f, -1f,    // bottom right
                1f, 1f      // top right
        };

        private float[] TEXTURE_COORDINATES = new float[]{
                //x,    y
                0.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f,
                1.0f, 0.0f
        };

        private Bitmap pendingBitmap;
        float particleR;
        float minOffset;
        float maxOffset;
        private int currentBuffer = 0;
        private int[] particlesData;
        private int[] vertexBufferId;
        private int[] textureBufferId;
        private int[] drawOrderBufferId;


        private void init() {
            particleR = px(2f);
            minOffset = px(16f);
            maxOffset = px(64f);
            egl = (EGL10) EGLContext.getEGL();

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

            // draw program (vertex and fragment shaders)
            if (!createDrawParticlesProgram() || !createDrawOriginalProgram()) {
                running = false;
                return;
            }

            GLES31.glEnable(GLES31.GL_BLEND);
            GLES31.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES31.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            checkGlErrors();
            //
        }

        private boolean createDrawOriginalProgram() {
            drawOriginalProgram = createProgram(R.raw.delete_msg_slider_vertex, R.raw.delete_msg_slider_fragment);
            if(drawOriginalProgram == -1) {
                return false;
            }

            int[] status = new int[1];
            GLES31.glLinkProgram(drawOriginalProgram);
            GLES31.glGetProgramiv(drawOriginalProgram, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                logE("ChatDeleteMessageEffect, link draw program error: " + GLES31.glGetProgramInfoLog(drawOriginalProgram));
                return false;
            }
            GLES31.glUseProgram(drawOriginalProgram);
            checkGlErrors();
            sldPositionHandle = GLES31.glGetAttribLocation(drawOriginalProgram, "inPosition");
            sldTexturePositionHandle = GLES31.glGetAttribLocation(drawOriginalProgram, "inTextCoord");
            sldTextureHandle = GLES20.glGetUniformLocation(drawOriginalProgram, "uTexture");
            sldProgressHandle = GLES20.glGetUniformLocation(drawOriginalProgram, "uProgress");

            genShapeData();

            return true;
        }

        private boolean createDrawParticlesProgram() {
            drawParticlesProgram = createProgram(R.raw.delete_msg_particles_vertex, R.raw.delete_msg_particles_fragment);
            if(drawParticlesProgram == -1) {
                return false;
            }

            genParticlesData();

            String[] feedbackVaryings = {"outPosition", "outTextCoord", "outTargetOffset"};
            GLES31.glTransformFeedbackVaryings(drawParticlesProgram, feedbackVaryings, GLES31.GL_INTERLEAVED_ATTRIBS);

            int[] status = new int[1];
            GLES31.glLinkProgram(drawParticlesProgram);
            GLES31.glGetProgramiv(drawParticlesProgram, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                logE("ChatDeleteMessageEffect, link draw program error: " + GLES31.glGetProgramInfoLog(drawParticlesProgram));
                running = false;
                return false;
            }
            GLES31.glUseProgram(drawParticlesProgram);
            checkGlErrors();
            ptcTextureHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uTexture");
            ptcProgressHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uProgress");
            ptcScreenSizeHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uScreenSizePx");
            ptcParticleBoundsHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uParticleBoundsPx");
            ptcParticlesCountHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uParticlesCount");
            ptcParticleRadiusHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uParticleRadius");
            ptcMinOffsetHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uMinOffset");
            ptcMaxOffsetHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uMaxOffset");
            ptcInitHandle = GLES20.glGetUniformLocation(drawParticlesProgram, "uInit");
            return true;
        }

        private int createProgram(int vertexResId, int fragmentResId) {
            int vertexShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);
            int fragmentShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);
            if (vertexShader == 0 || fragmentShader == 0) {
                return -1;
            }
            GLES31.glShaderSource(vertexShader, loadShader(vertexResId));
            GLES31.glCompileShader(vertexShader);
            int[] status = new int[1];
            GLES31.glGetShaderiv(vertexShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                logE("ChatDeleteMessageEffect, compile vertex shader error: " + GLES31.glGetShaderInfoLog(vertexShader));
                GLES31.glDeleteShader(vertexShader);
                return -1;
            }
            GLES31.glShaderSource(fragmentShader, loadShader(fragmentResId));
            GLES31.glCompileShader(fragmentShader);
            GLES31.glGetShaderiv(fragmentShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                logE("ChatDeleteMessageEffect, compile fragment shader error: " + GLES31.glGetShaderInfoLog(fragmentShader));
                GLES31.glDeleteShader(fragmentShader);
                return -1;
            }
            int program = GLES31.glCreateProgram();
            if (program == 0) {
                return -1;
            }
            GLES31.glAttachShader(program, vertexShader);
            GLES31.glAttachShader(program, fragmentShader);
            return program;
        }

        private void drawFrame(boolean clearPath) {
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }
            checkResize();
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
            if (clearPath) {
                egl.eglSwapBuffers(eglDisplay, eglSurface);
                checkGlErrors();
                return;
            }
            //
            // Draw slider
            //
            GLES31.glUseProgram(drawOriginalProgram);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(sldTextureHandle, 0);

            GLES20.glUniform1f(sldProgressHandle, (float) progress);

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, vertexBufferId[0]);
            GLES31.glEnableVertexAttribArray(sldPositionHandle);
            GLES31.glVertexAttribPointer(sldPositionHandle, COORDS_PER_VERTEX, GLES31.GL_FLOAT, false, VERTEX_STRIDE, 0);

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, textureBufferId[0]);
            GLES31.glEnableVertexAttribArray(sldTexturePositionHandle);
            GLES31.glVertexAttribPointer(sldTexturePositionHandle, COORDS_PER_VERTEX, GLES31.GL_FLOAT, false, VERTEX_STRIDE, 0);

            GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, drawOrderBufferId[0]);

            GLES31.glDrawElements(GLES31.GL_TRIANGLES, DRAW_ORDER.length, GLES31.GL_UNSIGNED_SHORT, 0);

            GLES31.glDisableVertexAttribArray(sldTexturePositionHandle);
            GLES31.glDisableVertexAttribArray(sldPositionHandle);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0);
            GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, 0);

            //
            // Draw particles
            //
            GLES31.glUseProgram(drawParticlesProgram);

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(ptcTextureHandle, 0);

            GLES20.glUniform1i(ptcParticlesCountHandle, particlesCount);
            GLES20.glUniform1f(ptcParticleRadiusHandle, particleR);

            GLES20.glUniform4f(ptcParticleBoundsHandle, textureBoundsPx.left, textureBoundsPx.top, textureBoundsPx.right, textureBoundsPx.bottom);
            GLES20.glUniform2f(ptcScreenSizeHandle, width, height);
            GLES20.glUniform1f(ptcProgressHandle, (float) progress);

            GLES20.glUniform1f(ptcInitHandle, init ? 1 : 0);
            init = false;

            GLES20.glUniform1f(ptcMinOffsetHandle, minOffset);
            GLES20.glUniform1f(ptcMaxOffsetHandle, maxOffset);

            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[currentBuffer]);
            int floatSize = 4;
            int vec2Size = floatSize * 2;
            //vec2 + vec2 = 4 * 2 + 4 * 2 = 8 + 8 = 16
            int stride = vec2Size + vec2Size + vec2Size;
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, stride, 0); // Position (vec2)
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, stride, 8); // Texture position (vec2)
            GLES31.glEnableVertexAttribArray(1);
            GLES31.glVertexAttribPointer(2, 2, GLES31.GL_FLOAT, false, stride, 16); // Texture position (vec2)
            GLES31.glEnableVertexAttribArray(2);
            GLES31.glBindBufferBase(GLES31.GL_TRANSFORM_FEEDBACK_BUFFER, 0, particlesData[1 - currentBuffer]);
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, stride, 0); // Position (vec2)
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, stride, 8); // Texture position (vec2)
            GLES31.glEnableVertexAttribArray(1);
            GLES31.glVertexAttribPointer(2, 2, GLES31.GL_FLOAT, false, stride, 16); // Texture position (vec2)
            GLES31.glEnableVertexAttribArray(2);

            GLES31.glBeginTransformFeedback(GLES31.GL_POINTS);
            GLES31.glDrawArrays(GLES31.GL_POINTS, 0, particlesCount);
            GLES31.glEndTransformFeedback();

            checkGlErrors();

            currentBuffer = 1 - currentBuffer;
            egl.eglSwapBuffers(eglDisplay, eglSurface);
            checkGlErrors();
        }

        private void releaseResources() {
            if (particlesData != null) {
                try { GLES31.glDeleteBuffers(2, particlesData, 0); } catch (Exception e) { logE(e); }
                particlesData = null;
            }
            if(vertexBufferId != null) {
                try {
                    GLES31.glDeleteBuffers(1, vertexBufferId, 0);
                    GLES31.glDeleteBuffers(1, textureBufferId, 0);
                    GLES31.glDeleteBuffers(1, drawOrderBufferId, 0);
                } catch (Exception e) { logE(e); }
                vertexBufferId = null;
                textureBufferId = null;
                drawOrderBufferId = null;
            }
            deleteTexture(textureId);
            textureId = 0;
            if (drawParticlesProgram != 0) {
                try { GLES31.glDeleteProgram(drawParticlesProgram); } catch (Exception e) { logE(e); }
                drawParticlesProgram = 0;
            }
            if (drawOriginalProgram != 0) {
                try { GLES31.glDeleteProgram(drawOriginalProgram); } catch (Exception e) { logE(e); }
                drawOriginalProgram = 0;
            }
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
                surfaceTexture.release();
            } catch (Exception e) {
                logE(e);
            }

            checkGlErrors();
        }

        private void deleteTexture(int textureId) {
            if (textureId == 0) {
                return;
            }
            final int[] textureHandle = new int[]{textureId};
            try { GLES20.glDeleteTextures(1, textureHandle, 0); } catch (Throwable e) { logE(e); }
        }

        private void checkTexture() {
            synchronized (textureLock) {
                Bitmap texture = pendingBitmap;
                if (texture == null || texture.isRecycled()) {
                    return;
                }
                if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    running = false;
                    return;
                }
                deleteTexture(textureId);
                final int[] textureHandle = new int[1];
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glGenTextures(1, textureHandle, 0);
                if (textureHandle[0] == 0) {
                    return;
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, texture, 0);
                textureId = textureHandle[0];
                pendingBitmap = null;
                texture.recycle();
                genParticlesData();
                startTime = System.currentTimeMillis();
                progress = 0.0;

                genShapeData();
            }
        }

        private void checkResize() {
            synchronized (resizeLock) {
                if (resize) {
                    GLES31.glViewport(0, 0, width, height);
                    resize = false;
                    genShapeData();
                }
            }
        }

        private void genParticlesData() {
            if (particlesData != null) {
                GLES31.glDeleteBuffers(2, particlesData, 0);
            }

            particlesData = new int[2];
            GLES31.glGenBuffers(2, particlesData, 0);

            for (int i = 0; i < 2; ++i) {
                GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[i]);
                //4 = float size
                int floatSize = 4;
                //vec2 + vec2 + vec2 = 2 + 2 + 2 = 4
                int dataCount = 2 + 2 + 2;
                GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, this.particlesCount * dataCount * floatSize, null, GLES31.GL_DYNAMIC_DRAW);
            }
            checkGlErrors();
        }

        private void genShapeData() {
            if(vertexBufferId != null) {
                GLES31.glDeleteBuffers(1, vertexBufferId, 0);
                GLES31.glDeleteBuffers(1, textureBufferId, 0);
                GLES31.glDeleteBuffers(1, drawOrderBufferId, 0);
            }

            vertexBufferId = new int[1];
            textureBufferId = new int[1];
            drawOrderBufferId = new int[1];
            GLES31.glGenBuffers(1, vertexBufferId, 0);
            GLES31.glGenBuffers(1, textureBufferId, 0);
            GLES31.glGenBuffers(1, drawOrderBufferId, 0);

            Rect bounds = textureBoundsPx;
            float[] coordinates;
            if (bounds != null) {
                float left = (float) bounds.left / width;
                float top = 1f - (float) bounds.top / height;
                float right = (float) bounds.right / width;
                float bottom = 1f - (float) bounds.bottom / height;

                left = left * 2f - 1f;
                top = top * 2f - 1f;
                right = right * 2f - 1f;
                bottom = bottom * 2 - 1f;

                coordinates = new float[]{
                        //x,    y
                        left, top,      // left top
                        left, bottom,   // left bottom
                        right, bottom,  // right bottom
                        right, top      // right top
                };
            } else {
                coordinates = QUADRANT_COORDINATES;
            }
            // initialize vertex byte buffer for shape coordinates (# of coordinate values * 4 bytes per float)
            ByteBuffer bb = ByteBuffer.allocateDirect(QUADRANT_COORDINATES.length * 4);
            bb.order(ByteOrder.nativeOrder());
            FloatBuffer vertexBuffer = bb.asFloatBuffer();
            vertexBuffer.put(coordinates);
            vertexBuffer.position(0);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, vertexBufferId[0]);
            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, QUADRANT_COORDINATES.length * 4, vertexBuffer, GLES31.GL_STATIC_DRAW);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0);

            ByteBuffer tbb = ByteBuffer.allocateDirect(TEXTURE_COORDINATES.length * 4);
            tbb.order(ByteOrder.nativeOrder());
            FloatBuffer textureBuffer = tbb.asFloatBuffer();
            textureBuffer.put(TEXTURE_COORDINATES);
            textureBuffer.position(0);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, textureBufferId[0]);
            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, TEXTURE_COORDINATES.length * 4, textureBuffer, GLES31.GL_STATIC_DRAW);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0);

            // initialize byte buffer for the draw list (# of coordinate values * 2 bytes per short)
            ByteBuffer dlb = ByteBuffer.allocateDirect(DRAW_ORDER.length * 2);
            dlb.order(ByteOrder.nativeOrder());
            ShortBuffer drawListBuffer = dlb.asShortBuffer();
            drawListBuffer.put(DRAW_ORDER);
            drawListBuffer.position(0);
            GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, drawOrderBufferId[0]);
            GLES31.glBufferData(GLES31.GL_ELEMENT_ARRAY_BUFFER, DRAW_ORDER.length * 2, drawListBuffer, GLES31.GL_STATIC_DRAW);
            GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, 0);

            checkGlErrors();
        }

        private void checkGlErrors() {
            int err;
            while ((err = GLES31.glGetError()) != GLES31.GL_NO_ERROR) {
                logE("Error code: " + err);
            }
        }
    }

    private void logD(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("EFFECT_RENDERER " + message);
        }
    }

    private void logE(Throwable e) {
        FileLog.e("DELETE_EFFECT_RENDERER " + e.getMessage(), e);
    }

    private void logE(String message) {
        FileLog.e("DELETE_EFFECT_RENDERER " + message);
    }

    private String loadShader(int resId) {
        return RLottieDrawable.readRes(null, resId);
    }

    float px(float dip) {
        return AndroidUtilities.dpf2(dip);
    }
}