-- One-off reset so that ids become chronological after a clean re-import:
-- oldest e-mail -> mbank_raw_emails.id = 1, oldest spending -> lowest spendings.id.
-- Re-import: run moveMBankTransfersToInbox() and then exportMBankEmailsToSpendingsApi() in Apps Script.
-- Only automatically derived data is lost (accounts/categories from dictionaries, Allegro links);
-- allegro_raw_emails / allegro_purchases and the dictionaries are untouched.
TRUNCATE spending_details, account_balances, spendings, mbank_raw_emails RESTART IDENTITY CASCADE;
