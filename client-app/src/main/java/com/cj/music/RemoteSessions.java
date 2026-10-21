package com.cj.music;

import com.cj.music.net.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Browsing runs on one executor; transfers own separate sessions. No Activity owns a TLS trust bypass. */
final class RemoteSessions implements AutoCloseable {
    private final ConcurrentHashMap<String,RemoteSession> browsing=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final Object retentionLock=new Object();
    private final InactiveConnectionRetention retention=new InactiveConnectionRetention();
    private String activeConnection="";
    private final java.util.Set<String> needsProbe=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService expiry=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"Inactive remote connections");thread.setDaemon(true);return thread;});
    RemoteSessions(){expiry.scheduleAtFixedRate(this::expireInactive,1,1,java.util.concurrent.TimeUnit.SECONDS);}
    void activate(String id){synchronized(retentionLock){if(!id.equals(activeConnection)&&!id.isEmpty())needsProbe.add(id);activeConnection=id;updateRetention();}}
    private void updateRetention(){retention.select(activeConnection,browsing.keySet(),System.nanoTime()/1_000_000L);}
    private void register(String id,RemoteSession session)throws IOException{synchronized(retentionLock){if(closed){session.close();throw new IOException("页面已关闭");}browsing.put(id,session);retention.forget(id);updateRetention();}}
    private void expireInactive(){
        java.util.List<RemoteSession> expired=new java.util.ArrayList<>();
        synchronized(retentionLock){if(closed)return;updateRetention();for(String id:retention.expired(System.nanoTime()/1_000_000L)){RemoteSession session=browsing.remove(id);if(session!=null)expired.add(session);}}
        for(RemoteSession session:expired)try{session.close();}catch(RuntimeException ignored){}
    }
    /** 该连接当前是否已有可用会话；false 表示下次浏览需要先登录/重连。 */
    boolean connected(String id){RemoteSession session=browsing.get(id);return session!=null&&session.healthy();}
    RemoteSession browse(ConnectionStore.Profile profile) throws IOException {
        if(closed)throw new IOException("页面已关闭");
        RemoteSession found=browsing.get(profile.id);
        if(found!=null&&found.healthy()){try{if(needsProbe.remove(profile.id))found.probe();else found.checkIdle();return found;}catch(IOException e){discard(profile.id);if(RemoteFailures.interrupted(e))throw e;}}
        if(found!=null)discard(profile.id);
        RemoteSession created=newSession(profile.endpoint);register(profile.id,created);
        try{created.connect();needsProbe.remove(profile.id);if(closed)throw new IOException("页面已关闭");return created;}
        catch(IOException e){discard(profile.id);throw e;}
    }
    /** 按协议构造会话：SMB → SmbSession，WebDAV/OpenList → WebdavSession，其余 → FtpSession。 */
    static RemoteSession newSession(Endpoint endpoint) throws IOException {
        if (endpoint.smb()) return new SmbSession(endpoint);
        if (endpoint.webdav()) return new WebdavSession(endpoint);
        return new FtpSession(endpoint);
    }
    /** 首次连接：新建会话并带进度回调地连接，成功后注册进浏览缓存。 */
    RemoteSession connect(ConnectionStore.Profile profile,RemoteSession.ConnectListener listener) throws IOException {
        if(closed)throw new IOException("页面已关闭");
        discard(profile.id);
        RemoteSession created=newSession(profile.endpoint);
        created.setConnectListener(listener);
        register(profile.id,created);
        try{created.connect();if(closed)throw new IOException("页面已关闭");return created;}
        catch(IOException e){discard(profile.id);throw e;}
    }
    java.util.List<RemoteEntry> list(ConnectionStore.Profile profile,String directory) throws IOException {
        for(int attempt=0;;attempt++)try{return browse(profile).list(directory);}
        catch(IOException e){
            discard(profile.id);
            boolean disconnected=RemoteFailures.disconnected(e);
            if(attempt>0||!disconnected)throw e;
            // Safe retry for read-only listing only. Never replay uploads, renames or deletion.
        }
    }
    void discard(String id){RemoteSession old;synchronized(retentionLock){old=browsing.remove(id);retention.forget(id);}if(old!=null)old.close();}
    @Override public void close(){java.util.List<RemoteSession> sessions;synchronized(retentionLock){closed=true;sessions=new java.util.ArrayList<>(browsing.values());browsing.clear();needsProbe.clear();}expiry.shutdownNow();for(RemoteSession session:sessions)session.close();}
}
