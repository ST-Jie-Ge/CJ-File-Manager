package com.cj.music;
import android.content.Context;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public final class LocalTransferFilesTest {
 private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 @Test public void uploadReadsRawPath() throws Exception {
  File file=File.createTempFile("upload-", ".txt",context().getCacheDir());
  try {try(OutputStream out=new FileOutputStream(file)){out.write(7);}
   try(InputStream in=LocalTransferFiles.openUpload(context().getContentResolver(),file.getAbsolutePath())){assertEquals(7,in.read());}
  }finally{file.delete();}
 }
 @Test public void commitPreservesFileCreatedDuringDownload() throws Exception {
  File folder=new File(context().getExternalFilesDir(null),"transfer-test-"+java.util.UUID.randomUUID());assertTrue(folder.mkdirs());
  File partial=new File(folder,"partial"),target=new File(folder,"target");
  try {
   assertFalse(target.exists()); // The pre-download check succeeds.
   try(OutputStream out=new FileOutputStream(partial)){out.write(9);}
   try(OutputStream out=new FileOutputStream(target)){out.write(7);} // Concurrent creator.
   try {LocalTransferFiles.commitDownload(partial,target,()->{});fail("Collision must fail");}catch(java.nio.file.FileAlreadyExistsException expected){}
   try(InputStream in=new FileInputStream(target)){assertEquals("Existing file overwritten",7,in.read());}
  }finally{partial.delete();target.delete();folder.delete();}
 }
 @Test public void uploadReadsFileAndContentUris()throws Exception {
  File directory=new File(context().getCacheDir(),"client-exports/"+java.util.UUID.randomUUID());assertTrue(directory.mkdirs());
  File file=new File(directory,"upload.txt");
  try {
   try(OutputStream out=new FileOutputStream(file)){out.write(7);}
   Uri content=androidx.core.content.FileProvider.getUriForFile(context(),context().getPackageName()+".client.exports",file);
   for(Uri uri:new Uri[]{Uri.fromFile(file),content})
    try(InputStream in=LocalTransferFiles.openUpload(context().getContentResolver(),uri.toString())){assertEquals(7,in.read());}
  }finally{file.delete();directory.delete();}
 }
 @Test public void successfulCommitAndCancelledCommit()throws Exception {
  File folder=new File(context().getExternalFilesDir(null),"transfer-test-"+java.util.UUID.randomUUID());assertTrue(folder.mkdirs());
  File partial=new File(folder,"partial"),target=new File(folder,"target");
  try {
   byte[] bytes=new byte[256*1024+37];new java.util.Random(7).nextBytes(bytes);
   try(OutputStream out=new FileOutputStream(partial)){out.write(bytes);}
   LocalTransferFiles.commitDownload(partial,target,()->{});
   assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(target.toPath()));assertTrue(target.delete());
   final int[] checks={0};
   try{LocalTransferFiles.commitDownload(partial,target,()->{if(++checks[0]>=3)throw new InterruptedIOException("cancelled");});fail();}
   catch(LocalTransferFiles.IncompleteCommitException expected){assertTrue(expected.getCause() instanceof InterruptedIOException);}
   assertTrue("Cancelled commit retains a possibly incomplete file for manual inspection",target.exists());assertTrue(partial.exists());
  }finally{partial.delete();target.delete();folder.delete();}
 }
 @Test public void replacementSurvivesCancelledCommit()throws Exception {
  File folder=new File(context().getExternalFilesDir(null),"transfer-test-"+java.util.UUID.randomUUID());assertTrue(folder.mkdirs());
  File partial=new File(folder,"partial"),target=new File(folder,"target"),moved=new File(folder,"moved");
  try {
   try(OutputStream out=new FileOutputStream(partial)){out.write(new byte[256*1024]);}
   final int[] checks={0};
   try{LocalTransferFiles.commitDownload(partial,target,()->{
    if(++checks[0]==2){
     assertTrue(target.renameTo(moved));
     try(OutputStream out=new FileOutputStream(target)){out.write(7);}
     throw new InterruptedIOException("cancelled");
    }
   });fail();}catch(IOException expected){}
   assertTrue("Another writer's replacement was deleted",target.exists());
   try(InputStream in=new FileInputStream(target)){assertEquals(7,in.read());}
  }finally{partial.delete();target.delete();moved.delete();folder.delete();}
 }
}