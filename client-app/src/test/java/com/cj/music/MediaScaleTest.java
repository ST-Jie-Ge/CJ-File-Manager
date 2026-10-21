package com.cj.music;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaScaleTest {
    @Test public void portraitFits480p(){assertEquals(480,MediaScale.width(1080,1920,854,480,854));}
    @Test public void landscapeFits480p(){assertEquals(854,MediaScale.width(1920,1080,854,480,854));}
    @Test public void squareFitsShortEdge(){assertEquals(480,MediaScale.width(1080,1080,854,480,854));}
    @Test public void smallSourceIsNotUpscaled(){assertEquals(320,MediaScale.width(320,240,1280,720,1280));}
    @Test public void originalAndMissingMetadataRemainSupported(){assertEquals(0,MediaScale.width(1080,1920,0,0,854));assertEquals(854,MediaScale.width(0,0,854,480,854));}
}
