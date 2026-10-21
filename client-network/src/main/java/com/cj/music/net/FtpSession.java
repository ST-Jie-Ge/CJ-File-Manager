package com.cj.music.net;

import org.apache.commons.net.ftp.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.bouncycastle.jsse.BCSSLSocket;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** One caller at a time; close() may be called concurrently to cancel sockets. */
public final class FtpSession implements RemoteSession {
    /** 兼容旧调用点（FtpDataSource 等）；语义等同 {@link RemoteSession.SeekNotSupportedException}。 */
    public static final class SeekNotSupportedException extends RemoteSession.SeekNotSupportedException {
        public SeekNotSupportedException() { super("服务器不支持 REST 定位"); }
    }
    private final FTPClient ftp;
    private final Endpoint endpoint;
    private final CertificateTrust trust;
    private volatile boolean closed;
    private volatile Socket dataSocket;
    private volatile ConnectListener connectListener;
    private long lastUse;
    private boolean tls12Compatibility;
    private interface ListingClient { Socket listing(String path)throws IOException; }
    private final class SecureClient extends FTPSClient implements ListingClient {
        public Socket listing(String path)throws IOException{return _openDataConnection_("MLSD",path);}
        private final SSLContext context;
        SecureClient(boolean implicit,SSLContext context) { super(implicit,context);this.context=context; }
        @Override protected void _prepareDataSocket_(Socket socket) throws IOException {
            dataSocket=socket;
            if(closed){closeData();throw new InterruptedIOException("传输已取消");}
        }
        @Override protected Socket _openDataConnection_(String command,String arg)throws IOException {
            long restart=getRestartOffset();
            try {
                Socket plain=super._openDataConnection_(command,arg);
                if(plain==null){closeData();if(readCommand(command)&&retryTls12()){setRestartOffset(restart);return _openDataConnection_(command,arg);}return null;}
                // Passive TCP is established by Commons Net, but TLS must use the configured
                // host rather than the numeric PASV/EPSV address for SAN matching and SNI.
                SSLSocket secure=(SSLSocket)context.getSocketFactory().createSocket(plain,endpoint.host,plain.getPort(),true);
                dataSocket=secure;
                if(closed){closeData();throw new InterruptedIOException("传输已取消");}
                secure.setUseClientMode(true);secure.setSoTimeout(plain.getSoTimeout());
                secure.setEnabledProtocols(new String[]{controlProtocol()});
                // Each FtpSession has its own TLS context: no credentials/sessions cross profiles.
                ((BCSSLSocket)secure).setBCSessionToResume(((BCSSLSocket)_socket_).getBCSession());
                if(endpoint.certificatePin.isEmpty()) {
                    SSLParameters parameters=secure.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);
                }
                secure.startHandshake();
                java.security.cert.Certificate certificate=secure.getSession().getPeerCertificates()[0];
                if(!CertificateTrust.fingerprint((java.security.cert.X509Certificate)certificate).equals(trust.sessionCertificate))
                    throw new SSLHandshakeException("数据连接证书与控制连接不一致");
                return secure;
            } catch(IOException failure){
                closeData();
                // A TLS alert can precede the server's 522 control reply.
                if(readCommand(command)&&!tls12Compatibility&&getReplyCode()==150){
                    int saved=getSoTimeout();try{setSoTimeout(1000);getReply();}catch(IOException ignored){}finally{setSoTimeout(saved);}
                }
                if(readCommand(command)&&retryTls12()){setRestartOffset(restart);return _openDataConnection_(command,arg);}
                throw failure;
            }
        }
        String controlProtocol(){return _socket_ instanceof SSLSocket?((SSLSocket)_socket_).getSession().getProtocol():"";}
    }
    private static boolean readCommand(String command){return command.equals("RETR")||command.equals("MLSD")||command.equals("LIST");}
    /** Only read-only requests may be restarted; never replay STOR or any mutation. */
    private boolean retryTls12()throws IOException{
        if(closed||Thread.currentThread().isInterrupted()||tls12Compatibility||ftp.getReplyCode()!=522
                ||!(ftp instanceof SecureClient)||!"TLSv1.3".equals(((SecureClient)ftp).controlProtocol()))return false;
        tls12Compatibility=true;closeData();ftp.disconnect();trust.sessionCertificate=null;trust.offered=null;trust.rejected=false;
        ((FTPSClient)ftp).setEnabledProtocols(new String[]{"TLSv1.2"});ftp.setRestartOffset(0);connect();return true;
    }
    private final class PlainClient extends FTPClient implements ListingClient {
        public Socket listing(String path)throws IOException{return _openDataConnection_("MLSD",path);}
        @Override protected Socket _openDataConnection_(String command,String arg)throws IOException {
            Socket socket=super._openDataConnection_(command,arg);dataSocket=socket;
            if(closed){closeData();throw new InterruptedIOException("传输已取消");}return socket;
        }
    }
    public FtpSession(Endpoint endpoint) throws IOException {
        this.endpoint=endpoint;
        SSLContext context=null;
        try {trust=endpoint.protocol==Endpoint.Protocol.FTP?null:new CertificateTrust(endpoint.certificatePin);
            if(trust!=null){context=SSLContext.getInstance("TLS",new BouncyCastleJsseProvider(new BouncyCastleProvider()));context.init(null,new TrustManager[]{trust},new java.security.SecureRandom());}}
        catch(java.security.GeneralSecurityException e){throw new IOException("TLS 初始化失败",e);}
        if(trust!=null){SecureClient tls=new SecureClient(endpoint.protocol==Endpoint.Protocol.FTPS_IMPLICIT,context);tls.setTrustManager(trust);tls.setEndpointCheckingEnabled(endpoint.certificatePin.isEmpty());tls.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});ftp=tls;}else ftp=new PlainClient();
        ftp.setConnectTimeout(12000);ftp.setDefaultTimeout(15000);ftp.setDataTimeout(Duration.ofSeconds(30));
        ftp.setControlEncoding(endpoint.charset);ftp.setUseEPSVwithIPv4(true);ftp.setIpAddressFromPasvResponse(false);
        // Do not fix TCP send/receive buffers: preserve kernel auto tuning.
        ftp.setBufferSize(128*1024);
    }
    @Override public void setConnectListener(ConnectListener listener){this.connectListener=listener;}
    private void stage(String text) throws IOException {ConnectListener l=connectListener;if(l!=null)l.onStage(text);}
    @Override public void connect() throws IOException {
        if(closed)throw new IOException("连接已关闭");
        try {
            stage("正在连接服务器…");
            ftp.connect(endpoint.host,endpoint.port);ftp.setSoTimeout(15000);
            if(!FTPReply.isPositiveCompletion(ftp.getReplyCode()))throw failure("服务器拒绝连接");
            if(closed)throw new InterruptedIOException("连接已取消");
            if(trust!=null&&trust.offered!=null)trust.sessionCertificate=CertificateTrust.fingerprint(trust.offered);
            stage("正在验证身份…");
            if(!ftp.login(endpoint.username,endpoint.password))throw failure("登录失败，请检查账户和权限");
            if(ftp instanceof FTPSClient){((FTPSClient)ftp).execPBSZ(0);((FTPSClient)ftp).execPROT("P");
                // 关键且脆弱：FTPSClient._prepareDataSocket_ 是空实现，数据通道的 TLS 完全来自
                // _socketFactory_。这里换回明文工厂，TLS 由 SecureClient._openDataConnection_
                // 手动包一层，刚好是单层 TLS（RFC 4217 的 PROT P 语义）。
                // 一旦有人去改 _prepareDataSocket_、或升级 commons-net 后它变成非空实现，
                // 数据连接会静默降级成明文——编译和运行都不报错。改动前请先读这段。
                ftp.setSocketFactory(javax.net.SocketFactory.getDefault());}
            ftp.enterLocalPassiveMode();
            if(!ftp.setFileType(FTP.BINARY_FILE_TYPE))throw failure("服务器不支持二进制传输");
            if("UTF-8".equalsIgnoreCase(endpoint.charset))ftp.sendCommand("OPTS","UTF8 ON");
            lastUse=System.nanoTime();
        } catch(IOException e){close();if(trust!=null&&trust.rejected&&trust.offered!=null)throw new CertificateApprovalException(trust.offered,e);throw e;}
    }
    @Override public boolean healthy() {return !closed&&ftp.isConnected();}
    @Override public void checkIdle()throws IOException {if(System.nanoTime()-lastUse>20_000_000_000L)probe();}
    @Override public void probe()throws IOException {if(closed)throw new InterruptedIOException("连接已关闭");if(!ftp.sendNoOp())throw failure("连接已过期，请刷新重连");lastUse=System.nanoTime();}
    @Override public String workingDirectory()throws IOException {String pwd=ftp.printWorkingDirectory();if(pwd==null)throw failure("无法读取起始目录");return path(pwd);}
    public static String path(String raw){Endpoint.clean(raw,"路径");if(!raw.startsWith("/"))throw new IllegalArgumentException("远程路径必须是绝对路径");return raw;}
    public static String child(String parent,String name){path(parent);Endpoint.clean(name,"文件名");if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/"))throw new IllegalArgumentException("无效的远程文件名");return(parent.endsWith("/")?parent:parent+"/")+name;}
    private void cwd(String directory)throws IOException {if(closed)throw new InterruptedIOException("连接已关闭");if(!ftp.changeWorkingDirectory(path(directory)))throw failure("无法访问目录");}
    @Override public List<RemoteEntry> list(String directory)throws IOException {
        try{return listOnce(directory);}catch(IOException failed){if(retryTls12())return listOnce(directory);throw failed;}
    }
    private List<RemoteEntry> listOnce(String directory)throws IOException {
        cwd(directory);ArrayList<RemoteEntry> out=new ArrayList<>();
        Socket listing=((ListingClient)ftp).listing(path(directory));
        if(listing!=null){
            try(Socket socket=listing;BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),endpoint.charset))){
                String line;while((line=reader.readLine())!=null){RemoteEntry entry=MlsdEntryParser.parse(directory,line);if(entry!=null)out.add(entry);}
            }catch(IOException e){close();throw e;}
            dataSocket=null;if(!ftp.completePendingCommand())throw failure("列出目录失败");
        }else{
            int code=ftp.getReplyCode();
            // Unsupported MLSD must fall back to LIST, but permission failures must remain failures.
            // 500/501/502/504 = unsupported; 550 is a permission/path failure.
            if(code==500||code==501||code==502||code==504){FTPFile[] files=ftp.listFiles(directory);if(!FTPReply.isPositiveCompletion(ftp.getReplyCode()))throw failure("列出目录失败");
                for(FTPFile f:files){String n=f.getName();if(n==null||n.equals(".")||n.equals(".."))continue;
                    try{out.add(new RemoteEntry(n,child(directory,n),f.isDirectory(),f.getSize(),f.getTimestamp()==null?0:f.getTimestamp().getTimeInMillis(),f.isSymbolicLink()));}
                    catch(IllegalArgumentException ignored){}
                }
            }
            else throw failure("列出目录失败");
        }
        // Link type does not tell us whether the target is a directory. Restore CWD even on failure.
        for(int i=0;i<out.size();i++){RemoteEntry e=out.get(i);if(!e.link)continue;
            boolean dir=false;try{dir=ftp.changeWorkingDirectory(e.path);}finally{cwd(directory);}
            if(dir)out.set(i,new RemoteEntry(e.name,e.path,true,e.size,e.modified,true));
        }
        lastUse=System.nanoTime();return out;
    }
    /**
     * 在线播放用的流式读取：从 offset 字节处开始顺序返回远程文件内容，绝不落盘。
     * offset &gt; 0 requires REST; unsupported positioning fails without downloading the prefix.
     * The caller closes each stream before reusing the session.
     */
    @Override public InputStream openStream(String remote,long offset)throws IOException {
        if(closed)throw new InterruptedIOException("连接已关闭");
        ftp.setRestartOffset(Math.max(0,offset));
        InputStream stream=ftp.retrieveFileStream(path(remote));
        if(stream==null){
            int code=ftp.getReplyCode();
            // Only explicit unsupported-command replies mean REST is unavailable.
            if(offset>0&&(code==500||code==501||code==502||code==504))throw new SeekNotSupportedException();
            throw failure("无法打开远程文件");
        }
        lastUse=System.nanoTime();return new StreamingInputStream(stream);
    }
    /**
     * 包装在线播放的流式读取。ExoPlayer 在 seek / 缓冲调整 / 取消时会反复 close() 数据流，
     * 直接关底层 socket 会在服务端留下 RST（日志里的"Connection reset by peer / 读取失败"）。
     * 这里改成：<b>未读完就被 close</b> 时先发 ABOR 让服务端优雅结束（并消费 426/226），
     * 已读到 EOF（正常播完）则不额外发 ABOR，交给调用方正常收尾。
     */
    private final class StreamingInputStream extends InputStream {
        private final InputStream delegate;
        private final Socket ownedSocket;
        private boolean finished;
        private boolean eof;
        StreamingInputStream(InputStream delegate){this.delegate=delegate;ownedSocket=dataSocket;}
        void cancel(){if(ownedSocket!=null)try{ownedSocket.close();}catch(IOException ignored){}}
        @Override public int read()throws IOException{
            int n=delegate.read();if(n<0)eof=true;return n;
        }
        @Override public int read(byte[] b,int off,int len)throws IOException{
            int n=delegate.read(b,off,len);if(n<0)eof=true;return n;
        }
        @Override public int available()throws IOException{return delegate.available();}
        @Override public void close()throws IOException {
            if(finished)return;finished=true;
            try{
                delegate.close();closeData();
                if(closed)return;
                if(eof){if(!ftp.completePendingCommand())throw failure("服务器未确认读取完成");}
                else finishInterruptedTransfer();
                lastUse=System.nanoTime();
            }catch(IOException failure){FtpSession.this.close();throw failure;}
            finally{dataSocket=null;}
        }
    }
    @Override public void cancelStream(InputStream stream){if(stream instanceof FtpSession.StreamingInputStream)((StreamingInputStream)stream).cancel();else close();}
    /** Unknown size is preferable to stale directory-list metadata. */
    @Override public long streamSize(String remote)throws IOException {
        String value=ftp.getSize(path(remote));
        if(value==null)return -1;
        try{return Long.parseLong(value.trim());}catch(NumberFormatException invalid){return -1;}
    }
    @Override public void download(String remote,OutputStream output,Progress progress)throws IOException {
        try {InputStream stream=ftp.retrieveFileStream(path(remote));if(stream==null)throw failure("下载失败");
            try(InputStream in=stream){copy(in,output,progress);}if(!ftp.completePendingCommand())throw failure("服务器未确认下载完成");lastUse=System.nanoTime();
        }catch(IOException e){close();throw e;}finally{dataSocket=null;}
    }
    /** Unique partial name first; final rename only after server acknowledges the upload. */
    @Override public void upload(String directory,String name,InputStream input,Progress progress)throws IOException {
        String target=child(directory,name),temporary=child(directory,".cjfm-"+UUID.randomUUID()+".part");
        assertAbsent(directory,name);
        try {OutputStream stream=ftp.storeFileStream(temporary);if(stream==null)throw failure("上传失败");try(OutputStream out=stream){copy(input,out,progress);}if(!ftp.completePendingCommand())throw failure("服务器未确认上传完成");
            assertAbsent(directory,name);if(!ftp.rename(temporary,target))throw failure("提交上传文件名失败");lastUse=System.nanoTime();
        }catch(IOException|RuntimeException e){
            boolean removed=false;
            if(!closed&&!Thread.currentThread().isInterrupted())try{
                if(dataSocket!=null){closeData();finishInterruptedTransfer();}
                removed=ftp.deleteFile(temporary);
            }catch(IOException|RuntimeException cleanup){e.addSuppressed(cleanup);}
            close();boolean scheduled=!removed&&FailedUploadCleanup.schedule(endpoint,temporary);
            throw new IOException("上传未完成，未覆盖目标文件；"+(removed?"已清理临时文件。":scheduled?"已安排临时文件清理；服务器不可达时可能残留 "+temporary+"。":"临时文件清理未完成："+temporary+"。")+e.getMessage(),e);
        }finally{dataSocket=null;}
    }
    /* 大小写不敏感判重：与 SmbSession.assertAbsent 保持一致，也与 rename() 里
       「仅大小写变化不算重名」的豁免一致。Windows 后端的 FTP（FileZilla/IIS 等）
       目录项大小写不敏感，这里用 equals 会漏判 Foo.txt 已有、再传 foo.txt 覆盖它；
       Linux 后端本身区分大小写，不敏感判重只会偶尔多报一次「同名」，不会丢数据。 */
    private void assertAbsent(String directory,String name)throws IOException {for(RemoteEntry e:list(directory))if(e.name.equalsIgnoreCase(name))throw new IOException("目标已有同名项目："+name);}
    private void copy(InputStream in,OutputStream out,Progress progress)throws IOException {byte[] b=new byte[128*1024];long total=0;int n;while((n=in.read(b))!=-1){if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("传输已取消");out.write(b,0,n);total+=n;if(progress!=null)progress.transferred(total);}out.flush();}
    @Override public void mkdir(String parent,String name)throws IOException {assertAbsent(parent,name);if(!ftp.makeDirectory(child(parent,name)))throw failure("新建目录失败");}
    @Override public void rename(String from,String parent,String name)throws IOException {String source=path(from),target=child(parent,name);
        /* 改成同名（或仅大小写变化）时目标就是自己，不该被 assertAbsent 判成"已存在"——SMB 分支早就是这么做的。 */
        if(!source.equalsIgnoreCase(target))assertAbsent(parent,name);
        if(!ftp.rename(source,target))throw failure("重命名失败");}
    @Override public void delete(String remote,boolean directory)throws IOException {boolean ok=directory?ftp.removeDirectory(path(remote)):ftp.deleteFile(path(remote));if(!ok)throw failure(directory?"删除目录失败（仅支持空目录）":"删除文件失败");}
    private IOException failure(String action){return new IOException(action+"（FTP "+ftp.getReplyCode()+"）"+(ftp.getReplyCode()==522?"。服务器拒绝 TLS 数据连接；已尝试复用控制通道会话，请检查服务端的 TLS 协议和会话复用设置。":""));}
    private void closeData(){Socket s=dataSocket;if(s!=null)try{s.close();}catch(IOException ignored){}dataSocket=null;}
    @Override public void close(){
        if(closed)return;closed=true;
        // 先关数据 socket：这是取消阻塞读的关键，必须第一时间执行，不能等 ABOR 往返。
        // Cancellation must not wait for ABOR. Normal stream closure drains replies separately.
        closeData();
        try{ftp.disconnect();}catch(IOException ignored){}
    }
    /** Drain ABOR's optional 426/226/225 replies behind a NOOP fence before reusing control. */
    private void finishInterruptedTransfer()throws IOException{
        int saved=ftp.getSoTimeout();long deadline=System.nanoTime()+3_000_000_000L;
        try{
            ftp.setSoTimeout(1000);ftp.abort();
            ftp.sendCommand("NOOP");
            for(int replies=0;;replies++){
                int code=ftp.getReplyCode();if(code==200)return;
                if(replies>=6||System.nanoTime()>=deadline||!(code==225||code==226||code==426))throw failure("无法确认流已结束，连接已丢弃");
                ftp.setSoTimeout(Math.max(1,(int)Math.min(1000,(deadline-System.nanoTime())/1_000_000L)));ftp.getReply();
            }
        }finally{if(!closed)ftp.setSoTimeout(saved);}
    }
}
