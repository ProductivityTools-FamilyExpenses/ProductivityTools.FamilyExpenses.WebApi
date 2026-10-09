package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Account balance snapshot parsed from an mBank notification row such as:
 * {@code mBank: Saldo rach. 42109862 w dniu 2026-08-31. Dostepne 6491,93 PLN}
 */
public record ParsedAccountBalance(
        String operationId,
        String account,
        LocalDate balanceDate,
        String operationTime,
        BigDecimal amount,
        String currency,
        String details
) {
}
