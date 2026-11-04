package com.karic.penny;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TransactionParser {
    private static final Pattern MONEY = Pattern.compile("(?i)\\b(?:UGX|UShs?|Shs?)\\s*([0-9]{1,3}(?:[, ][0-9]{3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern FEE = Pattern.compile("(?i)(?:fee|charge|charges|transaction cost)\\D{0,12}(?:UGX|UShs?|Shs?)?\\s*([0-9, ]+)");
    private static final Pattern BALANCE = Pattern.compile("(?i)(?:balance|bal\\.?|available balance)\\D{0,16}(?:UGX|UShs?|Shs?)?\\s*([0-9, ]+)");
    private static final Pattern REFERENCE = Pattern.compile("(?i)(?:ref(?:erence)?|transaction id|txn id|id)[:#\\s-]*([A-Z0-9]{5,})");

    private TransactionParser() {}

    public static Transaction parse(String sender, String body, long timestamp) {
        if (body == null) return null;
        String lower = body.toLowerCase(Locale.ROOT);
        boolean moneySignal = lower.contains("ugx") || lower.contains("ush") || lower.contains("shs") || lower.contains("balance");
        boolean transactionSignal = containsAny(lower, "sent", "paid", "withdraw", "purchase", "debited", "debit", "received", "credited", "deposit", "cash out", "cash in", "transfer");
        if (!moneySignal || !transactionSignal) return null;
        if (containsAny(lower, "otp", "one time password", "verification code", "pin is")) return null;

        Transaction tx = new Transaction();
        tx.sender = sender == null ? "Unknown" : sender;
        tx.timestamp = timestamp;
        tx.fee = findNumber(FEE, body);
        tx.balance = findNumber(BALANCE, body);
        tx.reference = findText(REFERENCE, body);
        tx.rawHash = sha256(tx.sender + "|" + timestamp + "|" + body);

        if (containsAny(lower, "received", "credited", "deposit", "cash in")) {
            tx.type = "INCOME";
            tx.category = "Income";
        } else {
            tx.type = "EXPENSE";
            tx.category = guessCategory(lower);
        }

        Matcher matcher = MONEY.matcher(body);
        while (matcher.find()) {
            double candidate = cleanNumber(matcher.group(1));
            String prefix = body.substring(Math.max(0, matcher.start() - 32), matcher.start()).toLowerCase(Locale.ROOT);
            boolean metadata = prefix.matches("(?s).*(?:balance|bal\\.?|fee|charges?|transaction cost)\\s*[:=]?\\s*$");
            if (candidate > 0 && !metadata) {
                tx.amount = candidate;
                break;
            }
        }
        if (tx.amount <= 0) return null;
        tx.counterparty = guessCounterparty(body, lower);
        return tx;
    }

    private static String guessCategory(String lower) {
        if (containsAny(lower, "airtime", "data bundle", "bundle")) return "Airtime & Data";
        if (containsAny(lower, "electricity", "umeme", "water", "nwsc", "bill")) return "Bills";
        if (containsAny(lower, "fuel", "petrol", "transport", "taxi", "uber", "safe boda")) return "Transport";
        if (containsAny(lower, "supermarket", "restaurant", "food", "cafe")) return "Food";
        if (containsAny(lower, "school", "tuition")) return "Education";
        if (containsAny(lower, "church", "offering", "tithe")) return "Giving";
        if (containsAny(lower, "withdraw", "cash out")) return "Cash Withdrawal";
        return "Uncategorised";
    }

    private static String guessCounterparty(String body, String lower) {
        String[] markers = {" to ", " from ", " at "};
        for (String marker : markers) {
            int i = lower.indexOf(marker);
            if (i >= 0) {
                String tail = body.substring(i + marker.length()).trim();
                tail = tail.split("[.\\n,]")[0].trim();
                if (tail.length() > 35) tail = tail.substring(0, 35);
                if (!tail.isEmpty()) return tail;
            }
        }
        return "Transaction";
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
    private static double findNumber(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text); return m.find() ? cleanNumber(m.group(1)) : 0;
    }
    private static String findText(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text); return m.find() ? m.group(1) : "";
    }
    private static double cleanNumber(String value) {
        try { return Double.parseDouble(value.replace(",", "").replace(" ", "")); }
        catch (Exception e) { return 0; }
    }
    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { return String.valueOf(value.hashCode()); }
    }
}
