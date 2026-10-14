from pathlib import Path
import subprocess, shutil, tempfile

root = Path(__file__).resolve().parents[1]
temporary = tempfile.TemporaryDirectory(prefix='nosmai-stream-tests-')
src = Path(temporary.name) / 'java'
def write(name, value):
    p = src / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(value)

write('android/graphics/Matrix.java', 'package android.graphics; public class Matrix {}')
write('android/util/Log.java', '''package android.util; public class Log {
public static int i(String a,String b){return 0;} public static int w(String a,String b){return 0;}
public static int e(String a,String b,Throwable t){t.printStackTrace();return 0;} }''')
write('android/os/SystemClock.java', '''package android.os; public class SystemClock {
public static long now=1000; public static long uptimeMillis(){return now;} }''')
write('android/opengl/GLES20.java', '''package android.opengl; public class GLES20 {
public static final int GL_TEXTURE_2D=1,GL_COLOR_BUFFER_BIT=2,GL_NEAREST=3,GL_RGBA=4,GL_UNSIGNED_BYTE=5,
GL_TEXTURE_MIN_FILTER=6,GL_TEXTURE_MAG_FILTER=7,GL_LINEAR=8,GL_TEXTURE_WRAP_S=9,GL_TEXTURE_WRAP_T=10,
GL_CLAMP_TO_EDGE=11,GL_FRAMEBUFFER=12,GL_COLOR_ATTACHMENT0=13;
static int id=100; public static void glGenFramebuffers(int n,int[] a,int o){for(int i=0;i<n;i++)a[o+i]=id++;}
public static void glGenTextures(int n,int[] a,int o){for(int i=0;i<n;i++)a[o+i]=id++;}
public static final java.util.Set<Integer> deleted=new java.util.HashSet<>(); public static void glDeleteTextures(int n,int[] a,int o){for(int i=0;i<n;i++)deleted.add(a[o+i]);} public static void glDeleteFramebuffers(int n,int[] a,int o){}
public static void glBindTexture(int t,int x){} public static void glTexParameteri(int t,int p,int v){}
public static void glTexImage2D(int a,int b,int c,int d,int e,int f,int g,int h,Object i){}
public static void glBindFramebuffer(int t,int x){} public static void glFramebufferTexture2D(int a,int b,int c,int d,int e){}
public static void glFlush(){} public static void glFinish(){} }''')
write('android/opengl/GLES30.java', """package android.opengl; public class GLES30 extends GLES20 {
public static final int GL_READ_FRAMEBUFFER=21, GL_DRAW_FRAMEBUFFER=22,GL_FRAMEBUFFER_COMPLETE=23,
GL_SYNC_GPU_COMMANDS_COMPLETE=24,GL_TIMEOUT_EXPIRED=25,GL_WAIT_FAILED=26;
public static boolean ready=true,failed=false; public static int copies;
public static int glCheckFramebufferStatus(int a){return GL_FRAMEBUFFER_COMPLETE;}
public static long glFenceSync(int a,int b){return 1;}
public static int glClientWaitSync(long a,int b,long c){return failed?GL_WAIT_FAILED:ready?27:GL_TIMEOUT_EXPIRED;}
public static void glDeleteSync(long a){}
public static void glBlitFramebuffer(int a,int b,int c,int d,int e,int f,int g,int h,int i,int j){copies++;} }""")
write('android/os/Handler.java', """package android.os; import java.util.*;
public class Handler { public final Queue<Runnable> work=new ArrayDeque<>(); public boolean stopped;
public boolean post(Runnable r){if(stopped)return false;work.add(r);return true;}
public boolean postDelayed(Runnable r,long ms){return post(r);}
public void one(){Runnable r=work.poll();if(r!=null)r.run();}
public void drain(){int i=0;while(!work.isEmpty()){if(++i>100)throw new AssertionError("queue did not drain");one();}}
}""")
write('io/agora/base/VideoFrame.java', '''package io.agora.base; public class VideoFrame {
public interface Buffer {void retain();void release();}
public interface TextureBuffer {enum Type {RGB}}
public final Buffer buffer; public VideoFrame(Buffer b,int r,long ts){buffer=b;} public void release(){buffer.release();} }''')
write('io/agora/base/TextureBuffer.java', '''package io.agora.base; public class TextureBuffer implements VideoFrame.Buffer {
public final int id; int refs=1; Runnable release;
public TextureBuffer(Object e,int w,int h,VideoFrame.TextureBuffer.Type t,int id,android.graphics.Matrix m,Object handler,Object yuv,Runnable r){this.id=id;release=r;}
public void retain(){refs++;} public void release(){if(--refs==0)release.run();} }''')
write('io/agora/base/TextureBufferHelper.java', """package io.agora.base;
public class TextureBufferHelper { public final android.os.Handler handler=new android.os.Handler(); public int disposed;
public android.os.Handler getHandler(){return handler;}
public void dispose(){disposed++;handler.stopped=true;}
}""")
write('io/agora/rtc2/RtcEngine.java', '''package io.agora.rtc2;
import io.agora.base.*; import java.util.*;
public class RtcEngine {public final List<VideoFrame> held=new ArrayList<>();
public boolean pushExternalVideoFrame(VideoFrame f){f.buffer.retain();held.add(f);return true;} }''')
write('io/agora/rtc2/gl/EglBaseProvider.java', 'package io.agora.rtc2.gl; public class EglBaseProvider {public static Object getCurrentEglContext(){return null;}}')

write('com/nosmai/effect/api/NosmaiSDK.java', """package com.nosmai.effect.api;
import java.util.*; public class NosmaiSDK {
public static final Map<Integer,Integer> released=new HashMap<>(); public static boolean shares=true,wait=true;
public static boolean isAgoraShareContext(long h){return shares;}
public static boolean waitStreamFrame(int t){return wait;}
public static void completeStreamFrame(int t){releaseStreamSlot(t);}
public static void releaseStreamSlot(int t){released.put(t,released.getOrDefault(t,0)+1);}
}""")
for name in ('AgoraTextureBridge.java','SdkTextureFrames.java'):
    target = src / 'com/example/nosmai_agora_bridge' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / 'android/src/main/java/com/example/nosmai_agora_bridge' / name, target)
write('com/example/nosmai_agora_bridge/ReviewHarness.java', """package com.example.nosmai_agora_bridge;
import io.agora.base.*; import io.agora.rtc2.*; import android.opengl.*;
import com.nosmai.effect.api.NosmaiSDK; import java.util.*;
public class ReviewHarness {
static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
static int next=900;
static int push(AgoraTextureBridge b,int w,int h){int id=next++; b.pushCopy(id,w,h,1,()->NosmaiSDK.releaseStreamSlot(id));return id;}
static int texture(VideoFrame f){return ((TextureBuffer)f.buffer).id;}
public static void main(String[] args)throws Exception{
TextureBufferHelper h=new TextureBufferHelper(); RtcEngine e=new RtcEngine(); AgoraTextureBridge b=new AgoraTextureBridge(h,e);
for(int i=0;i<4;i++){int source=push(b,720,1280);h.handler.drain();check(NosmaiSDK.released.get(source)==1,"source released once");}
check(e.held.size()==3,"full ring drops fourth frame");
check(e.held.stream().map(ReviewHarness::texture).distinct().count()==3,"held textures never reused");
int first=texture(e.held.get(0));e.held.remove(0).release();h.handler.drain();
push(b,480,640);h.handler.drain();check(GLES20.deleted.contains(first),"free resized texture deleted");
for(VideoFrame f:e.held)check(!GLES20.deleted.contains(texture(f)),"resize must not delete retained texture");
b.release();h.handler.drain();check(h.disposed==0,"helper stays alive while encoder holds textures");
for(VideoFrame f:e.held)f.release();h.handler.drain();check(h.disposed==1,"helper disposes after final release");
check(b.release().isDone(),"repeat release idempotent");

TextureBufferHelper h2=new TextureBufferHelper();RtcEngine e2=new RtcEngine();AgoraTextureBridge b2=new AgoraTextureBridge(h2,e2);
int queued=push(b2,720,1280);int dropped=push(b2,720,1280);b2.release();h2.handler.drain();
check(e2.held.isEmpty(),"queued copy must not push after stop");
check(NosmaiSDK.released.get(queued)==1 && NosmaiSDK.released.get(dropped)==1,"queued/busy source release once");
check(h2.disposed==1,"queued stop cleans helper");

TextureBufferHelper h3=new TextureBufferHelper();RtcEngine e3=new RtcEngine();AgoraTextureBridge b3=new AgoraTextureBridge(h3,e3);
GLES30.ready=false;int deferred=push(b3,720,1280);h3.handler.one();
check(e3.held.isEmpty(),"no submission before GPU completion");check(NosmaiSDK.released.get(deferred)==1,"source fenced before deferred submit");
GLES30.ready=true;h3.handler.drain();check(e3.held.size()==1,"completed GPU frame submitted");
e3.held.remove(0).release();h3.handler.drain();
GLES30.ready=false;push(b3,720,1280);h3.handler.one();b3.release();h3.handler.drain();
check(e3.held.isEmpty() && h3.disposed==1,"stop cancels fence polling without late submission");GLES30.ready=true;

TextureBufferHelper h4=new TextureBufferHelper();RtcEngine e4=new RtcEngine();AgoraTextureBridge b4=new AgoraTextureBridge(h4,e4);
SdkTextureFrames.shares(1);NosmaiSDK.wait=false;int invalid=push(b4,720,1280);h4.handler.drain();
check(e4.held.isEmpty() && NosmaiSDK.released.get(invalid)==1,"failed producer wait safely drops source");NosmaiSDK.wait=true;
GLES30.failed=true;push(b4,720,1280);h4.handler.drain();check(e4.held.isEmpty(),"failed GPU sync not published");GLES30.failed=false;
push(b4,720,1280);h4.handler.drain();check(e4.held.size()==1,"sync error permits next frame");
b4.release();h4.handler.drain();e4.held.get(0).release();h4.handler.drain();
System.out.println("PASS: retained textures, bounded queue, resize, source releases, GPU readiness, stop cancellation, deferred cleanup and sync recovery");
}
}""")
classes = Path(temporary.name) / 'classes'
subprocess.run(['javac','-d',str(classes),*[str(p) for p in src.rglob('*.java')]],check=True)
subprocess.run(['java','-cp',str(classes),'com.example.nosmai_agora_bridge.ReviewHarness'],check=True,timeout=15)
temporary.cleanup()
