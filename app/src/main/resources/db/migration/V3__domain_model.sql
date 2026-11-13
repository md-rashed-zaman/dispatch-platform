-- Core aggregates: company (tenant), technician, work_order + checklist_item, offer.
-- Constraints repeat the most important domain rules so that raw SQL, a bad migration or a bug in
-- another code path can't store invalid data (defense in depth, see T01 and T10).
-- Geo and skill indexes are added in step 1.5, after measuring the queries without them.

CREATE TABLE company
(
  id         UUID PRIMARY KEY,
  name       TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  slug       TEXT        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Technicians are global (not tenant-owned), so this table has no company id.
CREATE TABLE technician
(
  id                UUID PRIMARY KEY,
  full_name         TEXT             NOT NULL CHECK (length(full_name) BETWEEN 1 AND 200),
  phone             TEXT             NOT NULL UNIQUE CHECK (phone ~ '^\+8801[3-9][0-9]{8}$'),
  skills            TEXT[]           NOT NULL CHECK (cardinality(skills) > 0),
  home_latitude     DOUBLE PRECISION NOT NULL CHECK (home_latitude BETWEEN -90 AND 90),
  home_longitude    DOUBLE PRECISION NOT NULL CHECK (home_longitude BETWEEN -180 AND 180),
  -- Derived by Postgres, never written by the app. Note the order: PostGIS points are (longitude, latitude).
  home_location     GEOGRAPHY(Point, 4326) GENERATED ALWAYS AS
                      (ST_Point(home_longitude, home_latitude, 4326)::geography) STORED,
  service_radius_km INT              NOT NULL CHECK (service_radius_km BETWEEN 1 AND 100),
  rating            NUMERIC(3, 2) CHECK (rating BETWEEN 1 AND 5),
  status            TEXT             NOT NULL
                      CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'SUSPENDED')),
  version           BIGINT           NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE TABLE work_order
(
  id               UUID PRIMARY KEY,
  tenant_id        UUID             NOT NULL REFERENCES company (id),
  title            TEXT             NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  description      TEXT             NOT NULL CHECK (length(description) BETWEEN 1 AND 5000),
  status           TEXT             NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ROUTED', 'ASSIGNED',
                                                               'IN_PROGRESS', 'SUBMITTED', 'APPROVED',
                                                               'PAID', 'CANCELLED')),
  site_address     TEXT             NOT NULL CHECK (length(site_address) BETWEEN 1 AND 500),
  site_latitude    DOUBLE PRECISION NOT NULL CHECK (site_latitude BETWEEN -90 AND 90),
  site_longitude   DOUBLE PRECISION NOT NULL CHECK (site_longitude BETWEEN -180 AND 180),
  site_location    GEOGRAPHY(Point, 4326) GENERATED ALWAYS AS
                     (ST_Point(site_longitude, site_latitude, 4326)::geography) STORED,
  required_skills  TEXT[]           NOT NULL CHECK (cardinality(required_skills) > 0),
  window_starts_at TIMESTAMPTZ      NOT NULL,
  window_ends_at   TIMESTAMPTZ      NOT NULL,
  -- Money in minor units (poisha). BIGINT, never a floating point type.
  budget_minor     BIGINT           NOT NULL CHECK (budget_minor > 0),
  budget_currency  TEXT             NOT NULL CHECK (budget_currency ~ '^[A-Z]{3}$'),
  technician_id    UUID REFERENCES technician (id),
  version          BIGINT           NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
  CONSTRAINT work_order_window_valid CHECK (window_ends_at > window_starts_at),
  -- From ASSIGNED onward a work order always has a technician.
  CONSTRAINT work_order_assigned_has_technician CHECK (
    status NOT IN ('ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'APPROVED', 'PAID') OR technician_id IS NOT NULL)
);

-- Dispatcher screens list a tenant's work orders by status.
CREATE INDEX work_order_tenant_status_idx ON work_order (tenant_id, status);
-- Postgres doesn't index foreign keys automatically; "my jobs" lists filter by technician.
CREATE INDEX work_order_technician_idx ON work_order (technician_id);

CREATE TABLE checklist_item
(
  id            UUID PRIMARY KEY,
  work_order_id UUID NOT NULL REFERENCES work_order (id) ON DELETE CASCADE,
  position      INT  NOT NULL CHECK (position >= 0),
  description   TEXT NOT NULL CHECK (length(description) BETWEEN 1 AND 500),
  -- Deferred so items can be reordered inside one transaction without tripping on temporary duplicates.
  CONSTRAINT checklist_item_position_unique UNIQUE (work_order_id, position) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE offer
(
  id            UUID PRIMARY KEY,
  work_order_id UUID        NOT NULL REFERENCES work_order (id),
  technician_id UUID        NOT NULL REFERENCES technician (id),
  status        TEXT        NOT NULL CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED')),
  expires_at    TIMESTAMPTZ NOT NULL,
  responded_at  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT offer_once_per_technician UNIQUE (work_order_id, technician_id)
);

-- Last line of defense for "first accept wins" (step 1.6): even if application code has a race bug,
-- Postgres refuses a second ACCEPTED offer for the same work order.
CREATE UNIQUE INDEX offer_one_accepted_per_work_order ON offer (work_order_id) WHERE status = 'ACCEPTED';
-- "My open offers" for a technician.
CREATE INDEX offer_technician_status_idx ON offer (technician_id, status);
