-- Sample messages for LOCAL testing (npm run seed:local). Mimics a real Taglish dispatch chat:
-- mostly coordination noise + a couple of real incidentals, some with the trip ref omitted so the
-- sender/summary/registry context has to link them.
INSERT INTO captured_messages (source, group_name, sender, message, timestamp, raw_notif, content_hash, synced_at) VALUES
 ('viber', 'TVL X BEST', 'Dispatch', 'Job sheet: TXGU5040257 to Nutri Asia. Driver R. Ando / Helper M. Meridor', '2026-07-02T01:00:00Z', 1, 'seed01', '2026-07-02T01:00:00Z'),
 ('viber', 'TVL X BEST', 'R. Ando', 'nakaalis na po papuntang consignee', '2026-07-02T01:05:00Z', 1, 'seed02', '2026-07-02T01:05:00Z'),
 ('viber', 'TVL X BEST', 'Dispatch', 'ok ingat', '2026-07-02T01:06:00Z', 1, 'seed03', '2026-07-02T01:06:00Z'),
 ('viber', 'TVL X BEST', 'R. Ando', 'walang tao dito sa warehouse, ang tagal na naming naghihintay wala pa rin nag ooffload', '2026-07-02T03:30:00Z', 1, 'seed04', '2026-07-02T03:30:00Z'),
 ('viber', 'TVL X BEST', 'Dispatch', 'sige antay lang muna', '2026-07-02T03:35:00Z', 1, 'seed05', '2026-07-02T03:35:00Z'),
 ('viber', 'TVL X BEST', 'M. Meridor', 'need na po namin mag lalamove ng selyo kasi naiwan sa office, para di ma delay', '2026-07-02T04:00:00Z', 1, 'seed06', '2026-07-02T04:00:00Z'),
 ('viber', 'TVL X BEST', 'Dispatch', 'sige go', '2026-07-02T04:02:00Z', 1, 'seed07', '2026-07-02T04:02:00Z'),
 ('viber', 'TVL X BEST', 'R. Ando', 'grabe 4 oras kami nakatengga, sa wakas nag offload na', '2026-07-02T05:15:00Z', 1, 'seed08', '2026-07-02T05:15:00Z');
