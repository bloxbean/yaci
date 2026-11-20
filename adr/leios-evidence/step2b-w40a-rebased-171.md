# Musashi BlockSync E2E Report

- Status: PASS
- Started: 2026-10-19T03:12:48.938416Z
- Completed: 2026-10-19T03:16:49.780283Z
- Duration seconds: 240
- Last progress: 2026-10-19T03:16:22.036788Z
- Host: leios-node.play.dev.cardano.org:3001
- Intersect point: [slot=3640358, hash='8984e41c9f01706813b2a87fb257cd75b94c4274bd3dd00f8d9cf055821b91a6']
- Report: build/reports/musashi-e2e/step2b-w40a-rebased-171.md

## Config

- durationSeconds: 240
- progressSeconds: 60
- noProgressTimeoutSeconds: 300
- intersectPoint: [slot=3640358, hash='8984e41c9f01706813b2a87fb257cd75b94c4274bd3dd00f8d9cf055821b91a6']
- startFromTip: false
- blockSyncLeiosMode: AUTO
- fetchTxs: true
- maxTxsPerEndorserBlock: 20000
- txsOfferWaitMillis: 2000
- deliverVotes: true
- minRankingBlocks: 1
- minDijkstraRankingBlocks: 1
- minEndorserBlocks: 0
- minCompleteEndorserBlocks: 0

## Summary

- intersectionsFound: 2
- intersectionsNotFound: 0
- rankingBlocks: 9
- uniqueRankingBlocks: 9
- dijkstraRankingBlocks: 9
- rankingBlocksByEra: {Dijkstra=9}
- firstRankingBlockNo: 163020
- lastRankingBlockNo: 163028
- firstRankingBlockSlot: 3640399
- lastRankingBlockSlot: 3640575
- rankingBlockTransactions: 1770
- rankingBlockAnnouncements: 7
- leiosCertifiedTrue: 3
- leiosCertifiedFalse: 6
- leiosCertifiedParentAnnouncementMatches: 2
- leiosCertifiedParentAnnouncementMismatches: 0
- uniqueAnnouncedEbHashes: 7
- endorserBlocks: 6
- uniqueEndorserBlockHashes: 6
- announcementEbCorrelation: 6
- completeEndorserBlocks: 0
- incompleteEndorserBlocks: 6
- endorserBlockTxRefs: 12716
- fetchedEndorserBlockTxs: 0
- parsedEndorserBlockTxs: 0
- unparsedEndorserBlockTxs: 0
- endorserBlockHashMatches: 6
- endorserBlockHashMismatches: 0
- txHashMembershipMatches: 0
- txHashMembershipMismatches: 0
- voteBatches: 82
- votes: 82
- unknownVotes: 0
- votesByFormat: {ANNOUNCING_RB_HASH=82}
- rollbacks: 1
- disconnects: 4
- parseErrors: 0
- keepAlivesSent: 7

## Intersections

- point=[slot=3640358, hash='8984e41c9f01706813b2a87fb257cd75b94c4274bd3dd00f8d9cf055821b91a6'], tip=Tip(point=[slot=3640358, hash='8984e41c9f01706813b2a87fb257cd75b94c4274bd3dd00f8d9cf055821b91a6'], block=163019)
- point=[slot=3640358, hash='8984e41c9f01706813b2a87fb257cd75b94c4274bd3dd00f8d9cf055821b91a6'], tip=Tip(point=[slot=3640358, hash='8984e41c9f01706813b2a87fb257cd75b94c4274bd3dd00f8d9cf055821b91a6'], block=163019)

## Ranking Block Samples

- blockNo=163020, slot=3640399, era=Dijkstra, txs=0, hash=6c98aa1f0fecd9a08fc00e0dc3d9812160f9618b823279d5c176232639e771f6
- blockNo=163021, slot=3640400, era=Dijkstra, txs=0, hash=9ecde6303d762fe100d6ce548a960dcb396afa518c3efdb5899ca27e917e1044
- blockNo=163022, slot=3640406, era=Dijkstra, txs=354, hash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- blockNo=163023, slot=3640411, era=Dijkstra, txs=354, hash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- blockNo=163024, slot=3640437, era=Dijkstra, txs=354, hash=799a765d7f2074a948e5478e65650139db686d7e187d5b6bad6b32a0fc94a9a4
- blockNo=163025, slot=3640468, era=Dijkstra, txs=0, hash=abc87a93a342ae200265c5ed59264ec24d953f793306279ba9dd09c588c5419d
- blockNo=163026, slot=3640472, era=Dijkstra, txs=354, hash=c478f8e070fac5b180eb3eb663e0d48e61a9b00c465538e796f24a93b38af0db
- blockNo=163027, slot=3640551, era=Dijkstra, txs=354, hash=dfe3e00d514ff825b81c349299ba4368d7c942c9a2c68977b402a36149f4d57d
- blockNo=163028, slot=3640575, era=Dijkstra, txs=0, hash=048a19fa936247bfbc077936ab7370e0580c3afa64ea21d02afe225c6619692e

## RB Announcement Samples

- rb=163022, rbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998, ebHash=c61862436f48b2cdbd6afc289757383c9c18192213da6dba703721c337de675a, ebSize=19299
- rb=163023, rbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146, ebHash=aaf7392736b16776ab3b64ee8d1430c4dd3eaf394d0b841a21df424e4dbe15b1, ebSize=127335
- rb=163024, rbHash=799a765d7f2074a948e5478e65650139db686d7e187d5b6bad6b32a0fc94a9a4, ebHash=6d51253a9f3a13515b913d2c6d896fc36f7f8ffbf2e984df9495bc37011ecaf5, ebSize=199983
- rb=163025, rbHash=abc87a93a342ae200265c5ed59264ec24d953f793306279ba9dd09c588c5419d, ebHash=7a04e94e95fd76e976067f4f4570156bd7b01089e9b22e7d46a3b381f6ca4881, ebSize=11559
- rb=163026, rbHash=c478f8e070fac5b180eb3eb663e0d48e61a9b00c465538e796f24a93b38af0db, ebHash=a55bf4b7c076355c7f5bd6f382c37246b61e13ad30437e40007c5f5cdea3704f, ebSize=7202
- rb=163027, rbHash=dfe3e00d514ff825b81c349299ba4368d7c942c9a2c68977b402a36149f4d57d, ebHash=b2752f40a81fd5c8663abaab0c7c0154468125767279c3f151fa31516625c762, ebSize=160059
- rb=163028, rbHash=048a19fa936247bfbc077936ab7370e0580c3afa64ea21d02afe225c6619692e, ebHash=98044b9dd6959b922a9429b197f73bead56d61833ed47646755f27874b55e1fa, ebSize=59691

## Endorser Block Samples

- slot=3640406, ebHash=c61862436f48b2cdbd6afc289757383c9c18192213da6dba703721c337de675a, announcedSize=19299, txRefs=536, txs=0, txsComplete=false, announcementCbor=false
- slot=3640437, ebHash=6d51253a9f3a13515b913d2c6d896fc36f7f8ffbf2e984df9495bc37011ecaf5, announcedSize=199983, txRefs=5555, txs=0, txsComplete=false, announcementCbor=false
- slot=3640468, ebHash=7a04e94e95fd76e976067f4f4570156bd7b01089e9b22e7d46a3b381f6ca4881, announcedSize=11559, txRefs=321, txs=0, txsComplete=false, announcementCbor=false
- slot=3640472, ebHash=a55bf4b7c076355c7f5bd6f382c37246b61e13ad30437e40007c5f5cdea3704f, announcedSize=7202, txRefs=200, txs=0, txsComplete=false, announcementCbor=false
- slot=3640551, ebHash=b2752f40a81fd5c8663abaab0c7c0154468125767279c3f151fa31516625c762, announcedSize=160059, txRefs=4446, txs=0, txsComplete=false, announcementCbor=false
- slot=3640575, ebHash=98044b9dd6959b922a9429b197f73bead56d61833ed47646755f27874b55e1fa, announcedSize=59691, txRefs=1658, txs=0, txsComplete=false, announcementCbor=false

## Vote Samples

- format=ANNOUNCING_RB_HASH, voter=28, slot=null, ebHash=null, announcingRbHash=8984e41c9f01706813b2a87fb257cd75b94c4274bd3dd00f8d9cf055821b91a6
- format=ANNOUNCING_RB_HASH, voter=55, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=780, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=644, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=789, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=27, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=84, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=30, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=87, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=136, slot=null, ebHash=null, announcingRbHash=a2d87802b3874703447f39aaaa729ba6f55afbf6ebbe13af2932df6cf0d1d998
- format=ANNOUNCING_RB_HASH, voter=15, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=129, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=817, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=9, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=894, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=46, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=522, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=420, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=206, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146
- format=ANNOUNCING_RB_HASH, voter=42, slot=null, ebHash=null, announcingRbHash=807e64d971fe381b3db37947d2f02591a259f221c9038a16208916d6d8d05146

## Rollback Samples

- [slot=3640358, hash='8984e41c9f01706813b2a87fb257cd75b94c4274bd3dd00f8d9cf055821b91a6']

## Parse Error Samples

- none

## Warnings

- Disconnect observed; waiting for reconnect
- Disconnect observed; waiting for reconnect
- leiosCertified=true at rb=163020 without a previous RB in the observed window

