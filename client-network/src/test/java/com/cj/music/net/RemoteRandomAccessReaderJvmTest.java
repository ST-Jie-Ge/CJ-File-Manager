package com.cj.music.net;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class RemoteRandomAccessReaderJvmTest {
    private RemoteSession source(byte[] data)throws Exception{
        RemoteSession session=mock(RemoteSession.class);
        when(session.openStream(eq("video"),anyLong())).thenAnswer(call->{int position=((Long)call.getArgument(1)).intValue();return new ByteArrayInputStream(data,position,data.length-position);});
        return session;
    }
    @Test public void readsTailMetadataThenReturnsToBeginningWithoutDownloadingMiddle()throws Exception{
        byte[] data=new byte[100];data[90]=9;data[0]=1;RemoteSession session=source(data);byte[] out=new byte[2];
        try(RemoteRandomAccessReader reader=new RemoteRandomAccessReader(session,"video",100,8)){
            assertEquals(2,reader.readAt(90,out,0,2));assertEquals(9,out[0]);assertEquals(2,reader.readAt(0,out,0,2));assertEquals(1,out[0]);
            assertEquals(2,reader.readAt(2,out,0,2));assertEquals(-1,reader.readAt(100,out,0,2));
        }
        verify(session).openStream("video",90);verify(session).openStream("video",0);verify(session,never()).openStream("video",2);
    }
    @Test public void enforcesThumbnailByteBudget()throws Exception{
        RemoteSession session=source(new byte[20]);try(RemoteRandomAccessReader reader=new RemoteRandomAccessReader(session,"video",20,4)){
            assertEquals(4,reader.readAt(0,new byte[10],0,10));try{reader.readAt(4,new byte[1],0,1);fail();}catch(IOException expected){}
        }
    }
    @Test public void closingReaderClosesFileAndRejectsFurtherReads()throws Exception{
        RemoteSession session=mock(RemoteSession.class);InputStream stream=mock(InputStream.class);when(session.openStream("video",0)).thenReturn(stream);when(stream.read(any(byte[].class),anyInt(),anyInt())).thenReturn(1);
        RemoteRandomAccessReader reader=new RemoteRandomAccessReader(session,"video",2,20);reader.readAt(0,new byte[1],0,1);reader.close();verify(stream).close();
        try{reader.readAt(1,new byte[1],0,1);fail();}catch(IOException expected){}
    }
    @Test public void wrappedBrokenPipeIsRecognizedButPermissionFailureIsNot(){
        assertTrue(RemoteFailures.disconnected(new IOException(new IllegalStateException(new java.net.SocketException("Broken pipe")))));
        assertFalse(RemoteFailures.disconnected(new IOException("STATUS_ACCESS_DENIED")));
    }
}
