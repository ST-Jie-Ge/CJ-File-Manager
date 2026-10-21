package com.cj.music.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * FTP / FTPS / SMB 共用的远程会话抽象。浏览与传输各持独立会话，一次只有一个调用方；
 * close() 可被并发调用来取消阻塞中的 socket 操作。
 *
 * 路径约定：{@link #list} / {@link #openStream} / {@link #download} 等方法接收的是
 * 协议中立的「绝对路径」。FTP 是 /dir/file；SMB 是 share/dir/file（以共享名为根）。
 * 各实现自行保证路径的合法性与拼接，杜绝目录穿越。
 */
public interface RemoteSession extends AutoCloseable {

    /** 传输进度回调；返回的字节数是本次操作累计值。 */
    interface Progress { void transferred(long bytes) throws IOException; }

    /** 连接进度回调：connect() 在各阶段回调（供进度弹窗显示「正在连接/验证身份/读取目录」）。 */
    interface ConnectListener { void onStage(String stage) throws IOException; }

    /** 注册连接进度监听（可选）。 */
    void setConnectListener(ConnectListener listener);

    /** 建立连接并登录；失败抛 IOException，实现需自行清理资源。 */
    void connect() throws IOException;

    /** Local resource check only; probe() is required to establish actual remote liveness. */
    boolean healthy();

    /** 空闲较久时探测/续期连接；链路已失效则抛 IOException 让上层丢弃并重连。 */
    void checkIdle() throws IOException;

    /** Explicit round trip before writes / when returning to a retained network tab. */
    default void probe() throws IOException { checkIdle(); }

    /** 当前根目录（FTP 为服务端工作目录，SMB 为已连接的共享名）。 */
    String workingDirectory() throws IOException;

    /** 列出目录内容。 */
    List<RemoteEntry> list(String directory) throws IOException;

    /**
     * 在线播放用的流式读取：从 offset 字节处开始顺序返回文件内容，绝不落盘。
     * offset &gt; 0 依赖实现支持定位；不支持时应抛 {@link SeekNotSupportedException}。
     * 返回的流由调用方关闭；中途放弃时直接 close() 会话即可。
     */
    InputStream openStream(String remote, long offset) throws IOException;
    /** Interrupt only this owned stream; implementations must not touch a newer stream. */
    default void cancelStream(InputStream stream){close();}

    /** 服务器不支持从 offset 定位时抛出；播放器不应静默下载并丢弃大量数据。 */
    class SeekNotSupportedException extends IOException {
        public SeekNotSupportedException(String message) { super(message); }
    }

    /** 远端文件大小；未知返回 -1（比用目录列表的过期元数据更稳妥）。 */
    long streamSize(String remote) throws IOException;

    /** 下载到本地输出流。 */
    void download(String remote, OutputStream output, Progress progress) throws IOException;

    /** 上传：先写唯一临时名，服务器确认后改名提交，避免半成品覆盖。 */
    void upload(String directory, String name, InputStream input, Progress progress) throws IOException;

    void mkdir(String parent, String name) throws IOException;

    void rename(String from, String parent, String name) throws IOException;

    /** 删除文件或空目录；directory 为 true 时仅删空目录，绝不递归。 */
    void delete(String remote, boolean directory) throws IOException;

    /**
     * 变更类操作（批量删除等）在这条会话上允许的并发度，最小 1。
     * 默认 1：FTP 的控制连接上并发发命令会互相串扰（commons-net 的 FTPClient
     * 不是 thread-safe）。SMBJ 按消息 ID 复用同一条连接，可安全并发，见 SmbSession 覆写。
     */
    default int mutationParallelism() { return 1; }

    @Override void close();
}
