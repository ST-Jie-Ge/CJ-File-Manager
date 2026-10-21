package com.cj.music;

import android.content.Context;
import android.util.AtomicFile;
import org.libtorrent4j.*;
import org.libtorrent4j.alerts.*;
import org.libtorrent4j.swig.*;
import org.dkf.jed2k.TransferHandle;
import org.dkf.jed2k.TransferStatus;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.kad.*;
import org.dkf.jed2k.protocol.server.ServerMet;
import org.dkf.jed2k.kad.DhtTracker;
import org.dkf.jed2k.util.FUtils;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** All task and handle mutations are confined to the service's worker. */
final class LinkDownloadEngine implements AutoCloseable {
    private final Context context;
    private SessionManager torrent;
    private org.dkf.jed2k.Session ed2k;
    private DhtTracker kad;
    private final Map<String,TorrentHandle> torrents=new HashMap<>();
    private final Map<String,TransferHandle> transfers=new HashMap<>();
    private final Map<String,DownloadFileHandler> files=new HashMap<>();
    private static final class Resume {final String path;final byte[] bytes;Resume(String path,byte[] bytes){this.path=path;this.bytes=bytes;}}
    private final Queue<Resume> resumeAlerts=new ConcurrentLinkedQueue<>();
    private final ExecutorService bootstrap=Executors.newSingleThreadExecutor();
    private volatile List<ServerMet.ServerMetEntry> servers=Collections.emptyList();
    private volatile String bootstrapError="",threadError="";
    private volatile boolean closed;
    private final boolean discovery;
    private int serverIndex;private long lastServer=-45000,lastResume;
    LinkDownloadEngine(Context c){this(c,true);}
    LinkDownloadEngine(Context c,boolean discovery){context=c.getApplicationContext();this.discovery=discovery;}
    private File resume(String id){return new File(context.getFilesDir(),"download-"+id+".resume");}
    /* 代理与 tracker 偏好统一从这里读：代理只作用于磁力／BT（jed2k 引擎不支持代理，连接全部直连）。 */
    private static String[] readProxy(Context c){android.content.SharedPreferences p=c.getSharedPreferences("downloads",0);
        if(!p.getBoolean("proxy_enabled",false))return null;
        String host=p.getString("proxy_host","127.0.0.1");if(host.isEmpty())host="127.0.0.1";
        return new String[]{host,String.valueOf(p.getInt("proxy_port",1080))};}
    static List<String> readTrackers(Context c){List<String> out=new ArrayList<>();for(String line:c.getSharedPreferences("downloads",0).getString("trackers","").split("\\n")){line=line.trim();if(line.startsWith("http"))out.add(line);}return out;}
    /** 把当前代理偏好写进一个 SettingsPack（只设置显式项，apply 时不会覆盖其他设置）。 */
    private static SettingsPack proxyPack(Context c,String[] proxy){SettingsPack s=new SettingsPack();
        if(proxy==null){s.setInteger(settings_pack.int_types.proxy_type.swigValue(),settings_pack.proxy_type_t.none.swigValue());s.setInteger(settings_pack.int_types.proxy_port.swigValue(),0);s.setString(settings_pack.string_types.proxy_hostname.swigValue(),"");return s;}
        s.setInteger(settings_pack.int_types.proxy_type.swigValue(),settings_pack.proxy_type_t.socks5.swigValue());
        s.setInteger(settings_pack.int_types.proxy_port.swigValue(),Integer.parseInt(proxy[1]));
        s.setString(settings_pack.string_types.proxy_hostname.swigValue(),proxy[0]);
        /* tracker 域名解析、tracker 连接、peer 连接全走代理——本地代理（127.0.0.1）场景下这才彻底。 */
        s.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(),true);
        s.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(),true);
        s.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(),true);
        return s;}
    private void torrent() {
        if(torrent!=null)return;
        SessionManager s=new SessionManager(false);
        s.addListener(new AlertListener(){public int[] types(){return new int[]{AlertType.SAVE_RESUME_DATA.swig()};}public void alert(Alert<?> a){if(a instanceof SaveResumeDataAlert)try{SaveResumeDataAlert saved=(SaveResumeDataAlert)a;resumeAlerts.add(new Resume(saved.handle().savePath(),AddTorrentParams.writeResumeDataBuf(saved.params())));}catch(Exception ignored){}}});
        SettingsPack settings=new SettingsPack().listenInterfaces("0.0.0.0:0,[::]:0");
        settings.setInteger(settings_pack.int_types.connections_limit.swigValue(),80);
        settings.setInteger(settings_pack.int_types.active_downloads.swigValue(),4);
        settings.setInteger(settings_pack.int_types.active_seeds.swigValue(),0);
        settings.setInteger(settings_pack.int_types.max_metadata_size.swigValue(),2*1024*1024);
        settings.stopTrackerTimeout(1);
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(),false);
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(),false);
        settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(),discovery);
        settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(),discovery);
        /* 代理：编辑页保存后即时生效（新会话在启动时带上，运行中的会话由 applyPrefs 动态应用）。 */
        String[] proxy=readProxy(context);if(proxy!=null){
            settings.setInteger(settings_pack.int_types.proxy_type.swigValue(),settings_pack.proxy_type_t.socks5.swigValue());
            settings.setInteger(settings_pack.int_types.proxy_port.swigValue(),Integer.parseInt(proxy[1]));
            settings.setString(settings_pack.string_types.proxy_hostname.swigValue(),proxy[0]);
            settings.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(),true);
            settings.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(),true);
            settings.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(),true);}
        s.start(new SessionParams(settings));torrent=s;
    }
    /** 编辑页保存设置后调用：对运行中的磁力会话动态应用代理，并给已有任务补挂新 tracker（去重）。 */
    void applyPrefs(){
        if(torrent==null||closed)return;
        torrent.applySettings(proxyPack(context,readProxy(context)));
        List<String> trackers=readTrackers(context);
        for(TorrentHandle h:torrents.values())try{
            if(!h.isValid())continue;
            Set<String> known=new HashSet<>();for(AnnounceEntry e:h.trackers())known.add(e.url());
            for(String url:trackers)if(!known.contains(url))h.addTracker(new AnnounceEntry(url));
        }catch(Exception|LinkageError ignored){}
    }
    private void ed2k() throws Exception {
        if(ed2k!=null&&ed2k.isAlive())return;
        if(ed2k!=null)throw new IOException(threadError.isEmpty()?"ed2k 引擎已停止，请暂停后重试":threadError);
        org.dkf.jed2k.Settings settings=new org.dkf.jed2k.Settings();
        settings.clientName="FileManager";settings.modName="FileManager";settings.bufferPoolSize=32;settings.sessionConnectionsLimit=24;settings.serverPingTimeout=60;
        try(ServerSocket tcp=new ServerSocket(0);DatagramSocket udp=new DatagramSocket(0)){settings.listenPort=tcp.getLocalPort();settings.udpPort=udp.getLocalPort();}
        String agent=context.getSharedPreferences("downloads",0).getString("client_hash","");
        if(agent.isEmpty()){byte[] bytes=new byte[16];new java.security.SecureRandom().nextBytes(bytes);bytes[5]=14;bytes[14]=111;agent=Hash.fromBytes(bytes).toString();context.getSharedPreferences("downloads",0).edit().putString("client_hash",agent).apply();}
        settings.userAgent=Hash.fromString(agent);
        ed2k=new org.dkf.jed2k.Session(settings);ed2k.setName("ed2k-download");
        ed2k.setUncaughtExceptionHandler((t,e)->threadError="ed2k 连接异常，请暂停后重试");
        kad=new DhtTracker(settings.udpPort,new KadId(settings.userAgent),new InetSocketAddress("0.0.0.0",settings.listenPort));
        kad.setUncaughtExceptionHandler((t,e)->bootstrapError="Kad 连接异常，仍可通过服务器寻找资源");
        ed2k.setDhtTracker(kad);ed2k.start();kad.start();
        if(discovery)bootstrap.execute(()->{
            try{loadBootstrap(false);}catch(Exception e){bootstrapError="服务器列表读取失败，可在下载设置中指定 ed2k 服务器";}
            try{loadBootstrap(true);}catch(Exception ignored){}
        });
    }
    private byte[] fetch(String url,String asset,boolean online)throws Exception{
        if(!online)try(InputStream in=context.getAssets().open(asset)){return bounded(in);}
        HttpsURLConnectionGuard guard=new HttpsURLConnectionGuard(url);
        try(InputStream in=guard.connection.getInputStream()){return bounded(in);}finally{guard.connection.disconnect();}
    }
    private static final class HttpsURLConnectionGuard{
        final javax.net.ssl.HttpsURLConnection connection;
        HttpsURLConnectionGuard(String url)throws Exception{connection=(javax.net.ssl.HttpsURLConnection)new URL(url).openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);}
    }
    private static byte[] bounded(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException("列表过大");out.write(b,0,n);}return out.toByteArray();}
    private void loadBootstrap(boolean online)throws Exception{
        Exception failure=null;
        try{byte[] data=fetch("https://upd.emule-security.org/server.met","ed2k-server.met",online);ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);if(b.remaining()<5||Integer.toUnsignedLong(b.getInt(1))>1000)throw new IOException();ServerMet met=new ServerMet();met.get(b);if(!closed)servers=new ArrayList<>(met.getServers().getList());}catch(Exception e){failure=e;}
        try{byte[] data=fetch("https://upd.emule-security.org/nodes.dat","ed2k-nodes.dat",online);ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);if(b.remaining()<4)throw new IOException();KadNodesDat nodes=new KadNodesDat();nodes.get(b);if(!closed&&kad!=null){kad.addKadEntries(nodes.getContacts());kad.addKadEntries(nodes.getBootstrapEntries().getList());}}catch(Exception e){if(failure!=null)throw failure;}
    }
    void start(LinkDownloadStore.Task t)throws Exception{
        DownloadLink link=DownloadLink.parse(t.link);File directory=new File(t.directory);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建下载目录");
        if(link.magnet){
            torrent();TorrentHandle h=torrents.get(t.id);
            if(h==null){
                AddTorrentParams p=AddTorrentParams.parseMagnetUri(link.uri);File r=resume(t.id);
                if(r.isFile()&&r.length()<=8*1024*1024)try{error_code ec=new error_code();add_torrent_params raw=libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(Files.readAllBytes(r.toPath())),ec);if(ec.value()==0&&new AddTorrentParams(raw).getInfoHashes().getBest().equals(p.getInfoHashes().getBest()))p=new AddTorrentParams(raw);}catch(Exception ignored){}
                /* 自定义 tracker：追加到磁力链接自带的 tr 之后（resume 恢复的 tracker 已包含在内，同样先合并再判断）。 */
                List<String> custom=readTrackers(context);
                if(!custom.isEmpty())try{List<String> merged=new ArrayList<>(p.getTrackers());Set<String> known=new HashSet<>(merged);for(String url:custom)if(!known.contains(url))merged.add(url);p.setTrackers(merged);}catch(Exception|LinkageError ignored){}
                p.setSavePath(directory.getCanonicalPath());p.setFlags(p.getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv()));
                error_code ec=new error_code();h=new TorrentHandle(torrent.swig().add_torrent(p.swig(),ec));if(ec.value()!=0||!h.isValid())throw new IOException("磁力任务创建失败："+ec.message());torrents.put(t.id,h);
            }
            h.resume();
        }else{
            ed2k();TransferHandle h=transfers.get(t.id);
            if(h==null){File output=new File(directory,link.name);DownloadFileHandler handler=new DownloadFileHandler(output);org.dkf.jed2k.AddTransferParams p=new org.dkf.jed2k.AddTransferParams(Hash.fromString(link.key),System.currentTimeMillis(),link.size,handler,false);
                File r=resume(t.id);if(output.isFile()&&r.isFile()&&r.length()<=8*1024*1024)try{org.dkf.jed2k.AddTransferParams saved=new org.dkf.jed2k.AddTransferParams();FUtils.read(saved,r);if(saved.getHash().equals(p.getHash())&&saved.getSize().equals(p.getSize())&&validPieces(output,saved))p.resumeData=saved.resumeData;}catch(Exception ignored){}
                h=ed2k.addTransfer(p);if(!h.isValid())throw new IOException("ed2k 任务创建失败");transfers.put(t.id,h);files.put(t.id,handler);
            }h.resume();
        }t.state="寻找资源";t.error="";
    }
    void pause(LinkDownloadStore.Task t)throws Exception{
        TorrentHandle h=torrents.get(t.id);if(h!=null){h.pause();h.saveResumeData();}
        TransferHandle e=transfers.get(t.id);if(e!=null){e.pause();saveEd2k(t.id,e);}
        t.state="暂停";t.rate=0;
    }
    private void saveEd2k(String id,TransferHandle h)throws Exception{
        if(!h.isValid())return;org.dkf.jed2k.AddTransferParams p=new org.dkf.jed2k.AddTransferParams(h.getHash(),h.getCreateTime(),h.getSize(),h.getFile(),true);p.resumeData.setData(h.getResumeData());
        ByteBuffer b=ByteBuffer.allocate(p.bytesCount()).order(ByteOrder.LITTLE_ENDIAN);p.put(b);atomic(resume(id),b.array());
    }
    private boolean validPieces(File file,org.dkf.jed2k.AddTransferParams p)throws Exception{
        if(!p.resumeData.haveData())return false;org.dkf.jed2k.protocol.TransferResumeData data=p.resumeData.getData();
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){byte[] buffer=new byte[64*1024];for(int i=0;i<data.pieces.size();i++)if(data.pieces.getBit(i)){
            if(i>=data.hashes.size())return false;long offset=(long)i*org.dkf.jed2k.Constants.PIECE_SIZE,length=Math.min(org.dkf.jed2k.Constants.PIECE_SIZE,p.getSize().longValue()-offset);if(length<=0||offset+length>in.length())return false;in.seek(offset);org.dkf.jed2k.hash.MD4 digest=new org.dkf.jed2k.hash.MD4();while(length>0){int n=in.read(buffer,0,(int)Math.min(length,buffer.length));if(n<=0)return false;digest.update(buffer,0,n);length-=n;}if(!Hash.fromBytes(digest.digest()).equals(data.hashes.get(i)))return false;
        }}return true;
    }
    private static void atomic(File f,byte[] data)throws Exception{AtomicFile a=new AtomicFile(f);FileOutputStream out=null;try{out=a.startWrite();out.write(data);a.finishWrite(out);}catch(Exception e){if(out!=null)a.failWrite(out);throw e;}}
    void remove(LinkDownloadStore.Task t)throws Exception{pause(t);TorrentHandle h=torrents.remove(t.id);if(h!=null)torrent.remove(h);TransferHandle e=transfers.remove(t.id);if(e!=null)ed2k.removeTransfer(e.getHash(),false);files.remove(t.id);new AtomicFile(resume(t.id)).delete();}
    void poll(List<LinkDownloadStore.Task> tasks){
        long now=android.os.SystemClock.elapsedRealtime();boolean save=now-lastResume>15000;if(save)lastResume=now;
        if(ed2k!=null&&now-lastServer>45000){lastServer=now;String override=context.getSharedPreferences("downloads",0).getString("ed2k_server","");String connected=ed2k.getConnectedServerId();if(connected==null||connected.isEmpty())try{
            /* 自定义服务器配置坏掉（旧版本写入 / 被外部改动 / 手滑）时不能把内置列表也一起跳过：
               原来 substring(0,-1) 或 parseInt 一抛异常，这一轮既连不上自定义也不连内置，
               之后每 45 秒重复一次，ed2k 永远找不到服务器。校验不过就当没填，回落内置列表。 */
            boolean custom=false;
            if(!override.isEmpty()){int colon=override.lastIndexOf(':');if(colon>=1)try{int port=Integer.parseInt(override.substring(colon+1));if(port>=1&&port<=65535&&!override.substring(0,colon).contains("/")){ed2k.connectoTo("自定义服务器",override.substring(0,colon),port);custom=true;}}catch(NumberFormatException ignored){}}
            if(!custom&&!servers.isEmpty()){ServerMet.ServerMetEntry s=servers.get(serverIndex++%servers.size());ed2k.connectoTo(s.getName(),s.getHost(),s.getPort());}
        }catch(Exception ignored){}}
        Resume a;while((a=resumeAlerts.poll())!=null)try{for(LinkDownloadStore.Task t:tasks)if(new File(t.directory).getCanonicalPath().equals(a.path))atomic(resume(t.id),a.bytes);}catch(Exception ignored){}
        if(ed2k!=null){org.dkf.jed2k.alert.Alert alert;while((alert=ed2k.popAlert())!=null)if(alert instanceof org.dkf.jed2k.alert.TransferDiskIOErrorAlert){org.dkf.jed2k.alert.TransferDiskIOErrorAlert disk=(org.dkf.jed2k.alert.TransferDiskIOErrorAlert)alert;for(LinkDownloadStore.Task t:tasks){TransferHandle handle=transfers.get(t.id);if(handle!=null&&handle.getHash().equals(disk.hash)){try{pause(t);}catch(Exception ignored){}t.state="失败";t.error="下载文件写入失败，请检查可用空间和目录权限";}}}}
        for(LinkDownloadStore.Task t:tasks){if(!t.active())continue;try{
            TorrentHandle h=torrents.get(t.id);TransferHandle e=transfers.get(t.id);
            if(h!=null){TorrentStatus s=h.status();if(s.errorCode().getValue()!=0)throw new IOException(s.errorCode().getMessage());t.done=s.totalWantedDone();t.total=s.totalWanted();t.rate=s.downloadPayloadRate();t.peers=s.numPeers();if(s.hasMetadata())t.name=DownloadLink.safeName(s.name());t.state=s.hasMetadata()&&s.numPeers()>0?"下载中":"寻找资源";if(save)h.saveResumeData();if(s.isFinished()){h.pause();h.saveResumeData();t.state="完成";t.rate=0;}}
            if(e!=null){if(!ed2k.isAlive())throw new IOException(threadError.isEmpty()?"ed2k 引擎已停止":threadError);TransferStatus s=e.getStatus();t.done=s.totalDone;t.total=e.getSize();t.rate=s.downloadPayloadRate;t.peers=s.numPeers;t.state=t.peers>0?"下载中":"寻找资源";t.error=bootstrapError;if(save)saveEd2k(t.id,e);if(e.isFinished()){e.pause();saveEd2k(t.id,e);t.state="完成";t.rate=0;}}
        }catch(Exception|LinkageError ex){try{pause(t);}catch(Exception ignored){}t.state="失败";t.error=ex.getMessage()==null?"下载失败，可重试":ex.getMessage();t.rate=0;}}
    }
    @Override public void close(){closed=true;bootstrap.shutdownNow();if(torrent!=null){
        Map<String,String> paths=new HashMap<>();for(Map.Entry<String,TorrentHandle> e:torrents.entrySet())try{paths.put(e.getKey(),e.getValue().savePath());e.getValue().saveResumeData();}catch(Exception ignored){}
        long until=android.os.SystemClock.elapsedRealtime()+1000;Set<String> saved=new HashSet<>();while(saved.size()<paths.size()&&android.os.SystemClock.elapsedRealtime()<until){Resume a;while((a=resumeAlerts.poll())!=null)for(Map.Entry<String,String> e:paths.entrySet())if(e.getValue().equals(a.path))try{atomic(resume(e.getKey()),a.bytes);saved.add(e.getKey());}catch(Exception ignored){}try{Thread.sleep(30);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        torrent.stop();torrent=null;
    }if(kad!=null){kad.abort();try{kad.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(ed2k!=null){ed2k.abort();try{ed2k.join(6000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}for(DownloadFileHandler f:files.values())f.close();files.clear();}
}
