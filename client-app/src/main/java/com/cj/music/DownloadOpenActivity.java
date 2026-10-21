package com.cj.music;
import android.app.Activity;import android.content.Intent;import android.net.Uri;import android.os.Bundle;
/** External input only prepares the sidebar dialog; the user confirms the download. */
public final class DownloadOpenActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);Intent input=getIntent();new Thread(()->{try{String value;if(Intent.ACTION_SEND.equals(input.getAction())){Uri stream=input.getParcelableExtra(Intent.EXTRA_STREAM);value=stream==null?DownloadInput.text(input.getStringExtra(Intent.EXTRA_TEXT)):DownloadInput.read(this,stream);}else{if(input.getData()==null)throw new java.io.IOException("没有下载链接");value=DownloadInput.read(this,input.getData());}runOnUiThread(()->{startActivity(new Intent(this,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("download_link",value));finish();});}catch(Exception|LinkageError error){runOnUiThread(()->{android.widget.Toast.makeText(this,error.getMessage()==null?"无法读取下载链接":error.getMessage(),android.widget.Toast.LENGTH_LONG).show();finish();});}},"download-input").start();}
}
