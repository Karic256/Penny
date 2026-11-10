package com.karic.penny;

import org.junit.Test;
import static com.karic.penny.LedgerTest.*;
import static org.junit.Assert.*;

public class ChatGptReportTest {
    @Test public void separatesAccountsAndOmitsSourceDetails() {
        Transaction bank = bank("-1000", "06-11-2026 at 09:52", "Bal:50621. Ref: BANK90001");
        Transaction mobile = momo("MOMO90001", "4663.36", NOW);

        String report = ChatGptReport.csv(ledger(bank, mobile), account -> account.identifierKnown);

        assertTrue(report.contains("\"balance_snapshot\",\"Bank\",\"Account 1\",\"Centenary Bank\","
                + "\"2026-11-06T09:52:00+03:00\",\"50621\",\"Reported\",\"true\""));
        assertTrue(report.contains("\"balance_snapshot\",\"Mobile Money\",\"Account 2\",\"MTN\","
                + "\"2026-11-07T12:00:00+03:00\",\"4663.36\",\"Reported\",\"false\""));
        assertTrue(report.contains("\"transaction\",\"Mobile Money\",\"Account 2\""));
        assertTrue(report.contains("\"COMPLETED\",\"6500\",\"Airtime & Data\",\"false\",\"true\",\"0\",\"true\""));
        assertFalse(report.contains("BANK90001"));
        assertFalse(report.contains("MOMO90001"));
        assertFalse(report.contains("*123"));
        assertFalse(report.contains("Bundle Purchase"));
    }

    @Test public void leavesUnavailableBalancesBlankAndFlagsPendingAmounts() {
        Transaction pending = bank("-10000", "06-11-2026 at 09:52", "pending settlement");

        String report = ChatGptReport.csv(ledger(pending), account -> true);

        assertTrue(report.contains("\"balance_snapshot\",\"Bank\",\"Account 1\",\"Centenary Bank\","
                + "\"\",\"\",\"\",\"true\""));
        assertTrue(report.contains("\"PENDING\",\"10000\",\"Uncategorised\",\"false\",\"false\",\"\",\"false\""));
    }
}
