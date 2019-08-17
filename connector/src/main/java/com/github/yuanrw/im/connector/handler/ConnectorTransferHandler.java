package com.github.yuanrw.im.connector.handler;

import com.github.yuanrw.im.common.domain.ResponseCollector;
import com.github.yuanrw.im.common.parse.AbstractMsgParser;
import com.github.yuanrw.im.common.parse.InternalParser;
import com.github.yuanrw.im.common.util.IdWorker;
import com.github.yuanrw.im.common.util.TokenGenerator;
import com.github.yuanrw.im.connector.service.ConnectorService;
import com.github.yuanrw.im.connector.service.UserStatusService;
import com.github.yuanrw.im.protobuf.generate.Ack;
import com.github.yuanrw.im.protobuf.generate.Chat;
import com.github.yuanrw.im.protobuf.generate.Internal;
import com.google.inject.Inject;
import com.google.protobuf.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.github.yuanrw.im.common.parse.AbstractMsgParser.checkDest;
import static com.github.yuanrw.im.common.parse.AbstractMsgParser.checkFrom;

/**
 * send msg to transfer
 * stateless, shareable
 * Date: 2019-02-12
 * Time: 12:17
 *
 * @author yrw
 */
public class ConnectorTransferHandler extends SimpleChannelInboundHandler<Message> {
    private static Logger logger = LoggerFactory.getLogger(ConnectorTransferHandler.class);

    private static String connectorId = TokenGenerator.generate();

    private static ConcurrentMap<Long, ResponseCollector<Internal.InternalMsg>> userStatusMsgCollectorMap = new ConcurrentHashMap<>();
    private static List<ChannelHandlerContext> ctxList = new ArrayList<>();

    private FromTransferParser fromTransferParser;
    private ConnectorService connectorService;
    private UserStatusService userStatusService;

    @Inject
    public ConnectorTransferHandler(ConnectorService connectorService, UserStatusService userStatusService) {
        this.fromTransferParser = new FromTransferParser();
        this.connectorService = connectorService;
        this.userStatusService = userStatusService;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("[ConnectorTransfer] connect to transfer");

        ctxList.add(ctx);

        Internal.InternalMsg greet = Internal.InternalMsg.newBuilder()
            .setId(IdWorker.genId())
            .setVersion(1)
            .setMsgType(Internal.InternalMsg.MsgType.GREET)
            .setMsgBody(connectorId)
            .setFrom(Internal.InternalMsg.Module.CONNECTOR)
            .setDest(Internal.InternalMsg.Module.TRANSFER)
            .setCreateTime(System.currentTimeMillis())
            .build();

        ctx.writeAndFlush(greet);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
        logger.debug("[connector] get msg: {}", msg.toString());

        checkFrom(msg, Internal.InternalMsg.Module.TRANSFER);
        checkDest(msg, Internal.InternalMsg.Module.CONNECTOR);

        fromTransferParser.parse(msg, ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        //todo: reconnect
    }

    public static Collection<ChannelHandlerContext> getCtxList() {
        if (ctxList.size() == 0) {
            logger.warn("connector is not connected to a transfer!");
        }
        return ctxList;
    }

    public static ResponseCollector<Internal.InternalMsg> createUserStatusMsgCollector(Long msgId, Duration timeout) {
        ResponseCollector<Internal.InternalMsg> collector = new ResponseCollector<>(timeout,
            "time out waiting for msg from transfer");
        userStatusMsgCollectorMap.put(msgId, collector);
        return collector;
    }

    class FromTransferParser extends AbstractMsgParser {

        @Override
        public void registerParsers() {
            InternalParser parser = new InternalParser(3);
            parser.register(Internal.InternalMsg.MsgType.ACK,
                (m, ctx) -> userStatusSyncDone(m));
            parser.register(Internal.InternalMsg.MsgType.FORCE_OFFLINE,
                (m, ctx) -> userStatusService.forceOffline(m.getMsgBody()));

            register(Chat.ChatMsg.class, (m, ctx) -> {
                connectorService.doChatToClientAndFlush(m);
                connectorService.doSendAckToClientOrTransferAndFlush(connectorService.getDelivered(m));
            });
            register(Ack.AckMsg.class, (m, ctx) -> connectorService.doSendAckToClientAndFlush(m));
            register(Internal.InternalMsg.class, parser.generateFun());
        }

        private void userStatusSyncDone(Internal.InternalMsg msg) {
            ResponseCollector<Internal.InternalMsg> collector = userStatusMsgCollectorMap.remove(Long.parseLong(msg.getMsgBody()));
            if (collector != null) {
                collector.getFuture().complete(msg);
            } else {
                logger.error("Unexpected response received: {}", msg);
            }
        }
    }
}