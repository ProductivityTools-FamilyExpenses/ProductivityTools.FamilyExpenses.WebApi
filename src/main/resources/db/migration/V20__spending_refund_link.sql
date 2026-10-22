-- Refund tracking on an expense: the user flags an expense as "awaiting a refund" (refund_expected)
-- and later links the incoming refund transaction to it (refund_spending_id → spendings.id).
-- A refund row can be linked to at most one expense (partial unique index).
ALTER TABLE spending_details
    ADD COLUMN refund_expected BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN refund_spending_id BIGINT REFERENCES spendings(id) ON DELETE SET NULL;

CREATE INDEX idx_spending_details_refund_spending_id ON spending_details(refund_spending_id);

CREATE UNIQUE INDEX uq_spending_details_refund_spending_id
    ON spending_details(refund_spending_id)
    WHERE refund_spending_id IS NOT NULL;
