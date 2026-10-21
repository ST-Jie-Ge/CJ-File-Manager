package com.cj.music.net;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SingleFlightResourceJvmTest {
    private static class Resource implements AutoCloseable {AtomicInteger closes=new AtomicInteger();public void close(){closes.incrementAndGet();}}
    @Test public void concurrentCallersCreateOneResource()throws Exception{
        AtomicInteger creates=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);Resource resource=new Resource();
        SingleFlightResource<Resource> lazy=new SingleFlightResource<>(()->{creates.incrementAndGet();entered.countDown();finish.await();return resource;});
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<Resource> one=pool.submit(lazy::get);assertTrue(entered.await(2,TimeUnit.SECONDS));Future<Resource> two=pool.submit(lazy::get);finish.countDown();assertSame(resource,one.get(2,TimeUnit.SECONDS));assertSame(resource,two.get(2,TimeUnit.SECONDS));assertEquals(1,creates.get());}
        finally{finish.countDown();lazy.close();pool.shutdownNow();}assertEquals(1,resource.closes.get());
    }
    @Test public void closeDuringInitializationDoesNotBlockAndDisposesLateResource()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);Resource resource=new Resource();SingleFlightResource<Resource> lazy=new SingleFlightResource<>(()->{entered.countDown();finish.await();return resource;});
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try{Future<?> work=pool.submit(()->{try{lazy.get();fail();}catch(IOException expected){}});assertTrue(entered.await(2,TimeUnit.SECONDS));lazy.close();finish.countDown();work.get(2,TimeUnit.SECONDS);assertEquals(1,resource.closes.get());}
        finally{finish.countDown();pool.shutdownNow();lazy.close();}assertEquals(1,resource.closes.get());
    }
    @Test public void initializationFailureCanBeRetried()throws Exception{
        AtomicInteger attempts=new AtomicInteger();Resource resource=new Resource();try(SingleFlightResource<Resource> lazy=new SingleFlightResource<>(()->{if(attempts.incrementAndGet()==1)throw new IOException("failed");return resource;})){
            try{lazy.get();fail();}catch(IOException e){assertEquals("failed",e.getMessage());}assertSame(resource,lazy.get());assertEquals(2,attempts.get());
        }assertEquals(1,resource.closes.get());
    }
}
