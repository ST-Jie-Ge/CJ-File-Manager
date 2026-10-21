package com.cj.music;
import android.content.Context;import android.net.Uri;import java.io.*;import java.nio.charset.StandardCharsets;
final class DownloadInput {
 static String text(String value)throws Exception{if(value==null||value.length()>65536)throw new IOException("没有有效的下载链接");java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:magnet:\\?[^\\s<>\\\"]+|ed2k://[^\\r\\n]+?/)").matcher(value);if(!m.find())throw new IOException("没有找到磁力或 ed2k 链接");return DownloadLink.parse(m.group()).uri;}
 static String read(Context context,Uri uri)throws Exception{String scheme=uri.getScheme();if("magnet".equals(scheme)||"ed2k".equals(scheme))return DownloadLink.parse(uri.toString()).uri;if(!"content".equals(scheme)&&!"file".equals(scheme))throw new IOException("不支持的链接文件");
  byte[] bytes;try(InputStream in="file".equals(scheme)?new FileInputStream(new File(uri.getPath())):context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("无法读取文件");byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){if(out.size()+n>8*1024*1024)throw new IOException("链接文件过大");out.write(block,0,n);}bytes=out.toByteArray();}
  if(bytes.length>0&&bytes[0]=='d'){org.libtorrent4j.TorrentInfo info=new org.libtorrent4j.TorrentInfo(bytes);if(!info.isValid())throw new IOException("种子文件无效");org.libtorrent4j.AddTorrentParams p=new org.libtorrent4j.AddTorrentParams();p.setTorrentInfo(info);p.setInfoHashes(info.infoHashes());return DownloadLink.parse(org.libtorrent4j.swig.libtorrent.make_magnet_uri(p.swig())).uri;}
  return text(new String(bytes,StandardCharsets.UTF_8));
 }
}
