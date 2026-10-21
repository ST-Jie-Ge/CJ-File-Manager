package com.cj.music;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class PcmLoudnessProcessorTest {
    private PcmLoudnessProcessor processor(int channels)throws Exception{
        PcmLoudnessProcessor p=new PcmLoudnessProcessor();
        p.configure(new AudioProcessor.AudioFormat(48000,channels,C.ENCODING_PCM_16BIT));p.flush();return p;
    }
    private ByteBuffer block(int sample,int channels){ByteBuffer b=ByteBuffer.allocateDirect(960*channels*2).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<960*channels;i++)b.putShort((short)sample);b.flip();return b;}
    @Test public void measuresDigitalRmsAndPreservesEveryByte()throws Exception{
        PcmLoudnessProcessor p=processor(2);ByteBuffer b=block(16384,2);byte[] expected=new byte[b.remaining()];b.duplicate().get(expected);
        p.queueInput(b);assertFalse(b.hasRemaining());ByteBuffer out=p.getOutput();byte[] actual=new byte[out.remaining()];out.get(actual);
        assertArrayEquals(expected,actual);assertEquals(.5f,p.rmsAt(0),.0001f);assertEquals(-6.0206,20*Math.log10(p.rmsAt(0)),.001);
    }
    @Test public void respectsPlaybackWindowsAndResetsOnSeek()throws Exception{
        PcmLoudnessProcessor p=processor(1);p.queueInput(block(0,1));p.getOutput();p.queueInput(block(8192,1));p.getOutput();
        assertEquals(0,p.rmsAt(0),0);assertEquals(.25f,p.rmsAt(20),.0001f);assertEquals(0,p.rmsAt(1000),0);
        p.flush();assertEquals(0,p.rmsAt(20),0);p.queueInput(block(16384,1));assertEquals(.5f,p.rmsAt(0),.0001f);
    }
    @Test public void measuresPartialFinalWindow()throws Exception{
        PcmLoudnessProcessor p=processor(1);ByteBuffer b=ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN);b.putShort((short)-16384).flip();p.queueInput(b);p.getOutput();p.queueEndOfStream();assertEquals(.5f,p.rmsAt(0),.0001f);
    }
    @Test public void drainedOutputMayBeFedBackAsEmptyInput()throws Exception{
        PcmLoudnessProcessor p=processor(1);p.queueInput(block(16384,1));ByteBuffer out=p.getOutput();out.position(out.limit());p.queueInput(out);assertEquals(.5f,p.rmsAt(0),.0001f);
    }
}
