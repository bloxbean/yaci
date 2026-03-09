"""
PyCardano end-to-end test script for Yaci's Blockfrost-compatible API.

Tests read-only endpoints AND transaction building/submission.
Requires a running Yaci node-app instance in devnet mode.

Usage:
    YACI_URL=http://localhost:9000/api/v1 python test_yaci.py
"""

import os
import sys

import requests
from blockfrost import BlockFrostApi
from pycardano import (
    Address,
    BlockFrostChainContext,
    HDWallet,
    Network,
    PaymentExtendedSigningKey,
    StakeExtendedSigningKey,
    TransactionBuilder,
    TransactionOutput,
)

BASE_URL = os.environ.get("YACI_URL", "http://localhost:9000/api/v1")

# blockfrost-python appends api_version to base_url, so we split:
#   "http://localhost:9000/api/v1" -> base="http://localhost:9000/api", version="v1"
_url_parts = BASE_URL.rsplit("/", 1)
_BF_BASE_URL = _url_parts[0] if len(_url_parts) == 2 else BASE_URL
_BF_API_VERSION = _url_parts[1] if len(_url_parts) == 2 else "v1"

# Devnet test mnemonic (has pre-funded ADA)
TEST_MNEMONIC = os.environ.get(
    "TEST_MNEMONIC",
    "test test test test test test test test test test test test test test test test test test test test test test test sauce",
)

passed = 0
failed = 0


def test(name, fn):
    global passed, failed
    try:
        fn()
        print(f"  PASS  {name}")
        passed += 1
    except Exception as e:
        print(f"  FAIL  {name}: {e}")
        failed += 1


print(f"\nTesting Yaci Blockfrost-compatible API at {BASE_URL}\n")

# Create a BlockFrost chain context pointing at Yaci.
# BlockFrostChainContext doesn't expose api_version, so we create the
# BlockFrostApi ourselves and patch it in to avoid the /v0 default.
ctx = BlockFrostChainContext.__new__(BlockFrostChainContext)
ctx._project_id = "yaci-devnet"
ctx._base_url = BASE_URL
ctx._network = Network.TESTNET
ctx.api = BlockFrostApi(
    project_id="yaci-devnet",
    base_url=_BF_BASE_URL,
    api_version=_BF_API_VERSION,
)
ctx._epoch_info = ctx.api.epoch_latest()
ctx._epoch = None
ctx._genesis_param = None
ctx._protocol_param = None

print("--- Read-Only Endpoint Tests ---\n")


# Test 1: Genesis parameters
def test_genesis_params():
    params = ctx.genesis_param
    assert params is not None, "Genesis params should not be None"
    assert params.active_slots_coefficient is not None, "Should have active_slots_coefficient"
    assert params.network_magic is not None, "Should have network_magic"
    print(f"         network_magic={params.network_magic}")


test("genesis_param", test_genesis_params)


# Test 2: Protocol parameters
def test_protocol_params():
    params = ctx.protocol_param
    assert params is not None, "Protocol params should not be None"
    assert params.min_fee_coefficient is not None, "Should have min_fee_coefficient"
    assert params.min_fee_constant is not None, "Should have min_fee_constant"
    print(f"         min_fee_coefficient={params.min_fee_coefficient}, min_fee_constant={params.min_fee_constant}")


test("protocol_param", test_protocol_params)


# Test 3: Latest block (direct API call)
def test_latest_block():
    resp = requests.get(f"{BASE_URL}/blocks/latest")
    assert resp.status_code == 200, f"blocks/latest returned {resp.status_code}"
    block = resp.json()
    assert "hash" in block, "Latest block should have a hash"
    assert "height" in block, "Latest block should have a height"
    print(f"         Block #{block['height']}, hash: {block['hash'][:16]}...")


test("blocks/latest", test_latest_block)


# Test 4: Transaction lookup (non-existent — expects 404)
def test_tx_not_found():
    fake_hash = "0" * 64
    resp = requests.get(f"{BASE_URL}/txs/{fake_hash}")
    assert resp.status_code == 404, f"Expected 404 for non-existent tx, got {resp.status_code}"


test("txs/{hash} (non-existent)", test_tx_not_found)


# Test 5: Genesis endpoint (direct API call)
def test_genesis_endpoint():
    resp = requests.get(f"{BASE_URL}/genesis")
    assert resp.status_code == 200, f"genesis returned {resp.status_code}"
    data = resp.json()
    assert "active_slots_coefficient" in data, "Should have active_slots_coefficient"
    assert "network_magic" in data, "Should have network_magic"


test("genesis (direct)", test_genesis_endpoint)

# --- Transaction Building & Submission ---

print("\n--- Transaction Building & Submission ---\n")

# Derive wallet from mnemonic using CIP-1852 derivation paths
hdwallet = HDWallet.from_mnemonic(TEST_MNEMONIC)
hdwallet_spend = hdwallet.derive_from_path("m/1852'/1815'/0'/0/0")
hdwallet_stake = hdwallet.derive_from_path("m/1852'/1815'/0'/2/0")

# Use from_hdwallet() which correctly constructs: xprivate_key + public_key + chain_code
payment_skey = PaymentExtendedSigningKey.from_hdwallet(hdwallet_spend)
payment_vkey = payment_skey.to_verification_key()
stake_skey = StakeExtendedSigningKey.from_hdwallet(hdwallet_stake)
stake_vk = stake_skey.to_verification_key()

sender_address = Address(
    payment_part=payment_vkey.hash(),
    staking_part=stake_vk.hash(),
    network=Network.TESTNET,
)
print(f"  Wallet address: {sender_address}\n")


# Test 6: Fetch wallet UTxOs
def test_wallet_utxos():
    utxos = ctx.utxos(sender_address)
    assert isinstance(utxos, list), "UTxOs should be a list"
    print(f"         Found {len(utxos)} UTxO(s)")
    if utxos:
        total_lovelace = sum(u.output.amount.coin for u in utxos)
        print(f"         Total: {total_lovelace} lovelace ({total_lovelace / 1_000_000} ADA)")


test("utxos (wallet)", test_wallet_utxos)


# Test 7: Build, sign, and submit a transaction (send 2 ADA to self)
def test_build_sign_submit():
    utxos = ctx.utxos(sender_address)
    assert len(utxos) > 0, "Wallet has no UTxOs — cannot build tx (fund this address first)"

    builder = TransactionBuilder(ctx)
    builder.add_input_address(sender_address)
    builder.add_output(TransactionOutput(sender_address, 2_000_000))

    signed_tx = builder.build_and_sign([payment_skey], change_address=sender_address)
    print(f"         Built tx: {signed_tx.id}")

    ctx.submit_tx(signed_tx)
    print(f"         Submitted tx: {signed_tx.id}")


test("buildTx + signTx + submitTx (send 2 ADA to self)", test_build_sign_submit)

print(f"\nResults: {passed} passed, {failed} failed\n")
sys.exit(1 if failed > 0 else 0)
