package com.cj.music;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/** Local folder navigation, without SAF reauthorization or remote destinations. */
final class LocalDownloadDirectory {
    static void choose(Context context,Consumer<String> selected){
        ArrayList<File> roots=new ArrayList<>();roots.add(Environment.getExternalStorageDirectory());
        if(android.os.Build.VERSION.SDK_INT>=30){android.os.storage.StorageManager manager=context.getSystemService(android.os.storage.StorageManager.class);for(android.os.storage.StorageVolume volume:manager.getStorageVolumes()){File root=volume.getDirectory();if(root!=null&&!roots.contains(root)&&root.isDirectory())roots.add(root);}}
        if(roots.size()==1){browse(context,roots.get(0),roots.get(0),selected);return;}String[] names=new String[roots.size()];for(int i=0;i<names.length;i++)names[i]=i==0?"内部存储":roots.get(i).getAbsolutePath();new MaterialAlertDialogBuilder(context).setTitle("选择本地存储").setItems(names,(d,index)->browse(context,roots.get(index),roots.get(index),selected)).setNegativeButton("取消",null).show();
    }
    private static void browse(Context context,File root,File directory,Consumer<String> selected){
        /* listFiles(File::isDirectory) 是磁盘 IO：存储根目录 / 大媒体库动辄几千个子目录，
           留在主线程意味着用户每点一次目录就卡一下（极端情况 ANR）。
           先转圈，后台列完再弹选择框；列表本身是一次性的，重新进来会重新列。 */
        final ProgressDialog loading=ProgressDialog.show(context,null,"正在读取目录…",true,false);
        final Handler main=new Handler(Looper.getMainLooper());
        final Thread worker=new Thread(()->{
            final File[] listed=directory.listFiles(File::isDirectory);
            final ArrayList<File> destinations=new ArrayList<>();
            final ArrayList<String> names=new ArrayList<>();
            if(listed!=null){
                Arrays.sort(listed,(a,b)->a.getName().compareToIgnoreCase(b.getName()));
                if(!directory.equals(root)){destinations.add(directory.getParentFile());names.add("↑ 上一级");}
                for(File child:listed)try{if(child.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)){destinations.add(child);names.add("▸ "+child.getName());}}catch(java.io.IOException ignored){}
            }
            main.post(()->{
                try{loading.dismiss();}catch(Throwable ignored){}
                /* 列目录的这几百毫秒里用户可能已经把页面关了：别给死掉的 Activity 弹窗。 */
                if(context instanceof Activity&&((Activity)context).isDestroyed())return;
                if(listed==null){new MaterialAlertDialogBuilder(context).setMessage("此目录无法读取，请选择其他目录").setPositiveButton("返回",null).show();return;}
                showBrowser(context,root,directory,destinations,names,selected);
            });
        },"local-dir-listing");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }
    private static void showBrowser(Context context,File root,File directory,ArrayList<File> destinations,ArrayList<String> names,Consumer<String> selected){
        androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(context).setTitle(directory.getAbsolutePath()).setItems(names.toArray(new String[0]),(d,index)->browse(context,root,destinations.get(index),selected)).setPositiveButton("选择此目录",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{if(!directory.canWrite()){android.widget.Toast.makeText(context,"此目录不可写，请选择其他目录",android.widget.Toast.LENGTH_SHORT).show();return;}selected.accept(directory.getAbsolutePath());dialog.dismiss();}));dialog.show();
    }
}
