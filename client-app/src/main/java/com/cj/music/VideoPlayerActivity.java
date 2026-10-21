package com.cj.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FFmpegOnlyRenderersFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import com.cj.music.resources.R;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置播放器。
 * <ul>
 *   <li>本地文件：走系统 DefaultDataSource 直接读文件。</li>
 *   <li>FTP / FTPS / SMB 媒体：由 {@link RemoteDataSource} 在线按需读取，边下边播，全程不写本地缓存；
 *       seek 通过协议定位能力（FTP REST / SMB 按 offset 读）重新定位。</li>
 * </ul>
 */
public class VideoPlayerActivity extends AppCompatActivity {
    static final String EXTRA_PATH="path";       // 本地绝对路径，或远程绝对路径
    static final String EXTRA_PROFILE="profile"; // 远程连接 id；空串或缺失表示本地
    static final String EXTRA_TITLE="title";
    static final String EXTRA_SIZE="size";

    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private PlayerView playerView;
    private View chrome;
    private TextView status,hint,speedView,decoderView;
    private ImageButton repeatButton,rotateButton,lockButton;
    private String path,profileId,title;
    private long size;
    private boolean remote;
    private AudioManager audio;
    private int orientationStep;         // 0=竖屏, 1=横屏, 2=反向横屏
    private boolean orientationLocked;
    private boolean brightnessPermissionRequested,brightnessGestureDenied;
    private static final int[] ORIENTATIONS={
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE};
    private static final int[] ORIENTATION_LABELS={
            R.string.client_player_portrait,
            R.string.client_player_landscape,
            R.string.client_player_reverse_landscape};
    private static final int ADJUST_NONE=0,ADJUST_BRIGHTNESS=1,ADJUST_VOLUME=2;
    private float gestureX,gestureY; private boolean adjusting; private int adjustMode=ADJUST_NONE;
    private float baseBrightness; private int baseVolume;
    /** 首次改系统亮度前先记下原值：退出播放器时还原，否则会把用户的「自动亮度」永久关掉。 */
    private boolean brightnessModeSaved; private int savedBrightnessMode,savedBrightness;
    private long lastRx=-1L;
    private long lastSpeedTime;
    private boolean repeatOn;
    private boolean softwareFallbackUsed;
    private String lastDecoderName;
    private MediaSource currentSource;
    private RemoteDataSource.Factory streamingFactory;
    private final Runnable hideHint=()->{if(hint!=null)hint.setVisibility(View.GONE);};
    private final Runnable speedTick=new Runnable(){@Override public void run(){updateSpeed();ui.postDelayed(this,1000L);}};

    @Override protected void onCreate(Bundle state){
        final SharedPreferences prefs=getSharedPreferences("client",MODE_PRIVATE);
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(state);
        applyPlayerSystemBars();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);   // 播放视频时隐藏系统状态栏
        // 画面铺满到屏幕边缘（含刘海区域），避免黑边
        final WindowManager.LayoutParams lp=getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        setContentView(R.layout.client_video_player);

        playerView=findViewById(R.id.client_player_view);
        status=findViewById(R.id.client_player_status);
        // 旋转 / 锁定 / 循环随控制条一起显隐，所以从 PlayerView 的控制条里取，而不是 Activity 布局。
        chrome=findViewById(R.id.client_player_bar);
        rotateButton=playerView.findViewById(R.id.client_player_rotate);
        lockButton=playerView.findViewById(R.id.client_player_lock);
        repeatButton=playerView.findViewById(R.id.client_player_repeat);
        hint=findViewById(R.id.client_player_hint);
        speedView=findViewById(R.id.client_player_speed);
        decoderView=findViewById(R.id.client_player_decoder);
        audio=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        path=getIntent().getStringExtra(EXTRA_PATH);
        profileId=getIntent().getStringExtra(EXTRA_PROFILE);
        title=getIntent().getStringExtra(EXTRA_TITLE);
        size=getIntent().getLongExtra(EXTRA_SIZE,0L);
        remote=profileId!=null&&!profileId.isEmpty();
        if(path==null||path.isEmpty()){toast("缺少播放地址");finish();return;}
        if(title==null||title.trim().isEmpty()){int cut=path.lastIndexOf('/');title=cut<0?path:path.substring(cut+1);}

        ((TextView)findViewById(R.id.client_player_title)).setText(title);
        findViewById(R.id.client_player_back).setOnClickListener(v->finish());
        rotateButton.setOnClickListener(v->rotateOnce());
        lockButton.setOnClickListener(v->toggleOrientationLock());
        repeatButton.setOnClickListener(v->toggleRepeat());

        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setControllerShowTimeoutMs(3500);
        playerView.setControllerAnimationEnabled(false);
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->syncChrome(visibility==View.VISIBLE));

        initPlayer(/*softwareOnly=*/false);
        if(remote)resolveRemote();else startLocal();
    }

    /** Also cover the launch window, cutout area and bar restoration after rotation. */
    private void applyPlayerSystemBars(){
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
        getWindow().getDecorView().setBackgroundColor(android.graphics.Color.BLACK);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        getWindow().setStatusBarContrastEnforced(false);
        getWindow().setNavigationBarContrastEnforced(false);
        androidx.core.view.WindowInsetsControllerCompat bars=new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);bars.setAppearanceLightNavigationBars(false);
    }

    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);applyPlayerSystemBars();}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)applyPlayerSystemBars();}

    /** 创建 ExoPlayer：默认先走系统硬解，失败后切到 FFmpeg 软解。 */
    private void initPlayer(boolean softwareOnly){
        if(player!=null){player.release();player=null;}
        if(playerView!=null)playerView.setPlayer(null);
        DefaultRenderersFactory renderers;
        if(softwareOnly){
            renderers=new FFmpegOnlyRenderersFactory(this).setEnableDecoderFallback(true);
        }else{
            renderers=new NextRenderersFactory(this)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .setEnableDecoderFallback(true);
        }
        player=new ExoPlayer.Builder(this,renderers).setSeekForwardIncrementMs(10000).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int playbackState){
                if(playbackState==Player.STATE_BUFFERING)showStatus("正在缓冲…");
                else if(playbackState==Player.STATE_READY)showStatus(null);
            }
            @Override public void onPlayerError(PlaybackException error){
                if(!softwareFallbackUsed&&error.errorCode==PlaybackException.ERROR_CODE_DECODER_INIT_FAILED){
                    softwareFallbackUsed=true;
                    showStatus("系统解码器无法播放，正在切换 FFmpeg 软件解码…");
                    ui.post(()->{
                        if(isFinishing()||isDestroyed())return;
                        initPlayer(/*softwareOnly=*/true);
                        if(currentSource!=null)start(currentSource);
                    });
                    return;
                }
                showStatus("播放失败："+describe(error)+(remote?"\n\n可以改用「用其他应用打开」先下载到本机再播放。":""));
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize){
                updateDecoderLabel();
            }
        });
        player.addAnalyticsListener(new AnalyticsListener(){
            @Override public void onVideoDecoderInitialized(EventTime eventTime,String decoderName,long initializedTimestampMs,long initializationDurationMs){
                lastDecoderName=decoderName;
                updateDecoderLabel();
            }
        });
    }

    private void updateDecoderLabel(){
        if(decoderView==null)return;
        String name=lastDecoderName;
        boolean isFfmpeg=name!=null&&name.toLowerCase(java.util.Locale.ROOT).contains("ffmpeg");
        if(isFfmpeg){
            String codec=formatLabel(player!=null?player.getVideoFormat():null);
            decoderView.setText("软解 - "+codec);
        }else{
            decoderView.setText(decoderKind(name)+" · "+(name==null?"初始化中":name));
        }
    }

    private String formatLabel(androidx.media3.common.Format format){
        if(format==null)return "未知格式";
        String mime=format.sampleMimeType;
        if("video/avc".equals(mime))return "H.264";
        if("video/hevc".equals(mime))return "H.265 / HEVC";
        if("video/av1".equals(mime))return "AV1";
        if("video/x-vnd.on2.vp9".equals(mime))return "VP9";
        if("video/x-vnd.on2.vp8".equals(mime))return "VP8";
        if(mime!=null&&mime.startsWith("video/"))return mime.substring("video/".length()).toUpperCase(java.util.Locale.ROOT);
        return mime==null?"未知格式":mime;
    }

    /** 连接配置需要读 Keystore 解密，放到后台线程，避免卡住首帧。 */
    private void resolveRemote(){
        showStatus("正在连接服务器…");
        io.execute(()->{
            ConnectionStore.Profile resolved=null;String problem=null;
            try{resolved=new ConnectionStore(this).get(profileId);}
            catch(Exception e){problem=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            final ConnectionStore.Profile profile=resolved;final String failure=problem;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                if(profile==null){showStatus("无法读取连接配置："+failure);return;}
                streamingFactory=new RemoteDataSource.Factory(profile,path,size);
                final DataSource.Factory factory=streamingFactory;
                final Uri uri=new Uri.Builder().scheme("ftpstream").authority("stream").appendPath(path).build();
                start(new ProgressiveMediaSource.Factory(factory,extractors())
                        .setLoadErrorHandlingPolicy(new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(){
                            @Override public long getRetryDelayMsFor(androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo info){
                                for(Throwable cause=info.exception;cause!=null;cause=cause.getCause())
                                    if(cause instanceof com.cj.music.net.RemoteSession.SeekNotSupportedException)return androidx.media3.common.C.TIME_UNSET;
                                return super.getRetryDelayMsFor(info);
                            }
                        }).createMediaSource(MediaItem.fromUri(uri)));
            });
        });
    }
    private void startLocal(){
        final File file=new File(path);
        if(!file.exists()){showStatus("文件不存在或已无法访问");return;}
        IvfAv1Extractor.Index cached=IvfAv1Extractor.cachedIndex(file);
        if(cached!=null){IvfAv1Extractor.setPendingIndex(cached);startLocalSource(file);return;}
        /* IVF 索引要顺序扫一遍文件（逐帧读头），长视频在慢存储上是秒级操作——
           放在主线程就是 ANR。先转圈，后台建完再起播；建失败照样能播，只是不能拖进度条。 */
        showStatus("正在建立播放索引…");
        io.execute(()->{
            final IvfAv1Extractor.Index index=IvfAv1Extractor.cachedIndex(file);
            ui.post(()->{
                if(isFinishing()||isDestroyed())return;
                IvfAv1Extractor.setPendingIndex(index);
                startLocalSource(file);
            });
        });
    }
    private void startLocalSource(File file){
        start(new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(this),extractors()).createMediaSource(MediaItem.fromUri(Uri.fromFile(file))));
    }
    /** Check IVF magic first so raw AV1 files work even when mislabeled with an .mp4 extension. */
    private ExtractorsFactory extractors(){return ()->{Extractor[] defaults=new DefaultExtractorsFactory().createExtractors();Extractor[] all=new Extractor[defaults.length+1];all[0]=new IvfAv1Extractor();System.arraycopy(defaults,0,all,1,defaults.length);return all;};}
    private void start(MediaSource source){
        if(isFinishing()||isDestroyed())return;
        /* 重新 prepare（如软解降级回退）时要重新挂上 IVF 索引，否则新 Extractor 拿不到 SeekMap。
           只取缓存、不现扫：这条路在主线程，长视频现扫会 ANR；没有缓存就退回不可拖进度条。 */
        if(!remote&&path!=null&&!path.isEmpty()){
            File file=new File(path);
            if(file.exists())IvfAv1Extractor.setPendingIndex(IvfAv1Extractor.cachedIndex(file));
        }
        currentSource=source;
        player.setMediaSource(source);player.prepare();player.setPlayWhenReady(true);
    }
    /**
     * 解码器名不是硬件能力的可靠依据：例如某些 C2 组件名不带 "google"，但实际仍是软件实现。
     * 只有 Android 29+ 的 CodecInfo 明确标记为 hardware accelerated 时才显示“硬解”；
     * 无法验证的实现宁可如实显示“解码器”，绝不误报硬解。
     */
    private String decoderKind(String decoderName){
        String lower=decoderName==null?"":decoderName.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("ffmpeg")||lower.contains("libgav1")||lower.contains("libvpx")||lower.contains("c2.android")||lower.contains("omx.google")||lower.contains("software"))return "软解";
        if(decoderName!=null&&Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
            try{for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
                if(info.isEncoder()||(!decoderName.equals(info.getName())&&!decoderName.equals(info.getCanonicalName())))continue;
                if(info.isSoftwareOnly())return "软解";
                if(info.isHardwareAccelerated())return "硬解";
                return "解码器";
            }}catch(RuntimeException ignored){}
        }
        return "解码器";
    }
    /* ---------- 屏幕方向：旋转 + 锁定 ---------- */
    /** 依次切换：竖屏 → 横屏 → 反向横屏 → 竖屏。锁定状态下不可用。 */
    private void rotateOnce(){
        if(orientationLocked){toast(getString(R.string.client_player_locked_tip));return;}
        orientationStep=(orientationStep+1)%ORIENTATIONS.length;
        setRequestedOrientation(ORIENTATIONS[orientationStep]);
        toast(getString(ORIENTATION_LABELS[orientationStep]));
    }
    /** 锁定：冻结当前实际屏幕方向；解锁：交还给系统自动旋转。 */
    private void toggleOrientationLock(){
        orientationLocked=!orientationLocked;
        if(orientationLocked){
            final int current=currentScreenOrientation();
            for(int i=0;i<ORIENTATIONS.length;i++)if(ORIENTATIONS[i]==current)orientationStep=i;
            setRequestedOrientation(current);
        }else{
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        lockButton.setImageResource(orientationLocked?R.drawable.client_player_icon_lock:R.drawable.client_player_icon_unlock);
        lockButton.setContentDescription(getString(orientationLocked?R.string.client_player_unlock:R.string.client_player_lock));
        rotateButton.setAlpha(orientationLocked?0.4f:1f);
        speedView.setAlpha(orientationLocked?0.4f:1f);
        toast(getString(orientationLocked?R.string.client_player_orientation_locked:R.string.client_player_orientation_auto));
    }
    /** 读取设备当前实际方向（含反向），用于锁定时冻结。 */
    private int currentScreenOrientation(){
        final int rotation=getWindowManager().getDefaultDisplay().getRotation();
        switch(rotation){
            case android.view.Surface.ROTATION_90:  return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case android.view.Surface.ROTATION_180: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case android.view.Surface.ROTATION_270: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            default:                                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
    }

    /** 单文件播放，循环只有开 / 关两态（REPEAT_MODE_ONE 与 ALL 在这里等价）。 */
    private void toggleRepeat(){
        if(player==null)return;
        repeatOn=!repeatOn;
        player.setRepeatMode(repeatOn?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);
        repeatButton.setImageResource(repeatOn?R.drawable.client_player_icon_repeat_one:R.drawable.client_player_icon_repeat);
        repeatButton.setContentDescription(getString(repeatOn?R.string.client_player_repeat_enabled:R.string.client_player_repeat_disabled));
        toast(getString(repeatOn?R.string.client_player_repeat_enabled:R.string.client_player_repeat_disabled));
    }

    /* ---------- 顶栏随控制条一起显隐 ---------- */
    private void syncChrome(boolean show){
        if(chrome==null)return;
        chrome.setVisibility(show?View.VISIBLE:View.GONE);
    }

    /* ---------- 左半屏亮度 / 右半屏音量 ---------- */
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(player==null)return super.dispatchTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                gestureX=event.getX();gestureY=event.getY();adjusting=false;brightnessGestureDenied=false;break;
            case MotionEvent.ACTION_MOVE:{
                if(brightnessGestureDenied)return true;
                final float dx=event.getX()-gestureX,dy=event.getY()-gestureY;
                if(!adjusting&&Math.abs(dy)>dp(20)&&Math.abs(dy)>Math.abs(dx)&&inGestureZone(gestureX,gestureY)){
                    adjusting=true;
                    MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
                    adjustMode=gestureX<getResources().getDisplayMetrics().widthPixels/2f?ADJUST_BRIGHTNESS:ADJUST_VOLUME;
                    if(adjustMode==ADJUST_BRIGHTNESS)baseBrightness=currentBrightness();
                    else baseVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                }
                if(adjusting){applyAdjust(-dy);return true;}
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(brightnessGestureDenied){brightnessGestureDenied=false;return true;}
                if(adjusting){adjusting=false;adjustMode=ADJUST_NONE;hideHint.run();return true;}
                break;
        }
        return super.dispatchTouchEvent(event);
    }
    private boolean inGestureZone(float x,float y){
        final Rect rect=new Rect();chrome.getGlobalVisibleRect(rect);
        if(y<rect.bottom+dp(4))return false;                                   // 顶栏
        // 控制条可见时避开底部按钮行；隐藏后整屏都可手势（旋转 / 锁定 / 循环已并入控制条）。
        if(chrome.getVisibility()==View.VISIBLE)
            return y<getResources().getDisplayMetrics().heightPixels-dp(130);
        return true;
    }
    private void applyAdjust(float delta){
        final float range=Math.max(dp(120),getResources().getDisplayMetrics().heightPixels*0.6f);
        final float change=delta/range;
        if(adjustMode==ADJUST_BRIGHTNESS){
            final float value=Math.max(0.02f,Math.min(1f,baseBrightness+change));
            if(!Settings.System.canWrite(this)){
                brightnessGestureDenied=true;adjusting=false;adjustMode=ADJUST_NONE;
                if(!brightnessPermissionRequested){
                    brightnessPermissionRequested=true;
                    SystemBrightnessPermission.openSettings(this,()->brightnessPermissionRequested=false);
                }
                return;
            }
            try{
                if(!brightnessModeSaved){
                    brightnessModeSaved=true;
                    savedBrightnessMode=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                    savedBrightness=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,Math.round(value*255));
                }
                boolean manual=Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                boolean written=manual&&Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,Math.round(value*255));
                if(written)showHint(getString(R.string.client_brightness)+" "+Math.round(value*100)+"%");
                else{brightnessGestureDenied=true;toast("系统亮度设置失败");}
            }catch(SecurityException error){brightnessGestureDenied=true;toast("系统未允许修改亮度，请检查修改系统设置权限");}
        }else{
            final int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            final int value=Math.max(0,Math.min(max,Math.round(baseVolume+change*max)));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);
            showHint(getString(R.string.client_volume)+" "+Math.round(100f*value/max)+"%");
        }
    }
    private float currentBrightness(){
        try{return Math.max(0f,Math.min(1f,Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS)/255f));}
        catch(Exception e){return 0.5f;}
    }
    /** 还原被亮度手势改掉的系统亮度值与原亮度模式（自动亮度绝不能留着被关掉）。 */
    private void restoreSystemBrightness(){
        if(!brightnessModeSaved)return;brightnessModeSaved=false;
        try{
            Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,savedBrightness);
            Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,savedBrightnessMode);
        }catch(Exception ignored){}
    }
    private void showHint(String text){
        ui.removeCallbacks(hideHint);
        hint.setText(text);hint.setVisibility(View.VISIBLE);
        ui.postDelayed(hideHint,700L);
    }

    /* ---------- 网速 ---------- */
    private void updateSpeed(){
        if(speedView==null)return;
        if(streamingFactory==null){speedView.setText("");return;}
        final long rx=streamingFactory.received.get(),now=android.os.SystemClock.elapsedRealtime();
        if(lastRx>=0L&&rx>=lastRx&&now>lastSpeedTime)speedView.setText("↓ "+formatSpeed((rx-lastRx)*1000/(now-lastSpeedTime)));
        lastRx=rx;lastSpeedTime=now;
    }
    private static String formatSpeed(long bytesPerSecond){
        final float value;final String unit;
        if(bytesPerSecond>=1024L*1024L){value=bytesPerSecond/(1024f*1024f);unit="MB/s";}
        else if(bytesPerSecond>=1024L){value=bytesPerSecond/1024f;unit="KB/s";}
        else {value=bytesPerSecond;unit="B/s";}
        return (value<10f?String.format(java.util.Locale.getDefault(),"%.1f",value):String.valueOf(Math.round(value)))+" "+unit;
    }

    private String describe(PlaybackException error){
        final Throwable cause=error.getCause();
        final String detail=cause==null?null:cause.getMessage();
        return error.getErrorCodeName()+(detail==null||detail.isEmpty()?"":"（"+detail+"）");
    }
    private void showStatus(String text){
        if(isFinishing()||isDestroyed())return;
        if(text==null){status.setVisibility(TextView.GONE);return;}
        status.setText(text);status.setVisibility(TextView.VISIBLE);
    }
    private void toast(String text){if(!isFinishing()&&!isDestroyed())Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}

    @Override protected void onStart(){super.onStart();brightnessPermissionRequested=false;lastRx=-1L;ui.post(speedTick);}
    @Override protected void onStop(){super.onStop();ui.removeCallbacks(speedTick);if(player!=null)player.pause();}
    @Override protected void onDestroy(){
        restoreSystemBrightness();
        ui.removeCallbacks(speedTick);ui.removeCallbacks(hideHint);
        if(streamingFactory!=null){streamingFactory.close();streamingFactory=null;}
        if(player!=null){player.release();player=null;}
        if(playerView!=null)playerView.setPlayer(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
