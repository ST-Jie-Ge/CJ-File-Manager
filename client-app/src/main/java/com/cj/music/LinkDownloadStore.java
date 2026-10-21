package com.cj.music;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class LinkDownloadStore {
    static final class Task {
        String id,link,name,directory,state="暂停",error="";long done,total,rate;int peers;
        Task(DownloadLink l,File root){id=UUID.randomUUID().toString();link=l.uri;name=l.name;total=l.size;directory=new File(root,name+"-"+id.substring(0,8)).getAbsolutePath();}
        Task(JSONObject j)throws Exception{id=j.getString("id");link=j.getString("link");DownloadLink.parse(link);name=j.getString("name");directory=j.getString("directory");state=j.optString("state","暂停");error=j.optString("error");done=j.optLong("done");total=j.optLong("total");}
        JSONObject json()throws Exception {JSONObject j=new JSONObject();j.put("id",id).put("link",link).put("name",name).put("directory",directory).put("state",state).put("error",error).put("done",done).put("total",total).put("rate",rate).put("peers",peers);return j;}
        boolean active(){return state.equals("下载中")||state.equals("寻找资源");}
    }
    final AtomicFile file;
    LinkDownloadStore(Context c){file=new AtomicFile(new File(c.getFilesDir(),"link-downloads.json"));}
    List<Task> read(){List<Task> out=new ArrayList<>();try {byte[] bytes=file.readFully();if(bytes.length>4*1024*1024)return out;JSONArray a=new JSONArray(new String(bytes,StandardCharsets.UTF_8));for(int i=0;i<a.length();i++)try{out.add(new Task(a.getJSONObject(i)));}catch(Exception ignored){}}catch(Exception ignored){}return out;}
    void write(List<Task> tasks)throws Exception{JSONArray a=new JSONArray();for(Task t:tasks)a.put(t.json());FileOutputStream s=null;try{s=file.startWrite();s.write(a.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(s);}catch(Exception e){if(s!=null)file.failWrite(s);throw e;}}
}
