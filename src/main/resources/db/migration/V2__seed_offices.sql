-- Demo tenants and offices. Real deployments would manage these via the /api/v1/offices endpoint.
INSERT INTO office (tenant_id, office_code, name) VALUES
    ('acme',   'BLR-01', 'Acme Bengaluru Whitefield'),
    ('acme',   'BLR-02', 'Acme Bengaluru Outer Ring Road'),
    ('acme',   'HYD-01', 'Acme Hyderabad HITEC City'),
    ('globex', 'PUN-01', 'Globex Pune Hinjewadi'),
    ('globex', 'GGN-01', 'Globex Gurugram Cyber City');
