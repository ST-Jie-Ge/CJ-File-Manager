package com.cj.music.net;
import org.junit.Test;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import static org.junit.Assert.*;
public class RemoteFailuresJvmTest {
    @Test public void wrappedFutureCancellationSurvivesClearedThreadFlag(){
        assertTrue(RemoteFailures.interrupted(new IOException("SMB transport",new RuntimeException(new InterruptedException()))));
        assertTrue(RemoteFailures.interrupted(new IOException(new InterruptedIOException("cancelled"))));
    }
    @Test public void timeoutMustDiscardConnectionRatherThanBeTreatedAsUserSeek(){
        assertFalse(RemoteFailures.interrupted(new IOException(new SocketTimeoutException("timed out"))));
        assertFalse(RemoteFailures.interrupted(new IOException("bad response")));
    }
}
