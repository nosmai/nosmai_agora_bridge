#!/usr/bin/env python3
"""Compile the actual controller against deterministic RTC/SDK fakes; no device needed.
Uses Kotlin compiler jars from the local Gradle cache (populate by building Android).
"""
from pathlib import Path
import os
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
def jar(group, name, version):
    return next(p for p in (cache/group/name/version).glob('*/*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.0')
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.0'), stdlib,
          jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.0'),
          jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
          jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
          jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),
          jar('org.jetbrains','annotations','13.0')]
with tempfile.TemporaryDirectory(prefix='nosmai-controller-tests-') as directory:
    tmp=Path(directory)
    def write(name, content): (tmp/name).write_text(content)
    write('Context.kt', 'package android.content\nopen class Context {val applicationContext: Context get()=this}\n')
    write('Log.kt', 'package android.util\nobject Log {fun e(t:String,m:String,e:Throwable?=null)=0;fun w(t:String,m:String,e:Throwable)=0}\n')
    write('SDK.kt', '''package com.nosmai.effect.api
object NosmaiSDK {
 enum class RenderMode { DUAL_OUTPUT,PREVIEW_ONLY }
 class FrameData(val pixelBuffer:ByteArray,val width:Int,val height:Int,val timestampNs:Long=1,val format:Int=1)
 fun interface FrameCallback {fun onFrame(frame:FrameData?)}
 fun interface TextureCallback {fun onFrame(t:Int,w:Int,h:Int,ts:Long,fence:Long)}
 var frame:FrameCallback?=null;var texture:TextureCallback?=null;var mode=RenderMode.PREVIEW_ONLY
 fun setAgoraShareContext(h:Long){};fun setTextureFrameCallback(c:TextureCallback?){texture=c}
 fun setFrameCallback(c:FrameCallback?){frame=c};fun setRenderMode(m:RenderMode){mode=m}
 fun releaseStreamSlot(t:Int){}
}
''')
    write('Frames.kt', '''package io.agora.base
import java.nio.ByteBuffer
class VideoFrame(val buffer:JavaI420Buffer,val rotation:Int,val timestamp:Long){fun release()=buffer.release()}
class JavaI420Buffer(val y:ByteBuffer,val u:ByteBuffer,val v:ByteBuffer,val done:()->Unit){
 private var refs=1; fun retain(){refs++};fun release(){if(--refs==0)done()}
 companion object {fun wrap(w:Int,h:Int,y:ByteBuffer,ys:Int,u:ByteBuffer,us:Int,v:ByteBuffer,vs:Int,done:()->Unit)=JavaI420Buffer(y,u,v,done)}
}
class TextureBufferHelper {companion object {fun create(s:String,c:io.agora.rtc2.gl.EglContext):TextureBufferHelper?=TextureBufferHelper()}}
''')
    write('EGL.kt', '''package io.agora.rtc2.gl
class EglContext {val nativeEglContext=1L}
class Root {val eglBaseContext=EglContext()}
class EglBaseProvider {val rootEglBase=Root();companion object{fun instance()=EglBaseProvider()}}
''')
    write('Config.kt', '''package io.agora.rtc2.video
class VideoEncoderConfiguration(d:VideoDimensions,f:FRAME_RATE,b:Int,o:ORIENTATION_MODE){
 class VideoDimensions(w:Int,h:Int);enum class FRAME_RATE {FRAME_RATE_FPS_30};enum class ORIENTATION_MODE{ORIENTATION_MODE_FIXED_PORTRAIT}
 enum class MIRROR_MODE_TYPE{MIRROR_MODE_DISABLED};var minBitrate=0;var mirrorMode=MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
}
''')
    write('RTC.kt', '''package io.agora.rtc2
import io.agora.base.VideoFrame
open class IRtcEngineEventHandler {class RtcStats;open fun onJoinChannelSuccess(channel:String?,uid:Int,elapsed:Int){};open fun onLeaveChannel(stats:RtcStats?){};open fun onError(err:Int){}}
class RtcEngineConfig {var mAppId:String?=null;var mContext:android.content.Context?=null;var mEventHandler:IRtcEngineEventHandler?=null}
object Constants {const val VIDEO_MIRROR_MODE_DISABLED=0;const val CLIENT_ROLE_BROADCASTER=1;const val CHANNEL_PROFILE_LIVE_BROADCASTING=1;enum class ExternalVideoSourceType {VIDEO_FRAME}}
class ChannelMediaOptions {var clientRoleType=0;var channelProfile=0;var publishCameraTrack=true;var publishCustomVideoTrack=false;var publishCustomAudioTrack=false;var autoSubscribeVideo=false;var autoSubscribeAudio=false}
class RtcEngine(val events:IRtcEngineEventHandler){
 val nativeHandle=123L;var joins=0;var leaves=0;var joinResult=0;var leaveResult=0;var joinedName="";var autoJoin=false
 @Volatile var pushes=0;val frames=java.util.concurrent.CopyOnWriteArrayList<VideoFrame>();var external=false
 fun enableVideo()=0;fun setLocalVideoMirrorMode(m:Int)=0;fun setVideoEncoderConfiguration(c:io.agora.rtc2.video.VideoEncoderConfiguration)=0
 fun setExternalVideoSource(e:Boolean,t:Boolean,s:Constants.ExternalVideoSourceType):Int{external=e;return 0};fun setClientRole(r:Int)=0
 fun joinChannel(token:String?,name:String,uid:Int,o:ChannelMediaOptions):Int{joins++;joinedName=name;if(autoJoin&&joinResult==0)events.onJoinChannelSuccess(name,uid,0);return joinResult}
 fun leaveChannel():Int{leaves++;return leaveResult}
 fun pushExternalVideoFrame(f:VideoFrame):Boolean{f.buffer.retain();frames.add(f);pushes++;return true}
 fun releaseFrames(){frames.forEach{it.release()};frames.clear()}
 companion object {lateinit var last:RtcEngine;var destroyed=0;fun create(c:RtcEngineConfig):RtcEngine{last=RtcEngine(c.mEventHandler!!);return last};fun destroy(){destroyed++}}
}
''')
    write('Bridge.kt', '''package com.example.nosmai_agora_bridge
import java.util.concurrent.CompletableFuture
object SdkTextureFrames {fun shares(h:Long)=false}
class AgoraTextureBridge(h:io.agora.base.TextureBufferHelper,e:io.agora.rtc2.RtcEngine){fun release():CompletableFuture<Void> = CompletableFuture.completedFuture(null);fun pushCopy(t:Int,w:Int,h:Int,ts:Long,done:()->Unit){done()}}
''')
    write('Harness.kt', '''package com.example.nosmai_agora_bridge
import android.content.Context
import com.nosmai.effect.api.NosmaiSDK
import io.agora.rtc2.RtcEngine
import java.util.concurrent.*
fun checkThat(ok:Boolean,message:String){if(!ok)throw AssertionError(message)}
fun executor(c:VideoRawDataController):ExecutorService {val f=c.javaClass.getDeclaredField("pixels");f.isAccessible=true;return f.get(c) as ExecutorService}
fun idle(c:VideoRawDataController){executor(c).submit{}.get(2,TimeUnit.SECONDS)}
fun main(){
 val c=VideoRawDataController(Context(),"app");val e=RtcEngine.last
 checkThat(c.nativeHandle()==123L,"native handle preserved")
 checkThat(!c.startStreaming(null,"",0),"blank channel rejected")
 checkThat(c.startStreaming(null,"room",1),"first join accepted")
 checkThat(c.startStreaming(null,"room",1)&&e.joins==1,"duplicate start idempotent")
 checkThat(!c.startStreaming(null,"other",2)&&e.joins==1,"cannot replace active stream")
 val data=ByteArray(6){7};val frame=NosmaiSDK.FrameData(data,2,2)
 NosmaiSDK.frame!!.onFrame(frame);idle(c);checkThat(e.pushes==0,"no publish before join success")
 e.events.onJoinChannelSuccess("room",1,0)
 val entered=CountDownLatch(1);val proceed=CountDownLatch(1)
 executor(c).execute{entered.countDown();proceed.await()};entered.await()
 NosmaiSDK.frame!!.onFrame(frame);data.fill(99);repeat(10){NosmaiSDK.frame!!.onFrame(frame)}
 proceed.countDown();idle(c)
 checkThat(e.pushes==1&&e.frames[0].buffer.y.get(0).toInt()==7,"pooled SDK array copied before callback returns; queue bounded")
 repeat(5){NosmaiSDK.frame!!.onFrame(frame);idle(c)}
 checkThat(e.pushes==3,"retained I420 storage bounded at three frames")
 e.releaseFrames();NosmaiSDK.frame!!.onFrame(frame);idle(c);checkThat(e.pushes==4,"released I420 storage reused")
 val stale=NosmaiSDK.frame!!
 checkThat(c.stopStreaming(),"stop succeeds");checkThat(!e.external&&NosmaiSDK.mode==NosmaiSDK.RenderMode.PREVIEW_ONLY&&NosmaiSDK.frame==null,"stop restores preview and clears callback")
 stale.onFrame(frame);idle(c);checkThat(e.pushes==4,"old callback cannot publish after stop")
 checkThat(!c.startStreaming(null,"room",1),"wait for prior leave completion")
 e.events.onLeaveChannel(null);checkThat(c.startStreaming(null,"room",1),"restart after leave")
 e.events.onJoinChannelSuccess("room",1,0)
 e.leaveResult=-1;checkThat(!c.stopStreaming(),"leave failure reported")
 checkThat(!c.startStreaming(null,"room",1),"failed leave cannot overlap new session")
 e.leaveResult=0;checkThat(c.stopStreaming()&&e.leaves==3,"failed leave retained for retry")
 e.events.onLeaveChannel(null)
 e.joinResult=-1;checkThat(!c.startStreaming(null,"room",1),"join failure returned")
 checkThat(NosmaiSDK.frame==null&&!e.external,"failed join cleans frame callbacks")
 e.joinResult=0;checkThat(c.startStreaming(null,"room",1),"retry after failed join succeeds")
 c.stopStreaming();e.releaseFrames();c.dispose();checkThat(RtcEngine.destroyed==1,"native engine disposed once")
 println("PASS: duplicate/conflicting start, join gating/failure, bounded CPU copies, retained storage, stale callback, leave failure/retry, restart and dispose")
}
''')
    classes=tmp/'classes'
    subprocess.run(['java','-cp',os.pathsep.join(map(str,compiler)),
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn',
      '-classpath',str(stdlib),'-d',str(classes),
      str(root/'android/src/main/kotlin/com/example/nosmai_agora_bridge/VideoRawDataController.kt'),
      *map(str,tmp.glob('*.kt'))],check=True)
    subprocess.run(['java','-cp',os.pathsep.join([str(classes),str(stdlib)]),
      'com.example.nosmai_agora_bridge.HarnessKt'],check=True,timeout=15)
