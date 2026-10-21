#define DSP_HOST_TEST
#include "../../main/cpp/dsp.cpp"
#include <cassert>
#include <iostream>
#include <random>

double tone(Eq& eq,int rate,double hz){
    double energy=0;float frame[8]{};
    for(int i=0;i<rate;i++){frame[0]=float(.1*std::sin(2*pi*hz*i/rate));frame[1]=0;eq.process(frame);if(i>rate/2)energy+=frame[0]*frame[0];assert(frame[1]==0);}
    return std::sqrt(energy/(rate/2-1));
}
int main(){
    for(int rate:{8000,44100,48000,96000,192000}){
        Eq eq;eq.targetWet=1;eq.prepare(rate,2);
        double flat=tone(eq,rate,1000);assert(std::abs(flat-.1/std::sqrt(2.))<.0001);
        eq.target[0].hz=1000;eq.target[0].gain=6;eq.reset();double boosted=tone(eq,rate,1000);assert(std::abs(20*std::log10(boosted/flat)-6)<.02);
        eq.targetWet=0;eq.reset();assert(std::abs(tone(eq,rate,1000)-flat)<1e-7);
        eq.targetWet=1;eq.target[0].gain=0;eq.targetPre=-6;eq.reset();assert(std::abs(20*std::log10(tone(eq,rate,1000)/flat)+6)<.02);
        eq.targetPre=12;eq.reset();assert(std::abs(20*std::log10(tone(eq,rate,1000)/flat)-12)<.02);
        eq.reset();float zero[8]{};for(int i=0;i<5000;i++){eq.process(zero);assert(zero[0]==0&&zero[1]==0);}
    }
    // Parameter torture, all channels, repeated topology/bypass changes and long
    // running state. This catches IIR instability and counter wrap assumptions.
    std::mt19937 rng(1);Eq eq;eq.prepare(48000,8);float frame[8];
    for(int n=0;n<48000*180;n++){
        if(n%997==0){eq.targetWet=(rng()%2);for(auto& b:eq.target){b.hz=20+int(rng()%19981);b.gain=int(rng()%25)-12;b.q=.1+(rng()%119)/10.;b.type=rng()%3;b.enabled=rng()%2;}}
        for(float& v:frame)v=float((int(rng()%2001)-1000)*1e-5);
        eq.process(frame);for(float v:frame)assert(std::isfinite(v));
    }
    std::cout<<"PASS: unity, +6dB response, bypass, preamp, channel isolation, reset, five sample rates, 180s parameter stress\n";
}
