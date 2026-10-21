package com.cj.music;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.*;
import com.cj.music.net.RemoteSession;
import com.cj.music.net.PlaybackSessionPool;
import java.io.*;
import java.util.concurrent.atomic.*;
/** Independent FTP/FTPS/SMB streaming session; no disk cache. */
final class RemoteDataSource extends BaseDataSource {
    static final class Factory implements DataSource.Factory,AutoCloseable {
        private final ConnectionStore.Profile profile;private final String remote;
        final AtomicLong received=new AtomicLong();
        private final PlaybackSessionPool sessions;
        Factory(ConnectionStore.Profile profile,String remote,long ignoredListedSize){this.profile=profile;this.remote=remote;sessions=new PlaybackSessionPool(()->RemoteSessions.newSession(profile.endpoint));}
        @Override public RemoteDataSource createDataSource(){return new RemoteDataSource(profile,remote,received,sessions);}
        @Override public void close(){sessions.close();}
    }
    private final ConnectionStore.Profile profile;private final String remote;private final AtomicLong received;
    private final PlaybackSessionPool sessions;
    private volatile RemoteSession session;private volatile InputStream stream;private volatile AtomicBoolean watch;
    private Uri uri;private long remaining=C.LENGTH_UNSET;private boolean opened,failed;
    private RemoteDataSource(ConnectionStore.Profile profile,String remote,AtomicLong received,PlaybackSessionPool sessions){super(true);this.profile=profile;this.remote=remote;this.received=received;this.sessions=sessions;}
    @Override public Uri getUri(){return uri;}
    @Override public long open(DataSpec spec)throws IOException {
        close();checkInterrupted();failed=false;uri=spec.uri;transferInitializing(spec);
        AtomicBoolean active=new AtomicBoolean(true);watch=active;Thread owner=Thread.currentThread();
        Thread cancellation=new Thread(()->{
            try{while(active.get()){if(owner.isInterrupted()){
                synchronized(RemoteDataSource.this){
                    RemoteSession current=session;InputStream in=stream;
                    if(active.get()&&watch==active&&current!=null){if(in!=null)current.cancelStream(in);else sessions.discard(current);}
                }
                return;
            }Thread.sleep(50);}}
            catch(InterruptedException ignored){}
        },"Remote playback cancellation");cancellation.setDaemon(true);cancellation.start();
        try {
            long actualSize=start(spec.position);
            checkInterrupted();
            remaining=spec.length!=C.LENGTH_UNSET?spec.length:actualSize<0?C.LENGTH_UNSET:actualSize-spec.position;
            opened=true;transferStarted(spec);return remaining;
        }catch(IOException|RuntimeException failure){failed=!Thread.currentThread().isInterrupted()||stream==null;close();throw failure;}
    }
    private long start(long offset)throws IOException {
        RemoteSession created=sessions.acquire(acquired->session=acquired);
        try {
            checkInterrupted();long length=created.streamSize(remote);
            if(length>=0&&offset>length)throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            if(length>=0&&offset==length){stream=new ByteArrayInputStream(new byte[0]);return length;}
            stream=created.openStream(remote,offset);checkInterrupted();return length;
        }catch(RemoteSession.SeekNotSupportedException unsupported){throw new RemoteSession.SeekNotSupportedException("服务器不支持 REST，无法在线定位到此位置。可下载后播放，或在服务器开启断点续传。");}
    }
    private static void checkInterrupted()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("播放已取消");}
    @Override public int read(byte[] buffer,int offset,int length)throws IOException {
        if(length==0)return 0;checkInterrupted();if(remaining==0)return C.RESULT_END_OF_INPUT;
        InputStream in=stream;if(in==null)throw new IOException("远程数据流已关闭");
        int n;try{n=in.read(buffer,offset,remaining==C.LENGTH_UNSET?length:(int)Math.min(length,remaining));checkInterrupted();}
        catch(IOException|RuntimeException error){
            boolean cancelled=Thread.currentThread().isInterrupted()||com.cj.music.net.RemoteFailures.interrupted(error);
            failed=!cancelled;if(cancelled)Thread.currentThread().interrupt();throw error;
        }
        if(n<0)return C.RESULT_END_OF_INPUT;
        received.addAndGet(n);if(remaining!=C.LENGTH_UNSET)remaining-=n;bytesTransferred(n);return n;
    }
    @Override public void close(){
        RemoteSession current;InputStream in;
        synchronized(this){AtomicBoolean active=watch;watch=null;if(active!=null)active.set(false);current=session;session=null;in=stream;stream=null;}
        boolean interrupted=Thread.interrupted();
        try{
            if(current!=null&&failed)sessions.discard(current);
            if(in!=null)try{in.close();}catch(IOException|RuntimeException ignored){failed=true;}
            if(current!=null)sessions.release(current,!failed);
        }finally{if(interrupted)Thread.currentThread().interrupt();}
        remaining=C.LENGTH_UNSET;uri=null;if(opened){opened=false;transferEnded();}
    }
}
