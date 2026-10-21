package com.cj.music;

/** Fit either orientation inside the selected video size, without upscaling. */
final class MediaScale {
    static int width(int width,int height,int longEdge,int shortEdge,int fallback){
        if(longEdge<=0)return 0;
        if(width<=0||height<=0)return Math.min(longEdge,fallback);
        double factor=Math.min(1d,Math.min((double)longEdge/Math.max(width,height),(double)shortEdge/Math.min(width,height)));
        return Math.max(2,Math.min(width/2*2,(int)Math.round(width*factor/2)*2));
    }
}
