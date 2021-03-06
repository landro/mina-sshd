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

package org.apache.sshd.common.cipher;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;

import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.cipher.Cipher.Mode;
import org.apache.sshd.common.util.SecurityUtils;
import org.apache.sshd.util.BaseTestSupport;
import org.junit.Assume;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class BaseCipherTest extends BaseTestSupport {
    protected BaseCipherTest() {
        super();
    }

    protected void ensureKeySizeSupported(int bsize, String algorithm, String transformation) throws GeneralSecurityException {
        try {
            javax.crypto.Cipher cipher = SecurityUtils.getCipher(transformation);
            byte[] key = new byte[bsize];
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(key, algorithm));
        } catch (GeneralSecurityException e) {
            if (e instanceof InvalidKeyException) {    // NOTE: assumption violations are NOT test failures...
                Assume.assumeTrue(algorithm + "/" + transformation + "[" + bsize + "] N/A", false);
            }

            throw e;
        }
    }

    protected void ensureKeySizeSupported(int ivsize, int bsize, String algorithm, String transformation) throws GeneralSecurityException {
        try {
            javax.crypto.Cipher cipher = SecurityUtils.getCipher(transformation);
            byte[] key = new byte[bsize];
            byte[] iv = new byte[ivsize];
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(key, algorithm), new IvParameterSpec(iv));
        } catch (GeneralSecurityException e) {
            if (e instanceof InvalidKeyException) {
                Assume.assumeTrue(algorithm + "/" + transformation + "[" + bsize + "/" + ivsize + "]", false /* force exception */);
            }

            throw e;
        }
    }

    protected void testEncryptDecrypt(NamedFactory<Cipher> factory) throws Exception {
        String facName = factory.getName();
        Cipher enc = factory.create();
        int keySize = enc.getBlockSize(), ivSize = enc.getIVSize();
        byte[] key = new byte[keySize], iv = new byte[ivSize];
        enc.init(Mode.Encrypt, key, iv);

        byte[] expected = facName.getBytes(StandardCharsets.UTF_8);
        byte[] workBuf = expected.clone();    // need to clone since the cipher works in-line
        enc.update(workBuf, 0, workBuf.length);

        Cipher dec = factory.create();
        dec.init(Mode.Decrypt, key, iv);
        byte[] actual = workBuf.clone();
        dec.update(actual, 0, actual.length);

        assertArrayEquals(facName, expected, actual);
    }
}
