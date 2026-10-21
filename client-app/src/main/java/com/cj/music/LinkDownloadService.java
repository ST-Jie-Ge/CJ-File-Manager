package com.cj.music;

import android.app.*;
import android.content.*;
import android.os.*;
import com.cj.music.resources.R;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class LinkDownloadService extends Service {
    static final String CHANNEL="link-downloads";
    /* 同时下载上限：磁力 2 个 + ed2k 2 个，共 4 个；暂停 / 完成 / 失败的不占名额。 */
    static final int MAX_MAGNET=2,MAX_ED2K=2;
    private static final int NOTIFICATION=702;
    static volatile String snapshot="",notice="";
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final List<LinkDownloadStore.Task> tasks=new ArrayList<>();
    private LinkDownloadStore store;private LinkDownloadEngine engine;
    private PowerManager.WakeLock wake;private long persisted;private volatile int lastStartId;private final java.util.concurrent.atomic.AtomicInteger commands=new java.util.concurrent.atomic.AtomicInteger();
    static void command(Context c,String action,String id,String link,String directory){Intent i=new Intent(c,LinkDownloadService.class).setAction(action).putExtra("id",id).putExtra("link",link).putExtra("directory",directory);c.startForegroundService(i);}
    @Override public void onCreate(){super.onCreate();NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,"链接下载",NotificationManager.IMPORTANCE_LOW));startForeground(NOTIFICATION,notification("正在准备下载任务"));store=new LinkDownloadStore(this);engine=new LinkDownloadEngine(this);wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":link-download");wake.setReferenceCounted(false);
        /* 恢复上次的活动任务时同样按名额裁剪：超出的保持暂停，不自动挤掉正在跑的。 */
        worker.execute(()->{tasks.addAll(store.read());int magnets=0,ed2ks=0;
            for(LinkDownloadStore.Task t:tasks)if(t.active()){
                if(magnet(t)){if(magnets>=MAX_MAGNET){t.state="暂停";t.error=limitMessage(true);t.rate=0;continue;}magnets++;}
                else{if(ed2ks>=MAX_ED2K){t.state="暂停";t.error=limitMessage(false);t.rate=0;continue;}ed2ks++;}
                try{engine.start(t);}catch(Exception|LinkageError e){fail(t,e);}}
            publish(true);});
        worker.scheduleWithFixedDelay(()->{try{engine.poll(tasks);publish(false);}catch(Exception|LinkageError e){notice="下载任务更新失败："+message(e);}},1,1,TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent i,int flags,int startId){lastStartId=startId;if(i!=null){commands.incrementAndGet();String action=i.getAction(),id=i.getStringExtra("id"),link=i.getStringExtra("link"),directory=i.getStringExtra("directory");worker.execute(()->{
        try{
            String limit=null;
            if("add".equals(action)){DownloadLink parsed=DownloadLink.parse(link);if(full(parsed.magnet,null))throw new IOException(limitMessage(parsed.magnet));for(LinkDownloadStore.Task t:tasks)if(DownloadLink.parse(t.link).key.equals(parsed.key)&&DownloadLink.parse(t.link).magnet==parsed.magnet)throw new IOException("此下载任务已存在");if(tasks.size()>=100)throw new IOException("下载记录已满，请移除旧记录");File root=new File(directory).getCanonicalFile();if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法访问下载目录");LinkDownloadStore.Task t=new LinkDownloadStore.Task(parsed,root);tasks.add(t);store.write(tasks);try{engine.start(t);}catch(Exception|LinkageError e){fail(t,e);}}
            else if("pause_all".equals(action)){for(LinkDownloadStore.Task t:tasks)if(t.active())engine.pause(t);}
            else if("settings".equals(action))engine.applyPrefs(); /* 编辑页保存后即时应用代理与 tracker */
            else for(Iterator<LinkDownloadStore.Task> iterator=tasks.iterator();iterator.hasNext();){LinkDownloadStore.Task t=iterator.next();if(!t.id.equals(id))continue;
                if("pause".equals(action))engine.pause(t);
                else if("resume".equals(action)){ /* 续传也受名额限制：满了就保持暂停，并给出提示 */
                    if(full(magnet(t),t)){t.state="暂停";t.error=limitMessage(magnet(t));t.rate=0;limit=t.error;}
                    else try{engine.start(t);}catch(Exception|LinkageError e){fail(t,e);}}
                else if("remove".equals(action)){engine.remove(t);iterator.remove();}break;}
            notice=limit==null?"":limit;publish(true);
        }catch(Exception|LinkageError e){notice=message(e);publish(true);}finally{commands.decrementAndGet();}
    });}return START_STICKY;}
    private static String message(Throwable e){if(e instanceof LinkageError)return "下载引擎无法加载，请重新安装应用";return e.getMessage()==null?"下载失败，请重试":e.getMessage();}
    private static boolean magnet(LinkDownloadStore.Task t){return t.link.startsWith("magnet:");}
    /** 同名额是否已满（skip 排除自己：续传时自己本来就不在活动里，这里只是保险）。 */
    private boolean full(boolean isMagnet,LinkDownloadStore.Task skip){int count=0;
        for(LinkDownloadStore.Task t:tasks){if(t==skip||!t.active()||magnet(t)!=isMagnet)continue;count++;}
        return count>=(isMagnet?MAX_MAGNET:MAX_ED2K);}
    private String limitMessage(boolean isMagnet){return "同时最多下载 "+(isMagnet?MAX_MAGNET:MAX_ED2K)+" 个"+(isMagnet?"磁力":"ed2k")+"任务，请先暂停或移除其他任务";}
    private static void fail(LinkDownloadStore.Task t,Throwable e){t.state="失败";t.error=message(e);t.rate=0;}
    private void publish(boolean force){try{
        JSONArray a=new JSONArray();int active=0;long rate=0;for(LinkDownloadStore.Task t:tasks){a.put(t.json());if(t.active()){active++;rate+=t.rate;}}long now=SystemClock.elapsedRealtime();if(force||now-persisted>5000){store.write(tasks);persisted=now;}snapshot=a.toString();
        if(active>0){if(!wake.isHeld())wake.acquire(10*60*1000L);getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(active+" 个任务 · "+android.text.format.Formatter.formatShortFileSize(this,rate)+"/s"));}
        else {if(wake.isHeld())wake.release();if(commands.get()==0&&lastStartId>0)stopSelfResult(lastStartId);}
    }catch(Exception e){notice="下载记录保存失败";}}
    /* 旧的全屏下载页已删除：通知统一回到侧边栏的「磁力／ed2k 下载」页。 */
    private Notification notification(String content){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).putExtra("open_downloads",true).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent pause=PendingIntent.getService(this,1,new Intent(this,LinkDownloadService.class).setAction("pause_all"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.client_bottom_icon_download).setContentTitle("磁力／ed2k 下载").setContentText(content).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).addAction(new Notification.Action.Builder(null,"全部暂停",pause).build()).build();}
    @Override public void onDestroy(){
        /* 停机前必须把「暂停 + 进度落盘 + 引擎收尾」跑完：以前把这段塞进 worker 再立即
           shutdown()，Service 进程随后被回收时任务可能还停在队列里，磁力/ed2k 进度就丢了。
           现在提交后带超时等待，超时才 shutdownNow。 */
        java.util.concurrent.Future<?> flush=worker.submit(()->{try{for(LinkDownloadStore.Task t:tasks)if(t.active())try{engine.pause(t);}catch(Exception ignored){}store.write(tasks);engine.close();}catch(Exception|LinkageError ignored){}finally{if(wake.isHeld())wake.release();}});
        try{flush.get(3,TimeUnit.SECONDS);}catch(Exception ignored){Thread.currentThread().interrupt();}
        worker.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
