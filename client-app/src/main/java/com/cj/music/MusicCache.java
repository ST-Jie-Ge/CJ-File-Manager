package com.cj.music;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.cj.music.net.*;

/** Only generated files in our external app cache are ever removed. */
final class MusicCache {
    /** 网络音乐文件缓存上限；本地文件与本地 URI 直接读取。 */
    private static final long MAX_SINGLE=50L*1024*1024;
    /** 写入前要求的最小可用空间，低于此值不再下载，避免把存储写满。 */
    private static final long MIN_FREE=200L*1024*1024;
    /** 缓存总量上限，超出后按「目录内最新文件时间」从旧到新整目录淘汰。 */
    static final long MAX_TOTAL=512L*1024*1024;
    private static volatile long cacheEpoch;
    static File root(Context c)throws IOException{File base=c.getExternalCacheDir();if(base==null)throw new IOException("Android/data 缓存目录不可用");File root=new File(base,"music-converted");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建音乐缓存");return root;}
    static String hash(String value)throws Exception{byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
    static File folder(Context c,MusicTrack t)throws Exception{return new File(root(c),hash(t.id()+"|"+t.size+"|"+t.time+"|"+new ConnectionStore(c).thumbnailScope(t.profile)));}
    private static File analysisRoot(Context c){return new File(c.getFilesDir(),"music-analysis");}
    static File analysisFolder(Context c,MusicTrack t)throws Exception{
        return new File(analysisRoot(c),hash(t.id()+"|"+t.size+"|"+t.time+"|"+new ConnectionStore(c).thumbnailScope(t.profile)));
    }
    private static void writeAnalysisRecord(File record,byte[] bytes)throws IOException{
        File parent=record.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建音频分析目录");
        File temporary=File.createTempFile("analysis-",".part",parent);
        try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
            java.nio.file.Files.move(temporary.toPath(),record.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }finally{temporary.delete();}
    }
    /* cover() 看起来只是拼个路径，实际每次都要跑 SHA-256 + ConnectionStore 的 JSON 解析
       （folder() 里那一步）。播放页的 tick 每 500ms 就调一次，白烧得毫无必要 —— 路径是固定的，
       按曲目缓存起来即可。上限 512 条，超出整体清掉（只是重新算一次路径，没有正确性问题）。 */
    private static final java.util.Map<String,File> coverPaths=new java.util.concurrent.ConcurrentHashMap<>();
    static File cover(Context c,MusicTrack t)throws Exception{
        String key=t.id()+"|"+t.size+"|"+t.time+"|"+t.profile;
        File hit=coverPaths.get(key);
        if(hit!=null)return hit;
        File resolved=new File(folder(c,t),"cover.jpg");
        if(coverPaths.size()>512)coverPaths.clear();
        coverPaths.put(key,resolved);
        return resolved;
    }
    static File localCover(Context c,MusicTrack t)throws Exception{
        if(!t.profile.isEmpty())return null;
        File base=c.getExternalCacheDir();if(base==null)throw new IOException("Android/data 缓存目录不可用");
        File dir=new File(base,"music-artwork");if(!dir.isDirectory()&&!dir.mkdirs())return null;
        return new File(dir,hash(t.id()+"|"+t.size+"|"+t.time)+".jpg");
    }
    /** Prefetch never downloads remote media; analyse local files and already prepared copies. */
    static File availableAudio(Context c,MusicTrack track)throws Exception{
        if(track.profile.isEmpty()&&track.path.startsWith("content:"))return null;
        if(track.profile.isEmpty()&&!track.path.startsWith("content:")){File local=new File(track.path);return local.isFile()?local:null;}
        File dir=folder(c,track),record=new File(dir,"complete.json");
        if(record.isFile()&&record.length()<65536){org.json.JSONObject json=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(record.toPath()),StandardCharsets.UTF_8));File audio=new File(dir,json.getString("file"));if(audio.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())&&audio.isFile()&&audio.length()==json.getLong("size"))return audio;}
        return null;
    }
    static String ordinaryName(String name,String ext){int dot=name.lastIndexOf('.');String stem=dot<0?name:name.substring(0,dot);stem=stem.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");if(stem.length()>100)stem=stem.substring(0,100);if(stem.isEmpty())stem="music";return stem+"1."+ext;}
    /* 缓存目录锁：不能再用整个类一把 —— 预读下一首的下载会把「用户切歌时那一次 prepare」一起挡住，
       连点下一首就越点越卡。
       分段锁（固定 64 段）而不是「每目录一个锁对象」：后者是个只增不减的 Map，
       而键里带 size/time，改标签、重新扫描都会生成新键，长时间用下来就是几百 KB 不释放。
       64 段足够把并发下载打散，代价只是偶尔两首不相干的歌排一下队。 */
    private static final Object[] PREPARE_LOCKS=new Object[64];
    static{for(int i=0;i<PREPARE_LOCKS.length;i++)PREPARE_LOCKS[i]=new Object();}
    private static Object prepareLock(Context c,MusicTrack t){
        String key;
        try{key=folder(c,t).getCanonicalPath();}
        catch(Exception|LinkageError e){key=t.id()+"|"+t.size+"|"+t.time;}
        return PREPARE_LOCKS[(key.hashCode()&0x7fffffff)%PREPARE_LOCKS.length];
    }
    /* 整仓读写锁：prepare/波形写缓存持读锁（互相可并发），clear 持写锁（把所有人挡在外面）。
       没有它，用户在设置里点「清除缓存」会删掉正在下载的目录 —— 下载白跑、
       renameTo 抛错，还可能留下指向已删文件的 complete.json。 */
    private static final java.util.concurrent.locks.ReentrantReadWriteLock CACHE_LOCK=new java.util.concurrent.locks.ReentrantReadWriteLock();
    private static final java.util.concurrent.locks.Lock CACHE_READ=CACHE_LOCK.readLock(),CACHE_WRITE=CACHE_LOCK.writeLock();
    static File prepare(Context c,MusicTrack t)throws Exception{
        CACHE_READ.lock();
        try{synchronized(prepareLock(c,t)){return prepareLocked(c,t);}}
        finally{CACHE_READ.unlock();}
    }
    private static File prepareLocked(Context c,MusicTrack t)throws Exception{
        if(t.profile.isEmpty()&&t.path.startsWith("content:"))return null;
        if(!t.profile.isEmpty()){Endpoint.Protocol protocol=new ConnectionStore(c).get(t.profile).endpoint.protocol;if(protocol!=Endpoint.Protocol.FTP&&protocol!=Endpoint.Protocol.SMB)throw new IOException("音乐播放暂不支持该协议");}
        /* 本地文件直接返回原文件、不进缓存目录。
           原来这里会先 stat 一次再构造一个刷过 size/mtime 的 MusicTrack，但下一行就 return 了 ——
           那个对象从来没人读到，等于每首本地歌白建一次。 */
        if(t.profile.isEmpty()&&!t.path.startsWith("content:")){
            File local=new File(t.path);if(!local.isFile())throw new IOException("音乐文件不存在");return local;
        }
        // 先按目录列表给出的体积拒绝，避免为一个过大的文件白跑一次下载。
        if(t.size>MAX_SINGLE)throw new IOException("音乐文件超过 "+android.text.format.Formatter.formatFileSize(c,MAX_SINGLE)+"，无法播放");
        File dir=folder(c,t);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建音乐缓存");
        File record=new File(dir,"complete.json");
        if(record.isFile())try{org.json.JSONObject j=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(record.toPath()),StandardCharsets.UTF_8));String n=j.getString("file");File hit=new File(dir,n);if(hit.getParentFile().equals(dir)&&hit.isFile()&&hit.length()==j.getLong("size")&&hit.length()>0){hit.setLastModified(System.currentTimeMillis());return hit;}}catch(Exception ignored){}
        File part=new File(dir,"audio.part");
        try{
            /* 远程：直接下到 audio.part，下完 rename 成正式文件。
               以前是先下到 source.part、再整份 Files.copy 一份 —— 一首 50MB 的歌就多 50MB 读 + 50MB 写，
               峰值还同时占两份。content:// 与本地两个分支也一并去掉：
               content:// 第 97 行就 return 了，本地第 102 行 return 了，这两条本来就不可达。 */
            ConnectionStore.Profile p=new ConnectionStore(c).get(t.profile);if(p.endpoint.protocol!=Endpoint.Protocol.FTP&&p.endpoint.protocol!=Endpoint.Protocol.SMB)throw new IOException("音乐播放暂不支持该协议");
            final long[] checkedAt={0};
            try(RemoteSession remote=RemoteSessions.newSession(p.endpoint);FileOutputStream out=new FileOutputStream(part)){
                remote.connect();remote.download(t.path,out,bytes->{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消");
                    if(bytes>MAX_SINGLE)throw new IOException("音乐文件超过 "+android.text.format.Formatter.formatFileSize(c,MAX_SINGLE));
                    /* getUsableSpace() 是一次 statvfs：原来每个 chunk 都查，一首大歌累计上万次。每 8MB 查一次够了。 */
                    if(bytes-checkedAt[0]>=8L*1024*1024){checkedAt[0]=bytes;if(dir.getUsableSpace()<MIN_FREE)throw new IOException("可用空间不足 "+android.text.format.Formatter.formatFileSize(c,MIN_FREE)+"，请先清理存储");}});out.getFD().sync();
            }
            String ext=MusicDecoder.ext(t.name);
            File target=new File(dir,ordinaryName(t.name,ext));if(target.exists()&&!target.delete())throw new IOException("无法更新音乐缓存");if(!part.renameTo(target))throw new IOException("无法提交音乐缓存");
            org.json.JSONObject j=new org.json.JSONObject().put("file",target.getName()).put("size",target.length());File tmp=new File(dir,"complete.part");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(j.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!tmp.renameTo(record))throw new IOException("无法保存缓存索引");
            noteWritten(c,target);return target;
        }finally{part.delete();new File(dir,"complete.part").delete();}
    }
    /** 缓存总量的估算值，-1 表示还没量过。每次写入后累加，只有越过上限才真去扫一遍目录 ——
        原来是每准备完一首就无条件 trim 一次，那是把整个缓存根递归 measure() 一遍再逐目录 listFiles()。 */
    private static final java.util.concurrent.atomic.AtomicLong estimatedBytes=new java.util.concurrent.atomic.AtomicLong(-1);
    private static void noteWritten(Context c,File keep){
        try{
            if(estimatedBytes.get()<0){estimatedBytes.set(trim(c,keep));return;}
            if(estimatedBytes.addAndGet(keep.length())>MAX_TOTAL)estimatedBytes.set(trim(c,keep));
        }catch(Exception ignored){}
    }
    /** 清缓存拿写锁：把所有正在准备/正在写波形缓存的线程挡在外面，
        否则会把正在下载的目录删掉（下载白跑、renameTo 抛错、complete.json 指向已删文件）。 */
    static long clear(Context c,File keep)throws Exception{
        CACHE_WRITE.lock();
        try{cacheEpoch++;coverPaths.clear();File root=root(c);long count=clearGenerated(root,root.getCanonicalPath()+File.separator,keep==null?null:keep.getCanonicalPath());File analysis=analysisRoot(c);count+=clearGenerated(analysis,analysis.getCanonicalPath()+File.separator,null);c.getSharedPreferences("music",0).edit().putLong("cache_revision",System.currentTimeMillis()).apply();estimatedBytes.set(-1);return count;}
        finally{CACHE_WRITE.unlock();}
    }
    /** 本地只保留波形/频谱分析数据，清理旧音频副本和封面，绝不删除原始歌曲。 */
    static synchronized void discardLocal(Context c,MusicTrack track){
        if(!track.profile.isEmpty())return;
        try{
            File base=c.getExternalCacheDir();if(base==null)return;
            File root=new File(base,"music-converted");if(!root.isDirectory())return;
            File folder=new File(root,hash(track.id()+"|"+track.size+"|"+track.time+"|"+new ConnectionStore(c).thumbnailScope(track.profile)));
            if(!folder.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)||!folder.isDirectory())return;
            File[] files=folder.listFiles();if(files==null)return;
            for(File file:files){String name=file.getName();
                if(name.equals("waveform-v2.json")||name.equals("waveform-failed.json")||name.endsWith(".part"))continue;
                if(file.isFile())file.delete();
            }
            folder.delete();
        }catch(Exception ignored){}
    }
    /** 删除这首歌的整个缓存目录（音频副本 + 封面 + 波形），本地和远程都适用。
        删除歌曲文件后调用，别让没用的副本继续占着几百 MB。 */
    static synchronized void discard(Context c,MusicTrack track){
        if(track==null)return;
        try{
            File analysis=analysisFolder(c,track);clearGenerated(analysis,analysis.getCanonicalPath()+File.separator,null);analysis.delete();
            File dir=folder(c,track);
            File base=c.getExternalCacheDir();if(base==null)return;
            File root=new File(base,"music-converted");
            if(!dir.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)||!dir.isDirectory())return;
            clearGenerated(dir,dir.getCanonicalPath()+File.separator,null);dir.delete();
        }catch(Exception ignored){}
    }
    /**
     * 缓存总量超出 MAX_TOTAL 时，按「目录内最新文件的修改时间」从旧到新整目录淘汰。
     * keep 所在目录（正在播放的那一首）永不淘汰；删除失败不影响播放，直接跳过继续收下一批。
     */
    /** 返回**淘汰后剩余**的总量（调用方 noteWritten 用它刷新估算值）。 */
    static synchronized long trim(Context c,File keep)throws Exception{
        File root=root(c);File[] found=root.listFiles();if(found==null)return 0;
        String kept=keep==null?null:keep.getCanonicalFile().getParentFile().getCanonicalPath();
        java.util.ArrayList<Slot> slots=new java.util.ArrayList<>();long total=0;
        for(File folder:found){
            if(!folder.isDirectory()){total+=folder.length();continue;}
            long size=measure(folder),used=0;File[] files=folder.listFiles();
            if(files!=null)for(File file:files)used=Math.max(used,file.lastModified());
            if(used<=0)used=folder.lastModified();
            total+=size;
            if(!folder.getCanonicalPath().equals(kept))slots.add(new Slot(size,used,folder));
        }
        slots.sort((a,b)->Long.compare(a.used,b.used));
        long removed=0;
        for(Slot slot:slots){
            if(total<=MAX_TOTAL)break;
            try{clearGenerated(slot.folder,slot.folder.getCanonicalPath()+File.separator,null);slot.folder.delete();}
            catch(IOException failed){continue;}
            total-=slot.size;removed+=slot.size;
        }
        return total;
    }
    private static final class Slot{long size,used;final File folder;Slot(long size,long used,File folder){this.size=size;this.used=used;this.folder=folder;}}
    static long bytes(Context c)throws IOException{return measure(root(c))+measure(analysisRoot(c));}
    private static long measure(File file)throws IOException{if(!file.isDirectory())return file.length();long size=0;File[] files=file.listFiles();if(files!=null)for(File child:files)if(child.getCanonicalPath().startsWith(file.getCanonicalPath()+File.separator))size+=measure(child);return size;}
    private static long clearGenerated(File directory,String root,String keep)throws IOException{long size=0;File[] files=directory.listFiles();if(files==null)return 0;for(File file:files){String path=file.getCanonicalPath();if(!path.startsWith(root))continue;if(path.equals(keep))continue;if(file.isDirectory()){size+=clearGenerated(file,root,keep);File[] remaining=file.listFiles();if(remaining!=null&&remaining.length==0)file.delete();}else{long bytes=file.length();if(!file.delete())throw new IOException("部分音乐缓存无法删除");size+=bytes;}}return size;}
}
