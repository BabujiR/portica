package com.portica.aigateway.pii;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiScannerTest {

    private final PiiScanner all = new PiiScanner(List.of());

    @Test
    void redactsEachTypeWithAPlaceholder() {
        PiiScanner.Result r = all.redact("Mail ann@example.org or call (415) 555-0100; SSN 123-45-6789, "
                + "card 4242-4242-4242-4242, IBAN DE89 3704 0044 0532 0130 00, from 192.168.1.20");
        assertThat(r.text()).isEqualTo("Mail [EMAIL] or call [PHONE]; SSN [SSN], card [CREDIT_CARD], IBAN [IBAN], from [IP_ADDRESS]");
        assertThat(r.findings().total()).isEqualTo(6);
    }

    @Test
    void leavesLookalikesAlone() {
        String text = "Order 4111 1111 1111 1112 shipped on 2026-11-07; version 1.2.3.4.5; call ext. 1234";
        PiiScanner.Result r = all.redact(text);
        assertThat(r.text()).isEqualTo(text);   // not a valid card number (Luhn), not an IP, too short for a phone
        assertThat(r.findings().any()).isFalse();
    }

    @Test
    void onlyScansTheConfiguredTypes() {
        PiiScanner emailOnly = new PiiScanner(List.of("EMAIL", "NOT_A_TYPE"));
        assertThat(emailOnly.redact("a@b.io 4111111111111111").text()).isEqualTo("[EMAIL] 4111111111111111");
    }

    @Test
    void validatesCardsAndIbans() {
        assertThat(PiiScanner.luhn("4111111111111111")).isTrue();
        assertThat(PiiScanner.luhn("4111111111111112")).isFalse();
        assertThat(PiiScanner.iban("GB82WEST12345698765432")).isTrue();
        assertThat(PiiScanner.iban("GB82WEST12345698765431")).isFalse();
    }
}
