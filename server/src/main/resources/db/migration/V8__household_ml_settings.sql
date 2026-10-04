-- Per-household settings for how model predictions are used.
--
-- A household with no row uses the defaults in the application (auto-assign on, threshold 0.85).
-- A prediction at or above the threshold is applied as it is; one below it, or any prediction when
-- auto_assign is off, has to be confirmed by a person before the receipt can be confirmed.
CREATE TABLE household_ml_settings
(
    household_id          uuid          PRIMARY KEY REFERENCES household (id) ON DELETE CASCADE,
    auto_assign           boolean       NOT NULL DEFAULT true,
    -- Confidence a prediction needs to be applied without a person's confirmation.
    auto_assign_threshold numeric(4, 3) NOT NULL DEFAULT 0.850
        CHECK (auto_assign_threshold BETWEEN 0 AND 1),
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now()
);

CREATE TRIGGER household_ml_settings_set_updated_at
    BEFORE UPDATE ON household_ml_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
