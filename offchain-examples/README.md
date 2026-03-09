# Yaci Offchain SDK Examples

End-to-end test scripts that verify Yaci's Blockfrost-compatible REST API works with popular Cardano offchain SDKs, including transaction building and submission.

## Prerequisites

Start the Yaci node-app in devnet mode:

```bash
# From project root
./gradlew :node-app:quarkusDev -Dquarkus.profile=devnet
```

The API will be available at `http://localhost:9000/api/v1` by default.

## MeshJS (JavaScript)

```bash
cd meshjs
npm install
npm test
```

See [meshjs/README.md](meshjs/README.md) for details.

## PyCardano (Python)

```bash
cd pycardano
pip install -r requirements.txt
python test_yaci.py
```

See [pycardano/README.md](pycardano/README.md) for details.

## Endpoints Tested

| Endpoint | Description |
|----------|-------------|
| `GET /epochs/latest/parameters` | Protocol parameters |
| `GET /addresses/{addr}/utxos` | Address UTXOs |
| `GET /blocks/latest` | Latest block |
| `GET /blocks/{hashOrNumber}` | Block by hash or number |
| `GET /txs/{hash}` | Transaction by hash |
| `GET /genesis` | Genesis parameters |
| `POST /tx/submit` | Transaction submission |

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `YACI_URL` | `http://localhost:9000/api/v1` | Yaci API base URL |
| `TEST_MNEMONIC` | 24-word test mnemonic | Wallet mnemonic for tx tests |
