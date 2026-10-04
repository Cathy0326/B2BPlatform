package com.quipmarket.shared;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Maps exceptions to GraphQL errors.
 * - DomainException -> its own type + extensions.code (+ details)
 * - anything else   -> INTERNAL_ERROR with a generic message (never leak stack traces or SQL)
 */
@Component
class GraphQlErrorMapping extends DataFetcherExceptionResolverAdapter {

    private static final Logger log = LoggerFactory.getLogger(GraphQlErrorMapping.class);

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof DomainException d) {
            Map<String, Object> ext = new LinkedHashMap<>(d.details());
            ext.put("code", d.code());
            return GraphqlErrorBuilder.newError(env).errorType(d.errorType()).message(d.getMessage()).extensions(ext).build();
        }
        log.error("Unhandled error in {}", env.getField().getName(), ex);
        return GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.INTERNAL_ERROR)
                .message("Internal error")
                .extensions(Map.of("code", "INTERNAL_ERROR"))
                .build();
    }
}
