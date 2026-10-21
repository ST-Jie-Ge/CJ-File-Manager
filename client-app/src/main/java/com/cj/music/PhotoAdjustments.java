package com.cj.music;

/** Vibrance boosts low-saturation pixels more than already vivid colours. */
final class PhotoAdjustments {
    static int apply(int color,float brightness,float contrast,float vibrance){
        float r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        float max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        float saturation=max==0?0:(max-min)/max;
        float gain=1+vibrance*(vibrance>0?1-saturation:1);
        float gray=.2126f*r+.7152f*g+.0722f*b;
        r=((gray+(r-gray)*gain)-127.5f)*contrast+127.5f+brightness;
        g=((gray+(g-gray)*gain)-127.5f)*contrast+127.5f+brightness;
        b=((gray+(b-gray)*gain)-127.5f)*contrast+127.5f+brightness;
        return (color&0xff000000)|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b);
    }
    private static int clamp(float v){return Math.max(0,Math.min(255,Math.round(v)));}
}
