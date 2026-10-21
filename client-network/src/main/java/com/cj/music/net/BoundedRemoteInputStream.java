package com.cj.music.net;
import java.io.*;

/** Decoder-controlled reads may not exceed the thumbnail budget, even with stale file size. */
public final class BoundedRemoteInputStream extends FilterInputStream {
    private long remaining;
    public BoundedRemoteInputStream(InputStream input,long limit){super(input);if(limit<0)throw new IllegalArgumentException();remaining=limit;}
    @Override public int read()throws IOException{if(remaining==0)throw new IOException("缩略图读取已达上限");int n=in.read();if(n>=0)remaining--;return n;}
    @Override public int read(byte[] bytes,int offset,int count)throws IOException{if(count==0)return 0;if(remaining==0)throw new IOException("缩略图读取已达上限");int n=in.read(bytes,offset,(int)Math.min(count,remaining));if(n>0)remaining-=n;return n;}
    @Override public long skip(long count)throws IOException{long n=in.skip(Math.min(Math.max(0,count),remaining));remaining-=n;return n;}
    @Override public boolean markSupported(){return false;}
    @Override public void reset()throws IOException{throw new IOException("不支持重置远端读取预算");}
}
