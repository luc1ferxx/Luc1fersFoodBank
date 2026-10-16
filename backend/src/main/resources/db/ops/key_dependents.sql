-- Every constraint and index that involves a key column, with its definition: the inventory for widening a table's
-- keys online (docs/operations.md section 7). A key column is a column named id or ending in _id of type integer or
-- bigint; the list includes the foreign keys of other tables that reference one.
--
-- Not a Flyway migration (nothing runs it automatically). PostgreSQL only. Before a swap, keep its output:
--   psql -X -A -t -v ON_ERROR_STOP=1 -f key_dependents.sql > before.txt
-- and after the swap and the VALIDATE CONSTRAINT step run it again and compare (diff before.txt after.txt). No
-- difference means every constraint and index is back under its old name with its old definition; a NOT VALID foreign
-- key still shows the suffix until it is validated. The rows of the *_new and *_old columns of a swap in progress are
-- not listed (their names do not end in _id).
WITH key_columns AS (
    SELECT a.attrelid, a.attnum
    FROM pg_attribute a
    JOIN pg_class t ON t.oid = a.attrelid
    WHERE t.relnamespace = current_schema()::regnamespace
      AND t.relkind = 'r'
      AND a.attnum > 0
      AND NOT a.attisdropped
      AND a.atttypid IN ('integer'::regtype, 'bigint'::regtype)
      AND (a.attname = 'id' OR a.attname LIKE '%\_id')
)
SELECT c.conrelid::regclass::text  AS table_name,
       'constraint'                AS kind,
       c.conname::text             AS name,
       pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
-- NOT NULL constraints (PostgreSQL 18 records them here) follow the column through the swap.
WHERE c.contype <> 'n'
  AND (EXISTS (SELECT 1 FROM key_columns k WHERE k.attrelid = c.conrelid AND k.attnum = ANY (c.conkey))
       OR EXISTS (SELECT 1 FROM key_columns k WHERE k.attrelid = c.confrelid AND k.attnum = ANY (c.confkey)))
UNION ALL
SELECT x.indrelid::regclass::text,
       'index',
       x.indexrelid::regclass::text,
       pg_get_indexdef(x.indexrelid)
FROM pg_index x
WHERE EXISTS (SELECT 1 FROM key_columns k WHERE k.attrelid = x.indrelid AND k.attnum = ANY (x.indkey::int2[]))
  -- An index that backs a primary key or unique constraint is listed as that constraint.
  AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = x.indexrelid AND c.conrelid = x.indrelid)
ORDER BY 1, 2, 3;
