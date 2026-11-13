-- Local development data: 3 client companies, 300 technicians and 30 draft work orders around
-- Dhaka and Chattogram. Wipes existing data first. Never run against production.
--
-- Run:  docker compose -f infra/compose/docker-compose.yml exec -T postgres \
--         psql -U dispatch -d dispatch -v ON_ERROR_STOP=1 < infra/seed/dev-seed.sql

BEGIN;

TRUNCATE company, technician CASCADE;

-- City centres; points are scattered up to about 13 km (0.12 degrees) around them.
CREATE TEMP TABLE city (name TEXT, lat DOUBLE PRECISION, lon DOUBLE PRECISION) ON COMMIT DROP;
INSERT INTO city VALUES ('Dhaka', 23.8103, 90.4125), ('Chattogram', 22.3569, 91.7832);

CREATE TEMP TABLE skill (code TEXT) ON COMMIT DROP;
INSERT INTO skill VALUES ('networking'), ('pos-terminal'), ('printer'), ('cctv'), ('electrical'), ('ac-repair');

-- Fixed ids: Keycloak test users carry these as their tenant_id claim (infra/compose/keycloak).
INSERT INTO company (id, name, slug) VALUES
  ('01930000-0000-7000-8000-000000000001', 'Meghna Bank PLC', 'meghna-bank'),
  ('01930000-0000-7000-8000-000000000002', 'Shwapno Retail', 'shwapno-retail'),
  ('01930000-0000-7000-8000-000000000003', 'Padma Garments Ltd', 'padma-garments');

INSERT INTO technician (id, full_name, phone, skills, home_latitude, home_longitude, service_radius_km, rating, status)
SELECT gen_random_uuid(),
       (ARRAY['Rahim', 'Karim', 'Sumon', 'Nasrin', 'Tanvir', 'Farhana', 'Jamal', 'Rupa'])[1 + g % 8]
         || ' ' || (ARRAY['Uddin', 'Hossain', 'Ahmed', 'Akter', 'Islam', 'Rahman'])[1 + g % 6],
       '+88017' || lpad(g::TEXT, 8, '0'),
       -- 1 to 3 random skills; "g > 0" makes the subquery run per row instead of once
       (SELECT array_agg(code) FROM (SELECT code FROM skill WHERE g > 0 ORDER BY random() LIMIT 1 + g % 3) s),
       c.lat + (random() - 0.5) * 0.24,
       c.lon + (random() - 0.5) * 0.24,
       (ARRAY[10, 20, 30])[1 + g % 3],
       CASE WHEN g % 5 = 0 THEN NULL ELSE round((3 + random() * 2)::NUMERIC, 2) END,
       CASE WHEN g % 10 = 0 THEN 'PENDING_VERIFICATION' ELSE 'VERIFIED' END
FROM generate_series(1, 300) g
JOIN city c ON c.name = CASE WHEN g % 3 = 0 THEN 'Chattogram' ELSE 'Dhaka' END;

CREATE TEMP TABLE seeded_work_order ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, g FROM generate_series(1, 30) g;

INSERT INTO work_order (id, tenant_id, title, description, status, site_address, site_latitude, site_longitude,
                        required_skills, window_starts_at, window_ends_at, budget_minor, budget_currency)
SELECT w.id,
       (SELECT id FROM company ORDER BY slug OFFSET w.g % 3 LIMIT 1),
       (ARRAY['POS terminal not connecting', 'Office printer jams', 'CCTV camera offline',
              'Branch network down', 'AC not cooling in server room'])[1 + w.g % 5],
       'Reported by the branch manager this morning. Please bring spare cables.',
       'DRAFT',
       'Branch ' || w.g || ', ' || c.name,
       c.lat + (random() - 0.5) * 0.24,
       c.lon + (random() - 0.5) * 0.24,
       ARRAY[(ARRAY['pos-terminal', 'printer', 'cctv', 'networking', 'ac-repair'])[1 + w.g % 5]],
       date_trunc('day', now()) + INTERVAL '1 day 4 hours',  -- tomorrow 10:00 Dhaka time
       date_trunc('day', now()) + INTERVAL '1 day 8 hours',
       (ARRAY[150000, 250000, 400000])[1 + w.g % 3],          -- 1,500 / 2,500 / 4,000 BDT in poisha
       'BDT'
FROM seeded_work_order w
JOIN city c ON c.name = CASE WHEN w.g % 3 = 0 THEN 'Chattogram' ELSE 'Dhaka' END;

INSERT INTO checklist_item (id, work_order_id, position, description)
SELECT gen_random_uuid(), w.id, p.position, p.description
FROM seeded_work_order w
CROSS JOIN (VALUES (0, 'Photo of the problem before starting'),
                   (1, 'Fix applied and tested with the branch staff'),
                   (2, 'Photo of the working equipment')) AS p (position, description);

COMMIT;

SELECT (SELECT count(*) FROM company) AS companies,
       (SELECT count(*) FROM technician) AS technicians,
       (SELECT count(*) FROM work_order) AS work_orders,
       (SELECT count(*) FROM checklist_item) AS checklist_items;
