package com.karic.penny;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransactionParserTest {
    @Test public void referenceAndPhoneAreNotAmounts() {
        Transaction tx = TransactionParser.parse("MTN", "Ref 987654321: You sent UGX 25,000 to 0772123456. Fee UGX 500. Balance UGX 50,000", 1);
        assertNotNull(tx); assertEquals(25000, tx.amount, 0); assertEquals(500, tx.fee, 0);
    }
    @Test public void equalFeeAndAmountIsValid() {
        Transaction tx = TransactionParser.parse("Bank", "Paid UGX 500 at Cafe. Fee UGX 500. Balance UGX 1,000", 1);
        assertNotNull(tx); assertEquals(500, tx.amount, 0);
    }
    @Test public void skipsBalanceBeforeTransaction() {
        Transaction tx = TransactionParser.parse("Bank", "Balance UGX 10,000. Paid UGX 2,000 at Cafe", 1);
        assertNotNull(tx); assertEquals(2000, tx.amount, 0);
    }
    @Test public void ignoresSecurityCodes() {
        assertNull(TransactionParser.parse("Bank", "OTP 123456 to confirm purchase UGX 50,000", 1));
    }
    @Test public void doesNotGuessAnUnlabelledNumber() {
        assertNull(TransactionParser.parse("Bank", "Ref 123456 paid successfully. Balance 20000", 1));
    }
    @Test public void receivesIncomeWithDecimals() {
        Transaction tx = TransactionParser.parse("Bank", "Received UGX 1,250.50 from Alice", 1);
        assertNotNull(tx); assertEquals("INCOME", tx.type); assertEquals(1250.50, tx.amount, 0);
    }
}
