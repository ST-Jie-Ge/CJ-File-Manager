package com.cj.music.net;

public final class RemoteEntry {
    public final String name, path;
    public final boolean directory;
    public final boolean link;
    public final long size, modified, created;
    RemoteEntry(String name, String path, boolean directory, long size, long modified) {
        this(name,path,directory,size,modified,false);
    }
    RemoteEntry(String name, String path, boolean directory, long size, long modified, boolean link) {
        this(name,path,directory,size,modified,link,0);
    }
    RemoteEntry(String name,String path,boolean directory,long size,long modified,boolean link,long created) {
        this.created=Math.max(0,created);
        this.name=name; this.path=path; this.directory=directory; this.size=size; this.modified=modified;this.link=link;
    }
}
