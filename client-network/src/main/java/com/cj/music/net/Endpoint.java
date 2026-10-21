package com.cj.music.net;

import java.nio.charset.Charset;
import java.util.Locale;

/** Immutable snapshot; never serialize or log password with the endpoint. */
public final class Endpoint {
    /** FTP/FTPS/SMB 照旧；WEBDAV/OPENLIST 的目录路径由各会话按 URL 路径规则规范化。 */
    public enum Protocol { FTP, FTPS_EXPLICIT, FTPS_IMPLICIT, SMB, WEBDAV, OPENLIST }
    public final String host, username, password, charset, certificatePin, domain, share;
    public final int port;
    public final Protocol protocol;
    /** WebDAV/OpenList 是否走明文 http（地址栏写 http:// 前缀即判定）；其余协议恒为 false。 */
    public final boolean plainHttp;
    /** 原 7 参构造：FTP/FTPS 专用，domain/share 置空。 */
    public Endpoint(String host, int port, Protocol protocol, String username, String password,
                    String charset, String certificatePin) {
        this(host, port, protocol, username, password, charset, certificatePin, "", "", false);
    }
    /** 全参构造：SMB 使用 domain（域/工作组，可空）与 share（共享名，可空=列出根）。 */
    public Endpoint(String host, int port, Protocol protocol, String username, String password,
                    String charset, String certificatePin, String domain, String share) {
        this(host, port, protocol, username, password, charset, certificatePin, domain, share, false);
    }
    /** 带明文 http 标记的全参构造：WebDAV/OpenList 用地址栏的 http:// 前缀判定 scheme。 */
    public Endpoint(String host, int port, Protocol protocol, String username, String password,
                    String charset, String certificatePin, String domain, String share, boolean requestPlainHttp) {
        java.util.Objects.requireNonNull(protocol);
        /* 地址栏前缀即 scheme：WebDAV/OpenList 允许 http:// 或 https:// 开头，剥掉后只留主机名。
           局域网 NAS（AList/OpenList、群晖、威联通）默认就是 http，不认前缀根本连不上。 */
        String address = clean(host, "服务器地址").trim();
        boolean http = false;
        boolean webdavProtocol = protocol == Protocol.WEBDAV || protocol == Protocol.OPENLIST;
        if (webdavProtocol) {
            String lower = address.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://")) { http = true; address = address.substring(7).trim(); }
            else if (lower.startsWith("https://")) { address = address.substring(8).trim(); }
            while (address.endsWith("/")) address = address.substring(0, address.length() - 1).trim();
        }
        this.host = address;
        if (this.host.isEmpty() || this.host.contains("://") || this.host.contains("/") || this.host.contains("@"))
            throw new IllegalArgumentException(webdavProtocol
                    ? "地址只填写主机名或 IP（可写 http:// 前缀），不含路径和账户"
                    : "地址只填写主机名或 IP，不含协议、路径和账户");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须为 1–65535");
        this.port = port; this.protocol = protocol;
        this.plainHttp = http || (webdavProtocol && requestPlainHttp);
        this.username = clean(username, "用户名"); this.password = clean(password, "密码");
        if (this.username.isEmpty() && (protocol != Protocol.SMB || !this.password.isEmpty())
                && protocol != Protocol.WEBDAV && protocol != Protocol.OPENLIST)
            throw new IllegalArgumentException("用户名不能为空；匿名请选择匿名登录");
        String fileCharset=protocol==Protocol.SMB?"UTF-8":charset;
        Charset.forName(fileCharset); this.charset = fileCharset;
        boolean tls=protocol==Protocol.FTPS_EXPLICIT||protocol==Protocol.FTPS_IMPLICIT
                ||((protocol==Protocol.WEBDAV||protocol==Protocol.OPENLIST)&&!this.plainHttp);
        this.certificatePin = !tls||certificatePin == null ? "" : certificatePin.replace(":", "").toUpperCase(Locale.ROOT);
        if (!this.certificatePin.isEmpty() && !this.certificatePin.matches("[0-9A-F]{64}"))
            throw new IllegalArgumentException("证书指纹必须是 SHA-256");
        this.domain = protocol==Protocol.SMB?clean(domain == null ? "" : domain, "域").trim():"";
        if (this.domain.contains("\\") || this.domain.contains("/") || this.domain.contains("@"))
            throw new IllegalArgumentException("域/工作组只填写名称，不含分隔符");
        String configuredShare = protocol==Protocol.SMB||protocol==Protocol.WEBDAV||protocol==Protocol.OPENLIST
                ?clean(share == null ? "" : share, "起始目录").trim():"";
        // WebDAV/OpenList 的起始目录即 URL 前缀，必须由规范化入口校验（拒绝 .. 等穿越）。
        this.share = (protocol==Protocol.WEBDAV||protocol==Protocol.OPENLIST)
                ? WebdavSession.normalizeRootPath(configuredShare) : configuredShare;
    }
    public static String clean(String text, String label) {
        if (text == null || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0)
            throw new IllegalArgumentException(label + "包含非法字符");
        return text;
    }
    public Endpoint withPin(String pin) { return new Endpoint(host, port, protocol, username, password, charset, pin, domain, share, plainHttp); }
    public boolean smb() { return protocol == Protocol.SMB; }
    /** WebDAV / OpenList：HTTP(S) 通道，由 WebdavSession 承载。 */
    public boolean webdav() { return protocol == Protocol.WEBDAV || protocol == Protocol.OPENLIST; }
    /** 地址栏展示用：明文 http 时补回 scheme 前缀，编辑表单能原样回填。 */
    public String displayHost() { return plainHttp && webdav() ? "http://" + host : host; }
}
