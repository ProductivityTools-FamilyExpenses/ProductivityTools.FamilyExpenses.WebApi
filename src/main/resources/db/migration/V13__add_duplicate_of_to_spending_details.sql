-- A booked debit ("Obciazenie") that merely mirrors an outgoing transfer ("Przelew wych.") of the same
-- amount/account/day is a duplicate for accounting purposes. The transfer stays canonical (it carries
-- the recipient and target account); the debit points at it and is excluded from sums.
ALTER TABLE spending_details
    ADD COLUMN duplicate_of BIGINT REFERENCES spendings(id) ON DELETE SET NULL;

CREATE INDEX idx_spending_details_duplicate_of ON spending_details(duplicate_of);
