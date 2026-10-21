package com.cj.music;

import android.content.*;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import org.libtorrent4j.*;
import org.libtorrent4j.swig.*;
import org.dkf.jed2k.protocol.*;
import org.dkf.jed2k.protocol.client.*;
import org.dkf.jed2k.protocol.server.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class LinkDownloadTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private File folder(){File f=new File(context.getCacheDir(),"download-test-"+UUID.randomUUID());assertTrue(f.mkdirs());return f;}
    @Test public void malformedLinksAndHostileNamesAreRejectedOrContained()throws Exception{
        for(String s:new String[]{"magnet:?dn=x","magnet:?xt=urn:btih:xyz","ed2k://|file|x|-1|00000000000000000000000000000000|/","ed2k://|server|host|4661|/","file:///etc/passwd"})try{DownloadLink.parse(s);fail(s);}catch(Exception expected){}
        DownloadLink link=DownloadLink.parse("ed2k://|file|..%2F..%2Fa%2Bb.wav|42|1234567890abcdef1234567890abcdef|/");assertFalse(link.name.contains("/"));assertTrue(link.name.contains("a+b"));
        ByteBuffer header=ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put((byte)0xe3).putInt(Integer.MAX_VALUE).put((byte)1);header.flip();try{new PacketHeader().get(header);fail("Unbounded packet allocation");}catch(org.dkf.jed2k.exception.JED2KException expected){}
        assertEquals(60000,org.dkf.jed2k.protocol.Endpoint.fromInet(new InetSocketAddress("127.0.0.1",60000)).getPort());
        assertEquals(DownloadLink.parse("magnet:?xt=urn:btih:0000000000000000000000000000000000000000").key,DownloadLink.parse("magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").key);
    }
    private static void bstring(ByteArrayOutputStream out,byte[] b)throws Exception{out.write((b.length+":").getBytes("US-ASCII"));out.write(b);}
    private static void ascii(ByteArrayOutputStream out,String s)throws Exception{out.write(s.getBytes("US-ASCII"));}
    @Test public void magnetActuallyFetchesMetadataAndVerifiedFileFromLoopbackPeer()throws Exception{
        File root=folder(),seedDir=new File(root,"seed");assertTrue(seedDir.mkdir());byte[] data=new byte[1024*1024];new Random(42).nextBytes(data);Files.write(new File(seedDir,"sample.bin").toPath(),data);
        ByteArrayOutputStream hashes=new ByteArrayOutputStream();for(int i=0;i<data.length;i+=16384)hashes.write(MessageDigest.getInstance("SHA-1").digest(Arrays.copyOfRange(data,i,Math.min(data.length,i+16384))));
        ByteArrayOutputStream info=new ByteArrayOutputStream();ascii(info,"d6:lengthi"+data.length+"e4:name10:sample.bin12:piece lengthi16384e6:pieces");bstring(info,hashes.toByteArray());ascii(info,"e");ByteArrayOutputStream metainfo=new ByteArrayOutputStream();ascii(metainfo,"d4:info");metainfo.write(info.toByteArray());ascii(metainfo,"e");TorrentInfo ti=new TorrentInfo(metainfo.toByteArray());
        int seedPort;try(ServerSocket socket=new ServerSocket(0)){seedPort=socket.getLocalPort();}SessionManager seed=new SessionManager(false);SettingsPack settings=new SettingsPack().listenInterfaces("127.0.0.1:"+seedPort);settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(),false);settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(),false);settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(),false);settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(),false);seed.start(new SessionParams(settings));
        LinkDownloadEngine engine=new LinkDownloadEngine(context,false);
        try{
            org.libtorrent4j.AddTorrentParams params=new org.libtorrent4j.AddTorrentParams();params.setTorrentInfo(ti);params.setSavePath(seedDir.getPath());params.setFlags(params.getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv()));error_code ec=new error_code();TorrentHandle h=new TorrentHandle(seed.swig().add_torrent(params.swig(),ec));assertEquals(0,ec.value());
            long end=SystemClock.elapsedRealtime()+15000;while(!h.status().isSeeding()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertTrue(h.status().isSeeding());
            String endpoint="127.0.0.1:"+seedPort;DownloadLink link=DownloadLink.parse("magnet:?xt=urn:btih:"+ti.infoHash()+"&dn=sample.bin&x.pe="+endpoint);LinkDownloadStore.Task t=new LinkDownloadStore.Task(link,root);engine.start(t);end=SystemClock.elapsedRealtime()+60000;while(!t.state.equals("完成")&&SystemClock.elapsedRealtime()<end){engine.poll(Collections.singletonList(t));if(t.state.equals("失败"))fail(t.error);SystemClock.sleep(150);}
            assertEquals("done="+t.done+", peers="+t.peers+", error="+t.error,"完成",t.state);assertArrayEquals(data,Files.readAllBytes(new File(t.directory,"sample.bin").toPath()));
            engine.pause(t);engine.poll(Collections.singletonList(t));assertTrue(new File(t.directory,"sample.bin").exists());
        }finally{engine.close();seed.stop();}
    }
    @Test public void ed2kDownloadsOverTcpAndResumesWithoutTruncating()throws Exception{
        byte[] data=new byte[1024*1024];new Random(73).nextBytes(data);Hash hash=Hash.fromBytes(new org.dkf.jed2k.hash.MD4().digest(data));File root=folder();SharedPreferences prefs=context.getSharedPreferences("downloads",0);String old=prefs.getString("ed2k_server","");
        try(EdFixture fixture=new EdFixture(data,hash)){
            prefs.edit().putString("ed2k_server","127.0.0.1:"+fixture.server.getLocalPort()).commit();DownloadLink link=DownloadLink.parse("ed2k://|file|sample.bin|"+data.length+"|"+hash+"|/");LinkDownloadStore.Task t=new LinkDownloadStore.Task(link,root);
            LinkDownloadEngine engine=new LinkDownloadEngine(context,false);
            try{engine.start(t);long end=SystemClock.elapsedRealtime()+20000;File partial=new File(t.directory,"sample.bin");while((!partial.isFile()||partial.length()<184320)&&SystemClock.elapsedRealtime()<end){engine.poll(Collections.singletonList(t));SystemClock.sleep(60);}assertTrue("ED2K must receive actual data: "+fixture.trace+", failure="+fixture.failure+", state="+t.state+", error="+t.error,partial.length()>=184320);engine.pause(t);assertEquals("暂停",t.state);long length=partial.length();engine.close();SystemClock.sleep(300);assertEquals(length,partial.length());
                engine=new LinkDownloadEngine(context,false);engine.start(t);end=SystemClock.elapsedRealtime()+30000;while(!t.state.equals("完成")&&SystemClock.elapsedRealtime()<end){engine.poll(Collections.singletonList(t));if(t.state.equals("失败"))fail(t.error);SystemClock.sleep(120);}assertEquals("done="+t.done+", peers="+t.peers+", fixture="+fixture.failure,"完成",t.state);assertArrayEquals(data,Files.readAllBytes(partial.toPath()));
            }finally{engine.close();}
        }finally{prefs.edit().putString("ed2k_server",old).commit();}
    }
    @Test public void backgroundServicePersistsPauseAndFailureWithoutCrash()throws Exception{
        /* 下载 UI 已并入侧边栏 DownloadSidebarPage（LinkDownloadActivity 不存在），
           这里只需要一个前台 Activity 作为命令的 Context。 */
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));File root=folder();LinkDownloadStore store=new LinkDownloadStore(context);List<LinkDownloadStore.Task> old=store.read();store.write(Collections.emptyList());LinkDownloadService.snapshot="";
        try{instrumentation.runOnMainSync(()->LinkDownloadService.command(activity,"add","","magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Waiting",root.getPath()));long end=SystemClock.elapsedRealtime()+15000;while(!LinkDownloadService.snapshot.contains("Waiting")&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertTrue(LinkDownloadService.snapshot.contains("Waiting"));String id=store.read().get(0).id;
            instrumentation.runOnMainSync(activity::finish);SystemClock.sleep(1200);assertTrue(LinkDownloadService.snapshot.contains("寻找资源"));LinkDownloadService.command(context,"pause",id,null,null);end=SystemClock.elapsedRealtime()+10000;while(!LinkDownloadService.snapshot.contains("暂停")&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertEquals("暂停",store.read().get(0).state);assertTrue(new File(root,store.read().get(0).name+"-"+id.substring(0,8)).isDirectory());
            File invalid=new File(root,"occupied");Files.write(invalid.toPath(),new byte[]{1});LinkDownloadService.command(context,"add","","ed2k://|file|bad.bin|42|1234567890abcdef1234567890abcdef|/",invalid.getPath());end=SystemClock.elapsedRealtime()+10000;while(LinkDownloadService.notice.isEmpty()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertFalse(LinkDownloadService.notice.isEmpty());assertEquals(1,store.read().size());
        }finally{context.stopService(new Intent(context,LinkDownloadService.class));SystemClock.sleep(1800);store.write(old);LinkDownloadService.snapshot="";LinkDownloadService.notice="";}
    }
    @Test public void ed2kExactPieceBoundaryAcceptsTerminalEmptyHash()throws Exception{
        byte[] data=new byte[(int)org.dkf.jed2k.Constants.PIECE_SIZE];new Random(81).nextBytes(data);Hash piece=Hash.fromBytes(new org.dkf.jed2k.hash.MD4().digest(data));Hash hash=Hash.fromHashSet(Arrays.asList(piece,Hash.TERMINAL));SharedPreferences prefs=context.getSharedPreferences("downloads",0);String old=prefs.getString("ed2k_server","");
        try(EdFixture fixture=new EdFixture(data,hash)){prefs.edit().putString("ed2k_server","127.0.0.1:"+fixture.server.getLocalPort()).commit();LinkDownloadStore.Task t=new LinkDownloadStore.Task(DownloadLink.parse("ed2k://|file|boundary.bin|"+data.length+"|"+hash+"|/"),folder());LinkDownloadEngine engine=new LinkDownloadEngine(context,false);
            try{engine.start(t);long end=SystemClock.elapsedRealtime()+40000;while(!t.state.equals("完成")&&SystemClock.elapsedRealtime()<end){engine.poll(Collections.singletonList(t));if(t.state.equals("失败"))fail(t.error);SystemClock.sleep(120);}assertEquals(fixture.trace+" / "+fixture.failure,"完成",t.state);assertEquals(data.length,t.done);assertArrayEquals(data,Files.readAllBytes(new File(t.directory,"boundary.bin").toPath()));}finally{engine.close();}
        }finally{prefs.edit().putString("ed2k_server",old).commit();}
    }
    @Test public void bootstrapListsAndSidebarEntryAndDownloadCardsAreUsable()throws Exception{
        try(InputStream in=context.getAssets().open("ed2k-server.met")){ServerMet met=new ServerMet();met.get(ByteBuffer.wrap(readAll(in)).order(ByteOrder.LITTLE_ENDIAN));assertTrue(met.getServers().size()>0);}
        try(InputStream in=context.getAssets().open("ed2k-nodes.dat")){org.dkf.jed2k.protocol.kad.KadNodesDat nodes=new org.dkf.jed2k.protocol.kad.KadNodesDat();nodes.get(ByteBuffer.wrap(readAll(in)).order(ByteOrder.LITTLE_ENDIAN));assertTrue(nodes.getContacts().size()+nodes.getBootstrapEntries().size()>0);}
        File root=folder();LinkDownloadStore store=new LinkDownloadStore(context);List<LinkDownloadStore.Task> old=store.read();LinkDownloadStore.Task bt=new LinkDownloadStore.Task(DownloadLink.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Linux.iso"),root);bt.state="下载中";bt.total=1200000000;bt.done=450000000;bt.rate=1250000;bt.peers=12;LinkDownloadStore.Task ed=new LinkDownloadStore.Task(DownloadLink.parse("ed2k://|file|Concert.mp4|2400000000|1234567890abcdef1234567890abcdef|/"),root);ed.state="暂停";ed.done=640000000;store.write(Arrays.asList(bt,ed));org.json.JSONArray a=new org.json.JSONArray();a.put(bt.json()).put(ed.json());LinkDownloadService.snapshot=a.toString();
        /* open_downloads extra 让 MainActivity 起来后直接展开「磁力／ed2k 下载」侧边栏页，
           替代已不存在的 LinkDownloadActivity。 */
        MainActivity browser=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open_downloads",true));
        try{instrumentation.runOnMainSync(()->{com.google.android.material.navigation.NavigationView nav=browser.findViewById(com.cj.music.resources.R.id.client_sidebar);assertNotNull(nav.getMenu().findItem(com.cj.music.resources.R.id.client_action_downloads));});instrumentation.waitForIdleSync();SystemClock.sleep(1200);android.graphics.Bitmap picture=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(picture);try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"link-downloads.png"))){picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}picture.recycle();
        }finally{instrumentation.runOnMainSync(browser::finish);store.write(old);LinkDownloadService.snapshot="";}
    }
    private static byte[] readAll(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
    static final class EdFixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0),peer=new ServerSocket(0);final ExecutorService io=Executors.newCachedThreadPool();final byte[] data;final Hash hash;volatile boolean closed;volatile String failure="";final List<String> trace=new CopyOnWriteArrayList<>();final List<Socket> sockets=new CopyOnWriteArrayList<>();
        EdFixture(byte[] bytes,Hash h)throws Exception{data=bytes;hash=h;io.execute(()->accept(server,true));io.execute(()->accept(peer,false));}
        private void accept(ServerSocket listener,boolean isServer){while(!closed)try{Socket socket=listener.accept();socket.setSoTimeout(20000);sockets.add(socket);io.execute(()->serve(socket,isServer));}catch(Exception e){if(!closed)failure=e.toString();}}
        private void serve(Socket s,boolean isServer){try(Socket socket=s){DataInputStream in=new DataInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();while(!closed){byte[] head=new byte[6];in.readFully(head);ByteBuffer b=ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);PacketHeader header=new PacketHeader();header.get(b);byte[] payload=new byte[header.sizePacket()];in.readFully(payload);int type=header.key().packet&255;trace.add((isServer?"server:":"peer:")+type);
            if(isServer){org.dkf.jed2k.protocol.server.PacketCombiner combiner=new org.dkf.jed2k.protocol.server.PacketCombiner();if(type==1){IdChange id=new IdChange();id.clientId=0x0200007f;send(out,combiner,id);}else if(type==0x19){FoundFileSources found=new FoundFileSources();found.hash.assign(hash);found.sources.add(org.dkf.jed2k.protocol.Endpoint.fromString("127.0.0.1",peer.getLocalPort()));send(out,combiner,found);}continue;}
            org.dkf.jed2k.protocol.client.PacketCombiner combiner=new org.dkf.jed2k.protocol.client.PacketCombiner();org.dkf.jed2k.protocol.Serializable p=type==0x58?new FileRequest(hash):type==0x54?new StartUpload(hash):type==0x51?new HashSetRequest(hash):combiner.unpack(header,ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN));
            if(p instanceof Hello){HelloAnswer hello=new HelloAnswer();hello.hash.assign(Hash.EMULE);hello.point.assign(org.dkf.jed2k.protocol.Endpoint.fromString("127.0.0.1",peer.getLocalPort()));send(out,combiner,hello);}
            else if(p instanceof FileRequest){FileAnswer answer=new FileAnswer();answer.hash.assign(hash);answer.name.assignString("sample.bin");send(out,combiner,answer);}
            else if(p instanceof FileStatusRequest){FileStatusAnswer answer=new FileStatusAnswer();answer.hash.assign(hash);answer.bitfield.resize(1,true);send(out,combiner,answer);}
            else if(p instanceof StartUpload){send(out,combiner,new AcceptUpload());}
            else if(p instanceof HashSetRequest){Hash piece=Hash.fromBytes(new org.dkf.jed2k.hash.MD4().digest(data));ByteBuffer payloadHashes=ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN);hash.put(payloadHashes);payloadHashes.putShort((short)2);piece.put(payloadHashes);Hash.TERMINAL.put(payloadHashes);payloadHashes.flip();HashSetAnswer answer=new HashSetAnswer();answer.get(payloadHashes);send(out,combiner,answer);}
            else if(p instanceof RequestParts){RequestParts request=(RequestParts)p;for(int i=0;i<3;i++){int start=request.getBeginOffset(i).intValue(),end=request.getEndOffset(i).intValue();if(end<=start)continue;SystemClock.sleep(200);ByteBuffer frame=ByteBuffer.allocate(6+24+end-start).order(ByteOrder.LITTLE_ENDIAN);frame.put((byte)0xe3).putInt(25+end-start).put((byte)0x46);hash.put(frame);frame.putInt(start).putInt(end).put(data,start,end-start);out.write(frame.array());out.flush();}}
        }}catch(Exception e){if(!closed&&!(e instanceof EOFException)&&!(e instanceof SocketException))failure=e.toString();}}
        private static void send(OutputStream out,org.dkf.jed2k.protocol.PacketCombiner combiner,org.dkf.jed2k.protocol.Serializable packet)throws Exception{ByteBuffer b=ByteBuffer.allocate(packet.bytesCount()+64).order(ByteOrder.LITTLE_ENDIAN);assertTrue(combiner.pack(packet,b));out.write(b.array(),0,b.position());out.flush();}
        public void close()throws Exception{closed=true;server.close();peer.close();for(Socket s:sockets)s.close();io.shutdownNow();}
    }
}
