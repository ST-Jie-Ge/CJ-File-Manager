package com.cj.music;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;

/** 从侧边栏事件入口反馈原生按钮，不覆盖点击/长按/滚动监听。 */
final class SidebarPressMotion {
    static View target(View view,int x,int y){
        Rect bounds=new Rect();if(!view.isEnabled()||view.getVisibility()!=View.VISIBLE||!view.getGlobalVisibleRect(bounds)||!bounds.contains(x,y))return null;
        if(view instanceof androidx.compose.ui.platform.ComposeView)return null;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=group.getChildCount()-1;i>=0;i--){View hit=target(group.getChildAt(i),x,y);if(hit!=null)return hit;}}
        return view.isClickable()||view.isLongClickable()?view:null;
    }
    private static Drawable withoutPress(Drawable drawable){
        if(drawable instanceof RippleDrawable){RippleDrawable ripple=(RippleDrawable)drawable;for(int i=0;i<ripple.getNumberOfLayers();i++)if(ripple.getId(i)!=android.R.id.mask)return withoutPress(ripple.getDrawable(i));return null;}
        if(drawable instanceof StateListDrawable){
            StateListDrawable states=(StateListDrawable)drawable,result=new StateListDrawable();
            for(int i=0;i<states.getStateCount();i++){int[] set=states.getStateSet(i);boolean press=false;for(int state:set)if(state==android.R.attr.state_pressed)press=true;if(!press)result.addState(set,states.getStateDrawable(i));}
            return result;
        }
        return drawable;
    }
    static void down(View view){
        view.setBackground(withoutPress(view.getBackground()));view.setForeground(withoutPress(view.getForeground()));view.setStateListAnimator(null);
        view.animate().cancel();view.animate().scaleX(.96f).scaleY(.91f).setDuration(90).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    static void release(View view,boolean cancelled){
        if(view==null)return;view.animate().cancel();view.animate().scaleX(1).scaleY(1).setDuration(cancelled?140:260).setInterpolator(cancelled?new android.view.animation.DecelerateInterpolator():new android.view.animation.OvershootInterpolator(2.2f)).start();
    }
}
