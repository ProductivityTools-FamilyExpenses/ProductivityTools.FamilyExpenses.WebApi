package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Row from {@code spendings} LEFT JOINed with {@code spending_details}.
 * Detail columns (account, category, note, allegroRawEmailId, detailsUpdatedAt)
 * are {@code null} when no spending_details row exists yet.
 */
public record SpendingResponse(
        Long id,
        Long rawEmailId,
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
        String details,
        OffsetDateTime createdAt,
        String account,
        String category,
        String note,
        Long allegroRawEmailId,
        OffsetDateTime detailsUpdatedAt
) {
}
