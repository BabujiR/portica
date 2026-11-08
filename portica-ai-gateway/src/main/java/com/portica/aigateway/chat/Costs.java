package com.portica.aigateway.chat;

import com.portica.aigateway.provider.LlmProvider.Usage;
import com.portica.aigateway.runtime.AiRuntime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cost of a call from token counts and list prices (US dollars per million tokens). */
public final class Costs {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private Costs() {
    }

    public static BigDecimal of(Usage usage, AiRuntime.Model pricing) {
        BigDecimal in = price(pricing.inputCostPerMillion()).multiply(BigDecimal.valueOf(usage.promptTokens()));
        BigDecimal out = price(pricing.outputCostPerMillion()).multiply(BigDecimal.valueOf(usage.completionTokens()));
        return in.add(out).divide(MILLION, 8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal price(BigDecimal p) {
        return p == null ? BigDecimal.ZERO : p;
    }
}
