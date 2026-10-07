package top.productivitytools.spendings.webapi.dto;

import java.time.OffsetDateTime;

public record AllegroRawEmailRequest(
        String messageId,
        String threadId,
        String subject,
        OffsetDateTime emailDate,
        String rawHtml
) {
}
