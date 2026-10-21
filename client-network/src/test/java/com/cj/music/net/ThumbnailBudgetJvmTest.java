package com.cj.music.net;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ThumbnailBudgetJvmTest {
    @Test public void decoderCannotReadOrSkipBeyondBudget()throws Exception{
        try(BoundedRemoteInputStream stream=new BoundedRemoteInputStream(new ByteArrayInputStream(new byte[100]),4)){
            assertEquals(2,stream.skip(2));assertEquals(2,stream.read(new byte[20]));assertEquals(0,stream.skip(80));
            try{stream.read();fail();}catch(IOException expected){}assertFalse(stream.markSupported());
        }
    }
    @Test public void unsupportedRestDoesNotSilentlyRestartAtZero()throws Exception{
        RemoteSession session=mock(RemoteSession.class);when(session.openStream("movie",900)).thenThrow(new RemoteSession.SeekNotSupportedException("REST"));
        try(RemoteRandomAccessReader reader=new RemoteRandomAccessReader(session,"movie",1000,32)){
            try{reader.readAt(900,new byte[1],0,1);fail();}catch(RemoteSession.SeekNotSupportedException expected){}
        }verify(session,never()).openStream("movie",0);
    }
}
