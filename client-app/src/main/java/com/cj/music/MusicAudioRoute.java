package com.cj.music;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import java.util.ArrayList;
import java.util.List;

/** 音乐播放的音频输出设备选择。
 *
 *  走的是 ExoPlayer 的官方接口 {@code setPreferredAudioDevice(AudioDeviceInfo)}：
 *  直接把 AudioTrack 绑到指定设备上。相比 AudioManager 那套（setSpeakerphoneOn /
 *  startBluetoothSco 必须先切 MODE_IN_COMMUNICATION），它的好处是
 *  —— 蓝牙仍走 A2DP、保持立体声高音质，不会被降成单声道 16kHz 的通话通道；
 *  —— 音量键／播放页的音量条照常控制媒体音量（STREAM_MUSIC）；
 *  —— 不需要 MODIFY_AUDIO_SETTINGS 权限。
 *
 *  设备用「类型」记录而不是持有 AudioDeviceInfo 引用：断连重连后按类型重新查找即可，
 *  不会因为对象过期而切到一个已经拔掉的设备上。
 */
final class MusicAudioRoute {
    static final int AUTO=0,SPEAKER=1,WIRED=2,BLUETOOTH=3;
    private MusicAudioRoute(){}

    static final class Device{
        final int id;final String name;final String hint;
        Device(int id,String name,String hint){this.id=id;this.name=name;this.hint=hint;}
    }

    /** 当前可切换的输出入口：始终有「跟随系统」，其余只列实际连着的设备。 */
    static List<Device> devices(Context context){
        List<Device> list=new ArrayList<>();
        list.add(new Device(AUTO,"跟随系统","系统自动选择"));
        if(available(context,SPEAKER))list.add(new Device(SPEAKER,"扬声器","手机自带"));
        if(available(context,WIRED))list.add(new Device(WIRED,"有线耳机","已连接"));
        if(available(context,BLUETOOTH))list.add(new Device(BLUETOOTH,"蓝牙耳机","已连接"));
        return list;
    }

    /** 把类型翻译成一个当前真实存在的设备；AUTO 与「找不到」都返回 null（=跟随系统）。 */
    static AudioDeviceInfo resolve(Context context,int target){
        if(target==AUTO)return null;
        AudioManager audio=audio(context);
        if(audio==null)return null;
        AudioDeviceInfo[] all=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if(all==null)return null;
        for(AudioDeviceInfo info:all)if(matches(info.getType(),target))return info;
        return null;
    }

    static boolean available(Context context,int target){
        AudioManager audio=audio(context);
        if(audio==null)return false;
        AudioDeviceInfo[] all=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if(all==null)return false;
        for(AudioDeviceInfo info:all)if(matches(info.getType(),target))return true;
        return false;
    }

    private static boolean matches(int type,int target){
        if(target==SPEAKER)return type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        if(target==WIRED)return type==AudioDeviceInfo.TYPE_WIRED_HEADPHONES||type==AudioDeviceInfo.TYPE_WIRED_HEADSET
                ||type==AudioDeviceInfo.TYPE_USB_HEADSET||type==AudioDeviceInfo.TYPE_USB_DEVICE;
        if(target==BLUETOOTH)return type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP||type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        return false;
    }

    private static AudioManager audio(Context context){
        return (AudioManager)context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }
}
