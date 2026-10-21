package com.cj.music;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import androidx.test.platform.app.InstrumentationRegistry;
import com.cj.music.resources.R;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Inflate the actual Android vectors and verify their output at all three preview sizes. */
public class FileIconResourceUiTest {
    @Test public void approvedVectorsRenderWithoutTintLossOrMissingResources()throws Exception{
        int[] ids={R.drawable.client_ic_psd,R.drawable.client_ic_text,R.drawable.client_ic_doc,R.drawable.client_ic_xls,R.drawable.client_ic_ppt,R.drawable.client_ic_pdf,R.drawable.client_ic_exe,R.drawable.client_file_icon_folder,R.drawable.client_ic_file,R.drawable.client_ic_image,R.drawable.client_ic_video,R.drawable.client_ic_audio,R.drawable.client_ic_code,R.drawable.client_ic_archive,R.drawable.client_ic_apk};
        String[] labels={"PSD","文本","文档","表格","演示 A","PDF B","EXE B","文件夹","其他文件","图片","视频","音频","代码","压缩包","APK"};
        Bitmap sheet=Bitmap.createBitmap(1200,1040,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sheet);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xff193247);paint.setTextSize(25);paint.setTextAlign(Paint.Align.CENTER);
        for(int i=0;i<ids.length;i++){
            Drawable drawable=InstrumentationRegistry.getInstrumentation().getTargetContext().getDrawable(ids[i]);assertNotNull(drawable);
            int x=(i%4)*300+100,y=(i/4)*260+35;
            for(int size:new int[]{24,36,64}){
                Bitmap sample=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);drawable.setBounds(0,0,size,size);drawable.draw(new Canvas(sample));int colored=0;for(int py=0;py<size;py++)for(int px=0;px<size;px++){int pixel=sample.getPixel(px,py);if(Color.alpha(pixel)>128&&Color.red(pixel)!=Color.green(pixel))colored++;}assertTrue(labels[i]+" must retain colored artwork at "+size,colored>size);
                if(size==64)canvas.drawBitmap(sample,null,new android.graphics.Rect(x,y,x+100,y+100),null);else canvas.drawBitmap(sample,x+(size==24?10:65),y+165,null);sample.recycle();
            }
            canvas.drawText(labels[i],x+50,y+140,paint);
        }
        File target=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"applied-file-icons.png");try(FileOutputStream out=new FileOutputStream(target)){assertTrue(sheet.compress(Bitmap.CompressFormat.PNG,100,out));}sheet.recycle();
    }
}
