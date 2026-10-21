package com.cj.music.net;

import org.junit.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real sockets and TLS, deterministic server replies; production code is the client under test. */
public class FtpSessionTest {
    private Fixture server;
    @After public void stop() throws Exception {if(server!=null)server.close();}
    private Endpoint endpoint(Endpoint.Protocol mode,String password,String pin){return new Endpoint("localhost",server.port(),mode,"test",password,"UTF-8",pin);}
    private FtpSession connect(Endpoint.Protocol mode)throws Exception{server=new Fixture(mode==Endpoint.Protocol.FTPS_IMPLICIT);FtpSession client=new FtpSession(endpoint(mode,"test",mode==Endpoint.Protocol.FTP?"":server.pin));client.connect();return client;}
    private void roundTrip(FtpSession session)throws Exception {
        byte[] bytes=new byte[512*1024+37];new Random(7).nextBytes(bytes);
        session.mkdir("/","中文 空格");
        session.upload("/中文 空格","data.bin",new ByteArrayInputStream(bytes),null);
        List<RemoteEntry> rows=session.list("/中文 空格");assertEquals(1,rows.size());assertEquals("data.bin",rows.get(0).name);
        ByteArrayOutputStream result=new ByteArrayOutputStream();session.download(rows.get(0).path,result,null);assertArrayEquals(bytes,result.toByteArray());
        session.rename("/中文 空格/data.bin","/中文 空格","changed.bin");session.delete("/中文 空格/changed.bin",false);session.delete("/中文 空格",true);
        assertTrue(session.list("/").isEmpty());
    }
    @Test public void ftpBinaryRoundTripAndOperations()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTP)){roundTrip(c);}}
    @Test public void explicitTlsProtectsControlAndData()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTPS_EXPLICIT)){roundTrip(c);assertTrue(server.commands.contains("AUTH TLS"));assertTrue(server.commands.contains("PBSZ 0"));assertTrue(server.commands.contains("PROT P"));assertTrue(server.tlsData.get()>0);}}
    @Test public void implicitTlsRoundTrip()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTPS_IMPLICIT)){roundTrip(c);assertFalse(server.commands.contains("AUTH TLS"));assertTrue(server.tlsData.get()>0);}}
    @Test public void untrustedCertificateFailsBeforeCredentials()throws Exception{server=new Fixture(false);try(FtpSession c=new FtpSession(endpoint(Endpoint.Protocol.FTPS_EXPLICIT,"test",""))){try{c.connect();fail("must request certificate approval");}catch(CertificateApprovalException e){assertEquals(server.pin,e.fingerprint);}assertFalse(server.commands.stream().anyMatch(s->s.startsWith("USER")));}}
    @Test public void changedPinnedCertificateRejected()throws Exception{server=new Fixture(false);try(FtpSession c=new FtpSession(endpoint(Endpoint.Protocol.FTPS_EXPLICIT,"test",String.join("",Collections.nCopies(64,"0"))))){try{c.connect();fail();}catch(IOException expected){assertFalse(expected instanceof CertificateApprovalException);}assertFalse(server.commands.stream().anyMatch(s->s.startsWith("USER")));}}
    @Test public void wrongPasswordRejected()throws Exception{server=new Fixture(false);try(FtpSession c=new FtpSession(endpoint(Endpoint.Protocol.FTP,"wrong",""))){try{c.connect();fail();}catch(IOException e){assertTrue(e.getMessage().contains("530"));}}}
    @Test public void anonymousTlsLogin()throws Exception{server=new Fixture(false);Endpoint p=new Endpoint("localhost",server.port(),Endpoint.Protocol.FTPS_EXPLICIT,"anonymous","anonymous@","UTF-8",server.pin);try(FtpSession c=new FtpSession(p)){c.connect();assertTrue(c.list("/").isEmpty());}}
    @Test public void epsvFallsBackAndIgnoresForeignPasvAddress()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.noEpsv=true;server.files.put("/a.txt","a".getBytes(StandardCharsets.UTF_8));assertEquals(1,c.list("/").size());assertTrue(server.commands.contains("PASV"));}}
    @Test public void listFallbackOnlyWhenMlsdUnsupported()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.noMlsd=true;server.files.put("/中文 file.txt",new byte[]{1,2});assertEquals("中文 file.txt",c.list("/").get(0).name);assertTrue(server.commands.stream().anyMatch(s->s.startsWith("LIST")));}}
    @Test public void listingPermissionFailureIsNotAnEmptyDirectory()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.denyListing=true;try{c.list("/");fail();}catch(IOException e){assertTrue(e.getMessage().contains("550"));}assertFalse(server.commands.contains("LIST"));}}
    @Test public void mlsdLinksStayVisibleAndDirectoryProbeRestoresCwd()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){
            server.directories.add("/link");server.customListing="type=cdir; named-root\r\ntype=pdir; named-parent\r\ntype=OS.unix=slink:/target;size=0; link\r\ntype=OS.unix=symlink;size=0; broken\r\ntype=file;size=2; 中文 file.txt\r\n";
            List<RemoteEntry> rows=c.list("/");assertEquals(3,rows.size());assertTrue(rows.get(0).link);assertTrue(rows.get(0).directory);assertTrue(rows.get(1).link);assertFalse(rows.get(1).directory);assertEquals("中文 file.txt",rows.get(2).name);assertEquals("/",c.workingDirectory());
        }
    }
    @Test public void legacyListDirectoryLinkIsRecognizedAndDeletedWithDele()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){
            server.noMlsd=true;server.directories.add("/link");server.files.put("/link",new byte[0]);server.customListing="lrwxrwxrwx 1 user group 7 Jan 01 2026 link -> /target\r\n";
            RemoteEntry row=c.list("/").get(0);assertTrue(row.link);assertTrue(row.directory);assertEquals("/",c.workingDirectory());
            RemoteMutation.run(new RemoteMutation.Access(){public RemoteSession session(){return c;}public void discard(){c.close();}},RemoteMutation.Kind.DELETE,"/","/link","link",true);
            assertTrue(server.commands.contains("DELE /link"));assertFalse(server.commands.contains("RMD /link"));assertTrue(server.directories.contains("/link"));
        }
    }
    @Test public void lostDeletionReplyReconnectsOnlyToVerifyRealServerState()throws Exception{
        try(FtpSession initial=connect(Endpoint.Protocol.FTP)){
            server.files.put("/file",new byte[]{1});server.disconnectDelete=true;
            class Access implements RemoteMutation.Access,AutoCloseable {FtpSession current=initial;public RemoteSession session()throws IOException{if(current==null){current=new FtpSession(endpoint(Endpoint.Protocol.FTP,"test",""));current.connect();}return current;}public void discard(){if(current!=null)current.close();current=null;}public void close(){discard();}}
            try(Access access=new Access()){assertTrue(RemoteMutation.run(access,RemoteMutation.Kind.DELETE,"/","/file","file",false).contains("已不存在"));}
            assertEquals(1,server.commands.stream().filter(s->s.equals("DELE /file")).count());assertFalse(server.files.containsKey("/file"));
        }
    }
    @Test public void ftpVideoMetadataReaderCanSeekWithoutFetchingMiddle()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.supportRest=true;server.supportSize=true;byte[] data=new byte[1024*1024];data[data.length-1]=7;data[0]=2;server.files.put("/video",data);
            try(RemoteRandomAccessReader reader=new RemoteRandomAccessReader(c,"/video",c.streamSize("/video"),32)){
                byte[] out=new byte[1];assertEquals(1,reader.readAt(data.length-1,out,0,1));assertEquals(7,out[0]);assertEquals(1,reader.readAt(0,out,0,1));assertEquals(2,out[0]);
            }assertEquals(1,server.commands.stream().filter(s->s.startsWith("USER")).count());
        }
    }
    @Test public void refusesClearDataFallback()throws Exception{server=new Fixture(false);server.rejectProt=true;try(FtpSession c=new FtpSession(endpoint(Endpoint.Protocol.FTPS_EXPLICIT,"test",server.pin))){try{c.connect();fail();}catch(IOException expected){}assertFalse(server.commands.contains("PROT C"));}}
    @Test public void finalTransferFailureNotReportedAsSuccess()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.files.put("/a.bin",new byte[]{0,1,2});server.badCompletion=true;try{c.download("/a.bin",new ByteArrayOutputStream(),null);fail();}catch(IOException e){assertTrue(e.getMessage().contains("451"));}assertFalse(c.healthy());}}
    @Test public void existingUploadNeverOverwritten()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.files.put("/a",new byte[]{7});try{c.upload("/","a",new ByteArrayInputStream(new byte[]{9}),null);fail();}catch(IOException expected){}assertArrayEquals(new byte[]{7},server.files.get("/a"));assertFalse(server.commands.stream().anyMatch(s->s.startsWith("STOR")));}}
    @Test public void cancelClosesBlockedDataRead()throws Exception{try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.files.put("/slow",new byte[1]);server.stall=true;ExecutorService executor=Executors.newSingleThreadExecutor();try{Future<?> work=executor.submit(()->{try{c.download("/slow",new ByteArrayOutputStream(),null);throw new AssertionError("unexpected success");}catch(IOException expected){}});assertTrue(server.transferStarted.await(3,TimeUnit.SECONDS));c.close();work.get(3,TimeUnit.SECONDS);}finally{executor.shutdownNow();}}}
    @Test public void commandInjectionAndTraversalNamesRejected(){try{new Endpoint("localhost",21,Endpoint.Protocol.FTP,"user\r\nDELE x","p","UTF-8","");fail();}catch(IllegalArgumentException expected){}for(String bad:new String[]{"../x","..","x\nDELE a","x\0"})try{FtpSession.child("/",bad);fail(bad);}catch(IllegalArgumentException expected){}assertEquals("/中文 a",FtpSession.child("/","中文 a"));}
    @Test public void repeatedOffsetReadsKeepControlConnectionSynchronized()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){
            server.supportRest=true;server.supportSize=true;byte[] bytes=new byte[2*1024*1024];new Random(3).nextBytes(bytes);server.files.put("/movie",bytes);
            for(int offset:new int[]{0,4096,900000,2})try(InputStream stream=c.openStream("/movie",offset)){assertEquals(bytes[offset]&255,stream.read());}
            assertEquals(bytes.length,c.streamSize("/movie"));assertEquals(1,c.list("/").size());
            assertEquals(1,server.commands.stream().filter(s->s.startsWith("USER")).count());
        }
    }
    @Test public void normalStreamEofConsumesCompletionBeforeNextCommand()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.files.put("/movie",new byte[]{1,2});server.supportSize=true;
            try(InputStream stream=c.openStream("/movie",0)){while(stream.read()!=-1){}}
            assertEquals(2,c.streamSize("/movie"));assertEquals(1,c.list("/").size());
        }
    }
    @Test public void cancelledOldStreamCannotCloseNewReadOrForceRelogin()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){
            server.supportRest=true;server.files.put("/movie",new byte[2*1024*1024]);
            InputStream old=c.openStream("/movie",0);old.read();c.cancelStream(old);old.close();
            try(InputStream next=c.openStream("/movie",10)){c.cancelStream(old);assertEquals(0,next.read());}
            assertEquals(1,server.commands.stream().filter(s->s.startsWith("USER")).count());
            assertEquals(1,c.list("/").size());
        }
    }
    @Test public void unsupportedRestFailsWithoutRetrievingFromBeginning()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.files.put("/movie",new byte[]{1,2});
            try{c.openStream("/movie",1);fail();}catch(RemoteSession.SeekNotSupportedException expected){}
            assertFalse(server.commands.stream().anyMatch(s->s.startsWith("RETR")));
        }
    }
    @Test public void inputFailureRemovesPartialAndPreservesTarget()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){
            InputStream broken=new InputStream(){int n;public int read()throws IOException{if(n++<10)return 1;throw new IOException("source failed");}};
            try{c.upload("/","target",broken,null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("已清理"));}
            assertTrue(server.files.isEmpty());assertTrue(server.commands.stream().anyMatch(s->s.startsWith("DELE /.cjfm-")));
        }
    }
    @Test public void renameFailureRemovesOnlyOwnTemporaryFile()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.failRename=true;server.files.put("/other",new byte[]{7});
            try{c.upload("/","target",new ByteArrayInputStream(new byte[]{1,2}),null);fail();}catch(IOException expected){}
            assertEquals(Collections.singleton("/other"),server.files.keySet());assertArrayEquals(new byte[]{7},server.files.get("/other"));
        }
    }
    @Test public void disconnectedUploadSchedulesCleanupWithoutReplayingUpload()throws Exception{
        try(FtpSession c=connect(Endpoint.Protocol.FTP)){server.disconnectUpload=true;
            try{c.upload("/","target",new ByteArrayInputStream(new byte[]{1,2,3}),null);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("已安排"));}
            long deadline=System.nanoTime()+5_000_000_000L;
            while(!server.files.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertTrue(server.files.isEmpty());assertEquals(1,server.commands.stream().filter(s->s.startsWith("STOR")).count());
            assertFalse(server.files.containsKey("/target"));
        }
    }
    @Test public void strictExplicitFtpsResumesControlSessionOnEveryDataPort()throws Exception{strictReuse(false);}
    @Test public void strictImplicitFtpsResumesControlSessionOnEveryDataPort()throws Exception{strictReuse(true);}
    @Test public void strictServerCanNegotiateTls13ThenRecoverReadOnlyUsingTls12()throws Exception{
        server=new Fixture(false);server.requireReuse=true;
        try(FtpSession c=new FtpSession(endpoint(Endpoint.Protocol.FTPS_EXPLICIT,"test",server.pin))){c.connect();roundTrip(c);assertTrue(server.resumedData.get()>2);assertEquals(2,server.commands.stream().filter(s->s.startsWith("USER")).count());}
    }
    private void strictReuse(boolean implicit)throws Exception{
        server=new Fixture(implicit);server.tls12=true;server.requireReuse=true;
        try(FtpSession c=new FtpSession(endpoint(implicit?Endpoint.Protocol.FTPS_IMPLICIT:Endpoint.Protocol.FTPS_EXPLICIT,"test",server.pin))){c.connect();roundTrip(c);assertTrue(server.resumedData.get()>2);assertEquals(server.tlsData.get(),server.resumedData.get());}
    }

    static final class Fixture implements AutoCloseable {
        final SSLContext tls;final ServerSocket listener;final ExecutorService workers=Executors.newCachedThreadPool();
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final List<String> commands=new CopyOnWriteArrayList<>();
        final Map<String,byte[]> files=new ConcurrentHashMap<>();final Set<String> directories=ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger tlsData=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger resumedData=new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch transferStarted=new CountDownLatch(1);final String pin;final boolean implicit;
        volatile boolean noEpsv,noMlsd,denyListing,rejectProt,badCompletion,stall,supportRest,supportSize,tls12,requireReuse,failRename,disconnectUpload,disconnectDelete;
        volatile String customListing;
        Fixture(boolean implicit)throws Exception{this(implicit,System.getProperty("test.keystore"));}
        Fixture(boolean implicit,String certificate)throws Exception{
            this.implicit=implicit;KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(certificate)){keys.load(in,"test-only".toCharArray());}
            pin=CertificateTrust.fingerprint((X509Certificate)keys.getCertificate("localhost"));KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"test-only".toCharArray());tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,null);
            listener=implicit?tls.getServerSocketFactory().createServerSocket(0,50,InetAddress.getByName("127.0.0.1")):new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));directories.add("/");
            workers.submit(()->{while(!listener.isClosed())try{Socket socket=listener.accept();sockets.add(socket);workers.submit(()->serve(socket));}catch(IOException ignored){}});
        }
        int port(){return listener.getLocalPort();}
        private void serve(Socket original){Socket control=original;ServerSocket passive=null;String cwd="/",rename=null,user="";boolean secure=implicit,protectedData=false;long restart=0;
            try{control.setSoTimeout(5000);if(control instanceof SSLSocket&&tls12)((SSLSocket)control).setEnabledProtocols(new String[]{"TLSv1.2"});BufferedReader in=reader(control);BufferedWriter out=writer(control);reply(out,"220 ready");String line;
                while((line=in.readLine())!=null){String[] parts=line.split(" ",2);String cmd=parts[0],arg=parts.length==2?parts[1]:"";commands.add(cmd.equals("PASS")?"PASS [redacted]":line);
                    switch(cmd){
                        case "AUTH":reply(out,"234 TLS");control=tls.getSocketFactory().createSocket(control,"localhost",control.getPort(),true);((SSLSocket)control).setUseClientMode(false);if(tls12)((SSLSocket)control).setEnabledProtocols(new String[]{"TLSv1.2"});((SSLSocket)control).startHandshake();in=reader(control);out=writer(control);secure=true;break;
                        case "USER":user=arg;reply(out,"331 password");break;
                        case "PASS":reply(out,((user.equals("test")&&arg.equals("test"))||user.equals("anonymous"))?"230 logged in":"530 invalid");break;
                        case "PBSZ":reply(out,secure?"200 PBSZ":"503 TLS required");break;
                        case "PROT":protectedData=secure&&arg.equals("P")&&!rejectProt;reply(out,protectedData?"200 protected":"534 encryption required");break;
                        case "TYPE":case "OPTS":case "NOOP":reply(out,"200 OK");break;
                        case "ABOR":reply(out,"225 abort complete");break;
                        case "SIZE":reply(out,supportSize&&files.containsKey(arg)?"213 "+files.get(arg).length:"502 unsupported");break;
                        case "REST":if(supportRest){restart=Long.parseLong(arg);reply(out,"350 restart accepted");}else reply(out,"502 unsupported");break;
                        case "FEAT":reply(out,"211-Features\r\n UTF8\r\n MLST type*;size*;modify*;\r\n211 End");break;
                        case "SYST":reply(out,"215 UNIX Type: L8");break;
                        case "PWD":reply(out,"257 \""+cwd+"\"");break;
                        case "CWD":if(directories.contains(arg)){cwd=arg;reply(out,"250 OK");}else reply(out,"550 not found");break;
                        case "EPSV":case "PASV":
                            if(cmd.equals("EPSV")&&noEpsv){reply(out,"502 no EPSV");break;}
                            if(passive!=null)passive.close();passive=protectedData?tls.getServerSocketFactory().createServerSocket(0,50,InetAddress.getByName("127.0.0.1")):new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));if(passive instanceof SSLServerSocket&&tls12)((SSLServerSocket)passive).setEnabledProtocols(new String[]{"TLSv1.2"});passive.setSoTimeout(5000);int p=passive.getLocalPort();reply(out,cmd.equals("EPSV")?"229 (|||"+p+"|)":"227 (203,0,113,50,"+(p/256)+","+(p%256)+")");break;
                        case "MLSD":case "LIST":case "STOR":case "RETR":
                            if(cmd.equals("MLSD")&&(noMlsd||denyListing)){reply(out,noMlsd?"502 unsupported":"550 denied");break;}
                            if(cmd.equals("RETR")&&!files.containsKey(arg)){reply(out,"550 missing");break;}
                            reply(out,"150 opening");try(Socket data=passive.accept()){sockets.add(data);data.setSoTimeout(5000);if(data instanceof SSLSocket){((SSLSocket)data).startHandshake();tlsData.incrementAndGet();boolean resumed=Arrays.equals(((SSLSocket)control).getSession().getId(),((SSLSocket)data).getSession().getId());if(resumed)resumedData.incrementAndGet();if(requireReuse&&!resumed){reply(out,"522 TLS session not resumed");continue;}}
                                if(cmd.equals("STOR")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(data.getInputStream(),bytes);files.put(arg,bytes.toByteArray());if(disconnectUpload){control.close();return;}}
                                else if(cmd.equals("RETR")){transferStarted.countDown();if(stall){while(!Thread.currentThread().isInterrupted())Thread.sleep(100);}else {byte[] content=files.get(arg);int offset=(int)Math.min(restart,content.length);data.getOutputStream().write(content,offset,content.length-offset);restart=0;}}
                                else data.getOutputStream().write((customListing==null?listing(cwd,cmd.equals("MLSD")):customListing).getBytes(StandardCharsets.UTF_8));
                            }catch(IOException interrupted){reply(out,"426 transfer interrupted");}finally{passive.close();passive=null;}reply(out,badCompletion?"451 failure":"226 complete");break;
                        case "MKD":directories.add(arg);reply(out,"257 created");break;
                        case "RNFR":rename=arg;reply(out,"350 rename");break;
                        case "RNTO":if(!failRename&&rename!=null&&files.containsKey(rename)){files.put(arg,files.remove(rename));reply(out,"250 renamed");}else reply(out,"550 missing");break;
                        case "DELE":boolean removed=files.remove(arg)!=null;if(disconnectDelete){disconnectDelete=false;control.close();return;}reply(out,removed?"250 deleted":"550 missing");break;
                        case "RMD":reply(out,directories.remove(arg)?"250 removed":"550 missing");break;
                        case "QUIT":reply(out,"221 bye");return;
                        default:reply(out,"502 unsupported");
                    }
                }
            }catch(Exception ignored){/* TLS rejection and cancellation intentionally close sockets. */}finally{try{control.close();original.close();if(passive!=null)passive.close();}catch(IOException ignored){}}
        }
        private String listing(String cwd,boolean mlsd){String prefix=cwd.endsWith("/")?cwd:cwd+"/";StringBuilder s=new StringBuilder();Set<String> all=new TreeSet<>(directories);all.addAll(files.keySet());for(String path:all){if(!path.startsWith(prefix)||path.equals(cwd))continue;String name=path.substring(prefix.length());if(name.isEmpty()||name.contains("/"))continue;boolean dir=directories.contains(path);long size=dir?0:files.get(path).length;if(mlsd)s.append("type=").append(dir?"dir":"file").append(";size=").append(size).append(";modify=20260101000000; ").append(name).append("\r\n");else s.append(dir?"drwxr-xr-x":"-rw-r--r--").append(" 1 user group ").append(size).append(" Jan 01 2026 ").append(name).append("\r\n");}return s.toString();}
        private static BufferedReader reader(Socket s)throws IOException{return new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));}
        private static BufferedWriter writer(Socket s)throws IOException{return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8));}
        private static void reply(BufferedWriter out,String line)throws IOException{out.write(line+"\r\n");out.flush();}
        private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        public void close()throws Exception{listener.close();for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);}
    }
}
