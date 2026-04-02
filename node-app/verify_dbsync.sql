-- Cross-verification queries: Yaci Parquet exports vs DBSync
-- Run with: duckdb < verify_dbsync.sql
-- Requires: DBSync postgres at localhost:5434

ATTACH 'dbname=cexplorer host=localhost port=5434 user=postgres password=postgres' AS db (TYPE postgres);

-- ===== 1. AdaPot: compare treasury + reserves at all exported epochs =====
SELECT '=== AdaPot Verification ===' as section;
SELECT y.epoch, y.treasury as yaci_treasury, d.treasury as dbsync_treasury,
       y.treasury - d.treasury as treasury_diff,
       y.reserves as yaci_reserves, d.reserves as dbsync_reserves,
       y.reserves - d.reserves as reserves_diff
FROM 'data/epoch=*/adapot.parquet' y
JOIN db.ada_pots d ON y.epoch = d.epoch_no
WHERE y.treasury != d.treasury OR y.reserves != d.reserves
ORDER BY y.epoch;

SELECT count(*) as total_epochs,
       sum(CASE WHEN y.treasury = d.treasury AND y.reserves = d.reserves THEN 1 ELSE 0 END) as matched,
       sum(CASE WHEN y.treasury != d.treasury OR y.reserves != d.reserves THEN 1 ELSE 0 END) as mismatched
FROM 'data/epoch=*/adapot.parquet' y
JOIN db.ada_pots d ON y.epoch = d.epoch_no;

-- ===== 2. DRep Distribution: per-DRep comparison at epoch 280 =====
SELECT '=== DRep Distribution @ Epoch 280 ===' as section;
SELECT y.drep_hash, y.drep_id, y.amount as yaci_amount, d.amount as dbsync_amount,
       y.amount - d.amount as diff
FROM 'data/epoch=280/drep_dist.parquet' y
JOIN db.drep_hash dh ON y.drep_hash = encode(dh.raw, 'hex')
JOIN db.drep_distr d ON d.hash_id = dh.id AND d.epoch_no = 280
WHERE y.amount != d.amount
ORDER BY abs(y.amount - d.amount) DESC;

SELECT '=== DRep Dist Summary @ 280 ===' as section;
SELECT count(*) as total_dreps,
       sum(CASE WHEN y.amount = d.amount THEN 1 ELSE 0 END) as matched,
       sum(CASE WHEN y.amount != d.amount THEN 1 ELSE 0 END) as mismatched
FROM 'data/epoch=280/drep_dist.parquet' y
JOIN db.drep_hash dh ON y.drep_hash = encode(dh.raw, 'hex')
JOIN db.drep_distr d ON d.hash_id = dh.id AND d.epoch_no = 280;

-- ===== 3. DRep Distribution counts across all epochs =====
SELECT '=== DRep Dist Count per Epoch ===' as section;
SELECT y.epoch, y.cnt as yaci_count, d.cnt as dbsync_count, y.total as yaci_total, d.total as dbsync_total
FROM (SELECT epoch, count(*) as cnt, sum(amount) as total FROM 'data/epoch=*/drep_dist.parquet' GROUP BY epoch) y
JOIN (SELECT epoch_no as epoch, count(*) as cnt, sum(amount) as total FROM db.drep_distr GROUP BY epoch_no) d ON y.epoch = d.epoch
WHERE y.cnt != d.cnt OR y.total != d.total
ORDER BY y.epoch;

-- ===== 4. Epoch Stake: delegation count comparison across epochs =====
SELECT '=== Epoch Stake Count Comparison ===' as section;
SELECT y.epoch, y.cnt as yaci_count, d.cnt as dbsync_count
FROM (SELECT epoch, count(*) as cnt FROM 'data/epoch=*/epoch_stake.parquet' GROUP BY epoch) y
JOIN (SELECT epoch_no - 2 as epoch, count(*) as cnt FROM db.epoch_stake GROUP BY epoch_no) d ON y.epoch = d.epoch
WHERE y.cnt != d.cnt
ORDER BY y.epoch;

SELECT '=== Epoch Stake Summary ===' as section;
SELECT count(*) as total_epochs,
       sum(CASE WHEN y.cnt = d.cnt THEN 1 ELSE 0 END) as matched,
       sum(CASE WHEN y.cnt != d.cnt THEN 1 ELSE 0 END) as mismatched
FROM (SELECT epoch, count(*) as cnt FROM 'data/epoch=*/epoch_stake.parquet' GROUP BY epoch) y
JOIN (SELECT epoch_no - 2 as epoch, count(*) as cnt FROM db.epoch_stake GROUP BY epoch_no) d ON y.epoch = d.epoch;

-- ===== 5. Epoch Stake: per-credential comparison at epoch 278 (=dbsync 280) =====
SELECT '=== Epoch Stake Individual @ Epoch 278 ===' as section;
SELECT y.stake_address, y.pool_id, y.amount as yaci_amount, d.amount as dbsync_amount,
       y.amount - d.amount as diff
FROM 'data/epoch=278/epoch_stake.parquet' y
JOIN db.stake_address sa ON y.stake_address = sa.view
JOIN db.epoch_stake d ON d.addr_id = sa.id AND d.epoch_no = 280
WHERE y.amount != d.amount
ORDER BY abs(y.amount - d.amount) DESC
LIMIT 20;

-- ===== 6. Proposal Status: compare ratification results =====
SELECT '=== Proposal Ratification Status ===' as section;
SELECT y.epoch, y.tx_hash, y.gov_action_index, y.action_type, y.status,
       d.ratified_epoch, d.expired_epoch
FROM 'data/epoch=*/proposal_status.parquet' y
JOIN db.tx ON substring(encode(db.tx.hash, 'hex'), 1, length(y.tx_hash)) = y.tx_hash
JOIN db.gov_action_proposal d ON d.tx_id = db.tx.id AND d.index = y.gov_action_index
WHERE y.status = 'RATIFIED'
ORDER BY y.epoch, y.tx_hash;

SELECT '=== Verification Complete ===' as section;
