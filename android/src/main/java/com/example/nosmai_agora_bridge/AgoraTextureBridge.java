package com.example.nosmai_agora_bridge;

import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import io.agora.base.TextureBuffer;
import io.agora.base.TextureBufferHelper;
import io.agora.base.VideoFrame;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.gl.EglBaseProvider;

/**
 * Copies a Nosmai GL texture into an Agora-helper-owned texture on Agora's GL
 * thread, then pushes that copy as an external video frame. This avoids CPU
 * readback and avoids handing a foreign render-thread texture directly to the
 * encoder.
 */
final class AgoraTextureBridge {
    private static final String TAG = "AgoraTexBridge";
    private static final int DST_RING_SIZE = 3;
    private static final long DST_SLOT_STALL_MS = 500;

    private final TextureBufferHelper helper;
    private final RtcEngine engine;
    private final int[] tex = new int[DST_RING_SIZE];
    private final int[] fbo = new int[DST_RING_SIZE];
    private final AtomicBoolean[] dstBusy = new AtomicBoolean[DST_RING_SIZE];
    private final long[] dstBusySince = new long[DST_RING_SIZE];
    private final java.util.concurrent.ExecutorService pushWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "nosmai-agora-push"));
    private final java.util.concurrent.atomic.AtomicBoolean workerBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private int ringW = 0;
    private int ringH = 0;
    private int srcFbo = 0;
    private volatile boolean released = false;
    private int pushCount = 0;

    AgoraTextureBridge(TextureBufferHelper helper, RtcEngine engine) {
        this.helper = helper;
        this.engine = engine;
        for (int i = 0; i < DST_RING_SIZE; i++) {
            dstBusy[i] = new AtomicBoolean(false);
        }
    }

    void pushCopy(final int srcTexId, final int width, final int height,
                  final long timestampNs, final Runnable onSourceConsumed) {
        if (helper == null || engine == null || released) {
            if (onSourceConsumed != null) onSourceConsumed.run();
            return;
        }
        if (!workerBusy.compareAndSet(false, true)) {
            if (onSourceConsumed != null) onSourceConsumed.run();
            return;
        }
        try {
            pushWorker.execute(() -> {
                boolean sourceReleased = false;
                try {
                    helper.invoke((Callable<Void>) () -> {
                        ensureRing(width, height);
                        final int slot = acquireDstSlot();
                        if (slot < 0) {
                            if ((pushCount++ % 60) == 0) {
                                Log.w(TAG, "Dropping texture frame: Agora helper ring busy");
                            }
                            return null;
                        }

                        if (srcFbo == 0) {
                            int[] f = new int[1];
                            GLES20.glGenFramebuffers(1, f, 0);
                            srcFbo = f[0];
                        }

                        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo);
                        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER,
                                GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, srcTexId, 0);
                        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo[slot]);
                        GLES30.glBlitFramebuffer(0, 0, width, height,
                                0, height, width, 0,
                                GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST);
                        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER,
                                GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0);
                        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
                        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
                        GLES20.glFlush();

                        VideoFrame frame = null;
                        try {
                            VideoFrame.Buffer buffer = new TextureBuffer(
                                    EglBaseProvider.getCurrentEglContext(),
                                    width, height,
                                    VideoFrame.TextureBuffer.Type.RGB,
                                    tex[slot],
                                    new Matrix(),
                                    helper.getHandler(),
                                    null,
                                    () -> { dstBusySince[slot] = 0; dstBusy[slot].set(false); });
                            frame = new VideoFrame(buffer, 0, timestampNs);
                            boolean pushed = engine.pushExternalVideoFrame(frame);
                            pushCount++;
                            if (!pushed && (pushCount % 60 == 0)) {
                                Log.w(TAG, "pushExternalVideoFrame rejected frame #" + pushCount);
                            }
                        } finally {
                            if (frame != null) {
                                frame.release();
                            }
                            dstBusySince[slot] = 0;
                            dstBusy[slot].set(false);
                        }
                        return null;
                    });
                    if (onSourceConsumed != null) onSourceConsumed.run();
                    sourceReleased = true;
                } catch (Throwable t) {
                    Log.e(TAG, "pushCopy failed", t);
                    if (!sourceReleased && onSourceConsumed != null) onSourceConsumed.run();
                } finally {
                    workerBusy.set(false);
                }
            });
        } catch (Throwable t) {
            if (onSourceConsumed != null) onSourceConsumed.run();
            workerBusy.set(false);
        }
    }

    private void ensureRing(int width, int height) {
        if (ringW == width && ringH == height && tex[0] != 0) return;
        if (tex[0] != 0) {
            GLES20.glDeleteTextures(DST_RING_SIZE, tex, 0);
            GLES20.glDeleteFramebuffers(DST_RING_SIZE, fbo, 0);
        }
        GLES20.glGenTextures(DST_RING_SIZE, tex, 0);
        GLES20.glGenFramebuffers(DST_RING_SIZE, fbo, 0);
        for (int i = 0; i < DST_RING_SIZE; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex[i], 0);
            dstBusy[i].set(false);
            dstBusySince[i] = 0;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        ringW = width;
        ringH = height;
        Log.i(TAG, "Created helper dst texture " + width + "x" + height);
    }

    private int acquireDstSlot() {
        final long now = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < DST_RING_SIZE; i++) {
            if (dstBusy[i].compareAndSet(false, true)) {
                dstBusySince[i] = now;
                return i;
            }
        }
        int oldest = -1;
        long oldestSince = Long.MAX_VALUE;
        for (int i = 0; i < DST_RING_SIZE; i++) {
            long since = dstBusySince[i];
            if (since != 0 && (now - since) > DST_SLOT_STALL_MS && since < oldestSince) {
                oldestSince = since;
                oldest = i;
            }
        }
        if (oldest >= 0) {
            Log.w(TAG, "Reclaiming stalled Agora ring slot " + oldest
                    + " (busy " + (now - oldestSince) + "ms)");
            dstBusySince[oldest] = now;
            return oldest;
        }
        return -1;
    }

    void release() {
        released = true;
        pushWorker.shutdown();
        if (helper == null) return;
        try {
            helper.invoke((Callable<Void>) () -> {
                if (tex[0] != 0) {
                    GLES20.glDeleteTextures(DST_RING_SIZE, tex, 0);
                    GLES20.glDeleteFramebuffers(DST_RING_SIZE, fbo, 0);
                    for (int i = 0; i < DST_RING_SIZE; i++) {
                        tex[i] = 0;
                        fbo[i] = 0;
                        dstBusy[i].set(false);
                    }
                }
                if (srcFbo != 0) {
                    GLES20.glDeleteFramebuffers(1, new int[]{srcFbo}, 0);
                    srcFbo = 0;
                }
                ringW = 0;
                ringH = 0;
                return null;
            });
        } catch (Throwable ignored) {
        }
    }
}
