package com.cj.music.net;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;
import com.hierynomus.smbj.share.Share;
import com.rapid7.client.dcerpc.Interface;
import com.rapid7.client.dcerpc.transport.RPCTransport;
import java.io.EOFException;
import java.io.IOException;
import java.util.EnumSet;

/** Uses SMBJ 0.13's public pipe API, never the RPC library's SMBJ 0.10 helpers. */
final class SmbRpcTransport extends RPCTransport implements AutoCloseable {
    /** 持有 IPC$ 共享本身：只关管道会在服务端一直留着这条树连接，直到会话结束。 */
    private final Share ipc;
    private final NamedPipe pipe;
    private SmbRpcTransport(Share ipc,NamedPipe pipe){this.ipc=ipc;this.pipe=pipe;}

    static SmbRpcTransport connect(Session session)throws IOException{
        Share ipc=session.connectShare("IPC$");
        try {
            if(!(ipc instanceof PipeShare))throw new IOException("服务器 IPC$ 不是命名管道共享");
            PipeShare pipes=(PipeShare)ipc;
            NamedPipe pipe=pipes.open("srvsvc",SMB2ImpersonationLevel.Impersonation,
                    EnumSet.of(AccessMask.GENERIC_READ,AccessMask.GENERIC_WRITE),
                    EnumSet.noneOf(FileAttributes.class),SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,EnumSet.noneOf(SMB2CreateOptions.class));
            SmbRpcTransport transport=new SmbRpcTransport(ipc,pipe);
            try{transport.bind(Interface.SRVSVC_V3_0,Interface.NDR_32BIT_V2);return transport;}
            catch(IOException|RuntimeException error){transport.close();throw error;}
        }catch(IOException|RuntimeException error){
            try{ipc.close();}catch(IOException|RuntimeException ignored){}
            throw error;
        }
    }

    @Override public int transact(byte[] out,byte[] in)throws IOException{
        try{return complete(in,pipe.transact(out,in));}
        catch(RuntimeException error){throw new IOException("SMB 共享枚举请求失败",error);}
    }
    @Override public void write(byte[] out)throws IOException{
        try{int sent=0;while(sent<out.length){int n=pipe.write(out,sent,out.length-sent);if(n<=0)throw new EOFException("SMB 管道写入中断");sent+=n;}}
        catch(RuntimeException error){throw new IOException("SMB 管道写入失败",error);}
    }
    @Override public int read(byte[] in)throws IOException{
        try{return complete(in,pipe.read(in));}
        catch(RuntimeException error){throw new IOException("SMB 管道读取失败",error);}
    }
    private int complete(byte[] in,int used)throws IOException{
        if(in.length<16)throw new IOException("RPC 接收缓冲区过小");
        used=readUntil(in,used,16);
        boolean little=(in[4]&0x10)!=0;
        int length=little?((in[8]&255)|((in[9]&255)<<8)):(((in[8]&255)<<8)|(in[9]&255));
        if(length<16||length>in.length||used>length)throw new IOException("RPC 响应长度无效");
        return readUntil(in,used,length);
    }
    private int readUntil(byte[] in,int used,int needed)throws IOException{
        while(used<needed){int n=pipe.read(in,used,needed-used);if(n<=0)throw new EOFException("SMB 管道响应不完整");used+=n;}
        return used;
    }
    @Override public void close(){
        try{pipe.closeSilently();}catch(RuntimeException ignored){}
        try{ipc.close();}catch(IOException|RuntimeException ignored){}
    }
}
