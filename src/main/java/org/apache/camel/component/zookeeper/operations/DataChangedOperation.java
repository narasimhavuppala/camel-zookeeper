/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.zookeeper.operations;

import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.data.Stat;

/**
 * <code>DataChangedOperation</code> is an watch driven operation. It will wait
 * for an watched event indicating that the data contained in a given
 * node has changed before optionally retrieving the changed data.
 */
@SuppressWarnings("rawtypes")
public class DataChangedOperation extends FutureEventDrivenOperation<byte[]> {

    private boolean getChangedData;

    public DataChangedOperation(ZooKeeper connection, String znode, boolean getChangedData) {
        super(connection, znode, EventType.NodeDataChanged, EventType.NodeDeleted);
        this.getChangedData = getChangedData;
    }

    @Override
    protected void installWatch() {
        connection.getData(getNode(), this, new DataCallback() {
            public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
            }
        }, null);
    }

    public OperationResult<byte[]> getResult() {
        return getChangedData ? new GetDataOperation(connection, getNode()).getResult() : null;
    }

    protected final static Class[] constructorArgs = {ZooKeeper.class, String.class, boolean.class};

    @Override
    public ZooKeeperOperation createCopy() throws Exception {
        return getClass().getConstructor(constructorArgs).newInstance(new Object[] {connection, node, getChangedData});
    }
}
