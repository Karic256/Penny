package com.karic.penny;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int SMS_PERMISSION = 101;
    private static final int CREATE_CSV = 102;

    private PennyDatabase db;
    private TextView balance;
    private TextView income;
    private TextView expense;
    private TextView empty;
    private TextView permissionStatus;
    private LinearLayout list;
    private Button permissionButton;
    private final NumberFormat money = NumberFormat.getNumberInstance(Locale.US);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        db = new PennyDatabase(this);
        balance = findViewById(R.id.txtBalance);
        income = findViewById(R.id.txtIncome);
        expense = findViewById(R.id.txtExpense);
        empty = findViewById(R.id.txtEmpty);
        permissionStatus = findViewById(R.id.txtPermissionStatus);
        list = findViewById(R.id.transactionList);
        permissionButton = findViewById(R.id.btnPermission);
        Button importButton = findViewById(R.id.btnImport);
        Button exportButton = findViewById(R.id.btnExport);

        permissionButton.setOnClickListener(v -> requestSmsPermission());
        importButton.setOnClickListener(v -> importSms());
        exportButton.setOnClickListener(v -> createCsv());
        findViewById(R.id.btnEmail).setOnClickListener(v ->
                startActivity(new Intent(this, EmailImportActivity.class)));

        updatePermissionUi();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (db != null) {
            updatePermissionUi();
            refresh();
        }
    }

    private void requestSmsPermission() {
        requestPermissions(
                new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS},
                SMS_PERMISSION
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != SMS_PERMISSION) return;

        updatePermissionUi();
        if (hasReadSmsPermission()) {
            Toast.makeText(this, "SMS access allowed. You can now import alerts.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Penny cannot track SMS transactions without permission.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasReadSmsPermission() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasReceiveSmsPermission() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void updatePermissionUi() {
        boolean read = hasReadSmsPermission();
        boolean receive = hasReceiveSmsPermission();
        if (read && receive) {
            permissionStatus.setText("SMS tracking is active");
            permissionButton.setText("SMS access allowed");
            permissionButton.setEnabled(false);
        } else {
            permissionStatus.setText("SMS access is required for automatic tracking");
            permissionButton.setText("Allow SMS access");
            permissionButton.setEnabled(true);
        }
    }

    private void importSms() {
        if (!hasReadSmsPermission()) {
            Toast.makeText(this, "Allow SMS access first", Toast.LENGTH_SHORT).show();
            requestSmsPermission();
            return;
        }

        int imported = 0;
        ContentResolver resolver = getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                    null,
                    null,
                    Telephony.Sms.DATE + " DESC"
            );
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    Transaction tx = TransactionParser.parse(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getLong(2)
                    );
                    if (tx != null && db.insert(tx)) imported++;
                }
            }
            Toast.makeText(this, "Imported " + imported + " new financial transactions", Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "SMS permission was denied", Toast.LENGTH_LONG).show();
        } finally {
            if (cursor != null) cursor.close();
        }
        refresh();
    }

    private void refresh() {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.DAY_OF_MONTH, 1);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.MONTH, 1);

        double[] totals = db.monthTotals(start.getTimeInMillis(), end.getTimeInMillis());
        balance.setText("UGX " + money.format(totals[0] - totals[1]));
        income.setText("Income\nUGX " + money.format(totals[0]));
        expense.setText("Spent\nUGX " + money.format(totals[1]));

        List<Transaction> rows = db.recent(50);
        list.removeAllViews();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        for (Transaction transaction : rows) addTransaction(transaction);
    }

    private void addTransaction(Transaction transaction) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.card_bg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        TextView title = new TextView(this);
        title.setText(transaction.counterparty == null ? "Transaction" : transaction.counterparty);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));

        TextView amountText = new TextView(this);
        double displayedAmount = transaction.amount + ("EXPENSE".equals(transaction.type) ? transaction.fee : 0);
        amountText.setText(("INCOME".equals(transaction.type) ? "+ " : "− ") + "UGX " + money.format(displayedAmount));
        amountText.setTextSize(18);
        amountText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        amountText.setTextColor(getColor("INCOME".equals(transaction.type) ? R.color.penny_green : R.color.penny_red));

        TextView meta = new TextView(this);
        String feeText = transaction.fee > 0 ? " • Fee UGX " + money.format(transaction.fee) : "";
        meta.setText(transaction.category + " • " +
                new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(new Date(transaction.timestamp)) +
                "\n" + transaction.sender + feeText);
        meta.setTextColor(getColor(android.R.color.darker_gray));
        meta.setTextSize(13);

        card.addView(title);
        card.addView(amountText);
        card.addView(meta);
        list.addView(card);
    }

    private void createCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "penny-transactions.csv");
        startActivityForResult(intent, CREATE_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_CSV && resultCode == RESULT_OK && data != null) {
            exportCsv(data.getData());
        }
    }

    private void exportCsv(Uri uri) {
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("Could not open the selected file");
            StringBuilder csv = new StringBuilder("date,sender,type,amount,fee,balance,counterparty,category,reference\n");
            Cursor cursor = db.allForExport();
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            while (cursor.moveToNext()) {
                csv.append(csv(formatter.format(new Date(cursor.getLong(0))))).append(',')
                        .append(csv(cursor.getString(1))).append(',')
                        .append(csv(cursor.getString(2))).append(',')
                        .append(cursor.getDouble(3)).append(',')
                        .append(cursor.getDouble(4)).append(',')
                        .append(cursor.getDouble(5)).append(',')
                        .append(csv(cursor.getString(6))).append(',')
                        .append(csv(cursor.getString(7))).append(',')
                        .append(csv(cursor.getString(8))).append('\n');
            }
            cursor.close();
            output.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
