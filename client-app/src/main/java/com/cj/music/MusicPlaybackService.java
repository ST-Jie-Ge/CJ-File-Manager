package com.cj.music;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import com.cj.music.resources.R;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Playback owns its player independently of all activities and file-browser sessions. */
public final class MusicPlaybackService extends Service {
    static volatile MusicPlaybackService instance;
    private static boolean musicInterface;
    private boolean playAfterPrepare=true;
    /** 播放来源：0=文件管理器，1=音乐模式。两个界面的播放组件按来源互相隔离，不显示也不接管对方。 */
    static final int ORIGIN_BROWSER=0,ORIGIN_MUSIC=1;
    private volatile int origin=ORIGIN_MUSIC;
    /** 用户主动暂停（播放/暂停按钮、通知栏、耳机线控）。文件管理器的悬浮组件据此自动收起。 */
    private volatile boolean userPaused;
    int origin(){return origin;}
    boolean userPaused(){return userPaused;}
    /* 界面切换只在「app 内部从音乐模式走到文件管理器」时暂停播放。
       从桌面/其它 app 回来不算：那时前台 Activity 数是从 0 变 1 的（见 ClientApplication），
       用户只是回到 app，不该把后台正在播的歌按停。 */
    static void enterInterface(boolean music){
        if(musicInterface==music)return;
        if(ClientApplication.consumeBackgroundReturn()){musicInterface=music;return;}
        pauseForInterfaceChange();musicInterface=music;
    }
    static void pauseForInterfaceChange(){pausePlayback();}
    static void pausePlayback(){MusicPlaybackService s=instance;if(s!=null){s.playAfterPrepare=false;s.userPaused=false;if(s.player!=null)s.player.pause();s.update();}}
    static final String LOAD="music.load",TOGGLE="music.toggle",NEXT="music.next",PREVIOUS="music.previous",STOP="music.stop",MODE="music.mode";
    /** 播放模式：顺序播放 / 列表循环 / 单曲循环。 */
    static final int MODE_SEQUENTIAL=0,MODE_LIST_LOOP=1,MODE_SINGLE_LOOP=2;
    MusicTrack track;volatile File playingFile;String error="";volatile boolean preparing;volatile String artist="";volatile String lastPlaybackFailure="";
    volatile int[] coverTint;volatile Bitmap coverField;
    volatile Bitmap localArtwork;
    /** 当前音轨的码率（bps）与采样率（Hz）；取不到时为 0（界面就不显示这两项）。 */
    volatile int bitrate,sampleRate;
    String audioDecoderName="";
    volatile androidx.media3.common.Format sourceAudioFormat;
    androidx.media3.common.Format currentAudioFormat(){return player==null?null:player.getAudioFormat();}
    androidx.media3.common.Format currentInputAudioFormat(){return sourceAudioFormat;}
    /** 当前音频解码器的分类：硬解 / 软解 / 解码器（无法确定时）。 */
    String decoderKind(){
        String name=audioDecoderName;if(name==null||name.isEmpty())return "";
        String lower=name.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("ffmpeg")||lower.contains("c2.android")||lower.contains("omx.google")||lower.contains("software"))return "软解";
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
            try{for(android.media.MediaCodecInfo info:new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()){
                if(info.isEncoder()||(!name.equals(info.getName())&&!name.equals(info.getCanonicalName())))continue;
                if(info.isSoftwareOnly())return "软解";
                if(info.isHardwareAccelerated())return "硬解";
                return "解码器";
            }}catch(RuntimeException ignored){}
        }
        return "解码器";
    }
    /** 当前解码后 PCM 的位深描述。 */
    String pcmBitDepth(){
        androidx.media3.common.Format f=currentAudioFormat();if(f==null)return "";
        switch(f.pcmEncoding){
            case C.ENCODING_PCM_8BIT:return "PCM 8 bit";
            case C.ENCODING_PCM_16BIT:return "PCM 16 bit";
            case C.ENCODING_PCM_24BIT:return "PCM 24 bit";
            case C.ENCODING_PCM_32BIT:return "PCM 32 bit";
            case C.ENCODING_PCM_FLOAT:return "PCM Float";
            default:return "PCM";
        }
    }
    String halOutputInfo(){
        StringBuilder out=new StringBuilder("Android AudioTrack / AudioFlinger\n");
        try{
            android.media.AudioManager am=(android.media.AudioManager)getSystemService(Context.AUDIO_SERVICE);
            if(am!=null){String rate=am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);String frames=am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
                if(rate!=null)out.append("主输出配置：").append(rate).append(" Hz");
                if(frames!=null)out.append(" · buffer ").append(frames).append(" frames");
            }
        }catch(Exception ignored){}
        if(out.toString().equals("Android AudioTrack / AudioFlinger\n"))out.append("主输出配置：系统未提供\n");else out.append('\n');
        out.append("HAL 内部重采样／位深：Android 应用不可见");return out.toString();
    }
    private ExoPlayer player;private MediaSession session;private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<String> coverReady=Collections.synchronizedSet(new HashSet<>()),coverQueued=Collections.synchronizedSet(new HashSet<>());
    /* 封面预取：3 条低优先级线程 + 16 的排队容量。
       原来是 1 线程 / 5 容量：连续切歌时窗口里那几张要排队等一张张解，队尾还会被挤掉，
       切到那一首就得当场同步解码 —— 这就是「快速切歌封面跟不上」的主要原因。 */
    private final ThreadPoolExecutor coverWorker=new ThreadPoolExecutor(3,3,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(16),r->{Thread t=new Thread(r,"music-cover-prefetch");t.setPriority(Thread.MIN_PRIORITY);return t;},(task,executor)->{Runnable dropped=executor.getQueue().poll();if(dropped instanceof CoverPrefetch)coverQueued.remove(((CoverPrefetch)dropped).key);if(!executor.isShutdown())executor.execute(task);});
    private final ExecutorService metadataWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"music-metadata");t.setPriority(Thread.MIN_PRIORITY);return t;});
    /* 相邻曲目的音频文件预热：最低优先级单线程，只为切歌时少等一次下载/转码。
       队列只留 1 个、满了丢最旧那个：连点「下一首」时旧任务早已不相关，
       无界队列会让它们排着队把不相关的歌整首下载完，抢带宽也抢磁盘 IO。 */
    private final ExecutorService prewarmWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(1),r->{Thread t=new Thread(r,"music-prewarm");t.setPriority(Thread.MIN_PRIORITY);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    /** 已经预热过的曲目，避免来回切歌时反复排队。只是去重用的，超量直接整体清掉重来。 */
    private final Set<String> prewarmed=Collections.synchronizedSet(new HashSet<>());
    private volatile List<String> coverWindow=Collections.emptyList();
    private final Map<String,MusicTrack> coverTracks=new ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicBoolean tagEditing=new java.util.concurrent.atomic.AtomicBoolean();
    private boolean externalLocked;private volatile boolean fullyReleased;
    void awaitReleased()throws Exception{long end=System.currentTimeMillis()+15000;while(!fullyReleased&&System.currentTimeMillis()<end)Thread.sleep(20);if(!fullyReleased||!worker.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("播放器仍占用文件，已取消写回");}
    static void resumeEdited(Context c,MusicTrack t,List<MusicTrack> list,long position,boolean playing,boolean locked){
        String token=UUID.randomUUID().toString();
        pendingQueues.put(token,new ArrayList<>(list));
        Intent intent=new Intent(c,MusicPlaybackService.class).setAction(LOAD).putExtra("track",t.json().toString()).putExtra("queue_token",token).putExtra("resume_position",position).putExtra("resume_playing",playing).putExtra("external_locked",locked);
        if(t.path.startsWith("content:")){Uri uri=Uri.parse(t.path);intent.setData(uri);intent.setClipData(ClipData.newRawUri("music",uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        /* 与 start() 一致：后台启动被拒（Android 12+ 前台服务限制）时要把 token 摘掉，
           否则 pendingQueues 里永久残留一条没人取的队列。 */
        try{androidx.core.content.ContextCompat.startForegroundService(c,intent);}catch(RuntimeException error){pendingQueues.remove(token);throw error;}
    }
    static List<MusicTrack> replaceEditedQueue(List<MusicTrack> queue,MusicTrack before,MusicTrack after){
        java.util.LinkedHashMap<String,MusicTrack> next=new java.util.LinkedHashMap<>();
        for(MusicTrack t:queue){MusicTrack updated=t.id().equals(before.id())||t.id().equals(after.id())?after:t;next.put(updated.id(),updated);}
        return new ArrayList<>(next.values());
    }
    private long resumePosition;private boolean resumePlaying=true;
    static void playExternal(Context c,MusicTrack t){androidx.core.content.ContextCompat.startForegroundService(c,new Intent(c,MusicPlaybackService.class).setAction(LOAD).putExtra("track",t.json().toString()).putExtra("external_locked",true));}
    /** 音乐模式卡片上「上次播放」的播放键：服务已经被系统回收时，把这首歌重新拉起来从头播。
        故意不带 queue_token：onStartCommand 缺队列时会自己读曲库当播放队列（和原来的兜底是同一条路），
        上一首／下一首、耳机线控、通知栏按钮于是也都能用。 */
    static void resumeRemembered(Context c,MusicTrack t){resumeRemembered(c,t,true);}
    /** play=false：只把这首歌挂进服务、**不自动播**——用于「点悬浮卡片进全屏播放页」，
        效果和「播过一首再暂停」完全一致（曲目已就位、停在 0:00，按播放键才开始）。
        没有队列就照旧让 onStartCommand 自己读曲库当队列，上一首／下一首、线控、通知栏都能用。 */
    static void resumeRemembered(Context c,MusicTrack t,boolean play){
        if(c==null||t==null||t.path.isEmpty())return;
        Intent intent=new Intent(c,MusicPlaybackService.class).setAction(LOAD).putExtra("track",t.json().toString())
                .putExtra("origin",ORIGIN_MUSIC).putExtra("resume_position",0L).putExtra("resume_playing",play);
        if(t.path.startsWith("content:")){Uri uri=Uri.parse(t.path);intent.setData(uri);intent.setClipData(ClipData.newRawUri("music",uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        try{androidx.core.content.ContextCompat.startForegroundService(c,intent);}catch(RuntimeException ignored){}
    }
    static void unlockExternal(Context c){command(c,"music.unlock");}

    /* ===== 记住上次在音乐模式播放的曲目 =====
       进程被系统回收、后台被异常清掉之后，音乐模式的卡片仍然要显示「上次播放的那首」。
       只记音乐模式发起的播放（ORIGIN_MUSIC）：文件管理器里放的歌不算，两边各记各的
       —— 这与「播放组件按来源隔离」是同一条规则。 */
    private static final String REMEMBER_TRACK="last_track",REMEMBER_ARTIST="last_track_artist";
    static void remember(Context c,MusicTrack t){
        if(c==null||t==null||t.path.isEmpty())return;
        c.getApplicationContext().getSharedPreferences("music",0).edit().putString(REMEMBER_TRACK,t.json().toString()).apply();
    }
    static void rememberArtist(Context c,String artist){
        if(c==null)return;
        c.getApplicationContext().getSharedPreferences("music",0).edit().putString(REMEMBER_ARTIST,artist==null?"":artist).apply();
    }
    /** 上次播放的原始记录：纯内存读，界面按「记录变了」才解码，别在 500ms 的 tick 里反复 stat 文件。 */
    static String rememberedRaw(Context c){return c.getSharedPreferences("music",0).getString(REMEMBER_TRACK,"");}
    static String rememberedArtist(Context c){String a=c.getSharedPreferences("music",0).getString(REMEMBER_ARTIST,"");return a==null?"":a;}
    /** 解析记录并校验本地文件还在不在：删掉、改名、拔卡之后卡片要退回空态，不能指向一首不存在的歌。 */
    static MusicTrack rememberedTrack(String raw){
        if(raw==null||raw.isEmpty())return null;
        try{
            MusicTrack t=MusicTrack.from(new JSONObject(raw));
            if(t.name.isEmpty()||t.path.isEmpty())return null;
            if(t.profile.isEmpty()&&!t.path.startsWith("content:")&&!new File(t.path).isFile())return null;
            return t;
        }catch(Exception e){return null;}
    }
    private Future<?> task;private int generation;private List<MusicTrack> queue=new ArrayList<>();
    private int repeatMode;
    private int failedSkipGeneration=-1;
    private Bitmap notificationCover;
    private static final Map<String,List<MusicTrack>> pendingQueues=new ConcurrentHashMap<>();
    /** 音频输出设备选择（MusicAudioRoute.AUTO/SPEAKER/WIRED/BLUETOOTH）。
        静态保存：服务被回收重建后仍按用户上次的选择重新绑定，切歌、换播放页都不丢。 */
    private static int audioRoute=MusicAudioRoute.AUTO;
    static int audioRoute(){return audioRoute;}
    /** 切换输出设备。设备已拔掉时 resolve() 返回 null，等价于退回「跟随系统」。 */
    static void setAudioRoute(int route){
        audioRoute=route;
        MusicPlaybackService current=instance;
        if(current!=null&&current.player!=null)current.player.setPreferredAudioDevice(MusicAudioRoute.resolve(current,route));
    }
    /** 音乐模式入口：队列 = 当前分类下显示的那一列。 */
    static void play(Context context,MusicTrack track,List<MusicTrack> displayed){start(context,track,displayed,ORIGIN_MUSIC);}
    /** 文件管理器入口：队列 = 当前文件夹里的音频。以前这里不传队列，onStartCommand 会退回
        `playInFolder()` 总是带队列；无队列时只保留当前这首，`adjacent()` 自然停在本曲。
        直接返回——上一首／下一首、耳机线控、通知栏按钮全部没反应。 */
    static void playInFolder(Context context,MusicTrack track,List<MusicTrack> folder){start(context,track,folder,ORIGIN_BROWSER);}
    private static void start(Context context,MusicTrack track,List<MusicTrack> queue,int origin){
        String token=UUID.randomUUID().toString();pendingQueues.put(token,new ArrayList<>(queue));
        Intent intent=new Intent(context,MusicPlaybackService.class).setAction(LOAD).putExtra("track",track.json().toString()).putExtra("queue_token",token).putExtra("origin",origin);
        if(track.path.startsWith("content:")){Uri uri=Uri.parse(track.path);intent.setData(uri);intent.setClipData(ClipData.newRawUri("music",uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        try{androidx.core.content.ContextCompat.startForegroundService(context,intent);}catch(RuntimeException error){pendingQueues.remove(token);throw error;}
    }
    List<MusicTrack> playbackQueue(){return new ArrayList<>(queue);}
    void setPlaybackQueue(List<MusicTrack> tracks){
        if(origin!=ORIGIN_MUSIC)return;
        queue=new ArrayList<>(tracks);
        if(track==null)return;
        for(MusicTrack item:queue)if(item.id().equals(track.id()))return;
        if(queue.isEmpty())stopSelf();else load(queue.get(0));
    }
    private static String coverCacheKey(MusicTrack t){return t.id()+"|"+t.size+"|"+t.time;}
    /** 一首歌的取色结果：CoverTint.read 是 8×8×25 次取像素，field 是裁剪 + 降采样 + 3 遍模糊。 */
    private static final class Tinted{final int[] tint;final Bitmap field;Tinted(int[] tint,Bitmap field){this.tint=tint;this.field=field;}}
    /* 按曲目缓存取色结果：同一首歌原来在 load / scheduleMetadataAndCover / refreshCurrentArtwork
       里各算了一遍。上限 64 条，超出整体清掉 —— 只是重算一次，没有正确性问题。 */
    private static final java.util.Map<String,Tinted> tintCache=new java.util.concurrent.ConcurrentHashMap<>();
    /** local / art 二选一：CoverTint 对 Bitmap 和 File 各有重载，
        远程那一路要传 File（它自己按需要解码），传 Bitmap 会白白多解一次大图。 */
    private static Tinted tintOf(MusicTrack t,Bitmap local,File art,float aspect){
        if(local==null&&art==null)return null;
        String key=coverCacheKey(t);
        Tinted hit=tintCache.get(key);
        if(hit!=null)return hit;
        Tinted made=new Tinted(local!=null?CoverTint.read(local):CoverTint.read(art),
                               local!=null?CoverTint.field(local,aspect):CoverTint.field(art,aspect));
        if(tintCache.size()>64)tintCache.clear();
        tintCache.put(key,made);
        return made;
    }
    private float coverAspect(){return getResources().getDisplayMetrics().widthPixels/(float)Math.max(1,getResources().getDisplayMetrics().heightPixels);}
    private final class CoverPrefetch implements Runnable{
        final MusicTrack item;final String key;CoverPrefetch(MusicTrack item,String key){this.item=item;this.key=key;}
        @Override public void run(){try{
            if(!coverWindow.contains(key))return;
            if(item.profile.isEmpty())MusicLocalArtwork.ensureCached(getApplicationContext(),item);else MusicCoverLoader.ensureCover(getApplicationContext(),item);
            coverReady.add(key);new Handler(Looper.getMainLooper()).post(()->{if(instance==MusicPlaybackService.this&&track!=null&&coverCacheKey(track).equals(key))refreshCurrentArtwork(item);});
        }catch(Exception ignored){}finally{coverQueued.remove(key);}}
    }
    /* 切歌卡顿的另一半在音频：本地曲目的 prepare 只是 stat（很快），
       远程（SMB/FTP）曲目必须整首下完才能出声 —— 连点下一首时这段空档最明显。
       这里在落定当前曲目之后，把「下一首 + 上一首」提前准备好；
       已经命中缓存（availableAudio）就跳过，不重复下载。
       锁已按目录分开（见 MusicCache.prepare），预热的下载不会挡住用户切歌那一次 prepare。 */
    private void prewarmAdjacent(MusicTrack current){
        int at=-1;for(int i=0;i<queue.size();i++)if(queue.get(i).id().equals(current.id())){at=i;break;}
        if(at<0)return;
        if(at+1<queue.size())prewarmAudio(queue.get(at+1));
        if(at-1>=0)prewarmAudio(queue.get(at-1));
    }
    private void prewarmAudio(MusicTrack t){
        if(t==null)return;
        final String key=t.id()+"|"+t.size+"|"+t.time;
        synchronized(prewarmed){if(prewarmed.size()>256)prewarmed.clear();if(!prewarmed.add(key))return;}
        /* 记下提交时的代次：切歌后 generation 会变，排到队尾才发现已经不相关的任务直接跳过，
           别再为上一首/上上首白下载一整首。 */
        final int ticket=generation;
        prewarmWorker.execute(()->{
            if(ticket!=generation)return;
            try{
                if(MusicCache.availableAudio(getApplicationContext(),t)!=null)return;
                MusicCache.prepare(getApplicationContext(),t);
            }catch(Exception|OutOfMemoryError ignored){}
        });
    }
    private void requestCoverPrefetch(MusicTrack t){
        if(t==null)return;String key=coverCacheKey(t);
        synchronized(coverQueued){if(coverReady.contains(key)||!coverQueued.add(key))return;}
        coverWorker.execute(new CoverPrefetch(t,key));
    }
    private void refreshCurrentArtwork(MusicTrack t){
        worker.execute(()->{try{final Bitmap local=t.profile.isEmpty()?MusicLocalArtwork.readCached(this,t,1024):null;final File art=t.profile.isEmpty()?null:MusicCache.cover(this,t);final Bitmap cover=local!=null?local:art==null?null:notificationArtwork(art);final Bitmap found=cover==null?MusicFormatArtwork.bitmap(t.name):cover;
            new Handler(Looper.getMainLooper()).post(()->{if(instance!=this||track==null||!track.id().equals(t.id()))return;localArtwork=local;notificationCover=found;update();});
        }catch(Exception|OutOfMemoryError ignored){}});
    }
    /* 封面预取窗口：往前 5 首、往后 2 首。
       往前留得多是因为「下一首」是切歌的主要方向，连点几下也不会落到窗口外；
       往后 2 首够用，「上一首」通常是回退一两首。 */
    private static final int COVER_AHEAD=5,COVER_BEHIND=2;
    private void updateCoverWindow(MusicTrack current){
        int at=-1;for(int i=0;i<queue.size();i++)if(queue.get(i).id().equals(current.id())){at=i;break;}
        if(at<0){String key=coverCacheKey(current);List<String> before=coverWindow;coverWindow=Collections.singletonList(key);coverTracks.put(key,current);if(!before.contains(key))requestCoverPrefetch(current);return;}
        ArrayList<String> keys=new ArrayList<>();int low=Math.max(0,at-COVER_BEHIND),high=Math.min(queue.size()-1,at+COVER_AHEAD);
        for(int i=low;i<=high;i++){MusicTrack item=queue.get(i);String key=coverCacheKey(item);keys.add(key);coverTracks.put(key,item);}
        List<String> before=coverWindow;coverWindow=Collections.unmodifiableList(keys);
        for(String key:keys)if(!before.contains(key)){MusicTrack item=coverTracks.get(key);if(item!=null)requestCoverPrefetch(item);}
    }
    private void scheduleMetadataAndCover(MusicTrack t,File file,int ticket){
        metadataWorker.execute(()->{
            String foundArtist="";
            if(file!=null||t.profile.isEmpty())try(android.media.MediaMetadataRetriever metadata=new android.media.MediaMetadataRetriever()){
                if(file!=null)metadata.setDataSource(file.getPath());else metadata.setDataSource(this,Uri.parse(t.path));
                String value=metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);if(value!=null)foundArtist=value.trim();
                value=metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);if(value!=null&&!value.trim().isEmpty())t.title=value.trim();
                value=metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM);if(value!=null&&!value.trim().isEmpty())t.album=value.trim();
            }catch(Exception|OutOfMemoryError ignored){}
            final String resolvedArtist=foundArtist;final int[] spec=file==null?null:MusicDecoder.audioSpec(file);
            Bitmap localNow=t.profile.isEmpty()?MusicLocalArtwork.readCached(this,t,1024):null;
            File artNow=t.profile.isEmpty()?null:cachedRemoteCover(t);Bitmap cover=localNow!=null?localNow:artNow==null?null:notificationArtwork(artNow);
            Bitmap foundCover=cover==null?MusicFormatArtwork.bitmap(t.name):cover;
            Tinted tinted=tintOf(t,localNow,artNow,coverAspect());
            int[] tint=tinted==null?null:tinted.tint;
            Bitmap field=tinted==null?null:tinted.field;
            final Bitmap local=localNow,notification=foundCover,coverFieldResult=field;final int[] tintResult=tint;
            new Handler(Looper.getMainLooper()).post(()->{if(instance!=this||generation!=ticket||track==null||!track.id().equals(t.id()))return;
                artist=resolvedArtist;if(spec!=null){bitrate=spec[0];sampleRate=spec[1];}localArtwork=local;notificationCover=notification;if(tintResult!=null){coverTint=tintResult;coverField=coverFieldResult;}
                if(origin==ORIGIN_MUSIC){remember(this,t);rememberArtist(this,resolvedArtist);}update();
            });
            if(t.profile.isEmpty())MusicLocalArtwork.ensureCached(getApplicationContext(),t);
            else try{MusicCoverLoader.ensureCover(getApplicationContext(),t);}catch(Exception ignored){}
            refreshCurrentArtwork(t);
        });
    }
    private File cachedRemoteCover(MusicTrack t){try{File file=MusicCache.cover(this,t);return file.isFile()?file:null;}catch(Exception ignored){return null;}}
    private Bitmap notificationArtwork(File file){try{android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(file.getPath(),options);int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>512)sample*=2;options.inJustDecodeBounds=false;options.inSampleSize=sample;return android.graphics.BitmapFactory.decodeFile(file.getPath(),options);}catch(Exception|OutOfMemoryError ignored){return null;}}
    int repeatMode(){return repeatMode;}
    int loadVersion(){return generation;}
    static void command(Context c,String action){if(instance!=null)c.startService(new Intent(c,MusicPlaybackService.class).setAction(action));}
    boolean playing(){return player!=null&&player.isPlaying();}
    long position(){return player==null?0:player.getCurrentPosition();}
    long duration(){return player==null?0:Math.max(0,player.getDuration());}
    void seek(long p){if(player!=null&&!preparing)player.seekTo(p);}
    /** 从头播放／恢复：player 停在 STATE_ENDED 时（如最后一首播完、切回循环模式后按播放键），
        ExoPlayer 的 play() 不会自动 seek 回开头 —— 必须显式 seekTo(0) 再 play。 */
    private void playOrRestart(){
        userPaused=false;
        if(player!=null){
            if(player.getPlaybackState()==Player.STATE_ENDED)player.seekTo(0);
            player.play();
        }
    }
    @Override public void onCreate(){super.onCreate();coverTint=CoverTint.randomPalette();instance=this;
        repeatMode=Math.max(MODE_SEQUENTIAL,Math.min(MODE_SINGLE_LOOP,getSharedPreferences("music",0).getInt("repeat_mode",MODE_SEQUENTIAL)));
        NotificationChannel channel=new NotificationChannel("music-playback","音乐播放",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(channel);
        session=new MediaSession(this,"FileManagerMusic");session.setCallback(new MediaSession.Callback(){@Override public void onPlay(){playOrRestart();}@Override public void onPause(){if(player!=null){userPaused=true;player.pause();}}@Override public void onSkipToNext(){adjacent(1);}@Override public void onSkipToPrevious(){adjacent(-1);}@Override public void onSeekTo(long p){seek(p);}@Override public void onStop(){stopSelf();}});session.setActive(true);
        // EQ/DSP 已随音乐模式一并移除，音频走默认处理链。
        androidx.media3.exoplayer.DefaultRenderersFactory renderers=new androidx.media3.exoplayer.DefaultRenderersFactory(this){
            @Override protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(Context context,boolean floatOutput,boolean playbackParams){
                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    /* 允许 float 输出：之前强制 16-bit 会把「只输出 float 的硬件解码器」挤掉，
                       导致 MP3 等格式回退到 c2.android.* 软解。 */
                    .setEnableFloatOutput(true)
                    .build();
            }
        };
        player=new ExoPlayer.Builder(this,renderers).build();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);player.setHandleAudioBecomingNoisy(true);player.setWakeMode(C.WAKE_MODE_LOCAL);player.setPreferredAudioDevice(MusicAudioRoute.resolve(this,audioRoute));
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener(){
            @Override public void onAudioDecoderInitialized(EventTime eventTime,String name,long initializedTimestampMs,long initializationDurationMs){audioDecoderName=name;}
            @Override public void onAudioInputFormatChanged(EventTime eventTime,Format format,androidx.media3.exoplayer.DecoderReuseEvaluation decoderReuseEvaluation){sourceAudioFormat=format;}
            @Override public void onAudioDecoderReleased(EventTime eventTime,String name){if(name.equals(audioDecoderName))audioDecoderName="";}
        });
        player.addListener(new Player.Listener(){@Override public void onEvents(Player p,Player.Events e){update();}@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED&&repeatMode!=MODE_SINGLE_LOOP){
            /* 文件管理器里放歌：播完当前这首就停下，不自动切下一首。
               音乐模式不受影响，仍按循环模式自动切换；列表循环是用户显式选的，照旧自动切。 */
            if(origin==ORIGIN_BROWSER&&repeatMode==MODE_SEQUENTIAL)player.pause();else adjacent(1);
        }}@Override public void onPlayerError(PlaybackException e){error="播放失败："+(track==null?"音乐文件":track.displayTitle());lastPlaybackFailure=error;userPaused=true;update();if(failedSkipGeneration!=generation){failedSkipGeneration=generation;adjacent(1);}}});
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        // Foreground is started immediately, before any disk / network / conversion work.
        startForeground(701,notification());
        // 这次 LOAD 不会被执行，队列副本要收回：pendingQueues 是进程级静态 Map，漏一个就永久留着。
        if(intent==null||tagEditing.get()){String dropped=intent==null?null:intent.getStringExtra("queue_token");if(dropped!=null)pendingQueues.remove(dropped);stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction();if(LOAD.equals(action))try{origin=intent.getIntExtra("origin",ORIGIN_MUSIC);userPaused=false;externalLocked=intent.getBooleanExtra("external_locked",false);resumePosition=intent.getLongExtra("resume_position",0);resumePlaying=intent.getBooleanExtra("resume_playing",true);String token=intent.getStringExtra("queue_token");List<MusicTrack> selectedQueue=token==null?null:pendingQueues.remove(token);
        if(selectedQueue!=null)queue=selectedQueue;
        else{
            /* 音乐模式已移除：没有队列时只保留当前这一首（通知栏／耳机线控的上一首／下一首自然停在本曲）。 */
            queue=new ArrayList<>();
        }
        load(MusicTrack.from(new JSONObject(intent.getStringExtra("track"))));}catch(Exception e){error="无法打开音乐文件";update();}
        else if("music.unlock".equals(action)){externalLocked=false;}else if(TOGGLE.equals(action)){if(preparing)return START_NOT_STICKY;if(!error.isEmpty()&&track!=null)load(track);else if(player.isPlaying()){userPaused=true;player.pause();}else{playOrRestart();}}
        else if(NEXT.equals(action))adjacent(1);else if(PREVIOUS.equals(action))adjacent(-1);else if(STOP.equals(action))stopSelf();else if(MODE.equals(action))cycleMode();return START_NOT_STICKY;
    }
    private void load(MusicTrack t){
        /* 先把曲目记下来：哪怕后面解码失败、进程随后被杀，音乐模式的卡片也还知道「上次播放」是哪首。 */
        if(origin==ORIGIN_MUSIC)remember(this,t);
        playAfterPrepare=resumePlaying;final long seekAfterLoad=resumePosition;resumePlaying=true;resumePosition=0;final int ticket=++generation;if(task!=null)task.cancel(true);player.stop();track=t;playingFile=null;error="";artist="";bitrate=0;sampleRate=0;sourceAudioFormat=null;audioDecoderName="";preparing=true;notificationCover=null;localArtwork=null;userPaused=false;update();
        task=worker.submit(()->{try{
            File file=MusicCache.prepare(getApplicationContext(),t);
            File localCover=null;if(t.profile.isEmpty())try{localCover=MusicCache.localCover(this,t);}catch(Exception ignored){}
            final Bitmap local=localCover==null?null:MusicLocalArtwork.readCached(this,t,1024);
            final File art=t.profile.isEmpty()?null:MusicCache.cover(this,t);Bitmap extractedCover=local==null?(art==null?null:notificationArtwork(art)):local;final Bitmap foundNotificationCover=extractedCover==null?MusicFormatArtwork.bitmap(t.name):extractedCover;
            final int[] foundTint=local!=null?CoverTint.read(local):art==null?null:CoverTint.read(art);
            float aspect=getResources().getDisplayMetrics().widthPixels/(float)Math.max(1,getResources().getDisplayMetrics().heightPixels);
            final Bitmap foundField=local!=null?CoverTint.field(local,aspect):art==null?null:CoverTint.field(art,aspect);
            new Handler(Looper.getMainLooper()).post(()->{if(instance!=this||ticket!=generation)return;preparing=false;playingFile=file;
                localArtwork=local;notificationCover=foundNotificationCover;if(foundTint!=null){coverField=foundField;coverTint=foundTint;}
                // No artwork: retain both the previous background and its accent.
                /* 只把播放期间读到的元数据写回曲库：文件管理器发起的播放不改写音乐模式的数据。
                   顺便把补全后的曲目（标题/专辑）和歌手名更新进「上次播放」记录。 */
                player.setRepeatMode(repeatMode==MODE_SINGLE_LOOP?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);
                Uri uri=file==null?Uri.parse(t.path):Uri.fromFile(file);
                player.setMediaItem(new MediaItem.Builder().setUri(uri).setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder().setTitle(t.displayTitle()).build()).build());
                player.seekTo(seekAfterLoad);player.setPlayWhenReady(playAfterPrepare);player.prepare();update();
                updateCoverWindow(t);prewarmAdjacent(t);scheduleMetadataAndCover(t,file,ticket);
            });
        }catch(Exception|OutOfMemoryError e){new Handler(Looper.getMainLooper()).post(()->{if(instance!=this||ticket!=generation)return;preparing=false;error=e instanceof OutOfMemoryError?"文件过大，无法处理":(e.getMessage()==null?"音乐读取失败，请重试":e.getMessage());lastPlaybackFailure=error+"："+t.displayTitle();update();if(failedSkipGeneration!=generation){failedSkipGeneration=generation;adjacent(1);}});}});
    }
    private void cycleMode(){
        repeatMode=(repeatMode+1)%3;
        getSharedPreferences("music",0).edit().putInt("repeat_mode",repeatMode).apply();
        if(player!=null)player.setRepeatMode(repeatMode==MODE_SINGLE_LOOP?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);
        update();
    }
    private void adjacent(int delta){if(externalLocked||track==null)return;
        /* 播放期间保留用户点击歌曲时的分类与顺序，
           不因后台元数据更新或浏览其它分类而更换播放队列。 */
        // Retain the displayed category snapshot across previous/next.
        if(queue.isEmpty())return;int at=-1;for(int i=0;i<queue.size();i++)if(queue.get(i).id().equals(track.id())){at=i;break;}
        /* 当前这首不在队列里（从搜索结果、其它目录点的播放）：按方向从头／从尾切入，别让按钮静默哑掉。 */
        if(at<0)at=delta>0?-1:queue.size();
        int next=at+delta;if(next<0||next>=queue.size()){if(repeatMode==MODE_LIST_LOOP)next=next<0?queue.size()-1:0;else{player.pause();return;}}load(queue.get(next));}
    private PendingIntent action(String action,int code){return PendingIntent.getService(this,code,new Intent(this,MusicPlaybackService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"music-playback").setSmallIcon(R.drawable.client_ic_audio).setLargeIcon(notificationCover).setContentTitle(track==null?"音乐播放":track.displayTitle()).setContentText(!error.isEmpty()?error:preparing?"正在准备音乐…":playing()?"正在播放":"已暂停").setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(R.drawable.client_player_icon_previous,"上一首",action(PREVIOUS,1)).build()).addAction(new Notification.Action.Builder(playing()?R.drawable.client_player_icon_pause:R.drawable.client_player_icon_play,playing()?"暂停":"播放",action(TOGGLE,2)).build()).addAction(new Notification.Action.Builder(R.drawable.client_player_icon_next,"下一首",action(NEXT,3)).build()).addAction(new Notification.Action.Builder(R.drawable.client_bottom_icon_cancel,"停止",action(STOP,4)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session==null?null:session.getSessionToken()).setShowActionsInCompactView(0,1,2)).build();
    }
    private void update(){if(session==null||player==null)return;session.setMetadata(new MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART,notificationCover).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,notificationCover).putString(MediaMetadata.METADATA_KEY_TITLE,track==null?"":track.displayTitle()).putLong(MediaMetadata.METADATA_KEY_DURATION,duration()).build());session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_STOP).setState(preparing?PlaybackState.STATE_BUFFERING:!error.isEmpty()?PlaybackState.STATE_ERROR:playing()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,position(),playing()?1:0).build());getSystemService(NotificationManager.class).notify(701,notification());}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){++generation;if(task!=null)task.cancel(true);worker.shutdownNow();coverWorker.shutdownNow();metadataWorker.shutdownNow();prewarmWorker.shutdownNow();if(player!=null)player.release();if(session!=null)session.release();instance=null;fullyReleased=true;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
