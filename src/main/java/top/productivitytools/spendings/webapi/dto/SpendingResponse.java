package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        /** amount converted to PLN (equal to amount for PLN rows); null until a rate is available */
        BigDecimal amountPln,
        BigDecimal fxRate,
        LocalDate fxRateDate,
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
        OffsetDateTime detailsUpdatedAt,
        /** the user flagged this expense as awaiting a refund ("Do zwrotu"); false when no details row */
        boolean refundExpected,
        /** for an expense: id of the linked refund (incoming) spending; null until linked */
        Long refundSpendingId,
        /** for a refund row: id of the expense it refunds; null when this row is not linked as a refund */
        Long refundOfSpendingId
) {
}
