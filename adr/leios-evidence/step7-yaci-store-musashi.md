# Step 7: unmodified Yaci Store against Musashi (2026-10-19)

**Result: PASS.** Yaci Store `main` synced blocks 156,611–156,660 from the live Musashi relay with no code change.
All 50 blocks and 83,540 transactions match db-sync, including the 24 certifying blocks. Each certifying block
is 169 bytes on chain and carries no transactions of its own.

## Setup

- Yaci Store `main` @ `ef856acaf` (3.0.0-beta6-SNAPSHOT, Java 21, Spring Boot). **The only change:**
  `gradle/libs.versions.toml` → `yaci = "com.bloxbean.cardano:yaci:0.5.0-leios21-5f68691-SNAPSHOT"`.
- That Yaci jar is `feat/leios_complete_blocks` @ `2cad020` built with Java 21 bytecode. Yaci `next` targets Java 25
  (class version 69), which Yaci Store's Gradle 8.10 / Spring ASM cannot read yet. This is a build-level issue only,
  not a Leios one. 1,140 tests pass on that build.
- Default Yaci settings: `inlineEndorserBlockTxs = true`, `LeiosConfig` AUTO (magic 164).
- Store settings:
  - Parallel processing on (virtual threads), H2 file database.
  - Peer `leios-node.play.dev.cardano.org:3001` (node-to-node).
  - Sync window from block 156,611 (slot 3,495,626, `c6568d42…`) to block 156,660 (slot 3,496,663, `c2a847c8…`).
- `store.executor.blocks-batch-size=7`. This setting comes from how Yaci Store works and has nothing to do with Leios.
  With a stop slot, Yaci Store runs range-only (non-primary). Its parallel publisher writes a batch only when the
  batch is full; `batchDone` does not write a partial batch. On the first run, with the default batch size 100,
  only block 156,611 was stored: it is written on its own as the first block of an epoch. The other 49 blocks stayed
  in the buffer. With batches of 7, the 49 remaining blocks fill exactly 7 batches.

## What happened

- The first block, 156,611, certifies an EB, and Yaci had not seen its parent. The parent lookup used FindIntersect
  from slot 3,495,625 and found the parent at slot 3,495,605. The EB (1,767 txs) was fetched over the second
  connection, and the block reached Yaci Store complete.
- Timing: the range started at 14:55:27. Block 156,611 was processed at 14:55:52, after Yaci Store's epoch-change
  work. `batchDone` came at 14:56:11.
- Log: no `ERROR` lines. The 5 `WARN An active channel is already attached to this agent` lines come from
  `Agent.setChannel`. 2 are on Yaci Store's own tip-finder connection at startup and 3 when the parent-lookup
  connection opens. This is existing Yaci behaviour and harmless.

## Checks (H2 tables `block`, `transaction`)

- `block`: 50 rows. Slot, hash and `no_of_txs` are equal to db-sync for every block.
- `transaction`: 83,540 rows. Grouped by `block_hash`, the counts are equal to db-sync for every block.
- Positions:

| Transaction | Block | Index |
| :--- | ---: | ---: |
| `69dd28ca…b51c` | 156,613 | 343 |
| `e13b3def…df0e` | 156,613 | 344 |
| `c82dae27…2327` (last tx) | 156,614 | 5,254 |

  These are the same positions as in db-sync, so EB transactions come first and are in EB order.

## Per block

| Block | Slot | Hash | Certifying | db-sync txs | Yaci Store `no_of_txs` | Yaci Store tx rows |
| ---: | ---: | :--- | :---: | ---: | ---: | ---: |
| 156611 | 3495626 | `c6568d42…` | yes | 1,767 | 1,767 | 1,767 |
| 156612 | 3495634 | `e959076b…` |  | 354 | 354 | 354 |
| 156613 | 3495655 | `f9931423…` | yes | 345 | 345 | 345 |
| 156614 | 3495671 | `ec1e9841…` | yes | 5,255 | 5,255 | 5,255 |
| 156615 | 3495679 | `4f6517fa…` |  | 356 | 356 | 356 |
| 156616 | 3495684 | `2eefb54b…` |  | 354 | 354 | 354 |
| 156617 | 3495688 | `f8916d8f…` |  | 356 | 356 | 356 |
| 156618 | 3495738 | `6aa020bd…` |  | 354 | 354 | 354 |
| 156619 | 3495764 | `abab4416…` | yes | 5,555 | 5,555 | 5,555 |
| 156620 | 3495770 | `6c86b669…` |  | 355 | 355 | 355 |
| 156621 | 3495783 | `f813fa12…` |  | 354 | 354 | 354 |
| 156622 | 3495817 | `3979dd89…` | yes | 4,903 | 4,903 | 4,903 |
| 156623 | 3495819 | `9979e11d…` |  | 332 | 332 | 332 |
| 156624 | 3495823 | `60eb2868…` |  | 355 | 355 | 355 |
| 156625 | 3495834 | `db60f4dd…` |  | 356 | 356 | 356 |
| 156626 | 3495849 | `9d72ca10…` | yes | 2,024 | 2,024 | 2,024 |
| 156627 | 3495864 | `d69c1a58…` | yes | 775 | 775 | 775 |
| 156628 | 3495899 | `66dc08a2…` | yes | 610 | 610 | 610 |
| 156629 | 3495944 | `70038352…` | yes | 1,017 | 1,017 | 1,017 |
| 156630 | 3495949 | `f6eb7ef4…` |  | 354 | 354 | 354 |
| 156631 | 3495974 | `1c0ebe26…` |  | 354 | 354 | 354 |
| 156632 | 3495986 | `af9240ed…` |  | 354 | 354 | 354 |
| 156633 | 3496007 | `e2b26042…` | yes | 1,746 | 1,746 | 1,746 |
| 156634 | 3496009 | `d66d8feb…` |  | 356 | 356 | 356 |
| 156635 | 3496027 | `2c7c29de…` | yes | 5,555 | 5,555 | 5,555 |
| 156636 | 3496076 | `dac6ba76…` | yes | 1,734 | 1,734 | 1,734 |
| 156637 | 3496081 | `b0a1437d…` |  | 354 | 354 | 354 |
| 156638 | 3496082 | `76fc69c1…` |  | 356 | 356 | 356 |
| 156639 | 3496116 | `793d3089…` | yes | 232 | 232 | 232 |
| 156640 | 3496138 | `b928f07e…` | yes | 5,555 | 5,555 | 5,555 |
| 156641 | 3496177 | `b5ea31fc…` | yes | 4,656 | 4,656 | 4,656 |
| 156642 | 3496225 | `90b805c5…` | yes | 3,554 | 3,554 | 3,554 |
| 156643 | 3496233 | `52b9a9b1…` |  | 355 | 355 | 355 |
| 156644 | 3496279 | `23c88089…` |  | 354 | 354 | 354 |
| 156645 | 3496282 | `f1c18682…` |  | 354 | 354 | 354 |
| 156646 | 3496297 | `2648d1d3…` |  | 354 | 354 | 354 |
| 156647 | 3496304 | `72b2b5c4…` |  | 354 | 354 | 354 |
| 156648 | 3496356 | `9dbe9fc6…` | yes | 5,555 | 5,555 | 5,555 |
| 156649 | 3496358 | `f38e5af2…` |  | 354 | 354 | 354 |
| 156650 | 3496362 | `caa823d7…` |  | 324 | 324 | 324 |
| 156651 | 3496367 | `d6154a06…` |  | 357 | 357 | 357 |
| 156652 | 3496384 | `d93e668c…` | yes | 2,186 | 2,186 | 2,186 |
| 156653 | 3496397 | `78f384fc…` |  | 354 | 354 | 354 |
| 156654 | 3496417 | `364537ce…` | yes | 1,822 | 1,822 | 1,822 |
| 156655 | 3496451 | `18382e5c…` | yes | 5,555 | 5,555 | 5,555 |
| 156656 | 3496490 | `d81ebcee…` | yes | 2,800 | 2,800 | 2,800 |
| 156657 | 3496522 | `c28e702f…` | yes | 471 | 471 | 471 |
| 156658 | 3496524 | `1f610c3d…` |  | 354 | 354 | 354 |
| 156659 | 3496603 | `d1a76c65…` | yes | 5,555 | 5,555 | 5,555 |
| 156660 | 3496663 | `c2a847c8…` | yes | 5,145 | 5,145 | 5,145 |

Totals: db-sync 83,540, Yaci Store 83,540. The reference data is db-sync, via the KleioScan explorer.
