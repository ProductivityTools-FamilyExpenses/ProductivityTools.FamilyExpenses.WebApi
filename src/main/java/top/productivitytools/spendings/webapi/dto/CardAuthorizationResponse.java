package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Row of {@code card_authorizations}, optionally joined with the debit that settled it. */
public record CardAuthorizationResponse(
        Long id,
        Long rawEmailId,
        String operationId,
        String operationDate,
        String operationTime,
        String card,
        String merchant,
        BigDecimal amount,
        String currency,
        String amountLeft,
        String amountLeftCurrency,
        String details,
        OffsetDateTime createdAt,
        Long settledBySpendingId,
        String settledOperationDate,
        BigDecimal settledAmount,
        String settledCurrency,
        String settledAccount,
        String settledCategory
) {
}
