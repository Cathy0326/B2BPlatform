package com.quipmarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.catalog.Equipment.RentalRates;
import com.quipmarket.escrow.Escrow;
import com.quipmarket.rental.RentalPricing;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The money rules shared by every client. The same file is checked by the frontend (Vitest) and the
 * mobile app (Kotlin, on JVM, Android and iOS), so a price shown in any app matches what the backend charges.
 */
class MoneyRulesContractTest {

    static final JsonNode RULES = JsonMapper.shared().readTree(Path.of("../contracts/money-rules.json").toFile());

    static Stream<Arguments> feeCases() {
        return cases("platformFee").map(c -> Arguments.of(c.get("hammerCents").asLong(), c.get("feeCents").asLong()));
    }

    static Stream<Arguments> quoteCases() {
        return cases("rentalQuote").map(c -> Arguments.of(c.get("rates").asString(), c.get("rentalDays").asInt(), c));
    }

    private static Stream<JsonNode> cases(String rule) {
        return StreamSupport.stream(RULES.get(rule).get("cases").spliterator(), false);
    }

    @ParameterizedTest(name = "fee({0}) = {1}")
    @MethodSource("feeCases")
    void platformFee(long hammerCents, long feeCents) {
        assertThat(Escrow.platformFee(hammerCents)).isEqualTo(feeCents);
    }

    @ParameterizedTest(name = "{0}, {1} days")
    @MethodSource("quoteCases")
    void rentalQuote(String ratesName, int rentalDays, JsonNode expected) {
        JsonNode r = RULES.get("rentalQuote").get("rates").get(ratesName);
        var rates = new RentalRates(r.get("dailyCents").asLong(), r.get("weeklyCents").asLong(), r.get("monthlyCents").asLong());

        var price = RentalPricing.quote(rentalDays, rates);

        assertThat(price.totalCents()).isEqualTo(expected.get("totalCents").asLong());
        assertThat(new int[] {price.months(), price.weeks(), price.days()})
                .containsExactly(expected.get("months").asInt(), expected.get("weeks").asInt(), expected.get("days").asInt());
    }
}
