# PyCardano Test for Yaci

Verifies Yaci's Blockfrost-compatible API using [PyCardano](https://pycardano.readthedocs.io/), including transaction building and submission.

## Setup

```bash
pip install -r requirements.txt
# or use a virtual environment
python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt
```

## Run

```bash
python test_yaci.py
```

## Configuration

Set `YACI_URL` to point to your Yaci instance (default: `http://localhost:9000/api/v1`):

```bash
YACI_URL=http://localhost:9000/api/v1 python test_yaci.py
```

Set `TEST_MNEMONIC` to use a different wallet (default: standard 24-word test mnemonic).

## What's Tested

**Read-only endpoints:**
- `ctx.genesis_param` — genesis parameters
- `ctx.protocol_param` — protocol parameters
- `GET /blocks/latest` — latest block info
- `GET /txs/{hash}` — transaction lookup (expects 404 for non-existent)
- `GET /genesis` — genesis endpoint

**Transaction flow:**
- `HDWallet` — derive address from mnemonic
- `ctx.utxos(address)` — query wallet UTxOs
- `TransactionBuilder` — build, sign, and submit a 2 ADA self-transfer
