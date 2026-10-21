package com.cj.music.net;

import java.util.*;

/** Monotonic deadlines begin on leaving a connection, never on last network I/O. */
public final class InactiveConnectionRetention {
    public static final long DELAY_MILLIS=120_000L;
    private final Map<String,Long> inactiveSince=new HashMap<>();
    public void forget(String id){inactiveSince.remove(id);}
    public void select(String active,Collection<String> connected,long now){
        inactiveSince.keySet().retainAll(connected);
        inactiveSince.remove(active);
        for(String id:connected)if(!id.equals(active))inactiveSince.putIfAbsent(id,now);
    }
    public List<String> expired(long now){
        List<String> result=new ArrayList<>();Iterator<Map.Entry<String,Long>> entries=inactiveSince.entrySet().iterator();
        while(entries.hasNext()){Map.Entry<String,Long> entry=entries.next();if(now-entry.getValue()>=DELAY_MILLIS){result.add(entry.getKey());entries.remove();}}
        return result;
    }
}
