package com.cj.music;

import android.content.Context;
import com.cj.music.net.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 远程曲目的封面按需获取——不用先播一遍也能在曲库里看到封面。
 *
 * 播放路径（MusicPlaybackService.load）会顺手把封面写进 {@link MusicCache#cover}；
 * 曲库列表只读那个文件，所以远程曲目在播放前一直没封面。这里补上这一步，两条路：
 * ① 同目录下同名图片，其次通用封面名（cover／folder／album）——小图直接下载；
 * ② 随机读 + MediaMetadataRetriever 只取内嵌封面，绝不整份下载音频。
 * 成功即写入与播放路径同一个 cover.jpg；两条路都没结果就留一个空标记文件，
 * 免得每次滚动回列表都重新连一次服务器。
 */
final class MusicCoverLoader {
    /** 内嵌封面随机读的字节预算：ID3v2／FLAC 的图片块都在文件靠前处，12MB 足够。 */
    private static final long READ_BUDGET=16L*1024*1024;
    /** 同目录图片封面的文件大小上限；内嵌封面另按 2MB 判断，与播放路径一致。 */
    private static final long MAX_IMAGE=4L*1024*1024;
    private static final String MISSING=".nocover";
    /** 目录列表短期复用：一个专辑往往几十首歌同处一个目录，没必要每首都 LIST 一次。 */
    private static final long LISTING_TTL_MS=60000;
    private static final int LISTING_LIMIT=64;
    private static final java.util.concurrent.ConcurrentHashMap<String,List<RemoteEntry>> LISTINGS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String,Long> LISTED_AT=new java.util.concurrent.ConcurrentHashMap<>();

    /** 已有封面直接返回；否则尝试从服务器取一次。返回 null 表示确实没有封面（或服务器不可用）。 */
    static File ensureCover(Context c,MusicTrack t){
        if(t.profile.isEmpty())return null;
        try{
            File cover=MusicCache.cover(c,t);
            if(cover.isFile()&&cover.length()>0)return cover;
            File folder=cover.getParentFile();
            if(folder==null)return null;
            if(new File(folder,MISSING).isFile())return null;
            ConnectionStore.Profile profile=new ConnectionStore(c).get(t.profile);
            if(profile.endpoint.protocol!=Endpoint.Protocol.FTP&&profile.endpoint.protocol!=Endpoint.Protocol.SMB)return null;
            if(!folder.isDirectory()&&!folder.mkdirs())return null;
            boolean found=false;
            try(RemoteSession session=RemoteSessions.newSession(profile.endpoint)){
                session.connect();
                found=coverFromFolder(session,profile,t,cover)||coverFromTag(session,t,cover);
            }
            /* 并发下另一条路可能刚判定"无封面"并写了 .nocover；封面已到手就把它撤掉，
               否则 .nocover 会盖住这张封面，之后永远不再显示。 */
            if(found){new File(folder,MISSING).delete();return cover;}
            try{new File(folder,MISSING).createNewFile();}catch(IOException ignored){}
            return null;
        }catch(Exception|OutOfMemoryError e){return null;}
    }

    /** 同目录的同名图片优先，其次通用封面名——与本地曲目找同名 jpg/png 的规则保持一致。 */
    private static boolean coverFromFolder(RemoteSession session,ConnectionStore.Profile profile,MusicTrack t,File cover)throws Exception{
        int split=t.path.lastIndexOf('/');if(split<=0)return false;
        String directory=t.path.substring(0,split);
        String stem=t.name;int dot=stem.lastIndexOf('.');if(dot>0)stem=stem.substring(0,dot);
        if(stem.isEmpty())return false;
        List<RemoteEntry> entries=listing(session,profile,directory);
        RemoteEntry hit=null;
        for(String name:new String[]{stem+".jpg",stem+".jpeg",stem+".png"}){if((hit=find(entries,name))!=null)break;}
        if(hit==null)for(String name:new String[]{"cover.jpg","cover.jpeg","cover.png","folder.jpg","folder.png","album.jpg","album.png"}){if((hit=find(entries,name))!=null)break;}
        if(hit==null||hit.size<=0||hit.size>MAX_IMAGE)return false;
        final RemoteEntry image=hit;
        /* 原子落位：播放准备线程也会写同一个 cover.jpg，直接 FileOutputStream 会互相截断成半张图。 */
        // 列表报的 size 可能是陈旧的：流内再卡一次，免得一个"声称 3MB"的图实际拖回几百 MB。
        SafeFileOperations.writeReplacing(cover,out->session.download(image.path,out,bytes->{if(bytes>MAX_IMAGE)throw new IOException("封面图片过大");}));
        return cover.length()>0;
    }

    /** 内嵌封面：只随机读需要的那几段，不把整个音频拖下来。 */
    private static boolean coverFromTag(RemoteSession session,MusicTrack t,File cover)throws Exception{
        long length=session.streamSize(t.path);if(length<=0)return false;
        final RemoteRandomAccessReader reader=new RemoteRandomAccessReader(session,t.path,length,READ_BUDGET);
        android.media.MediaDataSource source=new android.media.MediaDataSource(){
            @Override public int readAt(long position,byte[] buffer,int offset,int size)throws IOException{return reader.readAt(position,buffer,offset,size);}
            @Override public long getSize(){return reader.size();}
            @Override public void close()throws IOException{reader.close();}
        };
        android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
        try{
            String ext=MusicDecoder.ext(t.name).toLowerCase(java.util.Locale.ROOT);
            if("flac".equals(ext)&&length>READ_BUDGET/2){byte[] probe=new byte[1];reader.readAt(Math.max(0,length-1),probe,0,1);}
            retriever.setDataSource(source);
            byte[] picture=retriever.getEmbeddedPicture();
            if(picture==null||picture.length==0||picture.length>2*1024*1024)return false;
            SafeFileOperations.writeReplacing(cover,out->out.write(picture));
            return cover.length()>0;
        }catch(Exception|OutOfMemoryError e){return false;}
        finally{try{retriever.release();}catch(Exception ignored){}finally{try{source.close();}catch(IOException ignored){}}}
    }

    private static RemoteEntry find(List<RemoteEntry> entries,String name){
        for(RemoteEntry entry:entries)if(!entry.directory&&entry.name.equalsIgnoreCase(name))return entry;
        return null;
    }

    /** 目录列表缓存：同一个目录在 60s 内只 LIST 一次，避免同专辑几十首歌各连一次服务器。 */
    private static List<RemoteEntry> listing(RemoteSession session,ConnectionStore.Profile profile,String directory)throws IOException{
        String scope=profile.id+"|"+directory;
        List<RemoteEntry> cached=LISTINGS.get(scope);Long at=LISTED_AT.get(scope);
        if(cached!=null&&at!=null&&System.currentTimeMillis()-at<LISTING_TTL_MS)return cached;
        List<RemoteEntry> entries=session.list(directory);
        if(LISTINGS.size()>=LISTING_LIMIT){LISTINGS.clear();LISTED_AT.clear();}
        LISTINGS.put(scope,entries);LISTED_AT.put(scope,System.currentTimeMillis());
        return entries;
    }
}
