package com.karic.penny;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** An SMS observation. Null money fields mean unknown, never zero. */
public class Transaction {
    public enum Status { COMPLETED, PENDING, FAILED, REVERSED, APPROVAL, UNKNOWN }
    public String provider, account, accountKind, sender, rawBody, sourceKey;
    public String direction, counterparty, description, category, transactionId, originalId;
    public String balanceType = "Reported", timeSource = "SMS received time";
    public BigDecimal amount, fee, reportedBalance;
    public long timestamp, receivedAt;
    public boolean transferHint, feeRefunded;
    public Status status = Status.UNKNOWN;
    public final List<String> warnings = new ArrayList<>();
    public String accountKey() { return provider + ":" + account; }
    public String displayAccount() {
        if ("MTN".equals(provider)) return "MTN Mobile Money" + ("unknown".equals(account) ? " • number unknown" : " • " + mask(account));
        return provider + ("unknown".equals(account) ? " • account unknown" : " • " + mask(account));
    }
    public static String mask(String value) {
        if (value == null || "unknown".equals(value)) return "Account unknown";
        String digits = value.replaceAll("[^0-9]", "");
        return "•••" + digits.substring(Math.max(0, digits.length() - 3));
    }
    public boolean isCredit() { return "IN".equals(direction); }
}
