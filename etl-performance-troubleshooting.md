# ETL Performance Troubleshooting — Production Slowdown

## The Problem
- Website connected directly to production SQL started loading extremely slowly while an ETL process ran concurrently.
- ETL pulls from **8 tables**, joined via mixed **LEFT + INNER joins** directly against production, into a single staging table.
- Batch sizes: **50,000 raw rows**, **10,000 combined rows**.
- Full pull: **15–18 million rows**, taking **3+ hours**. Target: **10–45 minutes**.
- Refresh cadence: **every 24 hours**.
- Per-table extraction alone is fast (~1–1.5 min/table) — the bottleneck is the join, not the raw extraction.

## Root Causes Identified
1. **Multi-table join running directly on production** — an 8-way LEFT/INNER join across 15–18M rows is a fundamentally different (and much more expensive) operation than pulling tables individually. This is very likely both:
   - Why the ETL itself is slow.
   - Why the **website** slows down — the join holds locks/scans live tables for its full duration.
2. **Large batch transactions (50k rows)** can trigger lock escalation (SQL Server escalates around ~5,000 locks/transaction/statement by default), blocking website reads for the batch duration.
3. **Indexing uncertainty** — "most" join keys are indexed, but even one missing or wrong (non-leading-column) index on a 15–18M row table can single-handedly account for hours. Needs verification via execution plan / `STATISTICS IO`.
4. **No CDC (Change Data Capture)** — every run does a full pull instead of an incremental delta, which is unnecessary given the 24-hour refresh cadence.

## CDC (Change Data Capture) — What It Is
- Reads the SQL Server transaction log to automatically capture inserted/updated/deleted rows into system-generated change tables.
- **Not the same as SQL Server Audit** (which logs who-did-what for security/compliance, not efficient change extraction).
- With a 24-hour cadence, CDC would mean pulling only ~1 day's worth of changes instead of the full 15–18M rows — likely a small fraction of the data.

### How to check if CDC is enabled
```sql
-- 1. Is CDC enabled at the database level?
SELECT name, is_cdc_enabled 
FROM sys.databases 
WHERE name = 'YourDatabaseName';

-- 2. Which tables are tracked by CDC?
SELECT s.name AS schema_name, t.name AS table_name, t.is_tracked_by_cdc
FROM sys.tables t
JOIN sys.schemas s ON t.schema_id = s.schema_id
WHERE t.is_tracked_by_cdc = 1;

-- 3. Are the CDC capture/cleanup jobs running?
EXEC sys.sp_cdc_help_jobs;
```
Run query 1 from any DB context; queries 2 and 3 require being connected to the specific target database.

### How to enable CDC (requires sysadmin — blocked for now, owned by Finvi)
```sql
-- Database level
USE YourDatabaseName;
EXEC sys.sp_cdc_enable_db;

-- Per table (repeat for each of the 8 tables)
EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name   = N'YourTableName',
    @role_name     = NULL,
    @capture_instance = N'dbo_YourTableName',
    @supports_net_changes = 1;

-- Verify tracking
EXEC sys.sp_cdc_help_change_data_capture;

-- Confirm SQL Agent jobs exist
EXEC sys.sp_cdc_help_jobs;
```

**Current blocker:** No sysadmin access — the instance is managed by **Finvi**, so CDC enablement has to be requested from them.

### What to ask Finvi for
- Enable CDC at the database level on the specific database.
- Enable CDC on the 8 named tables (schema + table names listed).
- Confirm SQL Agent is running and CDC capture/cleanup jobs are active.
- Ask about the retention window (default 3 days — request 7+ days as a safety margin).
- Framing tip: pitch it as *reducing load on their instance* (smaller, incremental pulls vs. today's 3+ hour full scans) rather than as a new feature ask — lands better with vendor-managed platforms.

## The Recommended Approach (staging is SQL Server, not Postgres/DuckDB)
**Core shift: stop joining on production. Extract raw, join elsewhere.**

### Option A — Keep SQL Server end-to-end (start here)
1. **Extract-only from source** — no joins, no transforms, just `SELECT * FROM table` per table, run in parallel across all 8 tables, using bulk-native load paths (`BULK INSERT`/`bcp`/minimally-logged inserts). Typically 10–50x faster than batched INSERT statements. This is the only part touching Finvi's production instance going forward — and it's cheap.
2. **Do the LEFT/INNER join on the staging SQL Server instance**, not production. Same tables, same join logic — just running against a copy that isn't also serving live website traffic. This removes lock contention with the website, since production no longer holds locks for the duration of an 8-way join.
3. **Atomic-swap the joined result into the live staging table**, consistent with the staging architecture already planned.

### Option B — Add DuckDB as a transform-only hop (only if Option A's join is still too slow on staging)
- Same extract-raw-in-parallel step from production.
- Pull the raw tables into **DuckDB** just for the join/combine (embedded, columnar, often faster than SQL Server for this specific workload over 15–18M rows).
- Bulk-load the *combined* result back into SQL Server staging via `BULK INSERT`.
- More moving parts — worth it only if staging SQL Server hardware or indexing still can't make the join fast enough on its own.

5. **Once/if Finvi grants CDC**, step 1 shrinks from "pull everything" to "pull yesterday's deltas" — the rest of the pipeline (join on staging, atomic swap) stays unchanged either way.

## Interim / Parallel Improvements (available now, no vendor dependency)
- **Drop batch size** from 50,000 to 1,000–5,000 rows per transaction, commit per batch (not per full run), add a small delay (100–500ms) between batches — reduces lock contention with the live website today.
- **Check isolation level** — enabling Read Committed Snapshot Isolation (RCSI) on SQL Server lets readers avoid blocking on writers regardless of batch size.
- **Confirm lock escalation** — query `sys.dm_tran_locks` during a batch run; `OBJECT`-level locks (vs. `ROW`/`PAGE`) confirm escalation is happening.
- **Verify indexing on join keys** — run the 8-table join with `SET STATISTICS IO ON` or capture the actual execution plan (Ctrl+M in SSMS). Look for `Table Scan`/`Clustered Index Scan` on join predicates instead of `Index Seek`, and check `sys.indexes`/`sys.index_columns` to confirm join columns are the *leading* column of their index — an index existing doesn't guarantee it's usable for that specific join.
- **Ask Finvi about a read-only replica/reporting mirror** as an alternative/companion ask to CDC — often an easier "yes" for vendors, and solves the website contention problem directly since the ETL would no longer touch the same instance as the live site.
- **Timestamp-based incremental pulls** as a stopgap on any of the 8 tables that have a reliable `LastModified`/`UpdatedAt` column — no vendor involvement needed, though it has known correctness gaps (misses deletes, can break on backdated updates) and should be paired with a periodic full reconciliation run.
- **Schedule full/reconciliation runs off-peak** (e.g., 2–4 AM) since refresh is only needed every 24 hours anyway — removes contention with live website traffic for whichever pass remains heaviest.

## Suggested Priority Order
1. Get execution plan / `STATISTICS IO` on the current 8-table join — confirm exactly which table(s) are scanning instead of seeking.
2. Split the join off production (Option A): extract raw per table (parallel, bulk-native) → join on SQL Server staging, not production → atomic swap.
3. Reduce batch size + add delay + check RCSI as an immediate stopgap while the above is built.
4. Submit the CDC (or read replica) request to Finvi in parallel — it's a longer lead-time item, so start the conversation now.
5. If the join is still too slow *on staging* after Option A, add DuckDB as a transform-only hop (Option B).
6. Once CDC or a replica is granted, swap the "extract everything" step for "extract deltas only" — rest of pipeline unchanged.
