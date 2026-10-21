package com.cj.music;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.cj.music.resources.R;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public class PreviewUiTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private Object field(Object instance,String name)throws Exception{Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
    private void navigate(ImagePreviewActivity activity,int direction)throws Exception{Method method=ImagePreviewActivity.class.getDeclaredMethod("navigate",int.class);method.setAccessible(true);instrumentation.runOnMainSync(()->{try{method.invoke(activity,direction);}catch(Exception e){throw new RuntimeException(e);}});}
    private void awaitTitle(ImagePreviewActivity activity,String expected)throws Exception{
        long deadline=android.os.SystemClock.elapsedRealtime()+5000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){final String[] shown={null};instrumentation.runOnMainSync(()->{try{shown[0]=((TextView)field(activity,"title")).getText().toString();}catch(Exception e){throw new RuntimeException(e);}});if(expected.equals(shown[0]))return;Thread.sleep(20);}
        fail("Preview did not show "+expected);
    }
    @Test public void previewSkipsCorruptImagesAndNavigatesBothDirections()throws Exception {
        File root=new File(instrumentation.getTargetContext().getCacheDir(),"preview-ui-"+System.nanoTime());assertTrue(root.mkdir());
        ArrayList<ImagePreviewActivity.Item> items=new ArrayList<>();
        for(String name:new String[]{"first.png","broken.jpg","last.png"}){
            File file=new File(root,name);try(FileOutputStream out=new FileOutputStream(file)){
                if(name.endsWith("png")){Bitmap bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();}else out.write(1);
            }items.add(new ImagePreviewActivity.Item(file.getAbsolutePath(),name,"","image/*"));
        }
        Field snapshots=ImagePreviewActivity.class.getDeclaredField("snapshots");snapshots.setAccessible(true);((Map<String,ArrayList<ImagePreviewActivity.Item>>)snapshots.get(null)).put("test",items);
        Intent intent=new Intent(instrumentation.getTargetContext(),ImagePreviewActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);intent.putExtra("snapshot","test");intent.putExtra("item",items.get(0));
        ImagePreviewActivity activity=(ImagePreviewActivity)instrumentation.startActivitySync(intent);
        try{awaitTitle(activity,"first.png");navigate(activity,1);awaitTitle(activity,"last.png");navigate(activity,-1);awaitTitle(activity,"first.png");assertFalse(ImagePreviewActivity.isImage("video.mp4","video/mp4"));}
        finally{instrumentation.runOnMainSync(activity::finish);for(File file:root.listFiles())file.delete();root.delete();}
    }
    @Test public void playerBarsHideTogetherAndOrientationButtonsAreAtBottom()throws Exception {
        Intent intent=new Intent(instrumentation.getTargetContext(),VideoPlayerActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);intent.putExtra(VideoPlayerActivity.EXTRA_PATH,"/missing-video.mp4");
        VideoPlayerActivity activity=(VideoPlayerActivity)instrumentation.startActivitySync(intent);
        try{
            Method method=VideoPlayerActivity.class.getDeclaredMethod("syncChrome",boolean.class);method.setAccessible(true);
            instrumentation.runOnMainSync(()->{try{
                View top=activity.findViewById(R.id.client_player_bar),bottom=(View)activity.findViewById(R.id.client_player_rotate).getParent();
                androidx.media3.ui.PlayerView playerView=activity.findViewById(R.id.client_player_view);
                assertSame(bottom,activity.findViewById(R.id.client_player_rotate).getParent());assertSame(bottom,activity.findViewById(R.id.client_player_lock).getParent());
                playerView.hideController();assertEquals(View.GONE,top.getVisibility());assertFalse(bottom.isShown());
                playerView.showController();assertEquals(View.VISIBLE,top.getVisibility());assertTrue(bottom.isShown());
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
}
