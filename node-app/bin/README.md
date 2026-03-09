# Yaci Node

A Cardano node implementation in Java — relay sync, local devnet, and REST API.

## Quick Start

### Relay Mode (Public Networks)

Sync from a public Cardano network and re-serve blocks on port 13337.

```bash
# Preprod (default)
./yaci-node.sh

# Mainnet
./yaci-node.sh --mainnet

# Preview
./yaci-node.sh --preview
```

Chain state is stored in `./chainstate/` (RocksDB).

### Devnet Mode (Local Block Producer)

Run a standalone local blockchain with automatic block production.

```bash
./yaci-node.sh --devnet
```

- Protocol magic: 42
- Automatic block production (configurable interval)
- Built-in faucet: `POST http://localhost:8080/api/v1/devnet/fund`
- Snapshot: `POST http://localhost:8080/api/v1/devnet/snapshot`
- Restore: `POST http://localhost:8080/api/v1/devnet/restore/{name}`
- Time advance: `POST http://localhost:8080/api/v1/devnet/time/advance`
- Rollback: `POST http://localhost:8080/api/v1/devnet/rollback`

## Key Features

- **REST API** (port 8080) — blocks, transactions, UTXOs, epochs, protocol params
- **Swagger UI** — `http://localhost:8080/q/swagger-ui`
- **Transaction submission** — `POST /api/v1/tx/submit` (CBOR or hex-encoded)
- **Plutus script evaluation** — `POST /api/v1/utils/txs/evaluate` (Ogmios-compatible)
- **Health check** — `http://localhost:8080/q/health/ready`
- **Cardano N2N server** on port 13337
- **Plugin system** — drop plugin JARs in the `plugins/` directory
- **Custom profiles** — `./yaci-node.sh --profile=<name>` or `-Dquarkus.profile=<name>`

## Configuration

### Environment Variables

Override any config property via environment variables:

```bash
YACI_NODE_SERVER_PORT=3001 ./yaci-node.sh
YACI_NODE_REMOTE_HOST=localhost YACI_NODE_REMOTE_PORT=3001 ./yaci-node.sh
```

### JVM Options (JAR mode only)

```bash
JAVA_OPTS="-Xmx4g -Xms2g" ./yaci-node.sh
```

### Config Files

The `config/` directory contains genesis files and protocol parameters for each network:

```
config/
  protocol-param.json
  network/
    devnet/
    mainnet/
    preprod/
    preview/
```

## Directory Structure

```
yaci-node.sh           Start script
yaci-node.jar          Uber-jar (JVM distribution)
yaci-node              Native binary (native distribution)
config/                Genesis and protocol parameter files
plugins/               Drop plugin JARs here
```

## More Information

- GitHub: https://github.com/bloxbean/yaci
- License: MIT
