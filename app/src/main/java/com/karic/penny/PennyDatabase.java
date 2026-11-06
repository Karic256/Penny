package com.karic.penny;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.*;

/** Raw financial SMS are retained locally so parsing can be audited and upgraded. */
public class PennyDatabase extends SQLiteOpenHelper {
    public PennyDatabase(Context context) { super(context,"penny.db",null,2); }
    @Override public void onCreate(SQLiteDatabase db) { createSmsTables(db); }
    private void createSmsTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS financial_sms (source_key TEXT PRIMARY KEY, sender TEXT NOT NULL, body TEXT NOT NULL, received_at INTEGER NOT NULL, source_time INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS sms_time ON financial_sms(received_at)");
        db.execSQL("CREATE TABLE IF NOT EXISTS corrections (entry_key TEXT PRIMARY KEY, category TEXT, internal INTEGER NOT NULL DEFAULT 0, peer TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        // V1 discarded raw SMS and represented unknown balances as zero. Preserve it as an archive,
        // but never mix those unreliable rows (including email receipts) into the SMS ledger.
        if(oldVersion<2)createSmsTables(db);
    }
    public boolean insertSms(String sender,String body,long receivedAt) {
        return insertSms(sender,body,receivedAt,receivedAt);
    }
    public boolean insertSms(String sender,String body,long receivedAt,long sourceTime) {
        Transaction t=TransactionParser.parse(sender,body,receivedAt,sourceTime);
        if(t==null)return false;
        ContentValues v=new ContentValues();v.put("source_key",t.sourceKey);v.put("sender",t.sender);v.put("body",body);v.put("received_at",receivedAt);v.put("source_time",sourceTime);
        return getWritableDatabase().insertWithOnConflict("financial_sms",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    public Ledger ledger() {
        List<Transaction> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT sender,body,received_at,source_time FROM financial_sms ORDER BY received_at,source_key",null)) {
            while(c.moveToNext()) {Transaction t=TransactionParser.parse(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3));if(t!=null)rows.add(t);}
        }
        Map<String,Ledger.Edit> edits=new HashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT entry_key,category,internal,peer FROM corrections",null)) {
            while(c.moveToNext())edits.put(c.getString(0),new Ledger.Edit(c.getString(1),c.getInt(2)!=0,c.getString(3)));
        }
        return new Ledger(rows,edits);
    }
    public void correct(String key,String category,boolean internal,String peer) {
        ContentValues v=new ContentValues();v.put("entry_key",key);v.put("category",category);v.put("internal",internal?1:0);v.put("peer",peer);
        getWritableDatabase().insertWithOnConflict("corrections",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void link(Ledger.Entry a,Ledger.Entry b) {
        if(!Ledger.validPair(a,b))throw new IllegalArgumentException("Select opposite matching transactions in different accounts");
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();
        try {correct(a.key,a.category,true,b.key);correct(b.key,b.category,true,a.key);sql.setTransactionSuccessful();}finally{sql.endTransaction();}
    }
    public void unlink(Ledger.Entry e,Ledger.Entry peer) {
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();
        try {correct(e.key,e.category,false,null);if(peer!=null)correct(peer.key,peer.category,false,null);sql.setTransactionSuccessful();}finally{sql.endTransaction();}
    }
    public int legacyCount() {
        try(Cursor exists=getReadableDatabase().rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='transactions'",null)) {
            if(!exists.moveToFirst())return 0;
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM transactions",null)){return c.moveToFirst()?c.getInt(0):0;}
    }
    public long lastImport() {try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE name='last_import'",null)){return c.moveToFirst()?Long.parseLong(c.getString(0)):0;}}
    public void markImport(long at) {ContentValues v=new ContentValues();v.put("name","last_import");v.put("value",Long.toString(at));getWritableDatabase().insertWithOnConflict("metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
}
