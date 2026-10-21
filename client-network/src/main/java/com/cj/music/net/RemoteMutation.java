package com.cj.music.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Retry read-only preparation, never replay a write whose server reply was lost. */
public final class RemoteMutation {
    public interface Access { RemoteSession session() throws IOException; void discard(); }
    public enum Kind { MKDIR, RENAME, DELETE }
    private RemoteMutation(){}
    private static RemoteSession prepare(Access access)throws IOException{
        for(int attempt=0;;attempt++)try{RemoteSession session=access.session();session.probe();return session;}
        catch(IOException e){access.discard();if(attempt>0||!RemoteFailures.disconnected(e))throw e;}
    }
    private static List<RemoteEntry> read(Access access,String parent)throws IOException{
        for(int attempt=0;;attempt++)try{return access.session().list(parent);}
        catch(IOException e){access.discard();if(attempt>0||!RemoteFailures.disconnected(e))throw e;}
    }
    private static RemoteEntry find(List<RemoteEntry> entries,String name){for(RemoteEntry entry:entries)if(entry.name.equals(name))return entry;return null;}
    /** 异常文案兜底：getMessage() 为空的异常（NPE 等）不能把失败显示成空白。 */
    private static String text(Throwable error){
        String message=error.getMessage();
        return message==null||message.trim().isEmpty()?error.getClass().getSimpleName():message;
    }
    /** Returns a verified-state notice when the original write response was lost. */
    public static String run(Access access,Kind kind,String parent,String from,String name,boolean directory)throws IOException{
        RemoteSession session=prepare(access);
        // Resolve the current link flag, rather than trusting a stale UI listing for deletion.
        RemoteEntry source=null;
        if(kind==Kind.DELETE){
            source=find(read(access,parent),name);
            if(source==null)return "已核验："+name+" 已不存在";
            session=prepare(access);
        }
        try{
            switch(kind){
                case MKDIR:session.mkdir(parent,name);break;
                case RENAME:session.rename(from,parent,name);break;
                case DELETE:session.delete(from,source.directory&&!source.link);break;
            }
            return null;
        }catch(IOException error){
            access.discard();if(!RemoteFailures.disconnected(error))throw error;
            try{
                List<RemoteEntry> entries=read(access,parent);
                RemoteEntry target=find(entries,name);
                if(kind==Kind.DELETE&&target==null)return "连接中断后已核验："+name+" 已不存在";
                String state=target==null?"目标未找到":"目标现已存在";
                if(kind==Kind.RENAME){String old=from.substring(from.lastIndexOf('/')+1);state+="，原名称"+(find(entries,old)==null?"已不存在":"仍存在");}
                throw new IOException("操作应答丢失，结果不确定（"+state+"）；未重复执行，请确认目录后再操作",error);
            }catch(IOException verification){if(verification.getCause()==error)throw verification;error.addSuppressed(verification);throw new IOException("操作应答丢失且核验失败；未重复执行，请刷新后确认结果",error);}
        }
    }

    /* ---------- 批量删除 ---------- */

    /** 批量删除的一项。uri 是协议中立绝对路径；name 用于在实时目录列表里定位（也进提示文案）。 */
    public static class Item {
        public final String uri,name;public final boolean directory;
        public Item(String uri,String name,boolean directory){this.uri=uri;this.name=name;this.directory=directory;}
    }

    /** 批量进度：每完成一项回调一次（完成 / 失败 / 跳过 / 未处理都算「离开队列」），
     *  可能在任意 worker 线程上——实现方自行切主线程。 */
    public interface Progress {void onProgress(int completed,int total,String currentName);}

    /**
     * 同目录批量删除：整个批次只列一次目录、只探活一次，然后逐项删除（会话支持时可并发）。
     *
     * 为什么不循环调 N 次 {@link #run}(…,Kind.DELETE,…)：那样每一项都要重新列一遍父目录
     * ——run() 要为「是不是链接」做实时解析——删 N 个文件等于把整个目录枚举 N 遍，
     * 大目录下这就是主要耗时；而且每项前面还各有一次探活往返。本方法把这两笔
     * 一次性开销摊到整批，SMB 上再按 mutationParallelism() 并发发删除请求。
     *
     * 安全语义与单项版一致：
     *  · 链接属性以实时列表为准，不信任 UI 上的过期 isDirectory；
     *  · 列表里找不到的项算「已不存在」，进提示文案，不算失败；
     *  · 单项失败（权限不足等）不中断批次，累计到最后统一报；
     *  · 连接断开时绝不盲发剩余项：重新列一次目录做事后核验，把
     *    「已不在目录（应已删除）/ 仍在服务器（未生效或未处理）」分开报给用户，
     *    由用户刷新确认后自行决定是否重来——宁可让人重试，不可悄悄重放写操作。
     */
    public static String deleteBatch(Access access,String parent,List<Item> items,Progress progress)throws IOException{
        RemoteSession session=prepare(access);
        List<RemoteEntry> listing=read(access,parent);
        final int total=items.size();
        final List<String> absent=new ArrayList<>();                                  // 仅提交线程写
        final List<String> failures=Collections.synchronizedList(new ArrayList<>());  // 多 worker 写
        final AtomicBoolean disconnected=new AtomicBoolean(false);
        final AtomicBoolean aborted=new AtomicBoolean(false);
        final AtomicInteger done=new AtomicInteger(0);
        final AtomicInteger success=new AtomicInteger(0);
        int workers=Math.max(1,Math.min(session.mutationParallelism(),total));
        ExecutorService pool=workers>1
                ?Executors.newFixedThreadPool(workers,r->{Thread thread=new Thread(r,"remote-delete");thread.setDaemon(true);return thread;})
                :null;
        try{
            for(final Item item:items){
                if(Thread.currentThread().isInterrupted()){aborted.set(true);break;}
                final RemoteEntry source=find(listing,item.name);
                if(source==null){absent.add(item.name);progress.onProgress(done.incrementAndGet(),total,item.name);continue;}
                final boolean directory=source.directory&&!source.link;
                Runnable task=()->{
                    try{
                        // 链路已断或被要求中止：不再发，留给事后核验去说清结果
                        if(disconnected.get()||aborted.get()||Thread.currentThread().isInterrupted()){aborted.set(true);return;}
                        session.delete(item.uri,directory);
                        success.incrementAndGet();
                    }catch(IOException error){
                        failures.add(item.name+"："+text(error));
                        if(RemoteFailures.disconnected(error))disconnected.set(true);
                    }catch(RuntimeException error){
                        failures.add(item.name+"："+text(error));
                    }finally{
                        progress.onProgress(done.incrementAndGet(),total,item.name);
                    }
                };
                if(pool!=null)pool.submit(task);else task.run();
            }
            if(pool!=null){
                pool.shutdown();
                try{pool.awaitTermination(10,TimeUnit.MINUTES);}
                catch(InterruptedException e){Thread.currentThread().interrupt();aborted.set(true);pool.shutdownNow();}
            }
        }finally{if(pool!=null)pool.shutdownNow();}
        /* ---- 汇总 ---- */
        if(disconnected.get()){
            access.discard();
            List<String> gone=new ArrayList<>(),still=new ArrayList<>();
            try{
                List<RemoteEntry> after=read(access,parent);
                for(Item item:items){
                    if(absent.contains(item.name))continue;
                    (find(after,item.name)==null?gone:still).add(item.name);
                }
            }catch(IOException verification){
                IOException wrapped=new IOException("删除中断：已完成 "+success.get()+" / "+total+" 项；事后核验也失败，请刷新目录确认结果（未重复执行任何操作）",verification);
                for(String failure:failures)wrapped.addSuppressed(new IOException(failure));
                throw wrapped;
            }
            StringBuilder summary=new StringBuilder("删除中断：已完成 "+success.get()+" / "+total+" 项");
            if(!gone.isEmpty())summary.append("；核验确认已删除：").append(String.join("、",gone));
            if(!still.isEmpty())summary.append("；仍在服务器（未生效或未处理）：").append(String.join("、",still));
            summary.append("。未重复执行任何操作，请刷新目录确认后再决定是否重试。");
            throw new IOException(summary.toString());
        }
        if(!failures.isEmpty()||(aborted.get()&&success.get()+absent.size()<total))
            throw new IOException("已完成 "+success.get()+" / "+total+" 项"+(failures.isEmpty()?"":"\n"+String.join("\n",failures)));
        if(!absent.isEmpty())return "已核验："+String.join("、",absent)+" 已不存在，其余 "+success.get()+" 项已删除";
        return null;
    }
}
