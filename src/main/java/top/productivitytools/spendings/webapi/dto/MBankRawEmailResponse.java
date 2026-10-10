package top.productivitytools.spendings.webapi.dto;

import java.time.OffsetDateTime;

/** Row of {@code mbank_raw_emails} without the (large) raw HTML body. */
public record MBankRawEmailResponse(
        Long id,
        String messageId,
        String threadId,
        String source,
        String subject,
        String attachmentName,
        OffsetDateTime emailDate,
        String status,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt,
        int spendingsCount,
        int balancesCount
) {
}
