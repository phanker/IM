package com.github.yuanrw.im.connector.service;

import com.github.yuanrw.im.common.domain.conn.Conn;
import com.github.yuanrw.im.common.util.IdWorker;
import com.github.yuanrw.im.connector.domain.ClientConnContext;
import com.github.yuanrw.im.connector.handler.ConnectorTransferHandler;
import com.github.yuanrw.im.protobuf.generate.Ack;
import com.github.yuanrw.im.protobuf.generate.Chat;
import com.google.inject.Inject;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.function.BiConsumer;

/**
 * process msg the connector received
 * Date: 2019-04-08
 * Time: 21:05
 *
 * @author yrw
 */
public class ConnectorService {

    private ClientConnContext clientConnContext;

    @Inject
    public ConnectorService(ClientConnContext clientConnContext) {
        this.clientConnContext = clientConnContext;
    }

    public void doChatToClientAndFlush(Chat.ChatMsg msg) {
        Conn conn = clientConnContext.getConnByUserId(msg.getDestId());
        conn.getCtx().write(msg);
        conn.getCtx().flush();
    }

    public void doSendAckToClientAndFlush(Ack.AckMsg ackMsg) {
        Conn conn = clientConnContext.getConnByUserId(ackMsg.getDestId());
        conn.getCtx().write(ackMsg);
        conn.getCtx().flush();
    }

    public void doChatToClientOrTransferAndFlush(Chat.ChatMsg msg) {
        Conn conn = clientConnContext.getConnByUserId(msg.getDestId());

        sendMsg(conn, msg, (c, m) -> {
            conn.getCtx().write(msg);
            conn.getCtx().write(getDelivered(msg));
        });
    }

    public void doSendAckToClientOrTransferAndFlush(Ack.AckMsg ackMsg) {
        Conn conn = clientConnContext.getConnByUserId(ackMsg.getDestId());
        sendMsg(conn, ackMsg, (c, m) -> {
            conn.getCtx().write(ackMsg);
        });
    }

    public Ack.AckMsg getDelivered(Chat.ChatMsg msg) {
        return Ack.AckMsg.newBuilder()
            .setId(IdWorker.genId())
            .setVersion(1)
            .setFromId(msg.getDestId())
            .setDestId(msg.getFromId())
            .setDestType(msg.getDestType() == Chat.ChatMsg.DestType.SINGLE ? Ack.AckMsg.DestType.SINGLE : Ack.AckMsg.DestType.GROUP)
            .setCreateTime(System.currentTimeMillis())
            .setMsgType(Ack.AckMsg.MsgType.DELIVERED)
            .setAckMsgId(msg.getId())
            .build();
    }

    private void sendMsg(Conn conn, Message msg, BiConsumer<Conn, Message> ifOnTheMachine) {
        if (conn == null) {
            new ArrayList<>(ConnectorTransferHandler.getCtxList()).get(0).writeAndFlush(msg);
        } else {
            //the user is connected to this machine
            //won 't save chat histories
            ifOnTheMachine.accept(conn, msg);
            conn.getCtx().flush();
        }
    }
}