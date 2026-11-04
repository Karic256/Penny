package com.karic.penny;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class PennyDatabase extends SQLiteOpenHelper {
    public PennyDatabase(Context context) { super(context, "penny.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT, type TEXT NOT NULL, amount REAL NOT NULL, fee REAL DEFAULT 0, balance REAL DEFAULT 0, counterparty TEXT, category TEXT, timestamp INTEGER NOT NULL, reference TEXT, raw_hash TEXT UNIQUE NOT NULL)");
        db.execSQL("CREATE INDEX idx_transactions_time ON transactions(timestamp DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public boolean insert(Transaction tx) {
        ContentValues v = new ContentValues();
        v.put("sender", tx.sender); v.put("type", tx.type); v.put("amount", tx.amount); v.put("fee", tx.fee);
        v.put("balance", tx.balance); v.put("counterparty", tx.counterparty); v.put("category", tx.category);
        v.put("timestamp", tx.timestamp); v.put("reference", tx.reference); v.put("raw_hash", tx.rawHash);
        return getWritableDatabase().insertWithOnConflict("transactions", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<Transaction> recent(int limit) {
        List<Transaction> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id,sender,type,amount,fee,balance,counterparty,category,timestamp,reference,raw_hash FROM transactions ORDER BY timestamp DESC LIMIT ?", new String[]{String.valueOf(limit)});
        while (c.moveToNext()) list.add(fromCursor(c)); c.close(); return list;
    }

    public double[] monthTotals(long start, long end) {
        Cursor c = getReadableDatabase().rawQuery("SELECT type,COALESCE(SUM(amount + CASE WHEN type='EXPENSE' THEN fee ELSE 0 END),0) FROM transactions WHERE timestamp>=? AND timestamp<? GROUP BY type", new String[]{String.valueOf(start), String.valueOf(end)});
        double income=0, expense=0; while(c.moveToNext()) { if("INCOME".equals(c.getString(0))) income=c.getDouble(1); else expense=c.getDouble(1); } c.close(); return new double[]{income,expense};
    }

    public Cursor allForExport() { return getReadableDatabase().rawQuery("SELECT timestamp,sender,type,amount,fee,balance,counterparty,category,reference FROM transactions ORDER BY timestamp DESC", null); }

    private Transaction fromCursor(Cursor c) {
        Transaction t = new Transaction(); t.id=c.getLong(0); t.sender=c.getString(1); t.type=c.getString(2); t.amount=c.getDouble(3); t.fee=c.getDouble(4); t.balance=c.getDouble(5); t.counterparty=c.getString(6); t.category=c.getString(7); t.timestamp=c.getLong(8); t.reference=c.getString(9); t.rawHash=c.getString(10); return t;
    }
}
