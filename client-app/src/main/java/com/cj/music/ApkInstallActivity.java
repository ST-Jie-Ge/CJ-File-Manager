package com.cj.music;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.cj.music.resources.R;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 别的应用里「打开」一个扩展名为 .1 的文件时被调用（浏览器常见做法：把 .apk 存成 xxx.apk.1 绕开安装拦截）。
 * 见 AndroidManifest 里的 intent-filter，在系统选择器里这一项显示为「安装此apk」。
 * 做的事：定位源文件 → 移动到 /storage/emulated/0/Download 并把扩展名改成 .apk → 拉起系统包安装器。
 */
public final class ApkInstallActivity extends AppCompatActivity {
    private static final String APK_MIME="application/vnd.android.package-archive";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private androidx.appcompat.app.AlertDialog waiting;private boolean working,waitingPermission,closed;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        Uri source=input();
        if(source==null){message("没有收到可安装的文件");finish();return;}
        /* 先要「安装未知应用」权限：没有就带用户去授权，回来后 onResume 自动继续，用户不用再点一次文件。 */
        if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){askPermission();return;}
        work(source);
    }
    @Override protected void onResume(){
        super.onResume();
        if(!waitingPermission)return;
        waitingPermission=false;
        Uri source=input();
        if(source==null){finish();return;}
        if(getPackageManager().canRequestPackageInstalls())work(source);
        else {message("未获得安装权限，已取消");finish();}
    }
    private Uri input(){Intent intent=getIntent();if(intent==null)return null;Uri data=intent.getData();if(data!=null)return data;/* 少数选择器走的是 SEND */return intent.getParcelableExtra(Intent.EXTRA_STREAM);}
    private void askPermission(){
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.client_install_apk))
            .setMessage("需要允许「"+getString(R.string.client_app_name)+"」安装未知应用，授权后会自动继续。")
            .setCancelable(false)
            .setNegativeButton("取消",(d,w)->finish())
            .setPositiveButton("去设置",(d,w)->{waitingPermission=true;try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}catch(Exception e){waitingPermission=false;message("无法打开权限设置");finish();}})
            .show();
    }
    private void work(final Uri source){
        if(working)return;
        working=true;showWaiting();
        io.execute(()->{
            File apk=null;String failure=null;
            try{apk=prepare(source);}catch(Exception|OutOfMemoryError e){failure=e.getMessage()==null?"无法准备安装文件":e.getMessage();}
            final File ready=apk;final String error=failure;
            runOnUiThread(()->{if(closed)return;working=false;dismissWaiting();if(ready==null){message(error);finish();return;}launch(ready);});
        });
    }
    /** 定位源文件 → 复制一份到 Download 并改名为 .apk → 拉起系统包安装器。
        只复制，绝不移动或删除源文件：本 Activity 是 exported 的，第三方应用可以把
        file:///…/任意.1 文件（或 ACTION_SEND 的 EXTRA_STREAM）指进来。一旦改成
        renameTo + origin.delete()，诱使用户点一下「安装此apk」就能把受害者文件
        搬进 Download 并删掉原件。多占一倍磁盘换源文件零风险，值得。 */
    private File prepare(Uri source) throws Exception {
        File download=new File(Environment.getExternalStorageDirectory(),"Download");
        if(!download.isDirectory()&&!download.mkdirs())throw new IOException("无法访问下载目录");
        File origin="file".equals(source.getScheme())?new File(source.getPath()):realPath(source);
        File target=unique(new File(download,apkName(origin!=null?origin.getName():displayName(source))));
        InputStream in;
        if(origin!=null&&origin.isFile()){
            in=new FileInputStream(origin);
        }else{
            try{in=getContentResolver().openInputStream(source);}
            catch(SecurityException e){throw new IOException("没有读取该文件的权限");}
            catch(FileNotFoundException e){throw new IOException("文件不存在或已被移动");}
            if(in==null)throw new IOException("无法读取该文件");
        }
        copy(in,target);
        return target;
    }
    /** content:// 背后的真实路径：DocumentsProvider 的 raw: 形式优先，其次问提供者的 _data 列（很多设备仍可用）。 */
    private File realPath(Uri uri){
        try{
            String path=uri.getPath();
            if(path!=null){int at=path.indexOf("raw:");if(at>=0){File candidate=new File(path.substring(at+4));if(candidate.isFile())return candidate;}}
        }catch(Exception ignored){}
        Cursor cursor=null;
        try{
            cursor=getContentResolver().query(uri,new String[]{"_data"},null,null,null);
            if(cursor!=null&&cursor.moveToFirst()){int column=cursor.getColumnIndex("_data");
                if(column>=0){String value=cursor.getString(column);if(value!=null&&!value.isEmpty()){File candidate=new File(value);if(candidate.isFile())return candidate;}}}
        }catch(Exception ignored){}
        finally{if(cursor!=null)try{cursor.close();}catch(Exception ignored){}}
        return null;
    }
    private String displayName(Uri uri){
        Cursor cursor=null;
        try{
            cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);
            if(cursor!=null&&cursor.moveToFirst()){int column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(column>=0){String value=cursor.getString(column);if(value!=null&&!value.isEmpty())return value;}}
        }catch(Exception ignored){}
        finally{if(cursor!=null)try{cursor.close();}catch(Exception ignored){}}
        try{String last=uri.getLastPathSegment();if(last!=null&&!last.isEmpty())return last;}catch(Exception ignored){}
        return "install.apk.1";
    }
    /** 「xxx.apk.1」/「xxx.1」→「xxx.apk」；本来就是 .apk 就原样返回。 */
    private String apkName(String name){
        if(name==null)name="";
        int slash=Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'));
        if(slash>=0)name=name.substring(slash+1);
        name=name.replace('/','_').replace('\\','_').replace(':','_').replace('\0','_').trim();
        if(name.toLowerCase(Locale.ROOT).endsWith(".1"))name=name.substring(0,name.length()-2).trim();
        while(name.startsWith("."))name="_"+name.substring(1);
        if(name.isEmpty())name="install";
        if(!name.toLowerCase(Locale.ROOT).endsWith(".apk"))name=name+".apk";
        return name;
    }
    /** 下载目录里重名就加 (1)(2)…，不覆盖用户已有的文件。 */
    private File unique(File target){
        if(!target.exists())return target;
        String name=target.getName();int dot=name.toLowerCase(Locale.ROOT).endsWith(".apk")?name.length()-4:name.length();
        String base=name.substring(0,dot),ext=name.substring(dot);File parent=target.getParentFile();
        for(int i=1;i<1000;i++){File candidate=new File(parent,base+"("+i+")"+ext);if(!candidate.exists())return candidate;}
        return new File(parent,base+"-"+System.currentTimeMillis()+ext);
    }
    /** 先写 .part 再改名：中途失败不会在下载目录里留下半个 apk。
        临时名带随机后缀，避免两次并发下载同名 apk 互踩同一个临时文件。 */
    private void copy(InputStream in,File out) throws Exception {
        File temp=File.createTempFile(".cjfm-",".part",out.getParentFile());
        try(InputStream source=in;OutputStream stream=new FileOutputStream(temp)){
            byte[] buffer=new byte[256*1024];int n;while((n=source.read(buffer))!=-1)stream.write(buffer,0,n);
        }catch(Exception e){temp.delete();throw e;}
        if(!temp.renameTo(out)){temp.delete();throw new IOException("无法写入下载目录");}
    }
    /** 拉起系统自带的包安装器（FileProvider 授权读，避免 FileUriExposedException）。 */
    private void launch(File apk){
        try{
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".client.exports",apk);
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }catch(android.content.ActivityNotFoundException e){message("系统里没有可用的安装程序");}
        catch(Exception e){message("无法拉起安装程序："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
        finish();
    }
    private void showWaiting(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(22),dp(24),dp(2));
        ProgressBar spinner=new ProgressBar(this);spinner.setIndeterminate(true);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(40),dp(40));size.gravity=Gravity.CENTER_HORIZONTAL;box.addView(spinner,size);
        TextView text=new TextView(this);text.setText("正在准备安装包…");text.setTextSize(13);text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textSize=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);textSize.topMargin=dp(14);box.addView(text,textSize);
        waiting=new MaterialAlertDialogBuilder(this).setView(box).setCancelable(false).create();
        waiting.setCanceledOnTouchOutside(false);
        waiting.show();
    }
    private void dismissWaiting(){if(waiting!=null){try{waiting.dismiss();}catch(Exception ignored){}waiting=null;}}
    private void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){closed=true;dismissWaiting();io.shutdownNow();super.onDestroy();}
}
