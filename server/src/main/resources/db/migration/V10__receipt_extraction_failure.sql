-- An uploaded receipt whose extraction fails is 'failed' with the reason kept, so the app can show it
-- and offer a retry instead of waiting on it forever.
ALTER TABLE receipt ADD COLUMN failure_reason text;

ALTER TABLE receipt DROP CONSTRAINT receipt_status_check;
ALTER TABLE receipt ADD CONSTRAINT receipt_status_check
    CHECK (status IN ('processing', 'draft', 'confirmed', 'void', 'failed'));
