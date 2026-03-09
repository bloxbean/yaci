/**
 * MeshJS end-to-end test script for Yaci's Blockfrost-compatible API.
 *
 * Tests read-only endpoints AND transaction building/submission.
 * Requires a running Yaci node-app instance in devnet mode.
 *
 * Usage:
 *   YACI_URL=http://localhost:9000/api/v1 node src/test-yaci.mjs
 */

// Polyfill XMLHttpRequest for Node.js — MeshJS uses it internally
import xhr2 from "xhr2";
global.XMLHttpRequest = xhr2;

import http from "node:http";
import { BlockfrostProvider, MeshWallet, MeshTxBuilder } from "@meshsdk/core";

const BASE_URL = process.env.YACI_URL || "http://localhost:9000/api/v1";
const provider = new BlockfrostProvider(BASE_URL);

// Devnet test mnemonic (has pre-funded ADA)
const TEST_MNEMONIC = (
  process.env.TEST_MNEMONIC ||
  "test test test test test test test test test test test test test test test test test test test test test test test sauce"
).split(" ");

let passed = 0;
let failed = 0;

async function test(name, fn) {
  try {
    await fn();
    console.log(`  PASS  ${name}`);
    passed++;
  } catch (err) {
    console.log(`  FAIL  ${name}: ${err.message || err.code || err}`);
    failed++;
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message || "Assertion failed");
}

/** Simple HTTP GET using Node.js built-in http module */
function httpGet(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        resolve({
          ok: res.statusCode >= 200 && res.statusCode < 300,
          status: res.statusCode,
          json: () => JSON.parse(data),
        });
      });
    }).on("error", reject);
  });
}

console.log(`\nTesting Yaci Blockfrost-compatible API at ${BASE_URL}\n`);
console.log("--- Read-Only Endpoint Tests ---\n");

// Test 1: Connectivity + latest block
await test("GET /blocks/latest", async () => {
  const response = await httpGet(`${BASE_URL}/blocks/latest`);
  assert(response.ok, `blocks/latest returned ${response.status}`);
  const block = response.json();
  assert(block.hash, "Latest block should have a hash");
  console.log(`         Block #${block.height}, hash: ${block.hash.substring(0, 16)}...`);
});

// Test 2: Protocol parameters via MeshJS
await test("fetchProtocolParameters", async () => {
  const params = await provider.fetchProtocolParameters();
  assert(params, "Protocol parameters should not be null");
  assert(params.minFeeA !== undefined, "Should have minFeeA");
  assert(params.minFeeB !== undefined, "Should have minFeeB");
  console.log(`         minFeeA=${params.minFeeA}, minFeeB=${params.minFeeB}`);
});

// Test 3: Genesis endpoint
await test("GET /genesis", async () => {
  const response = await httpGet(`${BASE_URL}/genesis`);
  assert(response.ok, `genesis returned ${response.status}`);
  const genesis = response.json();
  assert(genesis.active_slots_coefficient !== undefined, "Should have active_slots_coefficient");
  assert(genesis.network_magic !== undefined, "Should have network_magic");
  console.log(`         network_magic=${genesis.network_magic}`);
});

// Test 4: Transaction lookup (non-existent)
await test("fetchTxInfo (non-existent tx)", async () => {
  const fakeTxHash = "0000000000000000000000000000000000000000000000000000000000000000";
  try {
    const txInfo = await provider.fetchTxInfo(fakeTxHash);
    assert(!txInfo, "Non-existent tx should return null/undefined");
  } catch {
    // Expected — API returns 404
  }
});

// --- Transaction Building & Submission ---

console.log("\n--- Transaction Building & Submission ---\n");

// Initialize wallet from mnemonic
const wallet = new MeshWallet({
  networkId: 0, // testnet
  fetcher: provider,
  submitter: provider,
  key: {
    type: "mnemonic",
    words: TEST_MNEMONIC,
  },
});
await wallet.init();

let walletAddress;

// Test 5: Get wallet address
await test("getChangeAddress", async () => {
  walletAddress = await wallet.getChangeAddress();
  assert(walletAddress, "Wallet should have a change address");
  console.log(`         Address: ${walletAddress.substring(0, 40)}...`);
});

// Test 6: Fetch wallet UTxOs
await test("fetchAddressUTxOs (wallet)", async () => {
  const utxos = await provider.fetchAddressUTxOs(walletAddress);
  assert(Array.isArray(utxos), "UTxOs should be an array");
  console.log(`         Found ${utxos.length} UTxO(s)`);
  if (utxos.length > 0) {
    const totalLovelace = utxos.reduce((sum, u) => {
      const lovelace = u.output.amount.find((a) => a.unit === "lovelace");
      return sum + BigInt(lovelace?.quantity || "0");
    }, 0n);
    console.log(`         Total: ${totalLovelace} lovelace (${Number(totalLovelace) / 1_000_000} ADA)`);
  }
});

// Test 7: Build, sign, and submit a transaction (send 2 ADA to self)
await test("buildTx + signTx + submitTx (send 2 ADA to self)", async () => {
  // Verify we have UTxOs to spend
  const utxos = await provider.fetchAddressUTxOs(walletAddress);
  assert(utxos.length > 0, "Wallet has no UTxOs — cannot build tx");

  // Build a simple tx: send 2 ADA back to own address
  // Note: only pass evaluator if using Plutus scripts (it calls utils/txs/evaluate)
  const txBuilder = new MeshTxBuilder({
    fetcher: provider,
  });

  const unsignedTx = await txBuilder
    .txOut(walletAddress, [{ unit: "lovelace", quantity: "2000000" }])
    .changeAddress(walletAddress)
    .selectUtxosFrom(utxos)
    .complete();

  assert(unsignedTx, "Transaction build should return unsigned tx hex");
  console.log(`         Built tx: ${unsignedTx.substring(0, 32)}...`);

  // Sign the transaction
  const signedTx = await wallet.signTx(unsignedTx);
  assert(signedTx, "Signing should return signed tx hex");

  // Submit the transaction
  const txHash = await wallet.submitTx(signedTx);
  assert(txHash, "Submit should return a tx hash");
  console.log(`         Submitted tx: ${txHash}`);
});

console.log(`\nResults: ${passed} passed, ${failed} failed\n`);
process.exit(failed > 0 ? 1 : 0);
