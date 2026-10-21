package com.cj.music;
import java.io.File;
import java.util.Locale;
/** Ordinary audio metadata only. */
final class MusicDecoder {
    static String ext(String name){int i=name.lastIndexOf('.');return i<0?"":name.substring(i+1).toLowerCase(Locale.ROOT);}
    static int[] audioSpec(File file){
        if(file==null||!file.isFile())return null;
        android.media.MediaExtractor extractor=new android.media.MediaExtractor();
        try{
            extractor.setDataSource(file.getPath());
            for(int i=0;i<extractor.getTrackCount();i++){
                android.media.MediaFormat format=extractor.getTrackFormat(i);
                String mime=format.getString(android.media.MediaFormat.KEY_MIME);
                if(mime==null||!mime.startsWith("audio/"))continue;
                int rate=format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)?format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE):0;
                int bits=format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)?format.getInteger(android.media.MediaFormat.KEY_BIT_RATE):0;
                if(rate>0)return new int[]{bits>0?bits:0,rate};
            }
        }catch(Exception|OutOfMemoryError ignored){}
        finally{try{extractor.release();}catch(Exception ignored){}}
        return null;
    }
}
