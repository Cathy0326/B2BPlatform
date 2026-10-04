package com.quipmarket.ledger.internal;

import com.quipmarket.ledger.Ledger;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** Read-only ledger views. (Phase 4 restricts these to the ADMIN role.) */
@Controller
class LedgerGraphQlController {

    private final Ledger ledger;

    LedgerGraphQlController(Ledger ledger) {
        this.ledger = ledger;
    }

    @QueryMapping
    Ledger.TrialBalance trialBalance() {
        return ledger.trialBalance();
    }

    @QueryMapping
    List<Ledger.Entry> journalEntries(@Argument String reference, @Argument Integer last) {
        return ledger.entries(reference, last == null ? 50 : last);
    }
}
