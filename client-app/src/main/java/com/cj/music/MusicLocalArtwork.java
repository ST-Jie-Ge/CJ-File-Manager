package com.cj.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.File;

/** Local artwork is read into bounded memory; never copied into an app cache file. */
final class MusicLocalArtwork {
    private static final long READ_BUDGET=16L*1024*1024;
    static Bitmap read(Context context,MusicTrack track,int maxSide){
        if(!track.profile.isEmpty())return null;
        try{
            // 缓存迁移由曲库加载/标签编辑处理；读取封面不等待波形分析的缓存锁。
            if(!track.path.startsWith("content:")){
                File source=new File(track.path),parent=source.getParentFile();
                String name=source.getName();int dot=name.lastIndexOf('.');String stem=dot>0?name.substring(0,dot):name;
                if(parent!=null)for(String ext:new String[]{"jpg","jpeg","png","JPG","JPEG","PNG"}){
                    File picture=new File(parent,stem+"."+ext);
                    if(picture.isFile()&&picture.length()<=4L*1024*1024){
                        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(picture.getPath(),options);
                        if(!sample(options,maxSide))continue;
                        Bitmap result=BitmapFactory.decodeFile(picture.getPath(),options);if(result!=null)return result;
                    }
                }
            }
            try(MediaMetadataRetriever metadata=new MediaMetadataRetriever()){
                if(track.path.startsWith("content:"))metadata.setDataSource(context,Uri.parse(track.path));
                else{
                    File audio=new File(track.path);long length=audio.length();if(length<=0)return null;
                    final java.io.RandomAccessFile random=new java.io.RandomAccessFile(audio,"r");
                    android.media.MediaDataSource source=new android.media.MediaDataSource(){private long bytes;
                        @Override public int readAt(long position,byte[] buffer,int offset,int size)throws java.io.IOException{if(position<0||position>=length)return -1;if(bytes>=READ_BUDGET)throw new java.io.IOException("封面读取预算已用完");int count=(int)Math.min(Math.min((long)size,length-position),READ_BUDGET-bytes);synchronized(random){random.seek(position);int n=random.read(buffer,offset,count);if(n>0)bytes+=n;return n;}}
                        @Override public long getSize(){return length;}
                        @Override public void close()throws java.io.IOException{random.close();}
                    };
                    try{metadata.setDataSource(source);byte[] bytes=metadata.getEmbeddedPicture();if(bytes==null)return null;BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(!sample(options,maxSide))return null;return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);}
                    finally{try{metadata.release();}catch(Exception ignored){}try{source.close();}catch(Exception ignored){}}
                }
                byte[] bytes=metadata.getEmbeddedPicture();if(bytes==null)return null;
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(!sample(options,maxSide))return null;
                return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            }
        }catch(Exception|OutOfMemoryError ignored){return null;}
    }
    static void ensureCached(Context context,MusicTrack track){
        if(track==null||!track.profile.isEmpty())return;
        try{
            File cache=MusicCache.localCover(context,track);if(cache==null||cache.isFile()&&cache.length()>0)return;
            Bitmap image=read(context,track,1024);if(image==null)return;
            SafeFileOperations.writeReplacing(cache,out->{if(!image.compress(Bitmap.CompressFormat.JPEG,90,out))throw new java.io.IOException("封面编码失败");});
        }catch(Exception|OutOfMemoryError ignored){}
    }
    static Bitmap readCached(Context context,MusicTrack track,int maxSide){
        if(track==null||!track.profile.isEmpty())return null;
        try{
            File cache=MusicCache.localCover(context,track);if(cache==null||!cache.isFile()||cache.length()<=0)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(cache.getPath(),options);
            if(!sample(options,maxSide))return null;return BitmapFactory.decodeFile(cache.getPath(),options);
        }catch(Exception|OutOfMemoryError ignored){return null;}
    }
    private static boolean sample(BitmapFactory.Options options,int limit){
        if(options.outWidth<=0||options.outHeight<=0||options.outWidth>20000||options.outHeight>20000)return false;
        options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>limit)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;return true;
    }
}
