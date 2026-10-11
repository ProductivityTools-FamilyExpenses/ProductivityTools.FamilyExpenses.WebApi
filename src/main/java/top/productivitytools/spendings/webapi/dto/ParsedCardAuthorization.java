package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;

/**
 * Card pre-authorization parsed from an mBank notification row such as:
 * {@code mBank: Autoryzacja karty 5575***5205: ZABKA Z7239 K.2 WARSZAWA. Kwota: 8,99 PLN. Dostepne: 7992,73 PLN.}
 * Amount is negative (money leaving), consistent with spendings.
 */
public record ParsedCardAuthorization(
        String operationId,
        String operationDate,
        String operationTime,
        String card,
        String merchant,
        BigDecimal amount,
        String currency,
        String amountLeft,
        String amountLeftCurrency,
        String details
) {
}
