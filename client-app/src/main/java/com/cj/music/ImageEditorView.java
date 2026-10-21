package com.cj.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;

/**
 * 图片编辑画布。
 * 旋转 / 翻转 / 裁剪 / 缩放直接改位图（保证结果所见即所得），涂鸦按位图坐标记录，
 * 视图矩阵只负责显示时的适配与双指缩放，因此涂鸦与裁剪的坐标换算永远是线性的。
 */
final class ImageEditorView extends View {
    static final int MODE_VIEW=0, MODE_CROP=1, MODE_DRAW=2;
    private static final int MAX_UNDO=2;

    private Bitmap bitmap;
    private Bitmap original;
    private int mode=MODE_VIEW;

    private final Matrix display=new Matrix();
    private final float[] values=new float[9];
    private float scale=1f, dx, dy, fitScale=1f;

    private final RectF crop=new RectF();
    private int cropHit;
    private float cropTouchX, cropTouchY;
    private final RectF cropStart=new RectF();

    /** 画笔工具类型：普通笔 / 马赛克 / 文本。 */
    static final int TOOL_PEN=0, TOOL_MOSAIC=1, TOOL_TEXT=2;
    private static final class Stroke {
        final Path path=new Path(); final int color; final float width;
        final int tool;
        final String text; float textX,textY; final float textSize;
        Stroke(int color,float width){this(color,width,TOOL_PEN,null,0,0,0);}
        Stroke(int color,float width,int tool,String text,float textX,float textY,float textSize){
            this.color=color;this.width=width;this.tool=tool;this.text=text;
            this.textX=textX;this.textY=textY;this.textSize=textSize;
        }
    }
    private final ArrayList<Stroke> strokes=new ArrayList<>();
    private final ArrayList<Runnable> strokeUndo=new ArrayList<>();
    private final ArrayList<Bitmap> undo=new ArrayList<>();
    private Stroke current;
    private Stroke draggingText,selectedText;
    private float textStartX,textStartY,textTouchX,textTouchY;
    private boolean textGestureCancelled;
    private boolean rotationPreview;
    private float rotationDegrees;
    private float rotationZoom=1f;
    private Bitmap editBitmap;
    private ArrayList<Bitmap> editUndo;
    private ArrayList<Stroke> editStrokes;
    private ArrayList<Runnable> editStrokeUndo;
    private final ArrayList<float[]> editTextPositions=new ArrayList<>();
    private final ArrayList<Bitmap> retiredDuringEdit=new ArrayList<>();

    void beginEdit(){
        if(bitmap==null||editUndo!=null)return;
        editBitmap=bitmap;editUndo=new ArrayList<>(undo);editStrokes=new ArrayList<>(strokes);editStrokeUndo=new ArrayList<>(strokeUndo);
        editTextPositions.clear();for(Stroke s:editStrokes)editTextPositions.add(new float[]{s.textX,s.textY});
    }
    void finishEdit(boolean apply){
        if(editUndo==null)return;
        if(!apply){
            retiredDuringEdit.add(bitmap);retiredDuringEdit.addAll(undo);
            bitmap=editBitmap;undo.clear();undo.addAll(editUndo);strokes.clear();strokes.addAll(editStrokes);strokeUndo.clear();strokeUndo.addAll(editStrokeUndo);
            for(int i=0;i<editStrokes.size();i++){Stroke s=editStrokes.get(i);s.textX=editTextPositions.get(i)[0];s.textY=editTextPositions.get(i)[1];}
        }
        editBitmap=null;editUndo=null;editStrokes=null;editStrokeUndo=null;editTextPositions.clear();
        for(Bitmap b:retiredDuringEdit)if(b!=null&&b!=bitmap&&b!=original&&!undo.contains(b)&&!b.isRecycled())b.recycle();retiredDuringEdit.clear();
        current=null;draggingText=null;selectedText=null;rotationPreview=false;mode=MODE_VIEW;fitToView();notifyChange();
    }
    private void retire(Bitmap b){if(b==null||b.isRecycled())return;if(editUndo!=null&&(b==editBitmap||editUndo.contains(b)))retiredDuringEdit.add(b);else b.recycle();}

    private int brushColor=Color.RED;
    private float brushWidth=8f;
    private int brushTool=TOOL_PEN;
    private float textSize=40f;   // 文本字号（位图坐标，px）

    private float lastX,lastY;
    private boolean panning;
    /** 底部被子页面面板遮住的高度（px），图片要在这以上的区域里完成居中。 */
    private int bottomInset;
    private Runnable onChange;
    /** 文本模式下点击画布时回调（参数为位图坐标 x/y），由 Activity 弹输入框。 */
    private TextTapListener onTextTap;
    interface TextTapListener { void onTap(float bitmapX,float bitmapY); }
    void setOnTextTap(TextTapListener listener){onTextTap=listener;}

    private final ScaleGestureDetector scaleDetector;
    private final Paint imagePaint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
    private final Paint inkPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint=new Paint();
    private final Paint framePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint=new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 必须 public：LayoutInflater 通过反射查找 (Context, AttributeSet) 构造器，非 public 会抛 NoSuchMethodException。 */
    public ImageEditorView(Context context){this(context,null);}
    public ImageEditorView(Context context,AttributeSet attrs){
        super(context,attrs);
        inkPaint.setStyle(Paint.Style.STROKE);inkPaint.setStrokeCap(Paint.Cap.ROUND);inkPaint.setStrokeJoin(Paint.Join.ROUND);
        scrimPaint.setColor(0xB8F8F8F8);
        framePaint.setStyle(Paint.Style.STROKE);framePaint.setColor(0xFF111111);framePaint.setStrokeWidth(dp(1));
        handlePaint.setStyle(Paint.Style.STROKE);handlePaint.setColor(0xFF111111);handlePaint.setStrokeWidth(dp(3));
        scaleDetector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){zoomBy(detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());return true;}
        });
    }

    private float dp(float v){return v*getResources().getDisplayMetrics().density;}

    void setOnChange(Runnable listener){onChange=listener;}
    private Bitmap adjustmentPreview;private boolean adjusting;private float adjustmentBrightness,adjustmentContrast=1,adjustmentVibrance;
    void previewAdjustments(float brightness,float contrast,float vibrance){if(bitmap==null)return;adjusting=true;adjustmentBrightness=brightness;adjustmentContrast=contrast;adjustmentVibrance=vibrance;removeCallbacks(renderAdjustment);postDelayed(renderAdjustment,32);}
    private final Runnable renderAdjustment=()->{if(!adjusting||bitmap==null)return;int w=bitmap.getWidth(),h=bitmap.getHeight();float scale=Math.min(1,720f/Math.max(w,h));Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),true);Bitmap next=adjustBitmap(small);if(small!=bitmap)small.recycle();if(adjustmentPreview!=null)adjustmentPreview.recycle();adjustmentPreview=next;invalidate();};
    private Bitmap adjustBitmap(Bitmap source){Bitmap out=source.copy(Bitmap.Config.ARGB_8888,true);int w=out.getWidth();int[] row=new int[w];for(int y=0;y<out.getHeight();y++){out.getPixels(row,0,w,0,y,w,1);for(int x=0;x<w;x++)row[x]=PhotoAdjustments.apply(row[x],adjustmentBrightness,adjustmentContrast,adjustmentVibrance);out.setPixels(row,0,w,0,y,w,1);}return out;}
    void previewColor(float brightness,float saturation){
        android.graphics.ColorMatrix m=new android.graphics.ColorMatrix();m.setSaturation(saturation);
        android.graphics.ColorMatrix b=new android.graphics.ColorMatrix(new float[]{1,0,0,0,brightness,0,1,0,0,brightness,0,0,1,0,brightness,0,0,0,1,0});m.postConcat(b);
        imagePaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(m));invalidate();
    }
    void applyColor(){if(adjusting&&bitmap!=null){commitStrokes();Bitmap out=adjustBitmap(bitmap);pushUndo();bitmap=out;clearColor();notifyChange();return;}if(bitmap==null||imagePaint.getColorFilter()==null)return;commitStrokes();Bitmap out=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);new Canvas(out).drawBitmap(bitmap,0,0,imagePaint);pushUndo();bitmap=out;imagePaint.setColorFilter(null);invalidate();notifyChange();}
    void clearColor(){adjusting=false;removeCallbacks(renderAdjustment);if(adjustmentPreview!=null){adjustmentPreview.recycle();adjustmentPreview=null;}imagePaint.setColorFilter(null);invalidate();}
    void cropAspect(float ratio){if(bitmap==null)return;setMode(MODE_CROP);if(ratio<=0)return;float w=crop.width(),h=crop.height();if(w/h>ratio)w=h*ratio;else h=w/ratio;float x=crop.centerX(),y=crop.centerY();crop.set(x-w/2,y-h/2,x+w/2,y+h/2);invalidate();}
    void perspective(boolean vertical,float amount){
        if(bitmap==null||Math.abs(amount)<0.001f)return;commitStrokes();float w=bitmap.getWidth(),h=bitmap.getHeight(),a=Math.abs(amount)*.22f;
        float[] src={0,0,w,0,w,h,0,h},dst=src.clone();
        if(vertical){if(amount>0){dst[0]=w*a;dst[2]=w*(1-a);}else{dst[4]=w*(1-a);dst[6]=w*a;}}
        else if(amount>0){dst[1]=h*a;dst[7]=h*(1-a);}else{dst[3]=h*a;dst[5]=h*(1-a);}
        Matrix m=new Matrix();m.setPolyToPoly(src,0,dst,0,4);Bitmap out=Bitmap.createBitmap((int)w,(int)h,Bitmap.Config.ARGB_8888);new Canvas(out).drawBitmap(bitmap,m,imagePaint);pushUndo();bitmap=out;fitToView();notifyChange();
    }
    int getMode(){return mode;}
    Bitmap getBitmap(){return bitmap;}
    boolean hasStrokes(){return !strokes.isEmpty();}
    boolean canUndo(){return !strokeUndo.isEmpty()||!undo.isEmpty();}
    void setBrushColor(int color){brushColor=color;}
    void setBrushWidth(float width){brushWidth=width;}
    void setBrushTool(int tool){brushTool=tool;invalidate();}
    int getBrushTool(){return brushTool;}
    void setTextSize(float size){textSize=size;}
    /** 添加文本标注（x/y 为位图坐标）。 */
    void addText(String text,float x,float y){
        if(bitmap==null||text==null||text.isEmpty())return;
        Stroke s=new Stroke(brushColor,brushWidth,TOOL_TEXT,text,x,y,textSize/scale);
        strokes.add(s);strokeUndo.add(()->strokes.remove(s));selectedText=s;current=null;invalidate();notifyChange();
    }

    void load(Bitmap decoded){
        release();
        bitmap=decoded;original=decoded.copy(decoded.getConfig()==null?Bitmap.Config.ARGB_8888:decoded.getConfig(),false);
        strokes.clear();fitToView();notifyChange();
    }
    private void release(){
        finishEdit(true);
        strokes.clear();strokeUndo.clear();current=null;selectedText=null;draggingText=null;rotationPreview=false;
        for(Bitmap b:undo)if(b!=null&&!b.isRecycled())b.recycle();
        undo.clear();
        if(original!=null&&!original.isRecycled())original.recycle();
        original=null;
    }
    /** 回到刚载入时的状态。 */
    void resetToOriginal(){
        if(original==null)return;
        finishEdit(true);
        for(Bitmap b:undo)if(b!=null&&!b.isRecycled())b.recycle();
        undo.clear();strokes.clear();strokeUndo.clear();current=null;selectedText=null;draggingText=null;rotationPreview=false;
        bitmap=original.copy(original.getConfig()==null?Bitmap.Config.ARGB_8888:original.getConfig(),false);
        fitToView();notifyChange();
    }

    void setMode(int value){
        if(mode==MODE_CROP&&value!=MODE_CROP){}else if(value==MODE_CROP){commitStrokes();}
        mode=value;
        if(mode!=MODE_VIEW){panning=false;fitToView();}
        if(mode==MODE_CROP)initCrop();
        notifyChange();
    }

    /* ===== 变换：直接作用在位图上 ===== */
    void beginRotationPreview(){if(bitmap!=null){rotationPreview=true;rotationDegrees=0;rotationZoom=1f;fitToView();}}
    void previewRotationZoom(float zoom){if(!rotationPreview||Float.isNaN(zoom)||Float.isInfinite(zoom))return;rotationZoom=Math.max(1f,Math.min(3f,zoom));invalidate();}
    void previewRotation(float degrees){if(!rotationPreview||Float.isNaN(degrees)||Float.isInfinite(degrees))return;rotationDegrees=degrees;fitToView();}
    void cancelRotationPreview(){rotationPreview=false;rotationDegrees=0;rotationZoom=1f;fitToView();}
    void applyRotationPreview(){float degrees=rotationDegrees,zoom=rotationZoom;rotationPreview=false;rotationDegrees=0;rotationZoom=1f;if(degrees!=0||zoom!=1f)rotateBy(degrees,zoom);else fitToView();}
    void rotate(boolean clockwise){
        if(bitmap==null)return;
        commitStrokes();Matrix turn=new Matrix();turn.postRotate(clockwise?90f:-90f);
        Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),turn,true);
        pushUndo();bitmap=rotated;fitToView();notifyChange();
    }
    /** 保持画布尺寸，绕中心旋转并填满画布，裁掉越界内容。 */
    void rotateBy(float degrees){
        rotateBy(degrees,1f);
    }
    private void rotateBy(float degrees,float zoom){
        if(bitmap==null||(degrees==0&&zoom==1f))return;
        commitStrokes();pushUndo();
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(bitmap,rotationMatrix(degrees,zoom),imagePaint);
        bitmap=out;
        fitToView();notifyChange();
    }
    private Matrix rotationMatrix(float degrees,float zoom){
        float w=bitmap.getWidth(),h=bitmap.getHeight();double radians=Math.toRadians(degrees);float cosine=Math.abs((float)Math.cos(radians)),sine=Math.abs((float)Math.sin(radians));
        float cover=Math.max(cosine+sine*h/w,cosine+sine*w/h)*zoom;
        Matrix matrix=new Matrix();matrix.postTranslate(-w/2,-h/2);matrix.postRotate(degrees);matrix.postScale(cover,cover);matrix.postTranslate(w/2,h/2);return matrix;
    }
    void flip(boolean horizontal){
        if(bitmap==null)return;
        commitStrokes();pushUndo();
        Matrix m=new Matrix();m.postScale(horizontal?-1f:1f,horizontal?1f:-1f);
        bitmap=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);
        fitToView();notifyChange();
    }
    void resize(float factor){
        if(bitmap==null||factor<=0)return;
        int w=Math.max(1,Math.round(bitmap.getWidth()*factor)),h=Math.max(1,Math.round(bitmap.getHeight()*factor));
        if(w==bitmap.getWidth()&&h==bitmap.getHeight())return;
        commitStrokes();pushUndo();
        bitmap=Bitmap.createScaledBitmap(bitmap,w,h,true);
        fitToView();notifyChange();
    }
    void applyCrop(){
        if(bitmap==null||crop.isEmpty())return;
        int x=Math.round((crop.left-dx)/scale),y=Math.round((crop.top-dy)/scale);
        int w=Math.round(crop.width()/scale),h=Math.round(crop.height()/scale);
        x=Math.max(0,Math.min(bitmap.getWidth()-1,x));y=Math.max(0,Math.min(bitmap.getHeight()-1,y));
        w=Math.max(1,Math.min(bitmap.getWidth()-x,w));h=Math.max(1,Math.min(bitmap.getHeight()-y,h));
        if(x==0&&y==0&&w==bitmap.getWidth()&&h==bitmap.getHeight()){mode=MODE_VIEW;fitToView();notifyChange();return;}
        pushUndo();
        bitmap=Bitmap.createBitmap(bitmap,x,y,w,h);
        mode=MODE_VIEW;fitToView();notifyChange();
    }
    /** 是否有未保存的修改：撤销栈或涂鸦非空即为脏（全部撤销回原状后自动变干净），退出前用它提示。 */
    boolean dirty(){return !undo.isEmpty()||!strokes.isEmpty();}
    void undo(){
        if(!strokeUndo.isEmpty()){strokeUndo.remove(strokeUndo.size()-1).run();if(!strokes.contains(selectedText))selectedText=null;invalidate();notifyChange();return;}
        if(!undo.isEmpty()){
            Bitmap previous=undo.remove(undo.size()-1);
            if(bitmap!=null&&!bitmap.isRecycled()&&bitmap!=previous)retire(bitmap);
            bitmap=previous;fitToView();notifyChange();
        }
    }
    /** 把涂鸦烧进位图，返回可直接保存的位图（不改动画布状态）。 */
    Bitmap render(){
        if(bitmap==null)return null;
        if(strokes.isEmpty())return bitmap;
        Bitmap out=bitmap.copy(Bitmap.Config.ARGB_8888,true);
        Canvas canvas=new Canvas(out);
        for(Stroke s:strokes)drawStroke(canvas,out,s);
        return out;
    }

    private void pushUndo(){
        if(bitmap==null)return;
        if(!undo.isEmpty()&&undo.get(undo.size()-1)==bitmap)return;
        undo.add(bitmap);
        while(undo.size()>MAX_UNDO){Bitmap old=undo.remove(0);if(old!=null&&old!=bitmap&&!undo.contains(old)&&!old.isRecycled())retire(old);}
    }
    private void commitStrokes(){
        if(strokes.isEmpty())return;
        pushUndo();
        Bitmap out=bitmap.copy(Bitmap.Config.ARGB_8888,true);
        Canvas canvas=new Canvas(out);
        for(Stroke s:strokes)drawStroke(canvas,out,s);
        strokes.clear();strokeUndo.clear();current=null;selectedText=null;draggingText=null;bitmap=out;
    }
    /** 统一绘制一笔（普通笔 / 马赛克 / 文本）；src 为取样位图（onDraw 用 bitmap，render/commit 用拷贝）。 */
    private void drawStroke(Canvas canvas,Bitmap src,Stroke s){
        if(s.tool==TOOL_MOSAIC){
            canvas.save();canvas.clipPath(s.path);
            int block=Math.max(6,(int)(s.width));   // 马赛克块边长 = 画笔粗细
            RectF b=new RectF();s.path.computeBounds(b,true);
            int x0=Math.max(0,(int)Math.floor(b.left)),y0=Math.max(0,(int)Math.floor(b.top));
            int x1=Math.min(src.getWidth(),(int)Math.ceil(b.right)),y1=Math.min(src.getHeight(),(int)Math.ceil(b.bottom));
            inkPaint.setStyle(Paint.Style.FILL);
            for(int y=y0;y<y1;y+=block){
                for(int x=x0;x<x1;x+=block){
                    int cx=Math.min(src.getWidth()-1,x+block/2),cy=Math.min(src.getHeight()-1,y+block/2);
                    inkPaint.setColor(src.getPixel(cx,cy));
                    canvas.drawRect(x,y,Math.min(x1,x+block),Math.min(y1,y+block),inkPaint);
                }
            }
            inkPaint.setStyle(Paint.Style.STROKE);
            canvas.restore();
        }else if(s.tool==TOOL_TEXT){
            inkPaint.setStyle(Paint.Style.FILL);
            inkPaint.setColor(s.color);
            inkPaint.setTextSize(s.textSize);
            float baseline=s.textY;for(String line:s.text.split("\n",-1)){canvas.drawText(line,s.textX,baseline,inkPaint);baseline+=inkPaint.getFontSpacing();}
        }else{
            inkPaint.setStyle(Paint.Style.STROKE);
            inkPaint.setColor(s.color);
            inkPaint.setStrokeWidth(s.width);
            canvas.drawPath(s.path,inkPaint);
        }
    }
    private void notifyChange(){if(onChange!=null)onChange.run();}

    /* ===== 视图适配 ===== */
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);fitToView();}
    private void fitToView(){
        if(bitmap==null)return;
        float width=getWidth(),height=getHeight()-bottomInset;
        if(width<=0||height<=0)return;
        float pad=dp(14);
        float imageW=bitmap.getWidth(),imageH=bitmap.getHeight();
        float s=Math.min((width-2*pad)/imageW,(height-2*pad)/imageH);
        scale=s>0?s:1f;fitScale=scale;
        dx=(width-bitmap.getWidth()*scale)/2f;
        dy=(height-bitmap.getHeight()*scale)/2f;
        applyMatrix();
        if(mode==MODE_CROP)initCrop();
        invalidate();
    }
    private void applyMatrix(){display.reset();display.postScale(scale,scale);display.postTranslate(dx,dy);display.getValues(values);}
    private void zoomBy(float factor,float focusX,float focusY){
        if(bitmap==null)return;
        float target=Math.max(fitScale*0.5f,Math.min(fitScale*10f,scale*factor));
        float ratio=target/scale;
        // 围绕双指焦点缩放：焦点下的图像内容保持不动。缩放过程中不做 clamp，
        // 否则 clampPan 会改变 dx/dy 导致焦点漂移；改为手势结束（UP/POINTER_UP）后再钳制。
        dx=focusX-(focusX-dx)*ratio;dy=focusY-(focusY-dy)*ratio;scale=target;
        applyMatrix();invalidate();
    }
    /** 子页面面板打开时留出底部空间，否则图片下半部分会被面板盖住。 */
    void setBottomInset(int pixels){
        int value=Math.max(0,pixels);
        if(value==bottomInset)return;
        bottomInset=value;fitToView();
    }
    int getBottomInset(){return bottomInset;}
    private void clampPan(){
        if(bitmap==null)return;
        float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale,margin=dp(48);
        dx=Math.min(getWidth()-margin,Math.max(margin-w,dx));
        dy=Math.min(getHeight()-bottomInset-margin,Math.max(margin-h,dy));
    }
    private void initCrop(){
        if(bitmap==null)return;
        float inset=0f;
        crop.set(dx+bitmap.getWidth()*scale*inset,dy+bitmap.getHeight()*scale*inset,
                 dx+bitmap.getWidth()*scale*(1-inset),dy+bitmap.getHeight()*scale*(1-inset));
    }

    @Override protected void onDraw(Canvas canvas){
        if(bitmap==null)return;
        if(rotationPreview){
            canvas.save();canvas.concat(display);canvas.clipRect(0,0,bitmap.getWidth(),bitmap.getHeight());canvas.concat(rotationMatrix(rotationDegrees,rotationZoom));
            canvas.drawBitmap(bitmap,0,0,imagePaint);for(Stroke s:strokes)drawStroke(canvas,bitmap,s);canvas.restore();return;
        }
        if(adjustmentPreview!=null){canvas.save();canvas.concat(display);canvas.drawBitmap(adjustmentPreview,null,new RectF(0,0,bitmap.getWidth(),bitmap.getHeight()),imagePaint);canvas.restore();}else canvas.drawBitmap(bitmap,display,imagePaint);
        if(!strokes.isEmpty()){
            canvas.save();canvas.concat(display);
            for(Stroke s:strokes)drawStroke(canvas,bitmap,s);
            if(mode==MODE_DRAW&&brushTool==TOOL_TEXT&&selectedText!=null&&!rotationPreview){RectF bounds=textBounds(selectedText);bounds.inset(-dp(5)/scale,-dp(5)/scale);framePaint.setStrokeWidth(dp(1)/scale);canvas.drawRoundRect(bounds,dp(3)/scale,dp(3)/scale,framePaint);framePaint.setStrokeWidth(dp(1.5f));}
            canvas.restore();
        }
        if(mode==MODE_CROP){
            canvas.drawRect(0,0,getWidth(),crop.top,scrimPaint);
            canvas.drawRect(0,crop.bottom,getWidth(),getHeight(),scrimPaint);
            canvas.drawRect(0,crop.top,crop.left,crop.bottom,scrimPaint);
            canvas.drawRect(crop.right,crop.top,getWidth(),crop.bottom,scrimPaint);
            canvas.drawRect(crop,framePaint);
            float size=dp(18);
            for(int x=0;x<2;x++)for(int y=0;y<2;y++){
                float px=x==0?crop.left:crop.right,py=y==0?crop.top:crop.bottom;
                canvas.drawLine(px,py,px+(x==0?size:-size),py,handlePaint);
                canvas.drawLine(px,py,px,py+(y==0?size:-size),handlePaint);
            }
            canvas.drawLine(crop.centerX()-size/2,crop.top,crop.centerX()+size/2,crop.top,handlePaint);
            canvas.drawLine(crop.centerX()-size/2,crop.bottom,crop.centerX()+size/2,crop.bottom,handlePaint);
            canvas.drawLine(crop.left,crop.centerY()-size/2,crop.left,crop.centerY()+size/2,handlePaint);
            canvas.drawLine(crop.right,crop.centerY()-size/2,crop.right,crop.centerY()+size/2,handlePaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(bitmap==null)return false;
        if(rotationPreview)return true;
        if(mode==MODE_VIEW)return handleViewTouch(event);
        if(mode==MODE_CROP)return handleCropTouch(event);
        return handleDrawTouch(event);
    }
    private boolean handleViewTouch(MotionEvent event){
        scaleDetector.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:lastX=event.getX();lastY=event.getY();panning=true;return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下：立即停止单指平移，重置基准，避免双指缩放起点跳变。
                panning=false;lastX=event.getX();lastY=event.getY();return true;
            case MotionEvent.ACTION_MOVE:
                if(scaleDetector.isInProgress())return true;   // 双指缩放中，交给 onScale 处理，不平移
                if(panning&&event.getPointerCount()==1){
                    dx+=event.getX()-lastX;dy+=event.getY()-lastY;lastX=event.getX();lastY=event.getY();clampPan();applyMatrix();invalidate();}
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // 一根手指抬起：以剩余手指位置为新基准，继续平移；顺带把缩放后的越界钳制回来。
                clampPan();applyMatrix();invalidate();
                lastX=event.getX();lastY=event.getY();return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                panning=false;clampPan();applyMatrix();invalidate();return true;
            default:return true;
        }
    }
    private boolean handleDrawTouch(MotionEvent event){
        if(brushTool==TOOL_TEXT){
            return handleTextTouch(event);
        }
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                current=new Stroke(brushColor,brushWidth/scale,brushTool,null,0,0,0);
                current.path.moveTo(toBitmapX(event.getX()),toBitmapY(event.getY()));
                current.path.lineTo(toBitmapX(event.getX())+0.01f,toBitmapY(event.getY()));
                Stroke added=current;strokes.add(added);strokeUndo.add(()->strokes.remove(added));invalidate();notifyChange();return true;
            case MotionEvent.ACTION_MOVE:
                if(current!=null&&event.getPointerCount()==1){current.path.lineTo(toBitmapX(event.getX()),toBitmapY(event.getY()));invalidate();}
                return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:current=null;invalidate();notifyChange();return true;
            default:return true;
        }
    }
    private RectF textBounds(Stroke text){
        inkPaint.setTextSize(text.textSize);Paint.FontMetrics metrics=inkPaint.getFontMetrics();float width=0;String[] lines=text.text.split("\n",-1);for(String line:lines)width=Math.max(width,inkPaint.measureText(line));
        return new RectF(text.textX,text.textY+metrics.ascent,text.textX+width,text.textY+(lines.length-1)*inkPaint.getFontSpacing()+metrics.descent);
    }
    private Stroke textAt(float x,float y){float padding=dp(12)/scale;for(int i=strokes.size()-1;i>=0;i--){Stroke text=strokes.get(i);if(text.tool!=TOOL_TEXT)continue;RectF bounds=textBounds(text);bounds.inset(-padding,-padding);if(bounds.contains(x,y))return text;}return null;}
    private boolean handleTextTouch(MotionEvent event){
        float x=toBitmapX(event.getX()),y=toBitmapY(event.getY());
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                draggingText=textAt(x,y);selectedText=draggingText;textTouchX=x;textTouchY=y;textGestureCancelled=false;
                if(draggingText!=null){textStartX=draggingText.textX;textStartY=draggingText.textY;}invalidate();return true;
            case MotionEvent.ACTION_POINTER_DOWN:textGestureCancelled=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(draggingText!=null&&!textGestureCancelled){RectF bounds=textBounds(draggingText);float w=bounds.width(),h=bounds.height();float baseline=draggingText.textY-bounds.top;
                    draggingText.textX=Math.max(0,Math.min(Math.max(0,bitmap.getWidth()-w),textStartX+x-textTouchX));
                    draggingText.textY=Math.max(baseline,Math.min(Math.max(baseline,bitmap.getHeight()-h+baseline),textStartY+y-textTouchY));invalidate();}
                return true;
            case MotionEvent.ACTION_UP:
                if(draggingText!=null){Stroke text=draggingText;float oldX=textStartX,oldY=textStartY;if(textGestureCancelled){text.textX=oldX;text.textY=oldY;}else if(text.textX!=oldX||text.textY!=oldY)strokeUndo.add(()->{text.textX=oldX;text.textY=oldY;});draggingText=null;invalidate();notifyChange();}
                else if(!textGestureCancelled&&Math.hypot(x-textTouchX,y-textTouchY)*scale<dp(12)&&onTextTap!=null&&x>=0&&y>=0&&x<=bitmap.getWidth()&&y<=bitmap.getHeight())onTextTap.onTap(x,y);
                return true;
            case MotionEvent.ACTION_CANCEL:if(draggingText!=null){draggingText.textX=textStartX;draggingText.textY=textStartY;draggingText=null;}textGestureCancelled=true;invalidate();return true;
            default:return true;
        }
    }
    private boolean handleCropTouch(MotionEvent event){
        float hit=dp(34);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                cropHit=hitTest(event.getX(),event.getY(),hit);cropTouchX=event.getX();cropTouchY=event.getY();cropStart.set(crop);return true;
            case MotionEvent.ACTION_MOVE:{
                if(cropHit==0)return true;
                float moveX=event.getX()-cropTouchX,moveY=event.getY()-cropTouchY;
                float min=dp(48);
                if(cropHit==5){crop.set(cropStart);crop.offset(moveX,moveY);keepInside();}
                else{
                    float left=cropStart.left,top=cropStart.top,right=cropStart.right,bottom=cropStart.bottom;
                    if(cropHit==1||cropHit==3||cropHit==6)left=Math.min(cropStart.left+moveX,right-min);
                    if(cropHit==2||cropHit==4||cropHit==7)right=Math.max(cropStart.right+moveX,left+min);
                    if(cropHit==1||cropHit==2||cropHit==8)top=Math.min(cropStart.top+moveY,bottom-min);
                    if(cropHit==3||cropHit==4||cropHit==9)bottom=Math.max(cropStart.bottom+moveY,top+min);
                    crop.set(left,top,right,bottom);keepInside();
                }
                invalidate();return true;}
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:cropHit=0;return true;
            default:return true;
        }
    }
    private int hitTest(float x,float y,float radius){
        if(Math.hypot(x-crop.left,y-crop.top)<=radius)return 1;
        if(Math.hypot(x-crop.right,y-crop.top)<=radius)return 2;
        if(Math.hypot(x-crop.left,y-crop.bottom)<=radius)return 3;
        if(Math.hypot(x-crop.right,y-crop.bottom)<=radius)return 4;
        if(Math.hypot(x-crop.left,y-crop.centerY())<=radius)return 6;
        if(Math.hypot(x-crop.right,y-crop.centerY())<=radius)return 7;
        if(Math.hypot(x-crop.centerX(),y-crop.top)<=radius)return 8;
        if(Math.hypot(x-crop.centerX(),y-crop.bottom)<=radius)return 9;
        if(crop.contains(x,y))return 5;
        return 0;
    }
    private void keepInside(){
        float min=dp(48);
        if(crop.width()<min)crop.right=crop.left+min;
        if(crop.height()<min)crop.bottom=crop.top+min;
        if(crop.left<0)crop.offset(-crop.left,0);
        if(crop.top<0)crop.offset(0,-crop.top);
        if(crop.right>getWidth())crop.offset(getWidth()-crop.right,0);
        if(crop.bottom>getHeight())crop.offset(0,getHeight()-crop.bottom);
    }
    private float toBitmapX(float x){return (x-dx)/scale;}
    private float toBitmapY(float y){return (y-dy)/scale;}
}
