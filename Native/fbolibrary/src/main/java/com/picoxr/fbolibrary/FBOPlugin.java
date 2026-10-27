package com.picoxr.fbolibrary;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Copies MediaCodec's external OES surface into a Unity-owned GLES texture. */
public final class FBOPlugin implements SurfaceTexture.OnFrameAvailableListener {
    private static volatile String diagnosticMode = "off";
    private static volatile String diagnosticPath;
    private static volatile int diagnosticWarmup;
    public static void configureDiagnostic(String mode, String path) {
        diagnosticWarmup = 60;
        diagnosticPath = path;
        diagnosticMode = mode;
    }
    private static final String TAG = "FBOPlugin";

    private static final String VERTEX_SHADER =
        "#version 300 es\n" +
        "layout(location=0) in vec2 aPosition;\n" +
        "layout(location=1) in vec2 aTexCoord;\n" +
        "uniform mat4 textureTransform;\n" +
        "out vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
        "  vTexCoord = (textureTransform * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#version 300 es\n" +
        "#extension GL_OES_EGL_image_external_essl3 : require\n" +
        "precision mediump float;\n" +
        "in vec2 vTexCoord;\n" +
        "layout(location = 0) out vec4 fragColor;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "uniform int diagnosticUv;\n" +
        "void main() { fragColor = diagnosticUv == 1 ? vec4(vTexCoord, 0.5, 1.0) : texture(sTexture, vTexCoord); }\n";

    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private int oesTextureId;
    private int unityTextureId;
    private int framebufferId;
    private int programId;
    private int vertexArrayId;
    private int vertexBufferId;
    private int samplerLocation;
    private int transformLocation;
    private final float[] textureTransform = new float[16];
    private EGLContext ownerContext;
    private int width;
    private int height;
    public volatile boolean released = true;
    private boolean framebufferErrorLogged;

    public void init() {
        if (!released) {
            release();
        }

        ownerContext = EGL14.eglGetCurrentContext();
        requireContext();
        released = false; // Allows release() to clean up partial initialization.
        int previousOes = getInteger(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES);
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        oesTextureId = ids[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        // External OES textures reject mipmap filters and GenerateMipmap.
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, previousOes);

        surfaceTexture = new SurfaceTexture(oesTextureId);
        surfaceTexture.setOnFrameAvailableListener(this);
        surface = new Surface(surfaceTexture);
        frameAvailable.set(false);
        released = false;
        Log.i(TAG, "Initialized OES texture=" + oesTextureId);
    }

    public void BuildTexture(int textureId, int textureWidth, int textureHeight) {
        requireContext();
        if (surfaceTexture == null) {
            throw new IllegalStateException("init must be called before BuildTexture");
        }
        if (textureId == 0 || textureWidth <= 0 || textureHeight <= 0 || framebufferId != 0) {
            throw new IllegalStateException("Invalid texture or repeated BuildTexture without release");
        }
        unityTextureId = textureId;
        width = textureWidth;
        height = textureHeight;
        surfaceTexture.setDefaultBufferSize(width, height);

        int[] ids = new int[1];
        GLES30.glGenFramebuffers(1, ids, 0);
        framebufferId = ids[0];
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        samplerLocation = GLES30.glGetUniformLocation(programId, "sTexture");
        transformLocation = GLES30.glGetUniformLocation(programId, "textureTransform");
        // Reference geometry: four vertices, top-left first, TRIANGLE_STRIP.
        // Use GL bottom-left UVs before the producer's crop/transform. The
        // reference's top-left UVs already flipped Y; combining those with
        // SurfaceTexture's Y flip inverted the final Unity image on PICO.
        float[] vertices = {-1, 1, 0, 1,  1, 1, 1, 1,
                            -1,-1, 0, 0,  1,-1, 1, 0};
        FloatBuffer data = ByteBuffer.allocateDirect(vertices.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        data.put(vertices).position(0);
        int oldVao = getInteger(GLES30.GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = getInteger(GLES30.GL_ARRAY_BUFFER_BINDING);
        try {
            GLES30.glGenVertexArrays(1, ids, 0);
            vertexArrayId = ids[0];
            GLES30.glBindVertexArray(vertexArrayId);
            GLES30.glGenBuffers(1, ids, 0);
            vertexBufferId = ids[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferId);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.length * 4, data, GLES30.GL_STATIC_DRAW);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8);
        } finally {
            GLES30.glBindVertexArray(oldVao);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, oldBuffer);
        }
        framebufferErrorLogged = false;
        Log.i(TAG, "BuildTexture unityTexture=" + unityTextureId +
            " size=" + width + "x" + height);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture ignored) {
        // Latest-frame priority: one updateTexImage acquires the newest buffer.
        frameAvailable.set(true);
    }

    public boolean isUpdateFrame() {
        return frameAvailable.get();
    }

    public void updateTexture() {
        if (released || !frameAvailable.compareAndSet(true, false)) {
            return;
        }

        requireContext();
        GlState state = new GlState();
        try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(textureTransform);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, unityTextureId, 0);

            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                if (!framebufferErrorLogged) {
                    Log.e(TAG, "Framebuffer incomplete: status=" + status +
                        " unityTexture=" + unityTextureId);
                    framebufferErrorLogged = true;
                }
                throw new IllegalStateException("Framebuffer incomplete: " + status);
            }

            GLES30.glViewport(0, 0, width, height);
            for (int capability : GlState.CAPABILITIES) GLES30.glDisable(capability);
            GLES30.glColorMask(true, true, true, true);
            GLES30.glBindVertexArray(vertexArrayId);
            GLES30.glUseProgram(programId);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindSampler(0, 0);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES30.glUniform1i(samplerLocation, 0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "diagnosticUv"), "uv".equals(diagnosticMode) ? 1 : 0);
            GLES30.glUniformMatrix4fv(transformLocation, 1, false, textureTransform, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            if ("fbo".equals(diagnosticMode)) {
                float[] oldClear = new float[4];
                GLES30.glGetFloatv(GLES30.GL_COLOR_CLEAR_VALUE, oldClear, 0);
                GLES30.glClearColor(1, 0, 1, 1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                GLES30.glClearColor(oldClear[0],oldClear[1],oldClear[2],oldClear[3]);
            }
            String capturePath = diagnosticPath;
            if (capturePath != null && diagnosticWarmup-- <= 0 && ("uv".equals(diagnosticMode) || "fbo".equals(diagnosticMode) || "video".equals(diagnosticMode))) {
                diagnosticPath = null;
                Log.i(TAG, "DIAGNOSTIC timestamp=" + surfaceTexture.getTimestamp() + " transform=" + java.util.Arrays.toString(textureTransform));
                if ("video".equals(diagnosticMode)) {
                    final android.graphics.Bitmap source = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
                    android.view.PixelCopy.request(surface, source, result -> {
                        Log.i(TAG, "DIAGNOSTIC PixelCopy=" + result);
                        try (java.io.FileOutputStream output = new java.io.FileOutputStream(capturePath + ".surface.png")) {
                            if (result == android.view.PixelCopy.SUCCESS) source.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
                        } catch (java.io.IOException error) { Log.e(TAG,"PixelCopy save",error); }
                        finally { source.recycle(); }
                    }, new android.os.Handler(android.os.Looper.getMainLooper()));
                }
                int oldPack = getInteger(GLES30.GL_PIXEL_PACK_BUFFER_BINDING);
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
                ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
                GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels);
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, oldPack);
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(pixels);
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(capturePath)) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                    Log.i(TAG, "DIAGNOSTIC saved " + capturePath + " texture=" + unityTextureId);
                } catch (java.io.IOException error) { Log.e(TAG, "Diagnostic capture failed", error); }
                finally { bitmap.recycle(); }
            }
            int error = GLES30.glGetError();
            if (error != GLES30.GL_NO_ERROR) {
                // GL errors are context-wide: this may also report an earlier
                // caller's error. Conservatively do not count this copy as good.
                throw new IllegalStateException("GL error observed after FBO draw: " + error);
            }
        } finally {
            state.restore();
        }
    }

    public Surface getSurface() {
        return surface;
    }

    public SurfaceTexture getSurfaceTexture() {
        return surfaceTexture;
    }

    public void release() {
        if (released) return;
        requireContext();
        frameAvailable.set(false);
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (programId != 0) {
            GLES30.glDeleteProgram(programId);
            programId = 0;
        }
        if (vertexBufferId != 0) {
            GLES30.glDeleteBuffers(1, new int[] {vertexBufferId}, 0);
            vertexBufferId = 0;
        }
        if (vertexArrayId != 0) {
            GLES30.glDeleteVertexArrays(1, new int[] {vertexArrayId}, 0);
            vertexArrayId = 0;
        }
        if (framebufferId != 0) {
            int[] ids = {framebufferId};
            GLES30.glDeleteFramebuffers(1, ids, 0);
            framebufferId = 0;
        }
        if (oesTextureId != 0) {
            int[] ids = {oesTextureId};
            GLES30.glDeleteTextures(1, ids, 0);
            oesTextureId = 0;
        }
        unityTextureId = 0;
        released = true;
        Log.i(TAG, "Released");
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader;
        try {
            fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        } catch (RuntimeException failure) {
            GLES30.glDeleteShader(vertexShader);
            throw failure;
        }
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertexShader);
        GLES30.glAttachShader(program, fragmentShader);
        GLES30.glLinkProgram(program);
        int[] linked = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0);
        GLES30.glDeleteShader(vertexShader);
        GLES30.glDeleteShader(fragmentShader);
        if (linked[0] == 0) {
            String info = GLES30.glGetProgramInfoLog(program);
            GLES30.glDeleteProgram(program);
            throw new IllegalStateException("FBO shader link failed: " + info);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String info = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("FBO shader compile failed: " + info);
        }
        return shader;
    }

    private void requireContext() {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current.equals(EGL14.EGL_NO_CONTEXT) || !current.equals(ownerContext)) {
            throw new IllegalStateException("FBO requires its owning GLES context");
        }
    }

    private static int getInteger(int name) {
        int[] value = new int[1];
        GLES30.glGetIntegerv(name, value, 0);
        return value[0];
    }

    /** Preserve the Unity state we touch, including error/early-return paths. */
    private static final class GlState {
        static final int[] CAPABILITIES = {GLES30.GL_BLEND, GLES30.GL_CULL_FACE,
            GLES30.GL_DEPTH_TEST, GLES30.GL_STENCIL_TEST, GLES30.GL_SCISSOR_TEST,
            GLES30.GL_RASTERIZER_DISCARD, GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE,
            GLES30.GL_SAMPLE_COVERAGE};
        final int drawFbo = getInteger(GLES30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int readFbo = getInteger(GLES30.GL_READ_FRAMEBUFFER_BINDING);
        final int program = getInteger(GLES30.GL_CURRENT_PROGRAM);
        final int vao = getInteger(GLES30.GL_VERTEX_ARRAY_BINDING);
        final int activeTexture = getInteger(GLES30.GL_ACTIVE_TEXTURE);
        final int activeOes = getInteger(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES);
        final int[] viewport = new int[4];
        final boolean[] colorMask = new boolean[4];
        final boolean[] enabled = new boolean[CAPABILITIES.length];
        final int unitZeroOes;
        final int unitZeroSampler;

        GlState() {
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);
            GLES30.glGetBooleanv(GLES30.GL_COLOR_WRITEMASK, colorMask, 0);
            for (int i = 0; i < enabled.length; i++) enabled[i] = GLES30.glIsEnabled(CAPABILITIES[i]);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            unitZeroOes = getInteger(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES);
            unitZeroSampler = getInteger(GLES30.GL_SAMPLER_BINDING);
            GLES30.glActiveTexture(activeTexture);
        }

        void restore() {
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFbo);
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLES30.glUseProgram(program);
            GLES30.glBindVertexArray(vao);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, unitZeroOes);
            GLES30.glBindSampler(0, unitZeroSampler);
            GLES30.glActiveTexture(activeTexture);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, activeOes);
            GLES30.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            for (int i = 0; i < enabled.length; i++) {
                if (enabled[i]) GLES30.glEnable(CAPABILITIES[i]);
                else GLES30.glDisable(CAPABILITIES[i]);
            }
        }
    }
}
