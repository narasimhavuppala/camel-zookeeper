/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.zookeeper;

import static java.lang.String.format;
import static org.apache.camel.component.zookeeper.ZooKeeperUtils.getAclListFromMessage;
import static org.apache.camel.component.zookeeper.ZooKeeperUtils.getCreateMode;
import static org.apache.camel.component.zookeeper.ZooKeeperUtils.getNodeFromMessage;
import static org.apache.camel.component.zookeeper.ZooKeeperUtils.getPayloadFromExchange;
import static org.apache.camel.component.zookeeper.ZooKeeperUtils.getVersionFromMessage;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.zookeeper.operations.CreateOperation;
import org.apache.camel.component.zookeeper.operations.GetChildrenOperation;
import org.apache.camel.component.zookeeper.operations.OperationResult;
import org.apache.camel.component.zookeeper.operations.SetDataOperation;
import org.apache.camel.impl.DefaultProducer;
import org.apache.camel.util.ExchangeHelper;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.Stat;

/**
 * <code>ZookeeperProducer</code> attempts to set the content of nodes in the
 * {@link ZooKeeper} cluster with the payloads of the of the exchanges it
 * receives.
 *
 * @version $
 */
@SuppressWarnings("unchecked")
public class ZookeeperProducer extends DefaultProducer {

    private ZooKeeperConfiguration configuration;

    private ZooKeeperConnectionManager zkm;

    public ZookeeperProducer(ZooKeeperEndpoint endpoint) {
        super(endpoint);
        this.configuration = endpoint.getConfiguration();
        this.zkm = endpoint.getConnectionManager();
    }

    public void process(Exchange exchange) throws Exception {

        ZooKeeper connection = zkm.getConnection();
        ProductionContext context = new ProductionContext(connection, exchange);

        if (ExchangeHelper.isOutCapable(exchange)) {
            if (log.isDebugEnabled()) {
                log.debug(format("Storing data to znode '%s', waiting for confirmation", context.node));
            }

            OperationResult result = synchronouslySetData(context);
            if (configuration.listChildren()) {
                result = listChildren(context);
            }

            updateExchangeWithResult(context, result);
        } else {
            asynchronouslySetDataOnNode(connection, context);
        }
    }

    private void asynchronouslySetDataOnNode(ZooKeeper connection, ProductionContext context) {
        if (log.isDebugEnabled()) {
            log.debug(format("Storing data to node '%s', not waiting for confirmation", context.node));
        }
        connection.setData(context.node, context.payload, context.version, new AsyncSetDataCallback(), context);
    }

    private void updateExchangeWithResult(ProductionContext context, OperationResult result) {
        ZooKeeperMessage out = new ZooKeeperMessage(context.node, result.getStatistics());
        if (result.isOk()) {
            out.setBody(result.getResult());
        } else {
            context.exchange.setException(result.getException());
        }
        out.setHeaders(context.in.getHeaders());
        context.exchange.setOut(out);
    }

    private OperationResult listChildren(ProductionContext context) throws Exception {
        return new GetChildrenOperation(context.connection, context.node).get();
    }

    /** Simple container to avoid passing all these around as parameters */
    private class ProductionContext {
        ZooKeeper connection;
        Exchange exchange;
        Message in;
        byte[] payload;
        int version;
        String node;

        public ProductionContext(ZooKeeper connection, Exchange exchange) {
            this.connection = connection;
            this.exchange = exchange;
            this.in = exchange.getIn();
            this.node = getNodeFromMessage(in, configuration.getPath());
            this.version = getVersionFromMessage(in);
            this.payload = getPayloadFromExchange(exchange);
        }
    }

    private class AsyncSetDataCallback implements StatCallback {

        public void processResult(int rc, String node, Object ctx, Stat statistics) {
            if (Code.NONODE.equals(Code.get(rc))) {
                if (configuration.shouldCreate()) {
                    log.warn(format("Node '%s' did not exist, creating it...", node));
                    ProductionContext context = (ProductionContext)ctx;
                    OperationResult<String> result = null;
                    try {
                        result = createNode(context);
                    } catch (Exception e) {
                        log.error(format("Error trying to create node '%s'", node), e);
                    }

                    if (result == null || !result.isOk()) {
                        log.error(format("Error creating node '%s'", node), result.getException());
                    }
                }
            } else {
                logStoreComplete(node, statistics);
            }
        }
    }

    private OperationResult<String> createNode(ProductionContext ctx) throws Exception {
        CreateOperation create = new CreateOperation(ctx.connection, ctx.node);
        create.setPermissions(getAclListFromMessage(ctx.exchange.getIn()));
        CreateMode mode = getCreateMode(ctx.exchange.getIn());
        create.setCreateMode(mode == null ? CreateMode.EPHEMERAL : mode);
        create.setData(ctx.payload);
        return create.get();
    }

    /**
     * Tries to set the data first and if a no node error is received then an
     * attempt will be made to create it instead.
     */
    private OperationResult synchronouslySetData(ProductionContext ctx) throws Exception {

        SetDataOperation setData = new SetDataOperation(ctx.connection, ctx.node, ctx.payload);
        setData.setVersion(ctx.version);

        OperationResult result = setData.get();

        if (!result.isOk() && configuration.shouldCreate() && result.failedDueTo(Code.NONODE)) {
            log.warn(format("Node '%s' did not exist, creating it.", ctx.node));
            result = createNode(ctx);
        }
        return result;
    }

    private void logStoreComplete(String path, Stat statistics) {
        if (log.isDebugEnabled()) {
            if (log.isTraceEnabled()) {
                log.trace(format("Stored data to node '%s', and receive statistics %s", path, statistics));
            } else {
                log.debug(format("Stored data to node '%s'", path));
            }
        }
    }
}
