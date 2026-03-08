# MeshJS Test for Yaci

Verifies Yaci's Blockfrost-compatible API using [MeshJS](https://meshjs.dev/), including transaction building and submission.

## Setup

```bash
npm install
```

## Run

```bash
npm test
```

## Configuration

Set `YACI_URL` to point to your Yaci instance (default: `http://localhost:9000/api/v1`):

```bash
YACI_URL=http://localhost:9000/api/v1 npm test
```

Set `TEST_MNEMONIC` to use a different wallet (default: standard 24-word test mnemonic).

## What's Tested

**Read-only endpoints:**
- `GET /blocks/latest` — latest block info
- `fetchProtocolParameters()` — protocol parameters
- `GET /genesis` — genesis parameters
- `fetchTxInfo(hash)` — transaction lookup (expects 404 for non-existent)

**Transaction flow:**
- `MeshWallet` — derive address from mnemonic
- `fetchAddressUTxOs()` — query wallet UTxOs
- `MeshTxBuilder` — build, sign, and submit a 2 ADA self-transfer
