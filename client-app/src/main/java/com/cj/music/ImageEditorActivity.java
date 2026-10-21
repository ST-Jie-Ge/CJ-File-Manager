package com.cj.music;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.cj.music.resources.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/** 内置图片编辑器：变换 / 裁剪 / 缩放 / 涂鸦，保存为自动编号的副本。 */
public class ImageEditorActivity extends AppCompatActivity {
    static final String EXTRA_PATH="path";
    /** 保存成功后回传给调用方的新副本绝对路径，调用方据此定位到编辑结果而不是原图。 */
    static final String EXTRA_SAVED="saved";
    /** 编辑时位图长边上限：控制内存（原图文件不受影响，只影响本次编辑分辨率）。 */
    private static final int MAX_LONG_SIDE=2560;
    /** 子页面：不在主界面堆按钮，旋转翻转进「变换」，画笔进「涂鸦」。 */
    private static final int PAGE_NONE=0, PAGE_TRANSFORM=1, PAGE_DRAW=2;

    private SharedPreferences prefs;
    private File source;
    private ImageEditorView canvas;
    private boolean resizePanelOpen;
    private android.widget.ImageButton cropButton,drawButton,undoButton;
    private android.widget.ImageButton toolPen,toolMosaic,toolText;
    private TextView widthValue;
    private LinearLayout mainBar;
    /** 顶部功能入口行（翻转／旋转／还原／裁剪比例／标注／更多）：进入功能页后隐藏。 */
    private LinearLayout photoTools;
    private View pagePanel;
    private TextView pageTitle;
    private LinearLayout transformPage,drawPage;
    private Bitmap loaded;
    private LinearLayout rotationPanel;
    private Runnable applyRotationAction;
    private int rotationOldInset,rotationOldPageVisibility;
    private int page=PAGE_NONE;

    /** 调色板：白、黑两色必须有深色描边，否则白色块在浅色背景上等于隐形。 */
    private static final int[] PALETTE={0xFFE53935,0xFFFFC107,0xFF1E88E5,0xFFFFFFFF,0xFF111111};
    private final ImageView[] swatches=new ImageView[PALETTE.length];
    private int colorIndex;
    private LinearLayout photoControls;
    private int photoTab=-1,correction=0,adjustment=0;private final int[] adjustmentValues={100,100,100};
    private TextView photoBack,photoDone;
    private boolean rulerPreview;

    @Override protected void onCreate(Bundle state){
        prefs=getSharedPreferences("client",MODE_PRIVATE);
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);   // 编辑图片时隐藏系统状态栏
        boolean light=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES;
        new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView()).setAppearanceLightStatusBars(light);
        setContentView(R.layout.client_image_editor);

        String path=getIntent().getStringExtra(EXTRA_PATH);
        if(path==null){toast("没有可编辑的图片");finish();return;}
        source=new File(path);
        if(!source.exists()){toast("图片不存在");finish();return;}

        canvas=findViewById(R.id.client_editor_canvas);
        cropButton=findViewById(R.id.client_editor_crop);
        drawButton=findViewById(R.id.client_editor_draw);
        undoButton=findViewById(R.id.client_editor_undo);
        mainBar=findViewById(R.id.client_editor_main_bar);
        widthValue=findViewById(R.id.client_brush_width_value);
        pagePanel=findViewById(R.id.client_editor_page);
        pageTitle=findViewById(R.id.client_editor_page_title);
        transformPage=findViewById(R.id.client_editor_transform_page);
        drawPage=findViewById(R.id.client_editor_draw_page);
        ((TextView)findViewById(R.id.client_editor_title)).setText(source.getName());

        findViewById(R.id.client_editor_back).setOnClickListener(v->{if(isEditing())cancelCurrentEdit();else finish();});
        findViewById(R.id.client_editor_save).setOnClickListener(v->applyOrSave());
        findViewById(R.id.client_editor_cancel).setOnClickListener(v->cancelCurrentEdit());
        cropButton.setOnClickListener(v->{if(canvas.getMode()==ImageEditorView.MODE_CROP)cancelCurrentEdit();else {canvas.beginEdit();canvas.setMode(ImageEditorView.MODE_CROP);}});
        drawButton.setOnClickListener(v->showPage(PAGE_DRAW));
        findViewById(R.id.client_editor_transform).setOnClickListener(v->showPage(PAGE_TRANSFORM));
        findViewById(R.id.client_editor_rotate_left).setOnClickListener(v->{canvas.rotate(false);syncToolbar();});
        findViewById(R.id.client_editor_rotate_right).setOnClickListener(v->{canvas.rotate(true);syncToolbar();});
        findViewById(R.id.client_editor_flip_h).setOnClickListener(v->{canvas.flip(true);syncToolbar();});
        findViewById(R.id.client_editor_flip_v).setOnClickListener(v->{canvas.flip(false);syncToolbar();});
        findViewById(R.id.client_editor_resize).setOnClickListener(v->resizeDialog());
        findViewById(R.id.client_editor_undo).setOnClickListener(v->{canvas.undo();syncToolbar();});
        findViewById(R.id.client_editor_reset).setOnClickListener(v->new MaterialAlertDialogBuilder(this).setTitle("放弃所有修改？")
            .setMessage("将回到刚打开这张图片时的状态。").setNegativeButton("取消",null)
            .setPositiveButton("重置",(d,w)->{canvas.resetToOriginal();syncToolbar();}).show());

        // 配色：色块由代码拼三层（选中光圈 + 黑色描边 + 实色），backgroundTint 会把描边一起染掉，不能用。
        int[] ids={R.id.client_brush_color_0,R.id.client_brush_color_1,R.id.client_brush_color_2,R.id.client_brush_color_3,R.id.client_brush_color_4};
        for(int i=0;i<ids.length;i++){
            swatches[i]=findViewById(ids[i]);
            final int index=i;
            swatches[i].setOnClickListener(v->selectColor(index));
        }
        paintSwatches();

        Slider width=findViewById(R.id.client_brush_width);
        width.addOnChangeListener((slider,value,fromUser)->setBrushWidth(value));
        setBrushWidth(width.getValue());

        // 画笔工具切换：画笔 / 马赛克 / 文字
        toolPen=findViewById(R.id.client_brush_tool_pen);
        toolMosaic=findViewById(R.id.client_brush_tool_mosaic);
        toolText=findViewById(R.id.client_brush_tool_text);
        toolPen.setOnClickListener(v->{canvas.setBrushTool(ImageEditorView.TOOL_PEN);syncDraw();});
        toolMosaic.setOnClickListener(v->{canvas.setBrushTool(ImageEditorView.TOOL_MOSAIC);syncDraw();});
        toolText.setOnClickListener(v->{canvas.setBrushTool(ImageEditorView.TOOL_TEXT);syncDraw();});

        // 自由角度旋转
        findViewById(R.id.client_editor_rotate_free).setOnClickListener(v->rotateFreeDialog());

        // 文本模式下点击画布 → 弹输入框落文字
        canvas.setOnTextTap(this::promptText);
        canvas.setOnChange(this::syncToolbar);
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){photoNavigateBack();}
        });
        // Keep accessibility names without displaying operation hints.
        selectColor(0);
        syncDraw();
        loadBitmap();
        if(!isFinishing())installPhotoChrome();
    }

    /** Retain the existing drawing/resize panels while replacing the editor chrome. */
    private void installPhotoChrome(){
        android.widget.FrameLayout host=new android.widget.FrameLayout(this);host.setBackgroundColor(PhotoUi.PAPER);host.setFitsSystemWindows(true);
        View legacy=((android.view.ViewGroup)findViewById(android.R.id.content)).getChildAt(0);
        ((android.view.ViewGroup)legacy.getParent()).removeView(legacy);legacy.setVisibility(View.GONE);host.addView(legacy);
        ((android.view.ViewGroup)canvas.getParent()).removeView(canvas);((android.view.ViewGroup)pagePanel.getParent()).removeView(pagePanel);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);host.addView(body,new android.widget.FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=photoRow();header.setPadding(dp(24),dp(16),dp(24),dp(8));
        photoBack=photoText("返回",PhotoUi.INK);photoBack.setBackground(PhotoUi.bg(0xffdedee0,dp(30)));photoBack.setOnClickListener(v->photoNavigateBack());header.addView(photoBack,new LinearLayout.LayoutParams(dp(78),dp(40)));
        header.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));photoDone=photoText("保存副本",PhotoUi.INK);photoDone.setBackground(PhotoUi.bg(PhotoUi.YELLOW,dp(30)));photoDone.setOnClickListener(v->{if(photoEditing()){commitPhotoTool();selectPhotoTab(-1);}else saveAsCopy();});header.addView(photoDone,new LinearLayout.LayoutParams(dp(90),dp(40)));body.addView(header);
        LinearLayout tools=photoRow();tools.setPadding(dp(10),0,dp(10),0);
        addPhotoIcon(tools,"flip","水平翻转",()->{prepareCropOperation();canvas.flip(true);});
        addPhotoIcon(tools,"rotate","向左旋转90°",()->{prepareCropOperation();canvas.rotate(false);canvas.animate().cancel();canvas.setAlpha(.8f);canvas.animate().alpha(1).setDuration(140).start();});
        TextView restore=photoText("还原",0xffc49b00);tools.addView(restore,new LinearLayout.LayoutParams(0,dp(48),1));restore.setOnClickListener(v->new MaterialAlertDialogBuilder(this).setTitle("还原到原图？").setNegativeButton("取消",null).setPositiveButton("还原",(d,w)->{canvas.clearColor();canvas.resetToOriginal();selectPhotoTab(photoTab);}).show());
        addPhotoIcon(tools,"aspect","裁剪比例",()->new MaterialAlertDialogBuilder(this).setTitle("裁剪比例").setItems(new String[]{"原始比例","正方形 1:1","4:3","3:4","16:9"},(d,w)->{selectPhotoTab(2);float[] ratios={0,1,4f/3,3f/4,16f/9};canvas.cropAspect(ratios[w]);}).show());
        addPhotoIcon(tools,"pen","标注",()->{commitPhotoTool();showPage(PAGE_DRAW);});
        addPhotoIcon(tools,"more","更多",()->new MaterialAlertDialogBuilder(this).setItems(new String[]{"撤销上一步","旋转与翻转","调整图片尺寸"},(d,w)->{commitPhotoTool();if(w==0){canvas.undo();selectPhotoTab(photoTab);}else if(w==1)showPage(PAGE_TRANSFORM);else resizeDialog();}).show());body.addView(tools);photoTools=tools;
        canvas.setBackgroundColor(PhotoUi.PAPER);body.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        photoControls=new LinearLayout(this);photoControls.setOrientation(LinearLayout.VERTICAL);photoControls.setPadding(dp(12),dp(12),dp(12),dp(18));body.addView(photoControls);
        host.addView(pagePanel,new android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM));
        setContentView(host);getWindow().setNavigationBarColor(PhotoUi.PAPER);new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView()).setAppearanceLightNavigationBars(true);
        pagePanel.setBackground(PhotoUi.bg(PhotoUi.PAPER,dp(24)));pagePanel.setPadding(dp(20),dp(8),dp(20),dp(20));pageTitle.setTextSize(14);pageTitle.setTextColor(PhotoUi.MUTED);syncDraw();
        selectPhotoTab(-1);
    }
    private boolean photoEditing(){return photoTab>=0||isEditing()||rulerPreview;}
    /** 顶部按钮文案随状态变化；进入功能页（调整／滤镜／裁剪、画笔、旋转翻转、尺寸、裁剪框）后
        同时隐藏顶部那排功能入口，避免在操作页里继续点这些无关入口。 */
    private void updatePhotoHeader(){if(photoDone==null)return;boolean editing=photoEditing();photoBack.setText(editing?"上一级":"返回");photoDone.setText(editing?"完成":"保存副本");if(photoTools!=null)photoTools.setVisibility(editing?View.GONE:View.VISIBLE);}
    private void photoNavigateBack(){if(photoControls==null){if(isEditing())cancelCurrentEdit();else confirmDiscardEdits();return;}if(photoEditing()){if(rotationPanel!=null){closeRotationPanel(false);updatePhotoHeader();return;}canvas.clearColor();if(rulerPreview){canvas.cancelRotationPreview();rulerPreview=false;}canvas.finishEdit(false);showPage(PAGE_NONE);photoTab=-1;selectPhotoTab(-1);}else confirmDiscardEdits();}
    /** 退出编辑器前确认：有未保存的编辑时不再静默丢弃（以前按返回键直接 finish()）。 */
    private void confirmDiscardEdits(){
        if(canvas==null||!canvas.dirty()){finish();return;}
        new MaterialAlertDialogBuilder(this).setTitle("放弃未保存的编辑？").setMessage("当前图片的修改还没有保存，返回后将会丢失。")
            .setNegativeButton("继续编辑",null).setPositiveButton("放弃",(d,w)->finish()).show();
    }
    private void prepareCropOperation(){if(photoTab!=2||page!=PAGE_NONE)selectPhotoTab(2);if(rulerPreview){canvas.applyRotationPreview();rulerPreview=false;}canvas.beginEdit();}
    private LinearLayout photoRow(){LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER);return row;}
    private TextView photoText(String text,int color){TextView t=new TextView(this);t.setText(text);t.setTextColor(color);t.setTextSize(15);t.setGravity(android.view.Gravity.CENTER);return t;}
    private void addPhotoIcon(LinearLayout row,String icon,String label,Runnable action){row.addView(PhotoUi.button(this,icon,label,PhotoUi.MUTED,action),new LinearLayout.LayoutParams(dp(44),dp(46)));}
    private void commitPhotoTool(){
        if(rulerPreview){canvas.applyRotationPreview();rulerPreview=false;}
        canvas.applyColor();
        if(rotationPanel!=null&&applyRotationAction!=null){applyRotationAction.run();return;}
        if(canvas.getMode()==ImageEditorView.MODE_CROP)canvas.applyCrop();
        canvas.finishEdit(true);showPage(PAGE_NONE);
    }
    private void selectPhotoTab(int tab){
        if(tab==1)return; // Reserved filter entry: no operation and no message.
        commitPhotoTool();photoTab=tab;java.util.Arrays.fill(adjustmentValues,100);adjustment=0;photoControls.removeAllViews();
        if(tab>=0)canvas.beginEdit();canvas.setMode(tab==2?ImageEditorView.MODE_CROP:ImageEditorView.MODE_VIEW);updatePhotoHeader();
        LinearLayout choices=photoRow();photoControls.addView(choices,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView value=photoText(tab==2?"自由旋转 0°":"亮度",PhotoUi.MUTED);value.setTextSize(11);photoControls.addView(value,new LinearLayout.LayoutParams(-1,dp(24)));
        PhotoRuler ruler=new PhotoRuler(this);ruler.setMax(200);ruler.setProgress(100);ruler.setContentDescription(tab==2?"裁剪校正角度":"亮度");photoControls.addView(ruler,new LinearLayout.LayoutParams(-1,dp(44)));
        if(tab==2){
            String[] icons={"adjust","vertical","horizontal"},labels={"自由旋转","垂直透视","水平透视"};
            for(int i=0;i<3;i++){final int which=i;android.widget.ImageButton b=PhotoUi.button(this,icons[i],labels[i],PhotoUi.INK,()->{if(rulerPreview){canvas.applyRotationPreview();rulerPreview=false;canvas.setMode(ImageEditorView.MODE_CROP);}correction=which;value.setText(labels[which]);ruler.setProgress(100);for(int j=0;j<choices.getChildCount();j++)choices.getChildAt(j).setAlpha(j==which?1f:.45f);});android.graphics.drawable.GradientDrawable ring=PhotoUi.bg(0x00ffffff,dp(40));ring.setStroke(PhotoUi.dp(this,1.5f),0xffb5b5b8);b.setBackground(ring);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(50),dp(50));lp.setMargins(dp(12),0,dp(12),0);choices.addView(b,lp);b.setAlpha(i==correction?1f:.45f);}
        }else if(tab==0){String[] labels={"亮度","对比度","自然饱和度"};for(int i=0;i<labels.length;i++){final int which=i;TextView item=photoText(labels[i],PhotoUi.INK);item.setTextSize(13);item.setAlpha(i==adjustment?1:.45f);choices.addView(item,new LinearLayout.LayoutParams(0,dp(48),1));item.setOnClickListener(v->{adjustment=which;ruler.setProgress(adjustmentValues[which]);ruler.setContentDescription(labels[which]);value.setText(labels[which]+" "+(adjustmentValues[which]-100));for(int j=0;j<choices.getChildCount();j++)choices.getChildAt(j).setAlpha(j==which?1:.45f);});}}
        else{choices.setVisibility(View.GONE);value.setVisibility(View.GONE);ruler.setVisibility(View.GONE);}
        ruler.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(android.widget.SeekBar bar){if(photoTab==2&&correction==0&&!rulerPreview){canvas.beginRotationPreview();rulerPreview=true;}}
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user){if(!user)return;float amount=(progress-100)/100f;value.setText(String.format(Locale.getDefault(),photoTab==2?"%.1f°":"%+.0f",amount*(photoTab==2?45:100)));if(photoTab==0){adjustmentValues[adjustment]=progress;canvas.previewAdjustments((adjustmentValues[0]-100)*.9f,1+(adjustmentValues[1]-100)/125f,(adjustmentValues[2]-100)/100f);}else if(photoTab==2&&correction==0)canvas.previewRotation(amount*45);}
            public void onStopTrackingTouch(android.widget.SeekBar bar){if(photoTab==2){if(correction!=0){canvas.perspective(correction==1,(bar.getProgress()-100)/100f);canvas.setMode(ImageEditorView.MODE_CROP);bar.setProgress(100);}}}
        });
        LinearLayout tabs=photoRow();String[] names={"调整","滤镜","裁剪"},icons={"adjust","filter","crop"};for(int i=0;i<3;i++){final int target=i;LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(android.view.Gravity.CENTER);TextView marker=photoText(i==tab?"▾":"",0xffeab800);item.addView(marker,new LinearLayout.LayoutParams(-1,dp(16)));item.addView(PhotoUi.button(this,icons[i],names[i],i==tab?PhotoUi.INK:PhotoUi.MUTED,()->selectPhotoTab(target)),new LinearLayout.LayoutParams(dp(44),dp(42)));TextView label=photoText(names[i],i==tab?PhotoUi.INK:PhotoUi.MUTED);label.setTextSize(10);item.addView(label);item.setOnClickListener(v->selectPhotoTab(target));tabs.addView(item,new LinearLayout.LayoutParams(dp(70),dp(78)));}photoControls.addView(tabs);
    }
    private static final class PhotoRuler extends androidx.appcompat.widget.AppCompatSeekBar {
        final android.graphics.Paint paint=new android.graphics.Paint(3);
        PhotoRuler(android.content.Context c){super(c);setBackgroundColor(Color.TRANSPARENT);setPadding(PhotoUi.dp(c,10),0,PhotoUi.dp(c,10),0);}
        @Override protected synchronized void onDraw(android.graphics.Canvas c){float left=getPaddingLeft(),width=getWidth()-left-getPaddingRight(),middle=getHeight()/2f;paint.setStrokeWidth(PhotoUi.dp(getContext(),1));for(int i=0;i<=40;i++){paint.setColor(i%5==0?0xff969699:0xffcdcdcf);float x=left+width*i/40;c.drawLine(x,middle-PhotoUi.dp(getContext(),i%5==0?8:5),x,middle+PhotoUi.dp(getContext(),5),paint);}paint.setColor(PhotoUi.INK);paint.setStrokeWidth(PhotoUi.dp(getContext(),2));float x=left+width*getProgress()/getMax();c.drawLine(x,middle-PhotoUi.dp(getContext(),18),x,middle+PhotoUi.dp(getContext(),8),paint);}
    }

    /* ===== 子页面 ===== */

    /** 切换到某个子页面（PAGE_NONE 回到主工具栏）。画布始终留在上方，改动实时可见。 */
    private void showPage(int target){
        boolean open=target!=PAGE_NONE;
        if(open&&target!=page){if(isEditing())canvas.finishEdit(false);canvas.beginEdit();}
        if(open&&target==PAGE_TRANSFORM)canvas.setMode(ImageEditorView.MODE_VIEW);
        if(open&&target==PAGE_DRAW)canvas.setMode(ImageEditorView.MODE_DRAW);
        if(!open&&canvas.getMode()==ImageEditorView.MODE_DRAW)canvas.setMode(ImageEditorView.MODE_VIEW);
        page=target;
        mainBar.setVisibility(open?View.GONE:View.VISIBLE);
        pagePanel.setVisibility(open?View.VISIBLE:View.GONE);
        transformPage.setVisibility(target==PAGE_TRANSFORM?View.VISIBLE:View.GONE);
        drawPage.setVisibility(target==PAGE_DRAW?View.VISIBLE:View.GONE);
        if(open)pageTitle.setText(target==PAGE_DRAW?R.string.client_editor_draw:R.string.client_editor_transform);
        if(open)measureInset();
        else canvas.setBottomInset(0);
        syncToolbar();
    }

    /** 面板盖住了画布底部，必须把这段高度让出来，否则图片下半部分被挡住没法检查/作画。 */
    private void measureInset(){
        pagePanel.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){
                ViewTreeObserver observer=pagePanel.getViewTreeObserver();
                if(observer.isAlive())observer.removeOnGlobalLayoutListener(this);
                canvas.setBottomInset(pagePanel.getHeight());
            }
        });
    }

    /* ===== 涂鸦 ===== */

    private void setBrushWidth(float value){
        canvas.setBrushWidth(value);
        widthValue.setText(String.valueOf(Math.round(value)));
    }

    private void selectColor(int index){
        colorIndex=index;
        canvas.setBrushColor(PALETTE[index]);
        paintSwatches();
    }

    /** 重画全部色块：选中项外面再套一圈强调色光圈。 */
    private void paintSwatches(){
        int accent=getThemeAccent();
        for(int i=0;i<swatches.length;i++){
            if(swatches[i]==null)continue;
            swatches[i].setImageDrawable(swatch(PALETTE[i],i==colorIndex,accent));
        }
    }

    /** 三层依次：选中光圈（未选中时透明占位，保证选中前后大小不动）、黑色描边、实色圆。 */
    private Drawable swatch(int color,boolean selected,int accent){
        GradientDrawable halo=new GradientDrawable();halo.setShape(GradientDrawable.OVAL);
        halo.setStroke(Math.round(2*density()),selected?accent:Color.TRANSPARENT);
        GradientDrawable edge=new GradientDrawable();edge.setShape(GradientDrawable.OVAL);
        edge.setStroke(Math.round(2*density()),0xFF000000);
        GradientDrawable fill=new GradientDrawable();fill.setShape(GradientDrawable.OVAL);fill.setColor(color);
        LayerDrawable layered=new LayerDrawable(new Drawable[]{halo,edge,fill});
        int haloInset=Math.round(1*density()),edgeInset=Math.round(3*density()),fillInset=Math.round(4*density());
        layered.setLayerInset(1,edgeInset,edgeInset,edgeInset,edgeInset);
        layered.setLayerInset(2,fillInset,fillInset,fillInset,fillInset);
        layered.setLayerInset(0,haloInset,haloInset,haloInset,haloInset);
        return layered;
    }

    /** 高亮当前画笔工具。 */
    private void syncDraw(){
        int tool=canvas.getBrushTool();
        android.widget.ImageButton[] tools={toolPen,toolMosaic,toolText};int[] kinds={ImageEditorView.TOOL_PEN,ImageEditorView.TOOL_MOSAIC,ImageEditorView.TOOL_TEXT};for(int i=0;i<tools.length;i++){tools[i].setBackground(PhotoUi.bg(tool==kinds[i]?0xffffe9a0:0xffefeff1,dp(22)));tools[i].setPadding(dp(15),dp(15),dp(15),dp(15));}
        int accent=getThemeAccent(),text=themeColor(R.attr.client_text);
        toolPen.setColorFilter(tool==ImageEditorView.TOOL_PEN?accent:text);
        toolMosaic.setColorFilter(tool==ImageEditorView.TOOL_MOSAIC?accent:text);
        toolText.setColorFilter(tool==ImageEditorView.TOOL_TEXT?accent:text);
    }

    /** 滑杆只更新显示矩阵，应用时才生成旋转后的位图。 */
    private void rotateFreeDialog(){
        if(canvas==null||canvas.getBitmap()==null||rotationPanel!=null)return;
        resizePanelOpen=false;rotationOldInset=canvas.getBottomInset();rotationOldPageVisibility=pagePanel.getVisibility();pagePanel.setVisibility(View.GONE);
        canvas.beginRotationPreview();
        LinearLayout content=new LinearLayout(this);rotationPanel=content;content.setOrientation(LinearLayout.VERTICAL);content.setBackgroundResource(R.drawable.client_editor_panel);content.setPadding(dp(24),dp(16),dp(24),dp(16));
        TextView title=new TextView(this);title.setText(R.string.client_editor_rotate_free);title.setTextSize(18);title.setTextColor(themeColor(R.attr.client_text));content.addView(title);
        TextView angle=new TextView(this);angle.setText("旋转 0°");angle.setTextColor(themeColor(R.attr.client_text_secondary));content.addView(angle);
        Slider slider=new Slider(this);slider.setValueFrom(-180);slider.setValueTo(180);slider.setStepSize(0.5f);slider.setTickVisible(false);slider.setValue(0);slider.setContentDescription("旋转角度");content.addView(slider);
        slider.addOnChangeListener((s,value,fromUser)->{angle.setText(String.format(Locale.ROOT,"旋转 %.1f°",value));canvas.previewRotation(value);});
        TextView zoomLabel=new TextView(this);zoomLabel.setText("缩放 100%");zoomLabel.setTextColor(themeColor(R.attr.client_text_secondary));content.addView(zoomLabel);
        Slider zoom=new Slider(this);zoom.setValueFrom(100);zoom.setValueTo(300);zoom.setStepSize(1);zoom.setTickVisible(false);zoom.setValue(100);zoom.setContentDescription("旋转缩放");content.addView(zoom);
        zoom.addOnChangeListener((s,value,fromUser)->{zoomLabel.setText("缩放 "+Math.round(value)+"%");canvas.previewRotationZoom(value/100f);});
        LinearLayout actions=new LinearLayout(this);actions.setGravity(android.view.Gravity.END);content.addView(actions);
        android.widget.ImageButton reset=new android.widget.ImageButton(this);reset.setImageResource(R.drawable.client_editor_icon_reset);reset.setColorFilter(themeColor(R.attr.client_text));reset.setBackgroundResource(R.drawable.client_page_row);reset.setPadding(dp(11),dp(11),dp(11),dp(11));reset.setContentDescription("重置旋转和缩放");actions.addView(reset,new LinearLayout.LayoutParams(dp(44),dp(44)));reset.setOnClickListener(v->{slider.setValue(0);zoom.setValue(100);});
        ((android.widget.FrameLayout)pagePanel.getParent()).addView(content,new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT,android.view.Gravity.BOTTOM));
        applyRotationAction=()->{closeRotationPanel(true);canvas.finishEdit(true);showPage(PAGE_NONE);};
        content.post(()->{if(rotationPanel==content)canvas.setBottomInset(Math.max(rotationOldInset,content.getHeight()));});
        syncToolbar();
    }
    private void closeRotationPanel(boolean apply){
        if(rotationPanel==null)return;
        if(apply)canvas.applyRotationPreview();else canvas.cancelRotationPreview();
        ((android.view.ViewGroup)rotationPanel.getParent()).removeView(rotationPanel);rotationPanel=null;applyRotationAction=null;
        resizePanelOpen=false;pagePanel.setVisibility(rotationOldPageVisibility);canvas.setBottomInset(rotationOldInset);syncToolbar();
    }
    private boolean isEditing(){return rotationPanel!=null||page!=PAGE_NONE||canvas.getMode()==ImageEditorView.MODE_CROP;}
    private void cancelCurrentEdit(){
        if(rotationPanel!=null){boolean resize=resizePanelOpen;closeRotationPanel(false);if(!resize)return;}
        canvas.finishEdit(false);showPage(PAGE_NONE);
    }
    private void applyOrSave(){
        if(rotationPanel!=null){applyRotationAction.run();return;}
        if(canvas.getMode()==ImageEditorView.MODE_CROP){canvas.applyCrop();canvas.finishEdit(true);showPage(PAGE_NONE);return;}
        if(page!=PAGE_NONE){canvas.finishEdit(true);showPage(PAGE_NONE);return;}
        saveAsCopy();
    }

    /** 文本模式下点击画布，弹出输入框并在该处落文字。 */
    private void promptText(float x,float y){
        final EditText input=new EditText(this);input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.client_editor_text_title).setView(input)
            .setNegativeButton(R.string.client_editor_cancel,null)
            .setPositiveButton("添加",(d,w)->{
                String text=input.getText().toString().trim();
                if(text.isEmpty()){toast("请输入文字");return;}
                canvas.addText(text,x,y);
            }).show();
    }

    private void loadBitmap(){
        try{
            loaded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source),(decoder,info,src)->{
                int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                if(w<=0||h<=0)throw new IllegalArgumentException("无法读取图片尺寸");
                int longSide=Math.max(w,h);
                if(longSide>MAX_LONG_SIDE){float ratio=(float)MAX_LONG_SIDE/longSide;decoder.setTargetSize(Math.max(1,Math.round(w*ratio)),Math.max(1,Math.round(h*ratio)));}
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);
            });
        }catch(OutOfMemoryError e){toast("图片过大，内存不足无法编辑");finish();return;}
        catch(Exception e){toast("图片读取失败："+(e.getMessage()==null?"格式不支持":e.getMessage()));finish();return;}
        if(loaded==null){toast("图片读取失败");finish();return;}
        canvas.load(loaded);
        syncToolbar();
        syncDraw();
    }

    /** 按当前模式同步操作状态。 */
    private void syncToolbar(){
        if(canvas.getBitmap()==null)return;
        int mode=canvas.getMode();
        findViewById(R.id.client_editor_cancel).setVisibility(isEditing()?View.VISIBLE:View.GONE);
        ((TextView)findViewById(R.id.client_editor_save)).setText(rotationPanel!=null||page!=PAGE_NONE||mode==ImageEditorView.MODE_CROP?"应用":"保存");
        int accent=getThemeAccent(),text=themeColor(R.attr.client_text);
        cropButton.setColorFilter(mode==ImageEditorView.MODE_CROP?accent:text);
        drawButton.setColorFilter(page==PAGE_DRAW?accent:text);
        // Preview panels have their own uncommitted state; don't undo the
        // underlying image while a rotation or resize preview is displayed.
        boolean undoAvailable=canvas.canUndo()&&rotationPanel==null;
        undoButton.setVisibility(undoAvailable?View.VISIBLE:View.GONE);
        undoButton.setEnabled(undoAvailable);
        updatePhotoHeader();
        if(page==PAGE_DRAW)syncDraw();
    }
    private int getThemeAccent(){return themeColor(R.attr.client_accent);}
    private int themeColor(int attr){
        TypedValue value=new TypedValue();
        getTheme().resolveAttribute(attr,value,true);
        return value.data;
    }
    private float density(){return getResources().getDisplayMetrics().density;}

    private void resizeDialog(){
        if(rotationPanel!=null)return;
        canvas.beginEdit();resizePanelOpen=true;rotationOldInset=canvas.getBottomInset();rotationOldPageVisibility=pagePanel.getVisibility();pagePanel.setVisibility(View.GONE);
        Slider slider=new Slider(this);slider.setValueFrom(10);slider.setValueTo(200);slider.setValue(100);slider.setStepSize(5);slider.setTickVisible(false);slider.setContentDescription("图片尺寸");
        TextView label=new TextView(this);label.setTextSize(14);label.setTextColor(themeColor(R.attr.client_text));
        label.setText("缩放 100%");
        slider.addOnChangeListener((v,value,fromUser)->label.setText("缩放 "+Math.round(value)+"%"));
        LinearLayout box=new LinearLayout(this);rotationPanel=box;box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundResource(R.drawable.client_editor_panel);box.setPadding(dp(24),dp(16),dp(24),dp(16));box.addView(label);box.addView(slider);
        ((android.widget.FrameLayout)pagePanel.getParent()).addView(box,new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT,android.view.Gravity.BOTTOM));
        applyRotationAction=()->{canvas.resize(slider.getValue()/100f);closeRotationPanel(false);canvas.finishEdit(true);showPage(PAGE_NONE);};
        box.post(()->{if(rotationPanel==box)canvas.setBottomInset(box.getHeight());});syncToolbar();
    }

    private void saveAsCopy(){writeCopy();}
    static File numberedCopy(File source,int number){
        String base=source.getName();int dot=base.lastIndexOf('.');String stem=dot<=0?base:base.substring(0,dot);
        String ext=dot<=0?"":base.substring(dot);String lower=ext.toLowerCase(Locale.ROOT);
        if(!lower.equals(".jpg")&&!lower.equals(".jpeg")&&!lower.equals(".jfif")&&!lower.equals(".png")&&!lower.equals(".webp"))ext=".png";
        return new File(source.getParentFile(),stem+number+ext);
    }

    static Bitmap.CompressFormat outputFormat(File target)throws java.io.IOException {
        String name=target.getName().toLowerCase(Locale.ROOT);
        if(name.endsWith(".jpg")||name.endsWith(".jpeg")||name.endsWith(".jfif"))return Bitmap.CompressFormat.JPEG;
        if(name.endsWith(".png"))return Bitmap.CompressFormat.PNG;
        if(name.endsWith(".webp"))return Bitmap.CompressFormat.WEBP;
        throw new java.io.IOException("请选择 JPG、PNG 或 WebP 格式另存；此格式不支持覆盖编辑");
    }

    /** 编码完成后排他创建编号副本，原图和已有文件不会被覆盖。 */
    private void writeCopy(){
        File target=numberedCopy(source,1);
        File temp=null;
        try{
            Bitmap.CompressFormat format=outputFormat(target);
            Bitmap result=canvas.render();
            if(result==null){toast("没有可保存的内容");return;}
            temp=File.createTempFile(".cjfm-image-",".tmp",target.getParentFile());
            try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(temp),128*1024)){
                if(!result.compress(format,format==Bitmap.CompressFormat.JPEG?95:100,stream))throw new java.io.IOException("编码失败");
            }
            for(int number=1;;number++){
                target=numberedCopy(source,number);
                if(java.nio.file.Files.exists(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))continue;
                try{SafeFileOperations.publishNew(temp,target);break;}catch(java.nio.file.FileAlreadyExistsException exists){/* Try the next suffix without overwriting. */}
            }
            toast("已保存 "+target.getName());
            setResult(Activity.RESULT_OK,new Intent().putExtra(EXTRA_SAVED,target.getAbsolutePath()));
            finish();
        }catch(Exception|OutOfMemoryError e){
            toast("保存失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
        }finally{if(temp!=null)temp.delete();}
    }

    @Override protected void onDestroy(){
        if(rotationPanel!=null)closeRotationPanel(false);
        canvas=null;loaded=null;
        super.onDestroy();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
}
