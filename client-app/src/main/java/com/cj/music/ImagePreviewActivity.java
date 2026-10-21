package com.cj.music;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.cj.music.net.RemoteSession;
import com.cj.music.resources.R;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Preview the current directory's images; unsupported entries are skipped on navigation. */
public class ImagePreviewActivity extends AppCompatActivity {
    static final int REQUEST_PREVIEW=14;
    static final class Item implements Serializable {
        final String path,name,profile,mime;
        Item(String path,String name,String profile,String mime){this.path=path;this.name=name;this.profile=profile;this.mime=mime;}
    }
    // Keep the full directory snapshot out of Binder. The current item is also in the intent
    // so the activity can still be restored after process death.
    private static final Map<String,ArrayList<Item>> snapshots=new ConcurrentHashMap<>();
    static void launch(Activity activity,ArrayList<Item> items,int index){
        String key=UUID.randomUUID().toString();snapshots.put(key,new ArrayList<>(items));
        Intent intent=new Intent(activity,ImagePreviewActivity.class);intent.putExtra("snapshot",key);intent.putExtra("index",index);intent.putExtra("item",items.get(index));activity.startActivityForResult(intent,REQUEST_PREVIEW);
    }
    static boolean isImage(String name,String mime){
        // PSD 只出列表缩略图：它本身解不出来，放进预览必然失败，还会被记成「不支持」连累整轮滑动。
        if(PsdThumbnail.matches(name))return false;
        if(mime!=null&&mime.startsWith("image/"))return true;
        String lower=name.toLowerCase(Locale.ROOT);int dot=lower.lastIndexOf('.');
        return dot>=0&&Arrays.asList("jpg","jpeg","jfif","png","webp","gif","bmp","heic","heif","avif","ico","svg","tif","tiff").contains(lower.substring(dot+1));
    }
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    /** io 线程 add、UI 线程 clear（删除/插入后索引整体失效），必须同步——
        否则 HashSet 并发 add/clear 可能丢标记，极端情况抛 ConcurrentModificationException。 */
    private final Set<Integer> unsupported=Collections.synchronizedSet(new HashSet<>());
    private ArrayList<Item> items;
    private String snapshot;
    private int index=-1,imageTransition;
    private volatile int generation;
    private volatile RemoteSession transfer;
    private ImageView image,previousImage;
    private TextView title,message;
    private ProgressBar spinner;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Runnable pendingLoading;
    private LinearLayout actions;
    private ImageButton favoriteButton;
    private File currentFile;
    private PreviewFileCache previewCache;
    private float downX,downY;
    private boolean swipeCandidate;
    /* —— 缩放 / 平移 ——
       双指捏合放大、双指拖动平移；放大后单指也能平移（不至于想看点细节就滑到下一张）。
       松手 30 秒没有任何操作 → 带动画回到「适应屏幕」的原始位置与 1 倍缩放。 */
    private static final float MAX_ZOOM=5f;
    private static final long RESTORE_DELAY_MS=30_000L;
    private static final long RESTORE_ANIMATION_MS=320L;
    private float zoom=1f,panX=0f,panY=0f,lastSingleX,lastSingleY;
    private final float[] lastFocus={Float.NaN,Float.NaN};
    private Runnable restoreTask;
    private android.animation.ValueAnimator restoreAnimation;
    @Override protected void onCreate(Bundle state){
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);super.onCreate(state);
        // 预览页是纯黑背景：状态栏/导航栏也置黑、图标转白，否则浅色状态栏与黑底冲突、标题栏被顶到状态栏下。
        getWindow().setStatusBarColor(0xfffafafa);
        getWindow().setNavigationBarColor(0xfffafafa);
        new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView()).setAppearanceLightNavigationBars(true);
        snapshot=getIntent().getStringExtra("snapshot");items=snapshot==null?null:snapshots.get(snapshot);
        if(items==null){items=new ArrayList<>();Object raw=state==null?getIntent().getSerializableExtra("item"):state.getSerializable("item");Item item=raw instanceof Item?(Item)raw:null;if(item!=null)items.add(item);}
        if(items.isEmpty()){finish();return;}
        previewCache=new PreviewFileCache(getCacheDir());
        io.execute(()->PreviewFileCache.cleanOrphans(getCacheDir()));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfffafafa);root.setFitsSystemWindows(true);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=button("‹",()->finish());top.addView(back,new LinearLayout.LayoutParams(dp(52),dp(52)));
        title=new TextView(this);title.setTextColor(0xff202024);title.setTextSize(15);title.setGravity(Gravity.CENTER_VERTICAL);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(top);
        FrameLayout stage=new FrameLayout(this);previousImage=new ImageView(this);previousImage.setScaleType(ImageView.ScaleType.FIT_CENTER);stage.addView(previousImage,new FrameLayout.LayoutParams(-1,-1));
        image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);stage.addView(image,new FrameLayout.LayoutParams(-1,-1));
        message=new TextView(this);message.setTextColor(0xff202024);message.setGravity(Gravity.CENTER);stage.addView(message,new FrameLayout.LayoutParams(-1,-1));root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        spinner=new ProgressBar(this);spinner.setVisibility(View.GONE);spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff202024));
        stage.addView(spinner,new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER));
        actions=new LinearLayout(this);actions.setPadding(dp(20),dp(12),dp(20),dp(20));actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(photoAction("share","分享",()->external(false),true),new LinearLayout.LayoutParams(dp(48),dp(48)));
        View spacer=new View(this);actions.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        LinearLayout pill=new LinearLayout(this);pill.setBackground(PhotoUi.bg(0xfff0f0f2,dp(40)));
        favoriteButton=photoAction("heart","收藏",this::toggleFavorite,false);pill.addView(favoriteButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        pill.addView(photoAction("info","图片信息",this::showInfo,false),new LinearLayout.LayoutParams(dp(48),dp(48)));
        pill.addView(photoAction("sliders","编辑图片",this::edit,false),new LinearLayout.LayoutParams(dp(48),dp(48)));
        actions.addView(pill);actions.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        actions.addView(photoAction("trash","删除图片",this::deleteImage,true),new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(actions);setContentView(root);ClientWindowInsets.apply(this);
        final android.view.ScaleGestureDetector scaleDetector=new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){
                    @Override public boolean onScale(android.view.ScaleGestureDetector detector){
                        zoomAround(zoom*detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());
                        return true;
                    }
                });
        image.setOnTouchListener((view,event)->{
            if(!actions.isEnabled())return true;
            scaleDetector.onTouchEvent(event);
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    cancelRestore();
                    downX=event.getX();downY=event.getY();
                    lastSingleX=downX;lastSingleY=downY;lastFocus[0]=Float.NaN;swipeCandidate=true;return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    swipeCandidate=false;lastFocus[0]=Float.NaN;
                    lastSingleX=event.getX(0);lastSingleY=event.getY(0);return true;
                case MotionEvent.ACTION_MOVE:
                    if(event.getPointerCount()>=2){
                        // 双指拖动：按两指中心点位移平移，捏合缩放由上面的 detector 负责。
                        swipeCandidate=false;
                        float cx=(event.getX(0)+event.getX(1))/2f,cy=(event.getY(0)+event.getY(1))/2f;
                        if(!Float.isNaN(lastFocus[0])){
                            panX+=cx-lastFocus[0];panY+=cy-lastFocus[1];clampPan();applyTransform();
                        }
                        lastFocus[0]=cx;lastFocus[1]=cy;
                    }else if(zoom>1.001f){
                        panX+=event.getX()-lastSingleX;panY+=event.getY()-lastSingleY;
                        lastSingleX=event.getX();lastSingleY=event.getY();
                        clampPan();applyTransform();swipeCandidate=false;
                    }else{
                        lastSingleX=event.getX();lastSingleY=event.getY();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    /* 抬起一根手指后剩下一根继续拖：把「上一帧单指位置」更新到剩下那根，
                       否则下一次 MOVE 会拿一个很早的坐标算位移，画面会突然跳一下。 */
                    lastFocus[0]=Float.NaN;
                    int remaining=event.getActionIndex()==0?1:0;
                    if(remaining<event.getPointerCount()){
                        lastSingleX=event.getX(remaining);lastSingleY=event.getY(remaining);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    swipeCandidate=false;scheduleRestore();return true;
                case MotionEvent.ACTION_UP:
                    float dx=event.getX()-downX,dy=event.getY()-downY;
                    // 只在原始比例下才用单指左右滑切图；放大状态下左右滑是平移，不能把人甩到下一张。
                    if(swipeCandidate&&zoom<=1.001f&&Math.abs(dx)>dp(64)&&Math.abs(dx)>Math.abs(dy)*1.5f&&index>=0)navigate(dx<0?1:-1);
                    else view.performClick();
                    swipeCandidate=false;scheduleRestore();return true;
                default:return true;
            }
        });
        int start=state==null?getIntent().getIntExtra("index",0):state.getInt("index",0);load(Math.min(start,items.size()-1),1,true);
    }
    private TextView button(String text,Runnable action){TextView view=new TextView(this);view.setText(text);view.setTextColor(0xff202024);view.setGravity(Gravity.CENTER);view.setTextSize(14);view.setClickable(true);view.setFocusable(true);view.setOnClickListener(v->action.run());return view;}
    /** 底部功能按钮：纯黑底上只有白字会糊成一片，加一圈白色圆角描边把按钮边界画出来。 */
    private TextView action(String text,Runnable action){
        TextView view=button(text,action);
        view.setBackgroundResource(R.drawable.client_preview_action);
        view.setTextSize(13);view.setSingleLine(true);view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(46),1);
        params.setMarginStart(dp(5));params.setMarginEnd(dp(5));
        view.setLayoutParams(params);
        return view;
    }
    private void navigate(int direction){load(index+direction,direction,false);}
    private void load(int start,int direction,boolean initial){
        final int ticket=++generation;RemoteSession active=transfer;if(active!=null)active.close();
        /* items 是普通 ArrayList：UI 线程的 deleteImage()/locateSaved() 会 remove/add，
           io 线程下面这个 while 循环只读。两边并发访问既是 IndexOutOfBoundsException
           （删除恰好落在 size 判断与 get 之间）也是 ConcurrentModificationException 的来源。
           这里在 UI 线程拷一份快照，io 线程全程只碰快照——列表再变也是下一次 load() 的事
           （删除/插入后都会调 load()，generation 递增让旧回调自动作废）。 */
        final ArrayList<Item> list=new ArrayList<>(items);
        message.setVisibility(View.GONE);showLoading(ticket);enabled(false);
        io.execute(()->{
            int candidate=start;Bitmap bitmap=null;File file=null;String failure=null;
            while(candidate>=0&&candidate<list.size()&&ticket==generation){
                if(unsupported.contains(candidate)){candidate+=direction;continue;}
                Item item=list.get(candidate);
                try{file=resolve(item,ticket);ImageDecoder.Source source=item.path.startsWith("content:")&&item.profile.isEmpty()?ImageDecoder.createSource(getContentResolver(),Uri.parse(item.path)):ImageDecoder.createSource(file);
                    bitmap=ImageDecoder.decodeBitmap(source,(decoder,info,src)->{int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,4096f/Math.max(w,h));decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});break;
                }catch(Exception|OutOfMemoryError error){
                    previewCache.release(file);file=null;
                    if(ticket!=generation)return;
                    // A network or permission error is recoverable; only decoding failures skip entries.
                    if(error instanceof ImageDecoder.DecodeException||error instanceof IllegalArgumentException){unsupported.add(candidate);candidate+=direction;continue;}
                    failure=error.getMessage()==null?"无法读取图片":error.getMessage();break;
                }
            }
            if(bitmap==null&&initial&&start>0&&direction>0&&failure==null){runOnUiThread(()->{if(ticket==generation)load(start-1,-1,false);});return;}
            final Bitmap shown=bitmap;final File resolved=file;final int position=candidate;final String problem=failure;
            if(ticket!=generation){previewCache.release(file);if(bitmap!=null)bitmap.recycle();return;}
            runOnUiThread(()->{if(isDestroyed()||ticket!=generation){previewCache.release(resolved);if(shown!=null)shown.recycle();return;}
                cancelLoading();
                if(shown!=null){index=position;currentFile=resolved;previewCache.retain(resolved);showImage(shown);title.setText(items.get(index).name);message.setVisibility(View.GONE);enabled(true);updateFavorite();}
                else if(index>=0){message.setVisibility(View.GONE);enabled(true);toast(problem==null?"没有更多可预览的图片":problem);}
                else{message.setText(problem==null?"没有可预览的图片":problem);}
            });
        });
    }
    private void showImage(Bitmap bitmap){
        image.animate().cancel();previousImage.animate().cancel();
        // 换图就回到原始比例：上一张的缩放/位移跟着走会看不懂自己在看哪。
        cancelRestore();resetTransform(false);
        final int transition=++imageTransition;
        android.graphics.drawable.Drawable previous=image.getDrawable();
        previousImage.setImageDrawable(previous);previousImage.setAlpha(1f);
        image.setImageBitmap(bitmap);
        if(previous==null){image.setAlpha(1f);return;}
        image.setAlpha(0f);
        image.animate().alpha(1f).setDuration(180).start();
        previousImage.animate().alpha(0f).setDuration(180).withEndAction(()->{
            if(transition==imageTransition)previousImage.setImageDrawable(null);
        }).start();
    }
    /* ---------- 缩放 / 平移 ---------- */

    /** 把 zoom / pan 落到视图上：绕视图中心缩放，再叠加平移。 */
    private void applyTransform(){
        if(image==null)return;
        image.setPivotX(image.getWidth()/2f);image.setPivotY(image.getHeight()/2f);
        image.setScaleX(zoom);image.setScaleY(zoom);
        image.setTranslationX(panX);image.setTranslationY(panY);
    }
    /** FIT_CENTER 下 drawable 铺满视图的基础倍率，用来算平移边界。 */
    private float baseFit(){
        android.graphics.drawable.Drawable drawable=image==null?null:image.getDrawable();
        if(drawable==null||image.getWidth()==0||image.getHeight()==0)return 0f;
        int width=drawable.getIntrinsicWidth(),height=drawable.getIntrinsicHeight();
        if(width<=0||height<=0)return 0f;
        return Math.min((float)image.getWidth()/width,(float)image.getHeight()/height);
    }
    /** 平移不许把图拖出可视区（放大不足一屏时直接归零）。 */
    private void clampPan(){
        android.graphics.drawable.Drawable drawable=image==null?null:image.getDrawable();
        float fit=baseFit();
        if(drawable==null||fit<=0f){panX=0f;panY=0f;return;}
        float contentW=drawable.getIntrinsicWidth()*fit*zoom,contentH=drawable.getIntrinsicHeight()*fit*zoom;
        float maxX=Math.max(0f,(contentW-image.getWidth())/2f),maxY=Math.max(0f,(contentH-image.getHeight())/2f);
        panX=Math.max(-maxX,Math.min(maxX,panX));
        panY=Math.max(-maxY,Math.min(maxY,panY));
    }
    /** 缩放到 target，并保持手指下的那个像素不动。 */
    private void zoomAround(float target,float focusX,float focusY){
        if(image==null||image.getWidth()==0)return;
        float next=Math.max(1f,Math.min(MAX_ZOOM,target));
        if(Math.abs(next-zoom)<0.001f)return;
        float centerX=image.getWidth()/2f,centerY=image.getHeight()/2f;
        panX=(focusX-centerX)-(focusX-centerX-panX)*(next/zoom);
        panY=(focusY-centerY)-(focusY-centerY-panY)*(next/zoom);
        zoom=next;clampPan();applyTransform();
    }
    private void cancelRestore(){
        if(restoreTask!=null){main.removeCallbacks(restoreTask);restoreTask=null;}
        if(restoreAnimation!=null){restoreAnimation.cancel();restoreAnimation=null;}
    }
    /** 松手后 30 秒没有新动作 → 带动画回到原始位置与 1 倍缩放。 */
    private void scheduleRestore(){
        cancelRestore();
        if(zoom<=1.001f&&Math.abs(panX)<1f&&Math.abs(panY)<1f){resetTransform(false);return;}
        restoreTask=()->{restoreTask=null;animateRestore();};
        main.postDelayed(restoreTask,RESTORE_DELAY_MS);
    }
    private void animateRestore(){
        if(image==null||image.getDrawable()==null){resetTransform(false);return;}
        final float fromZoom=zoom,fromX=panX,fromY=panY;
        final android.animation.ValueAnimator animator=android.animation.ValueAnimator.ofFloat(0f,1f);
        restoreAnimation=animator;
        animator.setDuration(RESTORE_ANIMATION_MS);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(value->{
            float fraction=(float)value.getAnimatedValue();
            zoom=fromZoom+(1f-fromZoom)*fraction;
            panX=fromX*(1f-fraction);panY=fromY*(1f-fraction);
            applyTransform();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(android.animation.Animator animation){
                if(restoreAnimation==animator)restoreAnimation=null;
                resetTransform(false);
            }
        });
        animator.start();
    }
    private void resetTransform(boolean animate){
        if(animate){animateRestore();return;}
        zoom=1f;panX=0f;panY=0f;applyTransform();
    }
    /** 加载超过 1 秒才显示转圈；秒开的图不该闪一下提示。 */
    private void showLoading(int ticket){
        cancelLoading();
        pendingLoading=()->{pendingLoading=null;if(ticket==generation&&spinner!=null)spinner.setVisibility(View.VISIBLE);};
        main.postDelayed(pendingLoading,1000);
    }
    private void cancelLoading(){
        if(pendingLoading!=null){main.removeCallbacks(pendingLoading);pendingLoading=null;}
        if(spinner!=null)spinner.setVisibility(View.GONE);
    }
    private File resolve(Item item,int ticket)throws Exception {
        if(item.profile.isEmpty())return item.path.startsWith("content:")?null:new File(item.path);
        RemoteSession session=RemoteSessions.newSession(new ConnectionStore(this).get(item.profile).endpoint);transfer=session;
        try{if(ticket!=generation)throw new InterruptedIOException("预览已取消");session.connect();return previewCache.write(item.name,out->session.download(item.path,out,bytes->{if(ticket!=generation)throw new InterruptedIOException("预览已取消");}));}
        finally{session.close();if(transfer==session)transfer=null;}
    }
    private void enabled(boolean value){enableChildren(actions,value);actions.setAlpha(value?1f:0.4f);}
    private void enableChildren(View v,boolean enabled){v.setEnabled(enabled);if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)enableChildren(group.getChildAt(i),enabled);}}
    private ImageButton photoAction(String icon,String label,Runnable action,boolean circle){ImageButton b=PhotoUi.button(this,icon,label,PhotoUi.BLUE,action);if(circle)b.setBackground(PhotoUi.bg(0xfff0f0f2,dp(40)));return b;}
    private String favoriteKey(){Item item=items.get(index);return item.profile+"|"+item.path;}
    private void toggleFavorite(){if(index<0)return;android.content.SharedPreferences p=getSharedPreferences("photo_favorites",MODE_PRIVATE);p.edit().putBoolean(favoriteKey(),!p.getBoolean(favoriteKey(),false)).apply();updateFavorite();}
    private void updateFavorite(){if(index<0||favoriteButton==null)return;boolean checked=getSharedPreferences("photo_favorites",MODE_PRIVATE).getBoolean(favoriteKey(),false);favoriteButton.setImageDrawable(new PhotoUi.Icon(checked?"heartFill":"heart",PhotoUi.BLUE));favoriteButton.setContentDescription(checked?"取消收藏":"收藏");}
    private void showInfo(){if(index<0)return;Item item=items.get(index);String size=currentFile==null?"":android.text.format.Formatter.formatFileSize(this,currentFile.length());new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(item.name).setMessage(item.path+"\n\n"+size+"\n"+(item.mime==null?"":item.mime)).setPositiveButton("完成",null).setNeutralButton("其他应用打开",(d,w)->external(true)).show();}
    private void deleteImage(){if(index<0)return;Item item=items.get(index);if(!item.profile.isEmpty()||item.path.startsWith("content:")){toast("请返回文件管理器删除此图片");return;}
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("删除图片？").setMessage(item.name+"\n将删除原文件，无法撤销。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
            File target=new File(item.path);enabled(false);io.execute(()->{boolean ok=target.delete();runOnUiThread(()->{if(isFinishing())return;if(!ok){enabled(true);toast("删除失败");return;}setResult(RESULT_OK);getSharedPreferences("photo_favorites",MODE_PRIVATE).edit().remove(item.profile+"|"+item.path).apply();items.remove(index);unsupported.clear();if(items.isEmpty()){finish();return;}int next=Math.min(index,items.size()-1);index=-1;load(next,1,true);});});
        }).show();
    }
    private void edit(){
        Item item=items.get(index);if(!item.profile.isEmpty()){toast("远程图片请先下载到本地再编辑");return;}
        if(item.path.startsWith("content:")){toast("请先复制到本地目录再编辑");return;}
        Intent intent=new Intent(this,ImageEditorActivity.class);intent.putExtra(ImageEditorActivity.EXTRA_PATH,item.path);startActivityForResult(intent,1);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=1||result!=RESULT_OK)return;
        // 编辑器另存的是自动编号副本，原图不动；这里把副本插到当前图片后面并定位过去，
        // 否则用户保存完还停在旧图上，会以为没保存成功。
        load(locateSaved(data==null?null:data.getStringExtra(ImageEditorActivity.EXTRA_SAVED)),1,false);
    }
    /** 定位到编辑后新生成的文件：已在列表中就直接切过去，否则插到当前图片后面。 */
    private int locateSaved(String path){
        if(path==null||path.isEmpty()||index<0)return index;
        for(int i=0;i<items.size();i++)if(path.equals(items.get(i).path))return i;
        int at=Math.min(index+1,items.size());
        items.add(at,new Item(path,new File(path).getName(),"",savedMime(path)));
        unsupported.clear();   // 插入使索引整体后移，原先的「不支持」标记不再对应
        return at;
    }
    /** 副本后缀只会是 jpg/jpeg/jfif/png/webp（见 ImageEditorActivity.outputFormat），mime 不会为空。 */
    private static String savedMime(String name){
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".jfif"))return "image/jpeg";
        if(lower.endsWith(".webp"))return "image/webp";
        return "image/png";
    }
    private void external(boolean open){
        final Item item=items.get(index);final File file=currentFile;
        io.execute(()->{try{
            Uri uri;
            if(item.path.startsWith("content:")&&item.profile.isEmpty())uri=Uri.parse(item.path);
            else{
                File exported=ImageExportCache.export(getCacheDir(),file,item.name);
                uri=FileProvider.getUriForFile(this,getPackageName()+".client.exports",exported);
            }
            Intent intent=new Intent(open?Intent.ACTION_VIEW:Intent.ACTION_SEND);String mime=item.mime.startsWith("image/")?item.mime:"image/*";
            if(open)intent.setDataAndType(uri,mime);else{intent.setType(mime);intent.putExtra(Intent.EXTRA_STREAM,uri);}
            intent.setClipData(ClipData.newRawUri(item.name,uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(()->{if(isDestroyed())return;try{startActivity(Intent.createChooser(intent,open?"用其他应用打开":"分享图片"));}catch(ActivityNotFoundException error){toast("没有可处理此图片的应用");}});
        }catch(Exception error){runOnUiThread(()->{if(!isDestroyed())toast("无法打开或分享图片："+MainActivity.reason(error));});}});
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("index",Math.max(0,index));state.putSerializable("item",items.get(Math.max(0,index)));}
    @Override protected void onDestroy(){generation++;cancelLoading();cancelRestore();if(image!=null)image.animate().cancel();if(previousImage!=null){previousImage.animate().cancel();previousImage.setImageDrawable(null);}RemoteSession active=transfer;if(active!=null)active.close();io.shutdownNow();if(previewCache!=null)previewCache.close();/* 非用户主动退出的销毁（内存回收、"不保留活动"）也要摘掉快照，否则整份目录列表永久留在静态 Map 里。 */
        if(snapshot!=null)snapshots.remove(snapshot);super.onDestroy();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
}
