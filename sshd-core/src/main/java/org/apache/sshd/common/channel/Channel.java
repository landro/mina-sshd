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
package org.apache.sshd.common.channel;

import java.io.IOException;

import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public interface Channel extends Closeable {

    /**
     * @return Local channel identifier
     */
    int getId();

    /**
     * @return Remote channel identifier
     */
    int getRecipient();

    Window getLocalWindow();

    Window getRemoteWindow();

    Session getSession();

    void handleClose() throws IOException;

    void handleWindowAdjust(Buffer buffer) throws IOException;

    void handleRequest(Buffer buffer) throws IOException;

    void handleData(Buffer buffer) throws IOException;

    void handleExtendedData(Buffer buffer) throws IOException;

    void handleEof() throws IOException;

    void handleFailure() throws IOException;

    @Override
    CloseFuture close(boolean immediately);

    void init(ConnectionService service, Session session, int id) throws IOException;

    /**
     * For a server channel, this method will actually open the channel
     *
     * @param recipient  Recipient identifier
     * @param rwSize     Read/Write window size
     * @param packetSize Preferred maximum packet size
     * @param buffer     Incoming {@link Buffer} that triggered the call.
     *                   <B>Note:</B> the buffer's read position is exactly
     *                   <U>after</U> the information that read to this call
     *                   was decoded
     * @return An {@link OpenFuture} for the channel open request
     */
    OpenFuture open(int recipient, int rwSize, int packetSize, Buffer buffer);

    /**
     * For a client channel, this method will be called internally by the
     * session when the confirmation has been received.
     *
     * @param recipient  Recipient identifier
     * @param rwSize     Read/Write window size
     * @param packetSize Preferred maximum packet size
     * @param buffer     Incoming {@link Buffer} that triggered the call.
     *                   <B>Note:</B> the buffer's read position is exactly
     *                   <U>after</U> the information that read to this call
     *                   was decoded
     * @throws IOException If failed to handle the success
     */
    void handleOpenSuccess(int recipient, int rwSize, int packetSize, Buffer buffer) throws IOException;

    /**
     * For a client channel, this method will be called internally by the
     * session when the server has rejected this channel opening.
     *
     * @param buffer     Incoming {@link Buffer} that triggered the call.
     *                   <B>Note:</B> the buffer's read position is exactly
     *                   <U>after</U> the information that read to this call
     *                   was decoded
     * @throws IOException If failed to handle the success
     */
    void handleOpenFailure(Buffer buffer) throws IOException;

}
