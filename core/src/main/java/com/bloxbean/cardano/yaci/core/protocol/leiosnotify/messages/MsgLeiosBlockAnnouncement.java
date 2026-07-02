package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

import java.util.Objects;

public class MsgLeiosBlockAnnouncement implements Message {
    private final LeiosRawCbor announcement;

    public MsgLeiosBlockAnnouncement(LeiosRawCbor announcement) {
        this.announcement = Objects.requireNonNull(announcement, "announcement");
    }

    public LeiosRawCbor getAnnouncement() {
        return announcement;
    }

    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgLeiosBlockAnnouncementSerializer.INSTANCE.serialize(this);
    }
}
