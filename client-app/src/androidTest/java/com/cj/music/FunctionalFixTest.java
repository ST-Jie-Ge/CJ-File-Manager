package com.cj.music;
import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.*;
import android.net.Uri;
import androidx.media3.datasource.DataSpec;
import com.cj.music.net.Endpoint;
public class FunctionalFixTest {
  @Test public void freshSizeRestAndPositionBoundsWorkOverFtpAndFtps()throws Exception {
    for(Endpoint.Protocol protocol:new Endpoint.Protocol[]{Endpoint.Protocol.FTP,Endpoint.Protocol.FTPS_EXPLICIT}){
      Object fixture=fixture();((Map<String,byte[]>)field(fixture,"files")).put("/video",new byte[]{1,2,3,4,5,6,7,8});
      for(String name:new String[]{"supportRest","supportSize"}){Field f=fixture.getClass().getDeclaredField(name);f.setAccessible(true);f.setBoolean(fixture,true);}
      Method port=fixture.getClass().getDeclaredMethod("port");port.setAccessible(true);
      ConnectionStore.Profile profile=new ConnectionStore.Profile("test","test","/",new Endpoint("localhost",(Integer)port.invoke(fixture),protocol,"test","test","UTF-8",protocol==Endpoint.Protocol.FTP?"":(String)field(fixture,"pin")));
      RemoteDataSource.Factory factory=new RemoteDataSource.Factory(profile,"/video",4);
      try{
        try(FtpClose source=new FtpClose(factory.createDataSource())){
          assertEquals(6,source.value.open(new DataSpec.Builder().setUri(Uri.parse("ftpstream://stream/video")).setPosition(2).build()));
          byte[] bytes=new byte[16];assertEquals(6,source.value.read(bytes,0,16));assertEquals(3,bytes[0]);assertEquals(8,bytes[5]);assertEquals(6,factory.received.get());
        }
        try(FtpClose source=new FtpClose(factory.createDataSource())){
          assertEquals(0,source.value.open(new DataSpec.Builder().setUri(Uri.parse("ftpstream://stream/video")).setPosition(8).build()));assertEquals(-1,source.value.read(new byte[1],0,1));
        }
        try(FtpClose source=new FtpClose(factory.createDataSource())){
          try{source.value.open(new DataSpec.Builder().setUri(Uri.parse("ftpstream://stream/video")).setPosition(9).build());fail();}catch(androidx.media3.datasource.DataSourceException expected){}
        }
      }finally{factory.close();((AutoCloseable)fixture).close();}
    }
  }
  @Test public void formatFollowsTargetAndUnsupportedOverwriteIsRejected()throws Exception {
    assertEquals(Bitmap.CompressFormat.PNG,ImageEditorActivity.outputFormat(new File("copy.png")));
    assertEquals(Bitmap.CompressFormat.JPEG,ImageEditorActivity.outputFormat(new File("copy.JPG")));
    assertEquals(Bitmap.CompressFormat.WEBP,ImageEditorActivity.outputFormat(new File("copy.webp")));
    try{ImageEditorActivity.outputFormat(new File("original.gif"));fail();}catch(IOException expected){}
  }
  @Test public void favoritesAreScopedToConnection()throws Exception {
    org.json.JSONObject favorite=new org.json.JSONObject();favorite.put("uri","/");favorite.put("profileId","server-a");
    assertTrue(MainActivity.favoriteMatches(favorite,"/","server-a"));
    assertFalse(MainActivity.favoriteMatches(favorite,"/","server-b"));
    assertFalse(MainActivity.favoriteMatches(favorite,"/",""));
  }
  @Test public void stagedImageWritePreservesUnrelatedTempAndAtomicallyReplaces()throws Exception {
    File root=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"image-fix-"+System.nanoTime());assertTrue(root.mkdir());
    File target=new File(root,"image.png"),unrelated=new File(root,"image.png.tmp"),temp=File.createTempFile(".image-",".tmp",root);
    try{
      try(FileOutputStream out=new FileOutputStream(target)){out.write(1);}try(FileOutputStream out=new FileOutputStream(unrelated)){out.write(2);}try(FileOutputStream out=new FileOutputStream(temp)){out.write(3);}
      SafeFileOperations.replace(temp,target);try(FileInputStream in=new FileInputStream(target)){assertEquals(3,in.read());}try(FileInputStream in=new FileInputStream(unrelated)){assertEquals(2,in.read());}
    }finally{target.delete();unrelated.delete();temp.delete();root.delete();}
  }
  static Object field(Object instance,String name)throws Exception{Field f=instance.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(instance);}
  static Object fixture()throws Exception{Class<?> c=Class.forName("com.cj.music.net.FtpSessionTest$Fixture");Constructor<?> ctor=c.getDeclaredConstructor(boolean.class);ctor.setAccessible(true);return ctor.newInstance(false);}
  static RemoteDataSource.Factory source(Object fixture,long size)throws Exception{
    Method port=fixture.getClass().getDeclaredMethod("port");port.setAccessible(true);
    ConnectionStore.Profile profile=new ConnectionStore.Profile("test","test","/",new Endpoint("127.0.0.1",(Integer)port.invoke(fixture),Endpoint.Protocol.FTP,"test","test","UTF-8",""));
    return new RemoteDataSource.Factory(profile,"/video",size);
  }
  @Test public void requestedLengthAndCurrentSizeAreRespected()throws Exception{
    Object fixture=fixture();((Map<String,byte[]>)field(fixture,"files")).put("/video",new byte[8]);
    try{
      try(FtpClose source=new FtpClose(source(fixture,8))){
        assertEquals(2,source.value.open(new DataSpec.Builder().setUri(Uri.parse("ftpstream://stream/video")).setLength(2).build()));
        assertEquals(2,source.value.read(new byte[16],0,16));assertEquals(-1,source.value.read(new byte[16],0,16));
      }
      try(FtpClose source=new FtpClose(source(fixture,4))){
        source.value.open(new DataSpec.Builder().setUri(Uri.parse("ftpstream://stream/video")).build());
        assertEquals(8,source.value.read(new byte[16],0,16));assertEquals(-1,source.value.read(new byte[16],0,16));
      }
    }finally{((AutoCloseable)fixture).close();}
  }
  @Test public void unsupportedRestDoesNotStartAnExpensiveFallback()throws Exception {
    Object fixture=fixture();((Map<String,byte[]>)field(fixture,"files")).put("/video",new byte[8]);
    try(RemoteDataSource.Factory factory=source(fixture,8);FtpClose source=new FtpClose(factory.createDataSource())){
      try{source.value.open(new DataSpec.Builder().setUri(Uri.parse("ftpstream://stream/video")).setPosition(1).build());fail();}
      catch(com.cj.music.net.RemoteSession.SeekNotSupportedException expected){assertTrue(expected.getMessage().contains("REST"));}
      assertFalse(((java.util.List<String>)field(fixture,"commands")).stream().anyMatch(command->command.startsWith("RETR")));
      assertEquals(0,factory.received.get());
    }finally{((AutoCloseable)fixture).close();}
  }
  @Test public void interruptClosesBlockedReadThenNextRequestCanReconnect()throws Exception{
    Object fixture=fixture();((Map<String,byte[]>)field(fixture,"files")).put("/video",new byte[8]);
    Field stall=fixture.getClass().getDeclaredField("stall");stall.setAccessible(true);stall.setBoolean(fixture,true);
    try(RemoteDataSource.Factory factory=source(fixture,8)){
    RemoteDataSource source=factory.createDataSource();
    Thread loader=new Thread(()->{try{source.open(new DataSpec.Builder().setUri(Uri.parse("ftpstream://stream/video")).build());source.read(new byte[1],0,1);fail();}catch(IOException expected){}finally{source.close();}});
    try{
      loader.start();assertTrue(((CountDownLatch)field(fixture,"transferStarted")).await(5,TimeUnit.SECONDS));
      loader.interrupt();loader.join(1500);assertFalse(loader.isAlive());
      stall.setBoolean(fixture,false);
      source.open(new DataSpec.Builder().setUri(Uri.parse("ftpstream://stream/video")).build());
      assertEquals(8,source.read(new byte[16],0,16));
    }finally{source.close();loader.interrupt();loader.join(2000);}
    }finally{((AutoCloseable)fixture).close();}
  }
  static class FtpClose implements AutoCloseable{
    final RemoteDataSource value;final RemoteDataSource.Factory owned;
    FtpClose(RemoteDataSource value){this.value=value;owned=null;}
    FtpClose(RemoteDataSource.Factory factory){owned=factory;value=factory.createDataSource();}
    public void close(){value.close();if(owned!=null)owned.close();}
  }
  @Test public void resetThenUnchangedResizesRemainUsable() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    ImageEditorView view=new ImageEditorView(context);
    view.load(Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888));
    view.resetToOriginal();
    view.resize(1f);view.resize(1f);
    view.resize(1f);assertFalse(view.getBitmap().isRecycled());view.rotate(true);view.undo();assertFalse(view.getBitmap().isRecycled());
    
    });
  }
  @Test public void renamePreservesExistingDestination()throws Exception {
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    File root=new File(context.getCacheDir(),"rename-probe-"+System.nanoTime());assertTrue(root.mkdir());
    File a=new File(root,"a"),b=new File(root,"b");
    try {try(FileOutputStream out=new FileOutputStream(a)){out.write(1);}try(FileOutputStream out=new FileOutputStream(b)){out.write(2);}
      try{SafeFileOperations.renameNew(a,b);fail();}catch(IOException expected){} assertTrue(a.exists());try(FileInputStream in=new FileInputStream(b)){assertEquals(2,in.read());}
    }finally{a.delete();b.delete();root.delete();}
  }
}

