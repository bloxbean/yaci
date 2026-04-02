#!/usr/bin/env bash
#
# Cross-verify yaci-node parquet exports against DBSync and/or Yaci-Store
#
# Usage:
#   ./verify.sh                              # All epochs, both sources
#   ./verify.sh 232 280                      # Specific range
#   ./verify.sh 232                           # Single epoch
#   ./verify.sh --no-store 230 240           # DBSync only
#   ./verify.sh --no-dbsync                  # Yaci-Store only
#   ./verify.sh --report verify_report.md    # Write markdown report

set -uo pipefail

# ═══ Config ═══
DBSYNC_HOST="${DBSYNC_HOST:-localhost}"; DBSYNC_PORT="${DBSYNC_PORT:-5434}"
DBSYNC_USER="${DBSYNC_USER:-postgres}";  DBSYNC_PASS="${DBSYNC_PASS:-postgres}"
DBSYNC_DB="${DBSYNC_DB:-cexplorer}"

STORE_HOST="${STORE_HOST:-localhost}";    STORE_PORT="${STORE_PORT:-5432}"
STORE_USER="${STORE_USER:-postgres}";    STORE_PASS="${STORE_PASS:-postgres}"
STORE_DB="${STORE_DB:-postgres}";        STORE_SCHEMA="${STORE_SCHEMA:-preprod}"

DATA_DIR="${DATA_DIR:-data}"
USE_DBSYNC=true; USE_STORE=true; REPORT_FILE=""

# ═══ Parse args ═══
POSITIONAL=()
i=0; ARGS=("$@")
while [ $i -lt ${#ARGS[@]} ]; do
    case "${ARGS[$i]}" in
        --no-dbsync) USE_DBSYNC=false ;;
        --no-store)  USE_STORE=false ;;
        --report)    i=$((i+1)); REPORT_FILE="${ARGS[$i]:-verify_report.md}" ;;
        *)           POSITIONAL+=("${ARGS[$i]}") ;;
    esac
    i=$((i+1))
done
set -- "${POSITIONAL[@]+"${POSITIONAL[@]}"}"

if [ $# -ge 2 ]; then FROM_EPOCH=$1; TO_EPOCH=$2
elif [ $# -eq 1 ]; then FROM_EPOCH=$1; TO_EPOCH=$1
else
    FROM_EPOCH=$(ls -d "${DATA_DIR}"/epoch=* 2>/dev/null | sed 's/.*epoch=//' | sort -n | head -1)
    TO_EPOCH=$(ls -d "${DATA_DIR}"/epoch=* 2>/dev/null | sed 's/.*epoch=//' | sort -n | tail -1)
    [ -z "${FROM_EPOCH:-}" ] && echo "No parquet data in ${DATA_DIR}/" && exit 1
fi

TMPDIR=$(mktemp -d); trap "rm -rf $TMPDIR" EXIT
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')

dbsync_q() { PGPASSWORD="${DBSYNC_PASS}" psql -h "${DBSYNC_HOST}" -p "${DBSYNC_PORT}" -U "${DBSYNC_USER}" -d "${DBSYNC_DB}" -t -A --field-separator='|' -c "$1"; }
store_q()  { PGPASSWORD="${STORE_PASS}"  psql -h "${STORE_HOST}"  -p "${STORE_PORT}"  -U "${STORE_USER}"  -d "${STORE_DB}"  -t -A --field-separator='|' -c "$1"; }

# ═══ Connectivity ═══
if $USE_DBSYNC; then
    if ! dbsync_q "SELECT 1" >/dev/null 2>&1; then
        echo "⚠ DBSync unreachable at ${DBSYNC_HOST}:${DBSYNC_PORT}, disabling"; USE_DBSYNC=false
    fi
fi
if $USE_STORE; then
    if ! store_q "SELECT 1 FROM ${STORE_SCHEMA}.drep_dist LIMIT 1" >/dev/null 2>&1; then
        echo "⚠ Yaci-Store unreachable, disabling"; USE_STORE=false
    fi
fi
if ! $USE_DBSYNC && ! $USE_STORE; then echo "No reference databases available."; exit 1; fi

SOURCES=""; $USE_DBSYNC && SOURCES="${SOURCES} DBSync"; $USE_STORE && SOURCES="${SOURCES} Yaci-Store"
declare -a RESULTS=(); ALL_PASS=true

add_result() {
    local check="$1" source="$2" raw="$3"
    local mismatched
    mismatched=$(echo "$raw" | cut -d, -f3)
    RESULTS+=("${check}|${source}|${raw}")
    [ "$mismatched" != "0" ] && ALL_PASS=false
}

echo "╔══════════════════════════════════════════════════════════════════╗"
echo "║  Yaci-Node Cross-Verification (epochs ${FROM_EPOCH}-${TO_EPOCH})"
echo "║  Sources:${SOURCES}"
$USE_DBSYNC && echo "║  DBSync:     ${DBSYNC_HOST}:${DBSYNC_PORT}/${DBSYNC_DB}"
$USE_STORE  && echo "║  Yaci-Store: ${STORE_HOST}:${STORE_PORT}/${STORE_DB}.${STORE_SCHEMA}"
echo "║  Data:       ${DATA_DIR}/"
echo "╚══════════════════════════════════════════════════════════════════╝"
echo ""

# ═══════════════════════════════════════════════════════════════
# 1. AdaPot
# ═══════════════════════════════════════════════════════════════
echo "▸ AdaPot (treasury + reserves)"
ADAPOT_DDB="CREATE TABLE ref AS SELECT * FROM read_csv('\$F',columns={'epoch':'INT','treasury':'HUGEINT','reserves':'HUGEINT'},delim='|',header=false);
WITH y AS (SELECT epoch,treasury,reserves FROM '${DATA_DIR}/epoch=*/adapot.parquet' WHERE epoch BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH})
SELECT count(*),count(*) FILTER(WHERE y.treasury=r.treasury AND y.reserves=r.reserves),count(*) FILTER(WHERE y.treasury!=r.treasury OR y.reserves!=r.reserves)
FROM y JOIN ref r ON y.epoch=r.epoch;"

if $USE_DBSYNC; then
    dbsync_q "SELECT epoch_no,treasury,reserves FROM ada_pots WHERE epoch_no BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH} ORDER BY epoch_no" > "${TMPDIR}/d_adapot.csv"
    R=$(duckdb -csv -noheader -c "${ADAPOT_DDB//\$F/${TMPDIR}/d_adapot.csv}" 2>/dev/null)
    add_result "AdaPot" "DBSync" "$R"; echo "  [DBSync]: ${R}"
fi
if $USE_STORE; then
    store_q "SELECT epoch,treasury,reserves FROM ${STORE_SCHEMA}.adapot WHERE epoch BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH} ORDER BY epoch" > "${TMPDIR}/s_adapot.csv"
    R=$(duckdb -csv -noheader -c "${ADAPOT_DDB//\$F/${TMPDIR}/s_adapot.csv}" 2>/dev/null)
    add_result "AdaPot" "Store" "$R"; echo "  [Store]:  ${R}"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# 2. DRep Distribution
# ═══════════════════════════════════════════════════════════════
DREP_FILES=$(ls "${DATA_DIR}"/epoch=*/drep_dist.parquet 2>/dev/null | wc -l | tr -d ' ')
if [ "$DREP_FILES" -gt 0 ]; then
    echo "▸ DRep Distribution (individual per-DRep)"
    DREP_DDB="CREATE TABLE ref AS SELECT * FROM read_csv('\$F',columns={'epoch':'INT','drep_type':'INT','drep_hash':'VARCHAR','amount':'HUGEINT'},delim='|',header=false);
CREATE TABLE y AS SELECT epoch,drep_type,drep_hash,amount FROM '${DATA_DIR}/epoch=*/drep_dist.parquet' WHERE epoch BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH};
SELECT count(*),count(*) FILTER(WHERE y.amount=r.amount),count(*) FILTER(WHERE y.amount!=r.amount),count(*) FILTER(WHERE r.drep_hash IS NULL),count(*) FILTER(WHERE y.drep_hash IS NULL)
FROM y FULL OUTER JOIN ref r ON y.epoch=r.epoch AND y.drep_hash=r.drep_hash AND y.drep_type=r.drep_type;"

    if $USE_DBSYNC; then
        dbsync_q "SELECT dd.epoch_no,CASE WHEN dh.has_script THEN 1 ELSE 0 END,encode(dh.raw,'hex'),dd.amount FROM drep_distr dd JOIN drep_hash dh ON dd.hash_id=dh.id WHERE dd.epoch_no BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH} AND length(encode(dh.raw,'hex'))>0 ORDER BY 1,2,3" > "${TMPDIR}/d_drep.csv"
        R=$(duckdb -csv -noheader -c "${DREP_DDB//\$F/${TMPDIR}/d_drep.csv}" 2>/dev/null)
        add_result "DRep individual" "DBSync" "$R"; echo "  [DBSync]: ${R}"
    fi
    if $USE_STORE; then
        store_q "SELECT epoch,CASE drep_type WHEN 'ADDR_KEYHASH' THEN 0 WHEN 'SCRIPT_HASH' THEN 1 ELSE -1 END,drep_hash,amount FROM ${STORE_SCHEMA}.drep_dist WHERE epoch BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH} AND drep_type IN ('ADDR_KEYHASH','SCRIPT_HASH') ORDER BY 1,2,3" > "${TMPDIR}/s_drep.csv"
        R=$(duckdb -csv -noheader -c "${DREP_DDB//\$F/${TMPDIR}/s_drep.csv}" 2>/dev/null)
        add_result "DRep individual" "Store" "$R"; echo "  [Store]:  ${R}"
    fi
    echo ""
fi

# ═══════════════════════════════════════════════════════════════
# 3. Epoch Stake
# ═══════════════════════════════════════════════════════════════
STAKE_FILES=$(ls "${DATA_DIR}"/epoch=*/epoch_stake.parquet 2>/dev/null | wc -l | tr -d ' ')
if [ "$STAKE_FILES" -gt 0 ]; then
    echo "▸ Epoch Stake (count + total per epoch)"
    STAKE_DDB="CREATE TABLE ref AS SELECT * FROM read_csv('\$F',columns={'epoch':'INT','cnt':'INT','total':'HUGEINT'},delim='|',header=false);
WITH y AS (SELECT epoch+2 as epoch,count(*) as cnt,sum(amount) as total FROM '${DATA_DIR}/epoch=*/epoch_stake.parquet' WHERE epoch BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH} GROUP BY epoch)
SELECT count(*),count(*) FILTER(WHERE y.cnt=r.cnt AND y.total=r.total),count(*) FILTER(WHERE y.cnt!=r.cnt OR y.total!=r.total) FROM y JOIN ref r ON y.epoch=r.epoch;"

    if $USE_DBSYNC; then
        dbsync_q "SELECT epoch_no,count(*),sum(amount) FROM epoch_stake WHERE epoch_no BETWEEN $((FROM_EPOCH+2)) AND $((TO_EPOCH+2)) GROUP BY epoch_no ORDER BY epoch_no" > "${TMPDIR}/d_stake.csv"
        R=$(duckdb -csv -noheader -c "${STAKE_DDB//\$F/${TMPDIR}/d_stake.csv}" 2>/dev/null)
        add_result "Epoch stake" "DBSync" "$R"; echo "  [DBSync]: ${R}"
    fi
    if $USE_STORE; then
        store_q "SELECT active_epoch,count(*),sum(amount::numeric) FROM ${STORE_SCHEMA}.epoch_stake WHERE active_epoch BETWEEN $((FROM_EPOCH+2)) AND $((TO_EPOCH+2)) GROUP BY active_epoch ORDER BY active_epoch" > "${TMPDIR}/s_stake.csv"
        R=$(duckdb -csv -noheader -c "${STAKE_DDB//\$F/${TMPDIR}/s_stake.csv}" 2>/dev/null)
        add_result "Epoch stake" "Store" "$R"; echo "  [Store]:  ${R}"
    fi
    echo ""
fi

# ═══════════════════════════════════════════════════════════════
# 4. Proposals
# ═══════════════════════════════════════════════════════════════
PROP_FILES=$(ls "${DATA_DIR}"/epoch=*/proposal_status.parquet 2>/dev/null | wc -l | tr -d ' ')
if [ "$PROP_FILES" -gt 0 ] && $USE_DBSYNC; then
    echo "▸ Proposal Ratification & Expiry"
    dbsync_q "SELECT encode(tx.hash,'hex'),gap.index,gap.type::text,gap.ratified_epoch,gap.expired_epoch FROM gov_action_proposal gap JOIN tx ON gap.tx_id=tx.id WHERE gap.ratified_epoch IS NOT NULL OR gap.expired_epoch IS NOT NULL ORDER BY gap.id" > "${TMPDIR}/d_proposals.csv"
    R=$(duckdb -csv -noheader -c "
CREATE TABLE ref AS SELECT * FROM read_csv('${TMPDIR}/d_proposals.csv',columns={'tx_hash':'VARCHAR','idx':'INT','type':'VARCHAR','ratified':'INT','expired':'INT'},delim='|',header=false,nullstr='');
CREATE TABLE y AS SELECT epoch,tx_hash,gov_action_index as idx,status FROM '${DATA_DIR}/epoch=*/proposal_status.parquet' WHERE epoch BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH};
WITH rc AS (SELECT r.*,y.epoch ye FROM ref r LEFT JOIN y ON r.tx_hash=y.tx_hash AND r.idx=y.idx AND y.status='RATIFIED' WHERE r.ratified IS NOT NULL AND r.ratified BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH}),
     ec AS (SELECT r.*,y.epoch ye FROM ref r LEFT JOIN y ON r.tx_hash=y.tx_hash AND r.idx=y.idx AND y.status='EXPIRED' WHERE r.expired IS NOT NULL AND r.expired BETWEEN ${FROM_EPOCH} AND ${TO_EPOCH})
SELECT (SELECT count(*) FROM rc)+(SELECT count(*) FROM ec),(SELECT count(*) FROM rc WHERE ye=ratified)+(SELECT count(*) FROM ec WHERE ye=expired),(SELECT count(*) FROM rc WHERE ye IS NULL OR ye!=ratified)+(SELECT count(*) FROM ec WHERE ye IS NULL OR ye!=expired);" 2>/dev/null)
    add_result "Proposals" "DBSync" "$R"; echo "  [DBSync]: ${R}"
    echo ""
fi

# ═══════════════════════════════════════════════════════════════
# 5. Three-way
# ═══════════════════════════════════════════════════════════════
if $USE_DBSYNC && $USE_STORE && [ "$DREP_FILES" -gt 0 ]; then
    echo "▸ Three-way: DBSync vs Yaci-Store (DRep dist)"
    R=$(duckdb -csv -noheader -c "
CREATE TABLE ds AS SELECT * FROM read_csv('${TMPDIR}/d_drep.csv',columns={'epoch':'INT','drep_type':'INT','drep_hash':'VARCHAR','amount':'HUGEINT'},delim='|',header=false);
CREATE TABLE st AS SELECT * FROM read_csv('${TMPDIR}/s_drep.csv',columns={'epoch':'INT','drep_type':'INT','drep_hash':'VARCHAR','amount':'HUGEINT'},delim='|',header=false);
WITH d AS (SELECT epoch,count(*) cnt,sum(amount) total FROM ds GROUP BY epoch),s AS (SELECT epoch,count(*) cnt,sum(amount) total FROM st GROUP BY epoch)
SELECT count(*),count(*) FILTER(WHERE d.cnt=s.cnt AND d.total=s.total),count(*) FILTER(WHERE d.cnt!=s.cnt OR d.total!=s.total) FROM d JOIN s ON d.epoch=s.epoch;" 2>/dev/null)
    add_result "DBSync↔Store" "3-way" "$R"; echo "  [3-way]:  ${R}"
    echo ""
fi

# ═══════════════════════════════════════════════════════════════
# Summary table
# ═══════════════════════════════════════════════════════════════
echo "══════════════════════════════════════════════════════════════════"
printf "  %-25s %-8s %8s %8s %6s\n" "CHECK" "SOURCE" "TOTAL" "MATCH" "MISS"
echo "  ───────────────────────── ──────── ──────── ──────── ──────"
for r in "${RESULTS[@]}"; do
    IFS='|' read -r check source csv <<< "$r"
    total=$(echo "$csv" | cut -d, -f1)
    matched=$(echo "$csv" | cut -d, -f2)
    miss=$(echo "$csv" | cut -d, -f3)
    icon="✓"; [ "$miss" != "0" ] && icon="✗"
    printf "  %-25s %-8s %8s %8s %5s %s\n" "$check" "$source" "$total" "$matched" "$miss" "$icon"
done
echo "══════════════════════════════════════════════════════════════════"
$ALL_PASS && echo "  ✓ All checks PASSED (epochs ${FROM_EPOCH}-${TO_EPOCH})" || echo "  ✗ Some checks FAILED"
echo "══════════════════════════════════════════════════════════════════"

# ═══ Markdown report ═══
if [ -n "${REPORT_FILE}" ]; then
    {
        echo "# Yaci-Node Verification Report"
        echo ""; echo "**Date:** ${TIMESTAMP}"; echo "**Epochs:** ${FROM_EPOCH} — ${TO_EPOCH}"; echo "**Sources:**${SOURCES}"; echo ""
        $USE_DBSYNC && echo "- DBSync: \`${DBSYNC_HOST}:${DBSYNC_PORT}/${DBSYNC_DB}\`"
        $USE_STORE  && echo "- Yaci-Store: \`${STORE_HOST}:${STORE_PORT}/${STORE_DB}.${STORE_SCHEMA}\`"
        echo "- Parquet: \`${DATA_DIR}/\`"; echo ""
        echo "## Results"; echo ""
        echo "| Check | Source | Total | Matched | Mismatched | Status |"
        echo "|-------|--------|------:|--------:|-----------:|--------|"
        for r in "${RESULTS[@]}"; do
            IFS='|' read -r check source csv <<< "$r"
            total=$(echo "$csv" | cut -d, -f1); matched=$(echo "$csv" | cut -d, -f2); miss=$(echo "$csv" | cut -d, -f3)
            st="PASS"; [ "$miss" != "0" ] && st="**FAIL**"
            echo "| ${check} | ${source} | ${total} | ${matched} | ${miss} | ${st} |"
        done
        echo ""; $ALL_PASS && echo "**Result: ALL CHECKS PASSED**" || echo "**Result: SOME CHECKS FAILED**"
    } > "${REPORT_FILE}"
    echo ""; echo "Report: ${REPORT_FILE}"
fi
