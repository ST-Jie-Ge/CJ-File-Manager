package com.cj.music.net;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/** Local-only manual/emulator integration fixture. Never packaged into the Android app. */
public final class SmokeServer {
    public static void main(String[] args)throws Exception {
        FtpSessionTest.Fixture ftp=new FtpSessionTest.Fixture(false),explicit=new FtpSessionTest.Fixture(false),implicit=new FtpSessionTest.Fixture(true);
        for(FtpSessionTest.Fixture f:new FtpSessionTest.Fixture[]{ftp,explicit,implicit}){f.files.put("/hello.txt","Hello FTP client\n".getBytes(StandardCharsets.UTF_8));f.files.put("/中文 文件.txt","中文 UTF-8\n".getBytes(StandardCharsets.UTF_8));f.directories.add("/folder");f.files.put("/folder/nested.txt",new byte[]{1,2,3});}
        String config="ftp="+ftp.port()+"\nexplicit="+explicit.port()+"\nimplicit="+implicit.port()+"\npin="+explicit.pin+"\n";
        Files.write(Paths.get(args[0]),config.getBytes(StandardCharsets.UTF_8));
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{ftp.close();explicit.close();implicit.close();}catch(Exception ignored){}}));
        System.out.println("Loopback smoke fixtures ready");new CountDownLatch(1).await();
    }
}
