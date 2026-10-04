-- How each confirmed item got its owners: typed in by hand, a model prediction that was accepted
-- as is, or one that a person changed. NULL until the receipt is confirmed.
ALTER TABLE receipt_item
    ADD COLUMN label_source text
        CHECK (label_source IN ('manual', 'predicted_accepted', 'predicted_corrected'));

UPDATE receipt_item
SET label_source = 'manual'
WHERE receipt_id IN (SELECT id FROM receipt WHERE status = 'confirmed');
