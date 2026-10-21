package com.cj.music.net;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * WebDAV / OpenList 共用的 HTTP(S) 会话。OpenList（AList 延续项目）对外提供
 * WebDAV 兼容接口，因此复用同一条 PROPFIND/GET/PUT/MKCOL/MOVE/DELETE 通道。
 *
 * 路径约定与 FTP 一致：绝对路径 /dir/file；配置的 root（起始目录）作为 URL 前缀挂接。
 * 上传沿用「唯一临时名 + MOVE 提交」；读取支持 Range 续传。HTTPS 自签证书走
 * {@link CertificateTrust} 审批 + 指纹 pin（与 FTPS 同一套机制）。
 */
public final class WebdavSession implements RemoteSession {
    private static final String USER_AGENT = "CJFileManager/1.0";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final Endpoint endpoint;
    private final String rootPrefix;
    /** true = https（默认）；false = 明文 http（地址栏写了 http:// 前缀）。 */
    private final boolean secure;
    private volatile ConnectListener listener;
    private volatile boolean closed;
    private volatile boolean connected;
    private final CertificateTrust trust;
    private final SSLSocketFactory socketFactory;
    private volatile long lastUse;

    public WebdavSession(Endpoint endpoint) throws IOException {
        if (!endpoint.webdav()) throw new IOException("协议不匹配：" + endpoint.protocol);
        this.endpoint = endpoint;
        // 起始目录挂接在 URL 路径前缀上；Profile.root 与 endpoint.share 二者取其一（编辑器把目录放进 share）。
        String base = endpoint.share == null || endpoint.share.trim().isEmpty() ? "" : endpoint.share;
        this.rootPrefix = normalizeRootPath(base);
        this.secure = !endpoint.plainHttp;
        this.lastUse = System.nanoTime();
        if (!secure) {
            // 明文 http：没有 TLS 通道，自签证书审批与指纹 pin 都不参与（Endpoint 已把 pin 清空）。
            this.trust = null;
            this.socketFactory = null;
            return;
        }
        CertificateTrust trustManager;
        SSLSocketFactory factory;
        try {
            trustManager = new CertificateTrust(endpoint.certificatePin == null ? "" : endpoint.certificatePin);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{trustManager}, null);
            factory = context.getSocketFactory();
        } catch (GeneralSecurityException failure) {
            throw new IOException("TLS 初始化失败", failure);
        }
        this.trust = trustManager;
        this.socketFactory = factory;
    }

    /** 起始目录（URL 前缀）规范化；空→""，其余返回 /seg/seg 形式（无尾斜杠，根为 ""）。 */
    public static String normalizeRootPath(String root) {
        if (root == null) return "";
        String text = root.trim();
        if (text.isEmpty()) return "";
        if (text.indexOf('\0') >= 0 || text.contains("://") || text.contains("?") || text.contains("#"))
            throw new IllegalArgumentException("起始目录只填写路径，不含协议与查询参数");
        if (!text.startsWith("/")) text = "/" + text;
        StringBuilder out = new StringBuilder();
        for (String segment : text.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) throw new IllegalArgumentException("起始目录不能包含 ..");
            out.append('/').append(segment);
        }
        return out.toString();
    }

    /** 协议中立绝对路径 → 校验后的 URL 路径（含 root 前缀，不含 scheme/host）。 */
    private String urlPath(String remote) {
        if (remote == null) throw new IllegalArgumentException("路径不能为空");
        String text = remote.trim();
        if (text.indexOf('\0') >= 0 || text.contains("://") || text.contains("?") || text.contains("#"))
            throw new IllegalArgumentException("路径包含非法字符");
        if (!text.startsWith("/")) text = "/" + text;
        StringBuilder out = new StringBuilder();
        for (String segment : text.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) throw new IllegalArgumentException("路径不能包含 ..");
            out.append('/').append(segment);
        }
        return rootPrefix + out;
    }

    private static String nameOf(String path) {
        int cut = path.lastIndexOf('/');
        return cut < 0 ? path : path.substring(cut + 1);
    }

    private static String childPath(String parent, String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("名称不合法：" + name);
        String base = parent == null || parent.isEmpty() ? "/" : parent;
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private String authHeader() {
        String user = endpoint.username == null ? "" : endpoint.username;
        String pass = endpoint.password == null ? "" : endpoint.password;
        // FTP 匿名习惯（anonymous/anonymous@）在 WebDAV 上等同于匿名。
        if (user.equals("anonymous") && pass.equals("anonymous@")) return null;
        if (user.isEmpty() && pass.isEmpty()) return null;
        String token = Base64.encode((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private HttpURLConnection open(String method, String rawPath) throws IOException {
        if (closed) throw new IOException("会话已关闭");
        String encoded = encodePath(rawPath);
        /* scheme 由地址栏决定：写了 http:// 就发明文请求（局域网 NAS 常态），否则一律 https。
           以前这里写死 https，明文 WebDAV/OpenList 根本连不上。 */
        URL url = new URL(secure ? "https" : "http", endpoint.host, endpoint.port, encoded);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Connection", "close");
        String auth = authHeader();
        if (auth != null) connection.setRequestProperty("Authorization", auth);
        if (secure && connection instanceof HttpsURLConnection && socketFactory != null)
            ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
        connection.setRequestMethod(method);
        lastUse = System.nanoTime();
        return connection;
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (out.length() > 0 || path.startsWith("/")) out.append('/');
            if (!segment.isEmpty()) out.append(encodeSegment(segment));
        }
        if (path.endsWith("/") && !out.toString().endsWith("/")) out.append('/');
        return out.length() == 0 ? "/" : out.toString();
    }

    private static String encodeSegment(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int value = b & 0xff;
            if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9') || value == '-' || value == '_' || value == '.' || value == '~')
                out.append((char) value);
            else out.append('%').append(String.format(Locale.ROOT, "%02X", value));
        }
        return out.toString();
    }

    private void stage(String text) throws IOException {
        ConnectListener callback = listener;
        if (callback != null) callback.onStage(text);
    }

    private static String readBody(HttpURLConnection connection, int limit) throws IOException {
        InputStream in = null;
        try {
            in = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (in == null) return "";
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while (total < limit && (read = in.read(chunk)) > 0) {
                int allow = Math.min(read, limit - total);
                buffer.write(chunk, 0, allow);
                total += allow;
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private void requireStatus(HttpURLConnection connection, int expected, String action) throws IOException {
        requireStatusAny(connection, new int[]{expected}, action);
    }
    /** 接受一组成功状态码（PUT/MOVE/MKCOL 的成功码各服务器不完全一致，见 RFC 4918）。 */
    private void requireStatusAny(HttpURLConnection connection, int[] accepted, String action) throws IOException {
        int code = connection.getResponseCode();
        for (int ok : accepted) if (code == ok) return;
        String body = readBody(connection, 512);
        String message = action + "失败（HTTP " + code + "）";
        String hint = body == null ? "" : body.trim();
        if (hint.length() > 160) hint = hint.substring(0, 160);
        if (!hint.isEmpty()) message = message + "：" + hint;
        if (code == 401) throw new IOException(action + "失败：用户名或密码不正确");
        if (code == 404) throw new FileNotFoundException(message);
        if (code == 405) throw new IOException(action + "失败：服务器不支持该操作");
        if (code == 407) throw new IOException(action + "失败：需要代理认证");
        if (code == 423) throw new IOException(action + "失败：目标被锁定");
        if (code == 507) throw new IOException(action + "失败：服务器存储空间不足");
        throw new IOException(message);
    }

    @Override public void setConnectListener(ConnectListener listener) { this.listener = listener; }

    @Override public void connect() throws IOException {
        stage("正在连接服务器");
        try {
            String root = rootPrefix.isEmpty() ? "/" : rootPrefix + "/";
            HttpURLConnection probe = open("PROPFIND", root);
            try {
                probe.setRequestProperty("Depth", "0");
                probe.setDoOutput(true);
                probe.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
                byte[] body = PROPFIND_BODY;
                probe.setFixedLengthStreamingMode(body.length);
                OutputStream out = probe.getOutputStream();
                try { out.write(body); } finally { out.close(); }
                int code = probe.getResponseCode();
                if (code != 207 && code != 200) requireStatus(probe, 207, "连接");
            } finally {
                probe.disconnect();
            }
        } catch (IOException failure) {
            connected = false;
            // 首次连接的证书链被拒绝 → 交给调用方核对指纹后 withPin 重试（与 FTPS 同流程）。
            if (trust != null && trust.rejected && trust.offered != null
                    && (endpoint.certificatePin == null || endpoint.certificatePin.isEmpty()))
                throw new CertificateApprovalException(trust.offered, failure);
            throw failure;
        }
        connected = true;
        stage("正在读取目录");
    }

    @Override public boolean healthy() { return connected && !closed; }

    @Override public void checkIdle() throws IOException {
        if (closed) throw new IOException("会话已关闭");
        long idleMs = (System.nanoTime() - lastUse) / 1_000_000L;
        if (idleMs < 30000) return;
        probe();
    }

    @Override public void probe() throws IOException {
        if (closed) throw new IOException("会话已关闭");
        String root = rootPrefix.isEmpty() ? "/" : rootPrefix + "/";
        HttpURLConnection connection = open("PROPFIND", root);
        try {
            connection.setRequestProperty("Depth", "0");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
            byte[] body = PROPFIND_BODY;
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            try { out.write(body); } finally { out.close(); }
            int code = connection.getResponseCode();
            if (code != 207 && code != 200) {
                connected = false;
                requireStatus(connection, 207, "连接探测");
            }
        } catch (IOException failure) {
            connected = false;
            throw failure;
        } finally {
            connection.disconnect();
        }
    }

    @Override public String workingDirectory() throws IOException {
        return rootPrefix.isEmpty() ? "/" : rootPrefix;
    }

    @Override public List<RemoteEntry> list(String directory) throws IOException {
        String path = urlPath(directory);
        if (!path.endsWith("/")) path = path + "/";
        HttpURLConnection connection = open("PROPFIND", path);
        try {
            connection.setRequestProperty("Depth", "1");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
            byte[] body = PROPFIND_BODY;
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            try { out.write(body); } finally { out.close(); }
            requireStatus(connection, 207, "读取目录");
            String xml = readBody(connection, 8 * 1024 * 1024);
            List<RemoteEntry> entries = DavParser.parseMultiStatus(xml, rootPrefix);
            return filterChildren(entries, path);
        } finally {
            connection.disconnect();
        }
    }

    private List<RemoteEntry> filterChildren(List<RemoteEntry> entries, String collectionPath) {
        List<RemoteEntry> result = new ArrayList<>();
        String prefix = collectionPath.endsWith("/") ? collectionPath : collectionPath + "/";
        for (RemoteEntry entry : entries) {
            String entryPath = urlPath(entry.path);
            if (!entryPath.startsWith(prefix)) continue;
            String remainder = entryPath.substring(prefix.length());
            if (remainder.isEmpty() || remainder.contains("/")) continue;
            result.add(entry);
        }
        return result;
    }

    @Override public InputStream openStream(String remote, long offset) throws IOException {
        if (offset < 0) throw new IOException("偏移不能为负");
        HttpURLConnection connection = open("GET", urlPath(remote));
        try {
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = connection.getResponseCode();
            if (code == 416) throw new SeekNotSupportedException("服务器不支持从 " + offset + " 字节处继续读取");
            if (offset > 0 && code == 200) throw new SeekNotSupportedException("服务器不支持断点续读");
            if (code != 200 && code != 206) requireStatus(connection, 200, "读取文件");
            InputStream in = connection.getInputStream();
            connected = true;
            return new ConnectionInputStream(connection, in);
        } catch (IOException failure) {
            connection.disconnect();
            throw failure;
        }
    }

    @Override public long streamSize(String remote) throws IOException {
        HttpURLConnection connection = open("HEAD", urlPath(remote));
        try {
            requireStatus(connection, 200, "读取文件信息");
            String length = connection.getHeaderField("Content-Length");
            if (length == null) return -1;
            try { return Long.parseLong(length.trim()); } catch (NumberFormatException failure) { return -1; }
        } finally {
            connection.disconnect();
        }
    }

    @Override public void download(String remote, OutputStream output, Progress progress) throws IOException {
        InputStream in = null;
        try {
            in = openStream(remote, 0);
            byte[] chunk = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(chunk)) > 0) {
                output.write(chunk, 0, read);
                total += read;
                if (progress != null) progress.transferred(total);
            }
        } catch (IOException failure) {
            /* 读取失败/中途断连：把会话标记为不健康，调用方（RemoteSessions）下次
               不会再拿它去 probe/续传；否则 connected 仍是 true，healthy() 会骗过上层。 */
            connected = false;
            throw failure;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    @Override public void upload(String directory, String name, InputStream input, Progress progress) throws IOException {
        String parent = urlPath(directory);
        String target = childPath(parent, name);
        String temporary = childPath(parent, ".cjfm-" + UUID.randomUUID() + ".part");
        boolean committed = false;
        try {
            stage("正在上传");
            HttpURLConnection connection = open("PUT", temporary);
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(64 * 1024);
            OutputStream out = connection.getOutputStream();
            try {
                byte[] chunk = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                    total += read;
                    if (progress != null) progress.transferred(total);
                }
            } finally {
                out.close();
            }
            requireStatusAny(connection, new int[]{200, 201, 204}, "上传");
            connection.disconnect();
            moveReplace(temporary, target);
            committed = true;
        } finally {
            if (!committed && !temporary.isEmpty()) FailedUploadCleanup.schedule(endpoint, unmap(temporary));
        }
    }

    /** urlPath 的逆映射：把含 root 前缀的 URL 路径还原成协议中立路径（供清理任务使用）。 */
    private String unmap(String mapped) {
        if (!rootPrefix.isEmpty() && mapped.startsWith(rootPrefix)) {
            String rest = mapped.substring(rootPrefix.length());
            return rest.isEmpty() ? "/" : rest;
        }
        return mapped;
    }

    private void moveReplace(String from, String to) throws IOException {
        HttpURLConnection connection = open("MOVE", from);
        try {
            /* Destination 必须与实际请求同 scheme：明文 http 连接写 https:// 的 Destination，
               服务器（尤其带反代/校验的）会拒绝或改写目标。跟随 secure 决定 scheme。 */
            String scheme = secure ? "https" : "http";
            boolean defaultPort = secure ? endpoint.port == 443 : endpoint.port == 80;
            String base = scheme + "://" + endpoint.host + (defaultPort ? "" : ":" + endpoint.port);
            connection.setRequestProperty("Destination", base + encodePath(to));
            connection.setRequestProperty("Overwrite", "T");
            /* MOVE 提交：RFC 4918 允许 201（新建）/ 204（覆盖）/ 200（带实体），都算成功。 */
            requireStatusAny(connection, new int[]{200, 201, 204}, "提交文件");
        } finally {
            connection.disconnect();
        }
    }

    @Override public void mkdir(String parent, String name) throws IOException {
        String path = childPath(urlPath(parent), name);
        HttpURLConnection connection = open("MKCOL", path);
        try {
            int code = connection.getResponseCode();
            if (code == 405) throw new IOException("新建文件夹失败：同名文件或文件夹已存在");
            requireStatus(connection, 201, "新建文件夹");
        } finally {
            connection.disconnect();
        }
    }

    @Override public void rename(String from, String parent, String name) throws IOException {
        String source = urlPath(from);
        String target = childPath(urlPath(parent), name);
        moveReplace(source, target);
    }

    @Override public void delete(String remote, boolean directory) throws IOException {
        String path = urlPath(remote);
        if (directory && !path.endsWith("/")) path = path + "/";
        HttpURLConnection connection = open("DELETE", path);
        try {
            int code = connection.getResponseCode();
            if (code == 404) throw new FileNotFoundException("删除失败：目标不存在");
            if (code == 409) throw new IOException("删除失败：请先清空该文件夹的内容");
            requireStatus(connection, 204, "删除");
        } finally {
            connection.disconnect();
        }
    }

    @Override public int mutationParallelism() { return 4; }

    @Override public void close() {
        closed = true;
        connected = false;
    }

    private static final byte[] PROPFIND_BODY = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:creationdate/><d:displayname/>" +
            "</d:prop></d:propfind>").getBytes(StandardCharsets.UTF_8);

    /** 关闭输入流即释放底层连接；会话 close() 不会打断已交付的流。 */
    private static final class ConnectionInputStream extends InputStream {
        private final HttpURLConnection connection;
        private final InputStream delegate;
        ConnectionInputStream(HttpURLConnection connection, InputStream delegate) {
            this.connection = connection; this.delegate = delegate;
        }
        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }
        @Override public int available() throws IOException { return delegate.available(); }
        @Override public void close() throws IOException {
            try { delegate.close(); } finally { connection.disconnect(); }
        }
    }

    /** Base64（无换行），避免为 Basic 认证额外引依赖。 */
    static final class Base64 {
        private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        static String encode(byte[] data) {
            StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
            for (int i = 0; i < data.length; i += 3) {
                int b0 = data[i] & 0xff;
                int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
                int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
                out.append(ALPHABET[b0 >> 2]);
                out.append(ALPHABET[((b0 & 0x03) << 4) | (b1 >> 4)]);
                out.append(i + 1 < data.length ? ALPHABET[((b1 & 0x0f) << 2) | (b2 >> 6)] : '=');
                out.append(i + 2 < data.length ? ALPHABET[b2 & 0x3f] : '=');
            }
            return out.toString();
        }
    }

    /** 极简 PROPFIND 多状态解析：只取需要的字段，容错处理属性顺序与命名空间前缀。 */
    static final class DavParser {
        static List<RemoteEntry> parseMultiStatus(String xml, String rootPrefix) {
            List<RemoteEntry> entries = new ArrayList<>();
            if (xml == null || xml.isEmpty()) return entries;
            int cursor = 0;
            while (true) {
                int start = xml.indexOf("<d:response", cursor);
                if (start < 0) start = xml.indexOf("<D:response", cursor);
                if (start < 0) start = xml.indexOf("<response", cursor);
                if (start < 0) break;
                int end = xml.indexOf("</d:response>", start);
                if (end < 0) end = xml.indexOf("</D:response>", start);
                if (end < 0) end = xml.indexOf("</response>", start);
                if (end < 0) break;
                String block = xml.substring(start, end);
                cursor = end + 3;
                RemoteEntry entry = parseResponse(block, rootPrefix);
                if (entry != null) entries.add(entry);
            }
            return entries;
        }

        private static RemoteEntry parseResponse(String block, String rootPrefix) {
            String href = firstText(block, "href");
            if (href == null) return null;
            href = href.trim();
            int scheme = href.indexOf("://");
            if (scheme >= 0) {
                int pathStart = href.indexOf('/', scheme + 3);
                href = pathStart < 0 ? "/" : href.substring(pathStart);
            }
            int query = href.indexOf('?');
            if (query >= 0) href = href.substring(0, query);
            int hash = href.indexOf('#');
            if (hash >= 0) href = href.substring(0, hash);
            try { href = java.net.URLDecoder.decode(href, "UTF-8"); } catch (Exception ignored) {}
            boolean directory = block.contains("<d:collection") || block.contains("<D:collection") || block.contains("<collection");
            String lengthText = firstText(block, "getcontentlength");
            long size = 0;
            if (lengthText != null) try { size = Long.parseLong(lengthText.trim()); } catch (NumberFormatException ignored) {}
            String modifiedText = firstText(block, "getlastmodified");
            long modified = parseHttpDate(modifiedText);
            String createdText = firstText(block, "creationdate");
            long created = parseIsoDate(createdText);
            String name = nameOf(href);
            if (name.isEmpty() && directory) return null;
            String path = stripRoot(href, rootPrefix);
            return new RemoteEntry(name, path, directory, size, modified, false, created);
        }

        private static String stripRoot(String href, String rootPrefix) {
            if (!rootPrefix.isEmpty() && href.startsWith(rootPrefix)) {
                String rest = href.substring(rootPrefix.length());
                return rest.isEmpty() ? "/" : rest;
            }
            return href.isEmpty() ? "/" : href;
        }

        private static String firstText(String block, String localName) {
            String[] prefixes = {"d:", "D:", ""};
            for (String prefix : prefixes) {
                int open = block.indexOf("<" + prefix + localName);
                if (open < 0) continue;
                int openEnd = block.indexOf('>', open);
                if (openEnd < 0) continue;
                if (block.charAt(openEnd - 1) == '/') continue;
                int close = block.indexOf("</" + prefix + localName + ">", openEnd);
                if (close < 0) continue;
                return block.substring(openEnd + 1, close);
            }
            return null;
        }

        private static long parseHttpDate(String text) {
            if (text == null) return 0;
            String value = text.trim();
            if (value.isEmpty()) return 0;
            String[] patterns = {
                    "EEE, dd MMM yyyy HH:mm:ss zzz",
                    "EEEE, dd-MMM-yy HH:mm:ss zzz",
                    "EEE MMM d HH:mm:ss yyyy"
            };
            for (String pattern : patterns) {
                try {
                    SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                    Date date = format.parse(value);
                    return date.getTime();
                } catch (Exception ignored) {}
            }
            return 0;
        }

        private static long parseIsoDate(String text) {
            if (text == null) return 0;
            String value = text.trim();
            if (value.isEmpty()) return 0;
            try {
                String normalized = value.length() > 19 && (value.endsWith("Z") || value.indexOf('+') > 10)
                        ? value.replace("Z", "+0000") : value;
                if (normalized.length() >= 19 && normalized.charAt(10) == 'T') {
                    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
                    return format.parse(normalized).getTime();
                }
            } catch (Exception ignored) {}
            return 0;
        }
    }
}
