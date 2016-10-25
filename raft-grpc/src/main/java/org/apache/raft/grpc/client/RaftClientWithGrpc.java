/**
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
package org.apache.raft.grpc.client;

import io.grpc.stub.StreamObserver;
import org.apache.raft.client.ClientProtoUtils;
import org.apache.raft.client.RaftClientRequestSender;
import org.apache.raft.proto.RaftProtos.RaftClientReplyProto;
import org.apache.raft.proto.RaftProtos.RaftClientRequestProto;
import org.apache.raft.proto.RaftProtos.SetConfigurationRequestProto;
import org.apache.raft.protocol.RaftClientReply;
import org.apache.raft.protocol.RaftClientRequest;
import org.apache.raft.protocol.RaftPeer;
import org.apache.raft.protocol.SetConfigurationRequest;
import org.apache.raft.util.PeerProxyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.apache.raft.client.ClientProtoUtils.toRaftClientReply;
import static org.apache.raft.client.ClientProtoUtils.toRaftClientRequestProto;
import static org.apache.raft.client.ClientProtoUtils.toSetConfigurationRequestProto;

public class RaftClientWithGrpc implements RaftClientRequestSender, Closeable {
  public static final Logger LOG = LoggerFactory.getLogger(RaftClientWithGrpc.class);

  private final PeerProxyMap<RaftClientProtocolClient> proxies
      = new PeerProxyMap<RaftClientProtocolClient>() {
    @Override
    public RaftClientProtocolClient createProxy(RaftPeer peer)
        throws IOException {
      return new RaftClientProtocolClient(peer);
    }
  };

  public RaftClientWithGrpc(Collection<RaftPeer> peers) {
    addServers(peers);
  }

  @Override
  public RaftClientReply sendRequest(RaftClientRequest request)
      throws IOException {
    final String serverId = request.getReplierId();
    final RaftClientProtocolClient proxy = proxies.getProxy(serverId);
    if (request instanceof SetConfigurationRequest) {
      SetConfigurationRequestProto setConf =
          toSetConfigurationRequestProto((SetConfigurationRequest) request);
      return toRaftClientReply(proxy.setConfiguration(setConf));
    } else {
      RaftClientRequestProto requestProto = toRaftClientRequestProto(request);
      CompletableFuture<RaftClientReplyProto> replyFuture =
          new CompletableFuture<>();
      final StreamObserver<RaftClientRequestProto> requestObserver =
          proxy.append(new StreamObserver<RaftClientReplyProto>() {
            @Override
            public void onNext(RaftClientReplyProto value) {
              replyFuture.complete(value);
            }

            @Override
            public void onError(Throwable t) {
              // This implementation is used as RaftClientRequestSender. Retry
              // logic on NotLeaderException/IOException is in RaftClient.
              replyFuture.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
              if (!replyFuture.isDone()) {
                replyFuture.completeExceptionally(
                    new IOException("No reply for request " + request));
              }
            }
          });
      requestObserver.onNext(requestProto);
      requestObserver.onCompleted();

      // TODO: timeout support
      try {
        return ClientProtoUtils.toRaftClientReply(replyFuture.get());
      } catch (InterruptedException e) {
        throw new InterruptedIOException(
            "Interrupted while waiting for response of request " + request);
      } catch (ExecutionException e) {
        if (e.getCause() != null) {
          throw e.getCause() instanceof IOException ?
              (IOException) e.getCause() : new IOException(e.getCause());
        } else {
          throw new IOException(e);
        }
      }
    }
  }

  @Override
  public void addServers(Iterable<RaftPeer> servers) {
    proxies.addPeers(servers);
  }

  @Override
  public void close() throws IOException {
    proxies.getPeerIds().forEach(id -> {
      try {
        proxies.getProxy(id).shutdown();
      } catch (IOException e) {
        LOG.info("Got IOException when closing proxy with id " + id, e);
      }
    });
  }
}
