package com.bloxbean.cardano.yaci.node.ledgerstate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Hardcoded pre-Allegra mainnet pool reward addresses that were shared across pools.
 * <p>
 * Before Allegra (epochs 214-237 on mainnet), pool reward addresses could be shared
 * across multiple pools. The Cardano ledger has special handling for these cases —
 * shared reward addresses do not receive rewards to match the Haskell node behavior.
 * <p>
 * Data copied from yaci-store's SharedPoolRewardAddresses. Only relevant for mainnet
 * replay (protocol magic = 764824073) through pre-Allegra epochs.
 */
public final class SharedPoolRewardAddresses {
    private SharedPoolRewardAddresses() {}

    /** Mainnet protocol magic. */
    public static final long MAINNET_MAGIC = 764824073;

    private static final Map<Integer, Set<String>> EPOCH_ADDRESSES = Map.ofEntries(
            Map.entry(214, Set.of(
                    "pool13l0j202yexqh6l0awtee9g354244gmfze09utxz0sn7p7r3ev3m",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(215, Set.of(
                    "pool17rns3wjyql9jg9xkzw9h88f0kstd693pm6urwxmvejqgsyjw7ta",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(216, Set.of(
                    "pool13l0j202yexqh6l0awtee9g354244gmfze09utxz0sn7p7r3ev3m",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(218, Set.of(
                    "pool13l0j202yexqh6l0awtee9g354244gmfze09utxz0sn7p7r3ev3m",
                    "pool17rns3wjyql9jg9xkzw9h88f0kstd693pm6urwxmvejqgsyjw7ta")),
            Map.entry(219, Set.of(
                    "pool13l0j202yexqh6l0awtee9g354244gmfze09utxz0sn7p7r3ev3m",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(220, Set.of(
                    "pool13l0j202yexqh6l0awtee9g354244gmfze09utxz0sn7p7r3ev3m")),
            Map.entry(221, Set.of(
                    "pool13l0j202yexqh6l0awtee9g354244gmfze09utxz0sn7p7r3ev3m",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(222, Set.of(
                    "pool150n38x8gquu4yt5s0s6m3ajjqe89y68x96dh32tq8a4es2l6dvp",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr",
                    "pool19066qvd5dv6vq7fh5a5l7muzk6nc5fw8zq3w4tclyrhvjvlyeuc",
                    "pool1xt7mjrtnsew3v33lu8sf93upf20sxhmcrfnpm82ra46yxk7uy45")),
            Map.entry(223, Set.of(
                    "pool150n38x8gquu4yt5s0s6m3ajjqe89y68x96dh32tq8a4es2l6dvp",
                    "pool19066qvd5dv6vq7fh5a5l7muzk6nc5fw8zq3w4tclyrhvjvlyeuc",
                    "pool1xt7mjrtnsew3v33lu8sf93upf20sxhmcrfnpm82ra46yxk7uy45")),
            Map.entry(224, Set.of(
                    "pool1xt7mjrtnsew3v33lu8sf93upf20sxhmcrfnpm82ra46yxk7uy45",
                    "pool150n38x8gquu4yt5s0s6m3ajjqe89y68x96dh32tq8a4es2l6dvp")),
            Map.entry(225, Set.of(
                    "pool150n38x8gquu4yt5s0s6m3ajjqe89y68x96dh32tq8a4es2l6dvp",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr",
                    "pool1xt7mjrtnsew3v33lu8sf93upf20sxhmcrfnpm82ra46yxk7uy45")),
            Map.entry(226, Set.of(
                    "pool150n38x8gquu4yt5s0s6m3ajjqe89y68x96dh32tq8a4es2l6dvp",
                    "pool1xt7mjrtnsew3v33lu8sf93upf20sxhmcrfnpm82ra46yxk7uy45")),
            Map.entry(227, Set.of(
                    "pool150n38x8gquu4yt5s0s6m3ajjqe89y68x96dh32tq8a4es2l6dvp",
                    "pool1xt7mjrtnsew3v33lu8sf93upf20sxhmcrfnpm82ra46yxk7uy45")),
            Map.entry(228, Set.of(
                    "pool13l0j202yexqh6l0awtee9g354244gmfze09utxz0sn7p7r3ev3m",
                    "pool1xt7mjrtnsew3v33lu8sf93upf20sxhmcrfnpm82ra46yxk7uy45",
                    "pool150n38x8gquu4yt5s0s6m3ajjqe89y68x96dh32tq8a4es2l6dvp")),
            Map.entry(230, Set.of(
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(232, Set.of(
                    "pool19w5khsnmu27au0kprw0kjm8jr7knneysj7lfkqvnu66hyz0jxsx",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(233, Set.of(
                    "pool19w5khsnmu27au0kprw0kjm8jr7knneysj7lfkqvnu66hyz0jxsx")),
            Map.entry(234, Set.of(
                    "pool19w5khsnmu27au0kprw0kjm8jr7knneysj7lfkqvnu66hyz0jxsx",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(235, Set.of(
                    "pool19w5khsnmu27au0kprw0kjm8jr7knneysj7lfkqvnu66hyz0jxsx",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(236, Set.of(
                    "pool19w5khsnmu27au0kprw0kjm8jr7knneysj7lfkqvnu66hyz0jxsx",
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr")),
            Map.entry(237, Set.of(
                    "pool166dkk9kx5y6ug9tnvh0dnvxhwt2yca3g5pd5jaqa8t39cgyqqlr"))
    );

    /**
     * Get the shared pool reward addresses without reward for a given epoch.
     * Returns empty set for non-mainnet or epochs outside the affected range.
     *
     * @param epoch        the epoch number
     * @param networkMagic the network protocol magic
     * @return set of bech32 pool IDs that should not receive shared rewards
     */
    public static HashSet<String> getSharedAddressesWithoutReward(int epoch, long networkMagic) {
        if (networkMagic != MAINNET_MAGIC) return new HashSet<>();
        var addresses = EPOCH_ADDRESSES.get(epoch);
        return addresses != null ? new HashSet<>(addresses) : new HashSet<>();
    }
}
