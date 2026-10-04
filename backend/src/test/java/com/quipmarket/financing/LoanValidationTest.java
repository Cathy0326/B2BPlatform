package com.quipmarket.financing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.quipmarket.shared.DomainException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every boundary of the loan input rules: the last valid value passes, the first invalid one fails. */
class LoanValidationTest {

    @ParameterizedTest(name = "price={0} down={1} apr={2}bps term={3} -> {4}")
    @CsvSource({
            "0,        0,      600, 60,  Price must be greater than 0",
            "100000,   -1,     600, 60,  Down payment cannot be negative",
            "100000,   100000, 600, 60,  Down payment must be less than the price",
            "100000,   0,      -1,  60,  APR must be between",
            "100000,   0,      5001, 60, APR must be between",
            "100000,   0,      600, 0,   Term must be 1 to 120",
            "100000,   0,      600, 121, Term must be 1 to 120",
    })
    void rejectsInvalidInput(long price, long down, int aprBps, int term, String message) {
        assertThatThrownBy(() -> LoanCalculator.validate(new LoanCalculator.Input(price, down, aprBps, term)))
                .isInstanceOf(DomainException.InvalidInput.class)
                .hasMessageContaining(message);
    }

    @ParameterizedTest
    @CsvSource({"1, 0, 0, 1", "100000, 99999, 5000, 120", "100000, 0, 0, 120"})
    void acceptsTheBoundaryValues(long price, long down, int aprBps, int term) {
        assertThatNoException().isThrownBy(() -> LoanCalculator.validate(new LoanCalculator.Input(price, down, aprBps, term)));
    }
}
