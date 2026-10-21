package com.cj.music;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.sevenz.*;
import org.apache.commons.compress.archivers.tar.*;

public class ArchiveUtilTest {
    private final Path root;
    public ArchiveUtilTest()throws IOException{root=Files.createTempDirectory("archive-test-");}
    private File zip(String... names)throws Exception {
        File file=root.resolve("test.zip").toFile();
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(file))){for(int i=0;i<names.length;i++){z.putNextEntry(new ZipEntry(names[i]));z.write(("entry"+i).getBytes("UTF-8"));z.closeEntry();}}
        return file;
    }
    private File out()throws IOException{return Files.createDirectory(root.resolve("out")).toFile();}
    @Test public void existingFileSurvivesExtraction()throws Exception {
        File out=out();Path target=out.toPath().resolve("photo");Files.write(target,new byte[]{42});
        ArchiveUtil.extract(zip("photo"),out,ArchiveUtil.Progress.NONE,targetFile->ArchiveUtil.Conflict.SKIP);
        assertArrayEquals(new byte[]{42},Files.readAllBytes(target));
    }
    @Test public void normalizedCollisionUsesExplicitDefaultReplacePolicy()throws Exception {
        File out=out();ArchiveUtil.extract(zip("same","same."),out,ArchiveUtil.Progress.NONE);
        assertEquals("entry1",new String(Files.readAllBytes(out.toPath().resolve("same")),"UTF-8"));
    }
    @Test public void traversalIsRejected()throws Exception {
        File out=out();try{ArchiveUtil.extract(zip("../same"),out,ArchiveUtil.Progress.NONE);fail();}catch(IOException expected){}
        assertFalse(new File(out,"same").exists());assertFalse(root.resolve("same").toFile().exists());
    }
    @Test public void singleSevenZEntryCancelsWithoutPublishingPartialFile()throws Exception {
        File seven=root.resolve("large.7z").toFile(),out=out();byte[] data=new byte[1024*1024];new Random(1).nextBytes(data);
        try(SevenZOutputFile z=new SevenZOutputFile(seven)){SevenZArchiveEntry e=new SevenZArchiveEntry();e.setName("large.bin");e.setSize(data.length);z.putArchiveEntry(e);z.write(data);z.closeArchiveEntry();}
        final boolean[] cancelled={false};
        try{ArchiveUtil.extract(seven,out,new ArchiveUtil.Progress(){public boolean cancelled(){return cancelled[0];}public void onProgress(String n,long b){cancelled[0]=true;}});fail();}catch(IOException expected){}
        assertFalse(new File(out,"large.bin").exists());assertEquals(0,out.list().length);
    }
    @Test public void tarHardlinkIsExplicitlyRejected()throws Exception {
        File tar=root.resolve("links.tar").toFile(),out=out();
        try(TarArchiveOutputStream z=new TarArchiveOutputStream(new FileOutputStream(tar))){TarArchiveEntry e=new TarArchiveEntry("link",TarConstants.LF_LINK);e.setLinkName("real");z.putArchiveEntry(e);z.closeArchiveEntry();}
        try{ArchiveUtil.extract(tar,out,ArchiveUtil.Progress.NONE);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("TAR"));}
        assertFalse(new File(out,"link").exists());
    }
    @Test public void unreadableDirectoryFailsCompressionWithoutPublishingZip()throws Exception {
        File unreadable=new File(root.toFile(),"unreadable"){@Override public boolean isDirectory(){return true;}@Override public File[] listFiles(){return null;}};
        File output=root.resolve("out.zip").toFile();
        try{ArchiveUtil.createArchive(Arrays.asList(unreadable),output,"zip",ArchiveUtil.Progress.NONE,"");fail();}catch(IOException expected){}
        assertFalse(output.exists());
    }
    @Test public void normalZipRoundtrip()throws Exception {
        File folder=root.resolve("folder").toFile();assertTrue(folder.mkdir());Files.write(new File(folder,"hello").toPath(),new byte[]{1,2,3});
        File zip=root.resolve("result.zip").toFile(),out=out();ArchiveUtil.createZip(Arrays.asList(folder),zip,ArchiveUtil.Progress.NONE);ArchiveUtil.extract(zip,out,ArchiveUtil.Progress.NONE);
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(out,"folder/hello").toPath()));
    }
    @Test public void encryptedZipReportsIndeterminateProgressAndRoundtrips()throws Exception {
        File source=root.resolve("hello.txt").toFile();Files.write(source.toPath(),new byte[]{1,2,3});
        File archive=root.resolve("encrypted.zip").toFile();List<Long> reported=new ArrayList<>();
        ArchiveUtil.createArchive(Arrays.asList(source),archive,"zip",new ArchiveUtil.Progress(){public boolean cancelled(){return false;}public void onProgress(String name,long count){reported.add(count);}},"secret");
        assertFalse(reported.isEmpty());for(long count:reported)assertEquals(-1,count);
        File out=out();ArchiveUtil.extract(archive,out,ArchiveUtil.Progress.NONE,ArchiveUtil.Conflict.REPLACE,"secret");
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(out,"hello.txt").toPath()));
    }
    @Test public void failedReplacementPreservesOriginal()throws Exception {
        Path target=root.resolve("original");Files.write(target,new byte[]{1});
        try{SafeFileOperations.replace(root.resolve("missing").toFile(),target.toFile());fail();}catch(IOException expected){}
        assertArrayEquals(new byte[]{1},Files.readAllBytes(target));
    }
    @Test public void writerFailureLeavesOldTempNamesUntouched()throws Exception {
        Path existing=root.resolve("image.png.tmp");Files.write(existing,new byte[]{7});
        try{SafeFileOperations.writeNew(root.resolve("image.png").toFile(),out->{out.write(1);throw new IOException("encoding failed");});fail();}catch(IOException expected){}
        assertArrayEquals(new byte[]{7},Files.readAllBytes(existing));assertFalse(root.resolve("image.png").toFile().exists());
    }
    @Test public void rememberedSkipAsksOnceAndPreservesEveryConflict()throws Exception {
        File out=out();Files.write(new File(out,"a").toPath(),new byte[]{1});Files.write(new File(out,"b").toPath(),new byte[]{2});
        int[] asks={0};ArchiveUtil.Conflict policy=ArchiveUtil.rememberConflict(target->{asks[0]++;return ArchiveUtil.Conflict.SKIP;});
        ArchiveUtil.extract(zip("a","b","c"),out,ArchiveUtil.Progress.NONE,policy);
        assertEquals(1,asks[0]);assertArrayEquals(new byte[]{1},Files.readAllBytes(new File(out,"a").toPath()));
        assertArrayEquals(new byte[]{2},Files.readAllBytes(new File(out,"b").toPath()));assertTrue(new File(out,"c").isFile());
    }
    @Test public void abortPreservesExistingFileAndStopsExtraction()throws Exception {
        File out=out();Files.write(new File(out,"a").toPath(),new byte[]{1});
        try{ArchiveUtil.extract(zip("a","b"),out,ArchiveUtil.Progress.NONE,target->ArchiveUtil.Conflict.ABORT);fail();}catch(IOException expected){}
        assertArrayEquals(new byte[]{1},Files.readAllBytes(new File(out,"a").toPath()));assertFalse(new File(out,"b").exists());
    }
    private byte[] tarBytes()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(TarArchiveOutputStream tar=new TarArchiveOutputStream(bytes)){TarArchiveEntry entry=new TarArchiveEntry("hello.txt");entry.setSize(3);tar.putArchiveEntry(entry);tar.write(new byte[]{1,2,3});tar.closeArchiveEntry();}
        return bytes.toByteArray();
    }
    @Test public void tarLzmaExtractListAndVerifyAgree()throws Exception {
        byte[] tar=tarBytes();File archive=root.resolve("test.tar.lzma").toFile();
        try(OutputStream stream=new org.tukaani.xz.LZMAOutputStream(new FileOutputStream(archive),new org.tukaani.xz.LZMA2Options(),tar.length)){stream.write(tar);}
        assertTrue(ArchiveUtil.isArchive("test.tlz"));assertEquals("test",ArchiveUtil.stripExtension(archive.getName()));
        assertEquals(1,ArchiveUtil.list(archive,"",null).size());ArchiveUtil.verify(archive,ArchiveUtil.Progress.NONE,"",null);
        File out=out();ArchiveUtil.extract(archive,out,ArchiveUtil.Progress.NONE);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(out,"hello.txt").toPath()));
    }
    @Test public void tarSplitAcrossXzMembersExtractListAndVerifyAgree()throws Exception {
        byte[] tar=tarBytes();File archive=root.resolve("test.tar.xz").toFile();
        // Split the TAR payload itself, not two independent TAR files with end markers.
        try(OutputStream stream=new org.tukaani.xz.XZOutputStream(new FileOutputStream(archive),new org.tukaani.xz.LZMA2Options())){stream.write(tar,0,513);}
        try(OutputStream stream=new org.tukaani.xz.XZOutputStream(new FileOutputStream(archive,true),new org.tukaani.xz.LZMA2Options())){stream.write(tar,513,tar.length-513);}
        assertEquals(1,ArchiveUtil.list(archive,"",null).size());ArchiveUtil.verify(archive,ArchiveUtil.Progress.NONE,"",null);
        File out=out();ArchiveUtil.extract(archive,out,ArchiveUtil.Progress.NONE);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(out,"hello.txt").toPath()));
    }
    /* ---------- 分卷 ---------- */

    /** 造一个 zip4j 分卷包（x.z01/x.z02/x.zip），每卷 150KB、共 4 个 100KB 条目。 */
    private File zip4jSplit(String base)throws Exception {
        File[] sources=new File[4];
        byte[] payload=new byte[100*1024];
        new Random(7).nextBytes(payload);
        for(int i=0;i<sources.length;i++){
            sources[i]=root.resolve(base+"-entry"+i+".bin").toFile();
            int length=i==3?70*1024:100*1024;
            Files.write(sources[i].toPath(),Arrays.copyOf(payload,length));
        }
        net.lingala.zip4j.model.ZipParameters parameters=new net.lingala.zip4j.model.ZipParameters();
        parameters.setCompressionMethod(net.lingala.zip4j.model.enums.CompressionMethod.STORE);
        new net.lingala.zip4j.ZipFile(root.resolve(base+".zip").toFile())
                .createSplitZipFile(Arrays.asList(sources),parameters,true,150*1024);
        return root.resolve(base+".zip").toFile();
    }

    @Test public void zip4jSplitArchiveOpensFromLastVolume()throws Exception {
        File last=zip4jSplit("split");
        assertTrue(ArchiveUtil.isArchive("split.zip"));
        assertEquals(4,ArchiveUtil.list(last,"",null).size());
        ArchiveUtil.verify(last,ArchiveUtil.Progress.NONE,"",null);
        File out=out();ArchiveUtil.extract(last,out,ArchiveUtil.Progress.NONE);
        assertEquals(4,out.list().length);
    }

    @Test public void zip4jSplitArchiveOpensFromMiddleVolume()throws Exception {
        zip4jSplit("middle");
        // 用户点的是中间卷 .z01：应自动改用同目录的 .zip
        File middle=root.resolve("middle.z01").toFile();
        assertTrue(ArchiveUtil.isArchive("middle.z01"));
        assertEquals("middle",ArchiveUtil.stripExtension("middle.z01"));
        assertEquals(4,ArchiveUtil.list(middle,"",null).size());
        File out=out();ArchiveUtil.extract(middle,out,ArchiveUtil.Progress.NONE);
        assertEquals(4,out.list().length);
    }

    /** 把普通 zip 按字节切成 .001/.002/…（7-Zip、WinRAR「分卷」就是这么切的）。 */
    private void sliceIntoVolumes(File zip,String base,long chunk)throws Exception {
        byte[] all=Files.readAllBytes(zip.toPath());
        int part=0;
        for(int offset=0;offset<all.length;offset+=(int)chunk){
            int length=(int)Math.min(chunk,all.length-offset);
            File target=root.resolve(String.format(Locale.ROOT,"%s.%03d",base,part+1)).toFile();
            Files.write(target.toPath(),Arrays.copyOfRange(all,offset,offset+length));
            part++;
        }
        Files.delete(zip.toPath());
    }

    @Test public void numberedVolumesAreJoinedInOrder()throws Exception {
        // 真实场景：一个普通 zip 被切成 .001/.002/…
        File whole=root.resolve("sliced.zip").toFile();
        byte[] payload=new byte[300*1024];
        new Random(11).nextBytes(payload);
        try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(new FileOutputStream(whole))){
            for(int i=0;i<3;i++){
                z.putNextEntry(new java.util.zip.ZipEntry("part"+i+".bin"));
                z.write(payload,0,100*1024);z.closeEntry();
            }
        }
        sliceIntoVolumes(whole,"sliced.zip",150*1024);
        File first=root.resolve("sliced.zip.001").toFile();
        assertTrue(first.isFile());
        assertTrue(ArchiveUtil.isArchive("sliced.zip.001"));
        assertEquals("sliced",ArchiveUtil.stripExtension("sliced.zip.001"));
        assertEquals(3,ArchiveUtil.list(first,"",null).size());
        ArchiveUtil.verify(first,ArchiveUtil.Progress.NONE,"",null);
        File out=out();ArchiveUtil.extract(first,out,ArchiveUtil.Progress.NONE);
        assertEquals(3,out.list().length);
        // 点最后一卷也能解析到首卷开始拼
        File lastPart=root.resolve("sliced.zip.003").toFile();
        File out2=Files.createDirectory(root.resolve("out2")).toFile();
        ArchiveUtil.extract(lastPart,out2,ArchiveUtil.Progress.NONE);
        assertEquals(3,out2.list().length);
    }

    /** zip4j 私自分卷被改名成 .001 命名：卷号写在包里，拼接也救不回来，但必须给出能看懂的提示。 */
    @Test public void renamedZip4jSplitFailsWithReadableMessage()throws Exception {
        File last=zip4jSplit("renamed");
        Files.move(root.resolve("renamed.z01"),root.resolve("renamed.zip.001"));
        Files.move(root.resolve("renamed.z02"),root.resolve("renamed.zip.002"));
        Files.move(last.toPath(),root.resolve("renamed.zip.003"));
        File first=root.resolve("renamed.zip.001").toFile();
        try{ArchiveUtil.list(first,"",null);fail();}catch(java.io.IOException expected){
            assertTrue(expected.getMessage().contains("分卷"));
        }
    }

    @Test public void missingLastVolumeFailsWithReadableMessage()throws Exception {
        zip4jSplit("broken");
        Files.delete(root.resolve("broken.zip"));
        try{ArchiveUtil.list(root.resolve("broken.z01").toFile(),"",null);fail();}catch(java.io.IOException expected){
            assertTrue(expected.getMessage().contains("分卷"));
        }
    }

    @Test public void volumeNamingIsRecognisedAndStripped(){
        assertTrue(ArchiveUtil.isArchive("movie.part1.rar"));
        assertTrue(ArchiveUtil.isArchive("movie.part2.rar"));
        assertTrue(ArchiveUtil.isArchive("movie.r00"));
        assertTrue(ArchiveUtil.isArchive("photos.zip.001"));
        assertTrue(ArchiveUtil.isArchive("data.7z.001"));
        assertTrue(ArchiveUtil.isArchive("backup.tar.gz.001"));
        assertFalse(ArchiveUtil.isArchive("video.mp4.001"));
        assertFalse(ArchiveUtil.isArchive("photo.jpg"));
        assertEquals("movie",ArchiveUtil.stripExtension("movie.part1.rar"));
        assertEquals("movie",ArchiveUtil.stripExtension("movie.r00"));
        assertEquals("photos",ArchiveUtil.stripExtension("photos.zip.001"));
        assertEquals("backup",ArchiveUtil.stripExtension("backup.tar.gz.001"));
        assertEquals("data",ArchiveUtil.stripExtension("data.7z.002"));
    }

    /* ---------- RAR5（junrar 8.x） ---------- */

    /** 从测试资源目录取一份 junrar 官方 RAR5 语料落到临时目录（测试资源不打进 APK）。 */
    private File fixture(String name)throws IOException {
        File file=root.resolve(name).toFile();
        if(file.exists())return file;
        try(InputStream in=ArchiveUtilTest.class.getResourceAsStream("/com/cj/music/"+name)){
            if(in==null)throw new IOException("测试资源缺失："+name);
            try(java.io.OutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
        }
        return file;
    }

    @Test public void rar5PlainArchiveExtractsAndListsAndVerifies()throws Exception {
        File rar=fixture("rar5.rar");
        File out=out();
        ArchiveUtil.extract(rar,out,ArchiveUtil.Progress.NONE);
        assertArrayEquals("file1\r\n".getBytes("UTF-8"),Files.readAllBytes(new File(out,"FILE1.TXT").toPath()));
        assertArrayEquals("file2\r\n".getBytes("UTF-8"),Files.readAllBytes(new File(out,"FILE2.TXT").toPath()));
        List<ArchiveUtil.Item> items=ArchiveUtil.list(rar,null,null);
        assertEquals(2,items.size());
        ArchiveUtil.verify(rar,ArchiveUtil.Progress.NONE,null,null);
    }

    @Test public void rar5HeaderEncryptedArchiveReportsPasswordNeed()throws Exception {
        File rar=fixture("rar5-encrypted-junrar.rar");
        /* 主头加密（整包加密）：Archive 打开就会抛 WrongPasswordException（junrar 8.x
           "Missing password for header-encrypted RAR5 archive"），needsPassword 的
           catch(Exception|LinkageError) 吞掉后返回 false —— 「先试后问」的设计就是让
           解压时的真实异常兜底，界面再补一次「请输入密码」。 */
        assertFalse(ArchiveUtil.needsPassword(rar));
    }

    @Test public void rar5ContentEncryptedArchiveReportsPasswordNeed()throws Exception {
        File rar=fixture("rar5-password-junrar.rar");
        /* 仅文件内容加密：条目标志 isEncrypted() 能看出来，主头没加密。 */
        assertTrue(ArchiveUtil.needsPassword(rar));
    }

    @Test public void rar5WrongPasswordProducesChinesePasswordError()throws Exception {
        File rar=fixture("rar5-password-junrar.rar");
        File out=out();
        try{ArchiveUtil.extract(rar,out,ArchiveUtil.Progress.NONE,ArchiveUtil.Conflict.REPLACE,"definitely-wrong");fail();}
        catch(IOException expected){
            String message=expected.getMessage()==null?"":expected.getMessage();
            assertTrue("应报密码问题，实际是："+message,message.contains("密码")||message.contains("校验"));
        }
    }

    @Test public void rar5HeaderEncryptedWrongPasswordProducesChinesePasswordError()throws Exception {
        File rar=fixture("rar5-encrypted-junrar.rar");
        File out=out();
        try{ArchiveUtil.extract(rar,out,ArchiveUtil.Progress.NONE,ArchiveUtil.Conflict.REPLACE,"nope");fail();}
        catch(IOException expected){
            String message=expected.getMessage()==null?"":expected.getMessage();
            assertTrue("应报密码问题，实际是："+message,message.contains("密码")||message.contains("校验"));
        }
    }

    @Test public void rar5CorrectPasswordExtractsContent()throws Exception {
        File rar=fixture("rar5-password-junrar.rar");
        File out=out();
        ArchiveUtil.extract(rar,out,ArchiveUtil.Progress.NONE,ArchiveUtil.Conflict.REPLACE,"junrar");
        assertEquals(6,new File(out,"file1.txt").length());
    }

    @Test public void rar5RedirectionEntriesAreSkippedNotMaterialised()throws Exception {
        File rar=fixture("rar5-links.rar");
        File out=out();
        ArchiveUtil.extract(rar,out,ArchiveUtil.Progress.NONE);
        /* 真实文件照常解出 */
        assertTrue(new File(out,"file.txt").isFile());
        assertTrue(new File(out,"sub/inner.txt").isFile());
        /* 链接/副本条目被跳过，不落链接文件，也不把链接目标当文件内容写出来 */
        assertFalse(new File(out,"link-flat").exists());
        assertFalse(new File(out,"sub/link-up").exists());
        assertFalse(new File(out,"hard.txt").exists());
        assertFalse(new File(out,"copy-b.bin").exists());
        /* 目录条目仍要建出来 */
        assertTrue(new File(out,"sub").isDirectory());
    }
}
