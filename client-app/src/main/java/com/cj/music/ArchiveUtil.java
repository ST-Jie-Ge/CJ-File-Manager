package com.cj.music;

import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import com.github.junrar.Archive;
import com.github.junrar.BrokenHeader;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.rar5.Rar5Redirection;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 压缩 / 解压：支持 zip（含 AES 加密）、7z（含加密）、rar（含加密）、tar、tar.gz(tgz)、tar.bz2、tar.xz、
 * gz、bz2、xz、lzma；压缩可生成 zip、7z、tar、tar.gz、tar.bz2、tar.xz。
 * 所有写盘路径都做 zip-slip 校验，防止压缩包里的 ../ 逃出目标目录。
 */
final class ArchiveUtil {
    private ArchiveUtil(){}

    /** 进度回调：name 当前条目，written 已写入字节数（用于界面显示）。 */
    interface Progress {
        void onProgress(String name,long written);
        boolean cancelled();
        Progress NONE=new Progress(){public void onProgress(String name,long written){}public boolean cancelled(){return false;}};
    }

    private static final Set<String> ARCHIVES=new HashSet<>(Arrays.asList(
        "zip","rar","7z","gz","tgz","tar","bz2","tbz","tbz2","xz","txz","lzma","tlz",
        "jar","apk","apks","xapk","aab"));

    static boolean isArchive(String name){
        if(ARCHIVES.contains(ext(name)))return true;
        if(name==null)return false;
        // 分卷也算压缩包：用户点的可能是 .001、中间卷 .z01、或 .part2.rar，入口要能点开。
        java.util.regex.Matcher numbered=NUMBERED_VOLUME.matcher(name);
        if(numbered.matches())return volumeSuffix(numbered.group(1))!=null;
        return ZIP_SPLIT_VOLUME.matcher(name).matches()||RAR_SPLIT_VOLUME.matcher(name).matches()||RAR_PART_VOLUME.matcher(name).matches();
    }
    static String ext(String name){int dot=name==null?-1:name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
    /**
     * 去掉压缩/打包后缀：note.txt.gz → note.txt，sample.tar.gz → sample，sample.zip → sample。
     * 复合后缀（.tar.gz/.tar.bz2/.tar.xz）整体去掉，否则解压目录会变成 sample.tar 这种半截名字。
     * 分卷同样整段去掉：photos.zip.001 / movie.part1.rar / data.z01 → photos / movie / data。
     */
    static String stripExtension(String name){
        if(name==null)return "";
        java.util.regex.Matcher numbered=NUMBERED_VOLUME.matcher(name);
        if(numbered.matches()){
            String base=numbered.group(1);
            if(volumeSuffix(base)!=null)return stripExtension(base);
        }
        java.util.regex.Matcher part=RAR_PART_VOLUME.matcher(name);
        if(part.matches())return part.group(1);
        if(ZIP_SPLIT_VOLUME.matcher(name).matches()||RAR_SPLIT_VOLUME.matcher(name).matches()){
            int cut=name.lastIndexOf('.');
            return cut<=0?name:name.substring(0,cut);
        }
        String lower=name.toLowerCase(Locale.ROOT);
        for(String compound:new String[]{".tar.gz",".tar.bz2",".tar.xz",".tar.lzma"}){
            if(lower.endsWith(compound))return name.substring(0,name.length()-compound.length());
        }
        int dot=name.lastIndexOf('.');
        return dot<=0?name:name.substring(0,dot);
    }
    /** a.txt.gz → a.txt；log.gz → log；x.tar.gz → x.tar（单文件 gz 只需剥一层） */
    private static String stripCompressed(String name){return stripExtension(name);}

    /* ---------- 分卷压缩包 ---------- */

    /** 数字分卷：photos.zip.001/.002…（7-Zip、WinRAR、好压都用这一套）。 */
    private static final java.util.regex.Pattern NUMBERED_VOLUME=
            java.util.regex.Pattern.compile("^(.+)\\.(\\d{3})$",java.util.regex.Pattern.CASE_INSENSITIVE);
    /** zip4j 分卷的中间卷：photos.z01/.z02…，最后一卷是 photos.zip。 */
    private static final java.util.regex.Pattern ZIP_SPLIT_VOLUME=
            java.util.regex.Pattern.compile("^(.+)\\.z\\d{2}$",java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 老式 RAR 分卷的中间卷：movie.r00/.r01…，首卷是 movie.rar。 */
    private static final java.util.regex.Pattern RAR_SPLIT_VOLUME=
            java.util.regex.Pattern.compile("^(.+)\\.r\\d{2}$",java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 新式 RAR 分卷：movie.part1.rar/.part2.rar…，首卷是 part1。 */
    private static final java.util.regex.Pattern RAR_PART_VOLUME=
            java.util.regex.Pattern.compile("^(.+)\\.part(\\d+)\\.rar$",java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 数字分卷的 base 后缀 → 拼接后走哪个解压库；不是压缩包返回 null。 */
    private static String volumeSuffix(String base){
        String lower=base.toLowerCase(Locale.ROOT);
        for(String compound:new String[]{".tar.gz",".tar.bz2",".tar.xz",".tar.lzma"})
            if(lower.endsWith(compound))return compound;
        for(String simple:new String[]{".zip",".7z",".tar",".tgz",".tbz2",".tbz",".txz",".tlz",".gz",".bz2",".xz",".lzma"})
            if(lower.endsWith(simple))return simple;
        return null;
    }

    /**
     * 分卷解析结果：file 是真正交给解压库的数据源，name 决定格式（拼接后的临时文件名不能决定格式，
     * 否则 photos.zip.001 会被当成 .001 后缀）。temporary 非空时由 close() 清理。
     */
    static final class Volume implements java.io.Closeable{
        final File file;final String name;private final File temporary;
        Volume(File file,String name,File temporary){this.file=file;this.name=name;this.temporary=temporary;}
        @Override public void close(){if(temporary!=null&&!temporary.delete())temporary.deleteOnExit();}
    }

    /**
     * 把「用户点的那一卷」解析成能直接解压的东西，非分卷返回 null：
     * <ul>
     *   <li>x.zip.001 这类数字分卷：按 001→002→… 顺序拼接成临时文件（分卷本身就是字节切片，拼起来即完整包）</li>
     *   <li>x.z01（zip4j 中间卷）：改用同目录的 x.zip，zip4j 认得自己的分卷</li>
     *   <li>x.r00 / x.part2.rar：改用首卷 x.rar / x.part1.rar，junrar 会按命名规则自己续读</li>
     * </ul>
     */
    static Volume resolveVolume(File archive,Progress progress)throws IOException{
        String name=archive.getName();
        File parent=archive.getParentFile();
        java.util.regex.Matcher numbered=NUMBERED_VOLUME.matcher(name);
        if(numbered.matches()){
            String base=name.substring(0,numbered.end(1));
            String suffix=volumeSuffix(base);
            if(suffix!=null){
                File joined=assembleNumbered(archive,base,suffix,progress);
                return new Volume(joined,base,joined);
            }
        }
        java.util.regex.Matcher zipPart=ZIP_SPLIT_VOLUME.matcher(name);
        if(zipPart.matches()){
            String base=name.substring(0,zipPart.end(1));
            File last=new File(parent,base+".zip");
            if(!last.isFile())
                throw new IOException("分卷不完整：缺少最后一卷 "+last.getName()+"（ZIP 分卷要连同 .zip 那一卷一起放着）");
            return new Volume(last,last.getName(),null);
        }
        java.util.regex.Matcher rarPart=RAR_PART_VOLUME.matcher(name);
        if(rarPart.matches()){
            if("1".equals(rarPart.group(2)))return null;   // part1 就是首卷，junrar 自己往下读
            File first=new File(parent,name.substring(0,rarPart.end(1))+".part1.rar");
            if(!first.isFile())throw new IOException("分卷不完整：缺少首卷 "+first.getName());
            return new Volume(first,first.getName(),null);
        }
        java.util.regex.Matcher rarSplit=RAR_SPLIT_VOLUME.matcher(name);
        if(rarSplit.matches()){
            String base=name.substring(0,rarSplit.end(1));
            File first=new File(parent,base+".rar");
            if(!first.isFile())first=new File(parent,base+".part1.rar");
            if(!first.isFile())throw new IOException("分卷不完整：缺少首卷 "+base+".rar");
            return new Volume(first,first.getName(),null);
        }
        return null;
    }

    private static boolean isSplitZipQuietly(File file){
        try{return isSplitZip(file);}catch(Exception|LinkageError ignored){return false;}
    }

    /** 分卷/多盘的英文报错换成能看懂的提示（zip4j、commons-compress 各说各的）。 */
    private static Exception explainVolumeFailure(Exception failure){
        String message=failure.getMessage();
        if(message!=null){
            String lower=message.toLowerCase(Locale.ROOT);
            if(lower.contains("too many disks"))
                return new IOException("这个 ZIP 的卷号写在包里，属于 zip4j 私自分卷：请按 .z01/.z02… 与 .zip 的原名放回同一目录再解压",failure);
            if(lower.contains("split archive")||lower.contains("split zip"))
                return new IOException("分卷不完整：请确认 .z01/.z02… 与最后一卷 .zip（或 .001/.002…）都在同一目录",failure);
        }
        return failure;
    }

    /** 数字分卷按序拼接；只有一卷时直接用那一卷（有人只是把普通包改名成 .001）。
        拼接临时文件放分卷所在目录（同一卷）：落 java.io.tmpdir 会跨卷，
        小存储设备上先写一份完整副本再解压等于双倍占用，容易中途写满。 */
    private static File assembleNumbered(File clicked,String base,String suffix,Progress progress)throws IOException{
        File dir=clicked.getParentFile();
        ArrayList<File> parts=new ArrayList<>();
        long total=0;
        for(int i=1;i<10000;i++){
            File part=new File(dir,String.format(Locale.ROOT,"%s.%03d",base,i));
            if(!part.isFile()){
                if(i==1)throw new IOException("找不到首卷 "+part.getName());
                break;
            }
            parts.add(part);total+=part.length();
        }
        if(parts.isEmpty())throw new IOException("分卷文件不存在");
        if(parts.size()==1)return parts.get(0);
        /* 拼接副本 ≈ total，再留 10% 余量；乘法先转 double 再比，避免 total*11 在超大分卷上溢出。 */
        long usable=dir.getUsableSpace();
        if(usable>0&&usable<(long)(total*1.1))
            throw new IOException("空间不足：拼接这组分卷需要约 "+((total+1048575)/1048576)+" MB 临时空间");
        File temp=File.createTempFile(".cjfm-vol-",suffix,dir);
        boolean complete=false;
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(temp),128*1024)){
            long written=0;
            byte[] buffer=new byte[128*1024];
            for(File part:parts){
                if(progress!=null&&progress.cancelled())throw new IOException("已取消");
                try(InputStream in=new BufferedInputStream(new FileInputStream(part),128*1024)){
                    int read;
                    while((read=in.read(buffer))!=-1){out.write(buffer,0,read);written+=read;}
                }
                if(progress!=null)progress.onProgress(part.getName(),written);
            }
            complete=true;
        }finally{
            if(!complete)temp.delete();
        }
        return temp;
    }

    private static InputStream open(File file)throws IOException{return new BufferedInputStream(new FileInputStream(file),128*1024);}

    /** 解压遇到同名文件时怎么办：覆盖／跳过这一条／整体中止（中止按「已取消」上报）。 */
    interface Conflict {
        int OVERWRITE=0,SKIP=1,ABORT=2;
        int resolve(File target);
        Conflict REPLACE=target->OVERWRITE;
    }
    /** One policy instance belongs to one archive job, called by its worker thread. */
    static Conflict rememberConflict(Conflict ask){
        return new Conflict(){private int answer=-1;public int resolve(File target){if(answer<0)answer=ask.resolve(target);return answer;}};
    }

    static void extract(File archive,File target,Progress progress)throws Exception{extract(archive,target,progress,Conflict.REPLACE);}
    static void extract(File archive,File target,Progress progress,Conflict conflict)throws Exception{
        extract(archive,target,progress,conflict,null,null);
    }
    static void extract(File archive,File target,Progress progress,Conflict conflict,String password)throws Exception{
        extract(archive,target,progress,conflict,password,null);
    }
    static void extract(File archive,File target,Progress progress,Conflict conflict,String password,Charset zipCharset)throws Exception{
        final Conflict policy=conflict==null?Conflict.REPLACE:conflict;
        if(!target.exists()&&!target.mkdirs())throw new IOException("无法创建目标目录："+target.getAbsolutePath());
        final Progress report=progress==null?Progress.NONE:progress;
        Volume volume=resolveVolume(archive,report);
        try{
            extractResolved(volume==null?archive:volume.file,volume==null?archive.getName():volume.name,
                    target,report,policy,password,zipCharset);
        }catch(Exception failure){
            throw explainVolumeFailure(failure);
        }finally{
            if(volume!=null)volume.close();
        }
    }

    /** 真正的解压分发：archive 是数据来源，name 决定格式（分卷拼接后两者不是同一个文件）。
        baselineFree 是本次解压开始时目标卷的可用空间，供 checkSpace 算真实新增占用。 */
    private static void extractResolved(File archive,String name,File target,Progress progress,Conflict policy,String password,Charset zipCharset)throws Exception{
        long baselineFree=target.getUsableSpace();
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".zip")||lower.endsWith(".jar")||lower.endsWith(".apk")||lower.endsWith(".apks")||lower.endsWith(".xapk")||lower.endsWith(".aab"))extractZip(archive,target,progress,policy,password,zipCharset,baselineFree);
        else if(lower.endsWith(".7z"))extract7z(archive,target,progress,policy,password,baselineFree);
        else if(lower.endsWith(".rar"))extractRar(archive,target,progress,policy,password,baselineFree);
        else if(lower.endsWith(".tar.gz")||lower.endsWith(".tgz"))extractTar(new GzipCompressorInputStream(open(archive),true),target,progress,policy,baselineFree);
        else if(lower.endsWith(".tar.bz2")||lower.endsWith(".tbz")||lower.endsWith(".tbz2"))extractTar(new BZip2CompressorInputStream(open(archive),true),target,progress,policy,baselineFree);
        else if(lower.endsWith(".tar.xz")||lower.endsWith(".txz"))extractTar(new XZCompressorInputStream(open(archive),true),target,progress,policy,baselineFree);
        else if(lower.endsWith(".tar.lzma")||lower.endsWith(".tlz"))extractTar(new LZMACompressorInputStream(open(archive)),target,progress,policy,baselineFree);
        else if(lower.endsWith(".tar"))extractTar(open(archive),target,progress,policy,baselineFree);
        else if(lower.endsWith(".gz"))extractSingle(new GzipCompressorInputStream(open(archive),true),new File(target,stripCompressed(name)),progress,policy,baselineFree);
        else if(lower.endsWith(".bz2"))extractSingle(new BZip2CompressorInputStream(open(archive),true),new File(target,stripCompressed(name)),progress,policy,baselineFree);
        else if(lower.endsWith(".xz"))extractSingle(new XZCompressorInputStream(open(archive),true),new File(target,stripCompressed(name)),progress,policy,baselineFree);
        else if(lower.endsWith(".lzma"))extractSingle(new LZMACompressorInputStream(open(archive)),new File(target,stripCompressed(name)),progress,policy,baselineFree);
        else throw new IOException("暂不支持解压 ."+ext(name)+" 格式");
    }

    /**
     * 落盘一个条目。目标已存在时先问策略：覆盖走 writeReplacing（仍然先写临时文件），跳过则整条不写。
     * 以前这里一律调 writeNew，撞上同名文件会抛「目标已有同名文件」把**整个解压**中断在半路。
     */
    private static void writeEntry(File out,Conflict conflict,SafeFileOperations.Writer writer)throws Exception{
        writeEntry(out,conflict,writer,null);
    }
    /**
     * drain 非空时，即使这一条被「跳过」也要调用它把数据消费掉。
     * RAR 的 solid 归档里多个文件共用一个压缩流，跳过某个条目而不解码它，
     * 解码器状态就会错位 —— 后面的文件会解出一堆垃圾，而且界面上还显示「解压成功」。
     */
    /** 条目被跳过时的数据消费动作（会抛受检异常，所以不能用 Runnable）。 */
    private interface Drain { void drain()throws Exception; }
    private static void writeEntry(File out,Conflict conflict,SafeFileOperations.Writer writer,Drain drain)throws Exception{
        if(out.exists()){
            int decision=conflict.resolve(out);
            if(decision==Conflict.ABORT)throw new IOException("已取消");
            if(decision==Conflict.SKIP){if(drain!=null)drain.drain();return;}
            SafeFileOperations.writeReplacing(out,writer);
            return;
        }
        SafeFileOperations.writeNew(out,writer);
    }

    /**
     * 目标路径必须落在 root 内，否则视为恶意压缩包。
     * 条目名净化后为空（整条都是控制字符等）时返回 null，调用方应跳过该条目。
     */
    private static File safe(File root,String entryName)throws IOException{
        String clean=sanitize(entryName);
        if(clean==null)return null;
        File out=new File(root,clean);String base=root.getCanonicalPath(),path=out.getCanonicalPath();
        if(!path.equals(base)&&!path.startsWith(base+File.separator))throw new IOException("压缩包内路径越界："+entryName);
        return out;
    }

    /**
     * 净化归档内条目名。
     * 丢掉控制字符（Android 的 FUSE 会以 EPERM 拒绝写入含 \n \t 等字符的文件名，
     * 曾经遇到条目名结尾带换行导致整个解压失败），统一分隔符为 '/'，折叠重复斜杠，
     * 去掉每段结尾的空格与句点（Windows/SMB 上的非法名），丢弃前导与尾随斜杠。
     */
    /** Windows 盘符绝对路径（C:\... 或 C:/...）：每条目都编译一次正则太浪费，用字符判断。 */
    private static boolean windowsAbsolute(String entryName){
        return entryName.length()>=2&&Character.isLetter(entryName.charAt(0))&&entryName.charAt(1)==':';
    }
    private static String sanitize(String entryName)throws IOException{
        if(entryName==null)return null;
        if(entryName.startsWith("/")||entryName.startsWith("\\")||windowsAbsolute(entryName))throw new IOException("压缩包内绝对路径："+entryName);
        StringBuilder cleaned=new StringBuilder(entryName.length());
        for(int i=0;i<entryName.length();i++){
            char c=entryName.charAt(i);
            if(c<0x20||c==0x7f)continue;
            cleaned.append(c=='\\'?'/':c);
        }
        StringBuilder out=new StringBuilder(cleaned.length());
        for(String segment:cleaned.toString().split("/")){
            if(segment.trim().equals(".."))throw new IOException("压缩包内路径越界："+entryName);
            if(segment.equals("."))continue;
            int end=segment.length();
            while(end>0&&(segment.charAt(end-1)==' '||segment.charAt(end-1)=='.'))end--;
            segment=segment.substring(0,end);
            if(segment.isEmpty())continue;
            if(out.length()>0)out.append('/');
            out.append(segment);
        }
        return out.length()==0?null:out.toString();
    }

    private static void extractZip(File archive,File target,Progress progress,Conflict conflict,String password,Charset zipCharset,long baselineFree)throws Exception{
        boolean useZip4j=password!=null&&!password.isEmpty();
        if(!useZip4j){
            /* zip4j 自己的分卷格式（x.z01/x.z02/x.zip）只有它认得：commons-compress 读到
               x.zip 里的中央目录后会发现数据都在别的卷上，报错退出。 */
            try{useZip4j=isSplitZip(archive);}catch(Exception|LinkageError ignored){/* 读不出头就当普通 zip */}
        }
        if(useZip4j){
            extractZip4j(archive,target,progress,conflict,password,zipCharset,baselineFree);
            return;
        }
        try(ZipFile zip=ZipFile.builder().setFile(archive).setCharset(zipCharset==null?StandardCharsets.UTF_8:zipCharset).get()){
            Enumeration<ZipArchiveEntry> entries=zip.getEntries();long written=0;
            while(entries.hasMoreElements()){
                if(progress.cancelled())throw new IOException("已取消");
                ZipArchiveEntry entry=entries.nextElement();File out=safe(target,entry.getName());
                if(out==null)continue;
                if(entry.isDirectory()){SafeFileOperations.directory(out);continue;}
                final long before=written;final long[] total={written};
                writeEntry(out,conflict,stream->{try(InputStream in=zip.getInputStream(entry)){total[0]=copy(in,stream,progress,entry.getName(),before,out,baselineFree);}checkCancelled(progress);});
                written=total[0];checkSpace(out,written,baselineFree);
            }
        }
    }

    /** 是否 zip4j 分卷（末尾那卷 .zip 会被 isSplitArchive 标记）。 */
    private static boolean isSplitZip(File archive)throws Exception{
        try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(archive)){return zip.isSplitArchive();}
    }

    private static void extractZip4j(File archive,File target,Progress progress,Conflict conflict,String password,Charset zipCharset,long baselineFree)throws Exception{
        char[] pwd=password==null?null:password.toCharArray();
        try(net.lingala.zip4j.ZipFile zip=pwd==null?new net.lingala.zip4j.ZipFile(archive):new net.lingala.zip4j.ZipFile(archive,pwd)){
            if(zipCharset!=null)zip.setCharset(zipCharset);
            if(zip.isEncrypted()&&pwd==null)throw new IOException("该 ZIP 已加密，需要密码");
            @SuppressWarnings("unchecked")
            List<net.lingala.zip4j.model.FileHeader> headers=zip.getFileHeaders();
            long written=0;
            for(net.lingala.zip4j.model.FileHeader header:headers){
                if(progress.cancelled())throw new IOException("已取消");
                String entryName=header.getFileName();
                if(entryName==null)continue;
                File out=safe(target,entryName);
                if(out==null)continue;
                if(header.isDirectory()){SafeFileOperations.directory(out);continue;}
                final long before=written;final long[] total={written};
                writeEntry(out,conflict,stream->{try(InputStream in=zip.getInputStream(header)){total[0]=copy(in,stream,progress,entryName,before,out,baselineFree);}checkCancelled(progress);});
                written=total[0];checkSpace(out,written,baselineFree);
            }
        }
    }

    private static void extract7z(File archive,File target,Progress progress,Conflict conflict,String password,long baselineFree)throws Exception{
        SevenZFile.Builder builder=SevenZFile.builder().setFile(archive);
        if(password!=null&&!password.isEmpty())builder.setPassword(password);
        try(SevenZFile seven=builder.get()){
            SevenZArchiveEntry entry;byte[] buffer=new byte[128*1024];long written=0;
            while((entry=seven.getNextEntry())!=null){
                if(progress.cancelled())throw new IOException("已取消");
                File out=safe(target,entry.getName());
                if(out==null)continue;
                if(entry.isDirectory()){SafeFileOperations.directory(out);continue;}
                final SevenZArchiveEntry current=entry;final long[] total={written};
                writeEntry(out,conflict,stream->{int n;long sinceCheck=0;while(true){checkCancelled(progress);n=seven.read(buffer,0,buffer.length);if(n==-1)break;stream.write(buffer,0,n);total[0]+=n;progress.onProgress(current.getName(),total[0]);sinceCheck+=n;if(sinceCheck>=8*1024*1024){sinceCheck=0;checkSpace(out,total[0],baselineFree);}}checkCancelled(progress);});
                written=total[0];checkSpace(out,written,baselineFree);
            }
        }catch(org.apache.commons.compress.PasswordRequiredException required){throw new IOException("此 7z 压缩包需要密码",required);}
    }

    private static void extractRar(File archive,File target,Progress progress,Conflict conflict,String password,long baselineFree)throws Exception{
        /* junrar 抛的都是自己的异常类型，消息也未必可读；统一转成中文，别让用户看到
           "UnsupportedRarVersionException" 这种东西。CRC 校验 junrar 默认是开的，
           所以走到 CrcErrorException 只有两种可能：文件本身损坏，或密码不对。
           8.x 起 new Archive() 构造器对 UnsupportedRarEncrypted/UnsupportedRarVersion/
           CorruptHeader/BadRarArchive/WrongPassword 这五类会原样重抛，其余异常被
           recordHeaderFailure 吞掉让归档"打开成功"、提取幸存条目——所以 catch 链
           要显式接住 WrongPasswordException（否则密码错会被 catch(RarException) 包成
           "RAR 解压失败：…" 而不是"密码不正确"），并配合 hasBrokenHeaders 检查
           吞掉式失败。catch(RuntimeException) 里的 "exception in archive constructor"
           兜底在 8.x 下已失灵（该字符串只剩日志文本），保留以防降级到 7.x 时仍可工作。 */
        try{extractRarBody(archive,target,progress,conflict,password,baselineFree);}
        catch(com.github.junrar.exception.UnsupportedRarVersionException e){throw new IOException("暂不支持该 RAR 版本的压缩包（请用 WinRAR 5 以上重新打包）");}
        catch(com.github.junrar.exception.WrongPasswordException e){throw new IOException("RAR 解压失败：密码不正确",e);}
        catch(com.github.junrar.exception.MissingNextVolumeException e){throw new IOException("分卷不完整：缺少后续分卷，无法继续解压",e);}
        catch(com.github.junrar.exception.UnsafeLinkException e){throw new IOException("RAR 链接条目不安全，已拒绝解压："+(e.getMessage()==null?"":e.getMessage()),e);}
        catch(com.github.junrar.exception.CrcErrorException e){throw new IOException("RAR 校验失败：压缩包已损坏或密码不正确");}
        catch(com.github.junrar.exception.RarException e){throw new IOException("RAR 解压失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
        catch(RuntimeException e){
            String message=e.getMessage()==null?"":e.getMessage();
            if(message.contains("exception in archive constructor"))
                throw new IOException("RAR 解压失败：压缩包已损坏或密码不正确",e);
            throw e;
        }
    }
    private static void extractRarBody(File archive,File target,Progress progress,Conflict conflict,String password,long baselineFree)throws Exception{
        try(Archive rar=password==null||password.isEmpty()?new Archive(archive):new Archive(archive,password)){
            /* 8.x 构造器会把部分坏包异常吞掉让归档"打开成功"（getFileHeaders 只剩幸存条目）。
               这里显式检查：有坏头且一条条目都没读出来，说明包基本废了，别假装成功产出空目录。 */
            if(rar.hasBrokenHeaders()&&rar.getFileHeaders().isEmpty())
                throw new IOException("RAR 解压失败：压缩包已损坏或格式无法识别");
            long written=0;
            for(FileHeader header:rar.getFileHeaders()){
                if(progress.cancelled())throw new IOException("已取消");
                /* RAR5 链接/副本条目：junrar 8.x 有 getRedirection()，但本项目不落链接文件，
                   与 TAR 的「暂不支持链接或特殊条目」口径一致，显式跳过而不是把链接目标当文件内容写出来。 */
                Rar5Redirection redirect=header.getRedirection();
                if(redirect!=null)continue;
                File out=safe(target,header.getFileName());
                if(out==null)continue;
                if(header.isDirectory()){SafeFileOperations.directory(out);continue;}
                final FileHeader current=header;final long before=written;final long[] total={written};
                writeEntry(out,conflict,
                    stream->{CountingOutputStream counter=new CountingOutputStream(stream,progress,out.getName(),before,out,baselineFree);rar.extractFile(current,counter);checkCancelled(progress);total[0]=before+counter.count;},
                    ()->rar.extractFile(current,new DiscardOutputStream(progress)));
                written=total[0];checkSpace(out,written,baselineFree);
            }
        }
    }

    private static void extractTar(InputStream raw,File target,Progress progress,Conflict conflict,long baselineFree)throws Exception{
        try(TarArchiveInputStream tar=new TarArchiveInputStream(raw)){
            TarArchiveEntry entry;long written=0;
            while((entry=tar.getNextEntry())!=null){
                if(progress.cancelled())throw new IOException("已取消");
                File out=safe(target,entry.getName());
                if(out==null)continue;
                if(entry.isDirectory()){SafeFileOperations.directory(out);continue;}
                if(entry.isSymbolicLink()||entry.isLink()||!entry.isFile())throw new IOException("暂不支持 TAR 链接或特殊条目："+entry.getName());
                final String entryName=entry.getName();final long before=written;final long[] total={written};
                writeEntry(out,conflict,stream->{total[0]=copy(tar,stream,progress,entryName,before,out,baselineFree);checkCancelled(progress);});
                written=total[0];checkSpace(out,written,baselineFree);
            }
        }
    }

    private static void extractSingle(InputStream raw,File out,Progress progress,Conflict conflict,long baselineFree)throws Exception{
        try(InputStream in=raw){
            writeEntry(out,conflict,stream->{copy(in,stream,progress,out.getName(),0,out,baselineFree);checkCancelled(progress);});
        }
    }

    private static long copy(InputStream in,OutputStream out,Progress progress,String name,long written,File target,long baselineFree)throws IOException{
        byte[] buffer=new byte[128*1024];int n;long sinceCheck=0;
        while((n=in.read(buffer))!=-1){
            if(progress.cancelled())throw new IOException("已取消");
            out.write(buffer,0,n);written+=n;progress.onProgress(name,written);
            /* 每 8MB 对一次账：zip 炸弹（小包声明解出几百 GB）会在用户毫无感知时
               把存储写满，之后连拍照截图都存不下。按目标卷实时可用空间刹车。 */
            sinceCheck+=n;
            if(sinceCheck>=8*1024*1024){sinceCheck=0;checkSpace(target,written,baselineFree);}
        }
        checkSpace(target,written,baselineFree);
        return written;
    }
    /** 解压配额：本次新写入的字节超过目标卷实时可用空间就中止。
        注意 written 是「本次解压累计新写」而不是文件总长——以前拿累计值直接和可用空间比，
        目录里已有同名被覆盖的旧文件时会把旧体积也算进占用，快满的盘上可能误杀正常解压；
        真正的新增占用是「解压前后可用空间之差」，拿它跟新写入比才对得上。
        不设固定上限，大磁盘解大包不受影响；usable 取不到（<=0）时不拦，避免误伤。 */
    private static void checkSpace(File target,long written,long baselineFree)throws IOException{
        if(written<=0)return;
        long usable=target.getUsableSpace();
        if(usable<=0)return;
        long used=baselineFree>0?Math.max(0,baselineFree-usable):written;
        if(written>usable+used)
            throw new IOException("存储空间不足，已停止解压（已写出 "+sizeText(written)+"，卷剩余 "+sizeText(usable)+"）");
    }
    private static String sizeText(long bytes){
        if(bytes<1024)return bytes+" B";
        if(bytes<1024*1024)return String.format(java.util.Locale.ROOT,"%.1f KB",bytes/1024.0);
        if(bytes<1024L*1024*1024)return String.format(java.util.Locale.ROOT,"%.1f MB",bytes/1048576.0);
        return String.format(java.util.Locale.ROOT,"%.2f GB",bytes/1073741824.0);
    }
    private static void checkCancelled(Progress progress)throws IOException {
        if(progress.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("已取消");
    }

    /** zip 家族：commons-compress 与 zip4j 走的是同一批后缀。 */
    private static boolean isZipName(String lower){
        return lower.endsWith(".zip")||lower.endsWith(".jar")||lower.endsWith(".apk")||lower.endsWith(".apks")||lower.endsWith(".xapk")||lower.endsWith(".aab");
    }
    private static long drain(InputStream in,Progress progress)throws IOException{
        byte[] buffer=new byte[128*1024];int n;long total=0;
        while((n=in.read(buffer))!=-1){total+=n;checkCancelled(progress);}
        return total;
    }

    /* ---------- 只读工具：条目一览 / 完整性校验 / 是否加密 ---------- */

    /** 归档内的一条记录（只取元信息，不读内容）。size 取不到时为 -1。 */
    static final class Item{
        final String name;final long size;final boolean dir;
        Item(String name,long size,boolean dir){this.name=name;this.size=size;this.dir=dir;}
    }

    /**
     * 是否必须给密码才能解压：只看条目标志，不解密内容，所以很快。
     * 7Z 判断不了（它的加密信息藏在编码链里，要读到第一个条目才知道），一律返回 false ——
     * 那种情况由解压时的异常兜底，界面会补一次「请输入密码」。
     */
    static boolean needsPassword(File archive){
        Volume volume=null;
        try{
            volume=resolveVolume(archive,Progress.NONE);
            File source=volume==null?archive:volume.file;
            String name=(volume==null?archive.getName():volume.name).toLowerCase(Locale.ROOT);
            if(isZipName(name)){try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(source)){return zip.isEncrypted();}}
            if(name.endsWith(".rar")){
                try(Archive rar=new Archive(source)){
                    /* RAR5：主头加密（整包加密）时 Archive.isEncrypted() 为 true；
                       否则逐条看条目标志（RAR5 单条加密也走这里）。 */
                    try{if(rar.isEncrypted())return true;}catch(Exception ignored){/* 8.x isEncrypted 也可能抛，交给下面逐条判断 */}
                    for(FileHeader header:rar.getFileHeaders())if(header.isEncrypted())return true;
                }
                return false;
            }
        }catch(Exception|LinkageError ignored){/* 打不开就当不需要密码，交给解压时的真实错误说话 */}
        finally{if(volume!=null)volume.close();}
        return false;
    }

    /** 列出归档内的条目：不解压、不校验内容。加密归档必须给对密码，否则连头部／内容都读不出来。 */
    static List<Item> list(File archive,String password,Charset charset)throws Exception{
        Volume volume=resolveVolume(archive,Progress.NONE);
        try{
            return listResolved(volume==null?archive:volume.file,volume==null?archive.getName():volume.name,password,charset);
        }catch(Exception failure){
            throw explainVolumeFailure(failure);
        }finally{
            if(volume!=null)volume.close();
        }
    }
    private static List<Item> listResolved(File archive,String entryName,String password,Charset charset)throws Exception{
        final List<Item> out=new ArrayList<>();
        final String name=entryName.toLowerCase(Locale.ROOT);
        final boolean hasPassword=password!=null&&!password.isEmpty();
        if(isZipName(name)){
            /* 分卷只有 zip4j 认：commons-compress 会报 “Too many disks for zip archive”。 */
            if(hasPassword||isSplitZipQuietly(archive)||needsPassword(archive)){
                char[] pwd=hasPassword?password.toCharArray():null;
                try(net.lingala.zip4j.ZipFile zip=pwd==null?new net.lingala.zip4j.ZipFile(archive):new net.lingala.zip4j.ZipFile(archive,pwd)){
                    if(charset!=null)zip.setCharset(charset);
                    for(net.lingala.zip4j.model.FileHeader header:zip.getFileHeaders())
                        out.add(new Item(header.getFileName(),header.getUncompressedSize(),header.isDirectory()));
                }
                return out;
            }
            try(ZipFile zip=ZipFile.builder().setFile(archive).setCharset(charset==null?StandardCharsets.UTF_8:charset).get()){
                Enumeration<ZipArchiveEntry> entries=zip.getEntries();
                while(entries.hasMoreElements()){ZipArchiveEntry entry=entries.nextElement();out.add(new Item(entry.getName(),entry.getSize(),entry.isDirectory()));}
            }
            return out;
        }
        if(name.endsWith(".7z")){
            SevenZFile.Builder builder=SevenZFile.builder().setFile(archive);
            if(hasPassword)builder.setPassword(password);
            try(SevenZFile seven=builder.get()){
                SevenZArchiveEntry entry;
                while((entry=seven.getNextEntry())!=null)out.add(new Item(entry.getName(),entry.getSize(),entry.isDirectory()));
            }catch(org.apache.commons.compress.PasswordRequiredException required){throw new IOException("此 7z 压缩包需要密码",required);}
            return out;
        }
        if(name.endsWith(".rar")){
            try(Archive rar=hasPassword?new Archive(archive,password):new Archive(archive)){
                /* 链接/副本条目不算「文件内容」，与解压口径一致：跳过不列，免得用户以为能解开成文件。 */
                for(FileHeader header:rar.getFileHeaders()){
                    if(header.getRedirection()!=null)continue;
                    out.add(new Item(header.getFileName(),header.getFullUnpackSize(),header.isDirectory()));
                }
            }
            return out;
        }
        if(name.endsWith(".tar.gz")||name.endsWith(".tgz"))return listTar(new GzipCompressorInputStream(open(archive),true));
        if(name.endsWith(".tar.bz2")||name.endsWith(".tbz")||name.endsWith(".tbz2"))return listTar(new BZip2CompressorInputStream(open(archive),true));
        if(name.endsWith(".tar.xz")||name.endsWith(".txz"))return listTar(new XZCompressorInputStream(open(archive),true));
        if(name.endsWith(".tar.lzma")||name.endsWith(".tlz"))return listTar(new LZMACompressorInputStream(open(archive)));
        if(name.endsWith(".tar"))return listTar(open(archive));
        if(name.endsWith(".gz")||name.endsWith(".bz2")||name.endsWith(".xz")||name.endsWith(".lzma")){
            List<Item> single=new ArrayList<>();
            single.add(new Item(stripCompressed(entryName),-1,false));
            return single;
        }
        throw new IOException("暂不支持查看 ."+ext(entryName)+" 格式");
    }
    private static List<Item> listTar(InputStream raw)throws IOException{
        List<Item> out=new ArrayList<>();
        try(TarArchiveInputStream tar=new TarArchiveInputStream(raw)){
            TarArchiveEntry entry;
            while((entry=tar.getNextEntry())!=null)out.add(new Item(entry.getName(),entry.getSize(),entry.isDirectory()));
        }
        return out;
    }

    /**
     * 完整性校验：把每个条目读一遍再丢掉，让各库自带的 CRC 校验说话。
     * 注意这里的失败语义：加密归档在密码不对时也是同一类错误（解密出的字节 CRC 不对），
     * 所以报错文案统一写成「压缩包已损坏或密码不正确」，不要替用户下结论。
     */
    static void verify(File archive,Progress progress,String password,Charset charset)throws Exception{
        final Progress report=progress==null?Progress.NONE:progress;
        Volume volume=resolveVolume(archive,report);
        try{
            verifyResolved(volume==null?archive:volume.file,volume==null?archive.getName():volume.name,report,password,charset);
        }catch(Exception failure){
            throw explainVolumeFailure(failure);
        }finally{
            if(volume!=null)volume.close();
        }
    }
    private static void verifyResolved(File archive,String entryName,Progress report,String password,Charset charset)throws Exception{
        final String name=entryName.toLowerCase(Locale.ROOT);
        final boolean hasPassword=password!=null&&!password.isEmpty();
        if(isZipName(name)){
            if(hasPassword||needsPassword(archive)){
                char[] pwd=hasPassword?password.toCharArray():null;
                try(net.lingala.zip4j.ZipFile zip=pwd==null?new net.lingala.zip4j.ZipFile(archive):new net.lingala.zip4j.ZipFile(archive,pwd)){
                    if(charset!=null)zip.setCharset(charset);
                    for(net.lingala.zip4j.model.FileHeader header:zip.getFileHeaders()){
                        checkCancelled(report);
                        if(header.isDirectory())continue;
                        report.onProgress(header.getFileName(),0);
                        try(InputStream in=zip.getInputStream(header)){drain(in,report);}
                    }
                }
                return;
            }
            if(isSplitZip(archive)){
                /* 无密码也无加密标记，但可能是 zip4j 分卷：commons-compress 读不了，改用 zip4j。 */
                extractZip4jVerify(archive,report,charset);
                return;
            }
            try(ZipFile zip=ZipFile.builder().setFile(archive).setCharset(charset==null?StandardCharsets.UTF_8:charset).get()){
                Enumeration<ZipArchiveEntry> entries=zip.getEntries();
                while(entries.hasMoreElements()){
                    ZipArchiveEntry entry=entries.nextElement();
                    checkCancelled(report);
                    if(entry.isDirectory())continue;
                    report.onProgress(entry.getName(),0);
                    try(InputStream in=zip.getInputStream(entry)){drain(in,report);}
                }
            }
            return;
        }
        if(name.endsWith(".7z")){
            SevenZFile.Builder builder=SevenZFile.builder().setFile(archive);
            if(hasPassword)builder.setPassword(password);
            try(SevenZFile seven=builder.get()){
                SevenZArchiveEntry entry;byte[] buffer=new byte[128*1024];
                while((entry=seven.getNextEntry())!=null){
                    checkCancelled(report);
                    if(entry.isDirectory())continue;
                    report.onProgress(entry.getName(),0);
                    while(seven.read(buffer,0,buffer.length)!=-1)checkCancelled(report);
                }
            }catch(org.apache.commons.compress.PasswordRequiredException required){throw new IOException("此 7z 压缩包需要密码",required);}
            return;
        }
        if(name.endsWith(".rar")){
            verifyRar(archive,report,hasPassword?password:null);
            return;
        }
        if(name.endsWith(".tar.gz")||name.endsWith(".tgz"))verifyTar(new GzipCompressorInputStream(open(archive),true),report);
        else if(name.endsWith(".tar.bz2")||name.endsWith(".tbz")||name.endsWith(".tbz2"))verifyTar(new BZip2CompressorInputStream(open(archive),true),report);
        else if(name.endsWith(".tar.xz")||name.endsWith(".txz"))verifyTar(new XZCompressorInputStream(open(archive),true),report);
        else if(name.endsWith(".tar.lzma")||name.endsWith(".tlz"))verifyTar(new LZMACompressorInputStream(open(archive)),report);
        else if(name.endsWith(".tar"))verifyTar(open(archive),report);
        else if(name.endsWith(".gz"))verifySingle(new GzipCompressorInputStream(open(archive),true),report);
        else if(name.endsWith(".bz2"))verifySingle(new BZip2CompressorInputStream(open(archive),true),report);
        else if(name.endsWith(".xz"))verifySingle(new XZCompressorInputStream(open(archive),true),report);
        else if(name.endsWith(".lzma"))verifySingle(new LZMACompressorInputStream(open(archive)),report);
        else throw new IOException("暂不支持校验 ."+ext(entryName)+" 格式");
    }
    /** 无密码校验 zip4j 分卷：commons-compress 只能看到最后一卷里的中央目录，读数据会失败。 */
    private static void extractZip4jVerify(File archive,Progress report,Charset charset)throws Exception{
        try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(archive)){
            if(charset!=null)zip.setCharset(charset);
            for(net.lingala.zip4j.model.FileHeader header:zip.getFileHeaders()){
                checkCancelled(report);
                if(header.isDirectory())continue;
                report.onProgress(header.getFileName(),0);
                try(InputStream in=zip.getInputStream(header)){drain(in,report);}
            }
        }
    }
    /** junrar 的异常一律转中文（与解压路径同一套口径）。 */
    private static void verifyRar(File archive,Progress progress,String password)throws Exception{
        try(Archive rar=password==null?new Archive(archive):new Archive(archive,password)){
            if(rar.hasBrokenHeaders()&&rar.getFileHeaders().isEmpty())
                throw new IOException("RAR 校验失败：压缩包已损坏或格式无法识别");
            for(FileHeader header:rar.getFileHeaders()){
                checkCancelled(progress);
                if(header.isDirectory())continue;
                if(header.getRedirection()!=null)continue;
                progress.onProgress(header.getFileName(),0);
                rar.extractFile(header,new DiscardOutputStream(progress));
            }
        }
        catch(com.github.junrar.exception.UnsupportedRarVersionException e){throw new IOException("暂不支持该 RAR 版本的压缩包（请用 WinRAR 5 以上重新打包）");}
        catch(com.github.junrar.exception.WrongPasswordException e){throw new IOException("RAR 校验失败：密码不正确",e);}
        catch(com.github.junrar.exception.MissingNextVolumeException e){throw new IOException("分卷不完整：缺少后续分卷，无法继续校验",e);}
        catch(com.github.junrar.exception.UnsafeLinkException e){throw new IOException("RAR 链接条目不安全，已拒绝校验："+(e.getMessage()==null?"":e.getMessage()),e);}
        catch(com.github.junrar.exception.CrcErrorException e){throw new IOException("RAR 校验失败：压缩包已损坏或密码不正确");}
        catch(com.github.junrar.exception.RarException e){throw new IOException("RAR 校验失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
        catch(RuntimeException e){
            String message=e.getMessage()==null?"":e.getMessage();
            if(message.contains("exception in archive constructor"))
                throw new IOException("RAR 校验失败：压缩包已损坏或密码不正确",e);
            throw e;
        }
    }
    private static void verifyTar(InputStream raw,Progress progress)throws IOException{
        try(TarArchiveInputStream tar=new TarArchiveInputStream(raw)){
            TarArchiveEntry entry;
            while((entry=tar.getNextEntry())!=null){
                checkCancelled(progress);
                if(entry.isDirectory())continue;
                progress.onProgress(entry.getName(),0);
                drain(tar,progress);
            }
        }
    }
    private static void verifySingle(InputStream raw,Progress progress)throws IOException{
        try(InputStream in=raw){drain(in,progress);}
    }

    /** 支持的压缩格式名。 */
    static final Set<String> CREATE_FORMATS=new HashSet<>(Arrays.asList("zip","7z","tar","tar.gz","tar.bz2","tar.xz"));

    /**
     * 递归压缩，format 为 zip/7z/tar/tar.gz/tar.bz2/tar.xz。
     * **一律先写临时文件、成功后再落位**：压缩过程中取消或抛异常时，zip4j / SevenZOutputFile /
     * TarArchiveOutputStream 的 close() 照样会写出 central directory / 7z header / gzip trailer，
     * 于是目标位置会留下一个「能被打开、条目也齐全、但数据残缺」的归档 —— 界面却按成功收尾。
     * 走临时文件后，没跑完的目标文件根本不会出现。
     */
    static void createArchive(List<File> inputs,File output,String format,Progress progress,String password)throws Exception{
        if(!CREATE_FORMATS.contains(format))throw new IOException("不支持的压缩格式："+format);
        final Progress reporter=progress==null?Progress.NONE:progress;
        File parent=output.getAbsoluteFile().getParentFile();
        if(parent==null)throw new IOException("无法确定目标目录");
        if(!parent.exists()&&!parent.mkdirs())throw new IOException("无法创建目标目录");
        File temporary=File.createTempFile(".cjfm-",".tmp",parent);
        boolean committed=false;
        try{
            if("zip".equals(format)){
                if(password!=null&&!password.isEmpty())createZip4j(inputs,temporary,password,reporter);
                else createZip(inputs,temporary,reporter);
            }else if("7z".equals(format)){
                create7z(inputs,temporary,password,reporter);
            }else if("tar".equals(format)){
                createTar(inputs,temporary,null,reporter);
            }else if("tar.gz".equals(format)){
                createTar(inputs,temporary,new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(temporary),128*1024)),reporter);
            }else if("tar.bz2".equals(format)){
                createTar(inputs,temporary,new BZip2CompressorOutputStream(new BufferedOutputStream(new FileOutputStream(temporary),128*1024)),reporter);
            }else if("tar.xz".equals(format)){
                createTar(inputs,temporary,new XZCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(temporary),128*1024)),reporter);
            }
            checkCancelled(reporter);
            SafeFileOperations.replace(temporary,output);committed=true;
        }finally{if(!committed)temporary.delete();}
    }

    /** 递归压缩为 zip（目录会保留层级）。调用方已经保证 output 是临时文件。 */
    static void createZip(List<File> inputs,File output,Progress progress)throws Exception{
        final Progress reporter=progress==null?Progress.NONE:progress;
        File parent=output.getAbsoluteFile().getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("无法创建目标目录");
        final long[] written={0};
        try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(output),128*1024);
            ZipArchiveOutputStream zip=new ZipArchiveOutputStream(stream)){
            zip.setUseZip64(Zip64Mode.AsNeeded);
            for(File input:inputs)add(zip,input,input.getName(),reporter,written);
            checkCancelled(reporter);zip.finish();
        }
    }

    private static void createZip4j(List<File> inputs,File output,String password,Progress progress)throws Exception{
        char[] pwd=password.toCharArray();
        try(net.lingala.zip4j.ZipFile zip=new net.lingala.zip4j.ZipFile(output,pwd)){
            zip.setCharset(StandardCharsets.UTF_8);
            for(File input:inputs){
                /* 每个条目都要一份新的 ZipParameters：zip4j 的 addFolder() 会把
                   defaultFolderPath 改成「目录的父目录」，复用同一个 params 时后面的 addFile()
                   会按这个根算相对名 —— 目录里的文件会被改名成「目录名/文件名」，与目录里已存在的
                   同名条目互相覆盖，归档静默少内容；目录外的文件则可能退化成绝对路径。 */
                ZipParameters params=new ZipParameters();
                checkCancelled(progress);
                progress.onProgress(input.getName(),-1);
                params.setEncryptFiles(true);
                params.setEncryptionMethod(EncryptionMethod.AES);
                params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                params.setCompressionMethod(CompressionMethod.DEFLATE);
                params.setCompressionLevel(CompressionLevel.NORMAL);
                if(input.isDirectory())zip.addFolder(input,params);
                else zip.addFile(input,params);
                progress.onProgress(input.getName(),-1);
                checkCancelled(progress);
            }
        }
    }

    private static void create7z(List<File> inputs,File output,String password,Progress progress)throws Exception{
        char[] pwd=(password==null||password.isEmpty())?null:password.toCharArray();
        try(SevenZOutputFile seven=pwd==null?new SevenZOutputFile(output):new SevenZOutputFile(output,pwd)){
            for(File input:inputs){
                if(input.isDirectory())add7zDir(seven,input,input.getName(),progress);
                else add7zFile(seven,input,input.getName(),progress);
                checkCancelled(progress);
            }
        }
    }
    private static void add7zFile(SevenZOutputFile seven,File file,String entryName,Progress progress)throws Exception{
        SevenZArchiveEntry entry=seven.createArchiveEntry(file,entryName);
        seven.putArchiveEntry(entry);
        try(InputStream in=open(file)){
            byte[] buffer=new byte[128*1024];int n;long written=0;
            while((n=in.read(buffer))!=-1){
                if(progress.cancelled())throw new IOException("已取消");
                seven.write(buffer,0,n);written+=n;progress.onProgress(entryName,written);
            }
        }
        seven.closeArchiveEntry();
    }
    private static void add7zDir(SevenZOutputFile seven,File dir,String entryName,Progress progress)throws Exception{
        String name=entryName.endsWith("/")?entryName:entryName+"/";
        SevenZArchiveEntry entry=new SevenZArchiveEntry();entry.setName(name);entry.setDirectory(true);entry.setLastModifiedDate(new java.util.Date(dir.lastModified()));
        seven.putArchiveEntry(entry);seven.closeArchiveEntry();
        File[] kids=dir.listFiles();
        if(kids==null)throw new IOException("无法读取目录："+dir.getAbsolutePath());
        Arrays.sort(kids,(a,b)->a.getName().compareToIgnoreCase(b.getName()));
        for(File kid:kids){
            if(kid.isDirectory())add7zDir(seven,kid,name+kid.getName(),progress);
            else add7zFile(seven,kid,name+kid.getName(),progress);
        }
    }

    private static void createTar(List<File> inputs,File output,OutputStream outer,Progress progress)throws Exception{
        boolean ownStream=outer==null;
        OutputStream raw=ownStream?new BufferedOutputStream(new FileOutputStream(output),128*1024):outer;
        try(TarArchiveOutputStream tar=new TarArchiveOutputStream(raw)){
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            final long[] written={0};
            for(File input:inputs)add(tar,input,input.getName(),progress,written);
            checkCancelled(progress);tar.finish();
        }
    }

    private static void add(ZipArchiveOutputStream zip,File file,String entryName,Progress progress,long[] written)throws Exception{
        if(progress.cancelled())throw new IOException("已取消");
        if(file.isDirectory()){
            String dir=entryName.endsWith("/")?entryName:entryName+"/";
            ZipArchiveEntry entry=new ZipArchiveEntry(dir);entry.setTime(file.lastModified());zip.putArchiveEntry(entry);zip.closeArchiveEntry();
            File[] kids=file.listFiles();
            if(kids==null)throw new IOException("无法读取目录："+file.getAbsolutePath());
            Arrays.sort(kids,(a,b)->a.getName().compareToIgnoreCase(b.getName()));for(File kid:kids)add(zip,kid,dir+kid.getName(),progress,written);
            return;
        }
        ZipArchiveEntry entry=new ZipArchiveEntry(entryName);entry.setTime(file.lastModified());entry.setSize(file.length());
        zip.putArchiveEntry(entry);
        try(InputStream in=open(file)){
            byte[] buffer=new byte[128*1024];int n;
            while((n=in.read(buffer))!=-1){
                if(progress.cancelled())throw new IOException("已取消");
                zip.write(buffer,0,n);written[0]+=n;progress.onProgress(entryName,written[0]);
            }
        }
        zip.closeArchiveEntry();
    }

    private static void add(TarArchiveOutputStream tar,File file,String entryName,Progress progress,long[] written)throws Exception{
        if(progress.cancelled())throw new IOException("已取消");
        if(file.isDirectory()){
            String dir=entryName.endsWith("/")?entryName:entryName+"/";
            TarArchiveEntry entry=new TarArchiveEntry(file,dir);tar.putArchiveEntry(entry);tar.closeArchiveEntry();
            File[] kids=file.listFiles();
            if(kids==null)throw new IOException("无法读取目录："+file.getAbsolutePath());
            Arrays.sort(kids,(a,b)->a.getName().compareToIgnoreCase(b.getName()));for(File kid:kids)add(tar,kid,dir+kid.getName(),progress,written);
            return;
        }
        TarArchiveEntry entry=new TarArchiveEntry(file,entryName);
        tar.putArchiveEntry(entry);
        try(InputStream in=open(file)){
            byte[] buffer=new byte[128*1024];int n;
            while((n=in.read(buffer))!=-1){
                if(progress.cancelled())throw new IOException("已取消");
                tar.write(buffer,0,n);written[0]+=n;progress.onProgress(entryName,written[0]);
            }
        }
        tar.closeArchiveEntry();
    }

    /** 只消费字节、不落盘：给「跳过条目但必须把数据读出来」的场景用（RAR solid 归档）。 */
    private static final class DiscardOutputStream extends OutputStream {
        private final Progress progress;
        DiscardOutputStream(Progress progress){this.progress=progress;}
        @Override public void write(int b)throws IOException{checkCancelled(progress);}
        @Override public void write(byte[] b,int off,int len)throws IOException{checkCancelled(progress);}
    }

    /** 仅用于 rar 解压统计已写入字节数。 */
    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;private final Progress progress;private final String name;private final long before;private final File target;private final long baselineFree;long count;private long reported;private long sinceCheck;
        CountingOutputStream(OutputStream delegate,Progress progress,String name,long before,File target,long baselineFree){this.delegate=delegate;this.progress=progress;this.name=name;this.before=before;this.target=target;this.baselineFree=baselineFree;}
        private void report(){if(count-reported>=128*1024){reported=count;progress.onProgress(name,before+count);}}
        @Override public void write(int b)throws IOException{checkCancelled(progress);delegate.write(b);count++;report();tick(1);}
        @Override public void write(byte[] b,int off,int len)throws IOException{checkCancelled(progress);delegate.write(b,off,len);count+=len;report();tick(len);}
        /** 每 8MB 按目标卷可用空间对一次账（zip 炸弹防护，见 checkSpace）。 */
        private void tick(int bytes)throws IOException{
            sinceCheck+=bytes;
            if(sinceCheck>=8*1024*1024){sinceCheck=0;checkSpace(target,before+count,baselineFree);}
        }
        @Override public void flush()throws IOException{delegate.flush();}
        @Override public void close()throws IOException{delegate.close();}
    }

    /** 压缩时用的默认单文件基名。 */
    static String defaultArchiveName(String firstName){return stripExtension(firstName)+".zip";}

    static List<File> asFiles(List<String> paths){ArrayList<File> files=new ArrayList<>();for(String p:paths)files.add(new File(p));return files;}
}
