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
package org.apache.sshd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.FactoryManagerUtils;
import org.apache.sshd.common.SshdSocketAddress;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.util.BaseTestSupport;
import org.apache.sshd.util.BogusPasswordAuthenticator;
import org.apache.sshd.util.EchoShellFactory;
import org.apache.sshd.util.Utils;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Port forwarding tests
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ProxyTest extends BaseTestSupport {
    private SshServer sshd;
    private int sshPort;
    private int echoPort;
    private IoAcceptor acceptor;
    private SshClient client;

    @Before
    public void setUp() throws Exception {
        sshd = SshServer.setUpDefaultServer();
        FactoryManagerUtils.updateProperty(sshd, FactoryManager.WINDOW_SIZE, 2048);
        FactoryManagerUtils.updateProperty(sshd, FactoryManager.MAX_PACKET_SIZE, "256");
        sshd.setKeyPairProvider(Utils.createTestHostKeyProvider());
        sshd.setShellFactory(new EchoShellFactory());
        sshd.setPasswordAuthenticator(BogusPasswordAuthenticator.INSTANCE);
        sshd.setTcpipForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.start();
        sshPort = sshd.getPort();

        NioSocketAcceptor acceptor = new NioSocketAcceptor();
        acceptor.setHandler(new IoHandlerAdapter() {
            @Override
            public void messageReceived(IoSession session, Object message) throws Exception {
                IoBuffer recv = (IoBuffer) message;
                IoBuffer sent = IoBuffer.allocate(recv.remaining());
                sent.put(recv);
                sent.flip();
                session.write(sent);
            }
        });
        acceptor.setReuseAddress(true);
        acceptor.bind(new InetSocketAddress(0));
        echoPort = acceptor.getLocalAddress().getPort();
        this.acceptor = acceptor;
    }

    @After
    public void tearDown() throws Exception {
        if (sshd != null) {
            sshd.stop(true);
        }
        if (acceptor != null) {
            acceptor.dispose(true);
        }
        if (client != null) {
            client.stop();
        }
    }

    @Test
    public void testSocksProxy() throws Exception {
        try (ClientSession session = createNativeSession()) {
            SshdSocketAddress dynamic = session.startDynamicPortForwarding(new SshdSocketAddress("localhost", 0));

            String expected = getCurrentTestName();
            byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
            byte[] buf = new byte[bytes.length + Long.SIZE];
            for (int i = 0; i < 10; i++) {
                try (Socket s = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", dynamic.getPort())))) {
                    s.connect(new InetSocketAddress("localhost", echoPort));
                    s.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10L));

                    try (OutputStream sockOut = s.getOutputStream();
                         InputStream sockIn = s.getInputStream()) {

                        sockOut.write(bytes);
                        sockOut.flush();

                        int l = sockIn.read(buf);
                        assertEquals("Mismatched data at iteration " + i, expected, new String(buf, 0, l));
                    }
                }
            }

            session.stopDynamicPortForwarding(dynamic);

            try {
                try (Socket s = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", dynamic.getPort())))) {
                    s.connect(new InetSocketAddress("localhost", echoPort));
                    s.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10L));
                    s.getOutputStream().write(bytes);
                    fail("Unexpected success to write proxy data");
                }
            } catch (IOException e) {
                // expected
            }

            session.close(false).await();
        }
    }

    protected ClientSession createNativeSession() throws Exception {
        client = SshClient.setUpDefaultClient();
        FactoryManagerUtils.updateProperty(client, FactoryManager.WINDOW_SIZE, 2048);
        FactoryManagerUtils.updateProperty(client, FactoryManager.MAX_PACKET_SIZE, 256);
        client.setTcpipForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        client.start();

        ClientSession session = client.connect("sshd", "localhost", sshPort).verify(7L, TimeUnit.SECONDS).getSession();
        session.addPasswordIdentity("sshd");
        session.auth().verify();
        return session;
    }
}
