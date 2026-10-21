package com.cj.music;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class SystemBrightnessPermission {
    private static final String FIRST_REQUEST="system_brightness_permission_requested";
    static void requestOnceOnLaunch(Activity activity,SharedPreferences preferences){
        if(activity.isFinishing()||activity.isDestroyed()||preferences.getBoolean(FIRST_REQUEST,false))return;
        // Persist before showing the explanation. Cancelling must not repeat the startup prompt.
        if(!preferences.edit().putBoolean(FIRST_REQUEST,true).commit())return;
        if(!Settings.System.canWrite(activity))openSettings(activity);
    }
    static void openSettings(Activity activity){
        openSettings(activity,null);
    }
    static void openSettings(Activity activity,Runnable onDismiss){
        if(activity.isFinishing()||activity.isDestroyed())return;
        if(Settings.System.canWrite(activity)){launchSettings(activity);if(onDismiss!=null)onDismiss.run();return;}
        new MaterialAlertDialogBuilder(activity)
                .setTitle("视频组件缺少权限")
                .setNegativeButton("暂不授权",null)
                .setPositiveButton("去授权",(dialog,which)->launchSettings(activity))
                .setOnDismissListener(dialog->{if(onDismiss!=null)onDismiss.run();})
                .show();
    }
    private static void launchSettings(Activity activity){
        if(activity.isFinishing()||activity.isDestroyed())return;
        try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:"+activity.getPackageName())));}
        catch(ActivityNotFoundException error){Toast.makeText(activity,"请在系统设置中允许本应用修改系统设置",Toast.LENGTH_LONG).show();}
    }
}
