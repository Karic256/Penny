package com.karic.penny;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

final class ChatGptReport {
    private static final DateTimeFormatter EAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.of("Africa/Kampala"));

    static String csv(Ledger ledger, Predicate<Ledger.Account> included) {
        StringBuilder csv = new StringBuilder(
                "record_type,account_type,account,provider,date_EAT,latest_reported_balance_UGX,"
                + "balance_type,included_in_combined_balance,direction,status,amount_UGX,category,"
                + "internal_transfer,amount_verified,fee_UGX,fee_counted_in_spending,needs_review\r\n");
        Map<String, String> accountLabels = new HashMap<>();
        for (Ledger.Account account : ledger.accounts.values()) {
            String label = "Account " + (accountLabels.size() + 1);
            accountLabels.put(account.key, label);
            Ledger.Balance balance = account.latest;
            String provider = account.key.substring(0, account.key.indexOf(':'));
            row(csv, "balance_snapshot", account.kind, label, provider,
                    balance == null ? "" : date(balance.at),
                    balance == null ? "" : money(balance.value),
                    balance == null ? "" : balance.type, Boolean.toString(included.test(account)),
                    "", "", "", "", "", "", "", "", Boolean.toString(!account.warnings.isEmpty()));
        }
        for (Ledger.Entry entry : ledger.entries) {
            row(csv, "transaction", entry.t.accountKind, accountLabels.get(entry.accountKey()),
                    entry.t.provider, date(entry.occurredAt), "", "", "", entry.direction,
                    entry.status.name(), money(entry.amount), entry.category, Boolean.toString(entry.internal),
                    Boolean.toString(entry.counted()), money(entry.fee), Boolean.toString(entry.countsFee()),
                    Boolean.toString(!entry.warnings.isEmpty()));
        }
        return csv.toString();
    }

    private static String date(long time) {
        return EAT.format(Instant.ofEpochMilli(time));
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }

    private static void row(StringBuilder csv, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) csv.append(',');
            String value = fields[i] == null ? "" : fields[i];
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
