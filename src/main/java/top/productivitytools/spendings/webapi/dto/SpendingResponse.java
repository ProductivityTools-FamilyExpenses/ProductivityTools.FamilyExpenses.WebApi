package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Row from {@code spendings} LEFT JOINed with {@code spending_details}.
 * Detail columns (accountId/account, category, note, allegroRawEmailId, detailsUpdatedAt)
 * are {@code null} when no spending_details row exists yet. {@code account} is the name resolved
 * from dictionary_account via {@code accountId}.
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
        Long accountId,
        String account,
        String category,
        String note,
        Long allegroRawEmailId,
        /** id of the canonical spending this row duplicates (e.g. debit mirroring a transfer); null = count it */
        Long duplicateOf,
        OffsetDateTime detailsUpdatedAt
) {
}
