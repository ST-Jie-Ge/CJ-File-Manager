package com.cj.music;

import java.io.*;
import java.util.*;

/** Private originals owned by one preview; never expose these short-lived paths to other apps. */
final class PreviewFileCache implements AutoCloseable {
    private static final Set<String> live=new HashSet<>();
    private final File root;
    private final Set<File> owned=new HashSet<>();
    private boolean closed;
    PreviewFileCache(File cache){root=new File(new File(cache,"client-previews"),UUID.randomUUID().toString());synchronized(live){live.add(root.getAbsolutePath());}}
    File write(String name,SafeFileOperations.Writer writer)throws Exception{
        File folder=new File(root,UUID.randomUUID().toString());
        File target=new File(folder,new File(name).getName());
        synchronized(this){if(closed)throw new InterruptedIOException("预览已关闭");owned.add(folder);}
        try{
            SafeFileOperations.writeNew(target,out->{synchronized(this){if(closed)throw new InterruptedIOException("预览已关闭");}writer.write(out);});
            synchronized(this){if(closed)throw new InterruptedIOException("预览已关闭");}
            return target;
        }catch(Exception|Error failure){release(target);throw failure;}
    }
    void retain(File target){List<File> removed=new ArrayList<>();synchronized(this){for(File folder:owned)if(target==null||!folder.equals(target.getParentFile()))removed.add(folder);owned.removeAll(removed);}for(File folder:removed)deleteFolder(folder);}
    void release(File target){if(target==null)return;File folder=target.getParentFile();if(folder==null||!root.equals(folder.getParentFile()))return;synchronized(this){owned.remove(folder);}deleteFolder(folder);root.delete();}
    @Override public void close(){synchronized(this){closed=true;}retain(null);root.delete();synchronized(live){live.remove(root.getAbsolutePath());}}
    static void cleanOrphans(File cache){
        File[] roots=new File(cache,"client-previews").listFiles();if(roots==null)return;
        synchronized(live){for(File root:roots){if(!root.getName().matches("[a-fA-F0-9-]{36}")||live.contains(root.getAbsolutePath()))continue;File[] folders=root.listFiles();if(folders!=null)for(File folder:folders)if(folder.getName().matches("[a-fA-F0-9-]{36}"))deleteFolder(folder);root.delete();}}
    }
    private static void deleteFolder(File folder){File[] files=folder.listFiles();if(files!=null)for(File file:files)if(file.isFile())file.delete();folder.delete();}
}
