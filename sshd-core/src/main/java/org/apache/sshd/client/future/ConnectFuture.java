/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client.future;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.future.SshFuture;

/**
 * An {@link SshFuture} for asynchronous connections requests.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public interface ConnectFuture extends SshFuture<ConnectFuture> {

    /**
     * @return The referenced {@link ClientSession}
     */
    ClientSession getSession();

    /**
     * Wait and verify that connection succeeded within specified timeout
     *
     * @param count The number of time units to wait
     * @param unit  The {@link TimeUnit} for waiting
     * @return The {@link ConnectFuture}
     * @throws IOException If failed to verify the request on time
     */
    ConnectFuture verify(long count, TimeUnit unit) throws IOException;

    /**
     * @param timeout The wait timeout in milliseconds
     * @return The {@link ConnectFuture}
     * @throws IOException If failed to verify the request on time
     */
    ConnectFuture verify(long timeout) throws IOException;

    /**
     * Returns the cause of the connection failure.
     *
     * @return <code>null</code> if the connect operation is not finished yet,
     * or if the connection attempt is successful.
     */
    Throwable getException();

    /**
     * @return <code>true</code> if the connect operation is finished successfully.
     */
    boolean isConnected();

    /**
     * @return {@code true} if the connect operation has been canceled by
     * {@link #cancel()} method.
     */
    boolean isCanceled();

    /**
     * Sets the newly connected session and notifies all threads waiting for
     * this future.  This method is invoked by SSHD internally.  Please do not
     * call this method directly.
     *
     * @param session The {@link ClientSession}
     */
    void setSession(ClientSession session);

    /**
     * Sets the exception caught due to connection failure and notifies all
     * threads waiting for this future.  This method is invoked by SSHD
     * internally.  Please do not call this method directly.
     *
     * @param exception The caught {@link Throwable}
     */
    void setException(Throwable exception);

    /**
     * Cancels the connection attempt and notifies all threads waiting for
     * this future.
     */
    void cancel();

}
