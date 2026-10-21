package com.cj.music;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Stable share copies, independent of preview lifetime. Reuse identical content and filename. */
final class ImageExportCache {
    static synchronized File export(File cache,File source,String name)throws Exception{
        String filename=new File(name).getName();
        MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(filename.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);
        File staging=new File(new File(cache,"client-exports"),UUID.randomUUID().toString()),prepared=new File(staging,filename);
        try{
            SafeFileOperations.writeNew(prepared,out->{byte[] buffer=new byte[128*1024];try(InputStream input=new FileInputStream(source)){int n;while((n=input.read(buffer))!=-1){out.write(buffer,0,n);hash.update(buffer,0,n);}}});
            File folder=new File(new File(cache,"client-exports"),UUID.nameUUIDFromBytes(hash.digest()).toString());
            File target=new File(folder,filename);SafeFileOperations.directory(folder);
            if(!target.isFile())try{SafeFileOperations.publishNew(prepared,target);}
            catch(java.nio.file.FileAlreadyExistsException race){if(!target.isFile())throw race;}
            catch(IOException failure){target.delete();folder.delete();throw failure;}
            folder.setLastModified(System.currentTimeMillis());return target;
        }finally{prepared.delete();staging.delete();}
    }
}
