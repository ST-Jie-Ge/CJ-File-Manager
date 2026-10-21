package com.cj.music.net;

import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;
import java.io.*;

public final class TransferTestRunner extends AndroidJUnitRunner {
    @Override public void onCreate(Bundle arguments) {
        try {
            for(String name:new String[]{"localhost.p12","localhost-dns.p12"}) {
                File certificate=new File(getTargetContext().getCacheDir(),name);
                try(InputStream in=getContext().getAssets().open(name);OutputStream out=new FileOutputStream(certificate)) {
                    byte[] buffer=new byte[8192];int count;
                    while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
                }
                System.setProperty(name.equals("localhost.p12")?"test.keystore":"test.dns.keystore",certificate.getAbsolutePath());
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        super.onCreate(arguments);
    }
}
