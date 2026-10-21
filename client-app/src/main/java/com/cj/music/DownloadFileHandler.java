package com.cj.music;

import org.dkf.jed2k.disk.DesktopFileHandler;
import org.dkf.jed2k.exception.*;
import java.io.*;
import java.nio.channels.FileChannel;

/** Random writes must neither truncate a resumed file nor force O_APPEND. */
final class DownloadFileHandler extends DesktopFileHandler {
    private RandomAccessFile read,write;
    DownloadFileHandler(File f){super(f);}
    @Override public synchronized FileChannel getWriteChannel()throws JED2KException{try{if(write==null)write=new RandomAccessFile(file,"rw");return write.getChannel();}catch(IOException e){throw new JED2KException(ErrorCode.IO_EXCEPTION);}}
    @Override public synchronized FileChannel getReadChannel()throws JED2KException{try{if(read==null)read=new RandomAccessFile(file,"r");return read.getChannel();}catch(IOException e){throw new JED2KException(ErrorCode.IO_EXCEPTION);}}
    @Override public synchronized void closeChannels(){try{if(read!=null)read.close();}catch(IOException ignored){}try{if(write!=null)write.close();}catch(IOException ignored){}read=null;write=null;super.closeChannels();}
}
