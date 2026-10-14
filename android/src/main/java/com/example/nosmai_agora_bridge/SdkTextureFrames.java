package com.example.nosmai_agora_bridge;

import android.opengl.GLES20;
import com.nosmai.effect.api.NosmaiSDK;
import java.lang.reflect.Method;

/** Optional synchronized texture protocol; older SDKs use the I420 fallback. */
final class SdkTextureFrames {
    private static final Method SHARE, WAIT, COMPLETE;
    static {
        Method share = null, wait = null, complete = null;
        try {
            share = NosmaiSDK.class.getMethod("isAgoraShareContext", long.class);
            wait = NosmaiSDK.class.getMethod("waitStreamFrame", int.class);
            complete = NosmaiSDK.class.getMethod("completeStreamFrame", int.class);
        } catch (ReflectiveOperationException ignored) { }
        SHARE = share; WAIT = wait; COMPLETE = complete;
    }
    static boolean shares(long handle) {
        if (SHARE == null || WAIT == null || COMPLETE == null) return false;
        try { return Boolean.TRUE.equals(SHARE.invoke(null, handle)); }
        catch (ReflectiveOperationException | LinkageError error) { return false; }
    }
    static boolean waitFor(int texture) throws ReflectiveOperationException {
        return WAIT != null && Boolean.TRUE.equals(WAIT.invoke(null, texture));
    }
    static void complete(int texture) {
        try {
            if (COMPLETE == null) throw new IllegalStateException("Texture protocol unavailable");
            COMPLETE.invoke(null, texture);
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException error) {
            // A failed optional JNI call must not free a texture before its GPU read.
            GLES20.glFinish();
            NosmaiSDK.releaseStreamSlot(texture);
        }
    }
}
