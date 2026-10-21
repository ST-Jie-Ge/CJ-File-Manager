package com.cj.music;

import java.io.*;
import java.nio.file.*;

/** Writes complete content first; a new destination is always created exclusively. */
final class SafeFileOperations {
    interface Writer { void write(OutputStream output) throws Exception; }
    static void writeNew(File target,Writer writer)throws Exception {
        File parent=target.getAbsoluteFile().getParentFile();
        directory(parent);
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("目标已有同名文件："+target.getName());
        File temporary=File.createTempFile(".cjfm-", ".tmp",parent);
        try {
            try(OutputStream output=new BufferedOutputStream(new FileOutputStream(temporary),128*1024)){writer.write(output);}
            publishNew(temporary,target);
        }finally{temporary.delete();}
    }
    /** 与 writeNew 一样先写临时文件再落位，但允许覆盖已存在的目标（解压时同名文件用）。 */
    static void writeReplacing(File target,Writer writer)throws Exception {
        File parent=target.getAbsoluteFile().getParentFile();
        directory(parent);
        File temporary=File.createTempFile(".cjfm-", ".tmp",parent);
        try {
            try(OutputStream output=new BufferedOutputStream(new FileOutputStream(temporary),128*1024)){writer.write(output);}
            try{Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            catch(IOException|UnsupportedOperationException fallback){
                // 某些挂载点（FUSE 外部存储）不支持 move 覆盖，退回「截断重写」。
                try(OutputStream out=Files.newOutputStream(target.toPath(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
                    InputStream in=new FileInputStream(temporary)){byte[] buffer=new byte[128*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
            }
        }finally{temporary.delete();}
    }
    static void directory(File directory)throws IOException {
        if(directory==null||(!directory.isDirectory()&&!directory.mkdirs()))throw new IOException("无法创建目录："+directory);
    }
    static void publishNew(File temporary,File target)throws IOException {
        try {
            Files.createLink(target.toPath(),temporary.toPath());
            return;
        }catch(FileAlreadyExistsException exists){throw exists;}
        catch(UnsupportedOperationException|IOException unsupported){
            // Shared Android storage may not support hard links. CREATE_NEW still prevents overwrite.
        }
        try(OutputStream out=Files.newOutputStream(target.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            InputStream in=new FileInputStream(temporary)) {
            byte[] buffer=new byte[128*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }catch(FileAlreadyExistsException exists){throw exists;}
        catch(IOException failure){throw new IOException("提交失败，可能留下不完整文件，请检查："+target.getName(),failure);}
    }
    static void renameNew(File source,File target)throws Exception {
        if(source.getAbsolutePath().equals(target.getAbsolutePath()))return;
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("目标已有同名文件："+target.getName());
        if(source.isDirectory())Files.move(source.toPath(),target.toPath());
        else {
            publishNew(source,target);
            if(!source.delete())throw new IOException("新文件已生成，但无法删除原文件："+source.getName());
        }
    }
    /** 落位一个已写完的临时文件。优先原子 rename；某些挂载点不支持原子 move，退回普通 move。 */
    static void replace(File temporary,File target)throws IOException {
        try{Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(java.nio.file.AtomicMoveNotSupportedException unsupported){
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
