package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;

public record ParsedSpending(
        String operationId,
        String operationDate,
        String operationTime,
        String operationType,
        String srcAccount,
        String dstAccount,
        BigDecimal amount,
        String currency,
        String name,
        String amountLeft,
        String amountLeftCurrency,
        String details
) {
}
