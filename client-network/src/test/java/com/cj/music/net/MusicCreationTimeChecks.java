package com.cj.music.net;

public class MusicCreationTimeChecks {
    private static void assertNotNull(Object value){if(value==null)throw new AssertionError("Missing entry");}
    private static void assertTrue(boolean value){if(!value)throw new AssertionError();}
    private static void assertEquals(long expected,long actual){if(expected!=actual)throw new AssertionError(expected+" != "+actual);}
    public static void main(String[] args){MusicCreationTimeChecks checks=new MusicCreationTimeChecks();checks.creationAndModificationAreIndependent();checks.missingCreationNeverUsesModification();checks.invalidCreationIsUnknown();System.out.println("PASS: FTP creation, missing/invalid creation, independence from modification");}
    public void creationAndModificationAreIndependent(){
        RemoteEntry entry=MlsdEntryParser.parse("/","type=file;size=9;modify=20261011120000;create=20200102030405; track.mp3");
        assertNotNull(entry);assertEquals(1577934245000L,entry.created);assertTrue(entry.modified>entry.created);
    }
    public void missingCreationNeverUsesModification(){
        RemoteEntry entry=MlsdEntryParser.parse("/","type=file;modify=20261011120000; track.mp3");
        assertNotNull(entry);assertEquals(0,entry.created);assertTrue(entry.modified>0);
    }
    public void invalidCreationIsUnknown(){
        RemoteEntry entry=MlsdEntryParser.parse("/","type=file;modify=20261011120000;create=invalid; track.mp3");
        assertNotNull(entry);assertEquals(0,entry.created);
    }
}
