-- Older versions committed activation before reservation completion.
UPDATE reservations v
SET status = 'COMPLETED', updated_at = now()
FROM resources r
WHERE v.resource_id = r.resource_id AND v.status = 'PENDING' AND r.status = 'ACTIVE';
