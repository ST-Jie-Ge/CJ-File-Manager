package com.cj.music;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** Local side of network transfers; paths and content URIs have different readers. */
final class LocalTransferFiles {
    private LocalTransferFiles() { }
    static InputStream openUpload(ContentResolver resolver,String source)throws IOException {
        if(source.startsWith("/"))return new FileInputStream(source);
        InputStream input=resolver.openInputStream(Uri.parse(source));
        if(input==null)throw new IOException("无法读取上传文件");
        return input;
    }
    interface Cancellation { void check() throws IOException; }
    static final class IncompleteCommitException extends IOException {
        IncompleteCommitException(File target,IOException cause) {
            super("文件提交未完成："+target.getName()+"。目标位置可能保留不完整文件，请核对后手动处理。",cause);
        }
    }
    /** Android/FUSE rename can replace a competing file. CREATE_NEW reserves the name atomically. */
    static void commitDownload(File partial,File target,Cancellation cancellation)throws IOException {
        boolean created=false;
        try {
            cancellation.check();
            try(InputStream in=new FileInputStream(partial);
                OutputStream out=Files.newOutputStream(target.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                created=true;
                byte[] buffer=new byte[128*1024];int count;
                while((count=in.read(buffer))!=-1){cancellation.check();out.write(buffer,0,count);}
                out.flush();
            }
            cancellation.check();
        } catch(IOException failure) {
            // A pathname may now refer to another writer's replacement. Never delete it on failure.
            if(created)throw new IncompleteCommitException(target,failure);
            throw failure;
        }
    }
}
