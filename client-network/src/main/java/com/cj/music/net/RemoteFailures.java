package com.cj.music.net;

/** SMBJ wraps socket errors in transport/runtime exceptions. Inspect the cause chain. */
public final class RemoteFailures {
    private RemoteFailures(){}
    /** Future.get() may clear the thread flag while wrapping cancellation in an SMB transport error. */
    public static boolean interrupted(Throwable error){
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause())
            if(cause instanceof InterruptedException||cause instanceof java.io.InterruptedIOException&&!(cause instanceof java.net.SocketTimeoutException))return true;
        return false;
    }
    public static boolean disconnected(Throwable error){
        if(interrupted(error))return false;
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=error;cause!=null&&seen.add(cause);cause=cause.getCause()){
            if(cause instanceof com.hierynomus.mssmb2.SMBApiException){
                com.hierynomus.mserref.NtStatus status=((com.hierynomus.mssmb2.SMBApiException)cause).getStatus();
                if(status==com.hierynomus.mserref.NtStatus.STATUS_USER_SESSION_DELETED
                        ||status==com.hierynomus.mserref.NtStatus.STATUS_NETWORK_SESSION_EXPIRED
                        ||status==com.hierynomus.mserref.NtStatus.STATUS_CONNECTION_DISCONNECTED
                        ||status==com.hierynomus.mserref.NtStatus.STATUS_NETWORK_NAME_DELETED)return true;
            }
            if(cause instanceof java.net.SocketException||cause instanceof java.io.EOFException
                    ||cause instanceof java.net.SocketTimeoutException||cause instanceof java.util.concurrent.TimeoutException
                    ||cause instanceof org.apache.commons.net.ftp.FTPConnectionClosedException
                    ||cause instanceof com.hierynomus.protocol.transport.TransportException
                    ||cause instanceof java.net.HttpRetryException
                    ||cause instanceof java.net.MalformedURLException)return true;
        }
        return false;
    }
}
