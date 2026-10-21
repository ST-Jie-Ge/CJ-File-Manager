package com.cj.music;

import java.net.URLDecoder;
import java.util.Locale;

/** Validation never loads JNI, so malformed external intents are safe to inspect. */
final class DownloadLink {
    final String uri, key, name;
    final boolean magnet;
    final long size;
    private DownloadLink(String u,String k,String n,boolean m,long s){uri=u;key=k;name=n;magnet=m;size=s;}
    static DownloadLink parse(String input) throws Exception {
        String u=input.trim();
        if(u.length()>32768||u.indexOf('\n')>=0||u.indexOf('\r')>=0)throw new IllegalArgumentException("下载链接格式错误");
        if(u.startsWith("magnet:?")){
            String hash=null,name="磁力下载";
            for(String pair:u.substring(8).split("&")){int p=pair.indexOf('=');if(p<0)continue;String k=pair.substring(0,p),v=decode(pair.substring(p+1));
                if(k.equals("dn"))name=v;
                if(k.equals("xt")){
                    if(v.matches("(?i)urn:btih:([a-f0-9]{40}|[a-z2-7]{32})")){String h=v.substring(9);hash="urn:btih:"+(h.length()==32?hexBase32(h):h.toLowerCase(Locale.ROOT));}
                    else if(v.matches("(?i)urn:btmh:1220[a-f0-9]{64}"))hash=v.toLowerCase(Locale.ROOT);
                }
            }
            if(hash==null)throw new IllegalArgumentException("磁力链接缺少有效的文件哈希");
            return new DownloadLink(u,hash,safeName(name),true,0);
        }
        String[] p=u.split("\\|",-1);
        if(p.length<6||!p[0].equals("ed2k://")||!p[1].equals("file")||!p[p.length-1].equals("/")||!p[4].matches("(?i)[a-f0-9]{32}"))throw new IllegalArgumentException("请输入磁力链接或 ed2k 文件链接");
        long size=Long.parseLong(p[3]);if(size<=0||size>512L*1024*1024*1024)throw new IllegalArgumentException("文件大小无效");
        return new DownloadLink(u,p[4].toLowerCase(Locale.ROOT),safeName(decode(p[2])),false,size);
    }
    private static String decode(String s)throws Exception{return URLDecoder.decode(s.replace("+","%2B"),"UTF-8");}
    private static String hexBase32(String s){StringBuilder out=new StringBuilder();int bits=0,value=0;for(char c:s.toUpperCase(Locale.ROOT).toCharArray()){value=(value<<5)|"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);bits+=5;if(bits>=8){bits-=8;int b=(value>>bits)&255;out.append("0123456789abcdef".charAt(b>>4)).append("0123456789abcdef".charAt(b&15));}}return out.toString();}
    static String safeName(String name){
        String n=name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
        while(n.endsWith("."))n=n.substring(0,n.length()-1);
        if(n.isEmpty()||n.equals(".."))n="下载文件";
        while(n.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>180)n=n.substring(0,n.offsetByCodePoints(n.length(),-1));
        return n;
    }
}
