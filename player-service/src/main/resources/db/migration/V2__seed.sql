-- Seed data: 200 players, 5000 finished sessions, 3000 transactions.
-- Transactions are generated independently from sessions.

INSERT INTO players (username, email, country, level, balance, created_at)
SELECT 'seed_player_' || g,
       'seed_player_' || g || '@example.com',
       (ARRAY ['DE','CY','MT','UA','PL','GE','RS','KZ'])[1 + floor(random() * 8)::int],
       1 + floor(random() * 50)::int,
       round((random() * 5000)::numeric, 2),
       now() - (random() * interval '365 days')
FROM generate_series(1, 200) g;

INSERT INTO game_sessions (player_id, game, bet_amount, win_amount, status, started_at, finished_at)
SELECT 1 + floor(random() * 200)::int,
       (ARRAY ['slots','roulette','blackjack','poker','crash'])[1 + floor(random() * 5)::int],
       round((1 + random() * 99)::numeric, 2),
       CASE WHEN random() < 0.45 THEN round((random() * 300)::numeric, 2) ELSE 0 END,
       'FINISHED',
       t.ts,
       t.ts + (interval '1 minute') * ceil(random() * 30)
FROM (SELECT g, now() - (random() * interval '90 days') AS ts
      FROM generate_series(1, 5000) g) t;

INSERT INTO transactions (player_id, type, amount, created_at)
SELECT 1 + floor(random() * 200)::int,
       (ARRAY ['DEPOSIT','BET','WIN'])[1 + floor(random() * 3)::int],
       round((1 + random() * 500)::numeric, 2),
       now() - (random() * interval '90 days')
FROM generate_series(1, 3000);
