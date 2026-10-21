package com.cj.music.net;

/** Explicit-share profiles keep legacy share-relative paths; server profiles use share/path. */
final class SmbLocation {
    final String share,path;
    final boolean serverRoot;
    private SmbLocation(String share,String path,boolean root){this.share=share;this.path=path;this.serverRoot=root;}
    static SmbLocation resolve(String fixedShare,String remote){
        String path=SmbSession.cleanPath(remote);
        if(!fixedShare.isEmpty())return new SmbLocation(fixedShare,path,false);
        if(path.isEmpty())return new SmbLocation("","",true);
        int slash=path.indexOf('/');
        return new SmbLocation(slash<0?path:path.substring(0,slash),slash<0?"":path.substring(slash+1),false);
    }
}
