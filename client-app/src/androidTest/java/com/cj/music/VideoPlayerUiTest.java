package com.cj.music;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.cj.music.resources.R;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class VideoPlayerUiTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private void little(java.io.OutputStream out,int value,int bytes)throws Exception{for(int i=0;i<bytes;i++)out.write(value>>>(8*i)&255);}
    private File silence()throws Exception{
        File file=new File(instrumentation.getTargetContext().getCacheDir(),"player-ui.wav");int length=30*8000*2;
        try(FileOutputStream out=new FileOutputStream(file)){
            out.write("RIFF".getBytes("US-ASCII"));little(out,36+length,4);out.write("WAVEfmt ".getBytes("US-ASCII"));little(out,16,4);little(out,1,2);little(out,1,2);little(out,8000,4);little(out,16000,4);little(out,2,2);little(out,16,2);out.write("data".getBytes("US-ASCII"));little(out,length,4);byte[] zeros=new byte[4096];while(length>0){int n=Math.min(length,zeros.length);out.write(zeros,0,n);length-=n;}
        }return file;
    }
    @Test public void controlsAlignInPortraitAndLandscapeAndSystemBarsStayBlack()throws Exception{
        File file=silence();VideoPlayerActivity activity=(VideoPlayerActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),VideoPlayerActivity.class).putExtra(VideoPlayerActivity.EXTRA_PATH,file.getAbsolutePath()).putExtra(VideoPlayerActivity.EXTRA_TITLE,"播放控制预览").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->{try{ExoPlayer player=(ExoPlayer)field(activity,"player");player.setVolume(0);player.pause();PlayerView view=activity.findViewById(R.id.client_player_view);view.setControllerShowTimeoutMs(0);view.showController();}catch(Exception e){throw new RuntimeException(e);}});
            for(int attempt=0;attempt<30;attempt++){boolean[] ready={false};instrumentation.runOnMainSync(()->{try{ready[0]=((ExoPlayer)field(activity,"player")).getPlaybackState()==Player.STATE_READY;}catch(Exception e){throw new RuntimeException(e);}});if(ready[0])break;android.os.SystemClock.sleep(100);}
            instrumentation.runOnMainSync(()->{try{
                ExoPlayer player=(ExoPlayer)field(activity,"player");assertEquals(Player.STATE_READY,player.getPlaybackState());
                assertEquals(Color.BLACK,activity.getWindow().getNavigationBarColor());assertEquals(Color.BLACK,activity.getWindow().getStatusBarColor());assertFalse(activity.getWindow().isNavigationBarContrastEnforced());
                assertFalse(activity.getWindow().isStatusBarContrastEnforced());
                android.util.TypedValue background=new android.util.TypedValue();activity.getTheme().resolveAttribute(android.R.attr.windowBackground,background,true);assertEquals(Color.BLACK,background.data);
                activity.getWindow().setStatusBarColor(Color.WHITE);activity.getWindow().setNavigationBarColor(Color.WHITE);activity.onConfigurationChanged(new android.content.res.Configuration(activity.getResources().getConfiguration()));assertEquals(Color.BLACK,activity.getWindow().getStatusBarColor());assertEquals(Color.BLACK,activity.getWindow().getNavigationBarColor());
                activity.getWindow().setStatusBarColor(Color.WHITE);activity.onWindowFocusChanged(true);assertEquals(Color.BLACK,activity.getWindow().getStatusBarColor());
                assertEquals(0,activity.getWindow().getDecorView().getSystemUiVisibility()&View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                PlayerView view=activity.findViewById(R.id.client_player_view);view.hideController();assertEquals(View.GONE,activity.findViewById(R.id.client_player_bar).getVisibility());view.showController();assertEquals(View.VISIBLE,activity.findViewById(R.id.client_player_bar).getVisibility());
                activity.findViewById(R.id.client_player_repeat).performClick();assertEquals(Player.REPEAT_MODE_ONE,player.getRepeatMode());activity.findViewById(R.id.client_player_repeat).performClick();assertEquals(Player.REPEAT_MODE_OFF,player.getRepeatMode());
                activity.findViewById(R.id.client_player_lock).performClick();assertTrue((Boolean)field(activity,"orientationLocked"));activity.findViewById(R.id.client_player_lock).performClick();assertFalse((Boolean)field(activity,"orientationLocked"));
                player.seekTo(0);activity.findViewById(androidx.media3.ui.R.id.exo_ffwd).performClick();assertEquals(10000,player.getCurrentPosition());
            }catch(Exception e){throw new RuntimeException(e);}});
            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(150);
            for(int[] size:new int[][]{{320,568},{400,800},{800,400}}){
                instrumentation.runOnMainSync(()->{try{
                    float density=activity.getResources().getDisplayMetrics().density;ViewGroup content=activity.findViewById(android.R.id.content);View root=content.getChildAt(0);int width=Math.round(size[0]*density),height=Math.round(size[1]*density);root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
                    Rect back=new Rect(),title=new Rect();activity.findViewById(R.id.client_player_back).getGlobalVisibleRect(back);activity.findViewById(R.id.client_player_title).getGlobalVisibleRect(title);assertTrue(Math.abs(back.centerY()-title.centerY())<=Math.round(density));
                    View time=activity.findViewById(androidx.media3.ui.R.id.exo_time),progress=activity.findViewById(androidx.media3.ui.R.id.exo_progress);assertEquals(32*density,time.getTranslationY(),1f);assertEquals(Math.round(48*density),progress.getHeight());
                    ViewGroup controls=activity.findViewById(androidx.media3.ui.R.id.exo_center_controls);int previousRight=0;for(int i=0;i<controls.getChildCount();i++){View button=controls.getChildAt(i);if(!(button instanceof android.widget.ImageButton))continue;assertTrue("width="+button.getWidth()+" density="+density+" screen="+size[0],button.getWidth()>=Math.round(40*density));assertTrue("left="+button.getLeft()+" previous="+previousRight+" screen="+size[0],button.getLeft()>=previousRight);assertTrue("right="+button.getRight()+" parent="+controls.getWidth()+" screen="+size[0],button.getRight()<=controls.getWidth());previousRight=button.getRight();}
                    TextView position=activity.findViewById(androidx.media3.ui.R.id.exo_position),duration=activity.findViewById(androidx.media3.ui.R.id.exo_duration);position.setText("00:10");duration.setText("00:30");
                    // Layout-only preview: use a plain background to make the lighter scrim visible.
                    activity.findViewById(R.id.client_player_view).setBackgroundColor(0xFF536D79);activity.findViewById(androidx.media3.ui.R.id.exo_shutter).setBackgroundColor(0xFF536D79);
                    Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));try(FileOutputStream output=new FileOutputStream(new File(activity.getExternalFilesDir(null),"player-ui-"+size[0]+"x"+size[1]+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();
                }catch(Exception e){throw new RuntimeException(e);}});
            }
        }finally{instrumentation.runOnMainSync(activity::finish);file.delete();}
    }
}
