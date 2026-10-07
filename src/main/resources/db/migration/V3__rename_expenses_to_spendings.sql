ALTER TABLE expenses RENAME TO spendings;
ALTER INDEX idx_expenses_raw_email_id RENAME TO idx_spendings_raw_email_id;
ALTER INDEX idx_expenses_operation_type RENAME TO idx_spendings_operation_type;

ALTER TABLE expense_details RENAME TO spending_details;
ALTER TABLE spending_details RENAME COLUMN expense_id TO spending_id;
