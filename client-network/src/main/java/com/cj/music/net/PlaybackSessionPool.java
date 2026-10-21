package com.cj.music.net;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Owned by one player, never shared with browsing, thumbnails or transfers. */
public final class PlaybackSessionPool implements AutoCloseable {
    public interface Creator { RemoteSession create() throws IOException; }
    private final Creator creator;
    private final Set<RemoteSession> leased=new HashSet<>();
    private RemoteSession idle;
    private boolean closed;
    public PlaybackSessionPool(Creator creator){this.creator=creator;}
    public RemoteSession acquire() throws IOException {
        return acquire(session->{});
    }
    public RemoteSession acquire(java.util.function.Consumer<RemoteSession> onLease) throws IOException {
        RemoteSession result;boolean connect;
        synchronized(this){
            if(closed)throw new IOException("播放器已关闭");
            result=idle;idle=null;connect=result==null;
        }
        // Creating an SSL context can be expensive; player teardown must not wait for it.
        if(connect)result=creator.create();
        boolean rejected;
        synchronized(this){rejected=closed;if(!rejected)leased.add(result);}
        if(rejected){result.close();throw new IOException("播放器已关闭");}
        try{
            onLease.accept(result);
            if(connect)result.connect();else result.checkIdle();
            synchronized(this){if(closed||!leased.contains(result))throw new IOException("播放已取消");}
            return result;
        }catch(IOException|RuntimeException failure){discard(result);throw failure;}
    }
    /** Only return a session after its file stream has completed cleanly. */
    public void release(RemoteSession session,boolean reusable){
        boolean keep;
        synchronized(this){keep=leased.remove(session)&&!closed&&reusable&&session.healthy()&&idle==null;if(keep)idle=session;}
        if(!keep)session.close();
    }
    public void discard(RemoteSession session){synchronized(this){leased.remove(session);if(idle==session)idle=null;}session.close();}
    @Override public void close(){
        Set<RemoteSession> all;
        synchronized(this){if(closed)return;closed=true;all=new HashSet<>(leased);leased.clear();if(idle!=null){all.add(idle);idle=null;}}
        for(RemoteSession session:all)session.close();
    }
}
