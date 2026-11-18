-- ============================================================================
-- ARCHIVED METER READING PHOTOS (COMPRESSED JPEG BLOBS)
-- ============================================================================

CREATE TABLE meter_reading_photo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meter_reading_id UUID NOT NULL UNIQUE REFERENCES meter_reading(id) ON DELETE CASCADE,
    file_name VARCHAR(255),
    mime_type VARCHAR(60) NOT NULL DEFAULT 'image/jpeg',
    image_data BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_meter_reading_photo_reading ON meter_reading_photo(meter_reading_id);
