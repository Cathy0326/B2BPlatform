package com.quipmarket.audit.internal;

import com.quipmarket.audit.AuditTrail;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
class AuditGraphQlController {

    private final AuditTrail audit;

    AuditGraphQlController(AuditTrail audit) {
        this.audit = audit;
    }

    @QueryMapping
    List<AuditTrail.AuditRecord> auditLog(@Argument Integer last) {
        return audit.latest(last == null ? 50 : last);
    }

    @QueryMapping
    AuditTrail.Verification verifyAuditChain() {
        return audit.verify();
    }
}
