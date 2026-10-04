package com.quipmarket.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.BooleanValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Custom scalars are the API's front door for money and dates: bad input must be rejected, never guessed. */
class GraphQlScalarsTest {

    static final GraphQLContext CTX = GraphQLContext.getDefault();
    static final Locale LOCALE = Locale.ROOT;
    static final CoercedVariables NO_VARS = CoercedVariables.emptyVariables();

    @SuppressWarnings("unchecked")
    static <I, O> Coercing<I, O> coercing(graphql.schema.GraphQLScalarType t) {
        return (Coercing<I, O>) t.getCoercing();
    }

    @Test
    void longAcceptsWholeNumbersOnly() {
        Coercing<Long, Long> c = coercing(GraphQlScalars.LONG);

        assertThat(c.serialize(42, CTX, LOCALE)).isEqualTo(42L);
        assertThat(c.parseValue(7, CTX, LOCALE)).isEqualTo(7L);
        assertThat(c.parseValue(9_000_000_000L, CTX, LOCALE)).isEqualTo(9_000_000_000L);
        assertThat(c.parseValue(BigInteger.valueOf(5), CTX, LOCALE)).isEqualTo(5L);
        assertThat(c.parseValue(12.0, CTX, LOCALE)).isEqualTo(12L); // JSON numbers may arrive as doubles
        assertThat(c.parseLiteral(new IntValue(BigInteger.TEN), NO_VARS, CTX, LOCALE)).isEqualTo(10L);

        assertThatThrownBy(() -> c.serialize("x", CTX, LOCALE)).isInstanceOf(CoercingSerializeException.class);
        assertThatThrownBy(() -> c.parseValue(12.5, CTX, LOCALE)).isInstanceOf(CoercingParseValueException.class); // no fractional cents
        assertThatThrownBy(() -> c.parseValue(new BigDecimal("0.5"), CTX, LOCALE)).isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> c.parseValue("100", CTX, LOCALE)).isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> c.parseValue(BigInteger.TWO.pow(70), CTX, LOCALE)).isInstanceOf(ArithmeticException.class); // overflow is an error, not a wrap-around
        assertThatThrownBy(() -> c.parseLiteral(new StringValue("10"), NO_VARS, CTX, LOCALE)).isInstanceOf(CoercingParseLiteralException.class);
    }

    @Test
    void dateTimeIsStrictIso8601() {
        Coercing<Instant, String> c = coercing(GraphQlScalars.DATE_TIME);
        Instant t = Instant.parse("2026-10-04T08:30:00Z");

        assertThat(c.serialize(t, CTX, LOCALE)).isEqualTo("2026-10-04T08:30:00Z");
        assertThat(c.parseValue("2026-10-04T08:30:00Z", CTX, LOCALE)).isEqualTo(t);
        assertThat(c.parseLiteral(new StringValue("2026-10-04T08:30:00Z"), NO_VARS, CTX, LOCALE)).isEqualTo(t);

        assertThatThrownBy(() -> c.serialize("2026-10-04", CTX, LOCALE)).isInstanceOf(CoercingSerializeException.class);
        assertThatThrownBy(() -> c.parseValue("yesterday", CTX, LOCALE)).isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> c.parseLiteral(new BooleanValue(true), NO_VARS, CTX, LOCALE)).isInstanceOf(CoercingParseLiteralException.class);
    }

    @Test
    void dateIsACalendarDayWithoutTime() {
        Coercing<LocalDate, String> c = coercing(GraphQlScalars.DATE);

        assertThat(c.serialize(LocalDate.of(2026, 10, 4), CTX, LOCALE)).isEqualTo("2026-10-04");
        assertThat(c.parseValue("2026-02-28", CTX, LOCALE)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(c.parseLiteral(new StringValue("2026-10-04"), NO_VARS, CTX, LOCALE)).isEqualTo(LocalDate.of(2026, 10, 4));

        assertThatThrownBy(() -> c.serialize(Instant.EPOCH, CTX, LOCALE)).isInstanceOf(CoercingSerializeException.class);
        assertThatThrownBy(() -> c.parseValue("2026-02-30", CTX, LOCALE)).isInstanceOf(CoercingParseValueException.class); // no such day
        assertThatThrownBy(() -> c.parseLiteral(new IntValue(BigInteger.ONE), NO_VARS, CTX, LOCALE)).isInstanceOf(CoercingParseLiteralException.class);
    }
}
