package com.cj.music;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.cj.music.resources.R;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SelectionToolbarUiTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object entry(String name)throws Exception{Class<?> type=Class.forName(MainActivity.class.getName()+"$Entry");Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object entry=ctor.newInstance();for(String key:new String[]{"uri","name","mime"}){Field f=type.getDeclaredField(key);f.setAccessible(true);f.set(entry,key.equals("uri")?"/test-only/"+name:key.equals("name")?name:"text/plain");}return entry;}
    private View action(MainActivity activity,int text)throws Exception{LinearLayout actions=(LinearLayout)field(activity,"actions");for(int i=0;i<actions.getChildCount();i++)if(activity.getString(text).contentEquals(actions.getChildAt(i).getContentDescription()))return actions.getChildAt(i);throw new AssertionError("Missing toolbar action");}
    private void prepare(MainActivity activity)throws Exception{
        Object permission=field(activity,"storagePermissionDialog");if(permission instanceof android.app.Dialog)((android.app.Dialog)permission).dismiss();
        ArrayList entries=(ArrayList)field(activity,"entries");entries.clear();entries.add(entry("first.txt"));entries.add(entry("second.txt"));RecyclerView.Adapter adapter=(RecyclerView.Adapter)field(activity,"adapter");adapter.notifyDataSetChanged();
        RecyclerView.ViewHolder first=adapter.createViewHolder(activity.findViewById(R.id.client_files),0);adapter.bindViewHolder(first,0);assertTrue(first.itemView.performLongClick());
    }
    @Test public void longPressShowsDeleteAndRenameAndMultiSelectDisablesRename(){
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{try{prepare(activity);assertEquals(6,((LinearLayout)field(activity,"actions")).getChildCount());assertTrue(action(activity,R.string.client_rename).isEnabled());assertTrue(action(activity,R.string.client_delete).isEnabled());
            RecyclerView.Adapter adapter=(RecyclerView.Adapter)field(activity,"adapter");RecyclerView.ViewHolder second=adapter.createViewHolder(activity.findViewById(R.id.client_files),0);adapter.bindViewHolder(second,1);second.itemView.performLongClick();assertFalse(action(activity,R.string.client_rename).isEnabled());assertTrue(action(activity,R.string.client_delete).isEnabled());
        }catch(Exception e){throw new RuntimeException(e);}finally{activity.finish();}});
    }
    @Test public void moreMenuDoesNotDuplicateDeleteOrRename(){
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{instrumentation.runOnMainSync(()->{try{prepare(activity);
            /* 1.15 起更多操作改为 PopupWindow 图标菜单，选项构建拆到 buildSelectionItems：直接反射调用并断言文案。 */
            Method build=MainActivity.class.getDeclaredMethod("buildSelectionItems",ArrayList.class,ArrayList.class);build.setAccessible(true);
            List<?> items=(List<?>)build.invoke(activity,new ArrayList<Integer>(),new ArrayList<Integer>());
            List<String> labels=new ArrayList<>();for(Object item:items)labels.add(item.toString());
            assertTrue(labels.contains("复制"));assertFalse(labels.contains("删除"));assertFalse(labels.contains("重命名"));}catch(Exception e){throw new RuntimeException(e);}});}
        finally{instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void listGuttersAndBottomBarsStayConsistentInTwoAndThreeColumns()throws Exception{
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        android.content.SharedPreferences prefs=activity.getSharedPreferences("client",android.content.Context.MODE_PRIVATE);
        int oldMode=prefs.getInt("view_mode",0);boolean oldTablet=prefs.getBoolean("tablet_mode",false);
        try{
            instrumentation.runOnMainSync(()->{try{
                prepare(activity);((Set)field(activity,"selected")).clear();
                ArrayList entries=(ArrayList)field(activity,"entries");entries.clear();for(int i=0;i<9;i++)entries.add(entry("文档"+(i+1)+".txt"));
                ((RecyclerView.Adapter)field(activity,"adapter")).notifyDataSetChanged();
                Method actions=MainActivity.class.getDeclaredMethod("updateActions");actions.setAccessible(true);actions.invoke(activity);
            }catch(Exception e){throw new RuntimeException(e);}});
            for(int spans:new int[]{2,3}){
                instrumentation.runOnMainSync(()->{try{prefs.edit().putInt("view_mode",0).putBoolean("tablet_mode",spans==3).commit();Method mode=MainActivity.class.getDeclaredMethod("applyViewMode");mode.setAccessible(true);mode.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
                instrumentation.waitForIdleSync();android.os.SystemClock.sleep(150);
                instrumentation.runOnMainSync(()->{
                    RecyclerView list=activity.findViewById(R.id.client_files);assertEquals(spans,((androidx.recyclerview.widget.GridLayoutManager)list.getLayoutManager()).getSpanCount());
                    float density=activity.getResources().getDisplayMetrics().density;assertEquals(Math.round(6*density),list.getPaddingLeft());assertEquals(list.getPaddingLeft(),list.getPaddingRight());
                    android.graphics.Rect first=new android.graphics.Rect(),second=new android.graphics.Rect(),nextRow=new android.graphics.Rect();RecyclerView.ItemDecoration decoration=list.getItemDecorationAt(0);RecyclerView.State state=new RecyclerView.State();
                    assertNotNull(list.findViewHolderForAdapterPosition(spans));
                    decoration.getItemOffsets(first,list.findViewHolderForAdapterPosition(0).itemView,list,state);decoration.getItemOffsets(second,list.findViewHolderForAdapterPosition(1).itemView,list,state);decoration.getItemOffsets(nextRow,list.findViewHolderForAdapterPosition(spans).itemView,list,state);
                    assertEquals(0,first.top);assertEquals(3,nextRow.top);assertEquals(12,first.right+second.left);
                    View normal=activity.findViewById(R.id.client_bottom_bar),context=activity.findViewById(R.id.client_context_actions);assertEquals(normal.getLayoutParams().height,context.getLayoutParams().height);assertEquals(normal.getPaddingLeft(),context.getPaddingLeft());
                });
                capture(activity,"browser-list-"+spans+"cols.png",false);
            }
            instrumentation.runOnMainSync(()->{try{((Set)field(activity,"selected")).add("/test-only/文档1.txt");Method actions=MainActivity.class.getDeclaredMethod("updateActions");actions.setAccessible(true);actions.invoke(activity);assertEquals(6,((LinearLayout)field(activity,"actions")).getChildCount());}catch(Exception e){throw new RuntimeException(e);}});
            capture(activity,"browser-selected-bottom.png",true);
        }finally{prefs.edit().putInt("view_mode",oldMode).putBoolean("tablet_mode",oldTablet).commit();instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void drawerIgnoresShortSwipesAndClosesOnDeliberateLeftSwipe()throws Exception{
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->{try{
                Object permission=field(activity,"storagePermissionDialog");if(permission instanceof android.app.Dialog)((android.app.Dialog)permission).dismiss();
                androidx.drawerlayout.widget.DrawerLayout drawer=activity.findViewById(R.id.client_drawer);drawer.openDrawer(androidx.core.view.GravityCompat.START,false);
                assertTrue(activity.findViewById(R.id.client_bottom_bar_area).getClipToOutline());
            }catch(Exception e){throw new RuntimeException(e);}});
            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(150);
            instrumentation.runOnMainSync(()->{swipeDrawer(activity,40,0);assertTrue(((androidx.drawerlayout.widget.DrawerLayout)activity.findViewById(R.id.client_drawer)).isDrawerOpen(androidx.core.view.GravityCompat.START));});
            capture(activity,"sidebar-without-return-button.png",false);
            instrumentation.runOnMainSync(()->{swipeDrawer(activity,80,12);});
            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(350);
            instrumentation.runOnMainSync(()->{androidx.drawerlayout.widget.DrawerLayout drawer=activity.findViewById(R.id.client_drawer);assertFalse(drawer.isDrawerOpen(androidx.core.view.GravityCompat.START));});
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void leftSwipeReturnsFromAppearanceBeforeClosingDrawer()throws Exception{
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->{try{
                Object permission=field(activity,"storagePermissionDialog");if(permission instanceof android.app.Dialog)((android.app.Dialog)permission).dismiss();
                ((androidx.drawerlayout.widget.DrawerLayout)activity.findViewById(R.id.client_drawer)).openDrawer(androidx.core.view.GravityCompat.START,false);
            }catch(Exception e){throw new RuntimeException(e);}});
            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(200);
            instrumentation.runOnMainSync(()->{try{
                Method appearance=MainActivity.class.getDeclaredMethod("appearancePage");appearance.setAccessible(true);
                Method open=MainActivity.class.getDeclaredMethod("openPage",String.class,View.class);open.setAccessible(true);open.invoke(activity,"个性化",appearance.invoke(activity));
            }catch(Exception e){throw new RuntimeException(e);}});
            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(200);
            instrumentation.runOnMainSync(()->swipeDrawer(activity,80,12));
            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(350);
            instrumentation.runOnMainSync(()->{assertEquals(View.GONE,activity.findViewById(R.id.client_sidebar_page).getVisibility());assertTrue(((androidx.drawerlayout.widget.DrawerLayout)activity.findViewById(R.id.client_drawer)).isDrawerOpen(androidx.core.view.GravityCompat.START));});
            instrumentation.runOnMainSync(()->swipeDrawer(activity,80,12));
            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(350);
            instrumentation.runOnMainSync(()->assertFalse(((androidx.drawerlayout.widget.DrawerLayout)activity.findViewById(R.id.client_drawer)).isDrawerOpen(androidx.core.view.GravityCompat.START)));
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
    private void capture(MainActivity activity,String name,boolean bottom)throws Exception{
        // Device screenshots remain the visual check for the new gesture-only drawer.
        instrumentation.waitForIdleSync();android.os.SystemClock.sleep(150);
        instrumentation.runOnMainSync(()->{View view=activity.findViewById(bottom?R.id.client_bottom_bar_area:android.R.id.content);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(activity.getExternalFilesDir(null),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}catch(Exception e){throw new RuntimeException(e);}finally{bitmap.recycle();}});
    }
    private void swipeDrawer(MainActivity activity,float distanceDp,float verticalDp){
        View host=activity.findViewById(R.id.client_sidebar_host);int[] pos=new int[2];host.getLocationOnScreen(pos);
        float density=activity.getResources().getDisplayMetrics().density,x=pos[0]+host.getWidth()*.9f,y=pos[1]+host.getHeight()*.15f;
        long down=android.os.SystemClock.uptimeMillis();
        int[] actions={android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP};
        for(int i=0;i<actions.length;i++){float amount=i==0?0:1;android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down+i*150,actions[i],x-distanceDp*density*amount,y+verticalDp*density*amount,0);try{activity.dispatchTouchEvent(event);}finally{event.recycle();}}
    }
}
