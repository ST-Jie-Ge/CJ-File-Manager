package com.cj.music;

import android.content.Context;
import android.os.Environment;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** 应用内媒体文件选择器：不通过 SAF / OpenDocument，直接浏览本地可读目录。 */
final class LocalMediaPicker {
    private static final Set<String> VIDEO=new HashSet<>(Arrays.asList("mp4","mkv","webm","avi","mov","flv","f4v","m4v","3gp","3g2","ts","m2ts","mts","vob","mpg","mpeg","mpe","wmv","asf","rm","rmvb"));
    private static final Set<String> AUDIO=new HashSet<>(Arrays.asList("mp3","flac","wav","m4a","aac","ogg","oga","opus","amr","mka","wma","ape","dsd","dff","dsf"));

    static void choose(Context context,int type,Consumer<String> selected){
        ArrayList<File> roots=new ArrayList<>();roots.add(Environment.getExternalStorageDirectory());
        if(android.os.Build.VERSION.SDK_INT>=30){android.os.storage.StorageManager manager=context.getSystemService(android.os.storage.StorageManager.class);for(android.os.storage.StorageVolume volume:manager.getStorageVolumes()){File root=volume.getDirectory();if(root!=null&&!roots.contains(root)&&root.isDirectory())roots.add(root);}}
        if(roots.isEmpty()){Toast.makeText(context,"没有可读取的本地存储",Toast.LENGTH_SHORT).show();return;}
        if(roots.size()==1){browse(context,type,roots.get(0),roots.get(0),selected);return;}
        String[] names=new String[roots.size()];for(int i=0;i<names.length;i++)names[i]=i==0?"内部存储":roots.get(i).getAbsolutePath();
        new MaterialAlertDialogBuilder(context).setTitle("选择本地存储").setItems(names,(d,index)->browse(context,type,roots.get(index),roots.get(index),selected)).setNegativeButton("取消",null).show();
    }

    private static void browse(Context context,int type,File root,File directory,Consumer<String> selected){
        new Browser(context,type,root,selected).show(directory);
    }

    private static final class Browser {
        final Context context;final int type;final File root;final Consumer<String> selected;
        final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
        final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
        final ArrayList<File> entries=new ArrayList<>();
        android.widget.TextView path,status;android.widget.ImageButton up;
        android.widget.ListView list;android.widget.BaseAdapter adapter;
        androidx.appcompat.app.AlertDialog dialog;File directory;boolean closed;int generation;
        Browser(Context context,int type,File root,Consumer<String> selected){this.context=context;this.type=type;this.root=root;this.selected=selected;}
        int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
        int color(int attr){return MusicControls.color(context,attr);}
        android.widget.TextView text(int size){android.widget.TextView v=new android.widget.TextView(context);v.setTextSize(size);v.setTextColor(color(com.cj.music.resources.R.attr.client_text));return v;}
        void show(File start){
            android.widget.LinearLayout body=new android.widget.LinearLayout(context);body.setOrientation(1);body.setPadding(dp(12),0,dp(12),dp(8));
            android.widget.TextView hint=text(12);hint.setText(type==0?"选择需要转换的视频":"支持音频文件，也可从视频中提取音频");hint.setTextColor(color(com.cj.music.resources.R.attr.client_text_secondary));hint.setPadding(dp(8),0,dp(8),dp(12));body.addView(hint);
            android.widget.LinearLayout navigation=new android.widget.LinearLayout(context);navigation.setGravity(android.view.Gravity.CENTER_VERTICAL);
            up=new android.widget.ImageButton(context);up.setImageResource(com.cj.music.resources.R.drawable.client_ic_back);up.setContentDescription("返回上一级目录");up.setBackground(PhotoUi.bg(color(com.cj.music.resources.R.attr.client_surface_raised),dp(14)));up.setPadding(dp(12),dp(12),dp(12),dp(12));navigation.addView(up,new android.widget.LinearLayout.LayoutParams(dp(44),dp(44)));
            path=text(13);path.setMaxLines(2);path.setEllipsize(android.text.TextUtils.TruncateAt.START);path.setPadding(dp(12),0,dp(8),0);navigation.addView(path,new android.widget.LinearLayout.LayoutParams(0,-2,1));body.addView(navigation);
            status=text(12);status.setTextColor(color(com.cj.music.resources.R.attr.client_text_secondary));status.setPadding(dp(8),dp(12),dp(8),dp(8));body.addView(status);
            list=new android.widget.ListView(context);list.setDivider(null);list.setClipToPadding(false);list.setPadding(0,0,0,dp(8));
            adapter=new android.widget.BaseAdapter(){
                public int getCount(){return entries.size();}public Object getItem(int position){return entries.get(position);}public long getItemId(int position){return position;}
                public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){
                    android.widget.LinearLayout row;android.widget.ImageView icon;android.widget.TextView name,detail,end;
                    if(recycled==null){
                        row=new android.widget.LinearLayout(context);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(10),dp(8),dp(10));row.setMinimumHeight(dp(76));
                        icon=new android.widget.ImageView(context);icon.setPadding(dp(11),dp(11),dp(11),dp(11));row.addView(icon,new android.widget.LinearLayout.LayoutParams(dp(46),dp(46)));
                        android.widget.LinearLayout labels=new android.widget.LinearLayout(context);labels.setOrientation(1);labels.setPadding(dp(14),0,dp(10),0);name=text(15);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);detail=text(12);detail.setPadding(0,dp(4),0,0);detail.setTextColor(color(com.cj.music.resources.R.attr.client_text_secondary));labels.addView(name);labels.addView(detail);row.addView(labels,new android.widget.LinearLayout.LayoutParams(0,-2,1));
                        end=text(13);row.addView(end);row.setTag(new Object[]{icon,name,detail,end});
                    }else row=(android.widget.LinearLayout)recycled;
                    Object[] views=(Object[])row.getTag();icon=(android.widget.ImageView)views[0];name=(android.widget.TextView)views[1];detail=(android.widget.TextView)views[2];end=(android.widget.TextView)views[3];
                    File file=entries.get(position);boolean folder=file.isDirectory(),video=VIDEO.contains(extension(file.getName()));
                    int tint=folder?0xffac7829:video?0xff477bb5:0xff8960b2;
                    icon.setBackground(PhotoUi.bg(androidx.core.graphics.ColorUtils.setAlphaComponent(tint,24),dp(14)));icon.setColorFilter(tint);
                    icon.setImageResource(folder?com.cj.music.resources.R.drawable.client_ic_folder:video?com.cj.music.resources.R.drawable.client_ic_video:com.cj.music.resources.R.drawable.client_ic_audio);
                    name.setText(file.getName());detail.setText(folder?"文件夹":(video?"视频":"音频")+" · "+extension(file.getName()).toUpperCase(java.util.Locale.ROOT)+" · "+android.text.format.Formatter.formatFileSize(context,file.length()));
                    end.setText(folder?"›":"选择");end.setTextColor(folder?color(com.cj.music.resources.R.attr.client_text_secondary):color(com.cj.music.resources.R.attr.client_accent));
                    return row;
                }
            };
            list.setAdapter(adapter);body.addView(list,new android.widget.LinearLayout.LayoutParams(-1,Math.min(dp(420),context.getResources().getDisplayMetrics().heightPixels/2)));
            dialog=new MaterialAlertDialogBuilder(context).setTitle(type==0?"选择视频":"选择音频或视频").setView(body).setNegativeButton("取消",null).create();
            dialog.setOnDismissListener(d->{closed=true;io.shutdownNow();main.removeCallbacksAndMessages(null);});
            up.setOnClickListener(v->{if(directory!=null&&!directory.equals(root))open(directory.getParentFile());});
            list.setOnItemClickListener((parent,view,position,id)->{File file=entries.get(position);if(file.isDirectory())open(file);else{dialog.dismiss();selected.accept(file.getAbsolutePath());}});
            dialog.setOnKeyListener((d,key,event)->{if(key==android.view.KeyEvent.KEYCODE_BACK&&directory!=null&&!directory.equals(root)){if(event.getAction()==android.view.KeyEvent.ACTION_UP)open(directory.getParentFile());return true;}return false;});
            dialog.show();open(start);
        }
        void open(File target){
            if(closed||target==null)return;
            directory=target;int request=++generation;entries.clear();adapter.notifyDataSetChanged();list.setSelection(0);
            up.setEnabled(!target.equals(root));up.setAlpha(up.isEnabled()?1f:.35f);path.setText(target.equals(root)?"存储根目录":target.getAbsolutePath().substring(root.getAbsolutePath().length()));status.setText("正在读取…");
            io.execute(()->{
                ArrayList<File> found=new ArrayList<>();String error=null;
                try{
                    File[] children=target.listFiles();if(children==null)throw new java.io.IOException();
                    String base=root.getCanonicalPath();
                    for(File child:children){
                        if(Thread.currentThread().isInterrupted())return;
                        if(child.getName().startsWith("."))continue;
                        try{String canonical=child.getCanonicalPath();if(!canonical.startsWith(base+File.separator))continue;}catch(java.io.IOException ignored){continue;}
                        if(child.isDirectory()||(child.isFile()&&accept(type,child.getName())))found.add(child);
                    }
                    found.sort(Comparator.comparing((File f)->!f.isDirectory()).thenComparing(File::getName,String.CASE_INSENSITIVE_ORDER));
                }catch(java.io.IOException|SecurityException e){error="无法读取此目录，可返回上一级";}
                String failure=error;
                main.post(()->{if(closed||request!=generation)return;entries.addAll(found);adapter.notifyDataSetChanged();long folders=found.stream().filter(File::isDirectory).count();status.setText(failure!=null?failure:found.isEmpty()?"此目录下没有可选的媒体文件":folders+" 个文件夹 · "+(found.size()-folders)+" 个媒体文件");});
            });
        }
    }
    private static String extension(String name){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(java.util.Locale.ROOT);}
    private static boolean accept(int type,String name){String ext=extension(name);return type==0?VIDEO.contains(ext):VIDEO.contains(ext)||AUDIO.contains(ext);}
}
