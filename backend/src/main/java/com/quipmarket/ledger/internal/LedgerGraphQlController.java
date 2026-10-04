package com.quipmarket.ledger.internal;

import com.quipmarket.ledger.Ledger;
import java.util.List;
import com.quipmarket.shared.CurrentUser;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** Read-only ledger views. ADMIN only: the books show every customer's money. */
@Controller
class LedgerGraphQlController {

    private final Ledger ledger;

    LedgerGraphQlController(Ledger ledger) {
        this.ledger = ledger;
    }

    @QueryMapping
    Ledger.TrialBalance trialBalance(@ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId,
            @ContextValue(name = CurrentUser.ROLES_KEY, required = false) List<String> roles) {
        CurrentUser.requireAdmin(userId, roles);
        return ledger.trialBalance();
    }

    @QueryMapping
    List<Ledger.Entry> journalEntries(@Argument String reference, @Argument Integer last,
            @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId,
            @ContextValue(name = CurrentUser.ROLES_KEY, required = false) List<String> roles) {
        CurrentUser.requireAdmin(userId, roles);
        return ledger.entries(reference, last == null ? 50 : last);
    }
}
