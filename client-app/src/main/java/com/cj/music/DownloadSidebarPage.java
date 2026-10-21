package com.cj.music;
import android.content.*;import android.graphics.drawable.GradientDrawable;import android.os.Build;import android.os.Handler;import android.os.Looper;import android.view.*;import android.widget.*;import com.cj.music.resources.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.*;
import java.io.File;

/** Download page hosted in the sidebar: live task list on top, add button below.
    任务列表与 notice 都在这里实时轮询——添加成功立刻能看到新卡片，失败原因也会 toast 出来，
    不会再出现「点了下载一点反应没有」的情况。 */
final class DownloadSidebarPage extends LinearLayout{
    androidx.compose.ui.platform.ComposeView composeView;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private boolean detached;
    DownloadSidebarPage(Context c){super(c);setOrientation(VERTICAL);
        /* 任务列表已改成 Compose（MoreScreens.downloadPageView）。原来的 ScrollView + 手动
           removeAllViews 重建整套都不要了 —— Compose 自己按快照字符串重组。
           刷新节奏也由 Compose 侧轮询（见 DownloadPage 的 DisposableEffect）。 */
        addView(MoreScreensKt.downloadPageView(this),new LinearLayout.LayoutParams(-1,0,1f));
    }
    @Override protected void onDetachedFromWindow(){detached=true;super.onDetachedFromWindow();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    /* ---- Compose 侧的桥接 ---- */
    String pageDownloadSnapshot(){
        try{
            if(LinkDownloadService.snapshot.isEmpty()){ /* 服务从未启动过：先显示本地任务清单，有活动任务再唤醒服务 */
                JSONArray a=new JSONArray();boolean running=false;
                for(LinkDownloadStore.Task t:new LinkDownloadStore(getContext()).read()){a.put(t.json());running|=t.active();}
                LinkDownloadService.snapshot=a.toString();
                if(running)LinkDownloadService.command(getContext(),"sync",null,null,null);
            }
        }catch(Exception ignored){}
        return LinkDownloadService.snapshot;
    }
    String pageDownloadNotice(){return LinkDownloadService.notice;}
    void pageShowNotice(String notice){Toast.makeText(getContext().getApplicationContext(),notice,Toast.LENGTH_LONG).show();}
    String pageBytes(long n){return bytes(n);}
    int pageMaxMagnet(){return LinkDownloadService.MAX_MAGNET;}
    int pageMaxEd2k(){return LinkDownloadService.MAX_ED2K;}
    void pageDownloadCommand(String action,String id){command(action,id);}
    void pageConfirmRemove(String id){new MaterialAlertDialogBuilder(getContext()).setTitle("移除下载记录？").setMessage("已下载的文件会保留。").setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->command("remove",id)).show();}
    private String bytes(long n){return android.text.format.Formatter.formatShortFileSize(getContext(),n);}
    private void command(String action,String id){try{LinkDownloadService.command(getContext(),action,id,null,null);}catch(Exception e){Toast.makeText(getContext().getApplicationContext(),"无法连接下载服务，请重试",Toast.LENGTH_SHORT).show();}}
    /** 标题栏 ＋ 图标、外部链接入口都调它。 */
    void addDownload(){addDownload(null);}
    void addDownload(String initial){Context c=getContext();
        LinearLayout form=new LinearLayout(c);form.setOrientation(VERTICAL);
        int padding=Math.round(20*getResources().getDisplayMetrics().density);form.setPadding(padding,dp(8),padding,dp(8));
        EditText link=new EditText(c);link.setHint("magnet:?… 或 ed2k://…");
        if(initial!=null)link.setText(initial);form.addView(link);
        String[] path={c.getSharedPreferences("downloads",0).getString("directory",android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath())};
        TextView folder=new TextView(c);folder.setTextColor(MusicControls.color(c,R.attr.client_text));folder.setPadding(dp(12),padding,dp(12),padding);folder.setBackgroundResource(R.drawable.client_page_row);folder.setText("下载目录\n"+path[0]+"\n点击修改 ›");form.addView(folder);
        folder.setOnClickListener(v->{if(access())LocalDownloadDirectory.choose(c,selected->{path[0]=selected;folder.setText("下载目录\n"+selected+"\n点击修改 ›");});});
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(c).setTitle("添加下载").setView(form).setPositiveButton("下载",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            if(!access())return;
            try{
                DownloadLink parsed=DownloadLink.parse(link.getText().toString());
                File target=new File(path[0]);
                if(!target.isDirectory()&&!target.mkdirs()||!target.canWrite())throw new java.io.IOException("下载目录不可写，请重新选择");
                startDownload(parsed,target.getCanonicalPath());
                c.getSharedPreferences("downloads",0).edit().putString("directory",target.getCanonicalPath()).apply();
                dialog.dismiss();
            }catch(Exception error){link.setError(error.getMessage()==null?"链接无效":error.getMessage());}
        }));
        dialog.show();}
    /** Android 13+ 通知权限：只问一次（链接下载用前台通知展示进度与控制）。其余与全屏页一致。 */
    private void startDownload(DownloadLink link,String directory){
        Runnable start=()->{try{LinkDownloadService.command(getContext(),"add","",link.uri,directory);}
            catch(Exception e){Toast.makeText(getContext().getApplicationContext(),"无法启动下载任务，请重试",Toast.LENGTH_SHORT).show();}};
        if(Build.VERSION.SDK_INT>=33&&c33()&&!getContext().getSharedPreferences("downloads",0).getBoolean("notification_asked",false))
            new MaterialAlertDialogBuilder(getContext()).setTitle("下载通知").setMessage("允许通知后，可以从通知栏查看进度和暂停后台下载。")
                .setNegativeButton("暂不允许",(d,w)->{getContext().getSharedPreferences("downloads",0).edit().putBoolean("notification_asked",true).apply();start.run();})
                .setPositiveButton("继续",(d,w)->{getContext().getSharedPreferences("downloads",0).edit().putBoolean("notification_asked",true).apply();
                    ((android.app.Activity)getContext()).requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},42);start.run();}).show();
        else start.run();}
    private boolean c33(){return ((android.app.Activity)getContext()).checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED;}
    private boolean access(){if(android.os.Build.VERSION.SDK_INT<30||android.os.Environment.isExternalStorageManager())return true;
        new MaterialAlertDialogBuilder(getContext()).setTitle("需要完整存储访问权限").setNegativeButton("取消",null).setPositiveButton("去授权",(d,w)->getContext().startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,android.net.Uri.parse("package:"+getContext().getPackageName())))).show();return false;}
}
