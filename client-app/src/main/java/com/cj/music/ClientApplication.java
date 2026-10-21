package com.cj.music;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public final class ClientApplication extends android.app.Application {
    /** 当前处于 started 状态的 Activity 数量：0 表示整个 app 在后台。 */
    private static int foreground;
    /** 「刚从后台回到前台」标记，一次性消费。 */
    private static boolean cameFromBackground;

    @Override public void onCreate(){
        super.onCreate();
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            /* 0 → 1 才是「从桌面/其它 app 回来」：app 内部两个界面互切时数量始终是 1→2→1，
               不会归零，所以这里能区分开「回到前台」和「在 app 里换界面」两种情形。 */
            @Override public void onActivityStarted(Activity activity){if(foreground++==0)cameFromBackground=true;}
            @Override public void onActivityStopped(Activity activity){if(foreground>0)foreground--;}
            @Override public void onActivityCreated(Activity activity,Bundle savedInstanceState){}
            @Override public void onActivityResumed(Activity activity){}
            @Override public void onActivityPaused(Activity activity){}
            @Override public void onActivitySaveInstanceState(Activity activity,Bundle outState){}
            @Override public void onActivityDestroyed(Activity activity){}
        });
    }

    /** 这次界面切换是不是「app 从后台回来」带来的（消费掉标记，只生效一次）。 */
    static boolean consumeBackgroundReturn(){
        boolean value=cameFromBackground;
        cameFromBackground=false;
        return value;
    }
}
