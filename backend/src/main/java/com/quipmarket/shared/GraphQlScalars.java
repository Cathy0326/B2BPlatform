package com.quipmarket.shared;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * Custom scalars.
 * Long:     GraphQL's built-in Int is 32-bit (max ~$21M in cents). Money needs 64-bit.
 * DateTime: java.time.Instant <-> ISO-8601 UTC string.
 * Date:     java.time.LocalDate <-> "yyyy-MM-dd".
 */
@Configuration
class GraphQlScalars {

    @Bean
    RuntimeWiringConfigurer scalarWiring() {
        return wiring -> wiring.scalar(LONG).scalar(DATE_TIME).scalar(DATE);
    }

    static final GraphQLScalarType LONG = GraphQLScalarType.newScalar()
            .name("Long")
            .description("64-bit signed integer (used for money in cents)")
            .coercing(new Coercing<Long, Long>() {
                @Override
                public Long serialize(Object v, GraphQLContext c, Locale l) {
                    if (v instanceof Number n) return n.longValue();
                    throw new CoercingSerializeException("Not a number: " + v);
                }

                @Override
                public Long parseValue(Object v, GraphQLContext c, Locale l) {
                    if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
                    if (v instanceof BigInteger b) return b.longValueExact();
                    if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.longValue();
                    throw new CoercingParseValueException("Expected an integer, got " + v);
                }

                @Override
                public Long parseLiteral(Value<?> v, CoercedVariables vars, GraphQLContext c, Locale l) {
                    if (v instanceof IntValue i) return i.getValue().longValueExact();
                    throw new CoercingParseLiteralException("Expected an integer literal");
                }
            })
            .build();

    static final GraphQLScalarType DATE_TIME = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("ISO-8601 instant in UTC, e.g. 2026-10-04T08:30:00Z")
            .coercing(new Coercing<Instant, String>() {
                @Override
                public String serialize(Object v, GraphQLContext c, Locale l) {
                    if (v instanceof Instant i) return i.toString();
                    throw new CoercingSerializeException("Not an Instant: " + v);
                }

                @Override
                public Instant parseValue(Object v, GraphQLContext c, Locale l) {
                    try {
                        return Instant.parse(v.toString());
                    } catch (DateTimeParseException e) {
                        throw new CoercingParseValueException("Invalid DateTime: " + v);
                    }
                }

                @Override
                public Instant parseLiteral(Value<?> v, CoercedVariables vars, GraphQLContext c, Locale l) {
                    if (v instanceof StringValue s) return parseValue(s.getValue(), c, l);
                    throw new CoercingParseLiteralException("Expected a string");
                }
            })
            .build();

    static final GraphQLScalarType DATE = GraphQLScalarType.newScalar()
            .name("Date")
            .description("Calendar date, e.g. 2026-10-04")
            .coercing(new Coercing<LocalDate, String>() {
                @Override
                public String serialize(Object v, GraphQLContext c, Locale l) {
                    if (v instanceof LocalDate d) return d.toString();
                    throw new CoercingSerializeException("Not a LocalDate: " + v);
                }

                @Override
                public LocalDate parseValue(Object v, GraphQLContext c, Locale l) {
                    try {
                        return LocalDate.parse(v.toString());
                    } catch (DateTimeParseException e) {
                        throw new CoercingParseValueException("Invalid Date: " + v);
                    }
                }

                @Override
                public LocalDate parseLiteral(Value<?> v, CoercedVariables vars, GraphQLContext c, Locale l) {
                    if (v instanceof StringValue s) return parseValue(s.getValue(), c, l);
                    throw new CoercingParseLiteralException("Expected a string");
                }
            })
            .build();
}
