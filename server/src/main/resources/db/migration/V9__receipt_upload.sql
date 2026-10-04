-- Receipts can be created from an uploaded image before anything has been extracted.
-- Such a receipt is 'processing' with no payer yet, and total_minor stays 0 until extraction fills it in.

ALTER TABLE receipt
    ADD COLUMN image_key text,
    ALTER COLUMN paid_by_person_id DROP NOT NULL,
    ALTER COLUMN purchased_at SET DEFAULT now(),
    ALTER COLUMN total_minor SET DEFAULT 0;

ALTER TABLE receipt DROP CONSTRAINT receipt_status_check;
ALTER TABLE receipt ADD CONSTRAINT receipt_status_check
    CHECK (status IN ('processing', 'draft', 'confirmed', 'void'));
