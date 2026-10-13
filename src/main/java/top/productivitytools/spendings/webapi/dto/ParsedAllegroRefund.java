package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One Allegro refund notification ("Zwrot 64,58 zł za zakup ..."). */
public record ParsedAllegroRefund(
        String operationId,
        LocalDate refundDate,
        BigDecimal amount,
        /** Full price of the original order when stated in the e-mail, otherwise null. */
        BigDecimal originalAmount,
        boolean partial,
        String seller,
        String description
) {
}
