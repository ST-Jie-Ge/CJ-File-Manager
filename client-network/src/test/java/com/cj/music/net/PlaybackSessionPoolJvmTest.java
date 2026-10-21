package com.cj.music.net;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlaybackSessionPoolJvmTest {
    private RemoteSession session(){RemoteSession session=mock(RemoteSession.class);when(session.healthy()).thenReturn(true);return session;}
    @Test public void successiveSeeksAndNewDataSourcesReuseOneLogin()throws Exception{
        RemoteSession remote=session();PlaybackSessionPool.Creator creator=mock(PlaybackSessionPool.Creator.class);when(creator.create()).thenReturn(remote);
        try(PlaybackSessionPool pool=new PlaybackSessionPool(creator)){
            for(int i=0;i<5;i++){RemoteSession lease=pool.acquire();assertSame(remote,lease);pool.release(lease,true);}
            verify(creator,times(1)).create();verify(remote,times(1)).connect();verify(remote,never()).close();
        }verify(remote).close();
    }
    @Test public void failedStreamIsDiscardedAndNextOpenReconnects()throws Exception{
        RemoteSession first=session(),second=session();PlaybackSessionPool.Creator creator=mock(PlaybackSessionPool.Creator.class);when(creator.create()).thenReturn(first,second);
        try(PlaybackSessionPool pool=new PlaybackSessionPool(creator)){
            pool.release(pool.acquire(),false);assertSame(second,pool.acquire());verify(first).close();verify(second).connect();
        }
    }
    @Test public void simultaneousLoadersDoNotShareAControlPipeline()throws Exception{
        RemoteSession first=session(),second=session();PlaybackSessionPool.Creator creator=mock(PlaybackSessionPool.Creator.class);when(creator.create()).thenReturn(first,second);
        try(PlaybackSessionPool pool=new PlaybackSessionPool(creator)){assertNotSame(pool.acquire(),pool.acquire());}
    }
    @Test public void lateCancellationCannotCloseAnotherLease()throws Exception{
        RemoteSession first=session(),second=session();PlaybackSessionPool.Creator creator=mock(PlaybackSessionPool.Creator.class);when(creator.create()).thenReturn(first,second);
        try(PlaybackSessionPool pool=new PlaybackSessionPool(creator)){
            RemoteSession old=pool.acquire(),current=pool.acquire();pool.discard(old);pool.release(old,true);
            pool.release(current,true);assertSame(second,pool.acquire());verify(second,never()).close();
        }
    }
    @Test public void destroyInterruptsConnectAndPreventsPublishingIt()throws Exception{
        RemoteSession remote=session();CountDownLatch connecting=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        doAnswer(call->{connecting.countDown();assertTrue(cancelled.await(2,TimeUnit.SECONDS));return null;}).when(remote).connect();
        doAnswer(call->{cancelled.countDown();return null;}).when(remote).close();
        PlaybackSessionPool pool=new PlaybackSessionPool(()->remote);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<?> request=worker.submit(()->{try{pool.acquire();fail();}catch(IOException expected){}});assertTrue(connecting.await(2,TimeUnit.SECONDS));pool.close();request.get(2,TimeUnit.SECONDS);}
        finally{pool.close();worker.shutdownNow();}
        try{pool.acquire();fail();}catch(IOException expected){}
    }
    @Test public void destroyDoesNotWaitForTlsContextConstruction()throws Exception{
        RemoteSession remote=session();CountDownLatch creating=new CountDownLatch(1),finish=new CountDownLatch(1);
        PlaybackSessionPool pool=new PlaybackSessionPool(()->{creating.countDown();try{assertTrue(finish.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IOException(e);}return remote;});
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<?> request=worker.submit(()->{try{pool.acquire();fail();}catch(IOException expected){}});assertTrue(creating.await(2,TimeUnit.SECONDS));pool.close();finish.countDown();request.get(2,TimeUnit.SECONDS);verify(remote,never()).connect();verify(remote).close();}
        finally{finish.countDown();pool.close();worker.shutdownNow();}
    }
}
