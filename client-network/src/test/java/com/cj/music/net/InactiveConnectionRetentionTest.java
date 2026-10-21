package com.cj.music.net;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class InactiveConnectionRetentionTest {
    @Test public void leavingKeepsConnectionForTwoFullMinutes(){
        InactiveConnectionRetention timer=new InactiveConnectionRetention();timer.select("server",Arrays.asList("server"),0);
        assertTrue(timer.expired(999999).isEmpty());timer.select("",Arrays.asList("server"),1000000);
        assertTrue(timer.expired(1119999).isEmpty());assertEquals(Arrays.asList("server"),timer.expired(1120000));
    }
    @Test public void returningCancelsExpiryAndNextDepartureStartsFresh(){
        InactiveConnectionRetention timer=new InactiveConnectionRetention();timer.select("",Arrays.asList("server"),0);
        timer.select("server",Arrays.asList("server"),119999);assertTrue(timer.expired(120000).isEmpty());
        timer.select("",Arrays.asList("server"),200000);assertTrue(timer.expired(319999).isEmpty());assertEquals(Arrays.asList("server"),timer.expired(320000));
    }
    @Test public void refreshingAnotherTabDoesNotExtendDeadline(){
        InactiveConnectionRetention timer=new InactiveConnectionRetention();timer.select("other",Arrays.asList("server","other"),0);
        timer.select("other",Arrays.asList("server","other"),119999);assertEquals(Arrays.asList("server"),timer.expired(120000));
    }
    @Test public void switchingBetweenTabsUsingSameConnectionKeepsItActive(){
        InactiveConnectionRetention timer=new InactiveConnectionRetention();timer.select("server",Arrays.asList("server"),0);
        timer.select("server",Arrays.asList("server"),10000);assertTrue(timer.expired(999999).isEmpty());
    }
}
