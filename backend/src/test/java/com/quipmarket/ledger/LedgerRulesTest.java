package com.quipmarket.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.ledger.Ledger.AccountBalance;
import com.quipmarket.ledger.Ledger.AccountType;
import com.quipmarket.ledger.Ledger.TrialBalance;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerRulesTest {

    @Test
    void assetsAndExpensesGrowWithDebitsEverythingElseWithCredits() {
        assertThat(AccountType.ASSET.debitNormal()).isTrue();
        assertThat(AccountType.EXPENSE.debitNormal()).isTrue();
        assertThat(AccountType.LIABILITY.debitNormal()).isFalse();
        assertThat(AccountType.EQUITY.debitNormal()).isFalse();
        assertThat(AccountType.REVENUE.debitNormal()).isFalse();

        assertThat(new AccountBalance("cash", AccountType.ASSET, "", 1_000, 300).balanceCents()).isEqualTo(700);
        assertThat(new AccountBalance("fees", AccountType.REVENUE, "", 100, 600).balanceCents()).isEqualTo(500);
        assertThat(new AccountBalance("rent", AccountType.EXPENSE, "", 250, 0).balanceCents()).isEqualTo(250);
    }

    @Test
    void trialBalanceIsBalancedOnlyWhenTotalsMatch() {
        assertThat(new TrialBalance(List.of(), 500, 500).balanced()).isTrue();
        assertThat(new TrialBalance(List.of(), 500, 499).balanced()).isFalse();
        assertThat(Ledger.Line.debit("a", 5)).isEqualTo(new Ledger.Line("a", 5, 0));
        assertThat(Ledger.Line.credit("a", 5)).isEqualTo(new Ledger.Line("a", 0, 5));
    }
}
