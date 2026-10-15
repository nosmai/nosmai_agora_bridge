package com.example.nosmai_agora_bridge;

import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;
import io.agora.base.internal.video.YuvConverter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import io.agora.base.JavaI420Buffer;
import io.agora.base.TextureBuffer;
import io.agora.base.TextureBufferHelper;
import io.agora.base.VideoFrame;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.gl.EglBaseProvider;

/** Bounded GPU copies converted to owned I420 buffers for Agora submission. */
final class AgoraTextureBridge {
    private static final class Slot {
        final int texture, fbo, width, height;
        boolean busy;
        Slot(int texture, int fbo, int width, int height) {
            this.texture = texture; this.fbo = fbo;
            this.width = width; this.height = height;
        }
    }
    private final TextureBufferHelper helper;
    private final RtcEngine engine;
    // Bound memory while consumers retain frames. Owned I420 delivery avoids
    // texture-retention stalls observed with Agora 4.5.2.
    static final int MAX_IN_FLIGHT_TEXTURES = 3;
    private final Slot[] slots = new Slot[MAX_IN_FLIGHT_TEXTURES];
    private final AtomicBoolean pending = new AtomicBoolean();
    private final CompletableFuture<Void> submissionsDrained = new CompletableFuture<>();
    private volatile boolean released;
    private boolean helperDisposed;
    private int srcFbo;
    private YuvConverter yuvConverter;

    AgoraTextureBridge(TextureBufferHelper helper, RtcEngine engine) {
        this.helper = helper; this.engine = engine;
    }

    synchronized void pushCopy(int texture, int width, int height, long timestampNs,
                  Runnable onSourceDropped) {
        if (released || !pending.compareAndSet(false, true)) {
            onSourceDropped.run();
            return;
        }
        // No executor backlog and no blocking invoke on the SDK render worker.
        if (!helper.getHandler().post(() -> {
            boolean sourceCompleted = false;
            boolean submissionQueued = false;
            try {
                if (released) return;
                int index = acquire(width, height);
                if (index < 0) return;
                final Slot slot = slots[index];
                if (!SdkTextureFrames.waitFor(texture)) return;
                if (srcFbo == 0) {
                    int[] id = new int[1]; GLES20.glGenFramebuffers(1, id, 0); srcFbo = id[0];
                }
                try {
                    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo);
                    GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER,
                            GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
                    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, slot.fbo);
                    if (GLES30.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE ||
                        GLES30.glCheckFramebufferStatus(GLES30.GL_DRAW_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
                        throw new IllegalStateException("Streaming framebuffer is incomplete");
                    GLES30.glBlitFramebuffer(0, 0, width, height, 0, height, width, 0,
                            GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST);
                } finally {
                    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, srcFbo);
                    GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER,
                            GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0);
                    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
                    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
                    // The SDK records a consumer fence here, in this GL context.
                    // A CPU callback alone would not prove that the copy finished.
                    SdkTextureFrames.complete(texture);
                    sourceCompleted = true;
                }
                if (released) return;
                slot.busy = true;
                long ready = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (ready == 0) GLES20.glFinish();
                else GLES20.glFlush();
                submissionQueued = true;
                submitWhenReady(slot, timestampNs, ready);
            } catch (Throwable error) {
                Log.e("AgoraTexBridge", "Texture delivery failed", error);
            } finally {
                if (!sourceCompleted) onSourceDropped.run();
                if (!submissionQueued) pending.set(false);
            }
        })) {
            pending.set(false);
            onSourceDropped.run();
        }
    }

    private void submitWhenReady(Slot slot, long timestampNs, long fence) {
        // Establish completion before handing a texture to Agora, without a
        // blocking glFinish on the normal frame path or an undocumented fence ABI.
        if (!released && fence != 0) {
            int status = GLES30.glClientWaitSync(fence, 0, 0);
            if (status == GLES30.GL_TIMEOUT_EXPIRED) {
                helper.getHandler().postDelayed(() -> submitWhenReady(slot, timestampNs, fence), 1);
                return;
            }
            if (status == GLES30.GL_WAIT_FAILED) {
                GLES30.glDeleteSync(fence);
                slot.busy = false;
                pending.set(false);
                return;
            }
        }
        if (fence != 0) GLES30.glDeleteSync(fence);
        TextureBuffer textureBuffer = null;
        VideoFrame.I420Buffer buffer = null;
        try {
            if (released) return;
            if (yuvConverter == null) yuvConverter = new YuvConverter();
            textureBuffer = new TextureBuffer(
                    EglBaseProvider.getCurrentEglContext(), slot.width, slot.height,
                    VideoFrame.TextureBuffer.Type.RGB, slot.texture, new Matrix(),
                    helper.getHandler(), yuvConverter,
                    () -> helper.getHandler().post(() -> {
                        slot.busy = false;
                        if (released) cleanup();
                    }));
            // Conversion runs on our GL worker after the copy fence. The source
            // SDK slot is already returned, so readback never blocks its render
            // callback. Only the streaming handoff uses CPU-addressable pixels.
            buffer = convertForSubmission(textureBuffer);
            textureBuffer = null; // Ownership transferred to buffer's callback.
            VideoFrame frame = new VideoFrame(buffer, 0, timestampNs);
            engine.pushExternalVideoFrame(frame);
        } catch (Throwable error) {
            Log.e("AgoraTexBridge", "Texture submission failed", error);
        } finally {
            if (buffer != null) buffer.release();
            else if (textureBuffer != null) textureBuffer.release();
            else slot.busy = false;
            pending.set(false);
            if (released) cleanup();
        }
    }

    private VideoFrame.I420Buffer convertForSubmission(TextureBuffer texture) {
        VideoFrame.I420Buffer converted = texture.toI420();
        if (converted == null) throw new IllegalStateException("Texture conversion failed");
        try {
            // Keep both pixel storage and its slot alive for any asynchronous
            // consumer. Never infer ownership from pushExternalVideoFrame's bool.
            return JavaI420Buffer.wrap(converted.getWidth(), converted.getHeight(),
                    converted.getDataY(), converted.getStrideY(),
                    converted.getDataU(), converted.getStrideU(),
                    converted.getDataV(), converted.getStrideV(), () -> {
                        try { converted.release(); }
                        finally { texture.release(); }
                    });
        } catch (Throwable error) {
            converted.release();
            throw error;
        }
    }

    // All slot allocation/recycling/destruction happens on the helper GL thread.
    private int acquire(int width, int height) {
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            if (slot != null && slot.busy) continue;
            if (slot == null || slot.width != width || slot.height != height) {
                if (slot != null) delete(slot);
                int[] tex = new int[1], fbo = new int[1];
                GLES20.glGenTextures(1, tex, 0);
                GLES20.glGenFramebuffers(1, fbo, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                        0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, tex[0], 0);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                slots[i] = new Slot(tex[0], fbo[0], width, height);
            }
            return i;
        }
        return -1;
    }

    private void delete(Slot slot) {
        GLES20.glDeleteFramebuffers(1, new int[]{slot.fbo}, 0);
        GLES20.glDeleteTextures(1, new int[]{slot.texture}, 0);
    }

    private void cleanup() {
        if (pending.get()) return;
        boolean held = false;
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            if (slot == null) continue;
            if (slot.busy) { held = true; continue; }
            delete(slot); slots[i] = null;
        }
        if (srcFbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{srcFbo}, 0); srcFbo = 0;
        }
        if (!held && !helperDisposed) {
            helperDisposed = true;
            // Keep conversion resources and the GL thread alive until all
            // submitted buffers have returned their slots.
            if (yuvConverter != null) {
                yuvConverter.release();
                yuvConverter = null;
            }
            helper.dispose();
        }
    }

    // Completion guarantees no more engine submissions, not final encoder release.
    // The helper stays alive for outstanding buffers and disposes on final release.
    synchronized CompletableFuture<Void> release() {
        if (!released) {
            released = true;
            if (!helper.getHandler().post(() -> {
                cleanup();
                submissionsDrained.complete(null);
            })) submissionsDrained.completeExceptionally(new IllegalStateException("Helper stopped before drain"));
        }
        return submissionsDrained;
    }
}
