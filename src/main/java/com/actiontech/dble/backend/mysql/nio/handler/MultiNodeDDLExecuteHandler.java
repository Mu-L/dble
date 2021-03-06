/*
 * Copyright (C) 2016-2021 ActionTech.
 * based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */
package com.actiontech.dble.backend.mysql.nio.handler;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.datasource.ShardingNode;
import com.actiontech.dble.cluster.values.DDLTraceInfo;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.net.connection.BackendConnection;
import com.actiontech.dble.net.mysql.ErrorPacket;
import com.actiontech.dble.net.mysql.MySQLPacket;
import com.actiontech.dble.net.mysql.OkPacket;
import com.actiontech.dble.net.service.AbstractService;
import com.actiontech.dble.net.service.WriteFlags;
import com.actiontech.dble.route.RouteResultset;
import com.actiontech.dble.route.RouteResultsetNode;
import com.actiontech.dble.server.NonBlockingSession;
import com.actiontech.dble.services.mysqlsharding.MySQLResponseService;
import com.actiontech.dble.services.mysqlsharding.ShardingService;
import com.actiontech.dble.singleton.DDLTraceManager;
import com.actiontech.dble.singleton.TraceManager;
import com.actiontech.dble.util.FormatUtil;
import com.actiontech.dble.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * @author mycat
 */
public class MultiNodeDDLExecuteHandler extends MultiNodeQueryHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultiNodeQueryHandler.class);

    public MultiNodeDDLExecuteHandler(RouteResultset rrs, NonBlockingSession session) {
        super(rrs, session);
    }

    public void execute() throws Exception {
        TraceManager.TraceObject traceObject = TraceManager.serviceTrace(session.getShardingService(), "execute-for-ddl");
        try {
            lock.lock();
            try {
                this.reset();
                this.fieldsReturned = false;
            } finally {
                lock.unlock();
            }
            LOGGER.debug("rrs.getRunOnSlave()-" + rrs.getRunOnSlave());
            for (RouteResultsetNode node : rrs.getNodes()) {
                unResponseRrns.add(node);
            }
            DDLTraceManager.getInstance().updateDDLStatus(DDLTraceInfo.DDLStage.EXECUTE_START, session.getShardingService());
            for (final RouteResultsetNode node : rrs.getNodes()) {
                BackendConnection conn = session.getTarget(node);
                if (session.tryExistsCon(conn, node)) {
                    node.setRunOnSlave(rrs.getRunOnSlave());
                    existsConnectionExecute(conn.getBackendService(), node);
                } else {
                    connRrns.add(node);
                    node.setRunOnSlave(rrs.getRunOnSlave());
                    ShardingNode dn = DbleServer.getInstance().getConfig().getShardingNodes().get(node.getName());
                    dn.getConnection(dn.getDatabase(), session.getShardingService().isTxStart(), sessionAutocommit, node, this, node);
                }
            }
        } finally {
            TraceManager.finishSpan(session.getShardingService(), traceObject);
        }
    }

    private void existsConnectionExecute(MySQLResponseService responseService, RouteResultsetNode node) {
        TraceManager.sessionFinish(responseService);
        TraceManager.crossThread(responseService, "execute-in-exists-connection", session.getShardingService());
        innerExecute(responseService, node);
    }

    @Override
    public void errorResponse(byte[] data, @NotNull AbstractService service) {
        TraceManager.TraceObject traceObject = TraceManager.serviceTrace(service, "get-error-response");
        TraceManager.finishSpan(service, traceObject);
        DDLTraceManager.getInstance().updateConnectionStatus(session.getShardingService(), (MySQLResponseService) service,
                DDLTraceInfo.DDLConnectionStatus.CONN_EXECUTE_ERROR);
        ErrorPacket errPacket = new ErrorPacket();
        errPacket.read(data);
        err = errPacket;
        session.resetMultiStatementStatus();
        lock.lock();
        try {
            if (!isFail()) {
                setFail(new String(err.getMessage()));
            }
            if (errConnection == null) {
                errConnection = new ArrayList<>();
            }
            errConnection.add((MySQLResponseService) service);
            if (decrementToZero((MySQLResponseService) service)) {
                session.handleSpecial(rrs, false, getDDLErrorInfo());
                DDLTraceManager.getInstance().endDDL(session.getShardingService(), getDDLErrorInfo());
                LOGGER.warn("DDL execution failed");
                if (byteBuffer != null) {
                    session.getShardingService().writeDirectly(byteBuffer, WriteFlags.PART);
                }
                handleEndPacket(errPacket, false);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionClose(@NotNull AbstractService service, String reason) {
        TraceManager.TraceObject traceObject = TraceManager.serviceTrace(service, "get-connection-closed");
        TraceManager.finishSpan(service, traceObject);
        DDLTraceManager.getInstance().updateConnectionStatus(session.getShardingService(), (MySQLResponseService) service,
                DDLTraceInfo.DDLConnectionStatus.EXECUTE_CONN_CLOSE);
        if (checkClosedConn((MySQLResponseService) service)) {
            return;
        }
        LOGGER.warn("backend connect " + reason + ", conn info:" + service);
        ErrorPacket errPacket = new ErrorPacket();
        errPacket.setErrNo(ErrorCode.ER_ABORTING_CONNECTION);
        reason = "Connection {dbInstance[" + service.getConnection().getHost() + ":" + service.getConnection().getPort() + "],Schema[" + ((MySQLResponseService) service).getSchema() + "],threadID[" +
                ((MySQLResponseService) service).getConnection().getThreadId() + "]} was closed ,reason is [" + reason + "]";
        errPacket.setMessage(StringUtil.encode(reason, session.getShardingService().getCharset().getResults()));
        err = errPacket;
        session.resetMultiStatementStatus();
        lock.lock();
        try {
            RouteResultsetNode rNode = (RouteResultsetNode) ((MySQLResponseService) service).getAttachment();
            unResponseRrns.remove(rNode);
            session.getTargetMap().remove(rNode);
            ((MySQLResponseService) service).setResponseHandler(null);
            executeError((MySQLResponseService) service);
        } finally {
            lock.unlock();
        }
    }

    private void innerExecute(AbstractService service, RouteResultsetNode node) {
        //do ddl what ever the serverConnection is closed
        MySQLResponseService mysqlService = (MySQLResponseService) service;
        mysqlService.setResponseHandler(this);
        if (mysqlService.getConnection().isClosed()) {
            mysqlService.getConnection().close("DDL find connection close");
        } else {
            mysqlService.setSession(session);
            DDLTraceManager.getInstance().updateConnectionStatus(session.getShardingService(), mysqlService,
                    DDLTraceInfo.DDLConnectionStatus.CONN_EXECUTE_START);
            mysqlService.executeMultiNode(node, session.getShardingService(), sessionAutocommit && !session.getShardingService().isTxStart());
        }
    }

    @Override
    public void okResponse(byte[] data, @NotNull AbstractService service) {
        TraceManager.TraceObject traceObject = TraceManager.serviceTrace(service, "get-ok-response");
        TraceManager.finishSpan(service, traceObject);
        boolean executeResponse = ((MySQLResponseService) service).syncAndExecute();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("received ok response ,executeResponse:" + executeResponse + " from " + service);
        }
        if (executeResponse) {
            DDLTraceManager.getInstance().updateConnectionStatus(session.getShardingService(), (MySQLResponseService) service,
                    DDLTraceInfo.DDLConnectionStatus.CONN_EXECUTE_SUCCESS);
            session.setBackendResponseEndTime((MySQLResponseService) service);
            lock.lock();
            try {
                ShardingService source = session.getShardingService();
                if (!decrementToZero((MySQLResponseService) service))
                    return;
                if (isFail()) {
                    session.handleSpecial(rrs, false, null);
                    DDLTraceManager.getInstance().endDDL(source, "ddl end with execution failure");
                    LOGGER.warn("DDL execution failed");
                    session.resetMultiStatementStatus();
                    handleEndPacket(err, false);
                } else {
                    boolean metaInitial = session.handleSpecial(rrs, true, null);
                    if (!metaInitial) {
                        DDLTraceManager.getInstance().endDDL(source, "ddl end with meta failure");
                        executeMetaDataFailed(null);
                    } else {
                        session.setRowCount(0);
                        DDLTraceManager.getInstance().endDDL(source, null);
                        OkPacket ok = new OkPacket();
                        ok.read(data);
                        ok.setMessage(null);
                        ok.setAffectedRows(0);
                        ok.setServerStatus(source.isAutocommit() ? 2 : 1);
                        doSqlStat();
                        handleEndPacket(ok, true);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public void connectionError(Throwable e, Object attachment) {
        DDLTraceManager.getInstance().updateRouteNodeStatus(session.getShardingService(), (RouteResultsetNode) attachment, DDLTraceInfo.DDLConnectionStatus.EXECUTE_CONN_ERROR);
        super.connectionError(e, attachment);
    }

    protected void executeMetaDataFailed(String errMsg) {
        ErrorPacket errPacket = new ErrorPacket();
        errPacket.setErrNo(ErrorCode.ER_META_DATA);
        if (errMsg == null) {
            errMsg = "Create TABLE OK, but generate metedata failed. The reason may be that the current druid parser can not recognize part of the sql" +
                    " or the user for backend mysql does not have permission to execute the heartbeat sql.";
        }
        errPacket.setMessage(StringUtil.encode(errMsg, session.getShardingService().getCharset().getResults()));
        session.multiStatementPacket(errPacket);
        doSqlStat();
        handleEndPacket(errPacket, false);
    }

    private boolean checkClosedConn(MySQLResponseService service) {
        lock.lock();
        try {
            if (closedConnSet == null) {
                closedConnSet = new HashSet<>(1);
                closedConnSet.add(service);
            } else if (closedConnSet.contains(service)) {
                return true;
            } else {
                closedConnSet.add(service);
            }
            this.getSession().getTargetMap().remove(service.getAttachment());
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void executeError(MySQLResponseService service) {
        if (!isFail()) {
            setFail(new String(err.getMessage()));
        }
        if (errConnection == null) {
            errConnection = new ArrayList<>();
        }
        errConnection.add(service);
        if (canResponse()) {
            session.handleSpecial(rrs, false, null);
            DDLTraceManager.getInstance().endDDL(session.getShardingService(), new String(err.getMessage()));
            LOGGER.warn("DDL execution failed");
            if (byteBuffer == null) {
                handleEndPacket(err, false);
            } else {
                session.getShardingService().writeDirectly(byteBuffer, WriteFlags.PART);
                handleEndPacket(err, false);
            }
        }
    }

    private String getDDLErrorInfo() {
        StringBuilder s = new StringBuilder();
        s.append("{");
        for (int i = 0; i < errConnection.size(); i++) {
            MySQLResponseService responseService = errConnection.get(i);
            s.append("\n ").append(FormatUtil.format(i + 1, 3));
            s.append(" -> ").append(responseService.compactInfo());
        }
        s.append("\n}");

        return s.toString();
    }

    protected void handleEndPacket(MySQLPacket packet, boolean isSuccess) {
        session.clearResources(false);
        session.setResponseTime(isSuccess);
        packet.setPacketId(session.getShardingService().nextPacketId());
        packet.write(session.getSource());
    }

}
