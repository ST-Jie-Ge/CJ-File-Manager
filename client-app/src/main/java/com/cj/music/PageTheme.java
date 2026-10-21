package com.cj.music;

import android.app.Activity;
import android.content.Context;

/** Compose 界面读主题色的统一入口。
 *  Compose 侧不能直接调 Activity 的私有 attrColor()，而三个要改的界面又各是各的类，
 *  所以收成一个静态工具类，谁都调得动。 */
final class PageTheme {
    private PageTheme() {}
    static int accent(Context c){return MusicControls.color(c, com.cj.music.resources.R.attr.client_accent);}
    static int text(Context c){return MusicControls.color(c, com.cj.music.resources.R.attr.client_text);}
    static int secondary(Context c){return MusicControls.color(c, com.cj.music.resources.R.attr.client_text_secondary);}
    static int card(Context c){return MusicControls.color(c, com.cj.music.resources.R.attr.client_card);}
    static int outline(Context c){return MusicControls.color(c, com.cj.music.resources.R.attr.client_outline);}
    static int surface(Context c){return MusicControls.color(c, com.cj.music.resources.R.attr.client_surface_raised);}
    static int statusBar(Activity a){
        int id=a.getResources().getIdentifier("status_bar_height","dimen","android");
        return id>0?a.getResources().getDimensionPixelSize(id):Math.round(24*a.getResources().getDisplayMetrics().density);
    }
}
