package com.cj.music;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import com.cj.music.resources.R;
import com.google.android.material.slider.Slider;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.Assert.*;

public class ImageEditorInteractionUiTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Bitmap source(){Bitmap image=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);for(int y=10;y<60;y++)for(int x=10;x<80;x++)image.setPixel(x,y,Color.RED);return image;}
    private ImageEditorView editor(){ImageEditorView view=new ImageEditorView(instrumentation.getTargetContext());view.layout(0,0,640,640);view.load(source());return view;}
    private static Bitmap screen(ImageEditorView view){Bitmap image=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));return image;}
    private static void touch(ImageEditorView view,int action,float x,float y){MotionEvent event=MotionEvent.obtain(0,0,action,x,y,0);view.onTouchEvent(event);event.recycle();}
    private static void drag(ImageEditorView view,float x,float y,float dx,float dy,int end)throws Exception{float[] point={x,y};((Matrix)field(view,"display")).mapPoints(point);float scale=(Float)field(view,"scale");touch(view,MotionEvent.ACTION_DOWN,point[0],point[1]);touch(view,MotionEvent.ACTION_MOVE,point[0]+dx*scale,point[1]+dy*scale);touch(view,end,point[0]+dx*scale,point[1]+dy*scale);}
    @Test public void rotationPreviewDoesNotChangeImageOrUndoAndCancelRestoresDisplay(){instrumentation.runOnMainSync(()->{
        ImageEditorView view=editor();Bitmap original=view.getBitmap(),before=screen(view);view.beginRotationPreview();view.previewRotation(45);
        assertSame(original,view.getBitmap());assertFalse(view.canUndo());assertFalse(before.sameAs(screen(view)));assertTrue(original.sameAs(view.render()));
        view.cancelRotationPreview();assertTrue(before.sameAs(screen(view)));assertSame(original,view.getBitmap());
        view.beginRotationPreview();view.previewRotation(90);view.applyRotationPreview();assertEquals(320,view.getBitmap().getWidth());assertEquals(200,view.getBitmap().getHeight());assertTrue(view.canUndo());view.undo();assertTrue(original.sameAs(view.getBitmap()));
    });}
    @Test public void rotationKeepsCanvasSizeAndCoversCornersWithoutBorders(){instrumentation.runOnMainSync(()->{
        ImageEditorView view=editor();for(float angle:new float[]{15,-45,90,180}){view.rotateBy(angle);Bitmap result=view.getBitmap();assertEquals(320,result.getWidth());assertEquals(200,result.getHeight());for(int x:new int[]{0,319})for(int y:new int[]{0,199})assertEquals("No transparent border at "+angle,255,Color.alpha(result.getPixel(x,y)));view.undo();}
    });}
    @Test public void cancelRestoresAppliedImageAndUndoAfterManyTransforms(){instrumentation.runOnMainSync(()->{
        ImageEditorView view=editor();Bitmap original=view.getBitmap();view.rotateBy(15);Bitmap applied=view.getBitmap();view.beginEdit();
        for(int i=0;i<6;i++)view.rotateBy(30);view.undo();view.finishEdit(false);
        assertSame(applied,view.getBitmap());assertFalse(applied.isRecycled());view.undo();assertSame(original,view.getBitmap());assertFalse(original.isRecycled());
    });}
    @Test public void cancellingTextMovesAndAdditionsPreservesPreviousAnnotations(){instrumentation.runOnMainSync(()->{try{
        ImageEditorView view=editor();view.setMode(ImageEditorView.MODE_DRAW);view.setBrushTool(ImageEditorView.TOOL_TEXT);view.addText("Old",40,70);Bitmap applied=view.render();
        view.beginEdit();drag(view,45,65,40,20,MotionEvent.ACTION_UP);view.addText("New",100,120);view.finishEdit(false);assertTrue(applied.sameAs(view.render()));view.undo();assertFalse(view.hasStrokes());
    }catch(Exception e){throw new RuntimeException(e);}});}
    @Test public void draggedTextIsSavedAndUndoRestoresPositionBeforeRemovingText(){instrumentation.runOnMainSync(()->{try{
        ImageEditorView view=editor();view.setMode(ImageEditorView.MODE_DRAW);view.setBrushTool(ImageEditorView.TOOL_TEXT);view.addText("Text",50,70);Bitmap before=view.render();final int[] prompts={0};view.setOnTextTap((x,y)->prompts[0]++);
        // Change the displayed scale to verify movement is recorded in image coordinates.
        view.setBottomInset(340);drag(view,55,65,40,30,MotionEvent.ACTION_UP);
        Object text=((List<?>)field(view,"strokes")).get(0);assertEquals(90f,(Float)field(text,"textX"),0.01f);assertEquals(100f,(Float)field(text,"textY"),0.01f);assertEquals(0,prompts[0]);assertFalse(before.sameAs(view.render()));
        view.undo();assertTrue(before.sameAs(view.render()));assertTrue(view.hasStrokes());view.undo();assertFalse(view.hasStrokes());assertFalse(view.canUndo());
    }catch(Exception e){throw new RuntimeException(e);}});}
    @Test public void multilineTextCanBeDraggedFromSecondLineAndCancelled(){instrumentation.runOnMainSync(()->{try{
        ImageEditorView view=editor();view.setMode(ImageEditorView.MODE_DRAW);view.setBrushTool(ImageEditorView.TOOL_TEXT);view.addText("Line one\nLine two",40,70);Bitmap before=view.render();
        drag(view,45,85,30,20,MotionEvent.ACTION_CANCEL);assertTrue(before.sameAs(view.render()));view.undo();assertFalse(view.hasStrokes());
    }catch(Exception e){throw new RuntimeException(e);}});}
    private static Slider slider(View view,String description){if(view instanceof Slider&&description.contentEquals(view.getContentDescription()))return (Slider)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Slider result=slider(group.getChildAt(i),description);if(result!=null)return result;}}return null;}
    @Test public void rotationAndZoomUseTopApplyAndCancelRestoresImage()throws Exception{
        File image=new File(instrumentation.getTargetContext().getCacheDir(),"editor-rotation-ui-test.png");try(FileOutputStream out=new FileOutputStream(image)){source().compress(Bitmap.CompressFormat.PNG,100,out);}
        ImageEditorActivity activity=(ImageEditorActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),ImageEditorActivity.class).putExtra(ImageEditorActivity.EXTRA_PATH,image.getAbsolutePath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{try{
            ImageEditorView view=(ImageEditorView)field(activity,"canvas");Bitmap original=view.getBitmap();Method open=ImageEditorActivity.class.getDeclaredMethod("rotateFreeDialog");open.setAccessible(true);open.invoke(activity);
            View panel=(View)field(activity,"rotationPanel");Slider angle=slider(panel,"旋转角度"),zoom=slider(panel,"旋转缩放");assertNotNull(angle);assertNotNull(zoom);angle.setValue(35.5f);zoom.setValue(150);assertEquals(35.5f,(Float)field(view,"rotationDegrees"),0.01f);assertEquals(1.5f,(Float)field(view,"rotationZoom"),0.01f);assertSame(original,view.getBitmap());
            android.widget.TextView top=activity.findViewById(R.id.client_editor_save);assertEquals("应用",top.getText().toString());
            assertEquals(View.VISIBLE,activity.findViewById(R.id.client_editor_cancel).getVisibility());
            Method close=ImageEditorActivity.class.getDeclaredMethod("closeRotationPanel",boolean.class);close.setAccessible(true);close.invoke(activity,false);assertFalse((Boolean)field(view,"rotationPreview"));assertFalse(view.canUndo());assertSame(original,view.getBitmap());assertEquals("保存",top.getText().toString());
            open.invoke(activity);panel=(View)field(activity,"rotationPanel");slider(panel,"旋转缩放").setValue(200);top.performClick();assertNull(field(activity,"rotationPanel"));assertEquals("保存",top.getText().toString());assertEquals(320,view.getBitmap().getWidth());assertEquals(200,view.getBitmap().getHeight());assertFalse(original.sameAs(view.getBitmap()));view.undo();assertTrue(original.sameAs(view.getBitmap()));
            Method page=ImageEditorActivity.class.getDeclaredMethod("showPage",int.class);page.setAccessible(true);page.invoke(activity,2);assertEquals("应用",top.getText().toString());top.performClick();assertEquals("保存",top.getText().toString());
            page.invoke(activity,1);view.rotateBy(20);activity.findViewById(R.id.client_editor_cancel).performClick();assertTrue(original.sameAs(view.getBitmap()));assertEquals(View.GONE,activity.findViewById(R.id.client_editor_cancel).getVisibility());assertEquals("保存",top.getText().toString());
        }catch(Exception e){throw new RuntimeException(e);}finally{activity.finish();image.delete();}});
    }
    @Test public void saveCreatesNumberedCopyWithoutChangingSourceOrExistingCopy()throws Exception{
        File image=new File(instrumentation.getTargetContext().getCacheDir(),"editor-copy-ui-test.png"),first=ImageEditorActivity.numberedCopy(image,1),second=ImageEditorActivity.numberedCopy(image,2);first.delete();second.delete();
        try(FileOutputStream out=new FileOutputStream(image)){source().compress(Bitmap.CompressFormat.PNG,100,out);}byte[] original=java.nio.file.Files.readAllBytes(image.toPath());byte[] existing={1,2,3};java.nio.file.Files.write(first.toPath(),existing);
        ImageEditorActivity activity=(ImageEditorActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),ImageEditorActivity.class).putExtra(ImageEditorActivity.EXTRA_PATH,image.getAbsolutePath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{instrumentation.runOnMainSync(()->{activity.findViewById(R.id.client_editor_save).performClick();});instrumentation.waitForIdleSync();assertTrue(second.isFile());assertEquals("editor-copy-ui-test2.png",second.getName());assertArrayEquals(original,java.nio.file.Files.readAllBytes(image.toPath()));assertArrayEquals(existing,java.nio.file.Files.readAllBytes(first.toPath()));assertNotNull(android.graphics.BitmapFactory.decodeFile(second.getAbsolutePath()));}
        finally{instrumentation.runOnMainSync(activity::finish);image.delete();first.delete();second.delete();}
    }
    private void capture(String name)throws Exception{instrumentation.waitForIdleSync();android.os.SystemClock.sleep(150);Bitmap shot=instrumentation.getUiAutomation().takeScreenshot();if(shot!=null){try(FileOutputStream out=new FileOutputStream(new File(instrumentation.getTargetContext().getExternalFilesDir(null),name))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}shot.recycle();}}
    @Test public void undoOnlyAppearsWithHistoryAndToolsStayGrouped()throws Exception{
        File image=new File(instrumentation.getTargetContext().getCacheDir(),"editor-layout.png");
        try(FileOutputStream out=new FileOutputStream(image)){source().compress(Bitmap.CompressFormat.PNG,100,out);}
        ImageEditorActivity activity=(ImageEditorActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),ImageEditorActivity.class).putExtra(ImageEditorActivity.EXTRA_PATH,image.getAbsolutePath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->{try{
                ImageEditorView view=(ImageEditorView)field(activity,"canvas");
                View undo=activity.findViewById(R.id.client_editor_undo);
                ViewGroup tools=activity.findViewById(R.id.client_editor_main_bar);
                assertNoEditorGuidance(activity.getWindow().getDecorView());
                assertEquals(5,tools.getChildCount());
                assertEquals(R.id.client_editor_resize,tools.getChildAt(2).getId());
                assertEquals(R.id.client_editor_reset,tools.getChildAt(4).getId());
                assertEquals(View.GONE,undo.getVisibility());
                activity.findViewById(R.id.client_editor_transform).performClick();
                assertEquals(View.GONE,undo.getVisibility());
                activity.findViewById(R.id.client_editor_rotate_left).performClick();
                assertEquals(View.VISIBLE,undo.getVisibility());
                undo.performClick();assertFalse(view.canUndo());assertEquals(View.GONE,undo.getVisibility());
                activity.findViewById(R.id.client_editor_rotate_right).performClick();
                activity.findViewById(R.id.client_editor_save).performClick();
                assertEquals(View.VISIBLE,undo.getVisibility());
                activity.findViewById(R.id.client_editor_transform).performClick();
                activity.findViewById(R.id.client_editor_rotate_free).performClick();
                assertNoEditorGuidance(activity.getWindow().getDecorView());
                assertEquals(View.GONE,undo.getVisibility());
                activity.findViewById(R.id.client_editor_cancel).performClick();
                assertEquals(View.VISIBLE,undo.getVisibility());
                activity.findViewById(R.id.client_editor_cancel).performClick();
                undo.performClick();assertEquals(View.GONE,undo.getVisibility());
                activity.findViewById(R.id.client_editor_draw).performClick();
                assertNoEditorGuidance(activity.getWindow().getDecorView());
                view.addText("文字",40,40);assertEquals(View.VISIBLE,undo.getVisibility());
                undo.performClick();assertEquals(View.GONE,undo.getVisibility());
                view.addText("取消",40,40);activity.findViewById(R.id.client_editor_cancel).performClick();
                assertEquals(View.GONE,undo.getVisibility());
                view.rotate(true);assertEquals(View.VISIBLE,undo.getVisibility());
                view.resetToOriginal();assertEquals(View.GONE,undo.getVisibility());
            }catch(Exception e){throw new RuntimeException(e);}});
            captureLayout(activity,"editor-redesign-main.png");
            instrumentation.runOnMainSync(()->{activity.findViewById(R.id.client_editor_transform).performClick();activity.findViewById(R.id.client_editor_rotate_left).performClick();});
            captureLayout(activity,"editor-redesign-transform.png");
            instrumentation.runOnMainSync(()->{activity.findViewById(R.id.client_editor_cancel).performClick();activity.findViewById(R.id.client_editor_draw).performClick();});
            captureLayout(activity,"editor-redesign-draw.png");
        }finally{instrumentation.runOnMainSync(activity::finish);image.delete();}
    }
    private void assertNoEditorGuidance(View view){
        assertNull(view.getTooltipText());
        if(view instanceof android.widget.TextView){String text=((android.widget.TextView)view).getText().toString();for(String fragment:new String[]{"像素","双指","单指","拖动","点击空白","涂抹"})assertFalse(text,text.contains(fragment));}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)assertNoEditorGuidance(group.getChildAt(i));}
    }
    private void captureLayout(ImageEditorActivity activity,String name)throws Exception{
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(instrumentation.getTargetContext().getExternalFilesDir(null),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{bitmap.recycle();}});
    }
    @Test public void topControlsAreOrderedAndResizeUsesThem()throws Exception{
        File image=new File(instrumentation.getTargetContext().getCacheDir(),"editor-controls.png");try(FileOutputStream out=new FileOutputStream(image)){source().compress(Bitmap.CompressFormat.PNG,100,out);}
        ImageEditorActivity activity=(ImageEditorActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),ImageEditorActivity.class).putExtra(ImageEditorActivity.EXTRA_PATH,image.getAbsolutePath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->activity.findViewById(R.id.client_editor_transform).performClick());instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{View cancel=activity.findViewById(R.id.client_editor_cancel),apply=activity.findViewById(R.id.client_editor_save);assertTrue(cancel.getRight()<=apply.getLeft());assertTrue(activity.findViewById(R.id.client_editor_rotate_left) instanceof android.widget.ImageButton);assertNotNull(activity.findViewById(R.id.client_editor_rotate_left).getContentDescription());});capture("editor-transform-icons.png");
            instrumentation.runOnMainSync(()->activity.findViewById(R.id.client_editor_rotate_free).performClick());capture("editor-top-rotation-controls.png");
            instrumentation.runOnMainSync(()->{activity.findViewById(R.id.client_editor_cancel).performClick();activity.findViewById(R.id.client_editor_cancel).performClick();activity.findViewById(R.id.client_editor_resize).performClick();try{slider((View)field(activity,"rotationPanel"),"图片尺寸").setValue(50);}catch(Exception e){throw new RuntimeException(e);}activity.findViewById(R.id.client_editor_cancel).performClick();try{assertEquals(320,((ImageEditorView)field(activity,"canvas")).getBitmap().getWidth());}catch(Exception e){throw new RuntimeException(e);}activity.findViewById(R.id.client_editor_resize).performClick();try{slider((View)field(activity,"rotationPanel"),"图片尺寸").setValue(50);}catch(Exception e){throw new RuntimeException(e);}activity.findViewById(R.id.client_editor_save).performClick();try{assertEquals(160,((ImageEditorView)field(activity,"canvas")).getBitmap().getWidth());}catch(Exception e){throw new RuntimeException(e);}});
        }finally{instrumentation.runOnMainSync(activity::finish);image.delete();}
    }
}
