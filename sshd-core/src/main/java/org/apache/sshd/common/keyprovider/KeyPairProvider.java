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
package org.apache.sshd.common.keyprovider;

import java.security.KeyPair;
import java.util.Collections;

import org.apache.sshd.common.cipher.ECCurves;

/**
 * Provider for key pairs.  This provider is used on the server side to provide
 * the host key, or on the client side to provide the user key.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public interface KeyPairProvider {

    /**
     * SSH identifier for RSA keys
     */
    String SSH_RSA = "ssh-rsa";

    /**
     * SSH identifier for DSA keys
     */
    String SSH_DSS = "ssh-dss";

    /**
     * SSH identifier for EC keys in NIST curve P-256
     */
    String ECDSA_SHA2_NISTP256 = ECCurves.nistp256.getKeyType();

    /**
     * SSH identifier for EC keys in NIST curve P-384
     */
    String ECDSA_SHA2_NISTP384 = ECCurves.nistp384.getKeyType();

    /**
     * SSH identifier for EC keys in NIST curve P-521
     */
    String ECDSA_SHA2_NISTP521 = ECCurves.nistp521.getKeyType();

    /**
     * A {@link KeyPairProvider} that has no keys
     */
    KeyPairProvider EMPTY_KEYPAIR_PROVIDER =
        new KeyPairProvider() {
            @Override
            public KeyPair loadKey(String type) {
                return null;
            }

            @Override
            public Iterable<String> getKeyTypes() {
                return Collections.emptyList();
            }

            @Override
            public Iterable<KeyPair> loadKeys() {
                return Collections.emptyList();
            }

            @Override
            public String toString() {
                return "EMPTY_KEYPAIR_PROVIDER";
            }
        };

    /**
     * Load available keys.
     *
     * @return an {@link Iterable} instance of available keys, never {@code null}
     */
    Iterable<KeyPair> loadKeys();

    /**
     * Load a key of the specified type which can be "ssh-rsa", "ssh-dss", or
     * "ecdsa-sha2-nistp{256,384,521}". If there is no key of this type, return
     * {@code null}
     *
     * @param type the type of key to load
     * @return a valid key pair or {@code null}
     */
    KeyPair loadKey(String type);

    /**
     * @return The available {@link Iterable} key types in preferred order - never {@code null}
     */
    Iterable<String> getKeyTypes();

}
