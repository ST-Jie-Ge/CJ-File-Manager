package com.cj.music.net;

import java.util.*;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.parser.MLSxEntryParser;

/** Preserve OS-specific link types that Commons Net's standard parser discards. */
final class MlsdEntryParser {
    static RemoteEntry parse(String parent,String line){
        int separator=line.indexOf(' ');if(separator<0)return null;
        String name=line.substring(separator+1),type="",normalized="",created="";
        for(String fact:line.substring(0,separator).split(";")){
            int eq=fact.indexOf('=');if(eq<0)continue;
            if(fact.substring(0,eq).equalsIgnoreCase("type"))type=fact.substring(eq+1).toLowerCase(Locale.ROOT);
            else {normalized+=fact+";";if(fact.substring(0,eq).equalsIgnoreCase("create"))created=fact.substring(eq+1);}
        }
        if(type.equals("cdir")||type.equals("pdir")||name.equals(".")||name.equals(".."))return null;
        boolean link=type.equals("slink")||type.startsWith("os.unix=slink")||type.startsWith("os.unix=symlink");
        normalized+="type="+(link?"file":type)+"; "+name;
        FTPFile parsed=MLSxEntryParser.parseEntry(normalized);
        if(parsed==null)return null;
        FTPFile birth=created.isEmpty()?null:MLSxEntryParser.parseEntry("modify="+created+";type=file; birth");
        long creation=birth==null||birth.getTimestamp()==null?0:birth.getTimestamp().getTimeInMillis();
        try{return new RemoteEntry(name,FtpSession.child(parent,name),parsed.isDirectory(),parsed.getSize(),
                parsed.getTimestamp()==null?0:parsed.getTimestamp().getTimeInMillis(),link,creation);}
        catch(IllegalArgumentException invalid){return null;}
    }
}
