package com.cj.music.net;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RemoteMutationJvmTest {
    private static class Access implements RemoteMutation.Access {
        RemoteSession first,next;int discards;
        Access(RemoteSession first,RemoteSession next){this.first=first;this.next=next;}
        public RemoteSession session(){return discards==0?first:next;}
        public void discard(){discards++;}
    }
    @Test public void staleConnectionReconnectsBeforeSendingWrite()throws Exception{
        RemoteSession stale=mock(RemoteSession.class),fresh=mock(RemoteSession.class);
        doThrow(new SocketException("dead")).when(stale).probe();
        RemoteMutation.run(new Access(stale,fresh),RemoteMutation.Kind.MKDIR,"/","","new",true);
        verify(stale,never()).mkdir("/","new");verify(fresh,times(1)).mkdir("/","new");
    }
    @Test public void lostDeleteReplyVerifiesAbsenceWithoutRepeatingDelete()throws Exception{
        RemoteSession old=mock(RemoteSession.class),fresh=mock(RemoteSession.class);
        when(old.list("/")).thenReturn(Collections.singletonList(new RemoteEntry("file","/file",false,2,0)));
        doThrow(new EOFException()).when(old).delete("/file",false);when(fresh.list("/")).thenReturn(Collections.emptyList());
        assertTrue(RemoteMutation.run(new Access(old,fresh),RemoteMutation.Kind.DELETE,"/","/file","file",false).contains("已不存在"));
        verify(old,times(1)).delete("/file",false);verify(fresh,never()).delete(anyString(),anyBoolean());
    }
    @Test public void lostRenameReplyReportsStateWithoutReplaying()throws Exception{
        RemoteSession old=mock(RemoteSession.class),fresh=mock(RemoteSession.class);
        doThrow(new SocketTimeoutException()).when(old).rename("/old","/","new");
        when(fresh.list("/")).thenReturn(Collections.singletonList(new RemoteEntry("new","/new",false,2,0)));
        try{RemoteMutation.run(new Access(old,fresh),RemoteMutation.Kind.RENAME,"/","/old","new",false);fail();}
        catch(IOException e){assertTrue(e.getMessage().contains("结果不确定"));assertTrue(e.getMessage().contains("原名称已不存在"));}
        verify(fresh,never()).rename(anyString(),anyString(),anyString());
    }
    @Test public void permissionDenialIsNotRetriedOrVerified()throws Exception{
        RemoteSession old=mock(RemoteSession.class),fresh=mock(RemoteSession.class);
        doThrow(new IOException("denied")).when(old).mkdir("/","new");
        try{RemoteMutation.run(new Access(old,fresh),RemoteMutation.Kind.MKDIR,"/","","new",true);fail();}catch(IOException e){assertEquals("denied",e.getMessage());}
        verifyNoInteractions(fresh);
    }
    @Test public void directoryLinkIsDeletedAsLinkDespiteUiDirectoryFlag()throws Exception{
        RemoteSession session=mock(RemoteSession.class);when(session.list("/")).thenReturn(Collections.singletonList(new RemoteEntry("link","/link",true,0,0,true)));
        RemoteMutation.run(new Access(session,session),RemoteMutation.Kind.DELETE,"/","/link","link",true);
        verify(session).delete("/link",false);verify(session,never()).delete("/link",true);
    }
    @Test public void failedVerificationDoesNotReplayOriginalWrite()throws Exception{
        RemoteSession old=mock(RemoteSession.class),fresh=mock(RemoteSession.class);
        doThrow(new EOFException()).when(old).mkdir("/","new");when(fresh.list("/")).thenThrow(new SocketException());
        try{RemoteMutation.run(new Access(old,fresh),RemoteMutation.Kind.MKDIR,"/","","new",true);fail();}catch(IOException e){assertTrue(e.getMessage().contains("核验失败"));}
        verify(old,times(1)).mkdir("/","new");verify(fresh,never()).mkdir(anyString(),anyString());
    }
    @Test public void batchDeletesEveryItemAfterSingleListing()throws Exception{
        RemoteSession session=mock(RemoteSession.class);
        when(session.list("/")).thenReturn(Arrays.asList(new RemoteEntry("a","/a",false,1,0),new RemoteEntry("b","/b",false,1,0)));
        assertNull(RemoteMutation.deleteBatch(new Access(session,session),"/",Arrays.asList(
                new RemoteMutation.Item("/a","a",false),new RemoteMutation.Item("/b","b",false)),(c,t,n)->{}));
        verify(session,times(1)).list("/");
        verify(session).delete("/a",false);verify(session).delete("/b",false);
    }
    @Test public void batchResolvesLinkFlagFromSingleListing()throws Exception{
        RemoteSession session=mock(RemoteSession.class);
        when(session.list("/")).thenReturn(Collections.singletonList(new RemoteEntry("link","/link",true,0,0,true)));
        RemoteMutation.deleteBatch(new Access(session,session),"/",Collections.singletonList(new RemoteMutation.Item("/link","link",true)),(c,t,n)->{});
        verify(session).delete("/link",false);
    }
    @Test public void batchTreatsMissingItemsAsNoticeNotFailure()throws Exception{
        RemoteSession session=mock(RemoteSession.class);
        when(session.list("/")).thenReturn(Collections.singletonList(new RemoteEntry("a","/a",false,1,0)));
        String notice=RemoteMutation.deleteBatch(new Access(session,session),"/",Arrays.asList(
                new RemoteMutation.Item("/a","a",false),new RemoteMutation.Item("/gone","gone",false)),(c,t,n)->{});
        assertTrue(notice.contains("gone"));assertTrue(notice.contains("已不存在"));
        verify(session).delete("/a",false);verify(session,never()).delete("/gone",false);
    }
    @Test public void batchStopsOnDisconnectAndVerifiesInsteadOfReplaying()throws Exception{
        RemoteSession old=mock(RemoteSession.class),fresh=mock(RemoteSession.class);
        when(old.list("/")).thenReturn(Arrays.asList(new RemoteEntry("a","/a",false,1,0),new RemoteEntry("b","/b",false,1,0)));
        doThrow(new EOFException()).when(old).delete("/a",false);
        when(fresh.list("/")).thenReturn(Collections.singletonList(new RemoteEntry("b","/b",false,1,0)));
        try{RemoteMutation.deleteBatch(new Access(old,fresh),"/",Arrays.asList(
                new RemoteMutation.Item("/a","a",false),new RemoteMutation.Item("/b","b",false)),(c,t,n)->{});fail();}
        catch(IOException e){assertTrue(e.getMessage().contains("删除中断"));assertTrue(e.getMessage().contains("仍在服务器"));}
        verify(old).delete("/a",false);
        verify(fresh,never()).delete(anyString(),anyBoolean());   // 断开后绝不盲发剩余项
    }
    @Test public void batchReportsProgressForEveryItem()throws Exception{
        RemoteSession session=mock(RemoteSession.class);
        when(session.list("/")).thenReturn(Arrays.asList(new RemoteEntry("a","/a",false,1,0),new RemoteEntry("b","/b",false,1,0)));
        final int[] calls={0};final int[] last={0};final int[] totals={0};
        RemoteMutation.deleteBatch(new Access(session,session),"/",Arrays.asList(
                new RemoteMutation.Item("/a","a",false),new RemoteMutation.Item("/b","b",false),new RemoteMutation.Item("/gone","gone",false)),
                (completed,total,current)->{calls[0]++;last[0]=completed;totals[0]=total;});
        assertEquals(3,calls[0]);assertEquals(3,last[0]);assertEquals(3,totals[0]);
    }
    @Test public void smbParallelismOverlapsDeletes()throws Exception{
        RemoteSession smb=mock(RemoteSession.class);
        when(smb.mutationParallelism()).thenReturn(4);
        when(smb.list("/")).thenReturn(Arrays.asList(new RemoteEntry("a","/a",false,1,0),new RemoteEntry("b","/b",false,1,0),
                new RemoteEntry("c","/c",false,1,0),new RemoteEntry("d","/d",false,1,0)));
        final CountDownLatch entered=new CountDownLatch(4);
        final AtomicInteger live=new AtomicInteger(),peak=new AtomicInteger();
        doAnswer(invocation->{
            peak.accumulateAndGet(live.incrementAndGet(),Math::max);
            entered.countDown();
            try{assertTrue("删除请求没有并发发出",entered.await(3,TimeUnit.SECONDS));}
            finally{live.decrementAndGet();}
            return null;
        }).when(smb).delete(anyString(),anyBoolean());
        RemoteMutation.deleteBatch(new Access(smb,smb),"/",Arrays.asList(
                new RemoteMutation.Item("/a","a",false),new RemoteMutation.Item("/b","b",false),
                new RemoteMutation.Item("/c","c",false),new RemoteMutation.Item("/d","d",false)),(c,t,n)->{});
        verify(smb).delete("/a",false);verify(smb).delete("/b",false);
        verify(smb).delete("/c",false);verify(smb).delete("/d",false);
        assertTrue("峰值并发只有 "+peak.get()+"，批量删除走了串行路径",peak.get()>1);
    }
}
