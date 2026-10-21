package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.messaging.AfterSalesCaseLinePayload;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;


import java.time.LocalDateTime;
import java.util.List;


/**
 * The one way into the merchant's after-sales inbox (merchant_after_sales_cases, ADR 0002 sections 7 and 10.3): a
 * snapshot of a case, the data of an after-sales event or the case a merchant command returns, is upserted by the case
 * version it carries. A row is added for a case the inbox does not have yet, and a row only moves to a newer version,
 * so a late or replayed event never moves it back, and an event of a version a merchant command already wrote changes
 * nothing.
 */
@Component
class MerchantAfterSalesProjection {

    private static final TypeReference<List<MerchantAfterSalesCaseDto.Line>> LINES_TYPE = new TypeReference<>() {
    };

    private final MerchantAfterSalesRepository repository;
    private final ObjectMapper objectMapper;


    MerchantAfterSalesProjection(MerchantAfterSalesRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }


    /**
     * Inserts the row of the case unless it has one, then brings the row to the snapshot's version if it is older.
     * Both statements are safe against a concurrent writer of the same case: the insert through the unique case_id,
     * the update through its version condition.
     *
     * @param snapshot a customer's request (kind CUSTOMER_REQUEST: it has a restaurant, a reason and a description)
     * @param now      the row's updated_at when this changes it
     * @return true when the row was inserted or updated, false when it already reflected this or a newer version
     */
    boolean apply(AfterSalesCasePayload snapshot, LocalDateTime now) {
        int inserted = repository.insertIfAbsent(
                snapshot.caseId(),
                snapshot.orderId(),
                snapshot.orderNo(),
                snapshot.restaurantId(),
                snapshot.customerId(),
                snapshot.status(),
                snapshot.reasonCode(),
                snapshot.description(),
                serializeLines(snapshot),
                snapshot.requestedAmount(),
                snapshot.offeredAmount(),
                snapshot.approvedAmount(),
                snapshot.currency(),
                snapshot.merchantDecision(),
                snapshot.merchantNote(),
                snapshot.deadlineAt(),
                snapshot.version(),
                snapshot.openedAt(),
                now
        );
        if (inserted == 1) {
            return true;
        }
        return repository.updateIfNewer(
                snapshot.caseId(),
                snapshot.status(),
                snapshot.offeredAmount(),
                snapshot.approvedAmount(),
                snapshot.merchantDecision(),
                snapshot.merchantNote(),
                snapshot.deadlineAt(),
                snapshot.version(),
                now
        ) == 1;
    }


    MerchantAfterSalesCaseDto toDto(MerchantAfterSalesEntity row) {
        return new MerchantAfterSalesCaseDto(
                row.caseId(),
                row.orderId(),
                row.orderNo(),
                row.status(),
                row.reasonCode(),
                row.description(),
                readLines(row.lines()),
                row.requestedAmount(),
                row.offeredAmount(),
                row.approvedAmount(),
                row.currency(),
                row.merchantDecision(),
                row.merchantNote(),
                row.deadlineAt(),
                row.openedAt(),
                row.updatedAt()
        );
    }


    /**
     * The lines as stored with the row, each with its currency (the case's when an event line carries none).
     */
    private String serializeLines(AfterSalesCasePayload snapshot) {
        List<AfterSalesCaseLinePayload> lines = snapshot.lines() == null ? List.of() : snapshot.lines();
        try {
            return objectMapper.writeValueAsString(lines.stream()
                    .map(line -> new MerchantAfterSalesCaseDto.Line(
                            line.orderItemId(),
                            line.menuItemName(),
                            line.quantity(),
                            line.unitPrice(),
                            line.amount(),
                            line.currency() == null ? snapshot.currency() : line.currency()
                    ))
                    .toList());
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to serialize after-sales lines for the merchant inbox", ex);
        }
    }


    private List<MerchantAfterSalesCaseDto.Line> readLines(String lines) {
        try {
            return objectMapper.readValue(lines, LINES_TYPE);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unable to read merchant after-sales lines", ex);
        }
    }
}
