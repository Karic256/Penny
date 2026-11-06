package com.karic.penny;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

/** Paired cash-flow bars with a zero baseline, readable labels and accessibility values. */
public final class TrendView extends View {
    private final double[] income=new double[7],spending=new double[7];
    private final String[] labels=new String[7];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private double max=1;
    public TrendView(Context c,Ledger ledger,long start,long end,String accountKind) {
        super(c);long span=Math.max(1,end-start);
        SimpleDateFormat fmt=new SimpleDateFormat(span<=86400000L?"HH:mm":"dd MMM",Locale.US);fmt.setTimeZone(TimeZone.getTimeZone("Africa/Kampala"));
        StringBuilder access=new StringBuilder("Income and spending in seven time intervals. ");
        for(int i=0;i<7;i++) {
            long lo=start+span*i/7,hi=start+span*(i+1)/7;
            Ledger.Totals t=ledger.totals(lo,hi,accountKind);
            income[i]=t.income.doubleValue();spending[i]=t.spending.doubleValue();max=Math.max(max,Math.max(income[i],spending[i]));
            labels[i]=fmt.format(new Date(lo));access.append(labels[i]).append(": income UGX ").append(t.income).append(", spending UGX ").append(t.spending).append(". ");
        }
        setContentDescription(access.toString());setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);float d=getResources().getDisplayMetrics().density;
        float left=48*d,top=20*d,bottom=getHeight()-34*d,width=getWidth()-left-10*d,h=bottom-top;
        paint.setTextSize(10*d);paint.setColor(0xff7b8b82);
        for(int i=0;i<=2;i++) {float y=bottom-h*i/2;c.drawLine(left,y,getWidth()-8*d,y,paint);c.drawText(shortNumber(max*i/2),0,y+4*d,paint);}
        float unit=width/7,bar=Math.min(11*d,unit*.29f);
        for(int i=0;i<7;i++) {
            float x=left+unit*(i+.5f);paint.setColor(0xff137856);c.drawRoundRect(x-bar-1*d,bottom-(float)(income[i]/max*h),x-1*d,bottom,3*d,3*d,paint);
            paint.setColor(0xffd49352);c.drawRoundRect(x+1*d,bottom-(float)(spending[i]/max*h),x+bar+1*d,bottom,3*d,3*d,paint);
            if(i%2==0) {paint.setColor(0xff64776b);paint.setTextAlign(Paint.Align.CENTER);c.drawText(labels[i],x,bottom+19*d,paint);paint.setTextAlign(Paint.Align.LEFT);}
        }
    }
    private String shortNumber(double value) {return value>=1000000?String.format(Locale.US,"%.1fm",value/1000000):value>=1000?String.format(Locale.US,"%.0fk",value/1000):String.format(Locale.US,"%.0f",value);}
}
