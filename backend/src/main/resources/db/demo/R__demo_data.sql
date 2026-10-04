-- Demo data as a REPEATABLE migration (R__ prefix).
--
-- Why not V100__...? Versioned seed data with a high number breaks the next schema change:
-- a new V4 would sort BEFORE the already-applied V100, and Flyway refuses out-of-order migrations.
-- Repeatable migrations always run after all versioned ones (and re-run when this file changes),
-- so every statement here must be idempotent.
-- Inventory generated from frontend/app/data/equipment.ts so frontend mock and backend match.

INSERT INTO equipment (id, title, category, make, model, year, hours, location, listing_type,
                       sale_price_cents, daily_rate_cents, weekly_rate_cents, monthly_rate_cents, description, specs)
VALUES
    ('eq-1001', '2019 Caterpillar 320 Hydraulic Excavator', 'EXCAVATOR', 'Caterpillar', '320', 2019, 4210, 'Houston, TX', 'BOTH', 16450000, 110000, 320000, 800000, 'Well-maintained 20-ton class excavator with a 42" bucket, hydraulic thumb, and full service records. Undercarriage at 70%.', '[{"name":"Operating weight","value":"22,500 kg"},{"name":"Net power","value":"162 hp"},{"name":"Max dig depth","value":"6.7 m"},{"name":"Bucket","value":"42 in"}]'::jsonb),
    ('eq-1002', '2021 Komatsu PC210LC-11 Excavator', 'EXCAVATOR', 'Komatsu', 'PC210LC-11', 2021, 2380, 'Dallas, TX', 'SALE', 18900000, NULL, NULL, NULL, 'Low-hour Komatsu with KOMTRAX telematics, long-reach arm, and auxiliary hydraulics.', '[{"name":"Operating weight","value":"23,200 kg"},{"name":"Net power","value":"165 hp"},{"name":"Max dig depth","value":"6.9 m"},{"name":"Bucket","value":"48 in"}]'::jsonb),
    ('eq-1003', '2018 John Deere 850K Crawler Dozer', 'BULLDOZER', 'John Deere', '850K', 2018, 6900, 'Phoenix, AZ', 'BOTH', 21500000, 165000, 480000, 1250000, 'Powerful mid-size dozer with a 6-way PAT blade, ripper, and enclosed cab with A/C.', '[{"name":"Operating weight","value":"20,600 kg"},{"name":"Net power","value":"205 hp"},{"name":"Blade capacity","value":"4.2 m³"},{"name":"Track gauge","value":"1,980 mm"}]'::jsonb),
    ('eq-1004', '2022 Caterpillar D6 Dozer', 'BULLDOZER', 'Caterpillar', 'D6', 2022, 1450, 'Denver, CO', 'RENT', NULL, 195000, 560000, 1480000, 'Electric-drive D6 with Cat GRADE 3D-ready, VPAT blade, and rear-view camera.', '[{"name":"Operating weight","value":"23,000 kg"},{"name":"Net power","value":"215 hp"},{"name":"Blade capacity","value":"5.6 m³"},{"name":"Transmission","value":"Electric drive"}]'::jsonb),
    ('eq-1005', '2020 Volvo L120H Wheel Loader', 'WHEEL_LOADER', 'Volvo', 'L120H', 2020, 5120, 'Atlanta, GA', 'BOTH', 14200000, 95000, 275000, 690000, 'Fuel-efficient loader with a 4.0 yd³ bucket, ride control, and scale system.', '[{"name":"Operating weight","value":"20,000 kg"},{"name":"Net power","value":"269 hp"},{"name":"Bucket capacity","value":"4.0 yd³"},{"name":"Tipping load","value":"13,400 kg"}]'::jsonb),
    ('eq-1006', '2017 Caterpillar 950M Wheel Loader', 'WHEEL_LOADER', 'Caterpillar', '950M', 2017, 9800, 'Houston, TX', 'SALE', 11900000, NULL, NULL, NULL, 'High-production loader, fresh tires, recent transmission service. Sold as-is with inspection report.', '[{"name":"Operating weight","value":"18,900 kg"},{"name":"Net power","value":"250 hp"},{"name":"Bucket capacity","value":"4.5 yd³"},{"name":"Tipping load","value":"12,100 kg"}]'::jsonb),
    ('eq-1007', '2023 Bobcat S770 Skid Steer Loader', 'SKID_STEER', 'Bobcat', 'S770', 2023, 620, 'Austin, TX', 'BOTH', 6850000, 32000, 98000, 245000, 'Nearly new skid steer with a 2-speed drive, high-flow hydraulics, and joystick controls.', '[{"name":"Rated operating capacity","value":"1,537 kg"},{"name":"Net power","value":"92 hp"},{"name":"Lift path","value":"Vertical"},{"name":"Travel speed","value":"18 km/h"}]'::jsonb),
    ('eq-1008', '2021 Kubota SVL75-2 Compact Track Loader', 'SKID_STEER', 'Kubota', 'SVL75-2', 2021, 1890, 'Nashville, TN', 'RENT', NULL, 28000, 85000, 210000, 'Versatile compact track loader with a pilot-operated joystick and a 74" bucket.', '[{"name":"Rated operating capacity","value":"1,195 kg"},{"name":"Net power","value":"74 hp"},{"name":"Lift path","value":"Vertical"},{"name":"Track width","value":"320 mm"}]'::jsonb),
    ('eq-1009', '2016 Grove RT765E-2 Rough Terrain Crane', 'CRANE', 'Grove', 'RT765E-2', 2016, 7400, 'Los Angeles, CA', 'BOTH', 39500000, 290000, 850000, 2200000, '65-ton rough terrain crane with a 33 m main boom, jib, and annual certification.', '[{"name":"Max capacity","value":"65 t"},{"name":"Main boom","value":"33.5 m"},{"name":"Max tip height","value":"50 m"},{"name":"Drive","value":"4x4"}]'::jsonb),
    ('eq-1010', '2019 Liebherr LTM 1090-4.2 All Terrain Crane', 'CRANE', 'Liebherr', 'LTM 1090-4.2', 2019, 5300, 'Seattle, WA', 'SALE', 82000000, NULL, NULL, NULL, '90-ton all-terrain crane, 60 m telescopic boom, VarioBase outrigger system.', '[{"name":"Max capacity","value":"90 t"},{"name":"Main boom","value":"60 m"},{"name":"Axles","value":"4"},{"name":"Engine","value":"Liebherr 8-cyl diesel"}]'::jsonb),
    ('eq-1011', '2020 Case 580SN Backhoe Loader', 'BACKHOE', 'Case', '580SN', 2020, 3150, 'Charlotte, NC', 'BOTH', 8900000, 38000, 115000, 290000, 'Extendahoe, 4WD, pilot controls, and a 24" bucket. Great for utilities and site prep.', '[{"name":"Operating weight","value":"7,400 kg"},{"name":"Net power","value":"97 hp"},{"name":"Dig depth (extended)","value":"5.8 m"},{"name":"Loader bucket","value":"1.1 yd³"}]'::jsonb),
    ('eq-1012', '2022 JCB 3CX Backhoe Loader', 'BACKHOE', 'JCB', '3CX', 2022, 1100, 'Orlando, FL', 'RENT', NULL, 35000, 105000, 270000, 'Compact-footprint backhoe with a 6-in-1 front bucket and powershift transmission.', '[{"name":"Operating weight","value":"8,070 kg"},{"name":"Net power","value":"109 hp"},{"name":"Dig depth","value":"5.9 m"},{"name":"Loader bucket","value":"1.3 yd³"}]'::jsonb)
ON CONFLICT (id) DO NOTHING;

-- Existing rentals (half-open [start, end)).
INSERT INTO bookings (equipment_id, renter_id, period, total_cents) VALUES
    ('eq-1001', 'demo-renter', daterange('2026-10-06', '2026-10-13', '[)'), 0),
    ('eq-1001', 'demo-renter', daterange('2026-10-13', '2026-10-20', '[)'), 0),
    ('eq-1001', 'demo-renter', daterange('2026-11-02', '2026-11-09', '[)'), 0),
    ('eq-1003', 'demo-renter', daterange('2026-10-01', '2026-10-29', '[)'), 0),
    ('eq-1004', 'demo-renter', daterange('2026-10-10', '2026-10-17', '[)'), 0),
    ('eq-1004', 'demo-renter', daterange('2026-10-17', '2026-10-24', '[)'), 0),
    ('eq-1007', 'demo-renter', daterange('2026-10-05', '2026-10-08', '[)'), 0),
    ('eq-1009', 'demo-renter', daterange('2026-11-15', '2026-12-13', '[)'), 0),
    ('eq-1012', 'demo-renter', daterange('2026-10-20', '2026-11-17', '[)'), 0)
-- No conflict target: also skips rows the EXCLUDE constraint would reject (already seeded).
ON CONFLICT DO NOTHING;

-- Demo sellers (idempotent UPDATEs).
UPDATE equipment SET seller_id = 'seller-gulfcoast'  WHERE location LIKE '%TX';
UPDATE equipment SET seller_id = 'seller-pacific'    WHERE location LIKE '%WA' OR location LIKE '%CA';
UPDATE equipment SET seller_id = 'seller-southeast'  WHERE location LIKE '%GA' OR location LIKE '%NC' OR location LIKE '%FL' OR location LIKE '%TN';
UPDATE equipment SET seller_id = 'seller-mountain'   WHERE location LIKE '%AZ' OR location LIKE '%CO';
