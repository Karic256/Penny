package com.karic.penny;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int PERMISSION=101,EXPORT=102;
    private static final int GREEN=0xff146446,INK=0xff173c2c,MUTED=0xff61766a,CREAM=0xfff4f7f2,AMBER=0xff98632e;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final TimeZone zone=TimeZone.getTimeZone("Africa/Kampala");
    private Ledger ledger=new Ledger(Collections.emptyList(),Collections.emptyMap());
    private LinearLayout content;
    private ScrollView scroll;
    private String period="This Month",accountKind="All Accounts",notice="",error="";
    private long customStart,customEnd,lastImport;
    private int legacy,limit=40,loadGeneration;
    private boolean busy;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null) {period=saved.getString("period","This Month");accountKind=saved.getString("kind","All Accounts");customStart=saved.getLong("start");customEnd=saved.getLong("end");}
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(CREAM);getWindow().setNavigationBarColor(CREAM);
        scroll=new ScrollView(this);scroll.setBackgroundColor(CREAM);scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(14),dp(18),dp(28));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        render();
    }
    @Override protected void onResume(){super.onResume();reload();}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("period",period);b.putString("kind",accountKind);b.putLong("start",customStart);b.putLong("end",customEnd);}
    @Override protected void onDestroy(){loadGeneration++;worker.shutdown();super.onDestroy();}
    private void reload() {
        if(isDestroyed())return;final int generation=++loadGeneration;
        worker.execute(()->{
            try(PennyDatabase db=new PennyDatabase(this)) {
                Ledger data=db.ledger();int old=db.legacyCount();long imported=db.lastImport();
                runOnUiThread(()->{if(!isDestroyed()&&generation==loadGeneration){ledger=data;legacy=old;lastImport=imported;render();}});
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){error="Could not load local records. Try importing again. "+e.getClass().getSimpleName();render();}});}
        });
    }
    private void render() {
        int y=scroll.getScrollY();content.removeAllViews();
        LinearLayout head=row();TextView brand=text("penny",32,INK,true);head.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=new Button(this);menu.setText("⋮");menu.setContentDescription("Settings and SMS tools");head.addView(menu,new LinearLayout.LayoutParams(dp(56),dp(48)));menu.setOnClickListener(v->menu(menu));content.addView(head);
        label(content,"Your accounts. Your money story.",15,MUTED);
        label(content,(permission(Manifest.permission.RECEIVE_SMS)?"● SMS listening":"○ SMS access off")+"   ·   On-device only",12,GREEN);
        if(busy)label(content,"Importing financial SMS…",14,AMBER);
        if(!notice.isEmpty())label(content,notice,13,GREEN);
        if(!error.isEmpty())label(content,error,13,0xffa13d36);
        heading("Last reported balances",20);
        LinearLayout hero=card(content,0xff15543b);
        BigDecimal combined=BigDecimal.ZERO;int known=0;long oldest=Long.MAX_VALUE,newest=0;Set<String> types=new HashSet<>();
        boolean identifiers=true;
        for(Ledger.Account a:ledger.accounts.values()) {
            identifiers&=a.identifierKnown;
            if(a.latest!=null){known++;combined=combined.add(a.latest.value);oldest=Math.min(oldest,a.latest.at);newest=Math.max(newest,a.latest.at);types.add(a.latest.type);}
        }
        boolean complete=known==ledger.accounts.size()&&identifiers;
        label(hero,complete?"COMBINED LAST-REPORTED BALANCE":"PARTIAL LAST-REPORTED TOTAL",11,0xffc7dfcb);
        label(hero,known==0?"Balance unavailable":money(combined),30,0xffffffff,true);
        label(hero,known+" of "+ledger.accounts.size()+" account slots have a balance"+(complete?"":" • incomplete"),12,0xffd6ead9);
        if(known>0)label(hero,"Reports: "+date(oldest)+ (!date(newest).equals(date(oldest))?" – "+date(newest):"")+" EAT",12,0xffd6ead9);
        label(hero,"SMS snapshots, not live balances"+(types.size()>1?" • mixed balance types":""),12,0xffd6ead9);
        if(!identifiers)label(hero,"Some account identifiers are missing; multiple wallets cannot be distinguished.",12,0xffd6ead9);
        for(Ledger.Account a:ledger.accounts.values()) {
            LinearLayout c=card(content,0xffffffff);
            label(c,a.label,16,INK,true);
            label(c,a.latest==null?"Balance unavailable":money(a.latest.value),25,INK,true);
            if(a.latest!=null){label(c,"Last reported balance • "+a.latest.type,12,MUTED);label(c,date(a.latest.at)+" EAT • "+freshness(a.latest.at),12,MUTED);label(c,a.latest.timeSource,11,MUTED);}
            for(Ledger.Balance b:a.byType.values())if(b!=a.latest)label(c,b.type+": "+money(b.value)+" • "+date(b.at)+" EAT",12,MUTED);
            if(!a.warnings.isEmpty())label(c,"Review: "+a.warnings.iterator().next(),12,AMBER);
        }
        heading("Money movement",20);
        LinearLayout filters=row();
        Spinner dates=spinner(new String[]{"Today","This Week","This Month","Custom Dates"},period);
        Spinner accounts=spinner(new String[]{"All Accounts","Bank","Mobile Money"},accountKind);
        filters.addView(dates,new LinearLayout.LayoutParams(0,dp(48),1));filters.addView(accounts,new LinearLayout.LayoutParams(0,dp(48),1));content.addView(filters);
        dates.setOnItemSelectedListener(new Choice(){public void chosen(String s){if(!s.equals(period)){if(s.equals("Custom Dates"))pickDates();else{period=s;limit=40;render();}}}});
        accounts.setOnItemSelectedListener(new Choice(){public void chosen(String s){if(!s.equals(accountKind)){accountKind=s;limit=40;render();}}});
        long[] window=window();long start=window[0],end=window[1];Ledger.Totals totals=ledger.totals(start,end,accountKind);
        label(content,shortDate(start)+" – "+shortDate(end-1)+" • EAT",12,MUTED);
        LinearLayout r1=row();metric(r1,"Income",totals.income,GREEN);metric(r1,"Spending",totals.spending,AMBER);content.addView(r1);
        LinearLayout r2=row();metric(r2,"Known fees",totals.fees,MUTED);metric(r2,"Net cash flow",totals.net,INK);content.addView(r2);
        label(content,"Spending includes known fees. Net cash flow is income minus spending; it is not an account balance.",12,MUTED);
        label(content,totals.pending+" pending • "+totals.excluded+" non-completed or unverified records excluded • "+totals.unknownFees+" transactions with unknown fees",12,AMBER);
        if(legacy>0)label(content,legacy+" old records archived. Reimport SMS to reconstruct verified accounts; old/email rows are excluded.",13,AMBER);
        label(content,"History may be incomplete. Only supported financial SMS present on this phone are included."+(lastImport>0?" Last inbox scan: "+date(lastImport)+" EAT.":" Import SMS from the ⋮ menu."),12,MUTED);
        LinearLayout trend=card(content,0xffffffff);label(trend,"Income vs spending",17,INK,true);label(trend,"● Income     ● Spending (amber) • UGX",12,MUTED);
        trend.addView(new TrendView(this,ledger,start,end,accountKind),new LinearLayout.LayoutParams(-1,dp(175)));
        breakdown("Spending by category",totals.categories);breakdown("Spending by account",totals.accounts);
        patterns(start,end,totals);
        heading("Recent transactions",20);
        int shown=0,total=0;
        for(Ledger.Entry e:ledger.entries)if(ledger.matches(e,start,end,accountKind)){total++;if(shown++<limit)transaction(e);}
        if(total==0)label(content,"No financial SMS in this period. Choose another period or import from the menu.",14,MUTED);
        if(total>limit)button(content,"Show more ("+total+" records)",()->{limit+=40;render();});
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private void metric(LinearLayout parent,String title,BigDecimal amount,int color) {
        LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(12),dp(13),dp(8),dp(13));c.setBackground(bg(0xffffffff));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(dp(3),dp(5),dp(3),dp(5));parent.addView(c,lp);
        label(c,title,12,MUTED);label(c,money(amount),19,color,true);
    }
    private void breakdown(String title,Map<String,BigDecimal> values) {
        LinearLayout c=card(content,0xffffffff);label(c,title,17,INK,true);
        if(values.isEmpty()){label(c,"No completed spending in this period",13,MUTED);return;}
        BigDecimal sum=BigDecimal.ZERO;for(BigDecimal v:values.values())sum=sum.add(v);
        List<Map.Entry<String,BigDecimal>> rows=new ArrayList<>(values.entrySet());rows.sort((a,b)->b.getValue().compareTo(a.getValue()));
        for(Map.Entry<String,BigDecimal> e:rows) {
            label(c,e.getKey()+"  ·  "+money(e.getValue()),13,INK);
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgress((int)(e.getValue().doubleValue()/sum.doubleValue()*1000));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));bar.setContentDescription(e.getKey()+" "+money(e.getValue()));c.addView(bar,new LinearLayout.LayoutParams(-1,dp(12)));
        }
    }
    private void patterns(long start,long end,Ledger.Totals now) {
        LinearLayout c=card(content,0xffe8efe3);label(c,"Patterns in your SMS",17,INK,true);
        Map<String,Integer> counts=new TreeMap<>();int bank=0,mobile=0;
        for(Ledger.Entry e:ledger.entries)if(ledger.matches(e,start,end,accountKind)&&e.counted()&&!e.internal) {
            if("Bank".equals(e.t.accountKind))bank++;else mobile++;
            if(!e.credit()&&!e.title.equals("Bank debit")&&!e.title.equals("Mobile money payment"))counts.put(e.title,counts.getOrDefault(e.title,0)+1);
        }
        label(c,"Completed activity: "+bank+" bank • "+mobile+" mobile money",13,INK);
        List<Map.Entry<String,Integer>> frequent=new ArrayList<>(counts.entrySet());frequent.sort((a,b)->b.getValue()-a.getValue());int displayed=0;
        for(Map.Entry<String,Integer> e:frequent)if(e.getValue()>1&&displayed++<4)label(c,e.getKey()+" • "+e.getValue()+" payments",13,INK);
        if(displayed==0)label(c,"No repeated payment descriptions in this period.",13,MUTED);
        label(c,"Repeated descriptions do not establish a recurring bill or subscription.",12,MUTED);
        long prior=start-(end-start);boolean coverage=ledger.entries.stream().anyMatch(e->e.occurredAt<=prior);
        if(coverage){BigDecimal before=ledger.totals(prior,start,accountKind).spending;label(c,"Recorded spending vs previous equal-length period: "+money(now.spending.subtract(before))+" change. Missing SMS can skew this comparison.",12,MUTED);}
        else label(c,"Not enough earlier SMS history for a spending trend comparison.",12,MUTED);
    }
    private void transaction(Ledger.Entry e) {
        LinearLayout c=card(content,0xffffffff);c.setClickable(true);c.setFocusable(true);c.setOnClickListener(v->detail(e));
        label(c,e.title,16,INK,true);
        label(c,(e.direction==null?"":e.credit()?"+":"−")+(e.amount==null?"Amount unknown":money(e.amount)),20,e.credit()?GREEN:INK,true);
        label(c,e.t.displayAccount()+" • "+(e.internal?"Internal transfer":e.category),12,MUTED);
        label(c,date(e.occurredAt)+" EAT • "+e.status+(e.conflict?" • NEEDS REVIEW":""),12,e.status==Transaction.Status.COMPLETED?MUTED:AMBER);
        if(e.fee!=null)label(c,"Fee "+money(e.fee)+(e.feeRefunded?" • refunded":""),12,MUTED);
        for(Ledger.Balance b:e.reports)label(c,b.type+" balance "+money(b.value)+" • "+date(b.at)+" EAT",12,MUTED);
        if(e.sources.size()>1)label(c,e.sources.size()+" confirmations • counted as one record",12,GREEN);
        if(!e.warnings.isEmpty())label(c,"ⓘ Tap to inspect SMS and "+e.warnings.size()+" review notes",12,AMBER);
    }
    private void detail(Ledger.Entry e) {
        ScrollView s=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(18),dp(8),dp(18),dp(18));s.addView(box);
        label(box,e.t.displayAccount()+"\nAmount: "+known(e.amount)+"\nDirection: "+(e.direction==null?"Unknown":e.credit()?"Incoming":"Outgoing")+"\nStatus: "+e.status+"\nCategory: "+e.category+"\nFee: "+known(e.fee)+"\nTime: "+date(e.occurredAt)+" EAT\nInternal transfer: "+(e.internal?"Yes":"No"),14,INK);
        for(String w:e.warnings)label(box,"• "+w,13,AMBER);
        button(box,"Correct category / confirm salary",()->category(e));
        if(e.internal)button(box,"Mark as external / unlink transfer",()->change(()->{try(PennyDatabase db=new PennyDatabase(this)){db.unlink(e,ledger.find(e.peer));}}));
        else if(e.counted())button(box,"Identify internal transfer",()->transfer(e));
        for(Transaction t:e.sources) {
            label(box,"ORIGINAL SMS • "+t.sender+"\nReceived "+date(t.receivedAt)+" EAT\n"+t.rawBody,14,INK);
            label(box,"Parsed account: "+t.account+"\nProvider: "+t.provider+"\nTransaction ID: "+unknown(t.transactionId)+"\nOriginal ID: "+unknown(t.originalId)+"\nCounterparty: "+unknown(t.counterparty)+"\nDescription: "+unknown(t.description)+"\nAmount: "+known(t.amount)+"\nDirection: "+unknown(t.direction)+"\nFee: "+known(t.fee)+"\n"+t.balanceType+" balance: "+known(t.reportedBalance)+"\nStatus: "+t.status+"\n"+t.timeSource+": "+date(t.timestamp)+" EAT",12,MUTED);
            for(Map.Entry<String,BigDecimal> report:t.reportedBalances.entrySet())label(box,"Explicit "+report.getKey()+" balance: "+money(report.getValue()),12,MUTED);
        }
        new AlertDialog.Builder(this).setTitle(e.title).setView(s).setPositiveButton("Close",null).show();
    }
    private void category(Ledger.Entry e) {
        String[] choices={"Uncategorised","Airtime & Data","Food","Bills","Transport","Education","Shopping","Giving","Income (unconfirmed)","Salary (confirmed)"};
        new AlertDialog.Builder(this).setTitle("Confirm category").setItems(choices,(d,i)->change(()->{try(PennyDatabase db=new PennyDatabase(this)){db.correct(e.key,choices[i],e.internal,e.peer);}})).setNegativeButton("Cancel",null).show();
    }
    private void transfer(Ledger.Entry e) {
        List<Ledger.Entry> matches=new ArrayList<>();List<String> labels=new ArrayList<>();
        for(Ledger.Entry other:ledger.entries)if(Ledger.validPair(e,other)&&!other.internal){matches.add(other);labels.add(other.t.displayAccount()+" • "+date(other.occurredAt)+" • "+other.title);}
        labels.add("Confirm one leg only (other SMS missing)");
        new AlertDialog.Builder(this).setTitle("Choose the other account's transaction").setItems(labels.toArray(new String[0]),(d,i)->{
            new AlertDialog.Builder(this).setTitle("Confirm your own-account transfer").setMessage("Confirm that both accounts belong to you. The transfer principal will be excluded from income and spending; explicit fees remain spending. "+(i==matches.size()?"The other leg will remain unpaired.":"Both selected records will be linked.")).setPositiveButton("Confirm",(dd,w)->change(()->{try(PennyDatabase db=new PennyDatabase(this)){if(i==matches.size())db.correct(e.key,e.category,true,null);else db.link(e,matches.get(i));}})).setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Cancel",null).show();
    }
    private void change(Runnable action) {worker.execute(()->{try{action.run();runOnUiThread(this::reload);}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Change failed; records were not updated",Toast.LENGTH_LONG).show());}});}
    private void menu(View anchor) {
        PopupMenu p=new PopupMenu(this,anchor);String[] items={"Import financial SMS","SMS permissions","Export SMS ledger (CSV)","Review flagged records","Privacy and history"};
        for(int i=0;i<items.length;i++)p.getMenu().add(0,i,i,items[i]);
        p.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 0:importSms();break;case 1:requestPermissions(new String[]{Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS},PERMISSION);break;case 2:Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.setType("text/csv");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_TITLE,"penny-sms-ledger.csv");startActivityForResult(intent,EXPORT);break;case 3:reviewList();break;default:new AlertDialog.Builder(this).setTitle("Local SMS ledger").setMessage("Penny has no internet permission. Supported financial SMS and corrections stay on this phone; Android backups are disabled. No email import.\n\nBalances are the last reliable explicit reports, never live. Unknown wallet identifiers are grouped by provider and can be ambiguous.\n\nHistory may be incomplete: deleted SMS, other providers, cash activity and unreported fees are not inferred.\n\n"+legacy+" legacy records are preserved separately and excluded. Reimport SMS after upgrading. If the source SMS was deleted, those records cannot be reconstructed.").setPositiveButton("Close",null).show();}return true;});p.show();
    }
    private void reviewList() {
        List<Ledger.Entry> flagged=new ArrayList<>();for(Ledger.Entry e:ledger.entries)if(!e.warnings.isEmpty())flagged.add(e);
        String[] names=new String[flagged.size()];for(int i=0;i<names.length;i++)names[i]=flagged.get(i).title+" • "+date(flagged.get(i).occurredAt);
        new AlertDialog.Builder(this).setTitle("Records to review ("+names.length+")").setItems(names,(d,i)->detail(flagged.get(i))).setPositiveButton("Close",null).show();
    }
    private void importSms() {
        if(busy)return;
        if(!permission(Manifest.permission.READ_SMS)){requestPermissions(new String[]{Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS},PERMISSION);return;}
        busy=true;error="";notice="";render();
        worker.execute(()->{
            int imported=0;String problem=null;
            try(PennyDatabase db=new PennyDatabase(this)) {
                db.getWritableDatabase().beginTransaction();
                try(Cursor c=getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,new String[]{Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE,Telephony.Sms.DATE_SENT},null,null,Telephony.Sms.DATE+" ASC")) {
                    if(c==null)throw new IllegalStateException("Inbox unavailable");
                    while(c.moveToNext())if(db.insertSms(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3)>0?c.getLong(3):c.getLong(2)))imported++;
                    db.markImport(System.currentTimeMillis());db.getWritableDatabase().setTransactionSuccessful();
                }finally{db.getWritableDatabase().endTransaction();}
            }catch(Exception e){problem="SMS import failed; no partial import was saved. Check SMS permission.";}
            final int added=imported;final String failure=problem;
            runOnUiThread(()->{if(!isDestroyed()){busy=false;if(failure!=null)error=failure;else notice="Imported "+added+" new financial SMS. Confirmations reconciled below.";reload();}});
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==PERMISSION){notice=permission(Manifest.permission.READ_SMS)?"SMS access granted. Choose Import financial SMS from the menu.":"SMS access denied. Existing local records remain available.";render();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==EXPORT&&result==RESULT_OK&&data!=null&&data.getData()!=null)export(data.getData());}
    private void export(Uri uri) {
        final Ledger data=ledger;
        worker.execute(()->{
            try(OutputStream out=getContentResolver().openOutputStream(uri)) {
                if(out==null)throw new IllegalStateException();
                StringBuilder csv=new StringBuilder("date_EAT,provider,account,description,direction,status,amount_UGX,fee_UGX,category,internal_transfer,transaction_id,confirmations,reported_balances,review_notes\n");
                for(Ledger.Entry e:data.entries)csv.append(csv(date(e.occurredAt))).append(',').append(csv(e.t.provider)).append(',').append(csv(Transaction.mask(e.t.account))).append(',').append(csv(e.title)).append(',').append(csv(e.direction)).append(',').append(e.status).append(',').append(e.amount==null?"":e.amount.toPlainString()).append(',').append(e.fee==null?"":e.fee.toPlainString()).append(',').append(csv(e.category)).append(',').append(e.internal).append(',').append(csv(e.t.transactionId)).append(',').append(e.sources.size()).append(',').append(csv(reportSummary(e))).append(',').append(csv(String.join("; ",e.warnings))).append('\n');
                out.write(csv.toString().getBytes(StandardCharsets.UTF_8));runOnUiThread(()->Toast.makeText(this,"SMS ledger exported",Toast.LENGTH_SHORT).show());
            }catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Export failed",Toast.LENGTH_LONG).show());}
        });
    }
    private String csv(String s){if(s==null)s="";if(s.matches("^[=+@\\-\\t\\r].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    private String reportSummary(Ledger.Entry e){List<String> values=new ArrayList<>();for(Ledger.Balance b:e.reports)values.add(b.type+" "+b.value.toPlainString()+" at "+date(b.at)+" EAT");return String.join("; ",values);}
    private void pickDates() {
        Calendar c=Calendar.getInstance(zone);DatePickerDialog firstPicker=new DatePickerDialog(this,(v,y,m,d)->{
            Calendar first=Calendar.getInstance(zone);first.clear();first.set(y,m,d);
            DatePickerDialog lastPicker=new DatePickerDialog(this,(v2,y2,m2,d2)->{Calendar last=Calendar.getInstance(zone);last.clear();last.set(y2,m2,d2);last.add(Calendar.DAY_OF_MONTH,1);if(last.getTimeInMillis()<=first.getTimeInMillis()){Toast.makeText(this,"End date must follow start date",Toast.LENGTH_LONG).show();render();return;}customStart=first.getTimeInMillis();customEnd=last.getTimeInMillis();period="Custom Dates";limit=40;render();},y,m,d);
            lastPicker.setTitle("End date (inclusive)");lastPicker.setOnCancelListener(ignored->render());lastPicker.show();
        },c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH));firstPicker.setTitle("Start date");firstPicker.setOnCancelListener(ignored->render());firstPicker.show();
    }
    private long[] window() {
        if(period.equals("Custom Dates")&&customEnd>customStart)return new long[]{customStart,customEnd};
        Calendar start=Calendar.getInstance(zone);start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);
        if(period.equals("This Month"))start.set(Calendar.DAY_OF_MONTH,1);
        if(period.equals("This Week"))start.add(Calendar.DAY_OF_MONTH,-((start.get(Calendar.DAY_OF_WEEK)+5)%7));
        Calendar end=(Calendar)start.clone();if(period.equals("This Month"))end.add(Calendar.MONTH,1);else end.add(Calendar.DAY_OF_MONTH,period.equals("This Week")?7:1);
        return new long[]{start.getTimeInMillis(),end.getTimeInMillis()};
    }
    private Spinner spinner(String[] values,String selected){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));s.setSelection(Arrays.asList(values).indexOf(selected));s.setContentDescription(selected+" filter");return s;}
    abstract static class Choice implements AdapterView.OnItemSelectedListener{public void onNothingSelected(AdapterView<?> a){}public void onItemSelected(AdapterView<?> a,View v,int p,long id){chosen(a.getItemAtPosition(p).toString());}public abstract void chosen(String s);}
    private boolean permission(String p){return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
    private String money(BigDecimal n){NumberFormat f=NumberFormat.getNumberInstance(Locale.US);f.setMaximumFractionDigits(2);return "UGX "+f.format(n);}
    private String known(BigDecimal n){return n==null?"Unknown":money(n);}
    private String unknown(String s){return s==null?"Unknown":s;}
    private String date(long at){SimpleDateFormat f=new SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.US);f.setTimeZone(zone);return f.format(new Date(at));}
    private String shortDate(long at){SimpleDateFormat f=new SimpleDateFormat("dd MMM yyyy",Locale.US);f.setTimeZone(zone);return f.format(new Date(at));}
    private String freshness(long at){long hours=Math.max(0,(System.currentTimeMillis()-at)/3600000L);return hours<1?"less than an hour old":hours<24?hours+" hours old":hours/24+" days old";}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(18));return b;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(LinearLayout parent,int color){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(16),dp(14),dp(16),dp(14));l.setBackground(bg(color));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);parent.addView(l,lp);return l;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(3),0,dp(3));if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private void label(LinearLayout p,String s,int size,int color){label(p,s,size,color,false);}
    private void label(LinearLayout p,String s,int size,int color,boolean bold){p.addView(text(s,size,color,bold));}
    private void heading(String s,int size){TextView t=text(s,size,INK,true);t.setPadding(0,dp(22),0,dp(5));content.addView(t);}
    private void button(LinearLayout p,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(GREEN);p.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
}
