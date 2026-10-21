package com.cj.music;

import android.graphics.*;

/** Review-only artwork; deliberately excluded from release sources. */
final class MusicDesignCandidates {
    static final Paint p=new Paint(3);
    static void text(Canvas c,String value,float x,float y,int size,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTypeface(Typeface.create("sans-serif-medium",0));c.drawText(value,x,y,p);}
    static void path(Canvas c,float... points){Path q=new Path();q.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)q.lineTo(points[i],points[i+1]);c.drawPath(q,p);}
    static void icon(Canvas c,int design,int mode){
        p.setColor(0xff31594c);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(design==0?1.7f:2);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        if(design==0){
            if(mode==0){path(c,4,6,20,6,17,3);path(c,20,6,17,9);path(c,4,13,15,13);path(c,4,19,11,19);path(c,18,15,22,18,18,21);}
            else{c.drawArc(3,5,21,19,185,150,false,p);c.drawArc(3,5,21,19,5,150,false,p);path(c,16,3,21,5,20,10);path(c,8,21,3,19,4,14);if(mode==2){path(c,10,10,12,8,12,16);path(c,10,16,14,16);}else{path(c,9,10,15,10);path(c,9,14,15,14);}}
        }else{
            if(mode==0){c.drawRoundRect(3,4,16,20,3,3,p);path(c,7,8,12,8);path(c,7,12,12,12);path(c,19,6,19,18,22,15);path(c,19,18,17,15);}
            else{c.drawRoundRect(3,4,21,20,6,6,p);p.setStyle(Paint.Style.FILL);p.setColor(0xffedf4ef);c.drawRect(13,2,23,8,p);p.setColor(0xff31594c);p.setStyle(Paint.Style.STROKE);path(c,12,4,19,4,16,1);path(c,19,4,16,7);if(mode==2){path(c,10,11,12,9,12,16);path(c,10,16,14,16);}else{path(c,8,10,16,10);path(c,8,14,16,14);}}
        }p.setStyle(Paint.Style.FILL);
    }
    static Bitmap orders(){Bitmap b=Bitmap.createBitmap(1200,820,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xffedf4ef);text(c,"播放顺序 · 两套候选（未应用）",48,64,30,0xff263f36);String[] modes={"顺序播放","列表循环","单曲循环"};
        for(int design=0;design<2;design++){int top=110+design*340;text(c,design==0?"A / 细线箭头":"B / 圆角队列",48,top+24,22,0xff537666);for(int mode=0;mode<3;mode++){int x=48+mode*380;p.setColor(0xfffcfdfb);c.drawRoundRect(x,top+48,x+352,top+282,24,24,p);c.save();c.translate(x+136,top+80);c.scale(3.4f,3.4f);icon(c,design,mode);c.restore();text(c,modes[mode],x+112,top+214,22,0xff263f36);c.save();c.translate(x+112,top+237);icon(c,design,mode);c.restore();text(c,"24 px",x+150,top+256,15,0xff668274);}}
        return b;
    }
    static void sleeve(Canvas c,String format,int accent){p.setShader(new LinearGradient(0,0,240,240,new int[]{0xfffaf8ef,0xffe7eee7},null,Shader.TileMode.CLAMP));c.drawRoundRect(0,0,240,240,22,22,p);p.setShader(null);p.setColor(accent);c.drawRoundRect(22,22,218,166,14,14,p);p.setColor(0x30ffffff);c.drawCircle(172,56,68,p);p.setColor(0xfff4f1df);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);for(int i=0;i<8;i++){float x=45+i*21;float h=18+(float)(Math.sin(i*.82)*Math.sin(i*.82))*63;c.drawLine(x,97-h/2,x,97+h/2,p);}p.setStyle(Paint.Style.FILL);text(c,format,22,211,31,accent);p.setColor(accent);c.drawCircle(208,203,5,p);}
    static Bitmap covers(){Bitmap b=Bitmap.createBitmap(1200,740,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xffedf4ef);text(c,"无封面候选 / 声波纸套（未应用）",48,62,30,0xff263f36);String[] labels={"MP3","FLAC","M4A","WAV","OGG","AUDIO"};int[] colors={0xff466f62,0xff516b91,0xff906e59,0xff707454,0xff856783,0xff4c7980};for(int i=0;i<6;i++){c.save();c.translate(48+(i%3)*384,104+(i/3)*310);sleeve(c,labels[i],colors[i]);c.restore();c.save();c.translate(305+(i%3)*384,278+(i/3)*310);c.scale(.25f,.25f);sleeve(c,labels[i],colors[i]);c.restore();}return b;}
}
