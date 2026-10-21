package com.cj.music;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;
import com.cj.music.net.Endpoint;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

public class FavoriteGuardUiTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private Object field(Object instance,String name)throws Exception{Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
    private void openPage(MainActivity activity)throws Exception{Method open=MainActivity.class.getDeclaredMethod("openPage",String.class,View.class);open.setAccessible(true);open.invoke(activity,"已收藏的目录",new LinearLayout(activity));((DrawerLayout)field(activity,"drawer")).openDrawer(GravityCompat.START,false);}
    private void favorite(MainActivity activity,String profile)throws Exception{Method method=MainActivity.class.getDeclaredMethod("openFavorite",String.class,String.class,String.class);method.setAccessible(true);method.invoke(activity,"/storage/emulated/0","test",profile);}
    private void assertPage(MainActivity activity)throws Exception{assertEquals("已收藏的目录",field(activity,"currentPage"));assertTrue(((DrawerLayout)field(activity,"drawer")).isDrawerOpen(GravityCompat.START));}
    @Test public void fullLocalAndRemoteTabsKeepFavoritePageOpen()throws Exception{
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{ArrayList<Object> tabs=null,saved=null;String id="favorite-guard-test";try{
            tabs=(ArrayList<Object>)field(activity,"tabs");saved=new ArrayList<>(tabs);
            Method max=MainActivity.class.getDeclaredMethod("maxAllTabs");max.setAccessible(true);int limit=(Integer)max.invoke(activity);while(tabs.size()<limit)tabs.add(saved.get(0));
            openPage(activity);favorite(activity,"");assertPage(activity);assertEquals(limit,tabs.size());
            ConnectionStore store=new ConnectionStore(activity);store.save(new ConnectionStore.Profile(id,"test","/",new Endpoint("127.0.0.1",21,Endpoint.Protocol.FTP,"test","test","UTF-8","")));
            favorite(activity,id);assertPage(activity);assertEquals(limit,tabs.size());assertFalse((Boolean)field(activity,"connecting"));
        }catch(Exception e){throw new RuntimeException(e);}finally{if(tabs!=null&&saved!=null){tabs.clear();tabs.addAll(saved);}new ConnectionStore(activity).remove(id);activity.finish();}});
    }
    @Test public void missingConnectionKeepsFavoritePageOpen()throws Exception{
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{try{openPage(activity);favorite(activity,"missing-favorite-connection");assertPage(activity);}catch(Exception e){throw new RuntimeException(e);}finally{activity.finish();}});
    }
}
