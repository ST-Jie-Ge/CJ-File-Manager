package com.cj.music;

import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.cj.music.resources.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 格式工厂：一个独立于文件管理器的转码界面（和音乐播放同一层级）。
 *
 * 转码跑在 FFmpegKit 自己的线程上，本界面只负责拼参数、显示进度、允许取消。
 * 参数一律用 String[] 传（executeWithArgumentsAsync），不走命令行字符串——
 * 文件名带空格、中文、引号时不会被拆错，这是拼 -i 参数最容易踩的坑。
 *
 * 返回：空闲时两秒内连按两次返回文件管理器；转码时先确认是否停止任务。
 */
public final class FormatFactoryActivity extends AppCompatActivity {
    private final Handler ui=new Handler(Looper.getMainLooper());

    /* ===== 输入 ===== */
    private File sourceFile;private String sourceArg="",sourceName="";
    private long durationMs,sourceBytes;private int srcW,srcH;
    /* ===== 选项 ===== */
    private static final String[] FORMATS={"MP4","MKV","WebM","AVI","MOV","GIF","MP3"};
    private static final String[] EXTENSIONS={"mp4","mkv","webm","avi","mov","gif","mp3"};
    private static final String[] RESOLUTIONS={"原始分辨率","1080P","720P","480P"};
    private static final int[] RESOLUTION_WIDTH={0,1920,1280,854};
    /* 视频三档叫「画质」（CRF/qscale），音频三档叫「品质」（码率 / 压缩级别）——
       同一组下标，两套文案：音频那一栏再写「高画质」就是文案错误了。 */
    private static final String[] QUALITIES={"高画质","标准","高压缩"};
    private static final String[] AUDIO_QUALITIES={"高品质","标准","高压缩"};
    private static final String[] AUDIOS={"复制原音轨","AAC 128k","MP3 192k","无音频"};
    private static final String[] AUDIO_FORMATS={"MP3","FLAC","WAV","M4A (AAC)","OGG (Opus)","OGG (Vorbis)","M4A (ALAC)"};
    private static final String[] AUDIO_EXTENSIONS={"mp3","flac","wav","m4a","ogg","ogg","m4a"};
    private static final int TYPE_UNSET=-1,TYPE_VIDEO=0,TYPE_AUDIO=1;
    private int formatIndex,codecIndex,resolutionIndex=1,qualityIndex=1,audioIndex,audioFormatIndex;
    private int sourceType=TYPE_UNSET;
    /* ===== 运行状态 ===== */
    private FFmpegSession session;private boolean running;private long startedAt;private File output;
    private long lastBackAt;
    /** 探元数据的后台执行器与代次：连选文件时只有最后一次的结果允许写回状态。 */
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int loadGeneration;
    /* ===== 视图 ===== */
    /** 视图层已改成 Compose（FormatFactoryScreen.kt）：Java 只留 FFmpeg 逻辑，
        界面状态全部写进这个对象，Compose 读它重组。 */
    private FormatUiState uiState=new FormatUiState();
    /** 内容列左右内边距：手机上 20dp；平板/横屏把整列封顶 720dp 并水平居中，卡片不会被拉成一整屏宽。 */
    private int contentInset;

    @Override protected void onCreate(Bundle state){
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(state);
        contentInset=contentInset();
        uiState.setAccent(MusicControls.color(this,R.attr.client_accent));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MusicControls.color(this,R.attr.client_surface_raised));
        getWindow().setStatusBarColor(MusicControls.color(this,R.attr.client_surface_raised));
        getWindow().setNavigationBarColor(MusicControls.color(this,R.attr.client_surface_raised));
        setContentView(root);
        ClientWindowInsets.apply(this);
        /* 整个界面就是一块 ComposeView；原来那套卡片 / ChipGroup / Spinner / 底部胶囊都不要了。 */
        root.addView(FormatFactoryScreenKt.formatFactoryView(this,uiState),new LinearLayout.LayoutParams(-1,-1));
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){handleBack();}});
        pushFormatState();
    }

    @Override protected void onDestroy(){
        super.onDestroy();
        /* 转码跑在 FFmpegKit 自己的线程上，完成 / 统计回调又都是本类的实例方法引用——
           界面销毁时不取消会话，FFmpegKit 会一直持有本 Activity 直到转码跑完：
           长视频几分钟里白烧 CPU/电量、继续写输出文件，finishRun 还会操作已销毁的
           Activity（历史入库 + MediaScanner 扫描）。cancel() 幂等，用户主动「停止」
           走的也是同一条路。 */
        if(session!=null)session.cancel();
        io.shutdownNow();
    }

    /** 内容列封顶 720dp：平板上左右留出对称空白并居中，卡片宽度、药丸换行和间距才不会失控。 */
    private int contentInset(){
        int screen=getResources().getDisplayMetrics().widthPixels,cap=dp(720);
        return screen>cap?Math.round((screen-cap)/2f)+dp(20):dp(20);
    }

    /* ===== 顶部：极简返回 + 说明，不把操作区挤进标题栏 ===== */

    /* ===== 输入文件 ===== */
    private void choose(){
        if(sourceType==TYPE_UNSET){Toast.makeText(this,"请先选择视频或音频",Toast.LENGTH_SHORT).show();return;}
        if(android.os.Build.VERSION.SDK_INT>=30&&!android.os.Environment.isExternalStorageManager()){
            new MaterialAlertDialogBuilder(this).setTitle("需要文件访问权限")
                .setMessage("选择本地媒体文件需要「所有文件访问权限」。")
                .setNegativeButton("取消",null)
                .setPositiveButton("去授权",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName()))))
                .show();return;
        }
        LocalMediaPicker.choose(this,sourceType,path->{if(path!=null)load(path);});
    }
    private void load(String path){
        final int ticket=++loadGeneration;
        sourceFile=new File(path);sourceArg=path;
        sourceName=sourceFile.getName();durationMs=0;sourceBytes=sourceFile.length();srcW=0;srcH=0;lastFrame=null;
        output=null;uiState.setResult(FormatResult.NONE);
        pushFormatState();
        /* MediaMetadataRetriever.setDataSource + 解码首帧：4K 源 / 慢存储上要几百毫秒到数秒，
           留在主线程就是 ANR。整段挪后台；连选两个文件时按 loadGeneration 只认最后一次，
           旧任务的结果不回头覆盖新文件的状态。 */
        io.execute(()->{
            long duration=0;int width=0,height=0;android.graphics.Bitmap frame=null;
            MediaMetadataRetriever r=new MediaMetadataRetriever();
            try{r.setDataSource(path);
                String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String w=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if(d!=null)try{duration=Long.parseLong(d);}catch(NumberFormatException ignored){}
                if(w!=null)try{width=Integer.parseInt(w);}catch(NumberFormatException ignored){}
                if(h!=null)try{height=Integer.parseInt(h);}catch(NumberFormatException ignored){}
                String rotation=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if("90".equals(rotation)||"270".equals(rotation)){int swap=width;width=height;height=swap;}
                try{frame=r.getScaledFrameAtTime(-1,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,480);}catch(Throwable ignored){}
                if(frame==null)try{frame=android.media.ThumbnailUtils.createVideoThumbnail(sourceFile,new android.util.Size(480,480),null);}catch(Exception ignored){}
                if(frame==null)try{frame=r.getFrameAtTime(-1);}catch(Throwable ignored){}
            }catch(Throwable t){
                /* 不是媒体文件（或解码器不支持）：sourceArg 仍然有效，交给 ffmpeg 自己判断 */
            }finally{try{r.release();}catch(Throwable ignored){}}
            final long shownDuration=duration;final int shownWidth=width,shownHeight=height;final android.graphics.Bitmap shownFrame=frame;
            ui.post(()->{
                if(isDestroyed()||ticket!=loadGeneration)return;
                durationMs=shownDuration;srcW=shownWidth;srcH=shownHeight;lastFrame=shownFrame;
                pushFormatState();
            });
        });
    }
    private android.graphics.Bitmap lastFrame;
    /** 把当前输入状态推给 Compose。 */
    private void pushFormatState(){
        if(uiState==null)return;
        uiState.setSourceType(sourceType);
        uiState.setHasSource(sourceFile!=null);
        uiState.setPreviewFrame(lastFrame);
        uiState.setSourceName(sourceName);
        uiState.setSourceInfo(describe()+(sourceType==TYPE_AUDIO&&srcW>0?"\n仅提取音轨":""));
        uiState.setFormatIndex(formatIndex);
        uiState.setCodecIndex(Math.min(codecIndex,codecNames().length-1));
        uiState.setResolutionIndex(resolutionIndex);
        uiState.setQualityIndex(qualityIndex);
        uiState.setAudioIndex(audioIndex);
        uiState.setAudioFormatIndex(audioFormatIndex);
        uiState.setRunning(running);
        uiState.bump();
    }
    private String describe(){
        StringBuilder b=new StringBuilder();
        if(srcW>0&&srcH>0)b.append(srcW).append("×").append(srcH).append("  ·  ");
        if(durationMs>0)b.append(clock(durationMs)).append("  ·  ");
        if(sourceBytes>0)b.append(android.text.format.Formatter.formatFileSize(this,sourceBytes));
        String s=b.toString();return s.isEmpty()?"已就绪":s;
    }

    /* ===== 选项 ===== */
    private String[] codecNames(){
        switch(formatIndex){
            case 0:return new String[]{"H.264","H.265","MPEG-4"};
            case 1:return new String[]{"H.264","H.265","VP9"};
            case 2:return new String[]{"VP9"};
            case 3:return new String[]{"MPEG-4","H.264"};
            case 4:return new String[]{"H.264","H.265"};
            default:return new String[]{"默认"};
        }
    }
    private String codecName(){String[] n=codecNames();return n[Math.min(codecIndex,n.length-1)];}
    private boolean audioHasQuality(){return audioFormatIndex!=2&&audioFormatIndex!=6;}
    private String videoEncoder(){
        String n=codecName();
        if("H.264".equals(n))return "libx264";
        if("H.265".equals(n))return "libx265";
        if("VP9".equals(n))return "libvpx-vp9";
        return "mpeg4";
    }
    /** CRF / qscale：数值越小画质越好、文件越大，三档随编码器各取一套经验值。 */
    private String qualityValue(){
        int q=qualityIndex;String enc=videoEncoder();
        if("libx264".equals(enc))return new String[]{"18","23","28"}[q];
        if("libx265".equals(enc))return new String[]{"22","28","32"}[q];
        if("libvpx-vp9".equals(enc))return new String[]{"24","32","40"}[q];
        return new String[]{"2","4","6"}[q];
    }
    /** 按显示方向适配长短边，保持比例且不放大小尺寸源。 */
    private int scaleWidth(int fallback){
        int want=RESOLUTION_WIDTH[resolutionIndex];
        int shortEdge=new int[]{0,1080,720,480}[resolutionIndex];
        return MediaScale.width(srcW,srcH,want,shortEdge,fallback);
    }

    /* ===== 转码 ===== */
    private void start(){
        if(running)return;
        if(sourceFile==null||sourceArg.isEmpty()){Toast.makeText(this,"请先选择一个文件",Toast.LENGTH_SHORT).show();return;}
        if(android.os.Build.VERSION.SDK_INT>=30&&!android.os.Environment.isExternalStorageManager()){
            new MaterialAlertDialogBuilder(this).setTitle("需要文件访问权限")
                .setMessage("把转好的文件保存到 Movies/格式工厂 需要「所有文件访问权限」。")
                .setNegativeButton("取消",null)
                .setPositiveButton("去授权",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName()))))
                .show();return;
        }
        File dir=outputDir();if(dir==null){Toast.makeText(this,"无法创建输出目录",Toast.LENGTH_SHORT).show();return;}
        output=nextFile(dir);if(output==null){Toast.makeText(this,"无法写入输出文件",Toast.LENGTH_SHORT).show();return;}
        String[] command=buildCommand(output);
        running=true;startedAt=android.os.SystemClock.elapsedRealtime();
        /* 进度、状态文字、底部胶囊全部由 Compose 按 uiState 画，这里只推状态。 */
        uiState.setResult(FormatResult.NONE);uiState.setRunning(true);
        uiState.setProgress(0);uiState.setStatus("准备中…");uiState.bump();
        lastUi=0;
        session=FFmpegKit.executeWithArgumentsAsync(command,
            s->{final boolean ok=ReturnCode.isSuccess(s.getReturnCode());
                final boolean cancelled=ReturnCode.isCancel(s.getReturnCode());
                final String stack=s.getFailStackTrace()!=null?s.getFailStackTrace():s.getAllLogsAsString();
                ui.post(()->finishRun(ok,cancelled,stack));},
            null,
            statistics->{
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-lastUi<150)return;lastUi=now;
                ui.post(()->updateProgress(statistics));
            });
    }
    private volatile long lastUi;
    private void updateProgress(Statistics s){
        if(!running||uiState==null||isDestroyed())return;
        /* Statistics.getTime() 本身就是毫秒，和 MediaMetadataRetriever 给的 durationMs 同单位，
           直接相除即可——之前多除了一次 10，进度条最多只能爬到真实进度的十分之一，看着就像假的。 */
        long done=Math.max(0,(long)s.getTime());
        StringBuilder b=new StringBuilder();
        if(durationMs>0){
            int percent=(int)Math.max(0,Math.min(100,Math.round(100.0*Math.min(done,durationMs)/durationMs)));
            uiState.setProgress(percent);
            b.append(percent).append("%  ·  ").append(clock(done)).append(" / ").append(clock(durationMs));
        }else{
            /* 探不到总时长（少数格式）就别编一个百分比：转不定态，只报已处理的时间。 */
            uiState.setProgress(-1);
            b.append("已处理 ").append(clock(done)).append("（无法预知总时长）");
        }
        if(s.getSize()>0)b.append("  ·  ").append(android.text.format.Formatter.formatFileSize(this,s.getSize()));
        if(s.getSpeed()>0)b.append("  ·  ").append(String.format(Locale.ROOT,"%.1f×",s.getSpeed()));
        uiState.setStatus(b.toString());uiState.bump();
    }
    private void cancel(){
        if(!running)return;
        new MaterialAlertDialogBuilder(this).setTitle("停止这次转换？").setMessage("已生成的部分会被丢弃。")
            .setNegativeButton("继续转换",null).setPositiveButton("停止",(d,w)->{if(session!=null)session.cancel();}).show();
    }
    private void finishRun(boolean ok,boolean cancelled,String stack){
        running=false;
        /* 半成品产物先删：取消 / 失败都不留 .part 式的残file。
           这段不依赖界面存活——onDestroy 里取消转码后也会走到这里。 */
        if(!ok&&output!=null&&output.exists())output.delete();
        /* 界面已经没了（onDestroy 取消、旋转重建、系统回收）：不再碰 UI / 历史 / 媒体扫描，
           否则 MediaScannerConnection 与 FormatHistory 会操作已销毁的 Activity。 */
        if(isDestroyed()||isFinishing())return;
        uiState.setRunning(false);
        if(!ok){
            uiState.setResult(FormatResult.FAILED);
            uiState.setResultTitle(cancelled?"已取消转换":"转换失败");
            uiState.setResultDetail(!cancelled&&stack!=null&&!stack.isEmpty()?(sourceType==TYPE_AUDIO&&stack.contains("matches no streams")?"这个文件没有可提取的音轨，请选择带声音的视频或音频文件。":tail(stack)):"");
            uiState.bump();
            return;
        }
        long seconds=Math.max(1,(android.os.SystemClock.elapsedRealtime()-startedAt)/1000);
        uiState.setResult(FormatResult.DONE);
        uiState.setResultTitle("转换完成");
        uiState.setResultDetail(output.getName()+"  ·  "+(output.exists()?android.text.format.Formatter.formatFileSize(this,output.length()):"—")+"  ·  用时 "+seconds+" 秒");
        uiState.bump();
        /* 成功一条就记一条历史（最新的在最前），转换历史页直接读这里。 */
        FormatHistory.add(this,new FormatHistory.Item(output.getName(),output.getAbsolutePath(),sourceName,outputMime(),output.length(),seconds,System.currentTimeMillis()));
        MediaScannerConnection.scanFile(this,new String[]{output.getAbsolutePath()},null,null);
    }
    private String tail(String stack){String[] lines=stack.split("\n");StringBuilder b=new StringBuilder();
        for(int i=Math.max(0,lines.length-3);i<lines.length;i++)b.append(lines[i].trim()).append("\n");return b.toString().trim();}
    private void play(){
        if(output==null||!output.exists())return;
        try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".client.exports",output);
            Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(uri,outputMime());i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);
        }catch(Exception e){Toast.makeText(this,"无法打开这个文件",Toast.LENGTH_SHORT).show();}
    }
    private void share(){
        if(output==null||!output.exists())return;
        try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".client.exports",output);
            Intent i=new Intent(Intent.ACTION_SEND);i.setType(outputMime());i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"分享转好的文件"));
        }catch(Exception e){Toast.makeText(this,"无法分享这个文件",Toast.LENGTH_SHORT).show();}
    }
    private File outputDir(){
        File dir=new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),"格式工厂");
        if(!dir.isDirectory()&&!dir.mkdirs())return getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        return dir.isDirectory()?dir:getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
    }
    private String outputMime(){String ext=sourceType==TYPE_AUDIO?AUDIO_EXTENSIONS[audioFormatIndex]:EXTENSIONS[formatIndex];return FormatHistory.mimeFor(output==null?"output."+ext:output.getName(),sourceType==TYPE_AUDIO?"audio/*":"video/*");}
    private File nextFile(File dir){
        String base=strip(sourceName);if(base.isEmpty())base="输出";
        String ext=sourceType==TYPE_AUDIO?AUDIO_EXTENSIONS[audioFormatIndex]:EXTENSIONS[formatIndex];
        for(int i=0;i<200;i++){
            File f=new File(dir,base+(i==0?"":"_"+i)+"."+ext);
            if(!f.exists())return f;
        }
        return null;
    }
    private static String strip(String name){
        if(name==null)return "";int dot=name.lastIndexOf('.');
        String s=dot>0?name.substring(0,dot):name;
        return s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim().replaceAll("[. ]+$","");
    }
    private String[] buildCommand(File out){
        List<String> a=new ArrayList<>();
        a.add("-y");a.add("-i");a.add(sourceArg);
        if(formatIndex==5){
            int w=scaleWidth(480);if(w<=0)w=480;
            a.add("-vf");a.add("fps=12,scale="+w+":-1:flags=lanczos");
            a.add("-c:v");a.add("gif");a.add("-an");
        }else if(formatIndex==6){
            a.add("-map");a.add("0:a:0");
            a.add("-vn");a.add("-sn");a.add("-dn");
            switch(audioFormatIndex){
                case 0:a.add("-c:a");a.add("libmp3lame");a.add("-b:a");a.add(new String[]{"320k","192k","128k"}[qualityIndex]);break;
                case 1:a.add("-c:a");a.add("flac");a.add("-compression_level");a.add(new String[]{"3","5","8"}[qualityIndex]);break;
                case 2:a.add("-c:a");a.add("pcm_s16le");break;
                case 3:a.add("-c:a");a.add("aac");a.add("-b:a");a.add(new String[]{"256k","160k","96k"}[qualityIndex]);break;
                case 4:a.add("-c:a");a.add("libopus");a.add("-b:a");a.add(new String[]{"192k","128k","64k"}[qualityIndex]);break;
                case 5:a.add("-c:a");a.add("libvorbis");a.add("-q:a");a.add(new String[]{"6","4","2"}[qualityIndex]);break;
                default:a.add("-c:a");a.add("alac");break;
            }
        }else{
            String enc=videoEncoder();
            a.add("-c:v");a.add(enc);
            if(enc.startsWith("libx")){a.add("-preset");a.add("veryfast");}
            if("mpeg4".equals(enc)){a.add("-qscale:v");a.add(qualityValue());}
            else{a.add("-crf");a.add(qualityValue());}
            if("libvpx-vp9".equals(enc)){a.add("-b:v");a.add("0");a.add("-row-mt");a.add("1");}
            int w=scaleWidth(1280);
            if(w>0){a.add("-vf");a.add("scale="+w+":-2");}
            /* 「无音频」一律 -an：WebM 也一样（它只是装不下原音轨，不带音轨完全可以）。 */
            if(audioIndex==3){a.add("-an");}
            else if("webm".equals(EXTENSIONS[formatIndex])){a.add("-c:a");a.add("libopus");a.add("-b:a");a.add("96k");}
            else if(audioIndex==0){a.add("-c:a");a.add("copy");}
            else if(audioIndex==1){a.add("-c:a");a.add("aac");a.add("-b:a");a.add("128k");}
            else{a.add("-c:a");a.add("libmp3lame");a.add("-b:a");a.add("192k");}
            /* faststart 把 moov 原子挪到文件头：mp4/mov 能被边下边播，本地播放也是秒开。 */
            String ext=EXTENSIONS[formatIndex];
            if("mp4".equals(ext)||"mov".equals(ext)){a.add("-movflags");a.add("+faststart");}
        }
        a.add("-threads");a.add(String.valueOf(Math.max(1,Runtime.getRuntime().availableProcessors()-1)));
        a.add(out.getAbsolutePath());
        return a.toArray(new String[0]);
    }

    /* ===== 返回 ===== */
    /** 返回优先回到「视频 / 音频」选择页；已经在选择页时才两秒内连按两次退出格式工厂。 */
    void handleBack(){
        if(running){
            new MaterialAlertDialogBuilder(this).setTitle("正在转换").setMessage("返回文件管理器会停止这次转换，已生成的部分会被丢弃。")
                .setNegativeButton("继续转换",null).setPositiveButton("停止并返回",(d,w)->{if(session!=null)session.cancel();finish();}).show();
            return;
        }
        if(sourceType!=TYPE_UNSET){resetToTypePicker();return;}
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastBackAt<2000){finish();return;}
        lastBackAt=now;
        Toast.makeText(this,"再按一次返回文件管理器",Toast.LENGTH_SHORT).show();
    }
    /** 退回类型选择页：把已选文件与结果一并清空，避免再进来时看到上一轮的东西。 */
    private void resetToTypePicker(){
        sourceType=TYPE_UNSET;sourceFile=null;sourceArg="";sourceName="";
        durationMs=0;sourceBytes=0;srcW=0;srcH=0;lastFrame=null;output=null;
        uiState.setResult(FormatResult.NONE);
        pushFormatState();
    }

    /* ===== 小工具 ===== */
    private boolean isWide(){return getResources().getConfiguration().screenWidthDp>=600;}
    /** 选项药丸：用 ChipGroup（自带换行），比手写 FlowLayout 稳；选中态靠 checkable + 描边变化区分。 */
    private static String clock(long ms){
        long total=Math.max(0,ms)/1000;
        return String.format(Locale.ROOT,"%02d:%02d:%02d",total/3600,total%3600/60,total%60);
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private int statusBarHeight(){
        int id=getResources().getIdentifier("status_bar_height","dimen","android");
        return id>0?getResources().getDimensionPixelSize(id):dp(24);
    }

    /* ---- Compose 界面的桥接 ---- */
    int pageContentInset(){return contentInset;}
    boolean formatIsWide(){return isWide();}
    android.graphics.Bitmap formatFrame(){return lastFrame;}
    void formatChoose(){choose();}
    /** 输出目录：回到文件管理器并直接打开 Movies/格式工厂。 */
    private void openOutputDir(){
        File dir=outputDir();
        if(dir==null){Toast.makeText(this,"输出目录不可用",Toast.LENGTH_SHORT).show();return;}
        try{startActivity(new Intent(this,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_path",dir.getAbsolutePath()));}
        catch(Exception e){Toast.makeText(this,"无法打开输出目录",Toast.LENGTH_SHORT).show();}
    }
    private void openHistory(){startActivity(new Intent(this,FormatHistoryActivity.class));}
    void formatOpenOutputDir(){openOutputDir();}
    void formatOpenHistory(){openHistory();}
    void formatPickType(int type){sourceType=type;formatIndex=type==TYPE_AUDIO?6:0;sourceFile=null;lastFrame=null;durationMs=0;sourceBytes=0;srcW=0;srcH=0;pushFormatState();}
    void formatSetFormat(int index){formatIndex=index;codecIndex=Math.min(codecIndex,codecNames().length-1);pushFormatState();}
    void formatSetAudioFormat(int index){audioFormatIndex=index;pushFormatState();}
    void formatSetCodec(int index){codecIndex=index;pushFormatState();}
    void formatSetResolution(int index){resolutionIndex=index;pushFormatState();}
    void formatSetQuality(int index){qualityIndex=index;pushFormatState();}
    void formatSetAudio(int index){audioIndex=index;pushFormatState();}
    String[] formatCodecNames(){return codecNames();}
    String[] formatAudioFormats(){return AUDIO_FORMATS;}
    String[] formatAudioQualities(){return AUDIO_QUALITIES;}
    String[] formatVideoQualities(){return QUALITIES;}
    boolean formatAudioHasQuality(){return audioHasQuality();}
    void formatStart(){start();}
    void formatCancel(){cancel();}
    void formatPlay(){play();}
    void formatShare(){share();}
}
