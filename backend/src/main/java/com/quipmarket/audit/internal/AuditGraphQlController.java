package com.quipmarket.audit.internal;

import com.quipmarket.audit.AuditTrail;
import java.util.List;
import com.quipmarket.shared.CurrentUser;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
class AuditGraphQlController {

    private final AuditTrail audit;

    AuditGraphQlController(AuditTrail audit) {
        this.audit = audit;
    }

    @QueryMapping
    List<AuditTrail.AuditRecord> auditLog(@Argument Integer last, @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId,
            @ContextValue(name = CurrentUser.ROLES_KEY, required = false) List<String> roles) {
        CurrentUser.requireAdmin(userId, roles);
        return audit.latest(last == null ? 50 : last);
    }

    @QueryMapping
    AuditTrail.Verification verifyAuditChain(@ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId,
            @ContextValue(name = CurrentUser.ROLES_KEY, required = false) List<String> roles) {
        CurrentUser.requireAdmin(userId, roles);
        return audit.verify();
    }
}
