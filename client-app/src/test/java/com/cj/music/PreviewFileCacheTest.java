package com.cj.music;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class PreviewFileCacheTest {
    private final File cache;
    public PreviewFileCacheTest()throws IOException{cache=Files.createTempDirectory("preview-cache-").toFile();}
    @After public void cleanup()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(cache.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}}
    @Test public void browsingOneHundredImagesKeepsOnlyCurrentOriginal()throws Exception{
        File previous=null;try(PreviewFileCache preview=new PreviewFileCache(cache)){
            for(int i=0;i<100;i++){File next=preview.write("photo.jpg",out->out.write(new byte[]{1,2,3}));preview.retain(next);if(previous!=null)assertFalse(previous.exists());assertTrue(next.exists());previous=next;}
        }assertFalse(previous.exists());assertEquals(0,new File(cache,"client-previews").listFiles().length);
    }
    @Test public void failedDownloadDoesNotRemoveDisplayedImageOrLeaveNewDirectory()throws Exception{
        try(PreviewFileCache preview=new PreviewFileCache(cache)){
            File current=preview.write("current.jpg",out->out.write(1));preview.retain(current);
            try{preview.write("broken.jpg",out->{out.write(2);throw new IOException("broken");});fail();}catch(IOException expected){}
            assertTrue(current.exists());assertEquals(1,current.getParentFile().getParentFile().listFiles().length);
        }
    }
    @Test public void closingDuringDownloadReleasesLateFiles()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);ExecutorService io=Executors.newSingleThreadExecutor();PreviewFileCache preview=new PreviewFileCache(cache);
        try{Future<?> work=io.submit(()->{try{preview.write("late.jpg",out->{entered.countDown();finish.await();out.write(1);});fail();}catch(Exception expected){}});assertTrue(entered.await(2,TimeUnit.SECONDS));preview.close();finish.countDown();work.get(2,TimeUnit.SECONDS);File[] roots=new File(cache,"client-previews").listFiles();assertTrue(roots==null||roots.length==0);}
        finally{finish.countDown();preview.close();io.shutdownNow();}
    }
    @Test public void switchingToLocalImageNeverDeletesOriginalLocalFile()throws Exception{
        File local=new File(cache,"local.jpg");Files.write(local.toPath(),new byte[]{3});
        try(PreviewFileCache preview=new PreviewFileCache(cache)){File remote=preview.write("remote.jpg",out->out.write(1));preview.retain(local);preview.release(local);assertFalse(remote.exists());}
        assertArrayEquals(new byte[]{3},Files.readAllBytes(local.toPath()));
    }
    @Test public void repeatedShareReusesCopyAndSurvivesPreviewClose()throws Exception{
        File shared;try(PreviewFileCache preview=new PreviewFileCache(cache)){
            File remote=preview.write("photo.jpg",out->out.write(new byte[]{1,2}));shared=ImageExportCache.export(cache,remote,"photo.jpg");
            assertEquals(shared,ImageExportCache.export(cache,remote,"photo.jpg"));assertEquals(1,new File(cache,"client-exports").listFiles().length);
            File changed=preview.write("photo.jpg",out->out.write(new byte[]{4,5}));File next=ImageExportCache.export(cache,changed,"photo.jpg");assertNotEquals(shared,next);assertArrayEquals(new byte[]{4,5},Files.readAllBytes(next.toPath()));
        }assertArrayEquals(new byte[]{1,2},Files.readAllBytes(shared.toPath()));
    }
    @Test public void orphanCleanupPreservesLivePreviewsAndExports()throws Exception{
        try(PreviewFileCache preview=new PreviewFileCache(cache)){
            File current=preview.write("live.jpg",out->out.write(1));File shared=ImageExportCache.export(cache,current,"live.jpg");
            File orphan=new File(new File(cache,"client-previews"),UUID.randomUUID()+"/"+UUID.randomUUID());assertTrue(orphan.mkdirs());Files.write(new File(orphan,"stale.jpg").toPath(),new byte[]{2});
            PreviewFileCache.cleanOrphans(cache);assertFalse(orphan.exists());assertTrue(current.exists());assertTrue(shared.exists());
        }
    }
}
