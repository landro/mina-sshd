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

/**
 * Wrapper for a cryptographic cipher, used either for encryption
 * or decryption.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public interface Cipher {

    enum Mode {
        Encrypt, Decrypt
    }

    /**
     * @return Size of the initialization vector (in bytes)
     */
    int getIVSize();

    /**
     * @return The block size (in bytes) for this cipher
     */
    int getBlockSize();

    /**
     * Initialize the cipher for encryption or decryption with
     * the given key and initialization vector
     *
     * @param mode Encrypt/Decrypt initialization
     * @param key  Key bytes
     * @param iv   Initialization vector bytes
     * @throws Exception If failed to initialize
     */
    void init(Mode mode, byte[] key, byte[] iv) throws Exception;

    /**
     * Performs in-place encryption or decryption on the given data.
     *
     * @param input The input/output bytes
     * @throws Exception If failed to execute
     * @see #update(byte[], int, int)
     */
    void update(byte[] input) throws Exception; // TODO make this a default method in JDK-8

    /**
     * Performs in-place encryption or decryption on the given data.
     *
     * @param input       The input/output bytes
     * @param inputOffset The offset of the data in the data buffer
     * @param inputLen    The number of bytes to update - starting at the given offset
     * @throws Exception If failed to execute
     */
    void update(byte[] input, int inputOffset, int inputLen) throws Exception;

}
