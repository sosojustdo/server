package io.moquette.spi.impl;


import io.moquette.parser.proto.messages.AbstractMessage;
import io.moquette.parser.proto.messages.PublishMessage;
import io.moquette.server.ConnectionDescriptor;
import io.moquette.spi.ClientSession;
import io.moquette.spi.IMessagesStore;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static io.moquette.parser.proto.messages.AbstractMessage.QOSType.MOST_ONE;

class InternalRepublisher {

    private static final Logger LOG = LoggerFactory.getLogger(InternalRepublisher.class);

    private final ConcurrentMap<String, ConnectionDescriptor> connectionDescriptors;
    private final PersistentQueueMessageSender persistentSender;

    InternalRepublisher(ConcurrentMap<String, ConnectionDescriptor> connectionDescriptors) {
        this.connectionDescriptors = connectionDescriptors;
        this.persistentSender = new PersistentQueueMessageSender(connectionDescriptors);
    }

    void publishRetained(ClientSession targetSession, Collection<IMessagesStore.StoredMessage> messages) {
        for (IMessagesStore.StoredMessage storedMsg : messages) {
            //fire as retained the message
            Integer packetID = storedMsg.getQos() == AbstractMessage.QOSType.MOST_ONE ? null : targetSession.nextPacketId();
            if (packetID != null) {
                LOG.trace("Adding to inflight <{}>", packetID);
                targetSession.inFlightAckWaiting(storedMsg.getGuid(), packetID);
            }
            PublishMessage publishMsg = retainedPublishForQos(storedMsg.getTopic(), storedMsg.getQos(), storedMsg.getPayload());
            //set the PacketIdentifier only for QoS > 0
            if (publishMsg.getQos() != AbstractMessage.QOSType.MOST_ONE) {
                publishMsg.setMessageID(packetID);
            }
            this.persistentSender.sendPublish(targetSession, publishMsg);
        }
    }

    void publishStored(ClientSession clientSession, List<IMessagesStore.StoredMessage> publishedEvents) {
        for (IMessagesStore.StoredMessage pubEvt : publishedEvents) {
            //put in flight zone
            LOG.trace("Adding to inflight <{}>", pubEvt.getMessageID());
            clientSession.inFlightAckWaiting(pubEvt.getGuid(), pubEvt.getMessageID());
            PublishMessage publishMsg = notRetainedPublishForQos(pubEvt.getTopic(), pubEvt.getQos(), pubEvt.getMessage());
            //set the PacketIdentifier only for QoS > 0
            if (publishMsg.getQos() != AbstractMessage.QOSType.MOST_ONE) {
                publishMsg.setMessageID(pubEvt.getMessageID());
            }
            this.persistentSender.sendPublish(clientSession, publishMsg);
        }
    }

    private PublishMessage notRetainedPublishForQos(String topic, AbstractMessage.QOSType qos, ByteBuffer message) {
        return createPublishForQos(topic, qos, message, false);
    }

    private PublishMessage retainedPublishForQos(String topic, AbstractMessage.QOSType qos, ByteBuffer message) {
        return createPublishForQos(topic, qos, message, true);
    }

    private PublishMessage createPublishForQos(String topic, AbstractMessage.QOSType qos, ByteBuffer message, boolean retained) {
        PublishMessage pubMessage = new PublishMessage();
        pubMessage.setRetainFlag(retained);
        pubMessage.setTopicName(topic);
        pubMessage.setQos(qos);
        pubMessage.setPayload(message);
        return pubMessage;
    }
}