package com.cj.music;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class MusicMotionUiTest {
    @Test public void sidebarMotionRemovesPressColorButKeepsSelection(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            android.widget.TextView item=new android.widget.TextView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed},new ColorDrawable(Color.RED));
            states.addState(new int[]{android.R.attr.state_selected},new ColorDrawable(Color.BLUE));
            states.addState(new int[]{},new ColorDrawable(Color.WHITE));
            item.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.RED),states,null));
            SidebarPressMotion.down(item);item.setPressed(true);
            assertEquals(Color.WHITE,((ColorDrawable)item.getBackground().getCurrent()).getColor());
            item.setSelected(true);assertEquals(Color.BLUE,((ColorDrawable)item.getBackground().getCurrent()).getColor());
            SidebarPressMotion.release(item,true);item.animate().cancel();
        });
    }
}
