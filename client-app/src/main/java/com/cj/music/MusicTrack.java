package com.cj.music;
import org.json.*;
final class MusicTrack {
    final String name,path,profile;final long size,time;
    /* artist 是后来加的：显示歌手名，歌手从文件标签里读。
       老记录里没有这个字段，from() 读出来是空串 —— 重新读标签后补齐。 */
    String title="",album="",artist="";int number;long createdAt;
    String displayTitle(){if(!title.isEmpty())return title;int dot=name.lastIndexOf('.');return dot>0?name.substring(0,dot):name;}
    MusicTrack(String name,String path,String profile,long size,long time){this.name=name;this.path=path;this.profile=profile==null?"":profile;this.size=size;this.time=time;}
    String id(){return profile+"|"+path;}
    /** 曲目所属「文件夹」的标识：连接 + 所在目录，供文件夹分类聚合与整目录隐藏使用。 */
    String folderKey(){int split=path.lastIndexOf('/');return profile+"|"+(split>=0?path.substring(0,split):path);}
    JSONObject json(){JSONObject j=new JSONObject();try{j.put("name",name).put("path",path).put("profile",profile).put("size",size).put("time",time).put("title",title).put("album",album).put("artist",artist).put("number",number).put("createdAt",createdAt);}catch(JSONException impossible){throw new IllegalStateException(impossible);}return j;}
    static MusicTrack from(JSONObject j){MusicTrack t=new MusicTrack(j.optString("name"),j.optString("path"),j.optString("profile"),j.optLong("size"),j.optLong("time"));t.title=j.optString("title");t.album=j.optString("album");t.artist=j.optString("artist");t.number=j.optInt("number");t.createdAt=j.optLong("createdAt");return t;}
}
