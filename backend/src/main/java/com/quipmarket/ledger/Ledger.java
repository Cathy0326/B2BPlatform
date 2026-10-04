package com.quipmarket.ledger;

import java.time.Instant;
import java.util.List;

/**
 * Double-entry ledger. Public API of the ledger module.
 *
 * Every money movement is ONE journal entry with two or more lines whose debits equal credits.
 * Nothing is ever updated or deleted; corrections are new, reversing entries.
 * Balances are always computed as SUM(lines). There is no editable balance column.
 *
 * Normal balance side:
 *   ASSET, EXPENSE                -> debit increases   (balance = debits - credits)
 *   LIABILITY, EQUITY, REVENUE    -> credit increases  (balance = credits - debits)
 */
public interface Ledger {

    enum AccountType {
        ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE;

        public boolean debitNormal() {
            return this == ASSET || this == EXPENSE;
        }
    }

    record Line(String accountCode, long debitCents, long creditCents) {
        public static Line debit(String account, long cents) {
            return new Line(account, cents, 0);
        }

        public static Line credit(String account, long cents) {
            return new Line(account, 0, cents);
        }
    }

    record Entry(long id, String kind, String reference, String description, Instant createdAt, List<Line> lines) {}

    record AccountBalance(String code, AccountType type, String name, long debitsCents, long creditsCents) {
        public long balanceCents() {
            return type.debitNormal() ? debitsCents - creditsCents : creditsCents - debitsCents;
        }
    }

    record TrialBalance(List<AccountBalance> accounts, long totalDebitsCents, long totalCreditsCents) {
        public boolean balanced() {
            return totalDebitsCents == totalCreditsCents;
        }
    }

    /** Create the account if it does not exist (idempotent). */
    void ensureAccount(String code, AccountType type, String name);

    /**
     * Post a balanced entry. Idempotent per (kind, reference): posting the same business event twice
     * returns the original entry instead of moving the money twice.
     */
    Entry post(String kind, String reference, String description, List<Line> lines);

    TrialBalance trialBalance();

    long balanceOf(String accountCode);

    /** Newest first. reference = null for all entries. */
    List<Entry> entries(String reference, int limit);
}
