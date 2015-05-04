/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.cluster.net;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.zip.Checksum;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.xxhash.XXHashFactory;

import org.lealone.cluster.config.DatabaseDescriptor;
import org.lealone.cluster.gms.Gossiper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IncomingTcpConnection extends Thread {
    private static final Logger logger = LoggerFactory.getLogger(IncomingTcpConnection.class);

    private final int version;
    private final boolean compressed;
    private final Socket socket;
    private InetAddress from;

    public IncomingTcpConnection(int version, boolean compressed, Socket socket) {
        assert socket != null;
        this.version = version;
        this.compressed = compressed;
        this.socket = socket;
        if (DatabaseDescriptor.getInternodeRecvBufferSize() != null) {
            try {
                this.socket.setReceiveBufferSize(DatabaseDescriptor.getInternodeRecvBufferSize());
            } catch (SocketException se) {
                logger.warn("Failed to set receive buffer size on internode socket.", se);
            }
        }
    }

    /**
     * A new connection will either stream or message for its entire lifetime: because streaming
     * bypasses the InputStream implementations to use sendFile, we cannot begin buffering until
     * we've determined the type of the connection.
     */
    @Override
    public void run() {
        try {
            receiveMessages();
        } catch (EOFException e) {
            if (logger.isTraceEnabled())
                logger.trace("eof reading from socket; closing", e);
            // connection will be reset so no need to throw an exception.
        } catch (IOException e) {
            if (logger.isDebugEnabled())
                logger.debug("IOException reading from socket; closing", e);
        } finally {
            close();
        }
    }

    private void receiveMessages() throws IOException {
        // handshake (true) endpoint versions
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(MessagingService.current_version);
        out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int maxVersion = in.readInt();

        from = CompactEndpointSerializationHelper.deserialize(in);
        // record the (true) version of the endpoint
        MessagingService.instance().setVersion(from, maxVersion);
        if (logger.isDebugEnabled())
            logger.debug("Set version for {} to {} (will use {})", from, maxVersion, MessagingService.instance()
                    .getVersion(from));

        if (compressed) {
            if (logger.isDebugEnabled())
                logger.debug("Upgrading incoming connection to be compressed");
            LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();
            Checksum checksum = XXHashFactory.fastestInstance().newStreamingHash32(OutboundTcpConnection.LZ4_HASH_SEED)
                    .asChecksum();
            in = new DataInputStream(new LZ4BlockInputStream(socket.getInputStream(), decompressor, checksum));
        } else {
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 4096));
        }

        if (version > MessagingService.current_version) {
            // save the endpoint so gossip will reconnect to it
            Gossiper.instance.addSavedEndpoint(from);
            logger.info("Received messages from newer protocol version {}. Ignoring", version);
            return;
        }
        // outbound side will reconnect if necessary to upgrade version

        while (true) {
            MessagingService.validateMagic(in.readInt());
            receiveMessage(in, version);
        }
    }

    private InetAddress receiveMessage(DataInputStream input, int version) throws IOException {
        int id = input.readInt();

        long timestamp = System.currentTimeMillis();
        // make sure to readInt, even if cross_node_to is not enabled
        int partial = input.readInt();
        if (DatabaseDescriptor.hasCrossNodeTimeout())
            timestamp = (timestamp & 0xFFFFFFFF00000000L) | (((partial & 0xFFFFFFFFL) << 2) >> 2);

        MessageIn<?> message = MessageIn.read(input, version, id);
        if (message == null) {
            // callback expired; nothing to do
            return null;
        }
        if (version <= MessagingService.current_version) {
            MessagingService.instance().receive(message, id, timestamp);
        } else {
            if (logger.isDebugEnabled())
                logger.debug("Received connection from newer protocol version {}. Ignoring message", version);
        }
        return message.from;
    }

    private void close() {
        try {
            socket.close();
        } catch (IOException e) {
            if (logger.isDebugEnabled())
                logger.debug("Error closing socket", e);
        }
    }
}
