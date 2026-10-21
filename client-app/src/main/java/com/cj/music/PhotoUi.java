package com.cj.music;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.ImageButton;

/** Shared line icons: consistent optical size and touch targets in the photo tools. */
final class PhotoUi {
    static final int BLUE=0xff087fff, INK=0xff151518, MUTED=0xff8b8b90, YELLOW=0xffffcf00, PAPER=0xfff8f8f8;
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable bg(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    static ImageButton button(Context c,String icon,String label,int color,Runnable action){
        ImageButton b=new ImageButton(c);b.setImageDrawable(new Icon(icon,color));b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(c,11),dp(c,11),dp(c,11),dp(c,11));b.setContentDescription(label);b.setOnClickListener(v->action.run());return b;
    }
    static final class Icon extends Drawable {
        final String name;final Paint p=new Paint(3);final int color;
        Icon(String name,int color){this.name=name;this.color=color;}
        void line(Canvas c,float... pts){Path q=new Path();q.moveTo(pts[0],pts[1]);for(int i=2;i<pts.length;i+=2)q.lineTo(pts[i],pts[i+1]);c.drawPath(q,p);}
        public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/28f,getBounds().height()/28f);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.65f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(name){
                case "share":line(c,9,5,14,1,19,5);line(c,14,2,14,18);line(c,8,10,4,10,4,25,24,25,24,10,20,10);break;
                case "heart":case "heartFill":{Path q=new Path();q.moveTo(14,25);q.cubicTo(10,22,1,15,2,8);q.cubicTo(3,1,11,1,14,7);q.cubicTo(18,0,26,2,26,9);q.cubicTo(26,16,18,22,14,25);if(name.equals("heartFill"))p.setStyle(Paint.Style.FILL);c.drawPath(q,p);break;}
                case "info":c.drawCircle(14,14,11,p);line(c,12,13,15,13,15,21);line(c,11,21,18,21);c.drawCircle(14,8,0.8f,p);break;
                case "sliders":for(int i=0;i<3;i++){int y=6+i*8,x=i==1?9:19;line(c,2,y,x-3,y);line(c,x+3,y,26,y);c.drawCircle(x,y,2.5f,p);}break;
                case "trash":line(c,3,6,25,6);line(c,10,5,10,2,18,2,18,5);line(c,6,7,7,25,21,25,22,7);line(c,11,10,11,21);line(c,17,10,17,21);break;
                case "back":line(c,18,4,8,14,18,24);break;
                case "rotate":line(c,20,9,20,3,25,7);c.drawRoundRect(4,11,19,25,2,2,p);line(c,20,3,14,3,11,6);break;
                case "flip":line(c,14,8,14,26);line(c,11,10,2,25,11,25,11,10);line(c,17,10,26,25,17,25,17,10);line(c,3,4,25,4);line(c,6,1,3,4,6,7);line(c,22,1,25,4,22,7);break;
                case "crop":line(c,7,2,7,21,26,21);line(c,2,7,21,7,21,26);break;
                case "aspect":c.drawRoundRect(2,4,26,24,2,2,p);line(c,2,11,26,11);line(c,17,11,17,24);break;
                case "pen":c.drawCircle(14,14,12,p);line(c,9,22,13,6,15,6,19,22);line(c,11,16,17,16);break;
                case "filter":c.drawCircle(14,8,6,p);c.drawCircle(8,18,6,p);c.drawCircle(20,18,6,p);break;
                case "adjust":c.drawCircle(14,14,8,p);line(c,9,19,19,9);for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawPoint(14+(float)Math.cos(a)*12,14+(float)Math.sin(a)*12,p);}break;
                case "vertical":line(c,8,5,20,5,24,23,4,23,8,5);line(c,14,2,14,26);break;
                case "horizontal":line(c,5,8,23,4,23,24,5,20,5,8);line(c,2,14,26,14);break;
                default:c.drawCircle(14,14,11,p);for(int i=0;i<3;i++)c.drawCircle(8+i*6,14,0.8f,p);
            }c.restore();
        }
        public void setAlpha(int a){p.setAlpha(a);}public void setColorFilter(ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
