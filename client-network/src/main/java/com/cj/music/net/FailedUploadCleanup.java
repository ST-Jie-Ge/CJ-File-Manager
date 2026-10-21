package com.cj.music.net;

import java.util.concurrent.*;

/** One bounded recovery attempt; only deletes a unique temporary path owned by this upload. */
final class FailedUploadCleanup {
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),task->{Thread thread=new Thread(task,"Remote upload cleanup");thread.setDaemon(true);return thread;});
    static boolean schedule(Endpoint endpoint,String temporary){
        try{worker.execute(()->{
            RemoteSession recovery=null;
            try{
                if(endpoint.smb())recovery=new SmbSession(endpoint);
                else if(endpoint.webdav())recovery=new WebdavSession(endpoint);
                else recovery=new FtpSession(endpoint);
                recovery.connect();recovery.delete(temporary,false);
            }catch(Exception ignored){/* Offline/permission denied: never delete target or replay upload. */}
            finally{if(recovery!=null)recovery.close();}
        });return true;}catch(RejectedExecutionException full){return false;}
    }
    private FailedUploadCleanup(){}
}
