package com.karic.penny;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

/** Runs on a disposable emulator. Never run against a phone containing personal Penny data. */
public class DeviceTest extends InstrumentationTestCase {
    private Context context;
    @Override protected void setUp() throws Exception {super.setUp();context=getInstrumentation().getTargetContext();context.deleteDatabase("penny.db");}
    public void testV1MigrationAndRepeatedImports() {
        SQLiteOpenHelper old=new SQLiteOpenHelper(context,"penny.db",null,1){
            public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE transactions(id INTEGER PRIMARY KEY, sender TEXT, amount REAL, balance REAL)");db.execSQL("INSERT INTO transactions VALUES(1,'old record',6500,0)");}
            public void onUpgrade(SQLiteDatabase db,int a,int b){}
        };
        old.getWritableDatabase();old.close();
        try(PennyDatabase db=new PennyDatabase(context)) {
            assertEquals(1,db.legacyCount());assertEquals(0,db.ledger().entries.size());
            String body="MTN has deducted UGX 6500 at a fee of UGX 0. Transaction ID: TEST9000001. Message: Bundle Purchase. New balance is:UGX 4663";
            long at=System.currentTimeMillis();assertTrue(db.insertSms("MTN",body,at));assertFalse(db.insertSms("MTN",body,at));
            assertTrue(db.insertSms("MTN",body.replace("UGX 4663","UGX 4663.36"),at+1000));
            Ledger l=db.ledger();assertEquals(1,l.entries.size());assertEquals(0,l.totals(0,Long.MAX_VALUE,"All Accounts").spending.compareTo(new java.math.BigDecimal("6500")));
            Ledger.Entry e=l.entries.get(0);db.correct(e.key,"Bills",false,null);
        }
        try(PennyDatabase reopened=new PennyDatabase(context)){assertEquals("Bills",reopened.ledger().entries.get(0).category);assertEquals(1,reopened.legacyCount());}
    }
    public void testDashboardAndOriginalSmsDetail() throws Exception {
        long now=System.currentTimeMillis()-120000;
        SimpleDateFormat fmt=new SimpleDateFormat("dd-MM-yyyy 'at' HH:mm",Locale.US);fmt.setTimeZone(TimeZone.getTimeZone("Africa/Kampala"));String date=fmt.format(new Date(now));
        try(PennyDatabase db=new PennyDatabase(context)) {
            db.insertSms("Centenary","a trxn of -10,000 on your A/C **123 on "+date+". Bal:50,621. Ref: TESTBANK01",now);
            db.insertSms("MTN","MTN has deducted UGX 6500 at a fee of UGX 0. Transaction ID: TEST9000001. Message: Bundle Purchase. New balance is:UGX 4663",now);
            db.insertSms("MTN","MTN has deducted UGX 6500 at a fee of UGX 0. Transaction ID: TEST9000001. Message: Bundle Purchase. New balance is:UGX 4663.36",now+1000);
            db.markImport(System.currentTimeMillis());
        }
        Activity a=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            for(int i=0;i<100;i++){getInstrumentation().waitForIdleSync();final boolean[] ready={false};getInstrumentation().runOnMainSync(()->ready[0]=find(a.getWindow().getDecorView(),"UGX 55,284.36")!=null);if(ready[0])break;SystemClock.sleep(50);}
            getInstrumentation().runOnMainSync(()->{
                assertNotNull(find(a.getWindow().getDecorView(),"UGX 55,284.36"));
                assertNotNull(find(a.getWindow().getDecorView(),"Net cash flow"));
                assertNull(find(a.getWindow().getDecorView(),"Import email receipt"));
            });
            screenshot("01-balances");
            ScrollView scroll=(ScrollView)((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
            getInstrumentation().runOnMainSync(()->scroll.scrollTo(0,650));SystemClock.sleep(200);screenshot("02-cash-flow");
            getInstrumentation().runOnMainSync(()->scroll.fullScroll(View.FOCUS_DOWN));SystemClock.sleep(200);screenshot("03-transactions");
            getInstrumentation().runOnMainSync(()->{TextView bundle=find(a.getWindow().getDecorView(),"MTN Data Bundle");assertNotNull(bundle);((View)bundle.getParent()).performClick();});
            getInstrumentation().waitForIdleSync();SystemClock.sleep(200);screenshot("04-sms-detail");
        } finally {getInstrumentation().runOnMainSync(a::finish);getInstrumentation().waitForIdleSync();}
    }
    public void testReceiverThenInboxWithDifferentArrivalTimesIsIdempotent() {
        try(PennyDatabase db=new PennyDatabase(context)) {
            long sent=System.currentTimeMillis()-1000;
            String body="MTN has deducted UGX 6500. Message: Bundle Purchase";
            assertTrue(db.insertSms("MTN",body,sent+400,sent));
            assertFalse(db.insertSms("MTN",body,sent+800,sent));
            assertEquals(1,db.ledger().entries.size());
        }
    }
    private TextView find(View v,String exact){if(v instanceof TextView&&exact.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),exact);if(t!=null)return t;}return null;}
    public void testTransferPairPersistsAndCanBeUnlinked() {
        long at=System.currentTimeMillis();String key;
        try(PennyDatabase db=new PennyDatabase(context)) {
            db.insertSms("Centenary","a trxn of -10000 on your A/C **123. Funds Transfer. Fee UGX 50. Ref: TESTBANK01",at);
            db.insertSms("MTN","MTN received UGX 10000 from Bank. Fee UGX 0. Ref: TESTMOMO01",at);
            Ledger l=db.ledger();db.link(l.entries.get(0),l.entries.get(1));key=l.entries.get(0).key;
        }
        try(PennyDatabase db=new PennyDatabase(context)) {
            Ledger l=db.ledger();assertTrue(l.entries.get(0).internal);assertTrue(l.entries.get(1).internal);
            assertEquals(0,new java.math.BigDecimal("50").compareTo(l.totals(0,Long.MAX_VALUE,"All Accounts").spending));
            assertEquals(0,l.totals(0,Long.MAX_VALUE,"All Accounts").income.signum());
            Ledger.Entry e=l.find(key);db.unlink(e,l.find(e.peer));assertFalse(db.ledger().find(key).internal);
        }
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File directory=new File(context.getFilesDir(),"screenshots");assertTrue(directory.isDirectory()||directory.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
}
