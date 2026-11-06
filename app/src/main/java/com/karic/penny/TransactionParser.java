package com.karic.penny;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.*;
import java.util.regex.*;

/** Extend ADAPTERS for additional providers; never guess an amount from bare digits. */
public final class TransactionParser {
    private static final String NUM = "([+-]?(?:[0-9]{1,3}(?:[, ][0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?)";
    private static final String CCY = "(?:(?:UGX|UShs?|Shs?)\\s*)+";
    interface Adapter { boolean accepts(String sender, String body); void parse(Transaction t, String body); }
    private static final List<Adapter> ADAPTERS = Arrays.asList(new Centenary(), new Mtn());
    private TransactionParser() {}

    public static Transaction parse(String sender, String body, long receivedAt) {
        return parse(sender,body,receivedAt,receivedAt);
    }
    public static Transaction parse(String sender, String body, long receivedAt,long sourceTime) {
        if (body == null || body.trim().isEmpty()) return null;
        String text = body.replace('\u00a0', ' ').replace('\u2212', '-').replaceAll("[\\t\\r\\n]+", " ").replaceAll("([+-])\\s+(?=\\d)", "$1");
        if (has(text, "\\b(?:OTP|one[ -]time password|verification code|PIN is)\\b")) return null;
        Adapter adapter = null;
        for (Adapter a : ADAPTERS) if (a.accepts(sender == null ? "" : sender, text)) { adapter = a; break; }
        if (adapter == null) return null;
        Transaction t = new Transaction();
        t.sender = sender == null ? "Unknown sender" : sender;
        t.rawBody = body; t.receivedAt = receivedAt; t.timestamp = receivedAt;
        t.account = "unknown"; t.category = "Uncategorised";
        t.sourceKey = hash(t.sender.toLowerCase(Locale.ROOT) + "|" + sourceTime + "|" + body.trim().replaceAll("\\s+", " "));
        t.status = status(text);
        t.originalId = find("(?:original|reversal of|settlement of)(?:\\s+transaction)?(?:\\s+(?:id|ref(?:erence)?))?\\s*[:#=-]?\\s*([A-Z0-9][A-Z0-9/-]{4,})", text);
        // Remove relationship wording before looking for this event's own ID.
        String own = text.replaceAll("(?i)(?:original|reversal of|settlement of)(?:\\s+transaction)?(?:\\s+(?:id|ref(?:erence)?))?\\s*[:#=-]?\\s*[A-Z0-9][A-Z0-9/-]{4,}", "");
        t.transactionId = find("(?:transaction\\s*(?:id|number|no\\.?)|txn\\s*id|trxn\\s*id|txid|ref(?:erence)?(?:\\s*(?:no\\.?|number))?)\\s*[:#=-]?\\s*([A-Z0-9][A-Z0-9/-]{4,})", own);
        t.fee = number("(?:fee|charges?|transaction cost)(?:\\s+of|\\s+is)?\\s*[:=]?\\s*(?:" + CCY + ")?" + NUM, text);
        t.feeRefunded = has(text, "fees? (?:also )?(?:refunded|reversed)");
        Matcher bm = pattern("((?:new|available|ledger|current|closing)\\s+balance|balance|bal\\.?)\\s*(?:is)?\\s*[:=]?\\s*(?:" + CCY + ")?" + NUM).matcher(text);
        if (bm.find()) {
            t.reportedBalance = decimal(bm.group(2));
            String label = bm.group(1).toLowerCase(Locale.ROOT);
            t.balanceType = label.contains("available") ? "Available" : label.contains("ledger") ? "Ledger" : "Reported";
        }
        extractTime(t, text);
        adapter.parse(t, text);
        if (t.amount != null) t.amount = t.amount.abs();
        if (t.fee != null && t.fee.signum() < 0) { t.fee = null; t.warnings.add("Negative fee needs review"); }
        t.transferHint = has(text, "\\b(?:transfer|sent|send|ebanking)\\b");
        if (t.status == Transaction.Status.UNKNOWN) t.warnings.add("Completion not established by this SMS");
        if (t.status != Transaction.Status.APPROVAL && t.amount == null && t.reportedBalance == null) return null;
        if (t.amount == null && t.status != Transaction.Status.APPROVAL) t.warnings.add("Transaction amount unknown");
        if (t.direction == null && t.status != Transaction.Status.APPROVAL && t.status != Transaction.Status.REVERSED) t.warnings.add("Direction unknown");
        if (t.transactionId == null) t.warnings.add("No transaction ID; separate messages are not merged automatically");
        if ("unknown".equals(t.account)) t.warnings.add("Account identifier absent; grouped by provider only. Confirm this is one wallet/account.");
        return t;
    }
    private static class Centenary implements Adapter {
        public boolean accepts(String s, String b) { return has(s,"centenary|centebank|centemobile") || has(b,"\\bcentenary\\b"); }
        public void parse(Transaction t, String b) {
            t.provider = "Centenary Bank"; t.accountKind = "Bank";
            String a = find("(?:A/C|acc(?:ount)?(?:\\s*(?:no\\.?|number))?)\\s*[:#]?\\s*([*Xx•]*[0-9][*Xx•0-9-]{1,})", b);
            if (a != null) t.account = a.replaceAll("[*Xx•]+", "*").replace("-", "");
            String signed = find("(?:trxn|transaction)\\s+of\\s*(?:" + CCY + ")?" + NUM, b);
            if (signed != null) { t.amount = decimal(signed); t.direction = signed.trim().startsWith("-") ? "OUT" : "IN"; }
            if (t.amount == null) { t.amount = actionAmount(b); t.direction = direction(b); }
            String payer = find("\\((EFT[^)]*)\\)", b);
            t.counterparty = payer != null ? payer.trim() : counterparty(b);
            t.description = payer != null ? payer : find("(?:description|narration|message)\\s*:\\s*(.+?)(?=\\s+(?:new |available )?bal(?:ance)?\\b|$)", b);
            t.category = t.isCredit() ? "Income (unconfirmed)" : category(b);
            if (t.description == null) t.description = t.counterparty != null ? t.counterparty : has(b,"funds transfer") ? "Funds Transfer / Ebanking" : "Bank " + (t.isCredit() ? "credit" : "debit");
            if (payer != null) t.warnings.add("Incoming EFT payer retained; salary not confirmed");
        }
    }
    private static class Mtn implements Adapter {
        public boolean accepts(String s, String b) { return has(s,"\\bmtn\\b|mtnmomo|mobilemoney") || has(b,"\\bMTN\\b|MoMo|mobile money"); }
        public void parse(Transaction t, String b) {
            t.provider = "MTN"; t.accountKind = "Mobile Money";
            String a = find("(?:your (?:mobile money |momo )?(?:account|wallet)|A/C)\\s*[:#]?\\s*([*Xx•]*[0-9][*Xx•0-9]{2,})", b);
            if (a != null) t.account = a.replaceAll("[*Xx•]+", "*");
            t.amount = actionAmount(b); t.direction = direction(b); t.counterparty = counterparty(b);
            t.description = find("message\\s*:\\s*(.+?)(?=\\s*(?:[.;]\\s*)?(?:new balance|available balance|balance|transaction id|txn id)\\b|$)", b);
            t.category = t.isCredit() ? "Income (unconfirmed)" : category(b);
            if (has(b,"bundle purchase|data bundle|airtime")) { t.description = has(b,"bundle") ? "MTN Data Bundle" : "MTN Airtime"; t.category = "Airtime & Data"; }
            if (t.description == null) t.description = t.counterparty != null ? t.counterparty : "Mobile money " + (t.isCredit() ? "credit" : "payment");
        }
    }
    private static BigDecimal actionAmount(String b) {
        BigDecimal n = number("(?:deducted|debited|credited|paid|sent|received|withdrawn|deposited|purchase(?:d)?(?:\\s+of)?|payment(?:\\s+of)?|amount|transferred|reversed|refunded)\\s*(?:of|with|is)?\\s*[:=]?\\s*" + CCY + NUM, b);
        if (n == null) n = number(CCY + NUM + "\\s*(?:has been|was|is)?\\s*(?:deducted|debited|credited|paid|sent|received|withdrawn|transferred|reversed|refunded)", b);
        return n;
    }
    private static String direction(String b) {
        if (has(b,"\\b(?:received|credited|deposited|cash in)\\b")) return "IN";
        if (has(b,"\\b(?:deducted|debited|paid|sent|withdrawn|purchase|payment|cash out)\\b")) return "OUT";
        return null;
    }
    private static Transaction.Status status(String b) {
        if (has(b,"my approvals|to allow payment|approve (?:this |the )?(?:payment|transaction)|payment request")) return Transaction.Status.APPROVAL;
        if (has(b,"\\b(?:failed|declined|unsuccessful|cancelled|canceled)\\b")) return Transaction.Status.FAILED;
        if (has(b,"\\b(?:reversed|refunded|reversal successful|reversal completed)\\b")) return Transaction.Status.REVERSED;
        if (has(b,"pending settlement|\\bpending\\b|awaiting settlement|authori[sz]ation hold")) return Transaction.Status.PENDING;
        if (has(b,"\\b(?:trxn of|deducted|debited|credited|paid|sent|received|withdrawn|deposited|successful|completed|settled)\\b")) return Transaction.Status.COMPLETED;
        return Transaction.Status.UNKNOWN;
    }
    private static String counterparty(String b) {
        String v = find("\\b(?:to|from|at)\\s+(?!a fee|a charge|\\d{1,2}:|your (?:mobile money )?account)(.+?)(?=\\s+(?:on\\s+\\d|at\\s+\\d|at a fee|fee|new balance|available balance|bal:|transaction id|txn id|ref:)\\b|[.;]|$)", b);
        return v == null ? null : v.trim();
    }
    private static String category(String b) {
        if (has(b,"airtime|bundle")) return "Airtime & Data";
        if (has(b,"umeme|electricity|nwsc|water bill")) return "Bills";
        if (has(b,"fuel|petrol|taxi|uber|boda")) return "Transport";
        if (has(b,"supermarket|restaurant|cafe|food")) return "Food";
        if (has(b,"tuition|school fees")) return "Education";
        return "Uncategorised";
    }
    private static void extractTime(Transaction t, String b) {
        String d = find("\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[- ]\\d{2,4})\\b", b);
        if (d == null) return;
        String tm = find("\\b(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AP]M)?)\\b", b);
        if (tm == null) { t.warnings.add("Date has no time; SMS received time used for ordering"); return; }
        String value = d + " " + tm.replaceAll("(?i)\\s*([AP]M)", " $1").toUpperCase(Locale.US);
        String[] ds = {"dd-MM-yyyy","dd/MM/yyyy","yyyy-MM-dd","dd-MM-yy","dd/MM/yy","dd-MMM-yyyy","dd MMM yyyy","dd-MMM-yy","dd MMM yy","dd MMMM yyyy"};
        for (String df : ds) for (String tf : new String[]{"HH:mm:ss","HH:mm","hh:mm:ss a","hh:mm a"}) {
            SimpleDateFormat f = new SimpleDateFormat(df + " " + tf, Locale.US); f.setLenient(false); f.setTimeZone(TimeZone.getTimeZone("Africa/Kampala"));
            f.set2DigitYearStart(new GregorianCalendar(2000,0,1).getTime());
            ParsePosition p = new ParsePosition(0); Date parsed = f.parse(value,p);
            if (parsed != null && p.getIndex() == value.length()) {
                Calendar year=Calendar.getInstance(TimeZone.getTimeZone("Africa/Kampala"));year.setTime(parsed);
                if(year.get(Calendar.YEAR)<2000)continue;
                if (parsed.getTime() > t.receivedAt + 86400000L) { t.warnings.add("Future transaction date; SMS received time used"); return; }
                t.timestamp = parsed.getTime(); t.timeSource = "Transaction time (East Africa)"; return;
            }
        }
        t.warnings.add("Invalid date/time; SMS received time used");
    }
    static BigDecimal decimal(String s) { try { return new BigDecimal(s.replace(",", "").replace(" ", "")); } catch (Exception e) { return null; } }
    static Pattern pattern(String r) { return Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    static boolean has(String b,String r) { return pattern(r).matcher(b).find(); }
    static String find(String r,String b) { Matcher m=pattern(r).matcher(b); return m.find()?m.group(1):null; }
    static BigDecimal number(String r,String b) { String s=find(r,b); return s==null?null:decimal(s); }
    static String hash(String s) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder(); for(byte b:bytes)out.append(String.format("%02x",b)); return out.toString(); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
