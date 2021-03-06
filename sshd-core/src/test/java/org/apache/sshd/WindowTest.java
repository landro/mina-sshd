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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.FactoryManagerUtils;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.RuntimeSshException;
import org.apache.sshd.common.Service;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.Window;
import org.apache.sshd.common.io.IoReadFuture;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.channel.ChannelSessionFactory;
import org.apache.sshd.server.command.UnknownCommand;
import org.apache.sshd.server.forward.DirectTcpipFactory;
import org.apache.sshd.server.session.ServerConnectionService;
import org.apache.sshd.server.session.ServerConnectionServiceFactory;
import org.apache.sshd.server.session.ServerUserAuthService;
import org.apache.sshd.server.session.ServerUserAuthServiceFactory;
import org.apache.sshd.util.AsyncEchoShellFactory;
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
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WindowTest extends BaseTestSupport {

    private SshServer sshd;
    private SshClient client;
    private int port;
    private CountDownLatch authLatch;
    private CountDownLatch channelLatch;

    @Before
    public void setUp() throws Exception {
        authLatch = new CountDownLatch(0);
        channelLatch = new CountDownLatch(0);

        sshd = SshServer.setUpDefaultServer();
        sshd.setKeyPairProvider(Utils.createTestHostKeyProvider());
        sshd.setShellFactory(new TestEchoShellFactory());
        sshd.setCommandFactory(new CommandFactory() {
            @Override
            public Command createCommand(String command) {
                return new UnknownCommand(command);
            }
        });
        sshd.setPasswordAuthenticator(BogusPasswordAuthenticator.INSTANCE);
        sshd.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshd.setServiceFactories(Arrays.asList(
                new ServerUserAuthServiceFactory() {
                    @Override
                    public Service create(Session session) throws IOException {
                        return new ServerUserAuthService(session) {
                            @SuppressWarnings("synthetic-access")
                            @Override
                            public void process(int cmd, Buffer buffer) throws Exception {
                                authLatch.await();
                                super.process(cmd, buffer);
                            }
                        };
                    }
                },
                ServerConnectionServiceFactory.INSTANCE
        ));
        sshd.setChannelFactories(Arrays.<NamedFactory<Channel>>asList(
                new ChannelSessionFactory() {
                    @Override
                    public Channel create() {
                        return new ChannelSession() {
                            @SuppressWarnings("synthetic-access")
                            @Override
                            public OpenFuture open(int recipient, int rwsize, int rmpsize, Buffer buffer) {
                                try {
                                    channelLatch.await();
                                } catch (InterruptedException e) {
                                    throw new RuntimeSshException(e);
                                }
                                return super.open(recipient, rwsize, rmpsize, buffer);
                            }

                            @Override
                            public String toString() {
                                return "ChannelSession" + "[id=" + id + ", recipient=" + recipient + "]";
                            }
                        };
                    }
                },
                DirectTcpipFactory.INSTANCE));
        sshd.start();
        port = sshd.getPort();

        client = SshClient.setUpDefaultClient();
    }

    @After
    public void tearDown() throws Exception {
        if (sshd != null) {
            sshd.stop(true);
        }
        if (client != null) {
            client.stop();
        }
    }

    @Test
    public void testWindowConsumptionWithInvertedStreams() throws Exception {
        sshd.setShellFactory(new AsyncEchoShellFactory());
        FactoryManagerUtils.updateProperty(sshd, FactoryManager.WINDOW_SIZE, 1024);
        FactoryManagerUtils.updateProperty(client, FactoryManager.WINDOW_SIZE, 1024);
        client.start();

        try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
            session.addPasswordIdentity(getCurrentTestName());
            session.auth().verify(5L, TimeUnit.SECONDS);

            try (ChannelShell channel = session.createShellChannel()) {
                channel.open().verify(5L, TimeUnit.SECONDS);

                try (Channel serverChannel = sshd.getActiveSessions().iterator().next().getService(ServerConnectionService.class).getChannels().iterator().next()) {
                    Window clientLocal = channel.getLocalWindow();
                    Window clientRemote = channel.getRemoteWindow();
                    Window serverLocal = serverChannel.getLocalWindow();
                    Window serverRemote = serverChannel.getRemoteWindow();

                    final String message = "0123456789";
                    final int nbMessages = 500;

                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(channel.getInvertedIn()));
                         BufferedReader reader = new BufferedReader(new InputStreamReader(channel.getInvertedOut()))) {

                        for (int i = 0; i < nbMessages; i++) {
                            writer.write(message);
                            writer.write("\n");
                            writer.flush();

                            waitForWindowNotEquals(clientLocal, serverRemote, "client local", "server remote");

                            String line = reader.readLine();
                            assertEquals("Mismatched message at line #" + i, message, line);

                            waitForWindowEquals(clientLocal, serverRemote, "client local", "server remote");
                            waitForWindowEquals(clientRemote, serverLocal, "client remote", "server local");
                        }
                    }
                }
            }
        } finally {
            client.stop();
        }
    }

    @Test
    public void testWindowConsumptionWithDirectStreams() throws Exception {
        sshd.setShellFactory(new AsyncEchoShellFactory());
        FactoryManagerUtils.updateProperty(sshd, FactoryManager.WINDOW_SIZE, 1024);
        FactoryManagerUtils.updateProperty(client, FactoryManager.WINDOW_SIZE, 1024);

        client.start();

        try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
            session.addPasswordIdentity(getCurrentTestName());
            session.auth().verify(5L, TimeUnit.SECONDS);

            try (ChannelShell channel = session.createShellChannel();
                 PipedInputStream inPis = new PipedInputStream();
                 PipedOutputStream inPos = new PipedOutputStream(inPis);
                 PipedInputStream outPis = new PipedInputStream();
                 PipedOutputStream outPos = new PipedOutputStream(outPis)) {

                channel.setIn(inPis);
                channel.setOut(outPos);
                channel.open().verify();

                try (Channel serverChannel = sshd.getActiveSessions().iterator().next().getService(ServerConnectionService.class).getChannels().iterator().next()) {
                    Window clientLocal = channel.getLocalWindow();
                    Window clientRemote = channel.getRemoteWindow();
                    Window serverLocal = serverChannel.getLocalWindow();
                    Window serverRemote = serverChannel.getRemoteWindow();

                    final String message = "0123456789";
                    final int nbMessages = 500;

                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(inPos));
                         BufferedReader reader = new BufferedReader(new InputStreamReader(outPis))) {
                        for (int i = 0; i < nbMessages; i++) {
                            writer.write(message);
                            writer.write("\n");
                            writer.flush();

                            waitForWindowEquals(clientLocal, serverRemote, "client local", "server remote");

                            String line = reader.readLine();
                            assertEquals("Mismatched message at line #" + i, message, line);

                            waitForWindowEquals(clientLocal, serverRemote, "client local", "server remote");
                            waitForWindowEquals(clientRemote, serverLocal, "client remote", "server local");
                        }
                    }
                }
            }
        } finally {
            client.stop();
        }
    }

    @Test
    public void testWindowConsumptionWithAsyncStreams() throws Exception {
        sshd.setShellFactory(new AsyncEchoShellFactory());
        FactoryManagerUtils.updateProperty(sshd, FactoryManager.WINDOW_SIZE, 1024);
        FactoryManagerUtils.updateProperty(client, FactoryManager.WINDOW_SIZE, 1024);

        client.start();

        try (ClientSession session = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
            session.addPasswordIdentity(getCurrentTestName());
            session.auth().verify(5L, TimeUnit.SECONDS);

            try (ChannelShell channel = session.createShellChannel()) {
                channel.setStreaming(ClientChannel.Streaming.Async);
                channel.open().verify(5L, TimeUnit.SECONDS);

                try (Channel serverChannel = sshd.getActiveSessions().iterator().next().getService(ServerConnectionService.class).getChannels().iterator().next()) {
                    Window clientLocal = channel.getLocalWindow();
                    Window clientRemote = channel.getRemoteWindow();
                    Window serverLocal = serverChannel.getLocalWindow();
                    Window serverRemote = serverChannel.getRemoteWindow();

                    final String message = "0123456789\n";
                    final byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                    final int nbMessages = 500;
                    for (int i = 0; i < nbMessages; i++) {
                        Buffer buffer = new ByteArrayBuffer(bytes);
                        channel.getAsyncIn().write(buffer).verify();

                        waitForWindowNotEquals(clientLocal, serverRemote, "client local", "server remote");

                        Buffer buf = new ByteArrayBuffer(16);
                        IoReadFuture future = channel.getAsyncOut().read(buf);
                        future.verify();
                        assertEquals("Mismatched available data at line #" + i, message.length(), buf.available());
                        assertEquals("Mismatched data at line #" + i, message, new String(buf.array(), buf.rpos(), buf.available()));

                        waitForWindowEquals(clientLocal, serverRemote, "client local", "server remote");
                        waitForWindowEquals(clientRemote, serverLocal, "client remote", "server local");
                    }
                }
            }
        } finally {
            client.stop();
        }
    }

    protected void waitForWindowNotEquals(Window w1, Window w2, String n1, String n2) throws InterruptedException {
        for (int j = 0; j < 50; j++) {
            if (w1.getSize() != w2.getSize()) {
                break;
            }
            Thread.sleep(1);
        }
        assertNotEquals(n1 + " and " + n2, w1.getSize(), w2.getSize());
    }

    protected void waitForWindowEquals(Window w1, Window w2, String n1, String n2) throws InterruptedException {
        for (int j = 0; j < 50; j++) {
            if (w1.getSize() == w2.getSize()) {
                break;
            }
            Thread.sleep(1);
        }
        assertEquals(n1 + " and " + n2, w1.getSize(), w2.getSize());
    }

    public static class TestEchoShellFactory extends EchoShellFactory {
        @Override
        public Command create() {
            return new TestEchoShell();
        }

        public static class TestEchoShell extends EchoShell {

            public static CountDownLatch latch = new CountDownLatch(1);

            @Override
            public void destroy() {
                if (latch != null) {
                    latch.countDown();
                }
                super.destroy();
            }
        }
    }
}
