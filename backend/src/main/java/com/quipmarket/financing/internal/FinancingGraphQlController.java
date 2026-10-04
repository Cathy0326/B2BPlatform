package com.quipmarket.financing.internal;

import com.quipmarket.financing.LoanCalculator;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
class FinancingGraphQlController {

    @QueryMapping
    LoanCalculator.Quote loanQuote(@Argument LoanCalculator.Input input) {
        return LoanCalculator.amortize(input);
    }
}
