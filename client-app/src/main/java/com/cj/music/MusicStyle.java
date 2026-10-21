package com.cj.music;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

/** Music-only visual language: warm paper, ink, rounded record sleeves. */
final class MusicStyle {
    static final int ACCENT=0xff486e5d;
    static final Typeface DISPLAY=Typeface.create("serif",Typeface.NORMAL);
    static final Typeface LABEL=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    /** 同一套字形的粗体：预设列表里靠字重区分「内置」与「我的预设」，不再用分区标题。 */
    static final Typeface LABEL_BOLD=Typeface.create("sans-serif-medium",Typeface.BOLD);
    static GradientDrawable surface(int tint,float radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.WHITE,androidx.core.graphics.ColorUtils.blendARGB(tint,Color.WHITE,.48f)});d.setCornerRadius(radius);d.setStroke(1,0xccffffff);return d;}
}
