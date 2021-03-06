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
package org.apache.sshd.server.subsystem.sftp;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.sshd.common.Factory;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.FactoryManagerUtils;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.VersionProperties;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.digest.Digest;
import org.apache.sshd.common.file.FileSystemAware;
import org.apache.sshd.common.random.Random;
import org.apache.sshd.common.subsystem.sftp.SftpConstants;
import org.apache.sshd.common.subsystem.sftp.extensions.SpaceAvailableExtensionInfo;
import org.apache.sshd.common.subsystem.sftp.extensions.openssh.AbstractOpenSSHExtensionParser.OpenSSHExtension;
import org.apache.sshd.common.subsystem.sftp.extensions.openssh.FsyncExtensionParser;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.Int2IntFunction;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.Pair;
import org.apache.sshd.common.util.SelectorUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.BufferUtils;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.io.FileInfoExtractor;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.common.util.logging.AbstractLoggingBean;
import org.apache.sshd.common.util.threads.ThreadUtils;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SessionAware;
import org.apache.sshd.server.session.ServerSession;

import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_APPEND_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_READ_ATTRIBUTES;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_READ_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_WRITE_ATTRIBUTES;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.ACE4_WRITE_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.EXT_NEWLINE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.EXT_SUPPORTED;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.EXT_SUPPORTED2;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.EXT_VENDOR_ID;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.EXT_VERSIONS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SFTP_V3;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SFTP_V4;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SFTP_V5;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SFTP_V6;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_ACCESSTIME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_ALL;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_BITS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_CREATETIME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_MODIFYTIME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_OWNERGROUP;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_PERMISSIONS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FILEXFER_ATTR_SIZE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_ACCESS_DISPOSITION;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_APPEND;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_APPEND_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_APPEND_DATA_ATOMIC;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_CREAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_CREATE_NEW;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_CREATE_TRUNCATE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_EXCL;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_OPEN_EXISTING;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_OPEN_OR_CREATE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_READ;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_TRUNC;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_TRUNCATE_EXISTING;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXF_WRITE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_ATTRS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_BLOCK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_CLOSE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_DATA;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_EXTENDED;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_EXTENDED_REPLY;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_FSETSTAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_FSTAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_HANDLE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_INIT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_LINK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_LSTAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_MKDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_NAME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_OPEN;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_OPENDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_READ;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_READDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_READLINK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_REALPATH;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_REALPATH_STAT_ALWAYS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_REALPATH_STAT_IF;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_REMOVE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_RENAME;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_RENAME_ATOMIC;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_RENAME_OVERWRITE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_RMDIR;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_SETSTAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_STAT;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_STATUS;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_SYMLINK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_UNBLOCK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_VERSION;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FXP_WRITE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FX_FAILURE;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FX_NO_MATCHING_BYTE_RANGE_LOCK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FX_OK;
import static org.apache.sshd.common.subsystem.sftp.SftpConstants.SSH_FX_OP_UNSUPPORTED;

/**
 * SFTP subsystem
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class SftpSubsystem extends AbstractLoggingBean implements Command, Runnable, SessionAware, FileSystemAware {

    /**
     * Properties key for the maximum of available open handles per session.
     */
    public static final String MAX_OPEN_HANDLES_PER_SESSION = "max-open-handles-per-session";
    public static final int DEFAULT_MAX_OPEN_HANDLES = Integer.MAX_VALUE;

    /**
     * Size in bytes of the opaque handle value
     *
     * @see #DEFAULT_FILE_HANDLE_SIZE
     */
    public static final String FILE_HANDLE_SIZE = "sftp-handle-size";
    public static final int MIN_FILE_HANDLE_SIZE = 4;  // ~uint32
    public static final int DEFAULT_FILE_HANDLE_SIZE = 16;
    public static final int MAX_FILE_HANDLE_SIZE = 64;  // ~sha512

    /**
     * Max. rounds to attempt to create a unique file handle - if all handles
     * already in use after these many rounds, then an exception is thrown
     *
     * @see #generateFileHandle(Path)
     * @see #DEFAULT_FILE_HANDLE_ROUNDS
     */
    public static final String MAX_FILE_HANDLE_RAND_ROUNDS = "sftp-handle-rand-max-rounds";
    public static final int MIN_FILE_HANDLE_ROUNDS = 1;
    public static final int DEFAULT_FILE_HANDLE_ROUNDS = MIN_FILE_HANDLE_SIZE;
    public static final int MAX_FILE_HANDLE_ROUNDS = MAX_FILE_HANDLE_SIZE;

    /**
     * Force the use of a given sftp version
     */
    public static final String SFTP_VERSION = "sftp-version";

    public static final int LOWER_SFTP_IMPL = SFTP_V3; // Working implementation from v3
    public static final int HIGHER_SFTP_IMPL = SFTP_V6; //  .. up to
    public static final String ALL_SFTP_IMPL;

    /**
     * Force the use of a max. packet length - especially for {@link #doReadDir(Buffer, int)}
     * and {@link #doRead(Buffer, int)} methods
     *
     * @see #DEFAULT_MAX_PACKET_LENGTH
     */
    public static final String MAX_PACKET_LENGTH_PROP = "sftp-max-packet-length";
    public static final int DEFAULT_MAX_PACKET_LENGTH = 1024 * 16;

    /**
     * Allows controlling reports of which client extensions are supported
     * (and reported via &quot;support&quot; and &quot;support2&quot; server
     * extensions) as a comma-separate list of names. <B>Note:</B> requires
     * overriding the {@link #executeExtendedCommand(Buffer, int, String)}
     * command accordingly. If empty string is set then no server extensions
     * are reported
     *
     * @see #DEFAULT_SUPPORTED_CLIENT_EXTENSIONS
     */
    public static final String CLIENT_EXTENSIONS_PROP = "sftp-client-extensions";
    /**
     * The default reported supported client extensions
     */
    public static final Set<String> DEFAULT_SUPPORTED_CLIENT_EXTENSIONS =
            // TODO text-seek - see http://tools.ietf.org/wg/secsh/draft-ietf-secsh-filexfer/draft-ietf-secsh-filexfer-13.txt
            // TODO home-directory - see http://tools.ietf.org/wg/secsh/draft-ietf-secsh-filexfer/draft-ietf-secsh-filexfer-09.txt
            Collections.unmodifiableSet(
                    GenericUtils.asSortedSet(String.CASE_INSENSITIVE_ORDER,
                            Arrays.asList(
                                    SftpConstants.EXT_VERSION_SELECT,
                                    SftpConstants.EXT_COPY_FILE,
                                    SftpConstants.EXT_MD5_HASH,
                                    SftpConstants.EXT_MD5_HASH_HANDLE,
                                    SftpConstants.EXT_CHECK_FILE_HANDLE,
                                    SftpConstants.EXT_CHECK_FILE_NAME,
                                    SftpConstants.EXT_COPY_DATA,
                                    SftpConstants.EXT_SPACE_AVAILABLE
                            )));

    /**
     * Comma-separated list of which {@code OpenSSH} extensions are reported and
     * what version is reported for each - format: {@code name=version}. If empty
     * value set, then no such extensions are reported. Otherwise, the
     * {@link #DEFAULT_OPEN_SSH_EXTENSIONS} are used
     */
    public static final String OPENSSH_EXTENSIONS_PROP = "sftp-openssh-extensions";
    public static final List<OpenSSHExtension> DEFAULT_OPEN_SSH_EXTENSIONS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            new OpenSSHExtension(FsyncExtensionParser.NAME, "1")
                    ));

    public static final List<String> DEFAULT_OPEN_SSH_EXTENSIONS_NAMES =
            Collections.unmodifiableList(new ArrayList<String>(DEFAULT_OPEN_SSH_EXTENSIONS.size()) {
                private static final long serialVersionUID = 1L;    // we're not serializing it

                {
                    for (OpenSSHExtension ext : DEFAULT_OPEN_SSH_EXTENSIONS) {
                        add(ext.getName());
                    }
                }

            });

    public static final List<String> DEFAULT_UNIX_VIEW = Collections.singletonList("unix:*");

    /**
     * A {@link Map} of {@link FileInfoExtractor}s to be used to complete
     * attributes that are deemed important enough to warrant an extra
     * effort if not accessible via the file system attributes views
     */
    public static final Map<String, FileInfoExtractor<?>> FILEATTRS_RESOLVERS =
            Collections.unmodifiableMap(new TreeMap<String, FileInfoExtractor<?>>(String.CASE_INSENSITIVE_ORDER) {
                private static final long serialVersionUID = 1L;    // we're not serializing it

                {
                    put("isRegularFile", FileInfoExtractor.ISREG);
                    put("isDirectory", FileInfoExtractor.ISDIR);
                    put("isSymbolicLink", FileInfoExtractor.ISSYMLINK);
                    put("permissions", FileInfoExtractor.PERMISSIONS);
                    put("size", FileInfoExtractor.SIZE);
                    put("lastModifiedTime", FileInfoExtractor.LASTMODIFIED);
                }
            });

    static {
        StringBuilder sb = new StringBuilder(2 * (1 + (HIGHER_SFTP_IMPL - LOWER_SFTP_IMPL)));
        for (int v = LOWER_SFTP_IMPL; v <= HIGHER_SFTP_IMPL; v++) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        ALL_SFTP_IMPL = sb.toString();
    }

    protected ExitCallback callback;
    protected InputStream in;
    protected OutputStream out;
    protected OutputStream err;
    protected Environment env;
    protected Random randomizer;
    protected int fileHandleSize = DEFAULT_FILE_HANDLE_SIZE;
    protected int maxFileHandleRounds = DEFAULT_FILE_HANDLE_ROUNDS;
    protected ServerSession session;
    protected boolean closed;
    protected ExecutorService executors;
    protected boolean shutdownExecutor;
    protected Future<?> pendingFuture;
    protected byte[] workBuf = new byte[Math.max(DEFAULT_FILE_HANDLE_SIZE, Integer.SIZE / Byte.SIZE)]; // TODO in JDK-8 use Integer.BYTES
    protected FileSystem fileSystem = FileSystems.getDefault();
    protected Path defaultDir = fileSystem.getPath(System.getProperty("user.dir"));
    protected long requestsCount;
    protected int version;
    protected final Map<String, byte[]> extensions = new HashMap<>();
    protected final Map<String, Handle> handles = new HashMap<>();

    protected final UnsupportedAttributePolicy unsupportedAttributePolicy;

    /**
     * @param executorService The {@link ExecutorService} to be used by
     *                        the {@link SftpSubsystem} command when starting execution. If
     *                        {@code null} then a single-threaded ad-hoc service is used.
     * @param shutdownOnExit  If {@code true} the {@link ExecutorService#shutdownNow()}
     *                        will be called when subsystem terminates - unless it is the ad-hoc
     *                        service, which will be shutdown regardless
     * @param policy          The {@link UnsupportedAttributePolicy} to use if failed to access
     *                        some local file attributes
     * @see ThreadUtils#newSingleThreadExecutor(String)
     */
    public SftpSubsystem(ExecutorService executorService, boolean shutdownOnExit, UnsupportedAttributePolicy policy) {
        if (executorService == null) {
            executors = ThreadUtils.newSingleThreadExecutor(getClass().getSimpleName());
            shutdownExecutor = true;    // we always close the ad-hoc executor service
        } else {
            executors = executorService;
            shutdownExecutor = shutdownOnExit;
        }

        if (policy == null) {
            throw new IllegalArgumentException("No policy provided");
        }
        unsupportedAttributePolicy = policy;
    }

    public int getVersion() {
        return version;
    }

    public final UnsupportedAttributePolicy getUnsupportedAttributePolicy() {
        return unsupportedAttributePolicy;
    }

    @Override
    public void setSession(ServerSession session) {
        this.session = session;

        FactoryManager manager = session.getFactoryManager();
        Factory<? extends Random> factory = manager.getRandomFactory();
        this.randomizer = factory.create();

        this.fileHandleSize = FactoryManagerUtils.getIntProperty(manager, FILE_HANDLE_SIZE, DEFAULT_FILE_HANDLE_SIZE);
        ValidateUtils.checkTrue(this.fileHandleSize >= MIN_FILE_HANDLE_SIZE, "File handle size too small: %d", this.fileHandleSize);
        ValidateUtils.checkTrue(this.fileHandleSize <= MAX_FILE_HANDLE_SIZE, "File handle size too big: %d", this.fileHandleSize);

        this.maxFileHandleRounds = FactoryManagerUtils.getIntProperty(manager, MAX_FILE_HANDLE_RAND_ROUNDS, DEFAULT_FILE_HANDLE_ROUNDS);
        ValidateUtils.checkTrue(this.maxFileHandleRounds >= MIN_FILE_HANDLE_ROUNDS, "File handle rounds too small: %d", this.maxFileHandleRounds);
        ValidateUtils.checkTrue(this.maxFileHandleRounds <= MAX_FILE_HANDLE_ROUNDS, "File handle rounds too big: %d", this.maxFileHandleRounds);

        if (workBuf.length < this.fileHandleSize) {
            workBuf = new byte[this.fileHandleSize];
        }
    }

    @Override
    public void setFileSystem(FileSystem fileSystem) {
        if (fileSystem != this.fileSystem) {
            this.fileSystem = fileSystem;

            Iterable<Path> roots = ValidateUtils.checkNotNull(fileSystem.getRootDirectories(), "No root directories");
            Iterator<Path> available = ValidateUtils.checkNotNull(roots.iterator(), "No roots iterator");
            ValidateUtils.checkTrue(available.hasNext(), "No available root");
            this.defaultDir = available.next();
        }
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    @Override
    public void start(Environment env) throws IOException {
        this.env = env;
        try {
            pendingFuture = executors.submit(this);
        } catch (RuntimeException e) {    // e.g., RejectedExecutionException
            log.error("Failed (" + e.getClass().getSimpleName() + ") to start command: " + e.toString(), e);
            throw new IOException(e);
        }
    }

    @Override
    public void run() {
        try {
            for (long count = 1L;; count++) {
                int length = BufferUtils.readInt(in, workBuf, 0, workBuf.length);
                ValidateUtils.checkTrue(length >= ((Integer.SIZE / Byte.SIZE) + 1 /* command */), "Bad length to read: %d", length);

                Buffer buffer = new ByteArrayBuffer(length + (Integer.SIZE / Byte.SIZE) /* the length */);
                buffer.putInt(length);
                for (int remainLen = length; remainLen > 0;) {
                    int l = in.read(buffer.array(), buffer.wpos(), remainLen);
                    if (l < 0) {
                        throw new IllegalArgumentException("Premature EOF at buffer #" + count + " while read length=" + length + " and remain=" + remainLen);
                    }
                    buffer.wpos(buffer.wpos() + l);
                    remainLen -= l;
                }

                process(buffer);
            }
        } catch (Throwable t) {
            if ((!closed) && (!(t instanceof EOFException))) { // Ignore
                log.error("Exception caught in SFTP subsystem", t);
            }
        } finally {
            for (Map.Entry<String, Handle> entry : handles.entrySet()) {
                String id = entry.getKey();
                Handle handle = entry.getValue();
                try {
                    handle.close();
                    if (log.isDebugEnabled()) {
                        log.debug("Closed pending handle {} [{}]", id, handle);
                    }
                } catch (IOException ioe) {
                    log.error("Failed ({}) to close handle={}[{}]: {}",
                              ioe.getClass().getSimpleName(), id, handle, ioe.getMessage());
                }
            }

            callback.onExit(0);
        }
    }

    protected void process(Buffer buffer) throws IOException {
        int length = buffer.getInt();
        int type = buffer.getUByte();
        int id = buffer.getInt();
        if (log.isDebugEnabled()) {
            log.debug("process(length={}, type={}, id={})",
                    length, type, id);
        }

        switch (type) {
            case SSH_FXP_INIT:
                doInit(buffer, id);
                break;
            case SSH_FXP_OPEN:
                doOpen(buffer, id);
                break;
            case SSH_FXP_CLOSE:
                doClose(buffer, id);
                break;
            case SSH_FXP_READ:
                doRead(buffer, id);
                break;
            case SSH_FXP_WRITE:
                doWrite(buffer, id);
                break;
            case SSH_FXP_LSTAT:
                doLStat(buffer, id);
                break;
            case SSH_FXP_FSTAT:
                doFStat(buffer, id);
                break;
            case SSH_FXP_SETSTAT:
                doSetStat(buffer, id);
                break;
            case SSH_FXP_FSETSTAT:
                doFSetStat(buffer, id);
                break;
            case SSH_FXP_OPENDIR:
                doOpenDir(buffer, id);
                break;
            case SSH_FXP_READDIR:
                doReadDir(buffer, id);
                break;
            case SSH_FXP_REMOVE:
                doRemove(buffer, id);
                break;
            case SSH_FXP_MKDIR:
                doMakeDirectory(buffer, id);
                break;
            case SSH_FXP_RMDIR:
                doRemoveDirectory(buffer, id);
                break;
            case SSH_FXP_REALPATH:
                doRealPath(buffer, id);
                break;
            case SSH_FXP_STAT:
                doStat(buffer, id);
                break;
            case SSH_FXP_RENAME:
                doRename(buffer, id);
                break;
            case SSH_FXP_READLINK:
                doReadLink(buffer, id);
                break;
            case SSH_FXP_SYMLINK:
                doSymLink(buffer, id);
                break;
            case SSH_FXP_LINK:
                doLink(buffer, id);
                break;
            case SSH_FXP_BLOCK:
                doBlock(buffer, id);
                break;
            case SSH_FXP_UNBLOCK:
                doUnblock(buffer, id);
                break;
            case SSH_FXP_EXTENDED:
                doExtended(buffer, id);
                break;
            default:
                log.warn("Unknown command type received: {}", Integer.valueOf(type));
                sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OP_UNSUPPORTED, "Command " + type + " is unsupported or not implemented");
        }

        if (type != SSH_FXP_INIT) {
            requestsCount++;
        }
    }

    protected void doExtended(Buffer buffer, int id) throws IOException {
        executeExtendedCommand(buffer, id, buffer.getString());
    }

    /**
     * @param buffer    The command {@link Buffer}
     * @param id        The request id
     * @param extension The extension name
     * @throws IOException If failed to execute the extension
     */
    protected void executeExtendedCommand(Buffer buffer, int id, String extension) throws IOException {
        switch (extension) {
            case SftpConstants.EXT_TEXT_SEEK:
                doTextSeek(buffer, id);
                break;
            case SftpConstants.EXT_VERSION_SELECT:
                doVersionSelect(buffer, id);
                break;
            case SftpConstants.EXT_COPY_FILE:
                doCopyFile(buffer, id);
                break;
            case SftpConstants.EXT_COPY_DATA:
                doCopyData(buffer, id);
                break;
            case SftpConstants.EXT_MD5_HASH:
            case SftpConstants.EXT_MD5_HASH_HANDLE:
                doMD5Hash(buffer, id, extension);
                break;
            case SftpConstants.EXT_CHECK_FILE_HANDLE:
            case SftpConstants.EXT_CHECK_FILE_NAME:
                doCheckFileHash(buffer, id, extension);
                break;
            case FsyncExtensionParser.NAME:
                doOpenSSHFsync(buffer, id);
                break;
            case SftpConstants.EXT_SPACE_AVAILABLE:
                doSpaceAvailable(buffer, id);
                break;
            default:
                log.info("Received unsupported SSH_FXP_EXTENDED({})", extension);
                sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OP_UNSUPPORTED, "Command SSH_FXP_EXTENDED(" + extension + ") is unsupported or not implemented");
                break;
        }
    }

    protected void doSpaceAvailable(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        SpaceAvailableExtensionInfo info;
        try {
            info = doSpaceAvailable(id, path);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        buffer.clear();
        buffer.putByte((byte) SSH_FXP_EXTENDED_REPLY);
        buffer.putInt(id);
        SpaceAvailableExtensionInfo.encode(buffer, info);
        send(buffer);
    }

    protected SpaceAvailableExtensionInfo doSpaceAvailable(int id, String path) throws IOException {
        Path nrm = resolveNormalizedLocation(path);
        if (log.isDebugEnabled()) {
            log.debug("doSpaceAvailable(id={}) path={}[{}]", id, path, nrm);
        }

        FileStore store = Files.getFileStore(nrm);
        if (log.isTraceEnabled()) {
            log.trace("doSpaceAvailable(id={}) path={}[{}] - {}[{}]", id, path, nrm, store.name(), store.type());
        }

        return new SpaceAvailableExtensionInfo(store);
    }

    protected void doTextSeek(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        long line = buffer.getLong();
        try {
            // TODO : implement text-seek - see https://tools.ietf.org/html/draft-ietf-secsh-filexfer-03#section-6.3
            doTextSeek(id, handle, line);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doTextSeek(int id, String handle, long line) throws IOException {
        Handle h = handles.get(handle);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_EXTENDED(text-seek) (handle={}[{}], line={})", handle, h, line);
        }

        FileHandle fileHandle = validateHandle(handle, h, FileHandle.class);
        throw new UnsupportedOperationException("doTextSeek(" + fileHandle + ")");
    }

    // see https://github.com/openssh/openssh-portable/blob/master/PROTOCOL section 10
    protected void doOpenSSHFsync(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        try {
            doOpenSSHFsync(id, handle);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doOpenSSHFsync(int id, String handle) throws IOException {
        Handle h = handles.get(handle);
        if (log.isDebugEnabled()) {
            log.debug("doOpenSSHFsync({})[{}]", handle, h);
        }

        FileHandle fileHandle = validateHandle(handle, h, FileHandle.class);
        FileChannel channel = fileHandle.getFileChannel();
        channel.force(false);
    }

    protected void doCheckFileHash(Buffer buffer, int id, String targetType) throws IOException {
        String target = buffer.getString();
        String algList = buffer.getString();
        String[] algos = GenericUtils.split(algList, ',');
        long startOffset = buffer.getLong();
        long length = buffer.getLong();
        int blockSize = buffer.getInt();
        try {
            buffer.clear();
            buffer.putByte((byte) SSH_FXP_EXTENDED_REPLY);
            buffer.putInt(id);
            buffer.putString(SftpConstants.EXT_CHECK_FILE);
            doCheckFileHash(id, targetType, target, Arrays.asList(algos), startOffset, length, blockSize, buffer);
        } catch (Exception e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        send(buffer);
    }

    protected void doCheckFileHash(int id, String targetType, String target, Collection<String> algos,
                                   long startOffset, long length, int blockSize, Buffer buffer)
            throws Exception {
        Path path;
        if (SftpConstants.EXT_CHECK_FILE_HANDLE.equalsIgnoreCase(targetType)) {
            Handle h = handles.get(target);
            FileHandle fileHandle = validateHandle(target, h, FileHandle.class);
            path = fileHandle.getFile();

            /*
             * To quote http://tools.ietf.org/wg/secsh/draft-ietf-secsh-filexfer/draft-ietf-secsh-filexfer-09.txt section 9.1.2:
             *
             *       If ACE4_READ_DATA was not included when the file was opened,
             *       the server MUST return STATUS_PERMISSION_DENIED.
             */
            int access = fileHandle.getAccessMask();
            if ((access & ACE4_READ_DATA) == 0) {
                throw new AccessDeniedException("File not opened for read: " + path);
            }
        } else {
            path = resolveFile(target);

            /*
             * To quote http://tools.ietf.org/wg/secsh/draft-ietf-secsh-filexfer/draft-ietf-secsh-filexfer-09.txt section 9.1.2:
             *
             *      If 'check-file-name' refers to a SSH_FILEXFER_TYPE_SYMLINK, the
             *      target should be opened.
             */
            for (int index = 0; Files.isSymbolicLink(path) && (index < Byte.MAX_VALUE /* TODO make this configurable */); index++) {
                path = Files.readSymbolicLink(path);
            }

            if (Files.isSymbolicLink(path)) {
                throw new FileSystemLoopException(target + " yields a circular or too long chain of symlinks");
            }

            if (Files.isDirectory(path, IoUtils.getLinkOptions(false))) {
                throw new NotDirectoryException(path.toString());
            }
        }

        ValidateUtils.checkNotNullAndNotEmpty(algos, "No hash algorithms specified");

        NamedFactory<? extends Digest> factory = null;
        for (String a : algos) {
            factory = BuiltinDigests.fromFactoryName(a);
            if (factory != null) {
                break;
            }
        }
        ValidateUtils.checkNotNull(factory, "No matching digest factory found for %s", algos);

        doCheckFileHash(id, path, factory, startOffset, length, blockSize, buffer);
    }

    protected void doCheckFileHash(int id, Path file, NamedFactory<? extends Digest> factory,
                                   long startOffset, long length, int blockSize, Buffer buffer)
            throws Exception {
        ValidateUtils.checkTrue(startOffset >= 0L, "Invalid start offset: %d", startOffset);
        ValidateUtils.checkTrue(length >= 0L, "Invalid length: %d", length);
        ValidateUtils.checkTrue((blockSize == 0) || (blockSize >= SftpConstants.MIN_CHKFILE_BLOCKSIZE), "Invalid block size: %d", blockSize);
        ValidateUtils.checkNotNull(factory, "No digest factory provided");
        buffer.putString(factory.getName());

        long effectiveLength = length;
        long totalLength = Files.size(file);
        if (effectiveLength == 0L) {
            effectiveLength = totalLength - startOffset;
        } else {
            long maxRead = startOffset + length;
            if (maxRead > totalLength) {
                effectiveLength = totalLength - startOffset;
            }
        }
        ValidateUtils.checkTrue(effectiveLength > 0L, "Non-positive effective hash data length: %d", effectiveLength);

        byte[] digestBuf = (blockSize == 0)
                ? new byte[Math.min((int) effectiveLength, IoUtils.DEFAULT_COPY_SIZE)]
                : new byte[Math.min((int) effectiveLength, blockSize)];
        ByteBuffer wb = ByteBuffer.wrap(digestBuf);
        try (FileChannel channel = FileChannel.open(file, IoUtils.EMPTY_OPEN_OPTIONS)) {
            channel.position(startOffset);

            Digest digest = factory.create();
            digest.init();

            if (blockSize == 0) {
                while (effectiveLength > 0L) {
                    int remainLen = Math.min(digestBuf.length, (int) effectiveLength);
                    ByteBuffer bb = wb;
                    if (remainLen < digestBuf.length) {
                        bb = ByteBuffer.wrap(digestBuf, 0, remainLen);
                    }
                    bb.clear(); // prepare for next read

                    int readLen = channel.read(bb);
                    if (readLen < 0) {
                        break;
                    }

                    effectiveLength -= readLen;
                    digest.update(digestBuf, 0, readLen);
                }

                byte[] hashValue = digest.digest();
                if (log.isTraceEnabled()) {
                    log.trace("doCheckFileHash({}) offset={}, length={} - hash={}",
                            file, startOffset, length,
                            BufferUtils.printHex(':', hashValue));
                }
                buffer.putBytes(hashValue);
            } else {
                for (int count = 0; effectiveLength > 0L; count++) {
                    int remainLen = Math.min(digestBuf.length, (int) effectiveLength);
                    ByteBuffer bb = wb;
                    if (remainLen < digestBuf.length) {
                        bb = ByteBuffer.wrap(digestBuf, 0, remainLen);
                    }
                    bb.clear(); // prepare for next read

                    int readLen = channel.read(bb);
                    if (readLen < 0) {
                        break;
                    }

                    effectiveLength -= readLen;
                    digest.update(digestBuf, 0, readLen);

                    byte[] hashValue = digest.digest(); // NOTE: this also resets the hash for the next read
                    if (log.isTraceEnabled()) {
                        log.trace("doCheckFileHash({})[{}] offset={}, length={} - hash={}",
                                file, count, startOffset, length,
                                BufferUtils.printHex(':', hashValue));
                    }
                    buffer.putBytes(hashValue);
                }
            }
        }
    }

    protected void doMD5Hash(Buffer buffer, int id, String targetType) throws IOException {
        String target = buffer.getString();
        long startOffset = buffer.getLong();
        long length = buffer.getLong();
        byte[] quickCheckHash = buffer.getBytes();
        byte[] hashValue;

        try {
            hashValue = doMD5Hash(id, targetType, target, startOffset, length, quickCheckHash);
            if (log.isTraceEnabled()) {
                log.debug("doMD5Hash({})[{}] offset={}, length={}, quick-hash={} - hash={}",
                        targetType, target, startOffset, length, BufferUtils.printHex(':', quickCheckHash),
                        BufferUtils.printHex(':', hashValue));
            }

        } catch (Exception e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        buffer.clear();
        buffer.putByte((byte) SSH_FXP_EXTENDED_REPLY);
        buffer.putInt(id);
        buffer.putString(targetType);
        buffer.putBytes(hashValue);
        send(buffer);
    }

    protected byte[] doMD5Hash(int id, String targetType, String target, long startOffset, long length, byte[] quickCheckHash) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("doMD5Hash({})[{}] offset={}, length={}, quick-hash={}",
                    targetType, target, startOffset, length, BufferUtils.printHex(':', quickCheckHash));
        }

        Path path;
        if (SftpConstants.EXT_MD5_HASH_HANDLE.equalsIgnoreCase(targetType)) {
            Handle h = handles.get(target);
            FileHandle fileHandle = validateHandle(target, h, FileHandle.class);
            path = fileHandle.getFile();

            /*
             * To quote http://tools.ietf.org/wg/secsh/draft-ietf-secsh-filexfer/draft-ietf-secsh-filexfer-09.txt section 9.1.1:
             *
             *      The handle MUST be a file handle, and ACE4_READ_DATA MUST
             *      have been included in the desired-access when the file
             *      was opened
             */
            int access = fileHandle.getAccessMask();
            if ((access & ACE4_READ_DATA) == 0) {
                throw new AccessDeniedException("File not opened for read: " + path);
            }
        } else {
            path = resolveFile(target);
            if (Files.isDirectory(path, IoUtils.getLinkOptions(false))) {
                throw new NotDirectoryException(path.toString());
            }
        }

        /*
         * To quote http://tools.ietf.org/wg/secsh/draft-ietf-secsh-filexfer/draft-ietf-secsh-filexfer-09.txt section 9.1.1:
         *
         *      If both start-offset and length are zero, the entire file should be included
         */
        long effectiveLength = length;
        long totalSize = Files.size(path);
        if ((startOffset == 0L) && (length == 0L)) {
            effectiveLength = totalSize;
        } else {
            long maxRead = startOffset + effectiveLength;
            if (maxRead > totalSize) {
                effectiveLength = totalSize - startOffset;
            }
        }

        return doMD5Hash(id, path, startOffset, effectiveLength, quickCheckHash);
    }

    protected byte[] doMD5Hash(int id, Path path, long startOffset, long length, byte[] quickCheckHash) throws Exception {
        ValidateUtils.checkTrue(startOffset >= 0L, "Invalid start offset: %d", startOffset);
        ValidateUtils.checkTrue(length > 0L, "Invalid length: %d", length);

        Digest digest = BuiltinDigests.md5.create();
        digest.init();

        long effectiveLength = length;
        byte[] digestBuf = new byte[(int) Math.min(effectiveLength, SftpConstants.MD5_QUICK_HASH_SIZE)];
        ByteBuffer wb = ByteBuffer.wrap(digestBuf);
        boolean hashMatches = false;
        byte[] hashValue = null;

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(startOffset);

            /*
             * To quote http://tools.ietf.org/wg/secsh/draft-ietf-secsh-filexfer/draft-ietf-secsh-filexfer-09.txt section 9.1.1:
             *
             *      If this is a zero length string, the client does not have the
             *      data, and is requesting the hash for reasons other than comparing
             *      with a local file.  The server MAY return SSH_FX_OP_UNSUPPORTED in
             *      this case.
             */
            if (GenericUtils.length(quickCheckHash) <= 0) {
                // TODO consider limiting it - e.g., if the requested effective length is <= than some (configurable) threshold
                hashMatches = true;
            } else {
                int readLen = channel.read(wb);
                if (readLen < 0) {
                    throw new EOFException("EOF while read initial buffer from " + path);
                }
                effectiveLength -= readLen;
                digest.update(digestBuf, 0, readLen);

                hashValue = digest.digest();
                hashMatches = Arrays.equals(quickCheckHash, hashValue);
                if (hashMatches) {
                    /*
                     * Need to re-initialize the digester due to the Javadoc:
                     *
                     *      "The digest method can be called once for a given number
                     *       of updates. After digest has been called, the MessageDigest
                     *       object is reset to its initialized state."
                     */
                    if (effectiveLength > 0L) {
                        digest = BuiltinDigests.md5.create();
                        digest.init();
                        digest.update(digestBuf, 0, readLen);
                        hashValue = null;   // start again
                    }
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("doMD5Hash({}) offset={}, length={} - quick-hash mismatched expected={}, actual={}",
                                path, startOffset, length,
                                BufferUtils.printHex(':', quickCheckHash), BufferUtils.printHex(':', hashValue));
                    }
                }
            }

            if (hashMatches) {
                while (effectiveLength > 0L) {
                    int remainLen = Math.min(digestBuf.length, (int) effectiveLength);
                    ByteBuffer bb = wb;
                    if (remainLen < digestBuf.length) {
                        bb = ByteBuffer.wrap(digestBuf, 0, remainLen);
                    }
                    bb.clear(); // prepare for next read

                    int readLen = channel.read(bb);
                    if (readLen < 0) {
                        break;  // user may have specified more than we have available
                    }
                    effectiveLength -= readLen;
                    digest.update(digestBuf, 0, readLen);
                }

                if (hashValue == null) {    // check if did any more iterations after the quick hash
                    hashValue = digest.digest();
                }
            } else {
                hashValue = GenericUtils.EMPTY_BYTE_ARRAY;
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("doMD5Hash({}) offset={}, length={} - matches={}, quick={} hash={}",
                    path, startOffset, length, hashMatches,
                    BufferUtils.printHex(':', quickCheckHash), BufferUtils.printHex(':', hashValue));
        }

        return hashValue;
    }

    protected void doVersionSelect(Buffer buffer, int id) throws IOException {
        String proposed = buffer.getString();
        /*
         * The 'version-select' MUST be the first request from the client to the
         * server; if it is not, the server MUST fail the request and close the
         * channel.
         */
        if (requestsCount > 0L) {
            sendStatus(BufferUtils.clear(buffer), id, SSH_FX_FAILURE, "Version selection not the 1st request for proposal = " + proposed);
            session.close(true);
            return;
        }

        Boolean result = validateProposedVersion(buffer, id, proposed);
        /*
         * "MUST then close the channel without processing any further requests"
         */
        if (result == null) {   // response sent internally
            session.close(true);
            return;
        }
        if (result) {
            version = Integer.parseInt(proposed);
            sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
        } else {
            sendStatus(BufferUtils.clear(buffer), id, SSH_FX_FAILURE, "Unsupported version " + proposed);
            session.close(true);
        }
    }

    /**
     * @param buffer   The {@link Buffer} holding the request
     * @param id       The request id
     * @param proposed The proposed value
     * @return A {@link Boolean} indicating whether to accept/reject the proposal.
     * If {@code null} then rejection response has been sent, otherwise and
     * appropriate response is generated
     * @throws IOException If failed send an independent rejection response
     */
    protected Boolean validateProposedVersion(Buffer buffer, int id, String proposed) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_EXTENDED(version-select) (version={})", proposed);
        }

        if (GenericUtils.length(proposed) != 1) {
            return Boolean.FALSE;
        }

        char digit = proposed.charAt(0);
        if ((digit < '0') || (digit > '9')) {
            return Boolean.FALSE;
        }

        int value = digit - '0';
        String all = checkVersionCompatibility(buffer, id, value, SSH_FX_FAILURE);
        if (GenericUtils.isEmpty(all)) {    // validation failed
            return null;
        } else {
            return Boolean.TRUE;
        }
    }

    /**
     * Checks if a proposed version is within supported range. <B>Note:</B>
     * if the user forced a specific value via the {@link #SFTP_VERSION}
     * property, then it is used to validate the proposed value
     *
     * @param buffer        The {@link Buffer} containing the request
     * @param id            The SSH message ID to be used to send the failure message
     *                      if required
     * @param proposed      The proposed version value
     * @param failureOpcode The failure opcode to send if validation fails
     * @return A {@link String} of comma separated values representing all
     * the supported version - {@code null} if validation failed and an
     * appropriate status message was sent
     * @throws IOException If failed to send the failure status message
     */
    protected String checkVersionCompatibility(Buffer buffer, int id, int proposed, int failureOpcode) throws IOException {
        int low = LOWER_SFTP_IMPL;
        int hig = HIGHER_SFTP_IMPL;
        String available = ALL_SFTP_IMPL;
        // check if user wants to use a specific version
        Integer sftpVersion = FactoryManagerUtils.getInteger(session, SFTP_VERSION);
        if (sftpVersion != null) {
            int forcedValue = sftpVersion;
            if ((forcedValue < LOWER_SFTP_IMPL) || (forcedValue > HIGHER_SFTP_IMPL)) {
                throw new IllegalStateException("Forced SFTP version (" + sftpVersion + ") not within supported values: " + available);
            }
            hig = sftpVersion;
            low = hig;
            available = sftpVersion.toString();
        }

        if (log.isTraceEnabled()) {
            log.trace("checkVersionCompatibility(id={}) - proposed={}, available={}",
                    id, proposed, available);
        }

        if ((proposed < low) || (proposed > hig)) {
            sendStatus(BufferUtils.clear(buffer), id, failureOpcode, "Proposed version (" + proposed + ") not in supported range: " + available);
            return null;
        }

        return available;
    }

    protected void doBlock(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        long offset = buffer.getLong();
        long length = buffer.getLong();
        int mask = buffer.getInt();

        try {
            doBlock(id, handle, offset, length, mask);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doBlock(int id, String handle, long offset, long length, int mask) throws IOException {
        Handle p = handles.get(handle);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_BLOCK (handle={}[{}], offset={}, length={}, mask=0x{})",
                    handle, p, offset, length, Integer.toHexString(mask));
        }

        FileHandle fileHandle = validateHandle(handle, p, FileHandle.class);
        fileHandle.lock(offset, length, mask);
    }

    protected void doUnblock(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        long offset = buffer.getLong();
        long length = buffer.getLong();
        boolean found;
        try {
            found = doUnblock(id, handle, offset, length);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, found ? SSH_FX_OK : SSH_FX_NO_MATCHING_BYTE_RANGE_LOCK, "");
    }

    protected boolean doUnblock(int id, String handle, long offset, long length) throws IOException {
        Handle p = handles.get(handle);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_UNBLOCK (handle={}[{}], offset={}, length={})",
                    handle, p, offset, length);
        }

        FileHandle fileHandle = validateHandle(handle, p, FileHandle.class);
        return fileHandle.unlock(offset, length);
    }

    protected void doLink(Buffer buffer, int id) throws IOException {
        String targetPath = buffer.getString();
        String linkPath = buffer.getString();
        boolean symLink = buffer.getBoolean();

        try {
            if (log.isDebugEnabled()) {
                log.debug("Received SSH_FXP_LINK id={}, linkpath={}, targetpath={}, symlink={}",
                          id, linkPath, targetPath, symLink);
            }

            doLink(id, targetPath, linkPath, symLink);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doLink(int id, String targetPath, String linkPath, boolean symLink) throws IOException {
        createLink(id, targetPath, linkPath, symLink);
    }

    protected void doSymLink(Buffer buffer, int id) throws IOException {
        String targetPath = buffer.getString();
        String linkPath = buffer.getString();
        try {
            if (log.isDebugEnabled()) {
                log.debug("Received SSH_FXP_SYMLINK id={}, linkpath={}, targetpath={}", id, targetPath, linkPath);
            }
            doSymLink(id, targetPath, linkPath);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doSymLink(int id, String targetPath, String linkPath) throws IOException {
        createLink(id, targetPath, linkPath, true);
    }

    protected void createLink(int id, String targetPath, String linkPath, boolean symLink) throws IOException {
        Path link = resolveFile(linkPath);
        Path target = fileSystem.getPath(targetPath);
        if (log.isDebugEnabled()) {
            log.debug("createLink(id={}), linkpath={}[{}], targetpath={}[{}], symlink={})",
                      id, linkPath, link, targetPath, target, symLink);
        }

        if (symLink) {
            Files.createSymbolicLink(link, target);
        } else {
            Files.createLink(link, target);
        }
    }

    protected void doReadLink(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        String l;
        try {
            if (log.isDebugEnabled()) {
                log.debug("Received SSH_FXP_READLINK id={} path={}", id, path);
            }
            l = doReadLink(id, path);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendLink(BufferUtils.clear(buffer), id, l);
    }

    protected String doReadLink(int id, String path) throws IOException {
        Path f = resolveFile(path);
        Path t = Files.readSymbolicLink(f);
        if (log.isDebugEnabled()) {
            log.debug("doReadLink(id={}) path={}[{}]: {}", id, path, f, t);
        }
        return t.toString();
    }

    protected void doRename(Buffer buffer, int id) throws IOException {
        String oldPath = buffer.getString();
        String newPath = buffer.getString();
        int flags = 0;
        if (version >= SFTP_V5) {
            flags = buffer.getInt();
        }
        try {
            doRename(id, oldPath, newPath, flags);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doRename(int id, String oldPath, String newPath, int flags) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_RENAME (oldPath={}, newPath={}, flags=0x{})",
                    oldPath, newPath, Integer.toHexString(flags));
        }

        Collection<CopyOption> opts = Collections.emptyList();
        if (flags != 0) {
            opts = new ArrayList<>();
            if ((flags & SSH_FXP_RENAME_ATOMIC) == SSH_FXP_RENAME_ATOMIC) {
                opts.add(StandardCopyOption.ATOMIC_MOVE);
            }
            if ((flags & SSH_FXP_RENAME_OVERWRITE) == SSH_FXP_RENAME_OVERWRITE) {
                opts.add(StandardCopyOption.REPLACE_EXISTING);
            }
        }

        doRename(id, oldPath, newPath, opts);
    }

    protected void doRename(int id, String oldPath, String newPath, Collection<CopyOption> opts) throws IOException {
        Path o = resolveFile(oldPath);
        Path n = resolveFile(newPath);
        Files.move(o, n, GenericUtils.isEmpty(opts) ? IoUtils.EMPTY_COPY_OPTIONS : opts.toArray(new CopyOption[opts.size()]));
    }

    // see https://tools.ietf.org/html/draft-ietf-secsh-filexfer-extensions-00#section-7
    protected void doCopyData(Buffer buffer, int id) throws IOException {
        String readHandle = buffer.getString();
        long readOffset = buffer.getLong();
        long readLength = buffer.getLong();
        String writeHandle = buffer.getString();
        long writeOffset = buffer.getLong();
        try {
            doCopyData(id, readHandle, readOffset, readLength, writeHandle, writeOffset);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    @SuppressWarnings("resource")
    protected void doCopyData(int id, String readHandle, long readOffset, long readLength, String writeHandle, long writeOffset) throws IOException {
        boolean inPlaceCopy = readHandle.equals(writeHandle);
        Handle rh = handles.get(readHandle);
        Handle wh = inPlaceCopy ? rh : handles.get(writeHandle);
        if (log.isDebugEnabled()) {
            log.debug("SSH_FXP_EXTENDED[{}] read={}[{}], read-offset={}, read-length={}, write={}[{}], write-offset={})",
                    SftpConstants.EXT_COPY_DATA,
                    readHandle, rh, readOffset, readLength,
                    writeHandle, wh, writeOffset);
        }

        FileHandle srcHandle = validateHandle(readHandle, rh, FileHandle.class);
        Path srcPath = srcHandle.getFile();
        int srcAccess = srcHandle.getAccessMask();
        if ((srcAccess & ACE4_READ_DATA) != ACE4_READ_DATA) {
            throw new AccessDeniedException("File not opened for read: " + srcPath);
        }

        ValidateUtils.checkTrue(readLength >= 0L, "Invalid read length: %d", readLength);
        ValidateUtils.checkTrue(readOffset >= 0L, "Invalid read offset: %d", readOffset);

        long totalSize = Files.size(srcHandle.getFile());
        long effectiveLength = readLength;
        if (effectiveLength == 0L) {
            effectiveLength = totalSize - readOffset;
        } else {
            long maxRead = readOffset + effectiveLength;
            if (maxRead > totalSize) {
                effectiveLength = totalSize - readOffset;
            }
        }
        ValidateUtils.checkTrue(effectiveLength > 0L, "Non-positive effective copy data length: %d", effectiveLength);

        FileHandle dstHandle = inPlaceCopy ? srcHandle : validateHandle(writeHandle, wh, FileHandle.class);
        int dstAccess = dstHandle.getAccessMask();
        if ((dstAccess & ACE4_WRITE_DATA) != ACE4_WRITE_DATA) {
            throw new AccessDeniedException("File not opened for write: " + srcHandle);
        }

        ValidateUtils.checkTrue(writeOffset >= 0L, "Invalid write offset: %d", writeOffset);
        // check if overlapping ranges as per the draft
        if (inPlaceCopy) {
            long maxRead = readOffset + effectiveLength;
            if (maxRead > totalSize) {
                maxRead = totalSize;
            }

            long maxWrite = writeOffset + effectiveLength;
            if (maxWrite > readOffset) {
                throw new IllegalArgumentException("Write range end [" + writeOffset + "-" + maxWrite + "]"
                        + " overlaps with read range [" + readOffset + "-" + maxRead + "]");
            } else if (maxRead > writeOffset) {
                throw new IllegalArgumentException("Read range end [" + readOffset + "-" + maxRead + "]"
                        + " overlaps with write range [" + writeOffset + "-" + maxWrite + "]");
            }
        }

        byte[] copyBuf = new byte[Math.min(IoUtils.DEFAULT_COPY_SIZE, (int) effectiveLength)];
        while (effectiveLength > 0L) {
            int remainLength = Math.min(copyBuf.length, (int) effectiveLength);
            int readLen = srcHandle.read(copyBuf, 0, remainLength, readOffset);
            if (readLen < 0) {
                throw new EOFException("Premature EOF while still remaining " + effectiveLength + " bytes");
            }
            dstHandle.write(copyBuf, 0, readLen, writeOffset);

            effectiveLength -= readLen;
            readOffset += readLen;
            writeOffset += readLen;
        }
    }

    // see https://tools.ietf.org/html/draft-ietf-secsh-filexfer-extensions-00#section-6
    protected void doCopyFile(Buffer buffer, int id) throws IOException {
        String srcFile = buffer.getString();
        String dstFile = buffer.getString();
        boolean overwriteDestination = buffer.getBoolean();

        try {
            doCopyFile(id, srcFile, dstFile, overwriteDestination);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doCopyFile(int id, String srcFile, String dstFile, boolean overwriteDestination) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("SSH_FXP_EXTENDED[{}] (src={}, dst={}, overwrite=0x{})",
                    SftpConstants.EXT_COPY_FILE, srcFile, dstFile, overwriteDestination);
        }

        doCopyFile(id, srcFile, dstFile,
                overwriteDestination
                        ? Collections.<CopyOption>singletonList(StandardCopyOption.REPLACE_EXISTING)
                        : Collections.<CopyOption>emptyList());
    }

    protected void doCopyFile(int id, String srcFile, String dstFile, Collection<CopyOption> opts) throws IOException {
        Path src = resolveFile(srcFile);
        Path dst = resolveFile(dstFile);
        Files.copy(src, dst, GenericUtils.isEmpty(opts) ? IoUtils.EMPTY_COPY_OPTIONS : opts.toArray(new CopyOption[opts.size()]));
    }

    protected void doStat(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        int flags = SSH_FILEXFER_ATTR_ALL;
        if (version >= SFTP_V4) {
            flags = buffer.getInt();
        }

        Map<String, Object> attrs;
        try {
            attrs = doStat(id, path, flags);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendAttrs(BufferUtils.clear(buffer), id, attrs);
    }

    protected Map<String, Object> doStat(int id, String path, int flags) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_STAT (path={}, flags=0x{})", path, Integer.toHexString(flags));
        }
        Path p = resolveFile(path);
        return resolveFileAttributes(p, flags, IoUtils.getLinkOptions(false));
    }

    protected void doRealPath(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        log.debug("Received SSH_FXP_REALPATH (path={})", path);
        path = GenericUtils.trimToEmpty(path);
        if (GenericUtils.isEmpty(path)) {
            path = ".";
        }

        Map<String, ?> attrs = Collections.<String, Object>emptyMap();
        Pair<Path, Boolean> result;
        try {
            LinkOption[] options = IoUtils.getLinkOptions(false);
            if (version < SFTP_V6) {
                /*
                 * See http://www.openssh.com/txt/draft-ietf-secsh-filexfer-02.txt:
                 *
                 *      The SSH_FXP_REALPATH request can be used to have the server
                 *      canonicalize any given path name to an absolute path.
                 *
                 * See also SSHD-294
                 */
                result = doRealPathV345(id, path, options);
            } else {
                // see https://tools.ietf.org/html/draft-ietf-secsh-filexfer-13 section 8.9
                int control = 0;
                if (buffer.available() > 0) {
                    control = buffer.getUByte();
                }

                Collection<String> extraPaths = new LinkedList<>();
                while (buffer.available() > 0) {
                    extraPaths.add(buffer.getString());
                }

                result = doRealPathV6(id, path, extraPaths, options);

                Path p = result.getFirst();
                Boolean status = result.getSecond();
                if (control == SSH_FXP_REALPATH_STAT_IF) {
                    if (status == null) {
                        attrs = handleUnknownStatusFileAttributes(p, SSH_FILEXFER_ATTR_ALL, options);
                    } else if (status) {
                        try {
                            attrs = getAttributes(p, IoUtils.getLinkOptions(false));
                        } catch (IOException e) {
                            if (log.isDebugEnabled()) {
                                log.debug("Failed ({}) to retrieve attributes of {}: {}",
                                        e.getClass().getSimpleName(), p, e.getMessage());
                            }
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Dummy attributes for non-existing file: " + p);
                        }
                    }
                } else if (control == SSH_FXP_REALPATH_STAT_ALWAYS) {
                    if (status == null) {
                        attrs = handleUnknownStatusFileAttributes(p, SSH_FILEXFER_ATTR_ALL, options);
                    } else if (status) {
                        attrs = getAttributes(p, options);
                    } else {
                        throw new FileNotFoundException(p.toString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendPath(BufferUtils.clear(buffer), id, result.getFirst(), attrs);
    }

    protected Pair<Path, Boolean> doRealPathV6(int id, String path, Collection<String> extraPaths, LinkOption... options) throws IOException {
        Path p = resolveFile(path);
        int numExtra = GenericUtils.size(extraPaths);
        if (numExtra > 0) {
            StringBuilder sb = new StringBuilder(GenericUtils.length(path) + numExtra * 8);
            sb.append(path);

            for (String p2 : extraPaths) {
                p = p.resolve(p2);
                sb.append('/').append(p2);
            }

            path = sb.toString();
        }

        return validateRealPath(id, path, p, options);
    }

    protected Pair<Path, Boolean> doRealPathV345(int id, String path, LinkOption... options) throws IOException {
        return validateRealPath(id, path, resolveFile(path), options);
    }

    /**
     * @param id      The request identifier
     * @param path    The original path
     * @param f       The resolve {@link Path}
     * @param options The {@link LinkOption}s to use to verify file existence and access
     * @return A {@link Pair} whose left-hand is the <U>absolute <B>normalized</B></U>
     * {@link Path} and right-hand is a {@link Boolean} indicating its status
     * @throws IOException If failed to validate the file
     * @see IoUtils#checkFileExists(Path, LinkOption...)
     */
    protected Pair<Path, Boolean> validateRealPath(int id, String path, Path f, LinkOption... options) throws IOException {
        Path p = normalize(f);
        Boolean status = IoUtils.checkFileExists(p, options);
        return new Pair<>(p, status);
    }

    protected void doRemoveDirectory(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        try {
            doRemoveDirectory(id, path, IoUtils.getLinkOptions(false));
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doRemoveDirectory(int id, String path, LinkOption... options) throws IOException {
        Path p = resolveFile(path);
        log.debug("Received SSH_FXP_RMDIR (path={})[{}]", path, p);
        if (Files.isDirectory(p, options)) {
            Files.delete(p);
        } else {
            throw new NotDirectoryException(p.toString());
        }
    }

    protected void doMakeDirectory(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        Map<String, Object> attrs = readAttrs(buffer);
        try {
            doMakeDirectory(id, path, attrs, IoUtils.getLinkOptions(false));
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doMakeDirectory(int id, String path, Map<String, ?> attrs, LinkOption... options) throws IOException {
        Path p = resolveFile(path);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_MKDIR (path={}[{}], attrs={})", path, p, attrs);
        }

        Boolean status = IoUtils.checkFileExists(p, options);
        if (status == null) {
            throw new AccessDeniedException("Cannot validate make-directory existence for " + p);
        }

        if (status) {
            if (Files.isDirectory(p, options)) {
                throw new FileAlreadyExistsException(p.toString(), p.toString(), "Target directory already exists");
            } else {
                throw new FileNotFoundException(p.toString() + " already exists as a file");
            }
        } else {
            Files.createDirectory(p);
            setAttributes(p, attrs);
        }
    }

    protected void doRemove(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        try {
            doRemove(id, path, IoUtils.getLinkOptions(false));
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doRemove(int id, String path, LinkOption... options) throws IOException {
        Path p = resolveFile(path);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_REMOVE (path={}[{}])", path, p);
        }

        Boolean status = IoUtils.checkFileExists(p, options);
        if (status == null) {
            throw new AccessDeniedException("Cannot determine existence of remove candidate: " + p);
        }
        if (!status) {
            throw new FileNotFoundException(p.toString());
        } else if (Files.isDirectory(p, options)) {
            throw new FileNotFoundException(p.toString() + " is as a folder");
        } else {
            Files.delete(p);
        }
    }

    protected void doReadDir(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        Handle h = handles.get(handle);
        log.debug("Received SSH_FXP_READDIR (handle={}[{}])", handle, h);

        Buffer reply = null;
        try {
            DirectoryHandle dh = validateHandle(handle, h, DirectoryHandle.class);
            if (dh.isDone()) {
                throw new EOFException("Directory reading is done");
            }

            Path file = dh.getFile();
            LinkOption[] options = IoUtils.getLinkOptions(false);
            Boolean status = IoUtils.checkFileExists(file, options);
            if (status == null) {
                throw new AccessDeniedException("Cannot determine existence of read-dir for " + file);
            }

            if (!status) {
                throw new FileNotFoundException(file.toString());
            } else if (!Files.isDirectory(file, options)) {
                throw new NotDirectoryException(file.toString());
            } else if (!Files.isReadable(file)) {
                throw new AccessDeniedException("Not readable: " + file.toString());
            }

            if (dh.isSendDot() || dh.isSendDotDot() || dh.hasNext()) {
                // There is at least one file in the directory or we need to send the "..".
                // Send only a few files at a time to not create packets of a too
                // large size or have a timeout to occur.

                reply = BufferUtils.clear(buffer);
                reply.putByte((byte) SSH_FXP_NAME);
                reply.putInt(id);
                int lenPos = reply.wpos();
                reply.putInt(0);

                int count = doReadDir(id, dh, reply, FactoryManagerUtils.getIntProperty(session, MAX_PACKET_LENGTH_PROP, DEFAULT_MAX_PACKET_LENGTH));
                BufferUtils.updateLengthPlaceholder(reply, lenPos, count);
                if (log.isDebugEnabled()) {
                    log.debug("doReadDir({})[{}] - sent {} entries", handle, h, count);
                }
                if ((!dh.isSendDot()) && (!dh.isSendDotDot()) && (!dh.hasNext())) {
                    // if no more files to send
                    dh.markDone();
                }
            } else {
                // empty directory
                dh.markDone();
                throw new EOFException("Empty directory");
            }

            ValidateUtils.checkNotNull(reply, "No reply buffer created");
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        send(reply);
    }

    protected void doOpenDir(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        String handle;

        try {
            handle = doOpenDir(id, path, IoUtils.getLinkOptions(false));
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendHandle(BufferUtils.clear(buffer), id, handle);
    }

    protected String doOpenDir(int id, String path, LinkOption... options) throws IOException {
        Path p = resolveNormalizedLocation(path);
        log.debug("Received SSH_FXP_OPENDIR (path={})[{}]", path, p);

        Boolean status = IoUtils.checkFileExists(p, options);
        if (status == null) {
            throw new AccessDeniedException("Cannot determine open-dir existence for " + p);
        }

        if (!status) {
            throw new FileNotFoundException(path);
        } else if (!Files.isDirectory(p, options)) {
            throw new NotDirectoryException(path);
        } else if (!Files.isReadable(p)) {
            throw new AccessDeniedException("Not readable: " + p);
        } else {
            String handle = generateFileHandle(p);
            handles.put(handle, new DirectoryHandle(p));
            return handle;
        }
    }

    protected void doFSetStat(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        Map<String, Object> attrs = readAttrs(buffer);
        try {
            doFSetStat(id, handle, attrs);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doFSetStat(int id, String handle, Map<String, ?> attrs) throws IOException {
        Handle h = handles.get(handle);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_FSETSTAT (handle={}[{}], attrs={})", handle, h, attrs);
        }

        setAttributes(validateHandle(handle, h, Handle.class).getFile(), attrs);
    }

    protected void doSetStat(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        Map<String, Object> attrs = readAttrs(buffer);
        try {
            doSetStat(id, path, attrs);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doSetStat(int id, String path, Map<String, ?> attrs) throws IOException {
        log.debug("Received SSH_FXP_SETSTAT (path={}, attrs={})", path, attrs);
        Path p = resolveFile(path);
        setAttributes(p, attrs);
    }

    protected void doFStat(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        int flags = SSH_FILEXFER_ATTR_ALL;
        if (version >= SFTP_V4) {
            flags = buffer.getInt();
        }

        Map<String, ?> attrs;
        try {
            attrs = doFStat(id, handle, flags);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendAttrs(BufferUtils.clear(buffer), id, attrs);
    }

    protected Map<String, Object> doFStat(int id, String handle, int flags) throws IOException {
        Handle h = handles.get(handle);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_FSTAT (handle={}[{}], flags=0x{})", handle, h, Integer.toHexString(flags));
        }

        return resolveFileAttributes(validateHandle(handle, h, Handle.class).getFile(), flags, IoUtils.getLinkOptions(true));
    }

    protected void doLStat(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        int flags = SSH_FILEXFER_ATTR_ALL;
        if (version >= SFTP_V4) {
            flags = buffer.getInt();
        }

        Map<String, ?> attrs;
        try {
            attrs = doLStat(id, path, flags);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendAttrs(BufferUtils.clear(buffer), id, attrs);
    }

    protected Map<String, Object> doLStat(int id, String path, int flags) throws IOException {
        Path p = resolveFile(path);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_LSTAT (path={}[{}], flags=0x{})", path, p, Integer.toHexString(flags));
        }

        return resolveFileAttributes(p, flags, IoUtils.getLinkOptions(false));
    }

    protected void doWrite(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        long offset = buffer.getLong();
        int length = buffer.getInt();
        try {
            doWrite(id, handle, offset, length, buffer.array(), buffer.rpos(), buffer.available());
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "");
    }

    protected void doWrite(int id, String handle, long offset, int length, byte[] data, int doff, int remaining) throws IOException {
        Handle h = handles.get(handle);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_WRITE (handle={}[{}], offset={}, data=byte[{}])",
                    handle, h, offset, length);
        }

        FileHandle fh = validateHandle(handle, h, FileHandle.class);
        if (length < 0) {
            throw new IllegalStateException("Bad length (" + length + ") for writing to " + fh);
        }

        if (remaining < length) {
            throw new IllegalStateException("Not enough buffer data for writing to " + fh + ": required=" + length + ", available=" + remaining);
        }

        if (fh.isOpenAppend()) {
            fh.append(data, doff, length);
        } else {
            fh.write(data, doff, length, offset);
        }
    }

    protected void doRead(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        long offset = buffer.getLong();
        int requestedLength = buffer.getInt();
        int maxAllowed = FactoryManagerUtils.getIntProperty(session, MAX_PACKET_LENGTH_PROP, DEFAULT_MAX_PACKET_LENGTH);
        int readLen = Math.min(requestedLength, maxAllowed);

        if (log.isTraceEnabled()) {
            log.trace("doRead({})[offset={}] - req.={}, max.={}, effective={}",
                      handle, offset, requestedLength, maxAllowed, readLen);
        }

        try {
            ValidateUtils.checkTrue(readLen >= 0, "Illegal requested read length: %d", readLen);

            buffer.clear();
            buffer.ensureCapacity(readLen + Long.SIZE /* the header */, Int2IntFunction.IDENTITY);

            buffer.putByte((byte) SSH_FXP_DATA);
            buffer.putInt(id);
            int lenPos = buffer.wpos();
            buffer.putInt(0);

            int startPos = buffer.wpos();
            int len = doRead(id, handle, offset, readLen, buffer.array(), startPos);
            if (len < 0) {
                throw new EOFException("Unable to read " + readLen + " bytes from offset=" + offset + " of " + handle);
            }
            buffer.wpos(startPos + len);
            BufferUtils.updateLengthPlaceholder(buffer, lenPos, len);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        send(buffer);
    }

    protected int doRead(int id, String handle, long offset, int length, byte[] data, int doff) throws IOException {
        Handle h = handles.get(handle);
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_READ (handle={}[{}], offset={}, length={})",
                    handle, h, offset, length);
        }
        ValidateUtils.checkTrue(length > 0, "Invalid read length: %d", length);
        FileHandle fh = validateHandle(handle, h, FileHandle.class);

        return fh.read(data, doff, length, offset);
    }

    protected void doClose(Buffer buffer, int id) throws IOException {
        String handle = buffer.getString();
        try {
            doClose(id, handle);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendStatus(BufferUtils.clear(buffer), id, SSH_FX_OK, "", "");
    }

    protected void doClose(int id, String handle) throws IOException {
        Handle h = handles.remove(handle);
        log.debug("Received SSH_FXP_CLOSE (handle={}[{}])", handle, h);
        validateHandle(handle, h, Handle.class).close();
    }

    protected void doOpen(Buffer buffer, int id) throws IOException {
        String path = buffer.getString();
        /*
         * Be consistent with FileChannel#open - if no mode specified then READ is assumed
         */
        int access = 0;
        if (version >= SFTP_V5) {
            access = buffer.getInt();
            if (access == 0) {
                access = ACE4_READ_DATA | ACE4_READ_ATTRIBUTES;
            }
        }

        int pflags = buffer.getInt();
        if (pflags == 0) {
            pflags = SSH_FXF_READ;
        }

        if (version < SFTP_V5) {
            int flags = pflags;
            pflags = 0;
            switch (flags & (SSH_FXF_READ | SSH_FXF_WRITE)) {
                case SSH_FXF_READ:
                    access |= ACE4_READ_DATA | ACE4_READ_ATTRIBUTES;
                    break;
                case SSH_FXF_WRITE:
                    access |= ACE4_WRITE_DATA | ACE4_WRITE_ATTRIBUTES;
                    break;
                default:
                    access |= ACE4_READ_DATA | ACE4_READ_ATTRIBUTES;
                    access |= ACE4_WRITE_DATA | ACE4_WRITE_ATTRIBUTES;
                    break;
            }
            if ((flags & SSH_FXF_APPEND) != 0) {
                access |= ACE4_APPEND_DATA;
                pflags |= SSH_FXF_APPEND_DATA | SSH_FXF_APPEND_DATA_ATOMIC;
            }
            if ((flags & SSH_FXF_CREAT) != 0) {
                if ((flags & SSH_FXF_EXCL) != 0) {
                    pflags |= SSH_FXF_CREATE_NEW;
                } else if ((flags & SSH_FXF_TRUNC) != 0) {
                    pflags |= SSH_FXF_CREATE_TRUNCATE;
                } else {
                    pflags |= SSH_FXF_OPEN_OR_CREATE;
                }
            } else {
                if ((flags & SSH_FXF_TRUNC) != 0) {
                    pflags |= SSH_FXF_TRUNCATE_EXISTING;
                } else {
                    pflags |= SSH_FXF_OPEN_EXISTING;
                }
            }
        }

        Map<String, Object> attrs = readAttrs(buffer);
        String handle;
        try {
            handle = doOpen(id, path, pflags, access, attrs);
        } catch (IOException | RuntimeException e) {
            sendStatus(BufferUtils.clear(buffer), id, e);
            return;
        }

        sendHandle(BufferUtils.clear(buffer), id, handle);
    }

    /**
     * @param id     Request id
     * @param path   Path
     * @param pflags Open mode flags - see {@code SSH_FXF_XXX} flags
     * @param access Access mode flags - see {@code ACE4_XXX} flags
     * @param attrs  Requested attributes
     * @return The assigned (opaque) handle
     * @throws IOException if failed to execute
     */
    protected String doOpen(int id, String path, int pflags, int access, Map<String, Object> attrs) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_OPEN (path={}, access=0x{}, pflags=0x{}, attrs={})",
                    path, Integer.toHexString(access), Integer.toHexString(pflags), attrs);
        }
        int curHandleCount = handles.size();
        int maxHandleCount = FactoryManagerUtils.getIntProperty(session, MAX_OPEN_HANDLES_PER_SESSION, DEFAULT_MAX_OPEN_HANDLES);
        if (curHandleCount > maxHandleCount) {
            throw new IllegalStateException("Too many open handles: current=" + curHandleCount + ", max.=" + maxHandleCount);
        }

        Path file = resolveFile(path);
        String handle = generateFileHandle(file);
        handles.put(handle, new FileHandle(this, file, pflags, access, attrs));
        return handle;
    }

    // we stringify our handles and treat them as such on decoding as well as it is easier to use as a map key
    protected String generateFileHandle(Path file) {
        // use several rounds in case the file handle size is relatively small so we might get conflicts
        for (int index = 0; index < maxFileHandleRounds; index++) {
            randomizer.fill(workBuf, 0, fileHandleSize);
            String handle = BufferUtils.printHex(workBuf, 0, fileHandleSize, BufferUtils.EMPTY_HEX_SEPARATOR);
            if (handles.containsKey(handle)) {
                if (log.isTraceEnabled()) {
                    log.trace("generateFileHandle({}) handle={} in use at round {}", file, handle, Integer.valueOf(index));
                }
                continue;
            }

            if (log.isTraceEnabled()) {
                log.trace("generateFileHandle({}) {}", file, handle);
            }
            return handle;
        }

        throw new IllegalStateException("Failed to generate a unique file handle for " + file);
    }

    protected void doInit(Buffer buffer, int id) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Received SSH_FXP_INIT (version={})", Integer.valueOf(id));
        }

        String all = checkVersionCompatibility(buffer, id, id, SSH_FX_OP_UNSUPPORTED);
        if (GenericUtils.isEmpty(all)) { // i.e. validation failed
            return;
        }
        version = id;
        while (buffer.available() > 0) {
            String name = buffer.getString();
            byte[] data = buffer.getBytes();
            extensions.put(name, data);
        }

        buffer.clear();

        buffer.putByte((byte) SSH_FXP_VERSION);
        buffer.putInt(version);
        appendExtensions(buffer, all);

        send(buffer);
    }

    protected void appendExtensions(Buffer buffer, String supportedVersions) {
        appendVersionsExtension(buffer, supportedVersions);
        appendNewlineExtension(buffer, System.getProperty("line.separator"));
        appendVendorIdExtension(buffer, VersionProperties.getVersionProperties());
        appendOpenSSHExtensions(buffer);

        /* TODO updateAvailableExtensions(extensions, appendAclSupportedExtension(...)
            buffer.putString("acl-supported");
            buffer.putInt(4);
            // capabilities
            buffer.putInt(0);
        */

        Collection<String> extras = getSupportedClientExtensions();
        appendSupportedExtension(buffer, extras);
        appendSupported2Extension(buffer, extras);
    }

    protected List<OpenSSHExtension> appendOpenSSHExtensions(Buffer buffer) {
        List<OpenSSHExtension> extList = resolveOpenSSHExtensions();
        if (GenericUtils.isEmpty(extList)) {
            return extList;
        }

        for (OpenSSHExtension ext : extList) {
            buffer.putString(ext.getName());
            buffer.putString(ext.getVersion());
        }

        return extList;
    }

    protected List<OpenSSHExtension> resolveOpenSSHExtensions() {
        String value = FactoryManagerUtils.getString(session, OPENSSH_EXTENSIONS_PROP);
        if (value == null) {    // No override
            return DEFAULT_OPEN_SSH_EXTENSIONS;
        }

        String[] pairs = GenericUtils.split(value, ',');
        int numExts = GenericUtils.length(pairs);
        if (numExts <= 0) {     // User does not want to report ANY extensions
            return Collections.emptyList();
        }

        List<OpenSSHExtension> extList = new ArrayList<>(numExts);
        for (String nvp : pairs) {
            nvp = GenericUtils.trimToEmpty(nvp);
            if (GenericUtils.isEmpty(nvp)) {
                continue;
            }

            int pos = nvp.indexOf('=');
            ValidateUtils.checkTrue((pos > 0) && (pos < (nvp.length() - 1)), "Malformed OpenSSH extension spec: %s", nvp);
            String name = GenericUtils.trimToEmpty(nvp.substring(0, pos));
            String version = GenericUtils.trimToEmpty(nvp.substring(pos + 1));
            extList.add(new OpenSSHExtension(name, ValidateUtils.checkNotNullAndNotEmpty(version, "No version specified for OpenSSH extension %s", name)));
        }

        return extList;
    }

    protected Collection<String> getSupportedClientExtensions() {
        String value = FactoryManagerUtils.getString(session, CLIENT_EXTENSIONS_PROP);
        if (value == null) {
            return DEFAULT_SUPPORTED_CLIENT_EXTENSIONS;
        }

        if (value.length() <= 0) {  // means don't report any extensions
            return Collections.emptyList();
        }

        String[] comps = GenericUtils.split(value, ',');
        return Arrays.asList(comps);
    }

    /**
     * Appends the &quot;versions&quot; extension to the buffer. <B>Note:</B>
     * if overriding this method make sure you either do not append anything
     * or use the correct extension name
     *
     * @param buffer The {@link Buffer} to append to
     * @param value  The recommended value
     * @see SftpConstants#EXT_VERSIONS
     */
    protected void appendVersionsExtension(Buffer buffer, String value) {
        buffer.putString(EXT_VERSIONS);
        buffer.putString(value);
    }

    /**
     * Appends the &quot;newline&quot; extension to the buffer. <B>Note:</B>
     * if overriding this method make sure you either do not append anything
     * or use the correct extension name
     *
     * @param buffer The {@link Buffer} to append to
     * @param value  The recommended value
     * @see SftpConstants#EXT_NEWLINE
     */
    protected void appendNewlineExtension(Buffer buffer, String value) {
        buffer.putString(EXT_NEWLINE);
        buffer.putString(value);
    }

    /**
     * Appends the &quot;vendor-id&quot; extension to the buffer. <B>Note:</B>
     * if overriding this method make sure you either do not append anything
     * or use the correct extension name
     *
     * @param buffer            The {@link Buffer} to append to
     * @param versionProperties The currently available version properties
     * @see SftpConstants#EXT_VENDOR_ID
     * @see <A HREF="http://tools.ietf.org/wg/secsh/draft-ietf-secsh-filexfer/draft-ietf-secsh-filexfer-09.txt">DRAFT 09 - section 4.4</A>
     */
    protected void appendVendorIdExtension(Buffer buffer, Map<String, ?> versionProperties) {
        buffer.putString(EXT_VENDOR_ID);

        // placeholder for length
        int lenPos = buffer.wpos();
        buffer.putInt(0);
        buffer.putString(FactoryManagerUtils.getStringProperty(versionProperties, "groupId", getClass().getPackage().getName()));   // vendor-name
        buffer.putString(FactoryManagerUtils.getStringProperty(versionProperties, "artifactId", getClass().getSimpleName()));       // product-name
        buffer.putString(FactoryManagerUtils.getStringProperty(versionProperties, "version", FactoryManager.DEFAULT_VERSION));      // product-version
        buffer.putLong(0L); // product-build-number
        BufferUtils.updateLengthPlaceholder(buffer, lenPos);
    }

    /**
     * Appends the &quot;supported&quot; extension to the buffer. <B>Note:</B>
     * if overriding this method make sure you either do not append anything
     * or use the correct extension name
     *
     * @param buffer The {@link Buffer} to append to
     * @param extras The extra extensions that are available and can be reported
     *               - may be {@code null}/empty
     */
    protected void appendSupportedExtension(Buffer buffer, Collection<String> extras) {
        buffer.putString(EXT_SUPPORTED);

        int lenPos = buffer.wpos();
        buffer.putInt(0); // length placeholder
        // supported-attribute-mask
        buffer.putInt(SSH_FILEXFER_ATTR_SIZE | SSH_FILEXFER_ATTR_PERMISSIONS
                | SSH_FILEXFER_ATTR_ACCESSTIME | SSH_FILEXFER_ATTR_CREATETIME
                | SSH_FILEXFER_ATTR_MODIFYTIME | SSH_FILEXFER_ATTR_OWNERGROUP
                | SSH_FILEXFER_ATTR_BITS);
        // TODO: supported-attribute-bits
        buffer.putInt(0);
        // supported-open-flags
        buffer.putInt(SSH_FXF_READ | SSH_FXF_WRITE | SSH_FXF_APPEND
                | SSH_FXF_CREAT | SSH_FXF_TRUNC | SSH_FXF_EXCL);
        // TODO: supported-access-mask
        buffer.putInt(0);
        // max-read-size
        buffer.putInt(0);
        // supported extensions
        buffer.putStringList(extras, false);

        BufferUtils.updateLengthPlaceholder(buffer, lenPos);
    }

    /**
     * Appends the &quot;supported2&quot; extension to the buffer. <B>Note:</B>
     * if overriding this method make sure you either do not append anything
     * or use the correct extension name
     *
     * @param buffer The {@link Buffer} to append to
     * @param extras The extra extensions that are available and can be reported
     *               - may be {@code null}/empty
     * @see SftpConstants#EXT_SUPPORTED
     * @see <A HREF="https://tools.ietf.org/html/draft-ietf-secsh-filexfer-13#page-10">DRAFT 13 section 5.4</A>
     */
    protected void appendSupported2Extension(Buffer buffer, Collection<String> extras) {
        buffer.putString(EXT_SUPPORTED2);

        int lenPos = buffer.wpos();
        buffer.putInt(0); // length placeholder
        // supported-attribute-mask
        buffer.putInt(SSH_FILEXFER_ATTR_SIZE | SSH_FILEXFER_ATTR_PERMISSIONS
                | SSH_FILEXFER_ATTR_ACCESSTIME | SSH_FILEXFER_ATTR_CREATETIME
                | SSH_FILEXFER_ATTR_MODIFYTIME | SSH_FILEXFER_ATTR_OWNERGROUP
                | SSH_FILEXFER_ATTR_BITS);
        // TODO: supported-attribute-bits
        buffer.putInt(0);
        // supported-open-flags
        buffer.putInt(SSH_FXF_ACCESS_DISPOSITION | SSH_FXF_APPEND_DATA);
        // TODO: supported-access-mask
        buffer.putInt(0);
        // max-read-size
        buffer.putInt(0);
        // supported-open-block-vector
        buffer.putShort(0);
        // supported-block-vector
        buffer.putShort(0);
        // attrib-extension-count + attributes name
        buffer.putStringList(Collections.<String>emptyList(), true);
        // extension-count + supported extensions
        buffer.putStringList(extras, true);

        BufferUtils.updateLengthPlaceholder(buffer, lenPos);
    }

    protected void sendHandle(Buffer buffer, int id, String handle) throws IOException {
        buffer.putByte((byte) SSH_FXP_HANDLE);
        buffer.putInt(id);
        buffer.putString(handle);
        send(buffer);
    }

    protected void sendAttrs(Buffer buffer, int id, Map<String, ?> attributes) throws IOException {
        buffer.putByte((byte) SSH_FXP_ATTRS);
        buffer.putInt(id);
        writeAttrs(buffer, attributes);
        send(buffer);
    }

    protected void sendPath(Buffer buffer, int id, Path f, Map<String, ?> attrs) throws IOException {
        buffer.putByte((byte) SSH_FXP_NAME);
        buffer.putInt(id);
        buffer.putInt(1);   // one reply

        String originalPath = f.toString();
        //in case we are running on Windows
        String unixPath = originalPath.replace(File.separatorChar, '/');
        //normalize the given path, use *nix style separator
        String normalizedPath = SelectorUtils.normalizePath(unixPath, "/");
        if (normalizedPath.length() == 0) {
            normalizedPath = "/";
        }
        buffer.putString(normalizedPath);

        if (version == SFTP_V3) {
            f = resolveFile(normalizedPath);
            buffer.putString(getLongName(f, attrs));
            buffer.putInt(0);   // no flags
        } else if (version >= SFTP_V4) {
            writeAttrs(buffer, attrs);
        } else {
            throw new IllegalStateException("sendPath(" + f + ") unsupported version: " + version);
        }
        send(buffer);
    }

    protected void sendLink(Buffer buffer, int id, String link) throws IOException {
        //in case we are running on Windows
        String unixPath = link.replace(File.separatorChar, '/');
        buffer.putByte((byte) SSH_FXP_NAME);
        buffer.putInt(id);
        buffer.putInt(1);   // one response

        buffer.putString(unixPath);
        if (version == SFTP_V3) {
            buffer.putString(unixPath);
        }

        /*
         * As per the spec:
         *
         *      The server will respond with a SSH_FXP_NAME packet containing only
         *      one name and a dummy attributes value.
         */
        SftpHelper.writeAttrs(version, buffer, Collections.<String, Object>emptyMap());
        send(buffer);
    }

    /**
     * @param id      Request id
     * @param dir     The {@link DirectoryHandle}
     * @param buffer  The {@link Buffer} to write the results
     * @param maxSize Max. buffer size
     * @return Number of written entries
     * @throws IOException If failed to generate an entry
     */
    protected int doReadDir(int id, DirectoryHandle dir, Buffer buffer, int maxSize) throws IOException {
        int nb = 0;
        LinkOption[] options = IoUtils.getLinkOptions(false);
        while ((dir.isSendDot() || dir.isSendDotDot() || dir.hasNext()) && (buffer.wpos() < maxSize)) {
            if (dir.isSendDot()) {
                writeDirEntry(id, dir, buffer, nb, dir.getFile(), ".", options);
                dir.markDotSent();    // do not send it again
            } else if (dir.isSendDotDot()) {
                writeDirEntry(id, dir, buffer, nb, dir.getFile().getParent(), "..", options);
                dir.markDotDotSent(); // do not send it again
            } else {
                Path f = dir.next();
                writeDirEntry(id, dir, buffer, nb, f, getShortName(f), options);
            }

            nb++;
        }

        return nb;
    }

    /**
     * @param id        Request id
     * @param dir       The {@link DirectoryHandle}
     * @param buffer    The {@link Buffer} to write the results
     * @param index     Zero-based index of the entry to be written
     * @param f         The entry {@link Path}
     * @param shortName The entry short name
     * @param options   The {@link LinkOption}s to use for querying the entry-s attributes
     * @throws IOException If failed to generate the entry data
     */
    protected void writeDirEntry(int id, DirectoryHandle dir, Buffer buffer, int index, Path f, String shortName, LinkOption... options) throws IOException {
        Map<String, ?> attrs = resolveFileAttributes(f, SSH_FILEXFER_ATTR_ALL, options);

        buffer.putString(shortName);
        if (version == SFTP_V3) {
            String longName = getLongName(f, options);
            buffer.putString(longName);
            if (log.isTraceEnabled()) {
                log.trace("writeDirEntry(id=" + id + ")[" + index + "] - " + shortName + " [" + longName + "]: " + attrs);
            }
        } else {
            if (log.isTraceEnabled()) {
                log.trace("writeDirEntry(id=" + id + ")[" + index + "] - " + shortName + ": " + attrs);
            }
        }

        writeAttrs(buffer, attrs);
    }

    protected String getLongName(Path f, LinkOption... options) throws IOException {
        return getLongName(f, true, options);
    }

    private String getLongName(Path f, boolean sendAttrs, LinkOption... options) throws IOException {
        Map<String, Object> attributes;
        if (sendAttrs) {
            attributes = getAttributes(f, options);
        } else {
            attributes = Collections.emptyMap();
        }
        return getLongName(f, attributes);
    }

    private String getLongName(Path f, Map<String, ?> attributes) throws IOException {
        String username;
        if (attributes.containsKey("owner")) {
            username = Objects.toString(attributes.get("owner"), null);
        } else {
            username = "owner";
        }
        if (username.length() > 8) {
            username = username.substring(0, 8);
        } else {
            for (int i = username.length(); i < 8; i++) {
                username = username + " ";
            }
        }
        String group;
        if (attributes.containsKey("group")) {
            group = Objects.toString(attributes.get("group"), null);
        } else {
            group = "group";
        }
        if (group.length() > 8) {
            group = group.substring(0, 8);
        } else {
            for (int i = group.length(); i < 8; i++) {
                group = group + " ";
            }
        }

        Number length = (Number) attributes.get("size");
        if (length == null) {
            length = 0L;
        }
        String lengthString = String.format("%1$8s", length);

        Boolean isDirectory = (Boolean) attributes.get("isDirectory");
        Boolean isLink = (Boolean) attributes.get("isSymbolicLink");
        @SuppressWarnings("unchecked")
        Set<PosixFilePermission> perms = (Set<PosixFilePermission>) attributes.get("permissions");
        if (perms == null) {
            perms = EnumSet.noneOf(PosixFilePermission.class);
        }

        return (SftpHelper.getBool(isDirectory) ? "d" : (SftpHelper.getBool(isLink) ? "l" : "-"))
                + PosixFilePermissions.toString(perms) + "  "
                + (attributes.containsKey("nlink") ? attributes.get("nlink") : "1")
                + " " + username + " " + group + " " + lengthString + " "
                + UnixDateFormat.getUnixDate((FileTime) attributes.get("lastModifiedTime"))
                + " " + getShortName(f);
    }

    protected String getShortName(Path f) throws IOException {
        Path nrm = normalize(f);
        int  count = nrm.getNameCount();
        /*
         * According to the javadoc:
         *
         *      The number of elements in the path, or 0 if this path only
         *      represents a root component
         */
        if (OsUtils.isUNIX()) {
            Path name = f.getFileName();
            if (name == null) {
                Path p = resolveFile(".");
                name = p.getFileName();
            }

            if (name == null) {
                if (count > 0) {
                    name = nrm.getFileName();
                }
            }

            if (name != null) {
                return name.toString();
            } else {
                return nrm.toString();
            }
        } else {    // need special handling for Windows root drives
            if (count > 0) {
                Path name = nrm.getFileName();
                return name.toString();
            } else {
                return nrm.toString().replace(File.separatorChar, '/');
            }
        }
    }

    protected Map<String, Object> resolveFileAttributes(Path file, int flags, LinkOption... options) throws IOException {
        Boolean status = IoUtils.checkFileExists(file, options);
        if (status == null) {
            return handleUnknownStatusFileAttributes(file, flags, options);
        } else if (!status) {
            throw new FileNotFoundException(file.toString());
        } else {
            return getAttributes(file, flags, options);
        }
    }

    protected void writeAttrs(Buffer buffer, Map<String, ?> attributes) throws IOException {
        SftpHelper.writeAttrs(version, buffer, attributes);
    }

    protected Map<String, Object> getAttributes(Path file, LinkOption... options) throws IOException {
        return getAttributes(file, SSH_FILEXFER_ATTR_ALL, options);
    }

    protected Map<String, Object> handleUnknownStatusFileAttributes(Path file, int flags, LinkOption... options) throws IOException {
        switch (unsupportedAttributePolicy) {
            case Ignore:
                break;
            case ThrowException:
                throw new AccessDeniedException("Cannot determine existence for attributes of " + file);
            case Warn:
                log.warn("handleUnknownStatusFileAttributes(" + file + ") cannot determine existence");
                break;
            default:
                log.warn("handleUnknownStatusFileAttributes(" + file + ") unknown policy: " + unsupportedAttributePolicy);
        }

        return getAttributes(file, flags, options);
    }

    /**
     * @param file The {@link Path} location for the required attributes
     * @param flags A mask of the original required attributes - ignored by the
     * default implementation
     * @param options The {@link LinkOption}s to use in order to access the file
     * if necessary
     * @return A {@link Map} of the retrieved attributes
     * @throws IOException If failed to access the file
     * @see #resolveMissingFileAttributes(Path, int, Map, LinkOption...)
     */
    protected Map<String, Object> getAttributes(Path file, int flags, LinkOption ... options) throws IOException {
        FileSystem           fs = file.getFileSystem();
        Collection<String>   supportedViews = fs.supportedFileAttributeViews();
        Map<String, Object>  attrs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Collection<String>   views;

        if (GenericUtils.isEmpty(supportedViews)) {
            views = Collections.emptyList();
        } else if (supportedViews.contains("unix")) {
            views = DEFAULT_UNIX_VIEW;
        } else {
            views = new ArrayList<>(supportedViews.size());
            for (String v : supportedViews) {
                views.add(v + ":*");
            }
        }

        for (String v : views) {
            Map<String, Object> ta = readFileAttributes(file, v, options);
            if (GenericUtils.size(ta) > 0) {
                attrs.putAll(ta);
            }
        }

        Map<String, Object> completions = resolveMissingFileAttributes(file, flags, attrs, options);
        if (GenericUtils.size(completions) > 0) {
            attrs.putAll(completions);
        }

        return attrs;
    }

    /**
     * Called by {@link #getAttributes(Path, int, LinkOption...)} in order
     * to complete any attributes that could not be retrieved via the supported
     * file system views. These attributes are deemed important so an extra
     * effort is made to provide a value for them
     * @param file The {@link Path} location for the required attributes
     * @param flags A mask of the original required attributes - ignored by the
     * default implementation
     * @param current The {@link Map} of attributes already retrieved - may be
     * {@code null}/empty and/or unmodifiable
     * @param options The {@link LinkOption}s to use in order to access the file
     * if necessary
     * @return A {@link Map} of the extra attributes whose values need to be
     * updated in the original map. <B>Note:</B> it is allowed to specify values
     * which <U>override</U> existing ones - the default implementation does not
     * override values that have a non-{@code null} value
     * @throws IOException If failed to access the attributes - in which case
     * an <U>error</U> is returned to the SFTP client
     * @see #FILEATTRS_RESOLVERS
     */
    protected Map<String, Object> resolveMissingFileAttributes(Path file, int flags, Map<String, Object> current, LinkOption ... options) throws IOException {
        Map<String, Object> attrs = null;
        for (Map.Entry<String, FileInfoExtractor<?>> re : FILEATTRS_RESOLVERS.entrySet()) {
            String name = re.getKey();
            Object value = GenericUtils.isEmpty(current) ? null : current.get(name);
            FileInfoExtractor<?> x = re.getValue();
            try {
                Object resolved = resolveMissingFileAttributeValue(file, name, value, x, options);
                if (Objects.equals(resolved, value)) {
                    continue;
                }

                if (attrs == null) {
                    attrs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                }

                attrs.put(name, resolved);

                if (log.isDebugEnabled()) {
                    log.debug("resolveMissingFileAttributes(" + file + ")[" + name + "]"
                            + " replace " + value + " with " + resolved);
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("resolveMissingFileAttributes(" + file + ")[" + name + "]"
                            + " failed (" + e.getClass().getSimpleName() + ")"
                            + " to resolve missing value: " + e.getMessage());
                }
            }
        }

        if (attrs == null) {
            return Collections.emptyMap();
        } else {
            return attrs;
        }
    }

    protected Object resolveMissingFileAttributeValue(Path file, String name, Object value, FileInfoExtractor<?> x, LinkOption ... options) throws IOException {
        if (value != null) {
            return value;
        } else {
            return x.infoOf(file, options);
        }
    }

    protected Map<String, Object> addMissingAttribute(Path file, Map<String, Object> current, String name, FileInfoExtractor<?> x, LinkOption ... options) throws IOException {
        Object value = GenericUtils.isEmpty(current) ? null : current.get(name);
        if (value != null) {    // already have the value
            return current;
        }

        // skip if still no value
        value = x.infoOf(file, options);
        if (value == null) {
            return current;
        }

        if (current == null) {
            current = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        }

        current.put(name, value);
        return current;
    }

    protected Map<String, Object> readFileAttributes(Path file, String view, LinkOption ... options) throws IOException {
        try {
            return Files.readAttributes(file, view, options);
        } catch (IOException e) {
            return handleReadFileAttributesException(file, view, options, e);
        }
    }

    protected Map<String, Object> handleReadFileAttributesException(Path file, String view, LinkOption[] options, IOException e) throws IOException {
        switch (unsupportedAttributePolicy) {
            case Ignore:
                break;
            case Warn:
                log.warn("handleReadFileAttributesException(" + file + ")[" + view + "] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                break;
            case ThrowException:
                throw e;
            default:
                log.warn("handleReadFileAttributesException(" + file + ")[" + view + "]"
                        + " Unknown policy (" + unsupportedAttributePolicy + ")"
                        + " for " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return Collections.emptyMap();
    }

    protected void setAttributes(Path file, Map<String, ?> attributes) throws IOException {
        Set<String> unsupported = new HashSet<>();
        for (String attribute : attributes.keySet()) {
            String view = null;
            Object value = attributes.get(attribute);
            switch (attribute) {
                case "size": {
                    long newSize = ((Number) value).longValue();
                    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        channel.truncate(newSize);
                    }
                    continue;
                }
                case "uid":
                    view = "unix";
                    break;
                case "gid":
                    view = "unix";
                    break;
                case "owner":
                    view = "posix";
                    value = toUser(file, (UserPrincipal) value);
                    break;
                case "group":
                    view = "posix";
                    value = toGroup(file, (GroupPrincipal) value);
                    break;
                case "permissions":
                    if (OsUtils.isWin32()) {
                        @SuppressWarnings("unchecked")
                        Collection<PosixFilePermission> perms = (Collection<PosixFilePermission>) value;
                        IoUtils.setPermissionsToFile(file.toFile(), perms);
                        continue;
                    }
                    view = "posix";
                    break;

                case "creationTime":
                    view = "basic";
                    break;
                case "lastModifiedTime":
                    view = "basic";
                    break;
                case "lastAccessTime":
                    view = "basic";
                    break;
                default:    // ignored
            }
            if (view != null && value != null) {
                try {
                    Files.setAttribute(file, view + ":" + attribute, value, IoUtils.getLinkOptions(false));
                } catch (UnsupportedOperationException e) {
                    unsupported.add(attribute);
                }
            }
        }
        handleUnsupportedAttributes(unsupported);
    }

    protected void handleUnsupportedAttributes(Collection<String> attributes) {
        if (!attributes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String attr : attributes) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(attr);
            }
            switch (unsupportedAttributePolicy) {
                case Ignore:
                    break;
                case Warn:
                    log.warn("Unsupported attributes: " + sb.toString());
                    break;
                case ThrowException:
                    throw new UnsupportedOperationException("Unsupported attributes: " + sb.toString());
                default:
                    log.warn("Unknown policy for attributes=" + sb.toString() + ": " + unsupportedAttributePolicy);
            }
        }
    }

    private GroupPrincipal toGroup(Path file, GroupPrincipal name) throws IOException {
        String groupName = name.toString();
        FileSystem fileSystem = file.getFileSystem();
        UserPrincipalLookupService lookupService = fileSystem.getUserPrincipalLookupService();
        try {
            return lookupService.lookupPrincipalByGroupName(groupName);
        } catch (IOException e) {
            handleUserPrincipalLookupServiceException(GroupPrincipal.class, groupName, e);
            return null;
        }
    }

    private UserPrincipal toUser(Path file, UserPrincipal name) throws IOException {
        String username = name.toString();
        FileSystem fileSystem = file.getFileSystem();
        UserPrincipalLookupService lookupService = fileSystem.getUserPrincipalLookupService();
        try {
            return lookupService.lookupPrincipalByName(username);
        } catch (IOException e) {
            handleUserPrincipalLookupServiceException(UserPrincipal.class, username, e);
            return null;
        }
    }

    protected void handleUserPrincipalLookupServiceException(Class<? extends Principal> principalType, String name, IOException e) throws IOException {
        /* According to Javadoc:
         *
         *      "Where an implementation does not support any notion of group
         *      or user then this method always throws UserPrincipalNotFoundException."
         */
        switch (unsupportedAttributePolicy) {
            case Ignore:
                break;
            case Warn:
                log.warn("handleUserPrincipalLookupServiceException(" + principalType.getSimpleName() + "[" + name + "])"
                        + " failed (" + e.getClass().getSimpleName() + "): " + e.getMessage());
                break;
            case ThrowException:
                throw e;
            default:
                log.warn("Unknown policy for principal=" + principalType.getSimpleName() + "[" + name + "]: " + unsupportedAttributePolicy);
        }
    }

    protected Map<String, Object> readAttrs(Buffer buffer) throws IOException {
        return SftpHelper.readAttrs(version, buffer);
    }

    /**
     * @param <H>    The generic handle type
     * @param handle The original handle id
     * @param h      The resolved {@link Handle} instance
     * @param type   The expected handle type
     * @return The cast type
     * @throws FileNotFoundException  If the handle instance is {@code null}
     * @throws InvalidHandleException If the handle instance is not of the expected type
     */
    protected <H extends Handle> H validateHandle(String handle, Handle h, Class<H> type) throws IOException {
        if (h == null) {
            throw new FileNotFoundException("No such current handle: " + handle);
        }

        Class<?> t = h.getClass();
        if (!type.isAssignableFrom(t)) {
            throw new InvalidHandleException(handle, h, type);
        }

        return type.cast(h);
    }

    protected void sendStatus(Buffer buffer, int id, Exception e) throws IOException {
        int substatus = SftpHelper.resolveSubstatus(e);
        sendStatus(buffer, id, substatus, e.toString());
    }

    protected void sendStatus(Buffer buffer, int id, int substatus, String msg) throws IOException {
        sendStatus(buffer, id, substatus, (msg != null) ? msg : "", "");
    }

    protected void sendStatus(Buffer buffer, int id, int substatus, String msg, String lang) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("Send SSH_FXP_STATUS (substatus={}, lang={}, msg={})",
                    substatus, lang, msg);
        }

        buffer.putByte((byte) SSH_FXP_STATUS);
        buffer.putInt(id);
        buffer.putInt(substatus);
        buffer.putString(msg);
        buffer.putString(lang);
        send(buffer);
    }

    protected void send(Buffer buffer) throws IOException {
        int len = buffer.available();
        BufferUtils.writeInt(out, len, workBuf, 0, workBuf.length);
        out.write(buffer.array(), buffer.rpos(), len);
        out.flush();
    }

    @Override
    public void destroy() {
        if (!closed) {
            if (log.isDebugEnabled()) {
                log.debug("destroy() - mark as closed");
            }

            closed = true;

            // if thread has not completed, cancel it
            if ((pendingFuture != null) && (!pendingFuture.isDone())) {
                boolean result = pendingFuture.cancel(true);
                // TODO consider waiting some reasonable (?) amount of time for cancellation
                if (log.isDebugEnabled()) {
                    log.debug("destroy() - cancel pending future=" + result);
                }
            }

            pendingFuture = null;

            if ((executors != null) && (!executors.isShutdown()) && shutdownExecutor) {
                Collection<Runnable> runners = executors.shutdownNow();
                if (log.isDebugEnabled()) {
                    log.debug("destroy() - shutdown executor service - runners count=" + runners.size());
                }
            }

            executors = null;

            try {
                fileSystem.close();
            } catch (UnsupportedOperationException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Closing the file system is not supported");
                }
            } catch (IOException e) {
                log.debug("Error closing FileSystem", e);
            }
        }
    }

    protected Path resolveNormalizedLocation(String remotePath) throws IOException, InvalidPathException {
        return normalize(resolveFile(remotePath));
    }

    protected Path normalize(Path f) {
        if (f == null) {
            return null;
        }

        Path abs = f.isAbsolute() ? f : f.toAbsolutePath();
        return abs.normalize();
    }

    /**
     * @param remotePath The remote path - separated by '/'
     * @return The local {@link Path}
     * @throws IOException If failed to resolve the local path
     * @throws InvalidPathException If bad local path specification
     */
    protected Path resolveFile(String remotePath) throws IOException, InvalidPathException {
        String path = SelectorUtils.translateToLocalFileSystemPath(remotePath, '/', defaultDir.getFileSystem());
        Path p = defaultDir.resolve(path);
        if (log.isTraceEnabled()) {
            log.trace("resolveFile({}) {}", remotePath, p);
        }
        return p;
    }
}
