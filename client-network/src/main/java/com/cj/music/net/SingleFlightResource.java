package com.cj.music.net;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.*;

/** One initializer, including concurrent callers. No network IO under the publication lock. */
final class SingleFlightResource<T extends AutoCloseable> implements AutoCloseable {
    interface Creator<T> { T create() throws Exception; }
    private final Creator<T> creator;
    private FutureTask<T> task;
    private T value;
    private boolean closed;
    SingleFlightResource(Creator<T> creator){this.creator=creator;}
    T get() throws IOException {
        FutureTask<T> selected;
        synchronized(this){
            if(closed)throw new InterruptedIOException("连接已关闭");
            if(task==null)task=new FutureTask<>(()->{
                T created=creator.create();
                synchronized(this){if(!closed){value=created;return created;}}
                dispose(created);throw new InterruptedIOException("连接已关闭");
            });
            selected=task;
        }
        selected.run();
        try{return selected.get();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("共享枚举已取消");}
        catch(ExecutionException e){synchronized(this){if(task==selected)task=null;}Throwable cause=e.getCause();if(cause instanceof IOException)throw (IOException)cause;throw new IOException("共享枚举初始化失败",cause);}
    }
    public void close(){T old;synchronized(this){closed=true;old=value;value=null;}dispose(old);}
    private static void dispose(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ignored){}}
}
