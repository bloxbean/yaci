package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2CVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.OldN2CVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ProposedVersions;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ReasonVersionMismatch;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ReasonHandshakeDecodeError;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ReasonRefused;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Refuse;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.serializers.HandshakeSerializers.AcceptVersionSerializer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.serializers.HandshakeSerializers.ProposedVersionSerializer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AppOnlyHandshakeValidationTest {
    private static final long MAGIC = 424242;

    @Test
    void clientRejectsUnofferedVersionsAndWrongMagicBeforeReadiness() {
        for (AcceptVersion response : new AcceptVersion[]{
                accepted(100, MAGIC), accepted(14, MAGIC), accepted(101, MAGIC + 1),
                accepted(0x1_0000_0065L, MAGIC), accepted(101, 0x1_0000_0000L + MAGIC)}) {
            HandshakeAgent client = new HandshakeAgent(N2NVersionTableConstant.appOnly(MAGIC, true));
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            client.addListener(new HandshakeAgentListener() {
                @Override public void handshakeOk() { successes.incrementAndGet(); }
                @Override public void handshakeError(Reason reason) { errors.incrementAndGet(); }
            });
            client.sendRequest(client.buildNextMessage());
            client.receiveResponse(client.deserializeResponse(response.serialize()));
            assertEquals(0, successes.get(), response.toString());
            assertEquals(1, errors.get(), response.toString());
            assertNull(client.getProtocolVersion());
        }
    }

    @Test
    void wireRoundTripAndNegotiationRetainUnsignedMagicForAppAndCardanoVersions() {
        for (long magic : new long[]{0, Integer.MAX_VALUE, 0x8000_0000L, 0xffff_ffffL}) {
            for (boolean appOnly : new boolean[]{false, true}) {
                VersionTable table = appOnly ? N2NVersionTableConstant.appOnly(magic, true)
                        : N2NVersionTableConstant.v11AndAbove(magic);
                ProposedVersions proposal = ProposedVersionSerializer.INSTANCE.deserialize(new ProposedVersions(table).serialize());
                proposal.getVersionTable().getVersionDataMap().values()
                        .forEach(data -> assertEquals(magic, data.getNetworkMagic()));
                HandshakeAgent server = new HandshakeAgent(table, false);
                server.receiveResponse(proposal);
                AcceptVersion response = assertInstanceOf(AcceptVersion.class, server.buildNextMessage());
                AcceptVersion decoded = AcceptVersionSerializer.INSTANCE.deserialize(response.serialize());
                assertEquals(magic, decoded.getVersionData().getNetworkMagic());
                HandshakeAgent client = new HandshakeAgent(table);
                AtomicInteger successes = new AtomicInteger();
                client.addListener(new HandshakeAgentListener() {
                    @Override public void handshakeOk() { successes.incrementAndGet(); }
                });
                client.sendRequest(client.buildNextMessage());
                client.receiveResponse(decoded);
                assertEquals(1, successes.get());
                assertEquals(magic, client.getProtocolVersion().getVersionData().getNetworkMagic());
            }
        }
    }

    @Test
    void validLegacyAcceptanceWireShapesRemainSupported() {
        for (String hex : List.of(
                "830104821a00067932f4",          // Legacy V4 N2N two-field tuple.
                "83010b841a00067932f400f4",      // V11 N2N four-field tuple.
                "83011864841a00067932f400f4",    // Integrated app-layer V100.
                "83011980011a00067932")) {      // Legacy N2C scalar.
            AcceptVersion decoded = AcceptVersionSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(hex));
            assertEquals(MAGIC, decoded.getVersionData().getNetworkMagic(), hex);
            assertNotEquals(N2NVersionTableConstant.PROTOCOL_V101, decoded.getVersionNumber(), hex);
        }
    }

    @Test
    void directAcceptanceDecoderAlsoRejectsOverflowingTag() {
        assertThrows(RuntimeException.class, () -> AcceptVersionSerializer.INSTANCE.deserialize(
                HexUtil.decodeHexString("831b00000001000000011865841a00067932f400f4")));
    }

    @Test
    void sharedProposalDecoderPreservesLegacyNodeToClientUnsignedMagic() {
        VersionTable table = new VersionTable(Map.of(
                32769L, new OldN2CVersionData(0xffff_ffffL),
                32784L, new N2CVersionData(0x8000_0000L, false)));
        VersionTable decoded = ProposedVersionSerializer.INSTANCE.deserialize(
                new ProposedVersions(table).serialize()).getVersionTable();
        assertEquals(table, decoded);
    }

    @Test
    void refusalReasonsHaveStandardWireEncodingAndDecodeRoundTrip() {
        Map<Reason, String> fixtures = Map.of(
                new ReasonVersionMismatch(List.of(101L)), "82028200811865",
                new ReasonHandshakeDecodeError(101, "bad"), "82028301186563626164",
                new ReasonRefused(101, "no"), "820283021865626e6f");
        fixtures.forEach((reason, hex) -> {
            byte[] encoded = new Refuse(reason).serialize();
            assertEquals(hex, HexUtil.encodeHexString(encoded));
            assertEquals(reason, HandshkeState.Confirm.handleInbound(encoded));
        });
    }

    @Test
    void appOnlyTablesRejectOutOfRangeMagic() {
        assertThrows(IllegalArgumentException.class, () -> N2NVersionTableConstant.appOnly(-1, true));
        assertThrows(IllegalArgumentException.class, () -> N2NVersionTableConstant.appOnly(0x1_0000_0000L, false));
    }

    private AcceptVersion accepted(long version, long magic) {
        return new AcceptVersion(version, new N2NVersionData(magic, true, 0, false));
    }
}
