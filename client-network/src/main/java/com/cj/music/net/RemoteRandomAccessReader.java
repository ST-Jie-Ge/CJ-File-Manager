package com.cj.music.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** Bounded random reads for media metadata; never downloads a whole video to disk. */
public final class RemoteRandomAccessReader implements Closeable {
    private final RemoteSession session;
    private final String path;
    private final long length,limit;
    private long position=-1,received;
    private InputStream stream;
    private boolean closed;
    private int opens;
    public RemoteRandomAccessReader(RemoteSession session,String path,long length,long limit){
        this.session=session;this.path=path;this.length=length;this.limit=limit;
    }
    public synchronized int readAt(long offset,byte[] buffer,int start,int count)throws IOException{
        if(closed)throw new IOException("缩略图读取已关闭");
        if(offset<0||start<0||count<0||start>buffer.length-count)throw new IllegalArgumentException("无效的读取范围");
        if(count==0)return 0;if(length>=0&&offset>=length)return -1;
        if(received>=limit)throw new IOException("缩略图读取已达上限");
        if(stream==null||position!=offset){if(stream!=null){stream.close();stream=null;}if(++opens>128)throw new IOException("缩略图定位次数已达上限");stream=session.openStream(path,offset);position=offset;}
        int wanted=(int)Math.min(count,limit-received);if(length>=0)wanted=(int)Math.min(wanted,length-offset);
        int n=stream.read(buffer,start,wanted);if(n>0){position+=n;received+=n;}return n;
    }
    public long size(){return length;}
    @Override public synchronized void close()throws IOException{closed=true;if(stream!=null){stream.close();stream=null;}}
}
