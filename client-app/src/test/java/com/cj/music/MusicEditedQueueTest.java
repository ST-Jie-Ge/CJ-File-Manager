package com.cj.music;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class MusicEditedQueueTest {
    @Test public void metadataChangeReplacesPlaybackFileWithoutScan(){
        MusicTrack before=track("old.mp3",1),after=track("old.mp3",2),other=track("other.mp3",1);after.title="新曲名";
        List<MusicTrack> result=MusicPlaybackService.replaceEditedQueue(Arrays.asList(other,before),before,after);
        assertSame(other,result.get(0));assertSame(after,result.get(1));assertEquals(2,result.get(1).time);assertEquals("新曲名",result.get(1).title);
    }
    @Test public void pathReplacementDoesNotKeepOldOrDuplicateEntry(){
        MusicTrack before=track("old.mp3",1),after=track("new.mp3",2);
        List<MusicTrack> result=MusicPlaybackService.replaceEditedQueue(Arrays.asList(before,after),before,after);
        assertEquals(1,result.size());assertSame(after,result.get(0));assertEquals("/new.mp3",result.get(0).path);
    }
    private static MusicTrack track(String name,long time){return new MusicTrack(name,"/"+name,"",10,time);}
}
