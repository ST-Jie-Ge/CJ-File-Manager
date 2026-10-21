package com.cj.music;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.cj.music.resources.R;

/** 文件管理器底部的迷你播放卡片：封面 + 曲名 + 播放/暂停 + 进度条。 */
final class MusicControls extends LinearLayout {
    private final TextView title,status,time;private final ImageView cover;private ImageButton toggle;private final SeekBar seek;private String last="";private boolean dragging;private boolean shown;
    /** 用户主动暂停后自动收起组件，恢复播放再展开。 */
    private boolean collapsed;private MusicPlaybackService lastService;private int lastVersion=-1;private android.animation.ValueAnimator heightAnim;
    /** 封面解码失败最多重试 3 次、每次间隔 150ms（比 500ms 主循环快，失败后尽快定论不再重解码）。 */
    private static final int COVER_TRIES=3,COVER_RETRY_DELAY=150;
    private int coverTries;private String coverKey;
    private final android.os.Handler coverRetry=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable coverRetryTask=new Runnable(){public void run(){MusicPlaybackService s=MusicPlaybackService.instance;if(s==null||s.track==null||coverKey==null||coverKey.equals(cover.getTag()))return;try{java.io.File f=MusicCache.cover(getContext(),s.track);if(f.isFile())loadCover(f);}catch(Exception ignored){}}};
    static int color(Context c,int attr){android.util.TypedValue v=new android.util.TypedValue();c.getTheme().resolveAttribute(attr,v,true);return v.data;}
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    /** 容器统一画“描边 + 底色”卡片底（按钮本身只留图标）。 */
    MusicControls(Context c){super(c);setOrientation(VERTICAL);setPadding(dp(14),dp(10),dp(14),dp(8));setBackgroundResource(R.drawable.client_music_panel_bg);
        setClipToPadding(false);setClipChildren(false);
        LinearLayout head=new LinearLayout(c);head.setGravity(Gravity.CENTER_VERTICAL);cover=new ImageView(c);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),dp(12));}});cover.setClipToOutline(true);head.addView(cover,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout names=new LinearLayout(c);names.setOrientation(VERTICAL);names.setPadding(dp(10),0,0,0);title=text(13);title.setMaxLines(1);title.setEllipsize(null);status=text(11);status.setMaxLines(1);names.addView(title);names.addView(status);head.addView(names,new LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        /* 控制按钮只留播放／暂停，纯图标放在标题行右侧（进度条上方）。 */
        toggle=button(R.drawable.client_player_icon_play,"播放／暂停",MusicPlaybackService.TOGGLE);toggle.setPadding(dp(11),dp(11),dp(11),dp(11));head.addView(toggle,new LinearLayout.LayoutParams(dp(40),dp(40)));
        addView(head);
        time=text(11);time.setGravity(Gravity.END);addView(time);
        seek=new SeekBar(c);addView(seek,new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(32)));seek.setContentDescription("音乐播放进度");seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){}public void onStartTrackingTouch(SeekBar b){dragging=true;}public void onStopTrackingTouch(SeekBar b){dragging=false;MusicPlaybackService s=MusicPlaybackService.instance;if(s!=null)s.seek(s.duration()*b.getProgress()/1000);}});seek.setMax(1000);
        /* 初始必须显式隐藏：update() 只在状态翻转时切换可见性，默认 VISIBLE 会让空卡片常驻。 */
        setVisibility(GONE);update();
    }
    private TextView text(int size){TextView t=new MusicMarqueeTextView(getContext());t.setTextSize(size);t.setTextColor(color(getContext(),R.attr.client_text));return t;}
    /** 控制按钮：无底无描边的纯图标，触摸反馈用无边框涟漪。 */
    private ImageButton button(int icon,String label,String action){ImageButton b=new ImageButton(getContext());b.setImageResource(icon);b.setColorFilter(color(getContext(),R.attr.client_text));android.util.TypedValue tv=new android.util.TypedValue();getContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.selectableItemBackgroundBorderless,tv,true);b.setBackgroundResource(tv.resourceId);b.setPadding(dp(12),dp(12),dp(12),dp(12));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(48));lp.leftMargin=lp.rightMargin=dp(4);b.setLayoutParams(lp);b.setContentDescription(label);b.setOnClickListener(v->MusicPlaybackService.command(getContext(),action));return b;}
    void update(){MusicPlaybackService s=MusicPlaybackService.instance;boolean hasTrack=s!=null&&s.track!=null;
        /* 界面隔离：只显示文件管理器发起的播放。 */
        boolean owned=hasTrack&&s.origin()==MusicPlaybackService.ORIGIN_BROWSER;
        if(s!=lastService){last="";cover.setTag(null);lastService=s;lastVersion=s==null?-1:s.loadVersion();collapsed=false;}
        else if(s!=null&&s.loadVersion()!=lastVersion){last="";cover.setTag(null);lastVersion=s.loadVersion();collapsed=false;}
        /* 用户主动暂停播放 → 自动收起组件；恢复播放 → 重新展开。 */
        if(owned){if(s.userPaused()&&!s.preparing)collapsed=true;else if(s.playing())collapsed=false;}
        boolean want=owned&&!collapsed;
        /* 显隐带动画：开始播放渐入展开（底栏随之长高），停止/取消播放渐出收起（底栏随之变矮）。 */
        if(want&&!shown){shown=true;reveal();}
        else if(!want&&shown){shown=false;conceal();}
        if(!want)return;
        if(!hasTrack){title.setText("音乐播放");status.setText("选择歌曲开始播放");status.setVisibility(VISIBLE);cover.setImageDrawable(new MusicFormatArtwork("audio"));last="";cover.setTag(null);return;}title.setText(s.track.displayTitle());
        /* 状态行：有歌手名就显示歌手名，不再显示「正在播放／已暂停」这类文案。 */
        String st=!s.error.isEmpty()?s.error:s.preparing?"正在准备音乐…":(s.artist==null?"":s.artist);
        status.setText(st);status.setVisibility(st.isEmpty()?GONE:VISIBLE);
        toggle.setImageResource(s.playing()?R.drawable.client_player_icon_pause:R.drawable.client_player_icon_play);toggle.setEnabled(!s.preparing);seek.setEnabled(!s.preparing&&s.duration()>0);if(!dragging)seek.setProgress(s.duration()==0?0:(int)(s.position()*1000/s.duration()));time.setText(clock(s.position())+" / "+clock(s.duration()));String id=s.track.id();if(!id.equals(last)){last=id;coverTries=0;coverKey=null;cover.setImageDrawable(new MusicFormatArtwork(s.track.name));cover.setTag(null);}if(s.track.profile.isEmpty()){if(s.localArtwork!=null&&cover.getTag()==null){cover.setImageBitmap(s.localArtwork);cover.setTag(id);}}else if(cover.getTag()==null)try{java.io.File f=MusicCache.cover(getContext(),s.track);if(f.isFile()){coverKey=id;loadCover(f);}}catch(Exception ignored){} }
    /**
     * 迷你播放器封面：解码失败时短延时重试，最多 3 次；仍失败就标记已处理，避免每帧重读重解码。
     * 封面文件还没写出来时不算失败——下一轮 update() 会自然再试。
     */
    /** 迷你播放器封面解码共用的一条低优先级线程：update() 每 500ms 跑一次，
        远程封面还没落盘时会反复重试，直接在主线程 decodeFile 就是持续掉帧。 */
    private static final java.util.concurrent.ExecutorService COVER_DECODER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"music-mini-cover");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private void loadCover(java.io.File file){
        if(coverKey==null||coverKey.equals(cover.getTag()))return;
        final String key=coverKey;
        COVER_DECODER.execute(()->{
            Bitmap bitmap=null;
            try{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(bounds.outWidth>0&&bounds.outHeight>0&&Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>256)options.inSampleSize*=2;bitmap=BitmapFactory.decodeFile(file.getPath(),options);}
            catch(Exception|OutOfMemoryError ignored){}
            final Bitmap result=bitmap;
            post(()->{
                if(result!=null){cover.setImageBitmap(result);cover.setTag(key);coverTries=0;return;}
                if(++coverTries<COVER_TRIES)coverRetry.postDelayed(coverRetryTask,COVER_RETRY_DELAY);
                else cover.setTag(key);
            });
        });
    }
    @Override protected void onDetachedFromWindow(){coverRetry.removeCallbacksAndMessages(null);super.onDetachedFromWindow();}
    /** 按当前可见内容量一次高度：宽度取容器实际宽度（首帧尚未布局时用屏宽减左右各 12dp 外边距）。 */
    private int measuredHeight(){
        int width=getWidth();if(width<=0)width=getResources().getDisplayMetrics().widthPixels-dp(24);
        measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        return getMeasuredHeight();
    }
    /** 渐入 + 展开高度：底栏随播放组件一起平滑长高。 */
    private void reveal(){
        if(heightAnim!=null)heightAnim.cancel();animate().cancel();
        setVisibility(VISIBLE);setAlpha(0f);
        ViewGroup.LayoutParams lp=getLayoutParams();
        if(lp==null){setAlpha(1f);return;} // 还没挂到宿主上，直接显示，跳过动画
        int spec=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED);measure(spec,spec);
        int target=Math.max(getMeasuredHeight(),dp(120));lp.height=0;setLayoutParams(lp);
        animate().alpha(1f).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator());
        heightAnim=android.animation.ValueAnimator.ofInt(0,target);
        heightAnim.setDuration(220);heightAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        heightAnim.addUpdateListener(a->{ViewGroup.LayoutParams p=getLayoutParams();if(p==null)return;p.height=(int)a.getAnimatedValue();setLayoutParams(p);});
        heightAnim.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(shown){ViewGroup.LayoutParams p=getLayoutParams();if(p!=null){p.height=ViewGroup.LayoutParams.WRAP_CONTENT;setLayoutParams(p);}}}});
        heightAnim.start();
    }
    /** 渐出 + 收起高度：底栏随播放组件一起平滑变矮，最后真正隐藏。 */
    private void conceal(){
        if(heightAnim!=null)heightAnim.cancel();animate().cancel();
        animate().alpha(0f).setDuration(180).setInterpolator(new android.view.animation.AccelerateInterpolator());
        ViewGroup.LayoutParams lp=getLayoutParams();
        if(lp==null){setVisibility(GONE);setAlpha(1f);return;}
        int from=lp.height>0?lp.height:Math.max(getHeight(),0);
        heightAnim=android.animation.ValueAnimator.ofInt(from,0);
        heightAnim.setDuration(200);heightAnim.setInterpolator(new android.view.animation.AccelerateInterpolator());
        heightAnim.addUpdateListener(a->{ViewGroup.LayoutParams p=getLayoutParams();if(p==null)return;p.height=(int)a.getAnimatedValue();setLayoutParams(p);});
        heightAnim.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(!shown){setVisibility(GONE);setAlpha(1f);ViewGroup.LayoutParams p=getLayoutParams();if(p!=null){p.height=ViewGroup.LayoutParams.WRAP_CONTENT;setLayoutParams(p);}}}});
        heightAnim.start();
    }
    private String clock(long ms){long s=ms/1000;return String.format(java.util.Locale.ROOT,"%d:%02d",s/60,s%60);}
}
