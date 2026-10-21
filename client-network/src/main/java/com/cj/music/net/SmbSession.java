package com.cj.music.net;

import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.messages.SMB2Echo;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.Share;
import com.rapid7.client.dcerpc.mssrvs.ServerService;
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo1;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * SMB2/3 会话（基于 SMBJ）。路径以「共享名」为根，形如 share/dir/file；
 * 共享名由 {@link #connectShare} 单独建立，路径字符串绝不包含反斜杠与 UNC 前缀。
 *
 * 约定与 {@link FtpSession} 一致：一次一个调用方；close() 可并发调用以取消阻塞中的 IO。
 * SMBJ 底层是纯 Java NIO socket，close 掉 client 即可中断未完成的读写。
 */
public final class SmbSession implements RemoteSession {
    private final Endpoint endpoint;
    private volatile boolean closed;
    private volatile SMBClient client;
    private volatile Connection connection;
    private volatile Session session;
    private volatile DiskShare share;
    private volatile String shareName = "";
    private volatile ConnectListener connectListener;
    private long lastUse;
    private volatile ServerService shareEnumeration;
    private final Object enumerationLock=new Object();
    private final SingleFlightResource<SmbRpcTransport> enumerationTransport=new SingleFlightResource<>(()->{
        checkOpen();return SmbRpcTransport.connect(session);
    });

    public SmbSession(Endpoint endpoint) {
        this.endpoint = endpoint;
        if (endpoint.protocol != Endpoint.Protocol.SMB)
            throw new IllegalArgumentException("SmbSession 只能用于 SMB 端点");
    }

    private void checkOpen() throws IOException {
        if (closed) throw new InterruptedIOException("连接已关闭");
    }

    @Override public void setConnectListener(ConnectListener listener){this.connectListener=listener;}
    private void stage(String text) throws IOException {ConnectListener l=connectListener;if(l!=null)l.onStage(text);}
    @Override public void connect() throws IOException {
        checkOpen();
        try {
            SmbConfig config = SmbConfig.builder()
                    .withTimeout(12, TimeUnit.SECONDS)
                    .withSoTimeout(15, TimeUnit.SECONDS)
                    .withReadTimeout(30, TimeUnit.SECONDS)
                    .withWriteTimeout(30, TimeUnit.SECONDS)
                    .withTransactTimeout(30, TimeUnit.SECONDS)
                    .withDfsEnabled(true)
                    .withMultiProtocolNegotiate(true)
                    .build();
            stage("正在连接服务器…");
            client = new SMBClient(config);
            connection = client.connect(endpoint.host, endpoint.port);
            checkOpen();
            AuthenticationContext auth = authentication(endpoint);
            stage("正在验证身份…");
            session = connection.authenticate(auth);
            checkOpen();
            if (!endpoint.share.isEmpty()) { stage("正在连接共享「" + endpoint.share + "」…"); connectShare(endpoint.share); }
            lastUse = System.nanoTime();
        } catch (IOException e) {
            close();
            throw e;
        } catch (RuntimeException e) {
            close();
            // SMB 认证/协议错误归类为可读原因，便于上层弹窗提示「密码错误/拒绝访问/共享不存在」。
            if (e instanceof SMBApiException) {
                String hint = statusHint(((SMBApiException) e).getStatus());
                throw new IOException("SMB 连接失败" + hint, e);
            }
            throw new IOException("SMB 连接失败：" + e.getMessage(), e);
        }
    }

    static AuthenticationContext authentication(Endpoint endpoint) {
        if(endpoint.username.isEmpty() && endpoint.password.isEmpty())return AuthenticationContext.anonymous();
        if(endpoint.username.equalsIgnoreCase("Guest")&&endpoint.password.isEmpty())return AuthenticationContext.guest();
        return new AuthenticationContext(endpoint.username,endpoint.password.toCharArray(),endpoint.domain);
    }

    /** 切换磁盘共享；只在连接成功后发布新共享，失败保持原状态。 */
    public void connectShare(String name) throws IOException {
        checkOpen();
        String clean=cleanShare(name==null?"":name);
        if(clean.isEmpty())throw new IOException("请选择一个共享目录");
        if(share!=null&&clean.equals(shareName))return;
        Share opened=null;
        try {
            opened=session.connectShare(clean);checkOpen();
            if(!(opened instanceof DiskShare))throw new IOException("该共享不是文件目录");
            closeShare();
            synchronized(this){checkOpen();share=(DiskShare)opened;shareName=clean;opened=null;}
            checkOpen();lastUse=System.nanoTime();
        }catch(SMBApiException error){throw smbFailure("连接共享失败",error);}
        catch(RuntimeException error){throw new IOException(RemoteFailures.disconnected(error)?"SMB 连接已断开":"连接共享失败",error);}
        finally{if(opened!=null)try{opened.close();}catch(IOException ignored){}}
    }

    private String resolve(String remote,boolean file) throws IOException {
        SmbLocation location=SmbLocation.resolve(endpoint.share,remote);
        if(location.serverRoot)throw new IOException("请先进入一个共享目录");
        if(file&&location.path.isEmpty())throw new IOException("不能修改或读取共享目录本身，请选择共享内的文件或文件夹");
        connectShare(location.share);
        return location.path.replace('/','\\');
    }

    private List<RemoteEntry> listShares() throws IOException {
        checkOpen();
        try {
            // RPC requests use one pipe. Closing the socket never waits for this lock.
            synchronized(enumerationLock){
                if(shareEnumeration==null){
                    SmbRpcTransport transport=enumerationTransport.get();
                    synchronized(this){checkOpen();shareEnumeration=new ServerService(transport);}
                }
                List<RemoteEntry> result=shareEntries(shareEnumeration.getShares1());checkOpen();lastUse=System.nanoTime();return result;
            }
        }catch(IOException|RuntimeException error){
            checkOpen();throw new IOException("无法列出服务器共享，请编辑连接并手动填写共享名。"+error.getMessage(),error);
        }
    }
    static List<RemoteEntry> shareEntries(List<NetShareInfo1> shares) {
        java.util.TreeMap<String,RemoteEntry> result=new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(NetShareInfo1 info:shares){
            int type=info.getType();
            // 只留普通磁盘共享。低 16 位非 0 = IPC$/打印机等非磁盘资源；
            // 0x80000000 STYPE_SPECIAL = C$/ADMIN$ 这类管理共享（低 16 位仍是 0，光看低位过滤不掉）；
            // 0x40000000 STYPE_TEMPORARY = 临时共享。需要访问管理共享时可在连接里直接填共享名。
            if((type&0x80000000)!=0||(type&0x40000000)!=0||(type&0xffff)!=0)continue;
            try{String name=cleanShare(info.getNetName());if(!name.isEmpty())result.put(name,new RemoteEntry(name,name,true,0,0));}
            catch(IllegalArgumentException ignored){}
        }
        return new ArrayList<>(result.values());
    }

    private void closeShare() {
        DiskShare s = share;
        share = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    @Override public boolean healthy() {
        return !closed && connection != null && connection.isConnected();
    }

    @Override public void checkIdle() throws IOException {
        if (System.nanoTime() - lastUse <= 20_000_000_000L) return;
        probe();
    }

    @Override public void probe() throws IOException {
        checkOpen();
        if (!healthy()) throw new IOException("连接已过期，请刷新重连");
        Connection current=connection;
        try {
            SMB2Echo reply=current.<SMB2Echo>send(new SMB2Echo(current.getNegotiatedProtocol().getDialect())).get(12,TimeUnit.SECONDS);
            if(reply.getHeader().getStatusCode()!=NtStatus.STATUS_SUCCESS.getValue())throw new IOException("SMB 探活失败："+reply.getHeader().getStatusCode());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("SMB 探活已取消");}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException|RuntimeException e){throw new IOException("SMB 连接探测失败",e);}
        lastUse = System.nanoTime();
    }

    @Override public String workingDirectory() throws IOException {
        checkOpen();
        return shareName;
    }

    /** 规范化 SMB 相对路径：统一正斜杠、去除首尾斜杠、拒绝 . / .. 与 UNC/绝对路径。 */
    public static String cleanPath(String path) {
        Endpoint.clean(path, "路径");
        String p = path.replace('\\', '/');
        if(p.startsWith("//") || p.matches("(?i)[a-z]:.*"))
            throw new IllegalArgumentException("路径不接受 UNC 前缀或盘符");
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.startsWith("//") || p.matches("(?i)[a-z]:.*"))
            throw new IllegalArgumentException("路径应是共享名开头的相对路径，不含盘符或 UNC 前缀");
        String[] seg = p.split("/");
        for (String s : seg) if (s.equals("..") || s.equals("."))
            throw new IllegalArgumentException("路径不能包含 . 或 ..");
        return p;
    }

    private static String cleanShare(String name) {
        Endpoint.clean(name, "共享名");
        String s = name.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        if (s.contains("/")) throw new IllegalArgumentException("共享名不能包含路径");
        if (s.equals(".") || s.equals("..")) throw new IllegalArgumentException("无效的共享名");
        return s;
    }

    private static String child(String parent, String name) {
        String p = cleanPath(parent);
        Endpoint.clean(name, "文件名");
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("无效的文件名");
        return p.isEmpty() ? name : p + "/" + name;
    }

    @Override public List<RemoteEntry> list(String directory) throws IOException {
        checkOpen();
        String dir=cleanPath(directory);
        if(endpoint.share.isEmpty()&&dir.isEmpty())return listShares();
        String inside=resolve(dir,false);
        try {
            List<FileIdBothDirectoryInformation> files = share.list(inside);
            ArrayList<RemoteEntry> out = new ArrayList<>(files.size());
            for (FileIdBothDirectoryInformation f : files) {
                String n = f.getFileName();
                if (n == null || n.equals(".") || n.equals("..")) continue;
                boolean isDir = (f.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                try {
                    String full = child(dir, n);
                    long mtime = f.getLastWriteTime() == null ? 0L : f.getLastWriteTime().toEpochMillis();
                    out.add(new RemoteEntry(n, full, isDir, f.getEndOfFile(), mtime,
                            (f.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0,
                            f.getCreationTime()==null?0:f.getCreationTime().toEpochMillis()));
                } catch (IllegalArgumentException ignored) {
                    // 拒绝可能逃逸父目录或破坏 SMB 路径语义的名称
                }
            }
            lastUse = System.nanoTime();
            return out;
        } catch (SMBApiException e) {
            throw smbFailure("列出目录失败", e);
        } catch (RuntimeException e) {
            throw new IOException(RemoteFailures.disconnected(e)?"SMB 连接已断开":"SMB 读取目录失败",e);
        }
    }

    private File open(String path, EnumSet<AccessMask> access, SMB2CreateDisposition disposition) throws IOException {
        String inside=resolve(path,true);
        return share.openFile(inside, access, EnumSet.noneOf(FileAttributes.class),
                SMB2ShareAccess.ALL, disposition, null);
    }

    @Override public InputStream openStream(String remote, long offset) throws IOException {
        checkOpen();
        File file = open(remote, EnumSet.of(AccessMask.GENERIC_READ), SMB2CreateDisposition.FILE_OPEN);
        try {
            if(offset<0)throw new IllegalArgumentException("读取位置不能为负数");
            return new SmbSeekableInputStream(file,offset);
        } catch (IOException | RuntimeException e) {
            file.close();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("打开远端文件失败", e);
        }
    }

    @Override public long streamSize(String remote) throws IOException {
        checkOpen();
        try {
            String inside=resolve(remote,true);
            FileAllInformation info = share.getFileInformation(inside);
            if (info == null || info.getStandardInformation() == null) return -1L;
            return info.getStandardInformation().getEndOfFile();
        } catch (SMBApiException e) {
            throw smbFailure("读取文件信息失败", e);
        }
    }

    @Override public void download(String remote, OutputStream output, Progress progress) throws IOException {
        checkOpen();
        File file = open(remote, EnumSet.of(AccessMask.GENERIC_READ), SMB2CreateDisposition.FILE_OPEN);
        try (InputStream in = file.getInputStream()) {
            copy(in, output, progress);
        } finally {
            file.close();
        }
        lastUse = System.nanoTime();
    }

    @Override public void upload(String directory, String name, InputStream input, Progress progress) throws IOException {
        checkOpen();
        String target = child(directory, name);
        String temporary = child(directory, ".cjfm-" + UUID.randomUUID() + ".part");
        assertAbsent(directory, name);
        try {
            File file = open(temporary, EnumSet.of(AccessMask.GENERIC_WRITE),
                    SMB2CreateDisposition.FILE_CREATE);
            try (OutputStream out = file.getOutputStream()) {
                copy(input, out, progress);
            } finally {
                file.close();
            }
            assertAbsent(directory, name);
            if (!renameInternal(temporary, target))
                throw new IOException("提交上传文件名失败");
            lastUse = System.nanoTime();
        } catch (IOException|RuntimeException e) {
            boolean removed=false;
            if(!closed&&!Thread.currentThread().isInterrupted())try{String inside=resolve(temporary,true);share.rm(inside);removed=true;}
            catch(IOException|RuntimeException cleanup){e.addSuppressed(cleanup);}
            close();boolean scheduled=!removed&&FailedUploadCleanup.schedule(endpoint,temporary);
            throw new IOException("上传未完成，未覆盖目标文件；"+(removed?"已清理临时文件。":scheduled?"已安排临时文件清理；服务器不可达时可能残留 "+temporary+"。":"临时文件清理未完成："+temporary+"。") + e.getMessage(), e);
        }
    }

    private boolean renameInternal(String from,String to) throws IOException {
        SmbLocation source=SmbLocation.resolve(endpoint.share,from),target=SmbLocation.resolve(endpoint.share,to);
        if(!source.share.equalsIgnoreCase(target.share))throw new IOException("不支持跨共享重命名，请先复制再删除源文件");
        String src=resolve(from,true),dst=resolve(to,true);
        try(DiskEntry entry=share.open(src,EnumSet.of(AccessMask.DELETE,AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.noneOf(FileAttributes.class),SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT))){
            entry.rename(dst,false); // 服务器原子拒绝同名目标，禁止覆盖。
            return true;
        }catch(SMBApiException error){throw smbFailure("重命名失败",error);}
    }

    @Override public void mkdir(String parent, String name) throws IOException {
        checkOpen();
        assertAbsent(parent, name);
        try {
            String inside=resolve(child(parent,name),true);
            share.mkdir(inside);
        } catch (SMBApiException e) {
            throw smbFailure("新建目录失败", e);
        }
    }

    @Override public void rename(String from, String parent, String name) throws IOException {
        checkOpen();
        String source=cleanPath(from),target=child(parent,name);
        // 仅大小写变化（README → readme）是同一个文件，不该被 assertAbsent 判成"已存在"。
        if(!source.equalsIgnoreCase(target))assertAbsent(parent,name);
        if (!renameInternal(source, target)) throw new IOException("重命名失败");
    }

    @Override public void delete(String remote, boolean directory) throws IOException {
        checkOpen();
        try {
            String inside=resolve(remote,true);
            // Open the object itself, even if the listing is stale or it is a broken junction.
            // SET_DISPOSITION refuses nonempty ordinary directories without traversing children.
            try(DiskEntry entry=share.open(inside,EnumSet.of(AccessMask.DELETE),
                    EnumSet.noneOf(FileAttributes.class),SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT))){entry.deleteOnClose();}
        } catch (SMBApiException e) {
            throw smbFailure(directory ? "删除目录失败（仅支持空目录）" : "删除文件失败", e);
        }
    }

    /** SMBJ 的 Session 靠消息 ID 复用同一条 TCP 连接，share.open / deleteOnClose 各自持独立
     *  handle，可以安全并发——批量删除按 4 路并发发请求，把高延迟链路（远程 SMB / VPN）上
     *  逐项串行等待往返的时间省掉。FTP 不覆写（默认 1）：控制连接上并发发命令会串扰。 */
    @Override public int mutationParallelism(){return 4;}

    private void assertAbsent(String directory, String name) throws IOException {
        for (RemoteEntry e : list(directory)) if (e.name.equalsIgnoreCase(name))
            throw new IOException("目标已有同名项目：" + name);
    }

    private void copy(InputStream in, OutputStream out, Progress progress) throws IOException {
        byte[] b = new byte[128 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(b)) != -1) {
            if (closed || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("传输已取消");
            out.write(b, 0, n);
            total += n;
            if (progress != null) progress.transferred(total);
        }
        out.flush();
    }

    private IOException smbFailure(String action, SMBApiException e) {
        return new IOException(action + statusHint(e.getStatus())+" ["+e.getStatus()+"]",e);
    }

    /** 把 NT 状态码映射为可读提示（带括号前缀）；未知状态返回空串。 */
    private static String statusHint(NtStatus status) {
        if (status == NtStatus.STATUS_OBJECT_NAME_COLLISION) return "（目标已有同名项目，未覆盖）";
        if (status == NtStatus.STATUS_DIRECTORY_NOT_EMPTY) return "（目录非空，未删除任何内容）";
        if (status == NtStatus.STATUS_ACCESS_DENIED) return "（拒绝访问，请检查账户权限）";
        if (status == NtStatus.STATUS_LOGON_FAILURE) return "（账户或密码错误）";
        if (status == NtStatus.STATUS_BAD_NETWORK_NAME) return "（共享名不存在）";
        if (status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND
         || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) return "（路径不存在）";
        if (status == NtStatus.STATUS_NOT_A_DIRECTORY) return "（不是目录）";
        if (status == NtStatus.STATUS_SHARING_VIOLATION) return "（文件被占用）";
        return "";
    }

    @Override public void close() {
        SMBClient c; Connection conn; Session login; DiskShare disk;
        synchronized(this){
            closed=true;c=client;client=null;conn=connection;connection=null;
            login=session;session=null;disk=share;share=null;shareEnumeration=null;
        }
        // Close the transport first so cancellation does not wait for SMB CLOSE/LOGOFF replies.
        // SMBClient.close() is graceful and sends LOGOFF. Force-close the socket first,
        // including when cancellation comes from the UI thread.
        if(conn!=null)try{conn.close(true);}catch(IOException|RuntimeException ignored){}
        if(c!=null)try{c.close();}catch(RuntimeException ignored){}
        enumerationTransport.close();
        if(disk!=null)try{disk.close();}catch(IOException|RuntimeException ignored){}
        if(login!=null)try{login.close();}catch(IOException|RuntimeException ignored){}
        if(conn!=null)try{conn.close();}catch(IOException|RuntimeException ignored){}
    }

    /** 包装 SMBJ File，支持从 offset 起顺序读取，供在线播放 seek 使用。 */
    private final class SmbSeekableInputStream extends InputStream {
        private final File file;
        private long position;
        private final long length;
        private volatile boolean streamClosed;
        SmbSeekableInputStream(File file, long offset) throws IOException {
            this.file = file;
            long len = -1;
            try {
                FileAllInformation info = file.getFileInformation();
                if (info != null && info.getStandardInformation() != null) len = info.getStandardInformation().getEndOfFile();
            } catch (Exception ignored) {}
            this.length = len;
            this.position = offset;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            checkOpen();
            if(streamClosed)throw new IOException("远端文件流已关闭");
            if (len == 0) return 0;
            if (length >= 0 && position >= length) return -1;
            int n = file.read(b, position, off, len);
            if (n <= 0) return -1;
            position += n;
            return n;
        }
        @Override public void close() throws IOException {
            synchronized(this){if(streamClosed)return;streamClosed=true;}
            file.close();
        }
    }
    @Override public void cancelStream(InputStream stream){
        if(stream instanceof SmbSession.SmbSeekableInputStream)try{stream.close();}catch(IOException|RuntimeException failure){close();}
        else close();
    }
}
