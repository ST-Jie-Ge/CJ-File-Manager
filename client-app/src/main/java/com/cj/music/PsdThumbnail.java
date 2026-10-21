package com.cj.music;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * PSD 缩略图：Photoshop 存盘时会把一张 JPEG 预览写进「图像资源块」（Image Resource Block），
 * 这里只顺序读取文件头部的资源区把它抠出来，图层与像素数据一概不碰。
 * 因此几百 MB 的 PSD 也能瞬间出缩略图——代价只是几十 KB 的顺序读。
 *
 * 注意：这不是 PSD 解码器，拿到的是 PS 自己生成的预览图，不是图层合并结果。
 */
final class PsdThumbnail {
    /** 资源区整体扫描上限 / 单个资源块上限 / 缩略图字节上限：坏文件不该拖死内存和 IO。 */
    private static final int SCAN_LIMIT=8*1024*1024;
    private static final int BLOCK_LIMIT=8*1024*1024;
    private static final int JPEG_LIMIT=4*1024*1024;
    /** 缩略图资源 ID：1036 = Photoshop 5.0+（RGB），1033 = 4.0 老文件（那份 JPEG 是 BGR 顺序）。 */
    private static final int RESOURCE_THUMB=1036, RESOURCE_THUMB_LEGACY=1033;

    private PsdThumbnail(){}

    static boolean matches(String name){
        int dot=name==null?-1:name.lastIndexOf('.');
        return dot>=0&&"psd".equals(name.substring(dot+1).toLowerCase(Locale.ROOT));
    }

    /** 只需读流的前若干 KB；找不到（或不是 PSD）返回 null。调用方负责关闭流。 */
    static Bitmap read(InputStream source,int target){
        try{
            Jpeg found=scan(source);
            if(found==null)return null;
            Bitmap decoded=decode(found.bytes,target);
            if(decoded==null)return null;
            return found.bgr?swapRedBlue(decoded):decoded;
        }catch(IOException|OutOfMemoryError|RuntimeException e){return null;}
    }

    /* ===== 资源区扫描 ===== */

    private static final class Jpeg {final byte[] bytes;final boolean bgr;Jpeg(byte[] bytes,boolean bgr){this.bytes=bytes;this.bgr=bgr;}}

    private static Jpeg scan(InputStream source)throws IOException{
        InputStream in=source instanceof BufferedInputStream?source:new BufferedInputStream(source,32*1024);
        byte[] header=new byte[26];
        if(!readFully(in,header,26))return null;
        if(header[0]!='8'||header[1]!='B'||header[2]!='P'||header[3]!='S')return null;
        if(u16(header,4)!=1)return null;                       // PSB（版本 2）长度字段是另一套，不支持
        byte[] four=new byte[4];
        if(!readFully(in,four,4))return null;
        long colorMode=u32(four,0);
        if(colorMode<0||colorMode>SCAN_LIMIT||!skip(in,colorMode))return null;
        if(!readFully(in,four,4))return null;
        long section=u32(four,0);
        long scanned=0;
        while(scanned+12<=section&&scanned<SCAN_LIMIT){
            if(!readFully(in,four,4))return null;scanned+=4;
            if(four[0]!='8'||four[1]!='B'||four[2]!='I'||four[3]!='M')return null;
            byte[] pair=new byte[2];
            if(!readFully(in,pair,2))return null;scanned+=2;
            int id=u16(pair,0);
            int name=in.read();
            if(name<0)return null;scanned+=1;
            if(!skip(in,name))return null;scanned+=name;
            if((name&1)==0){if(!skip(in,1))return null;scanned+=1;}   // 名字字段含长度字节必须是偶数
            if(!readFully(in,four,4))return null;scanned+=4;
            long size=u32(four,0),padded=(size&1)==1?size+1:size;
            if(size<0||padded>BLOCK_LIMIT)return null;
            if(id==RESOURCE_THUMB||id==RESOURCE_THUMB_LEGACY)return thumbnail(in,size,id==RESOURCE_THUMB_LEGACY);
            if(!skip(in,size))return null;
            if((size&1)==1&&!skip(in,1))return null;
            scanned+=padded;
        }
        return null;
    }

    /** 缩略图资源体：28 字节头 + JPEG 数据。头里第 20 字节起是压缩后长度。 */
    private static Jpeg thumbnail(InputStream in,long size,boolean bgr)throws IOException{
        if(size<28)return null;
        byte[] info=new byte[28];
        if(!readFully(in,info,28))return null;
        if((int)u32(info,0)!=1)return null;                    // 1 = kJpegRGB；0 = kRawRGB 不做，太罕见
        long length=u32(info,20);
        if(length<=0||length>JPEG_LIMIT||length>size-28)return null;
        byte[] jpeg=new byte[(int)length];
        if(!readFully(in,jpeg,jpeg.length))return null;
        return new Jpeg(jpeg,bgr);
    }

    private static Bitmap decode(byte[] jpeg,int target){
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
        int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/(sample*2)>=target)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);
    }

    /** 1033 那份 JPEG 是 BGR 顺序，解码后把红蓝通道换回来。 */
    private static Bitmap swapRedBlue(Bitmap source){
        Bitmap out=Bitmap.createBitmap(source.getWidth(),source.getHeight(),Bitmap.Config.ARGB_8888);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            0,0,1,0,0,
            0,1,0,0,0,
            1,0,0,0,0,
            0,0,0,1,0})));
        new Canvas(out).drawBitmap(source,0,0,paint);
        source.recycle();
        return out;
    }

    private static boolean readFully(InputStream in,byte[] buffer,int length)throws IOException{
        int done=0;
        while(done<length){int n=in.read(buffer,done,length-done);if(n<0)return false;done+=n;}
        return true;
    }
    /** 远程流（FTP/SMB）的 skip 不保证跳满，一律按字节读掉。 */
    private static boolean skip(InputStream in,long count)throws IOException{
        byte[] sink=new byte[8192];long left=count;
        while(left>0){int n=in.read(sink,0,(int)Math.min(sink.length,left));if(n<0)return false;left-=n;}
        return true;
    }
    private static long u32(byte[] b,int at){return ((long)(b[at]&0xff)<<24)|((b[at+1]&0xff)<<16)|((b[at+2]&0xff)<<8)|(b[at+3]&0xff);}
    private static int u16(byte[] b,int at){return ((b[at]&0xff)<<8)|(b[at+1]&0xff);}
}
