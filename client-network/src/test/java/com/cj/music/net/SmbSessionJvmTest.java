package com.cj.music.net;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.*;
import com.rapid7.client.dcerpc.mssrvs.ServerService;
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercises production operations against controlled server replies; never touches a real share. */
public class SmbSessionJvmTest {
    private Endpoint endpoint(String share,String user,String password){return new Endpoint("localhost",445,Endpoint.Protocol.SMB,user,password,"UTF-8","","",share);}
    private void field(SmbSession client,String name,Object value)throws Exception{Field f=SmbSession.class.getDeclaredField(name);f.setAccessible(true);f.set(client,value);}
    private SmbSession client(String fixed,DiskShare disk)throws Exception{
        SmbSession client=new SmbSession(endpoint(fixed,"user","pass"));Session session=mock(Session.class);
        when(session.connectShare(anyString())).thenReturn(disk);field(client,"session",session);return client;
    }
    private SMBApiException status(NtStatus status){return new SMBApiException(status.getValue(),SMB2MessageCommandCode.SMB2_SET_INFO,"test reply",null);}
    @Test public void nonEmptyDirectoryIsRefusedWithoutRecursing()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("data",disk);
        DiskEntry entry=mock(DiskEntry.class);
        when(disk.open(eq("folder"),anySet(),anySet(),anySet(),any(),eq(EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)))).thenReturn(entry);
        doThrow(status(NtStatus.STATUS_DIRECTORY_NOT_EMPTY)).when(entry).deleteOnClose();
        try{c.delete("folder",true);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("目录非空"));}
        verify(entry).close();verify(disk,never()).rmdir(anyString(),anyBoolean());verify(disk,never()).rm(anyString());
    }
    @Test public void brokenDirectoryLinkIsDeletedThroughReparseHandleOnly()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("data",disk);DiskEntry link=mock(DiskEntry.class);
        when(disk.open(eq("broken"),eq(EnumSet.of(com.hierynomus.msdtyp.AccessMask.DELETE)),anySet(),anySet(),eq(SMB2CreateDisposition.FILE_OPEN),eq(EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)))).thenReturn(link);
        c.delete("broken",true);verify(link).deleteOnClose();verify(link).close();verify(disk,never()).rm(anyString());verify(disk,never()).rmdir(anyString(),anyBoolean());verify(disk,never()).list(anyString());
    }
    @Test public void explicitProbeReachesServerRootEvenWhenRecentlyUsed()throws Exception{
        SmbSession c=client("",mock(DiskShare.class));
        com.hierynomus.smbj.connection.Connection connection=mock(com.hierynomus.smbj.connection.Connection.class);
        when(connection.isConnected()).thenReturn(true);when(connection.getNegotiatedProtocol()).thenReturn(new com.hierynomus.smbj.connection.NegotiatedProtocol(SMB2Dialect.SMB_3_0,65536,65536,65536,true));field(c,"connection",connection);field(c,"lastUse",System.nanoTime());
        com.hierynomus.mssmb2.messages.SMB2Echo reply=new com.hierynomus.mssmb2.messages.SMB2Echo(SMB2Dialect.SMB_3_0);
        when(connection.<com.hierynomus.mssmb2.messages.SMB2Echo>send(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(reply));
        c.checkIdle();verify(connection,never()).send(any());c.probe();c.probe();verify(connection,times(2)).send(any(com.hierynomus.mssmb2.messages.SMB2Echo.class));
    }
    @Test public void probeTimeoutAndExpiredSessionAreRetryableButPermissionIsNot()throws Exception{
        assertTrue(RemoteFailures.disconnected(new IOException(new java.util.concurrent.TimeoutException())));
        assertTrue(RemoteFailures.disconnected(status(NtStatus.STATUS_USER_SESSION_DELETED)));
        assertFalse(RemoteFailures.disconnected(status(NtStatus.STATUS_ACCESS_DENIED)));
        assertFalse(RemoteFailures.disconnected(new IOException(new InterruptedException())));
    }
    @Test public void folderRenameUsesDirectoryCapableHandle()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("data",disk);Directory dir=mock(Directory.class);
        when(disk.list("")).thenReturn(Collections.emptyList());
        when(disk.open(eq("old"),anySet(),anySet(),anySet(),eq(SMB2CreateDisposition.FILE_OPEN),eq(EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)))).thenReturn(dir);
        c.rename("old","","new");verify(dir).rename("new",false);verify(dir).close();
        verify(disk,never()).openFile(anyString(),anySet(),anySet(),anySet(),any(),any());
    }
    @Test public void targetCreatedAfterPrecheckIsNotOverwritten()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("data",disk);DiskEntry src=mock(DiskEntry.class);
        when(disk.list("")).thenReturn(Collections.emptyList());
        when(disk.open(eq("old"),anySet(),anySet(),anySet(),any(),eq(EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)))).thenReturn(src);
        doThrow(status(NtStatus.STATUS_OBJECT_NAME_COLLISION)).when(src).rename("new",false);
        try{c.rename("old","","new");fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("未覆盖"));}
        verify(src,never()).rename(anyString(),eq(true));verify(src).close();
    }
    @Test public void uploadCommitRejectsConcurrentTarget()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("data",disk);
        com.hierynomus.smbj.share.File temporary=mock(com.hierynomus.smbj.share.File.class);
        DiskEntry commit=mock(DiskEntry.class);ByteArrayOutputStream out=new ByteArrayOutputStream();
        when(disk.list("")).thenReturn(Collections.emptyList());when(temporary.getOutputStream()).thenReturn(out);
        when(disk.openFile(anyString(),anySet(),anySet(),anySet(),eq(SMB2CreateDisposition.FILE_CREATE),isNull())).thenReturn(temporary);
        when(disk.open(anyString(),anySet(),anySet(),anySet(),eq(SMB2CreateDisposition.FILE_OPEN),eq(EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)))).thenReturn(commit);
        doThrow(status(NtStatus.STATUS_OBJECT_NAME_COLLISION)).when(commit).rename("file.txt",false);
        try{c.upload("","file.txt",new ByteArrayInputStream(new byte[]{1,2,3}),null);fail();}
        catch(IOException expected){assertTrue(expected.getMessage().contains("未覆盖"));}
        assertArrayEquals(new byte[]{1,2,3},out.toByteArray());verify(commit,never()).rename(anyString(),eq(true));
        verify(disk).rm(argThat(path->path.startsWith(".cjfm-")&&path.endsWith(".part")));
        verify(disk,never()).rm("file.txt");
    }
    @Test public void failedSourceClosesHandleBeforeCleaningOnlyItsTemporaryFile()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("data",disk);com.hierynomus.smbj.share.File temporary=mock(com.hierynomus.smbj.share.File.class);
        when(disk.list("")).thenReturn(Collections.emptyList());when(temporary.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(disk.openFile(anyString(),anySet(),anySet(),anySet(),eq(SMB2CreateDisposition.FILE_CREATE),isNull())).thenReturn(temporary);
        InputStream broken=new InputStream(){public int read()throws IOException{throw new IOException("source unreadable");}};
        try{c.upload("","target",broken,null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("已清理"));}
        org.mockito.InOrder order=inOrder(temporary,disk);order.verify(temporary).close();order.verify(disk).rm(argThat(path->path.startsWith(".cjfm-")&&path.endsWith(".part")));
        verify(disk,never()).rm("target");
    }
    @Test public void explicitShareRootRemainsBrowsable()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("data",disk);when(disk.list("")).thenReturn(Collections.emptyList());
        assertTrue(c.list("").isEmpty());assertTrue(c.list("").isEmpty());verify(disk,times(2)).list("");
    }
    @Test public void serverRootListsOnlyDiskShares()throws Exception{
        SmbSession c=client("",mock(DiskShare.class));ServerService rpc=mock(ServerService.class);field(c,"shareEnumeration",rpc);
        when(rpc.getShares1()).thenReturn(Arrays.asList(new NetShareInfo1("IPC$",3,""),new NetShareInfo1("printer",1,""),new NetShareInfo1("Movies",0,""),new NetShareInfo1("Data",0,"")));
        List<RemoteEntry> rows=c.list("");assertEquals(2,rows.size());assertEquals("Data",rows.get(0).path);assertTrue(rows.get(0).directory);assertEquals("Movies",rows.get(1).path);
    }
    @Test public void enumeratedShareCanBeEnteredAndReturnedToServerRoot()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("",disk);when(disk.list("")).thenReturn(Collections.emptyList());
        ServerService rpc=mock(ServerService.class);field(c,"shareEnumeration",rpc);when(rpc.getShares1()).thenReturn(Collections.singletonList(new NetShareInfo1("Data",0,"")));
        c.list("Data");assertEquals(1,c.list("").size());c.list("Data");verify(disk,times(2)).list("");
    }
    @Test public void serverRootAndShareRootCannotBeDeleted()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("",disk);
        for(String root:Arrays.asList("","Data")){try{c.delete(root,true);fail();}catch(IOException expected){}}
        verifyNoInteractions(disk);
    }
    @Test public void streamUsesShareAndOffsetAndClosesHandle()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("",disk);com.hierynomus.smbj.share.File file=mock(com.hierynomus.smbj.share.File.class);
        when(disk.openFile(eq("video.bin"),anySet(),anySet(),anySet(),any(),isNull())).thenReturn(file);
        when(file.read(any(byte[].class),eq(4L),eq(0),eq(3))).thenAnswer(call->{byte[] target=call.getArgument(0);System.arraycopy(new byte[]{5,6,7},0,target,0,3);return 3;});
        byte[] bytes=new byte[3];try(InputStream stream=c.openStream("Data/video.bin",4)){assertEquals(3,stream.read(bytes));}
        assertArrayEquals(new byte[]{5,6,7},bytes);verify(file).close();
    }
    @Test public void cancelledSmbReadClosesOnlyItsHandleOnce()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("data",disk);com.hierynomus.smbj.share.File first=mock(com.hierynomus.smbj.share.File.class),second=mock(com.hierynomus.smbj.share.File.class);
        when(disk.openFile(eq("movie"),anySet(),anySet(),anySet(),any(),isNull())).thenReturn(first,second);
        InputStream old=c.openStream("movie",0);c.cancelStream(old);old.close();
        try(InputStream next=c.openStream("movie",4)){c.cancelStream(old);verify(second,never()).close();}
        verify(first,times(1)).close();verify(second,times(1)).close();verify(disk,never()).close();
    }
    @Test public void anonymousAndGuestAreRealSmbIdentities(){
        assertTrue(SmbSession.authentication(endpoint("","","")).isAnonymous());
        assertTrue(SmbSession.authentication(endpoint("","Guest","")).isGuest());
        assertFalse(SmbSession.authentication(endpoint("","anonymous","anonymous@")).isAnonymous());
    }
    @Test public void ftpStillRejectsEmptyUsername(){
        try{new Endpoint("localhost",21,Endpoint.Protocol.FTP,"","","UTF-8","");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void oldAndNewPathsResolveWithoutDuplicateShare(){
        SmbLocation old=SmbLocation.resolve("Data","folder/file");assertEquals("Data",old.share);assertEquals("folder/file",old.path);
        SmbLocation discovered=SmbLocation.resolve("","Data/folder/file");assertEquals("Data",discovered.share);assertEquals(old.path,discovered.path);
        assertTrue(SmbLocation.resolve("","").serverRoot);assertFalse(SmbLocation.resolve("Data","").serverRoot);
    }
    @Test public void traversalAndUncPathsAreRejected(){
        for(String bad:Arrays.asList("Data/../file","Data/./file","\\\\server\\Data","C:\\file")){
            try{SmbLocation.resolve("",bad);fail(bad);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void malformedRpcReplyUsesAndroidCompatibleIOException()throws Exception{
        com.rapid7.client.dcerpc.messages.RequestResponse reply=new com.rapid7.client.dcerpc.messages.RequestResponse(){
            @Override public void unmarshalResponse(com.rapid7.client.dcerpc.io.PacketInput input){}
        };
        try{
            reply.unmarshal(new com.rapid7.client.dcerpc.io.PacketInput(new ByteArrayInputStream(new byte[]{0,0,0,0,1})));
            fail("Trailing RPC bytes must be rejected");
        }catch(com.rapid7.client.dcerpc.AndroidUnmarshalException expected){assertTrue(expected instanceof IOException);}
    }
    private byte[] rpcPacket(com.rapid7.client.dcerpc.Header packet)throws Exception{
        packet.setMajorVersion((byte)5);packet.setPFCFlags(EnumSet.of(com.rapid7.client.dcerpc.PFCFlag.FIRST_FRAGMENT,com.rapid7.client.dcerpc.PFCFlag.LAST_FRAGMENT));
        packet.setNDR(new byte[]{0x10,0,0,0});ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        packet.marshal(new com.rapid7.client.dcerpc.io.PacketOutput(bytes));return bytes.toByteArray();
    }
    @Test public void nativePipeBindsAndParsesShareEnumerationWithoutLegacySmbjMethods()throws Exception{
        Session session=mock(Session.class);PipeShare ipc=mock(PipeShare.class);NamedPipe pipe=mock(NamedPipe.class);
        when(session.connectShare("IPC$")).thenReturn(ipc);
        when(ipc.open(eq("srvsvc"),any(),anySet(),anySet(),anySet(),eq(SMB2CreateDisposition.FILE_OPEN),anySet())).thenReturn(pipe);
        com.rapid7.client.dcerpc.messages.BindResponse bind=new com.rapid7.client.dcerpc.messages.BindResponse();
        bind.setPDUType(com.rapid7.client.dcerpc.PDUType.BIND_ACK);bind.setMaxXmitFrag((short)16384);bind.setMaxRecvFrag((short)16384);
        byte[] ack=rpcPacket(bind);
        ByteArrayOutputStream stubBytes=new ByteArrayOutputStream();com.rapid7.client.dcerpc.io.PacketOutput stub=new com.rapid7.client.dcerpc.io.PacketOutput(stubBytes);
        // Level 1, non-null container, zero entries, null buffer, total 0, null resume, success.
        for(int value:new int[]{1,1,1,0,0,0,0,0})stub.writeInt(value);
        com.rapid7.client.dcerpc.messages.Response response=new com.rapid7.client.dcerpc.messages.Response();
        response.setPDUType(com.rapid7.client.dcerpc.PDUType.RESPONSE);response.setStub(stubBytes.toByteArray());byte[] reply=rpcPacket(response);
        when(pipe.transact(any(byte[].class),any(byte[].class))).thenAnswer(call->{byte[] outgoing=call.getArgument(0),incoming=call.getArgument(1);byte[] data=outgoing[2]==11?ack:reply;System.arraycopy(data,0,incoming,0,data.length);return data.length;});
        try(SmbRpcTransport transport=SmbRpcTransport.connect(session)){assertTrue(new ServerService(transport).getShares1().isEmpty());}
        verify(pipe,times(2)).transact(any(byte[].class),any(byte[].class));verify(pipe).closeSilently();
    }
    @Test public void cancellationClosesSocketBeforeGracefulResources()throws Exception{
        SmbSession c=new SmbSession(endpoint("","user","pass"));
        com.hierynomus.smbj.connection.Connection connection=mock(com.hierynomus.smbj.connection.Connection.class);
        com.hierynomus.smbj.SMBClient smb=mock(com.hierynomus.smbj.SMBClient.class);field(c,"connection",connection);field(c,"client",smb);
        c.close();org.mockito.InOrder order=inOrder(connection,smb);order.verify(connection).close(true);order.verify(smb).close();
    }
    @Test public void protocolSpecificOptionsAreIsolated(){
        Endpoint smb=new Endpoint("localhost",445,Endpoint.Protocol.SMB,"","","not-a-charset","ftp-pin","WORK","Data");
        assertEquals("UTF-8",smb.charset);assertEquals("",smb.certificatePin);assertTrue(SmbSession.authentication(smb).isAnonymous());
        Endpoint ftp=new Endpoint("localhost",21,Endpoint.Protocol.FTP,"anonymous","anonymous@","GBK","ignored","bad/domain","Data");
        assertEquals("GBK",ftp.charset);assertEquals("",ftp.domain);assertEquals("",ftp.share);assertEquals("anonymous@",ftp.password);
        // WebDAV/OpenList：用户名可匿名，share 承载起始目录，pin 生效（HTTPS），domain 忽略。
        Endpoint webdav=new Endpoint("dav.example",443,Endpoint.Protocol.WEBDAV,"","","UTF-8",String.join("",java.util.Collections.nCopies(64,"a")),"ignored","/dav/照片");
        assertTrue(webdav.webdav());assertFalse(webdav.smb());assertEquals("",webdav.domain);assertEquals("/dav/照片",webdav.share);
        Endpoint openlist=new Endpoint("nas.local",443,Endpoint.Protocol.OPENLIST,"user","pass","UTF-8","","","/media");
        assertTrue(openlist.webdav());assertEquals("/media",openlist.share);
    }
    @Test public void webdavRejectsTraversalRoot(){
        try{new Endpoint("dav.example",443,Endpoint.Protocol.WEBDAV,"","","UTF-8","","","/dav/../../etc");fail();}catch(IllegalArgumentException expected){}
        try{com.cj.music.net.WebdavSession.normalizeRootPath("/ok/../no");fail();}catch(IllegalArgumentException expected){}
    }
    /** 地址栏前缀即 scheme：http:// → 明文（pin 必须失效），https:// 与裸主机名 → TLS。 */
    @Test public void webdavSchemeComesFromHostPrefix(){
        String pin=String.join("",java.util.Collections.nCopies(64,"b"));
        Endpoint plain=new Endpoint("http://192.168.1.5",80,Endpoint.Protocol.WEBDAV,"","","UTF-8",pin,"","/dav");
        assertEquals("192.168.1.5",plain.host);assertTrue(plain.plainHttp);
        assertEquals("明文通道不该带证书指纹","",plain.certificatePin);
        assertEquals("http://192.168.1.5",plain.displayHost());
        Endpoint trailing=new Endpoint("HTTP://nas.local/",80,Endpoint.Protocol.OPENLIST,"","","UTF-8","","","");
        assertEquals("nas.local",trailing.host);assertTrue(trailing.plainHttp);
        Endpoint secure=new Endpoint("https://dav.example",443,Endpoint.Protocol.WEBDAV,"","","UTF-8",pin,"","");
        assertEquals("dav.example",secure.host);assertFalse(secure.plainHttp);
        assertEquals(pin.toLowerCase(java.util.Locale.ROOT),secure.certificatePin.toLowerCase(java.util.Locale.ROOT));
        Endpoint bare=new Endpoint("dav.example",443,Endpoint.Protocol.WEBDAV,"","","UTF-8","","","");
        assertFalse(bare.plainHttp);assertEquals("dav.example",bare.displayHost());
        // 标记随 withPin 延续（否则审批通过后的重试会退回 https）。
        assertTrue(plain.withPin("").plainHttp);assertFalse(secure.withPin(pin).plainHttp);
        // 非 WebDAV 协议不接受前缀，也不产生明文标记。
        try{new Endpoint("http://192.168.1.5",21,Endpoint.Protocol.FTP,"u","p","UTF-8","");fail();}catch(IllegalArgumentException expected){}
        assertFalse(new Endpoint("localhost",21,Endpoint.Protocol.FTP,"u","p","UTF-8","").plainHttp);
    }
    @Test public void disconnectedShareOpenAndDirectoryListRemainRetryable()throws Exception{
        DiskShare disk=mock(DiskShare.class);SmbSession c=client("",disk);
        Session session=(Session)getField(c,"session");
        RuntimeException broken=new com.hierynomus.smbj.common.SMBRuntimeException(new java.net.SocketException("Broken pipe"));
        when(session.connectShare("Data")).thenThrow(broken);
        try{c.list("Data");fail();}catch(IOException expected){assertTrue(RemoteFailures.disconnected(expected));}
        doReturn(disk).when(session).connectShare("Data");when(disk.list("")).thenThrow(broken);
        try{c.list("Data");fail();}catch(IOException expected){assertTrue(RemoteFailures.disconnected(expected));}
    }
    private Object getField(SmbSession client,String name)throws Exception{Field field=SmbSession.class.getDeclaredField(name);field.setAccessible(true);return field.get(client);}
}
