package com.cj.music;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.cj.music.resources.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 格式工厂的转换历史：一条一条列出转成功的产物，点击直接播放，长按可以删除。
 *
 * 只存元数据（路径 / 大小 / 耗时 / 时间），不复制文件——文件本身一直在 Movies/格式工厂 里，
 * 被用户手动删掉了就标成「文件已不存在」，不再提供播放。
 */
public final class FormatHistoryActivity extends AppCompatActivity {
    private LinearLayout list;
    private View clearButton;
    /** 列表已改成 Compose：内容由 MoreScreens.formatHistoryView 画，
        增删之后重新 setContent 一次即可（和原来 removeAllViews 再 addView 一个意思）。 */
    androidx.compose.ui.platform.ComposeView pageView;

    @Override protected void onCreate(Bundle state){
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        int background=MusicControls.color(this,R.attr.client_surface_raised);
        root.setBackgroundColor(background);
        getWindow().setStatusBarColor(background);getWindow().setNavigationBarColor(background);
        setContentView(root);ClientWindowInsets.apply(this);
        if(getSupportActionBar()!=null)getSupportActionBar().hide();
        androidx.compose.ui.platform.ComposeView content=MoreScreensKt.formatHistoryView(this);
        pageView=content;root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        render();
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){finish();}});
    }

    private View toolbar(){
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(20),statusBarHeight()+dp(12),dp(20),dp(10));
        ImageButton back=new ImageButton(this);back.setImageResource(R.drawable.client_ic_back);back.setContentDescription("返回格式工厂");
        back.setColorFilter(MusicControls.color(this,R.attr.client_text));back.setPadding(dp(10),dp(10),dp(10),dp(10));
        back.setBackground(round(MusicControls.color(this,R.attr.client_surface),20));back.setOnClickListener(v->finish());
        bar.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));
        TextView title=text("转换历史",20);title.setTypeface(MusicStyle.LABEL);title.setPadding(dp(14),0,0,0);
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView clear=text("清空",13);clear.setTypeface(MusicStyle.LABEL);
        clear.setTextColor(MusicControls.color(this,R.attr.client_text_secondary));
        clear.setPadding(dp(10),dp(8),dp(10),dp(8));
        clear.setBackground(round(MusicControls.color(this,R.attr.client_surface),14));
        clear.setOnClickListener(v->confirmClear());bar.addView(clear);clearButton=clear;
        return bar;
    }

    /** 增删之后重新组一次内容（等价于原来的 removeAllViews + 逐条 addView）。 */
    private void render(){MoreScreensKt.renderHistoryContent(this);}
    /* ---- Compose 侧的桥接 ---- */
    java.util.List<FormatHistory.Item> pageHistoryItems(){return FormatHistory.read(this);}
    boolean pageHistoryExists(FormatHistory.Item item){return new java.io.File(item.path).isFile();}
    String pageHistorySubtitle(FormatHistory.Item item){return subtitle(item);}
    void pageHistoryPlay(FormatHistory.Item item){play(item);}
    void pageHistoryRemove(FormatHistory.Item item){confirmRemove(item);}
    void pageHistoryClear(){confirmClear();}
    int pageStatusBar(){return statusBarHeight();}

    private String subtitle(FormatHistory.Item item){
        StringBuilder b=new StringBuilder();
        b.append(new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(item.at)));
        b.append("  ·  ").append(android.text.format.Formatter.formatFileSize(this,item.size));
        b.append("  ·  用时 ").append(Math.max(1,item.seconds)).append(" 秒");
        if(!item.from.isEmpty())b.append("  ·  来自 ").append(item.from);
        return b.toString();
    }
    private void play(FormatHistory.Item item){
        File file=new File(item.path);
        if(!file.isFile()){toast("文件已不存在");return;}
        try{
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".client.exports",file);
            Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(uri,FormatHistory.mimeFor(file.getName(),item.mime));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);
        }catch(Exception e){toast("无法打开这个文件");}
    }

    private void confirmRemove(FormatHistory.Item item){
        new MaterialAlertDialogBuilder(this).setTitle("从历史里移除？").setMessage(item.name+"\n只移除这条记录，不会删除文件本身。")
            .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{FormatHistory.remove(FormatHistoryActivity.this,item.path);render();}).show();
    }
    private void confirmClear(){
        new MaterialAlertDialogBuilder(this).setTitle("清空转换历史？").setMessage("只清空记录，不会删除已转好的文件。")
            .setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{FormatHistory.clear(FormatHistoryActivity.this);render();}).show();
    }
    private void toast(String message){android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_SHORT).show();}

    private LinearLayout.LayoutParams rowParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);return p;}
    private GradientDrawable cardBackground(){GradientDrawable bg=round(MusicControls.color(this,R.attr.client_surface),22);bg.setStroke(dp(1),MusicControls.color(this,R.attr.client_outline));return bg;}
    private GradientDrawable round(int color,float radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);
        t.setTextColor(MusicControls.color(this,R.attr.client_text));t.setIncludeFontPadding(false);return t;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private int statusBarHeight(){
        int id=getResources().getIdentifier("status_bar_height","dimen","android");
        return id>0?getResources().getDimensionPixelSize(id):dp(24);
    }
}
