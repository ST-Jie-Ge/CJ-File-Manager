package com.cj.music;

import android.graphics.*;
import java.io.File;

/** Bounded, blurred cover colour field; the player/lyrics/system bars share it. */
final class CoverTint {
    /** Curated analogous pastels: restrained saturation, no arbitrary RGB combinations. */
    static int[] randomPalette(){int[][] palettes={{0xffb8d9dc,0xffd7cde9,0xff336878},{0xffead2c2,0xffe3c5d7,0xff854e65},{0xffc5d9c4,0xffe4ddbc,0xff4b6d4f},{0xffbdcfe8,0xffd1d0ea,0xff495e91},{0xffe8ced4,0xffeeddbf,0xff8b5460}};int[] palette=palettes[new java.util.Random().nextInt(palettes.length)],colors=new int[65];for(int y=0;y<8;y++)for(int x=0;x<8;x++)colors[y*8+x]=androidx.core.graphics.ColorUtils.blendARGB(palette[0],palette[1],(x+y)/14f);colors[64]=palette[2];return colors;}
    static int[] read(Bitmap original){
        if(original==null)return null;
        Bitmap small=null,tiny=null;
        try{
            small=Bitmap.createScaledBitmap(original,Math.min(64,original.getWidth()),Math.min(64,original.getHeight()),true);
            tiny=Bitmap.createScaledBitmap(small,8,8,true);int[] result=new int[65];result[64]=dominantAccent(small);
            for(int y=0;y<8;y++)for(int x=0;x<8;x++){int r=0,g=0,b=0,count=0;for(int yy=Math.max(0,y-2);yy<=Math.min(7,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(7,x+2);xx++){int pixel=tiny.getPixel(xx,yy);r+=Color.red(pixel);g+=Color.green(pixel);b+=Color.blue(pixel);count++;}result[y*8+x]=Color.rgb(r/count,g/count,b/count);}return result;
        }catch(Exception|OutOfMemoryError ignored){return null;}finally{if(tiny!=null&&tiny!=small&&tiny!=original)tiny.recycle();if(small!=null&&small!=original)small.recycle();}
    }
    static Bitmap field(Bitmap original,float aspect){
        if(original==null||aspect<=0)return null;Bitmap cropped=null;
        try{int w=original.getWidth(),h=original.getHeight(),cw=w,ch=h;if(w/(float)h>aspect)cw=Math.max(1,Math.round(h*aspect));else ch=Math.max(1,Math.round(w/aspect));cropped=Bitmap.createBitmap(original,(w-cw)/2,(h-ch)/2,cw,ch);return smooth(downsample(cropped,12,Math.max(6,Math.round(12/aspect))),3);}
        catch(Exception|OutOfMemoryError ignored){return null;}finally{if(cropped!=null&&cropped!=original)cropped.recycle();}
    }
    static int[] read(File file){try{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>64)options.inSampleSize*=2;
        Bitmap source=BitmapFactory.decodeFile(file.getPath(),options);if(source==null)return null;
        int side=Math.min(source.getWidth(),source.getHeight());Bitmap square=Bitmap.createBitmap(source,(source.getWidth()-side)/2,(source.getHeight()-side)/2,side,side);
        Bitmap tiny=Bitmap.createScaledBitmap(square,8,8,true);int[] colors=new int[65];
        colors[64]=dominantAccent(square);
        for(int y=0;y<8;y++)for(int x=0;x<8;x++){int r=0,g=0,b=0,count=0;for(int yy=Math.max(0,y-2);yy<=Math.min(7,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(7,x+2);xx++){int pixel=tiny.getPixel(xx,yy);r+=Color.red(pixel);g+=Color.green(pixel);b+=Color.blue(pixel);count++;}colors[y*8+x]=Color.rgb(r/count,g/count,b/count);}
        if(tiny!=square)tiny.recycle();if(square!=source)square.recycle();source.recycle();return colors;
    }catch(Exception|OutOfMemoryError ignored){return null;}}
    /** 整幅封面虚化铺底（激进版）：按屏幕比例居中裁切 → 多级降采样到极小的 22×N 位图，
        交给绘制时的双线性放大铺满整屏。等于一次极重模糊，开销几乎为零（无 RenderScript / BLUR）。
        aspect = 屏宽 / 屏高。返回 null 表示取不到封面，调用方退回 8×8 色场方案。 */
    static Bitmap field(File file,float aspect){
        try{
            if(file==null||!file.isFile()||aspect<=0)return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
            while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>256)options.inSampleSize*=2;
            Bitmap source=BitmapFactory.decodeFile(file.getPath(),options);if(source==null)return null;
            int w=source.getWidth(),h=source.getHeight(),cw=w,ch=h;
            if(w/(float)h>aspect)cw=Math.max(1,Math.round(h*aspect));else ch=Math.max(1,Math.round(w/aspect));
            Bitmap cropped=Bitmap.createBitmap(source,(w-cw)/2,(h-ch)/2,cw,ch);
            /* 宽度是关键旋钮：越小越糊（6~20 之间按口味调）。之后再跑 3 遍 3×3 邻域平均，
               把残留的大色块抹平——这一步才是"更激进"的关键，只降采样的话宽色块轮廓仍在。 */
            int tw=12,th=Math.max(6,Math.round(tw/aspect));
            Bitmap tiny=smooth(downsample(cropped,tw,th),3);
            if(cropped!=source)cropped.recycle();
            source.recycle();
            return tiny;
        }catch(Exception|OutOfMemoryError ignored){return null;}
    }
    /** 分步降采样：每次最多缩到一半，避免一次性大比例双线性缩放造成采样噪点/摩尔纹。 */
    private static Bitmap downsample(Bitmap source,int targetW,int targetH){
        Bitmap current=source;
        while(current.getWidth()/2>=targetW&&current.getHeight()/2>=targetH)
            current=Bitmap.createScaledBitmap(current,Math.max(targetW,current.getWidth()/2),Math.max(targetH,current.getHeight()/2),true);
        return Bitmap.createScaledBitmap(current,targetW,targetH,true);
    }
    /** 3×3 邻域平均（边缘夹取），跑在 12×N 的小图上，几十次乘加而已。 */
    private static Bitmap smooth(Bitmap bitmap,int passes){
        int w=bitmap.getWidth(),h=bitmap.getHeight(),size=w*h;
        int[] pixels=new int[size];int[] scratch=new int[size];
        bitmap.getPixels(pixels,0,w,0,0,w,h);
        for(int pass=0;pass<passes;pass++){
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int r=0,g=0,b=0,count=0;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int sx=x+dx<0?0:Math.min(w-1,x+dx),sy=y+dy<0?0:Math.min(h-1,y+dy);
                    int color=pixels[sy*w+sx];
                    r+=color>>16&255;g+=color>>8&255;b+=color&255;count++;
                }
                scratch[y*w+x]=0xff000000|r/count<<16|g/count<<8|b/count;
            }
            int[] swap=pixels;pixels=scratch;scratch=swap;
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }
    private static int dominantAccent(Bitmap bitmap){
        double[] weights=new double[12],r=new double[12],g=new double[12],b=new double[12];float[] hsv=new float[3];
        for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++){int color=bitmap.getPixel(x,y);Color.colorToHSV(color,hsv);if(hsv[1]<.15f||hsv[2]<.12f)continue;int bucket=Math.min(11,(int)(hsv[0]/30));double weight=hsv[1]*hsv[1]*(.4+.6*hsv[2]);weights[bucket]+=weight;r[bucket]+=Color.red(color)*weight;g[bucket]+=Color.green(color)*weight;b[bucket]+=Color.blue(color)*weight;}
        int best=0;for(int i=1;i<12;i++)if(weights[i]>weights[best])best=i;if(weights[best]==0)return 0xff344654;
        return Color.rgb((int)(r[best]/weights[best]),(int)(g[best]/weights[best]),(int)(b[best]/weights[best]));
    }
    static int accent(int[] source,int[] background){
        int base=source!=null&&source.length>64?source[64]:0xff315f8b;float[] hsv=new float[3];Color.colorToHSV(base,hsv);hsv[1]=Math.max(.5f,Math.min(.8f,hsv[1]));hsv[2]=Math.min(.6f,Math.max(.35f,hsv[2]));
        for(int attempt=0;attempt<20;attempt++){int color=Color.HSVToColor(hsv);boolean readable=true;for(int pixel:background){pixel=androidx.core.graphics.ColorUtils.setAlphaComponent(pixel,255);int visible=androidx.core.graphics.ColorUtils.compositeColors(androidx.core.graphics.ColorUtils.setAlphaComponent(color,180),pixel);if(androidx.core.graphics.ColorUtils.calculateContrast(visible,pixel)<2){readable=false;break;}}if(readable)return color;hsv[2]*=.88f;}
        hsv[2]=Math.max(.16f,hsv[2]);return Color.HSVToColor(hsv);
    }
}
