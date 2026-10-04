package com.quipmarket.shared;

import java.util.List;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** Lets the frontend learn its own public id and roles (needed to label "You" in bid histories). */
@Controller
class MeGraphQlController {

    record Me(String id, List<String> roles) {}

    @QueryMapping
    Me me(@ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId,
          @ContextValue(name = CurrentUser.ROLES_KEY, required = false) List<String> roles) {
        return userId == null ? null : new Me(userId, roles == null ? List.of() : roles);
    }
}
