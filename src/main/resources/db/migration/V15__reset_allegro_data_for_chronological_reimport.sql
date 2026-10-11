-- One-off reset so that Allegro ids become chronological after a clean re-import
-- (run moveAllegroImportedToInbox() and then exportAllegroEmailsToSpendingsApi() in Apps Script).
-- spending_details.allegro_raw_email_id links are cleared; they are re-created by the enrichment step.
UPDATE spending_details SET allegro_raw_email_id = NULL WHERE allegro_raw_email_id IS NOT NULL;
TRUNCATE allegro_purchases, allegro_raw_emails RESTART IDENTITY CASCADE;
