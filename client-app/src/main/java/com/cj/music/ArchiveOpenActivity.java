package com.cj.music;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.cj.music.resources.R;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 别的应用「打开」一个压缩包时首先进入此浮层 Activity。
 * 把 content:// 文件落到本地 Download（file:// 则直接定位），然后唤起 MainActivity
 * 并新建标签页定位到该压缩包，主界面底部悬浮卡片显示解压操作。
 */
public final class ArchiveOpenActivity extends AppCompatActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private androidx.appcompat.app.AlertDialog waiting;private boolean working,closed;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        Uri source=input();
        if(source==null){message("没有收到可打开的压缩包");finish();return;}
        work(source);
    }
    private Uri input(){Intent intent=getIntent();if(intent==null)return null;Uri data=intent.getData();if(data!=null)return data;return intent.getParcelableExtra(Intent.EXTRA_STREAM);}
    private void work(final Uri source){
        if(working)return;
        working=true;showWaiting();
        io.execute(()->{
            File archive=null;String failure=null;
            try{archive=prepare(source);}catch(Exception|OutOfMemoryError e){failure=e.getMessage()==null?"无法准备压缩包":e.getMessage();}
            final File ready=archive;final String error=failure;
            runOnUiThread(()->{if(closed)return;working=false;dismissWaiting();if(ready==null){message(error);finish();return;}launch(ready);});
        });
    }
    /** file:// 直接复用原路径；content:// 复制到 Download 并尽量保留原始文件名。 */
    private File prepare(Uri source) throws Exception {
        if("file".equals(source.getScheme())){
            File file=new File(source.getPath());
            if(file.isFile())return file;
        }
        File download=new File(Environment.getExternalStorageDirectory(),"Download");
        if(!download.isDirectory()&&!download.mkdirs())throw new IOException("无法访问下载目录");
        String name=displayName(source);
        String mime=null;try{mime=getContentResolver().getType(source);}catch(Exception ignored){}
        name=safeName(name,mime);
        File target=unique(new File(download,name));
        InputStream in;
        try{in=getContentResolver().openInputStream(source);}
        catch(SecurityException e){throw new IOException("没有读取该文件的权限");}
        catch(FileNotFoundException e){throw new IOException("文件不存在或已被移动");}
        if(in==null)throw new IOException("无法读取该文件");
        copy(in,target);
        return target;
    }
    private String displayName(Uri uri){
        Cursor cursor=null;
        try{
            cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);
            if(cursor!=null&&cursor.moveToFirst()){int column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(column>=0){String value=cursor.getString(column);if(value!=null&&!value.isEmpty())return value;}}
        }catch(Exception ignored){}
        finally{if(cursor!=null)try{cursor.close();}catch(Exception ignored){}}
        try{String last=uri.getLastPathSegment();if(last!=null&&!last.isEmpty()){int slash=Math.max(last.lastIndexOf('/'),last.lastIndexOf('\\'));return slash>=0?last.substring(slash+1):last;}}catch(Exception ignored){}
        return "archive";
    }
    private String safeName(String name,String mime){
        if(name==null)name="archive";
        name=name.replace('/','_').replace('\\','_').replace(':','_').replace('\0','_').trim();
        while(name.startsWith("."))name="_"+name.substring(1);
        if(name.isEmpty())name="archive";
        boolean known=ArchiveUtil.isArchive(name);
        if(!known){
            // 选择器传进来的文件可能没有正确扩展名，按 MIME 或原样补 .zip 兜底。
            String ext="zip";
            if(mime!=null)switch(mime.toLowerCase(Locale.ROOT)){
                case "application/x-7z-compressed":ext="7z";break;
                case "application/vnd.rar":case "application/x-rar-compressed":ext="rar";break;
                case "application/x-tar":ext="tar";break;
                case "application/gzip":case "application/x-gzip":ext="gz";break;
                case "application/x-xz":ext="xz";break;
                case "application/x-bzip2":ext="bz2";break;
                case "application/x-lzma":ext="lzma";break;
            }
            name=name+"."+ext;
        }
        return name;
    }
    private File unique(File target){
        if(!target.exists())return target;
        String name=target.getName();
        int dot=name.lastIndexOf('.');
        String base=dot>0?name.substring(0,dot):name;
        String ext=dot>0?name.substring(dot):"";
        File parent=target.getParentFile();
        for(int i=1;i<1000;i++){File candidate=new File(parent,base+"("+i+")"+ext);if(!candidate.exists())return candidate;}
        return new File(parent,base+"-"+System.currentTimeMillis()+ext);
    }
    private void copy(InputStream in,File out) throws Exception {
        /* 临时名带随机后缀：固定用 out.getName()+".part" 时，两次并发打开同名压缩包会互踩同一临时文件。 */
        File temp=File.createTempFile(".cjfm-",".part",out.getParentFile());
        try(InputStream source=in;OutputStream stream=new FileOutputStream(temp)){
            byte[] buffer=new byte[256*1024];int n;while((n=source.read(buffer))!=-1)stream.write(buffer,0,n);
        }catch(Exception e){temp.delete();throw e;}
        if(!temp.renameTo(out)){temp.delete();throw new IOException("无法写入下载目录");}
    }
    private void launch(File archive){
        Intent intent=new Intent(this,MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("archive_dir",archive.getParentFile().getAbsolutePath())
            .putExtra("archive_file",archive.getName());
        try{startActivity(intent);}catch(Exception e){message("无法打开主界面");}
        finish();
    }
    /** 等待框内容已改成 Compose（MoreScreens.archiveWaitingView）：
        外面还是 MaterialAlertDialog，里面就一个转圈 + 一行文案。 */
    private void showWaiting(){
        waiting=new MaterialAlertDialogBuilder(this).setView(MoreScreensKt.archiveWaitingView(this)).setCancelable(false).create();
        waiting.setCanceledOnTouchOutside(false);waiting.show();
    }
    private void dismissWaiting(){if(waiting!=null){try{waiting.dismiss();}catch(Exception ignored){}waiting=null;}}
    private void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){closed=true;dismissWaiting();io.shutdownNow();super.onDestroy();}
}
