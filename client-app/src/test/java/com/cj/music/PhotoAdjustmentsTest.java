package com.cj.music;
import org.junit.Test;
import static org.junit.Assert.*;
public class PhotoAdjustmentsTest {
 @Test public void identityAndAlpha(){assertEquals(0x7f56789a,PhotoAdjustments.apply(0x7f56789a,0,1,0));}
 @Test public void vibranceProtectsAlreadySaturatedColours(){assertEquals(0xffff0000,PhotoAdjustments.apply(0xffff0000,0,1,1));int muted=0xffa08888;assertNotEquals(muted,PhotoAdjustments.apply(muted,0,1,1));}
 @Test public void desaturateAndClamp(){int gray=PhotoAdjustments.apply(0xff604020,0,1,-1);assertEquals((gray>>16)&255,gray&255);assertEquals(0xffffffff,PhotoAdjustments.apply(0xffffffff,90,1.8f,1));}
}
