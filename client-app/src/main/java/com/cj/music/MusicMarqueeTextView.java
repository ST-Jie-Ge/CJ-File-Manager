package com.cj.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.animation.LinearInterpolator;
import androidx.appcompat.widget.AppCompatTextView;
import android.text.Layout;

/** Single-line, back-and-forth title with a readable pause at both ends. */
final class MusicMarqueeTextView extends AppCompatTextView {
    private ValueAnimator motion;
    private float offset;
    private String content;
    private boolean frozen;
    void setFrozen(boolean value){frozen=value;if(motion!=null){if(value)motion.pause();else motion.resume();}if(!value)invalidate();}
    MusicMarqueeTextView(Context context){super(context);setSingleLine(true);setEllipsize(null);setHorizontallyScrolling(true);setFallbackLineSpacing(true);setIncludeFontPadding(true);}
    private void stop(){if(motion!=null){motion.removeAllListeners();motion.cancel();motion=null;}}
    private void restart(){stop();offset=0;invalidate();}
    @Override protected void onTextChanged(CharSequence text,int start,int before,int count){super.onTextChanged(text,start,before,count);String next=text==null?"":text.toString();if(!next.equals(content)){content=next;restart();}}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw)restart();}
    @Override protected void onDraw(Canvas canvas){
        Layout layout=getLayout();if(layout==null||layout.getLineCount()==0){super.onDraw(canvas);return;}
        int available=getWidth()-getCompoundPaddingLeft()-getCompoundPaddingRight();float overflow=layout.getLineWidth(0)-available;
        if(overflow<=0||available<=0){stop();offset=0;super.onDraw(canvas);return;}
        // Draw the shaped TextView layout, including emoji spans, fallback fonts and bidi runs.
        // Canvas.drawText(String) bypassed those spans when a long title started scrolling.
        float scroll=layout.getParagraphDirection(0)==Layout.DIR_RIGHT_TO_LEFT?overflow-offset:offset;
        int save=canvas.save();canvas.clipRect(getCompoundPaddingLeft(),0,getWidth()-getCompoundPaddingRight(),getHeight());getPaint().setColor(getCurrentTextColor());canvas.translate(getCompoundPaddingLeft()-layout.getLineLeft(0)-scroll,getBaseline()-layout.getLineBaseline(0));layout.draw(canvas);canvas.restoreToCount(save);
        if(!frozen&&motion==null&&isShown()&&isAttachedToWindow())move(overflow,overflow);
    }
    private void move(float target,float overflow){
        motion=ValueAnimator.ofFloat(offset,target);motion.setStartDelay(1300);float density=getResources().getDisplayMetrics().density;motion.setDuration(Math.max(1000,Math.round(overflow/density/28*1000)));motion.setInterpolator(new LinearInterpolator());
        motion.addUpdateListener(value->{offset=(float)value.getAnimatedValue();invalidate();});motion.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){motion=null;if(isAttachedToWindow()&&isShown())move(target>0?0:overflow,overflow);}});motion.start();
    }
    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
    @Override protected void onVisibilityChanged(View changedView,int visibility){super.onVisibilityChanged(changedView,visibility);if(visibility!=VISIBLE)stop();else invalidate();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)stop();else invalidate();}
}
