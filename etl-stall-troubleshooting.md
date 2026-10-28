# ETL Stall Fix: Beginner Step-by-Step Guide

This guide is written for a beginner (and for Codex working with a beginner). Every step says **what to do, why, the exact command, what you should see, and what to do if it goes wrong.** Do the steps **in order**, and **change only one thing at a time** so you know what helped.

---

## 0. Rules for whoever follows this guide (human or Codex)

1. **Make a backup before changing anything** (Step 1).
2. **Change one thing, then test.** Never apply many changes at once.
3. **Never delete or drop anything** (tables, indexes, data) without saving its definition first and asking the user.
4. **Write down every result** (numbers, errors). They are needed for the next step.
5. If something is unclear (table names, role names), **look it up with the queries given here or ask the user. Do not guess.**
6. Words like `etl_user`, `target_table`, `batch_date` below are **placeholders**. Replace them with the real names.

---

## 1. The situation in plain words

- **Database:** PostgreSQL on a **Windows** server. The **website and the ETL share this one server and this one database**.
- **ETL:** loads 366 batches, one day per batch. Each batch is about **200,000 to 500,000 rows**. A full run is about **50-80 GB** of data (tables + indexes).
- **Problem:** around **batch 200-230** the ETL gets stuck, and the **website stops responding**.
- **Server:** 24 GB RAM (about 10 GB is free during the ETL), CPU stays at 50-60%, disk space is fine (C: 250 GB free, D: 450 GB free).
- **Already tried:** raised `max_connections` to 150 (did not help). `shared_buffers` is 6 GB.

### The main theory (in plain words)
Postgres keeps recently used data in memory. Your usable memory for this is only about **16 GB** (6 GB `shared_buffers` + about 10 GB Windows file cache). By batch 200-230 your table and indexes are about **28-50 GB**, so they no longer fit. Now every insert has to read and write the disk, the disk gets overloaded, and the website's queries wait in line behind the ETL. That looks like "frozen website" even though CPU and RAM look fine.

We will **prove or disprove this theory first** (Part B), then fix it (Parts C-E).

### Things to find out first (write them here)
| Item | How to find it | Value |
|---|---|---|
| Postgres version | `SELECT version();` | |
| Data folder | `SHOW data_directory;` | |
| Config file | `SHOW config_file;` | |
| ETL database role name | ask the user, or `SELECT usename, count(*) FROM pg_stat_activity GROUP BY 1;` while the ETL runs | |
| Big table name(s) | query in Step 4 | |
| How the ETL writes data (row inserts, pandas `to_sql`, `COPY`, upserts) | read the ETL code | |

---

## 2. How to run SQL on Windows (beginner)

**Option A: pgAdmin.** Open pgAdmin, connect to the server, right-click the database, choose **Query Tool**, paste the SQL, press **F5**.

**Option B: psql.** Start menu, search **SQL Shell (psql)**, press Enter through the prompts, enter the postgres password. Paste the SQL ending with `;`.

Use a **superuser account (usually `postgres`)** for the diagnostic queries. Postgres keeps a few connection slots reserved for superusers, so you can still log in even when the site is jammed.

---

# PART A: Prepare (safe, 10 minutes)

## Step 1: Back up the settings files

1. In SQL run: `SHOW data_directory;` and `SHOW config_file;`
2. Open the data folder in File Explorer.
3. Copy `postgresql.conf` and `postgresql.auto.conf` to a safe folder, e.g. `D:\backup\pg-config-2026-10-28\`.

**Why:** if a setting causes trouble, you can restore these files and restart Postgres.

## Step 2: Record the current settings

Run and save the output:
```sql
SELECT name, setting, unit, context FROM pg_settings
WHERE name IN ('shared_buffers','effective_cache_size','work_mem','maintenance_work_mem',
               'max_wal_size','min_wal_size','checkpoint_timeout','checkpoint_completion_target',
               'wal_buffers','max_connections','autovacuum_work_mem','random_page_cost',
               'autovacuum_vacuum_cost_limit','synchronous_commit');
```
Note: if `max_wal_size` is already 16 GB, **keep it** (do not lower it). If `wal_buffers` is already 16 MB, that is fine.

---

# PART B: Find out what is really wrong (measure first)

## Step 3: Add simple logging to the ETL

Make the ETL write one line per batch to a file `etl_metrics.csv` with: batch number, date, rows loaded, **seconds taken**, and the time it finished. (Codex: add this using the ETL's language, e.g. record `time.time()` before and after each batch.)

**Why:** this shows the exact batch where it starts getting slow, and whether it slows gradually (growth problem) or suddenly (resource limit).

## Step 4: Measure table and index sizes

Run now, then again every ~10 batches during a test run, and again at the stall:
```sql
SELECT relname,
       pg_size_pretty(pg_total_relation_size(relid)) AS total,
       pg_size_pretty(pg_relation_size(relid))       AS table_only,
       pg_size_pretty(pg_indexes_size(relid))        AS indexes,
       n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC
LIMIT 10;
```
**Read it:** if `indexes` for the big table is **above about 10 GB** near batch 200, memory is the problem.

## Step 5: Measure the cache hit rate

Run at about batch 150 and again at about batch 210:
```sql
SELECT round(100.0 * sum(idx_blks_hit) / nullif(sum(idx_blks_hit + idx_blks_read), 0), 2) AS index_cache_hit_pct
FROM pg_statio_user_indexes;

SELECT round(100.0 * sum(heap_blks_hit) / nullif(sum(heap_blks_hit + heap_blks_read), 0), 2) AS table_cache_hit_pct
FROM pg_statio_user_tables;
```
**Read it:**
- Falls from about 99% to **below 95%**: memory is the cause. Go on with Parts C-E.
- Stays high: memory is not the cause. Focus on locks and disk (Step 6).

## Step 6: Capture what is happening during the freeze

Start the ETL. **The moment the website freezes**, open a **separate** Query Tool/psql window and run:
```sql
SELECT pid, pg_blocking_pids(pid) AS blocked_by, usename, state,
       wait_event_type, wait_event,
       now() - query_start AS runtime, left(query, 120) AS query
FROM pg_stat_activity
WHERE state <> 'idle'
ORDER BY runtime DESC;
```
**Read it:**
- `blocked_by` has numbers: a **lock** problem. The blocking `pid` is the culprit (probably an ETL transaction).
- `wait_event_type` is `IO` or `wait_event` is `WALWrite`/`DataFileRead`: the **disk is overloaded** (matches our theory).
- Many rows `active` with empty wait_event: a slow query or a bad plan.

Also check the disk: press **Win + R**, type `resmon`, Enter, open the **Disk** tab. Note **Disk Queue Length** and **Response Time** for the drive that holds the Postgres data folder. High numbers (queue length above about 2-3, response time above about 50 ms) mean the disk is saturated.

Also check the Postgres log (the `log` folder inside the data directory, newest file). Search for: `checkpoints are occurring too frequently`, `deadlock`, `could not extend file`, `canceling statement`.

**Save all results.**

---

# PART C: Quick, safe fixes (settings and the Windows side)

Do these in order. Each one is reversible.

## Step 7: Stop antivirus from scanning the database

1. Open **Windows Security** -> **Virus & threat protection** -> **Manage settings** -> **Exclusions** -> **Add or remove exclusions**.
2. **Add folder:** the Postgres data directory (from `SHOW data_directory;`).
3. Add the ETL's temp/working folder too.

**Why:** Defender scanning thousands of database file writes can freeze everything.
**Test:** rerun the ETL and see if the stall moves.

## Step 8: Limit what the ETL can take from the website

Replace `etl_user` with the real ETL role:
```sql
ALTER ROLE etl_user CONNECTION LIMIT 5;
ALTER ROLE etl_user SET lock_timeout = '5s';        -- fail quickly instead of blocking the website
ALTER ROLE etl_user SET statement_timeout = '30min';
ALTER ROLE etl_user SET work_mem = '128MB';         -- faster sorts for the ETL only
ALTER ROLE etl_user SET maintenance_work_mem = '2GB';
ALTER ROLE etl_user SET synchronous_commit = off;   -- faster commits; worst case a crash loses the last few commits, it never corrupts data
```
These apply the **next time the ETL connects**. No restart needed.
**Undo:** `ALTER ROLE etl_user RESET ALL;`

## Step 9: Change the Postgres server settings

What can be raised and what cannot:
- `effective_cache_size` allocates nothing; it only tells the planner how much cache to expect. Set it to **16GB** (6 GB shared_buffers + about 10 GB Windows cache).
- `shared_buffers` stays at **6 GB** (8 GB at most). On Windows, bigger gives little gain and the website needs memory.
- Real cache is limited by physical RAM, so settings alone cannot fix 28-50 GB of data (see Part D).

Run:
```sql
ALTER SYSTEM SET effective_cache_size = '16GB';
ALTER SYSTEM SET maintenance_work_mem = '1GB';
ALTER SYSTEM SET autovacuum_work_mem = '1GB';
ALTER SYSTEM SET work_mem = '32MB';                 -- keep modest: it is multiplied by the website's ~80 connections
ALTER SYSTEM SET max_wal_size = '8GB';              -- SKIP this line if it is already 16GB
ALTER SYSTEM SET min_wal_size = '2GB';
ALTER SYSTEM SET checkpoint_timeout = '15min';
ALTER SYSTEM SET checkpoint_completion_target = 0.9;
SELECT pg_reload_conf();
```
Verify with `SHOW effective_cache_size;` etc.

Optional (needs a restart, minor gain): `ALTER SYSTEM SET wal_buffers = '64MB';` (skip if already 16MB).
Only if the data drive is SSD/NVMe: `ALTER SYSTEM SET random_page_cost = 1.1;` then reload.

**How to restart Postgres on Windows (only if a setting needs it):** press **Win + R**, type `services.msc`, Enter, find the service named like **postgresql-x64-16**, right-click **Restart**. Do this when the website can be briefly offline.

**Undo:** restore the backed-up `postgresql.conf` and `postgresql.auto.conf` from Step 1 and restart the service.

## Step 10: Lower the ETL's priority on Windows

- While the ETL runs: **Task Manager** -> **Details** tab -> right-click `python.exe` (the ETL) -> **Set priority** -> **Below normal**.
- Or start it from PowerShell:
  ```powershell
  $p = Start-Process python -ArgumentList "etl.py" -PassThru
  $p.PriorityClass = "BelowNormal"
  ```

## Step 11: Test run (batches around 195-235)

Run the ETL for those batches (or the full run). Check `etl_metrics.csv` and whether the website stays alive.
- **Fixed:** keep going, you can stop here.
- **Better but not fixed or no change:** continue to Part D.

---

# PART D: Fix the real cause (make the data smaller to work with)

Cache is only about 16 GB, so the real fix is to make each batch touch **less data**. Do these **one at a time**, testing after each.

## Step 12: Refresh planner statistics after every batch

At the end of each batch (after commit), run on the table (or the partition) that was just loaded:
```sql
ANALYZE target_table;
```
Also make autovacuum react sooner on the big table:
```sql
ALTER TABLE target_table SET (autovacuum_analyze_scale_factor = 0.01,
                              autovacuum_vacuum_scale_factor = 0.02);
```
**Why:** by default Postgres re-analyzes only after 10% of the table changed, which at ~100M rows is about 10M rows. Until then it plans queries using outdated numbers and may pick very slow plans.

## Step 13: Clean up connections and transactions in the ETL code

Codex: check the ETL code for these and fix them:
1. Open **one connection** (or a pool of at most 5) for the whole run. Close it in a `finally` block.
2. **Commit at the end of every batch.** Never keep one transaction open across batches.
3. Do not run schema changes (`ALTER`, `CREATE INDEX`, `TRUNCATE`, `VACUUM FULL`) on tables the website reads while the site is busy.

## Step 14: Replace the rewritten JSON progress file

Instead of reading and rewriting a JSON file every batch, append **one line per finished batch** to a `progress.jsonl` file, e.g.
```
{"batch": 215, "date": "2025-08-03", "rows": 412345, "status": "done"}
```
or store progress in a tiny database table. On restart, read the last line.

## Step 15: Load with COPY (much faster than row-by-row inserts)

If the ETL inserts row by row, with `executemany`, or with pandas `to_sql`, switch to this pattern, per batch:
1. Create a temporary staging table: `CREATE TEMP TABLE stage (LIKE target_table INCLUDING DEFAULTS);`
2. Stream the batch into it with `COPY` (in Python with psycopg2: `cursor.copy_expert("COPY stage FROM STDIN WITH (FORMAT csv)", file)`).
3. One set-based insert: `INSERT INTO target_table SELECT * FROM stage;` (if you need to skip duplicates, add `ON CONFLICT DO NOTHING` or `WHERE NOT EXISTS (...)` limited to **this batch's date only**, never the whole history).
4. Commit, then drop the temp table.

A 200-500k row batch can be one `COPY`. If the site still lags, split into 100k-row chunks with `time.sleep(0.5)` between them.

## Step 16: Reduce index cost (do this carefully)

Every index must be updated on every insert, and the cost grows as indexes outgrow memory.

1. **List the indexes and SAVE their definitions** (so nothing is lost):
   ```sql
   SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'target_table';
   ```
   Copy the output into a file `index_definitions_backup.sql`.
2. With the user, decide which indexes are **not needed for the load** (not part of a primary key/unique constraint the ETL relies on). Ask the user which ones the website needs.
3. Drop only those, run the load, then **recreate them once at the end** with the saved definitions (using the ETL role's high `maintenance_work_mem`).

**Why:** building an index once on a finished table is far cheaper than maintaining it through 366 batches.
**Warning:** the website may be slower without its indexes while the load runs. Do this in a maintenance window or only for indexes the site does not use.

## Step 17: Partition the big table by date (advanced, biggest long-term win)

Partitioning splits one huge table into many small ones (e.g. one per month). Each batch writes into one small partition whose indexes stay in memory. Old partitions go cold and cost nothing.

**Codex: first read the real schema** (`\d+ target_table` in psql) and then **ask the user before migrating**. Outline:
```sql
-- 1. New partitioned table with the same columns
CREATE TABLE target_table_new (LIKE target_table INCLUDING DEFAULTS)
  PARTITION BY RANGE (batch_date);

-- 2. One partition per month covering the full date range (repeat for each month)
CREATE TABLE target_table_2025_01 PARTITION OF target_table_new
  FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- 3. Create the needed indexes on the parent (they apply to every partition)

-- 4. Copy existing data across, check row counts match, then during a short
--    maintenance window rename: target_table -> target_table_old,
--    target_table_new -> target_table.
```
Keep `target_table_old` until the new one is verified. Primary keys and unique constraints on a partitioned table must include the partition column (`batch_date`).

## Step 18: Regular maintenance

Run `VACUUM (ANALYZE) target_table;` about every 25 batches, ideally off-peak.

---

# PART E: Verify

## Step 19: Final test

Run batches **195-235** again with logging on. It is fixed when **all** are true:
- Batch time stays roughly flat (no sharp climb after batch ~200)
- The website keeps responding for the whole run
- The Step 6 query shows no long blocked sessions, and the ETL role has 5 connections or fewer
- Resource Monitor disk queue length stays low

If not fixed, rerun the Step 6 queries during the freeze and compare to the "Read it" rules. Send the results to whoever is helping.

---

# PART F: If it is still not fixed (long term)

Running a live website and a heavy ETL on one server is the root problem. Options, best first:
1. Run the ETL on **its own server**, or move **Postgres to a dedicated server**.
2. Point website reads at a **read replica** so ETL writes never compete with site queries.
3. Put **PgBouncer** in front of the website: its 80 connections become about 20 real ones.
4. **Add RAM** (32-64 GB). It helps, but a 50-80 GB run will eventually outgrow it, so do this after Part D.
5. Put the WAL (`pg_wal`) and data on **separate drives** (only with Postgres stopped and a verified backup).

---

## Master checklist (tick in order)

- [ ] Step 1: backed up config files
- [ ] Step 2: recorded current settings
- [ ] Step 3: per-batch logging added
- [ ] Steps 4-6: measured sizes, cache hit rate, and the freeze
- [ ] Step 7: Defender exclusion
- [ ] Step 8: ETL role limits
- [ ] Step 9: Postgres settings (`effective_cache_size` = 16GB, etc.)
- [ ] Step 10: ETL priority lowered
- [ ] Step 11: test run
- [ ] Step 12: `ANALYZE` after each batch + autovacuum tuned
- [ ] Step 13: connection and transaction cleanup
- [ ] Step 14: JSONL/DB progress marker
- [ ] Step 15: `COPY` loading
- [ ] Step 16: index reduction (definitions saved first)
- [ ] Step 17: date partitioning
- [ ] Step 18: periodic `VACUUM (ANALYZE)`
- [ ] Step 19: final verification (batches 195-235)
