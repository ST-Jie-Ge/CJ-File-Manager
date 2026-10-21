package com.cj.music;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Read-only PCM tap. Samples go to the audio sink unchanged; no capture API is used. */
final class PcmLoudnessProcessor extends BaseAudioProcessor {
    private final long[] times=new long[2048];
    private final float[] values=new float[2048];
    private int count, cursor, windowSamples, channels=1, sampleRate=48000, encoding;
    private long totalSamples;
    private double squares;
    private int samples;

    @Override protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat format) {
        encoding=format.encoding;channels=format.channelCount;sampleRate=format.sampleRate;
        windowSamples=Math.max(channels,(sampleRate/50)*channels);
        return encoding==C.ENCODING_PCM_16BIT||encoding==C.ENCODING_PCM_FLOAT
                ?format:AudioProcessor.AudioFormat.NOT_SET;
    }
    @Override public void queueInput(ByteBuffer input) {
        if(!input.hasRemaining())return;
        ByteBuffer output=replaceOutputBuffer(input.remaining());
        ByteBuffer read=input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int bytes=encoding==C.ENCODING_PCM_FLOAT?4:2;
        while(read.remaining()>=bytes) {
            double sample=bytes==4?read.getFloat():read.getShort()/32768.0;
            if(!Double.isFinite(sample))sample=0;
            squares+=sample*sample;samples++;totalSamples++;
            if(samples>=windowSamples)publish();
        }
        output.put(input).flip();
    }
    private synchronized void publish() {
        times[cursor]=(totalSamples-samples)*1000/(channels*(long)sampleRate);
        values[cursor]=(float)Math.min(1,Math.sqrt(squares/samples));
        cursor=(cursor+1)%times.length;count=Math.min(count+1,times.length);
        samples=0;squares=0;
    }
    /** Look up the playing window, not the decoder's potentially seconds-ahead window. */
    synchronized float rmsAt(long positionMs) {
        for(int i=0;i<count;i++) {
            int at=(cursor-1-i+times.length)%times.length;
            if(times[at]<=positionMs)return positionMs-times[at]<100?values[at]:0;
        }
        return 0;
    }
    @Override protected void onQueueEndOfStream(){if(samples>0)publish();}
    @Override protected synchronized void onFlush(){count=cursor=samples=0;totalSamples=0;squares=0;Arrays.fill(values,0);}
}
