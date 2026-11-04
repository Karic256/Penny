package com.karic.penny;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Date;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Explicit, local receipt import. No inbox access or network permission. */
public class EmailImportActivity extends Activity {
    private EditText receipt, amount, merchant, date;
    private Spinner kind;
    private Button save;
    private String reviewedText;
    private Transaction candidate;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root); setContentView(scroll);
        TextView heading = new TextView(this);
        heading.setText("Import an email receipt"); heading.setTextSize(24); root.addView(heading);
        TextView help = new TextView(this);
        help.setText("Copy the receipt text from Gmail and paste it here. Penny reads only this text, on your phone. Review the amount and original transaction date before saving. UGX receipts only; automatic Gmail sync is not connected.");
        root.addView(help);
        receipt = field(root, "Paste receipt text", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        receipt.setMinLines(5);
        Button detect = new Button(this); detect.setText("Review receipt"); root.addView(detect);
        merchant = field(root, "Merchant or description", InputType.TYPE_CLASS_TEXT);
        amount = field(root, "Amount (UGX)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        date = field(root, "Transaction date (yyyy-MM-dd)", InputType.TYPE_CLASS_DATETIME);
        kind = new Spinner(this);
        kind.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Expense", "Income"})); root.addView(kind);
        save = new Button(this); save.setText("Save reviewed transaction"); save.setEnabled(false); root.addView(save);
        Button cancel = new Button(this); cancel.setText("Cancel"); root.addView(cancel); cancel.setOnClickListener(v -> finish());
        detect.setOnClickListener(v -> review()); save.setOnClickListener(v -> persist());
    }

    private EditText field(LinearLayout root, String label, int type) {
        TextView title = new TextView(this); title.setText(label); root.addView(title);
        EditText edit = new EditText(this); edit.setInputType(type); edit.setContentDescription(label); root.addView(edit); return edit;
    }

    private void review() {
        reviewedText = receipt.getText().toString().trim();
        if (reviewedText.isEmpty()) { receipt.setError("Paste a receipt first"); return; }
        candidate = TransactionParser.parse("Email receipt", reviewedText, System.currentTimeMillis());
        amount.setText(candidate == null ? "" : String.valueOf(candidate.amount + ("EXPENSE".equals(candidate.type) ? candidate.fee : 0)));
        merchant.setText(candidate == null ? "" : candidate.counterparty);
        kind.setSelection(candidate != null && "INCOME".equals(candidate.type) ? 1 : 0);
        // An email may describe an older purchase: never silently use today's date.
        date.setText(""); save.setEnabled(true);
        Toast.makeText(this, "Check the amount and enter the receipt's transaction date", Toast.LENGTH_LONG).show();
    }

    private void persist() {
        if (!receipt.getText().toString().trim().equals(reviewedText)) {
            save.setEnabled(false); Toast.makeText(this, "Receipt changed. Review it again.", Toast.LENGTH_LONG).show(); return;
        }
        double value;
        try { value = Double.parseDouble(amount.getText().toString()); }
        catch (Exception e) { amount.setError("Enter a valid amount"); return; }
        if (!Double.isFinite(value) || value <= 0) { amount.setError("Enter an amount greater than zero"); return; }
        String description = merchant.getText().toString().trim();
        if (description.isEmpty()) { merchant.setError("Enter a merchant or description"); return; }
        String dateText = date.getText().toString().trim();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US); fmt.setLenient(false);
        Date parsed;
        try { parsed = fmt.parse(dateText); if (parsed == null || !fmt.format(parsed).equals(dateText)) throw new Exception(); }
        catch (Exception e) { date.setError("Use a valid date: yyyy-MM-dd"); return; }
        Transaction tx = candidate == null ? new Transaction() : candidate;
        tx.sender = "Email receipt"; tx.amount = value; tx.timestamp = parsed.getTime();
        tx.counterparty = description; tx.type = kind.getSelectedItemPosition() == 0 ? "EXPENSE" : "INCOME";
        tx.category = "INCOME".equals(tx.type) ? "Income" : "Uncategorised";
        // Fee must be included in the reviewed total, avoiding hidden charges.
        tx.fee = 0; tx.balance = 0; tx.reference = candidate == null ? "" : candidate.reference;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(reviewedText.replaceAll("\\s+", " ").getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("email:"); for (byte b : hash) key.append(String.format("%02x", b)); tx.rawHash = key.toString();
        } catch (Exception e) { Toast.makeText(this, "Could not identify receipt", Toast.LENGTH_LONG).show(); return; }
        try (PennyDatabase db = new PennyDatabase(this)) {
            boolean added = db.insert(tx);
            Toast.makeText(this, added ? "Transaction saved" : "This receipt was already imported", Toast.LENGTH_LONG).show();
            if (added) finish();
        }
    }
}
