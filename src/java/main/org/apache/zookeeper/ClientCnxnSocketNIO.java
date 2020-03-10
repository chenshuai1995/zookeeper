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

package org.apache.zookeeper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.apache.zookeeper.ClientCnxn.EndOfStreamException;
import org.apache.zookeeper.ClientCnxn.Packet;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientCnxnSocketNIO extends ClientCnxnSocket {
    private static final Logger LOG = LoggerFactory
            .getLogger(ClientCnxnSocketNIO.class);

    private final Selector selector = Selector.open();

    private SelectionKey sockKey; // 代表这个连接的，就是这个SelectionKey

    ClientCnxnSocketNIO() throws IOException {
        super();
    }

    @Override
    boolean isConnected() {
        return sockKey != null;
    }
    
    /**
     * @return true if a packet was received
     * @throws InterruptedException
     * @throws IOException
     */
    void doIO(List<Packet> pendingQueue, LinkedList<Packet> outgoingQueue, ClientCnxn cnxn)
      throws InterruptedException, IOException {
        SocketChannel sock = (SocketChannel) sockKey.channel();
        if (sock == null) {
            throw new IOException("Socket is null!");
        }
        if (sockKey.isReadable()) { // 就是说有数据可以读取，一般来说你发送出去的请求的响应
            // 或者是zk服务端反向推送给你的事件通知
            int rc = sock.read(incomingBuffer); // 一开始这个incomingBuffer都是读取头，长度
            if (rc < 0) {
                throw new EndOfStreamException(
                        "Unable to read additional data from server sessionid 0x"
                                + Long.toHexString(sessionId)
                                + ", likely server has closed socket");
            }
            if (!incomingBuffer.hasRemaining()) {
                incomingBuffer.flip();
                if (incomingBuffer == lenBuffer) {
                    recvCount++;
                    readLength();
                } else if (!initialized) {
                    readConnectResult(); // 如果是CreateSession的响应
                    enableRead();
                    if (findSendablePacket(outgoingQueue,
                            cnxn.sendThread.clientTunneledAuthenticationInProgress()) != null) {
                        // Since SASL authentication has completed (if client is configured to do so),
                        // outgoing packets waiting in the outgoingQueue can now be sent.
                        enableWrite();
                    }
                    lenBuffer.clear();
                    incomingBuffer = lenBuffer;
                    updateLastHeard();
                    initialized = true;
                } else {
                    sendThread.readResponse(incomingBuffer);
                    lenBuffer.clear();
                    incomingBuffer = lenBuffer;
                    updateLastHeard();
                }
            }
        }
        // 如果当前的网络连接是可以写的s
        if (sockKey.isWritable()) {
            synchronized(outgoingQueue) {
                // 就是从这个队列里获取一个Packet出来
                // 这里并没有从队列头部删除掉
                Packet p = findSendablePacket(outgoingQueue,
                        cnxn.sendThread.clientTunneledAuthenticationInProgress());

                if (p != null) {
                    updateLastSend();
                    // If we already started writing p, p.bb will already exist
                    if (p.bb == null) {
                        if ((p.requestHeader != null) &&
                                (p.requestHeader.getType() != OpCode.ping) &&
                                (p.requestHeader.getType() != OpCode.auth)) {
                            p.requestHeader.setXid(cnxn.getXid());
                        }
                        p.createBB();
                    }
                    // 把这个packet的数据采用ByteBuffer的模式通过socket写出去

                    sock.write(p.bb); // 直接把请求（统一进行封装，类型，数据，等等）
                    // 如果说zk服务端宕机了，此时在这里一定会有异常的

                    // 这个代码是处理拆包的，你发送数据的时候，就怕一次性没有发送完全
                    if (!p.bb.hasRemaining()) {
                        sentCount++;
                        // 把他从队列头部删除掉
                        outgoingQueue.removeFirstOccurrence(p);
                        if (p.requestHeader != null
                                && p.requestHeader.getType() != OpCode.ping
                                && p.requestHeader.getType() != OpCode.auth) {
                            synchronized (pendingQueue) {
                                pendingQueue.add(p); // 因为此时需要等待这个请求的响应
                            }
                        }
                    }
                }
                if (outgoingQueue.isEmpty()) {
                    // No more packets to send: turn off write interest flag.
                    // Will be turned on later by a later call to enableWrite(),
                    // from within ZooKeeperSaslClient (if client is configured
                    // to attempt SASL authentication), or in either doIO() or
                    // in doTransport() if not.
                    disableWrite();
                    // 如果发现你的outgoingQueue是空的，此时就取消对socket的OP_WRITE事件的关注
                    // 避免说你频繁的发现socket可以执行OP_WRITE，然后代码进到这里，但是是没有数据可以写的
                } else {
                    // Just in case
                    enableWrite();
                }
            }
        }
    }

    private Packet findSendablePacket(LinkedList<Packet> outgoingQueue,
                                      boolean clientTunneledAuthenticationInProgress) {
        synchronized (outgoingQueue) {
            if (outgoingQueue.isEmpty()) {
                return null;
            }
            if (outgoingQueue.getFirst().bb != null // If we've already starting sending the first packet, we better finish
                || !clientTunneledAuthenticationInProgress) {
                return outgoingQueue.getFirst();
            }

            // Since client's authentication with server is in progress,
            // send only the null-header packet queued by primeConnection().
            // This packet must be sent so that the SASL authentication process
            // can proceed, but all other packets should wait until
            // SASL authentication completes.
            ListIterator<Packet> iter = outgoingQueue.listIterator();
            while (iter.hasNext()) {
                Packet p = iter.next();
                if (p.requestHeader == null) {
                    // We've found the priming-packet. Move it to the beginning of the queue.
                    iter.remove();
                    outgoingQueue.add(0, p);
                    return p;
                } else {
                    // Non-priming packet: defer it until later, leaving it in the queue
                    // until authentication completes.
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("deferring non-priming packet: " + p +
                                "until SASL authentication completes.");
                    }
                }
            }
            // no sendable packet found.
            return null;
        }
    }

    @Override
    void cleanup() {
        // 这个过程是很经典的，非常值得我们来进行学习，对我们编写一些生产级的代码
        // 一个是对代码进行良好的封装，定义生产级的协议，状态码，输入输出的参数的标识
        // 对大量的异常进行良好的捕获和处理
        // 此时就直接认为这个网络连接出现了问题了
        if (sockKey != null) {
            SocketChannel sock = (SocketChannel) sockKey.channel();
            sockKey.cancel(); // 客户端主动断开网络连接的一个方式
            try {
                sock.socket().shutdownInput(); // 关闭
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring exception during shutdown input", e);
                }
            }
            try {
                sock.socket().shutdownOutput();
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring exception during shutdown output",
                            e);
                }
            }
            try {
                sock.socket().close();
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring exception during socket close", e);
                }
            }
            try {
                sock.close();
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring exception during channel close", e);
                }
            }
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("SendThread interrupted during sleep, ignoring");
            }
        }
        sockKey = null;
    }
 
    @Override
    void close() {
        try {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Doing client selector close");
            }
            selector.close();
            if (LOG.isTraceEnabled()) {
                LOG.trace("Closed client selector");
            }
        } catch (IOException e) {
            LOG.warn("Ignoring exception during selector close", e);
        }
    }
    
    /**
     * create a socket channel.
     * @return the created socket channel
     * @throws IOException
     */
    SocketChannel createSock() throws IOException {
        // 生产级的网络通信代码，也不过如此
        // 即使你用netty，主要是做了大量的封装，提供了更多的功能
        // 对一些潜在的网络问题，做了很多的处理

        // 无论是kafka、还是zookeeper，世界顶级知名度的开源分布式中间件系统
        // 网络通信的代码其实很简单，没大家想的那么的困难
        // 分布式中间件系统，分布式架构设计，peers、master-slave
        // 面向对象 + 内存（各种数据结构，各种集合类） + 并发编程 + 代码逻辑 = 中间件系统里的核心代码
        // 网络通信（集群间通信、客户端与服务端的通信），磁盘IO（os cache，flush）
        // 就是不同的分布式系统，他自己是有不同的架构、机制在里面的，用来解决不同场景的问题

        SocketChannel sock;
        sock = SocketChannel.open();
        sock.configureBlocking(false);
        sock.socket().setSoLinger(false, -1);
        sock.socket().setTcpNoDelay(true);
        return sock;
    }

    /**
     * register with the selection and connect
     * @param sock the {@link SocketChannel} 
     * @param addr the address of remote host
     * @throws IOException
     */
    void registerAndConnect(SocketChannel sock, InetSocketAddress addr) 
    throws IOException {
        sockKey = sock.register(selector, SelectionKey.OP_CONNECT);
        boolean immediateConnect = sock.connect(addr); // 连接发起之后，是否在瞬间完成了连接
        // 我们大致就理解为在这里就可以完成连接这样子
        if (immediateConnect) {
            sendThread.primeConnection();
        }
    }
    
    @Override
    void connect(InetSocketAddress addr) throws IOException {
        SocketChannel sock = createSock();
        try {
           registerAndConnect(sock, addr);
        } catch (IOException e) {
            LOG.error("Unable to open socket to " + addr);
            sock.close();
            throw e;
        }
        initialized = false;

        /*
         * Reset incomingBuffer
         */
        lenBuffer.clear();
        incomingBuffer = lenBuffer;
    }

    /**
     * Returns the address to which the socket is connected.
     * 
     * @return ip address of the remote side of the connection or null if not
     *         connected
     */
    @Override
    SocketAddress getRemoteSocketAddress() {
        // a lot could go wrong here, so rather than put in a bunch of code
        // to check for nulls all down the chain let's do it the simple
        // yet bulletproof way
        try {
            return ((SocketChannel) sockKey.channel()).socket()
                    .getRemoteSocketAddress();
        } catch (NullPointerException e) {
            return null;
        }
    }

    /**
     * Returns the local address to which the socket is bound.
     * 
     * @return ip address of the remote side of the connection or null if not
     *         connected
     */
    @Override
    SocketAddress getLocalSocketAddress() {
        // a lot could go wrong here, so rather than put in a bunch of code
        // to check for nulls all down the chain let's do it the simple
        // yet bulletproof way
        try {
            return ((SocketChannel) sockKey.channel()).socket()
                    .getLocalSocketAddress();
        } catch (NullPointerException e) {
            return null;
        }
    }

    @Override
    synchronized void wakeupCnxn() {
        selector.wakeup(); // 避免你的Selector在阻塞状态中
    }
    
    @Override
    void doTransport(int waitTimeOut, List<Packet> pendingQueue, LinkedList<Packet> outgoingQueue,
                     ClientCnxn cnxn)
            throws IOException, InterruptedException {
        // 之前基于NIO的连接建立完毕之后，他是关注的是OP_READ和OP_WRITE
        // 当前这个连接如果有zk服务端的响应发送回来，此时会触发OP_READ，如果是可以写数据出去，会触发OP_WRITE
        selector.select(waitTimeOut);
        Set<SelectionKey> selected;
        synchronized (this) {
            selected = selector.selectedKeys();
        }
        // Everything below and until we get back to the select is
        // non blocking, so time is effectively a constant. That is
        // Why we just have to do this once, here
        updateNow();
        for (SelectionKey k : selected) {
            SocketChannel sc = ((SocketChannel) k.channel());
            if ((k.readyOps() & SelectionKey.OP_CONNECT) != 0) {
                if (sc.finishConnect()) {
                    updateLastSendAndHeard();
                    sendThread.primeConnection();
                }
            } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                // 如果有读写事件可以执行
                // 此时就是在这里去执行对应的网络IO操作
                doIO(pendingQueue, outgoingQueue, cnxn);
            }
        }
        if (sendThread.getZkState().isConnected()) {
            synchronized(outgoingQueue) {
                if (findSendablePacket(outgoingQueue,
                        cnxn.sendThread.clientTunneledAuthenticationInProgress()) != null) {
                    enableWrite();
                }
            }
        }
        selected.clear();
    }

    //TODO should this be synchronized?
    @Override
    void testableCloseSocket() throws IOException {
        LOG.info("testableCloseSocket() called");
        ((SocketChannel) sockKey.channel()).socket().close();
    }

    @Override
    synchronized void enableWrite() {
        int i = sockKey.interestOps();
        if ((i & SelectionKey.OP_WRITE) == 0) {
            sockKey.interestOps(i | SelectionKey.OP_WRITE);
        }
    }

    @Override
    public synchronized void disableWrite() {
        int i = sockKey.interestOps();
        if ((i & SelectionKey.OP_WRITE) != 0) {
            sockKey.interestOps(i & (~SelectionKey.OP_WRITE));
        }
    }

    synchronized private void enableRead() {
        int i = sockKey.interestOps();
        if ((i & SelectionKey.OP_READ) == 0) {
            sockKey.interestOps(i | SelectionKey.OP_READ);
        }
    }

    @Override
    synchronized void enableReadWriteOnly() {
        sockKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        // 后续对这个连接仅仅关注度读写请求
    }

    Selector getSelector() {
        return selector;
    }

    @Override
    void sendPacket(Packet p) throws IOException {
        SocketChannel sock = (SocketChannel) sockKey.channel();
        if (sock == null) {
            throw new IOException("Socket is null!");
        }
        p.createBB();
        ByteBuffer pbb = p.bb;
        sock.write(pbb);
    }


}
