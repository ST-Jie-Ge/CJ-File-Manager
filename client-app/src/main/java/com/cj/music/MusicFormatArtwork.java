package com.cj.music;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.util.Locale;

/** Small procedural record sleeves; no bitmap cache, permissions or network required. */
final class MusicFormatArtwork extends Drawable {
    static Bitmap bitmap(String name){Bitmap b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);MusicFormatArtwork art=new MusicFormatArtwork(name);art.setBounds(0,0,256,256);art.draw(new Canvas(b));return b;}
    private final Paint p=new Paint(3);private final String format;private final int style,accent,paper;
    MusicFormatArtwork(String name){String ext=MusicDecoder.ext(name);format=(ext.isEmpty()?"AUDIO":ext).toUpperCase(Locale.ROOT);style=(ext.equals("flac")||ext.equals("wav")||ext.equals("ape")||ext.equals("alac"))?1:(ext.equals("m4a")||ext.equals("aac"))?2:0;int hash=(format.hashCode()&0x7fffffff)%5;int[] colors={0xff427565,0xff956a52,0xff777391,0xff427989,0xff9c6477};accent=colors[hash];paper=androidx.core.graphics.ColorUtils.blendARGB(accent,Color.WHITE,.86f);}
    @Override public void draw(Canvas c){c.save();Rect b=getBounds();c.translate(b.left,b.top);c.scale(b.width()/200f,b.height()/200f);p.setShader(new LinearGradient(0,0,200,200,new int[]{0xfff9f7ef,paper},null,Shader.TileMode.CLAMP));p.setStyle(Paint.Style.FILL);c.drawRect(0,0,200,200,p);p.setShader(null);
        /* 右上角那行「AUDIO / N」按需求去掉（三种风格共用这一处），只留左上角的装饰短横线。 */
        p.setColor(accent);p.setAlpha(80);p.setStrokeWidth(1);c.drawLine(16,23,54,23,p);p.setAlpha(255);
        if(style==2){p.setColor(accent);c.drawRoundRect(21,51,179,139,13,13,p);p.setColor(paper);c.drawRoundRect(32,63,168,119,8,8,p);p.setColor(0xff333b3a);c.drawRoundRect(47,77,153,108,15,15,p);for(int x:new int[]{66,134}){p.setColor(0xfff9f7ef);c.drawCircle(x,92,11,p);p.setColor(accent);c.drawCircle(x,92,4,p);}p.setColor(0xffd6d2c9);c.drawRoundRect(64,125,136,135,4,4,p);p.setColor(0x85ffffff);for(int x:new int[]{29,171})for(int y:new int[]{59,131})c.drawCircle(x,y,2,p);p.setColor(accent);p.setStrokeWidth(1);for(int x:new int[]{66,134}){c.drawLine(x-5,92,x+5,92,p);c.drawLine(x,87,x,97,p);}}
        else{int base=style==1?0xffc0cbd0:0xff27352f;p.setShader(new SweepGradient(100,94,new int[]{base,style==1?0xfff8fbf7:0xff506255,base,style==1?0xffe4eeec:0xff35493f,base},null));c.drawCircle(100,94,65,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.65f);p.setColor(style==1?0x60ffffff:0x308ca99b);for(int r=29;r<63;r+=3)c.drawCircle(100,94,r,p);p.setStyle(Paint.Style.FILL);p.setColor(accent);c.drawCircle(100,94,23,p);p.setColor(paper);c.drawCircle(100,94,7,p);p.setColor(0xfffaf8f0);c.drawCircle(100,94,2,p);p.setStyle(Paint.Style.STROKE);p.setColor(0x45ffffff);p.setStrokeWidth(2);c.drawArc(39,33,161,155,205,65,false,p);p.setStyle(Paint.Style.FILL);}
        /* 左下角保留格式名（MP3 / FLAC / M4A…）；右下角的 LOSSLESS / DIGITAL TAPE / DIGITAL RECORD 按需求去掉。 */
        p.setColor(accent);p.setTextAlign(Paint.Align.LEFT);p.setTextSize(format.length()>5?19:24);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));c.drawText(format,18,181,p);p.setStrokeWidth(2);p.setAlpha(100);c.drawLine(133,175,181,175,p);p.setAlpha(255);c.restore();}
    @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
