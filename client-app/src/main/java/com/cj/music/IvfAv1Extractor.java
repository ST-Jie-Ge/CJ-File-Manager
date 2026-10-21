package com.cj.music;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Map;

/** Reads IVF files carrying AV1 frames. IVF is frequently, but incorrectly, named .mp4. */
final class IvfAv1Extractor implements Extractor {
    private static final int HEADER_SIZE=32,FRAME_HEADER_SIZE=12,MAX_FRAME_SIZE=64*1024*1024;
    private static Index pendingIndex;

    static synchronized void setPendingIndex(Index index){pendingIndex=index;}

    static boolean isIvfFile(File file){
        try(RandomAccessFile raf=new RandomAccessFile(file,"r")){
            byte[] magic=new byte[12];raf.readFully(magic);
            return magic[0]=='D'&&magic[1]=='K'&&magic[2]=='I'&&magic[3]=='F'&&magic[8]=='A'&&magic[9]=='V'&&magic[10]=='0'&&magic[11]=='1';
        }catch(Exception e){return false;}
    }

    static Index buildIndex(File file){
        if(!isIvfFile(file))return null;
        /* IVF 是严格顺序格式：32 字节文件头，然后一帧帧 [12 字节帧头 + 载荷] 紧挨着排。
           以前用 RandomAccessFile 每帧 seek + read 两次系统调用，长视频（几万帧）在慢存储上
           是秒级操作，而调用方在主线程——直接 ANR。改成缓冲流顺序扫：跳过载荷交给
           BufferedInputStream（大块跳过会转为底层 seek），系统调用数量降一个量级。 */
        try(InputStream raw=new java.io.BufferedInputStream(new java.io.FileInputStream(file),256*1024);
            java.io.DataInputStream in=new java.io.DataInputStream(raw)){
            byte[] header=new byte[HEADER_SIZE];in.readFully(header);
            long rate=u32(header,16),scale=u32(header,20);
            long timeScale=rate<=0||scale<=0?1_000_000L:Math.max(1L,1_000_000L*scale/rate);
            long pos=HEADER_SIZE;
            ArrayList<Long> times=new ArrayList<>();ArrayList<Long> positions=new ArrayList<>();
            byte[] fh=new byte[FRAME_HEADER_SIZE];
            while(true){
                if(!readFully(in,fh))break;
                int size=(int)u32(fh,0);if(size<=0||size>MAX_FRAME_SIZE)break;
                long timestamp=u64(fh,4);
                positions.add(pos);
                times.add(timestamp*timeScale);
                pos+=FRAME_HEADER_SIZE+size;
                if(!skipFully(in,size))break;
            }
            if(times.isEmpty())return null;
            long[] t=new long[times.size()],p=new long[positions.size()];
            for(int i=0;i<t.length;i++){t[i]=times.get(i);p[i]=positions.get(i);}
            return new Index(t,p,file.length());
        }catch(Exception e){return null;}
    }
    private static boolean readFully(java.io.DataInputStream in,byte[] buffer){
        try{in.readFully(buffer);return true;}catch(java.io.EOFException e){return false;}catch(Exception e){return false;}
    }
    private static boolean skipFully(java.io.InputStream in,long count){
        try{
            long left=count;
            while(left>0){long n=in.skip(left);if(n<=0)return false;left-=n;}
            return true;
        }catch(Exception e){return false;}
    }
    /** 索引缓存：同一个文件第二次打开不用再扫一遍。key 带长度 + 修改时间，文件被改过自动失效。
        每条索引是两个 long[]（每帧 16 字节），长视频不小，所以只留最近两条。 */
    private static final Map<String,Index> INDEX_CACHE=new java.util.LinkedHashMap<String,Index>(4,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Index> eldest){return size()>2;}
    };
    /** 取缓存，没有就现建（可能耗时——调用方放后台线程）。 */
    static synchronized Index cachedIndex(File file){
        String key=file.getAbsolutePath()+"|"+file.length()+"|"+file.lastModified();
        Index index=INDEX_CACHE.get(key);
        if(index==null){index=buildIndex(file);if(index!=null)INDEX_CACHE.put(key,index);}
        return index;
    }

    private final byte[] header=new byte[HEADER_SIZE],frameHeader=new byte[FRAME_HEADER_SIZE];
    private ExtractorOutput output;private TrackOutput track;private boolean initialized;private long timeScale=1;
    private Index index;

    @Override public boolean sniff(ExtractorInput input)throws IOException{
        byte[] magic=new byte[12];boolean present=input.peekFully(magic,0,magic.length,true);input.resetPeekPosition();
        return present&&magic[0]=='D'&&magic[1]=='K'&&magic[2]=='I'&&magic[3]=='F'&&magic[8]=='A'&&magic[9]=='V'&&magic[10]=='0'&&magic[11]=='1';
    }
    @Override public void init(ExtractorOutput output){
        this.output=output;
        synchronized(IvfAv1Extractor.class){if(pendingIndex!=null){index=pendingIndex;pendingIndex=null;}}
    }
    @Override public int read(ExtractorInput input,PositionHolder seek)throws IOException{
        if(!initialized){
            if(!input.readFully(header,0,HEADER_SIZE,true))return RESULT_END_OF_INPUT;
            if(header[0]!='D'||header[1]!='K'||header[2]!='I'||header[3]!='F'||header[8]!='A'||header[9]!='V'||header[10]!='0'||header[11]!='1')throw new IOException("不是 AV1 IVF 文件");
            int width=u16(header,12),height=u16(header,14);long rate=u32(header,16),scale=u32(header,20);timeScale=rate<=0||scale<=0?1_000_000L:Math.max(1L,1_000_000L*scale/rate);
            track=output.track(0,C.TRACK_TYPE_VIDEO);track.format(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(width).setHeight(height).build());
            output.endTracks();
            output.seekMap(index!=null?index:new SeekMap.Unseekable(C.TIME_UNSET));
            initialized=true;
        }
        if(!input.readFully(frameHeader,0,FRAME_HEADER_SIZE,true))return RESULT_END_OF_INPUT;
        int size=(int)u32(frameHeader,0);if(size<=0||size>MAX_FRAME_SIZE)throw new IOException("无效 IVF 帧长度："+size);
        long timestamp=u64(frameHeader,4);byte[] frame=new byte[size];input.readFully(frame,0,size);
        track.sampleData(new ParsableByteArray(frame),size);track.sampleMetadata(timestamp*timeScale,C.BUFFER_FLAG_KEY_FRAME,size,0,null);
        return RESULT_CONTINUE;
    }
    @Override public void seek(long position,long timeUs){/* 输入已跳转到 SeekMap 给出的位置；逐帧读取即可。 */}
    @Override public void release(){}

    private static int u16(byte[] data,int offset){return (data[offset]&255)|((data[offset+1]&255)<<8);}
    private static long u32(byte[] data,int offset){return ((long)data[offset]&255)|(((long)data[offset+1]&255)<<8)|(((long)data[offset+2]&255)<<16)|(((long)data[offset+3]&255)<<24);}
    private static long u64(byte[] data,int offset){long low=u32(data,offset),high=u32(data,offset+4);return low|(high<<32);}

    static final class Index implements SeekMap {
        private final long[] times;
        private final long[] positions;
        private final long durationUs;
        private final long length;
        Index(long[] times,long[] positions,long length){
            this.times=times;this.positions=positions;this.length=length;
            this.durationUs=times.length>0?times[times.length-1]:C.TIME_UNSET;
        }
        @Override public boolean isSeekable(){return true;}
        @Override public long getDurationUs(){return durationUs;}
        @Override public SeekPoints getSeekPoints(long timeUs){
            int idx=upperBound(times,timeUs)-1;
            if(idx<0)idx=0;
            else if(idx>=times.length)idx=times.length-1;
            return new SeekPoints(new SeekPoint(times[idx],positions[idx]));
        }
    }

    private static int upperBound(long[] sorted,long value){
        int lo=0,hi=sorted.length;
        while(lo<hi){int mid=(lo+hi)>>>1;if(sorted[mid]<=value)lo=mid+1;else hi=mid;}
        return lo;
    }
}
