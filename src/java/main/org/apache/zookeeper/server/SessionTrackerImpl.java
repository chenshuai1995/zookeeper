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

package org.apache.zookeeper.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;

/**
 * This is a full featured SessionTracker. It tracks session in grouped by tick
 * interval. It always rounds up the tick interval to provide a sort of grace
 * period. Sessions are thus expired in batches made up of sessions that expire
 * in a given interval.
 */
public class SessionTrackerImpl extends Thread implements SessionTracker {
    private static final Logger LOG = LoggerFactory.getLogger(SessionTrackerImpl.class);

    HashMap<Long, SessionImpl> sessionsById = new HashMap<Long, SessionImpl>();

    HashMap<Long, SessionSet> sessionSets = new HashMap<Long, SessionSet>();

    ConcurrentHashMap<Long, Integer> sessionsWithTimeout;
    long nextSessionId = 0;
    long nextExpirationTime;

    int expirationInterval;

    public static class SessionImpl implements Session {
        SessionImpl(long sessionId, int timeout, long expireTime) {
            this.sessionId = sessionId;
            this.timeout = timeout;
            this.tickTime = expireTime; // 下一次这个session可能会过期的时间
            isClosing = false;

            // 12:03，sessionTimeout是120s，2分钟
            // timeout = 120s
            // tickTime = 12:03 + 120s = 12:05

        }

        final long sessionId;
        final int timeout;
        long tickTime;
        boolean isClosing;

        Object owner;

        public long getSessionId() { return sessionId; }
        public int getTimeout() { return timeout; }
        public boolean isClosing() { return isClosing; }
    }

    public static long initializeNextSession(long id) {
        long nextSid = 0;
        nextSid = (System.currentTimeMillis() << 24) >> 8;

        // 当前时间戳，左移了24位，又右移了8位
        // 0000 0000 0101 0101 0101 0101 0101 0101 0101 0101 0101 0101 0000 0000 0000 0000

        // id的值，3
        // 0000 0011 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000 0000

        // 最终的初始化的sessionId
        // 0000 0011 0101 0101 0101 0101 0101 0101 0101 0101 0101 0101 0000 0000 0000 0000
        // 二进制的位运算推算出来的一个初始化的值
        // 这个值一定是唯一的

        // 万一几台不同的机器，leader和follower取用了同一个时间戳呢？
        // 所以说一定要在时间戳里拼接进去这台机器的myid

        nextSid =  nextSid | (id <<56);
        return nextSid;
    }

    static class SessionSet {
        HashSet<SessionImpl> sessions = new HashSet<SessionImpl>();
    }

    SessionExpirer expirer;

    private long roundToInterval(long time) {
        // We give a one interval grace period
        // expirationInterval，他是用来后续在后台线程里每隔这么多时间检查session是否过期的
        // 默认是跟tickTime是一样大的，默认2000ms，2s
        // 默认是，(12:05 / 2s + 1) * 2s
        // (2 / 2 + 1) * 2 = 4
        // (3 / 2 + 1) * 2 = 4
        // (7 / 2 + 1) * 2 = 8
        // (6 / 2 + 1) * 2 = 8

        // 让你的expireTime，过期时间，应该是expirationInterval的倍数

        return (time / expirationInterval + 1) * expirationInterval;
    }

    public SessionTrackerImpl(SessionExpirer expirer,
            ConcurrentHashMap<Long, Integer> sessionsWithTimeout, int tickTime,
            long sid)
    {
        super("SessionTracker");
        this.expirer = expirer;
        this.expirationInterval = tickTime;
        this.sessionsWithTimeout = sessionsWithTimeout;
        nextExpirationTime = roundToInterval(System.currentTimeMillis());

        // 当前时间：12:01 -> 12:02，下一次过期时间就是12:02
        // 12:02 -> 会话1，会话2，会话3
        // 12:01:40，会话1和会话2都及时的发送了ping请求，进行了会话的激活
        // 12:04 -> 会话1，会话2；12:02 -> 会话3
        // 下一次过期时间是12:02

        this.nextSessionId = initializeNextSession(sid);
        for (Entry<Long, Integer> e : sessionsWithTimeout.entrySet()) {
            addSession(e.getKey(), e.getValue());
        }
    }

    volatile boolean running = true;

    volatile long currentTime;

    synchronized public void dumpSessions(PrintWriter pwriter) {
        pwriter.print("Session Sets (");
        pwriter.print(sessionSets.size());
        pwriter.println("):");
        ArrayList<Long> keys = new ArrayList<Long>(sessionSets.keySet());
        Collections.sort(keys);
        for (long time : keys) {
            pwriter.print(sessionSets.get(time).sessions.size());
            pwriter.print(" expire at ");
            pwriter.print(new Date(time));
            pwriter.println(":");
            for (SessionImpl s : sessionSets.get(time).sessions) {
                pwriter.print("\t0x");
                pwriter.println(Long.toHexString(s.sessionId));
            }
        }
    }

    @Override
    synchronized public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pwriter = new PrintWriter(sw);
        dumpSessions(pwriter);
        pwriter.flush();
        pwriter.close();
        return sw.toString();
    }

    @Override
    synchronized public void run() {
        try {
            while (running) {
                currentTime = System.currentTimeMillis();
                if (nextExpirationTime > currentTime) {
                    this.wait(nextExpirationTime - currentTime);
                    continue;
                }
                SessionSet set;
                set = sessionSets.remove(nextExpirationTime); // 直接把12:02这个分桶
                if (set != null) {
                    for (SessionImpl s : set.sessions) { // 会话3
                        setSessionClosing(s.sessionId);
                        expirer.expire(s);
                    }
                }

                // 更新了下次过期的分桶对应的时间，12:02 + 120s = 12:04分桶
                // 在这个期间，你的会话1和会话2正常的发送请求过来，或者是发送ping过来，都会转移分桶
                // 12:06 -> 会话1，会话2
                // 12:04过期这个分桶，此时里面是没有会话的
                nextExpirationTime += expirationInterval;
            }
        } catch (InterruptedException e) {
            LOG.error("Unexpected interruption", e);
        }
        LOG.info("SessionTrackerImpl exited loop!");
    }

    synchronized public boolean touchSession(long sessionId, int timeout) {
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG,
                                     ZooTrace.CLIENT_PING_TRACE_MASK,
                                     "SessionTrackerImpl --- Touch session: 0x"
                    + Long.toHexString(sessionId) + " with timeout " + timeout);
        }
        SessionImpl s = sessionsById.get(sessionId);
        // Return false, if the session doesn't exists or marked as closing
        if (s == null || s.isClosing()) {
            return false;
        }

        // expireTime就是这个session他下一次的过期时间
        // 当前时间：12:03，timeout：120s，12:05
        long expireTime = roundToInterval(System.currentTimeMillis() + timeout);
        if (s.tickTime >= expireTime) {
            // Nothing needs to be done
            return true;
        }
        SessionSet set = sessionSets.get(s.tickTime);
        if (set != null) {
            set.sessions.remove(s);
        }
        s.tickTime = expireTime;
        // 不同的session，他们原始的now + timeout，都是不一样的
        // 但是经过处理之后，其实就可能很多session的expireTime是一样的

        set = sessionSets.get(s.tickTime);
        if (set == null) {
            set = new SessionSet();
            sessionSets.put(expireTime, set);
        }
        set.sessions.add(s);

        // 统一都是expirationInterval的倍数（=tickTime，2s，在zk的配置文件里，zoo.cfg里配置的）
        // 先检查我的最小倍数（512倍）的那个分桶里的session有没有过期
        // 然后在检查下一个倍数（648倍）的那个分桶里的session有没有过期

        {
            // expireTime(12:05) : SessionSet(<会话1, 会话2, 会话3>),
            // expireTIme(12:10) : SessionSet(<会话4, 会话5, 会话6>)
        }

        return true;
    }

    synchronized public void setSessionClosing(long sessionId) {
        if (LOG.isTraceEnabled()) {
            LOG.info("Session closing: 0x" + Long.toHexString(sessionId));
        }
        SessionImpl s = sessionsById.get(sessionId);
        if (s == null) {
            return;
        }
        s.isClosing = true;
    }

    synchronized public void removeSession(long sessionId) {
        SessionImpl s = sessionsById.remove(sessionId);
        sessionsWithTimeout.remove(sessionId);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                    "SessionTrackerImpl --- Removing session 0x"
                    + Long.toHexString(sessionId));
        }
        if (s != null) {
            SessionSet set = sessionSets.get(s.tickTime);
            // Session expiration has been removing the sessions   
            if(set != null){
                set.sessions.remove(s);
            }
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");

        running = false;
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                                     "Shutdown SessionTrackerImpl!");
        }
    }


    synchronized public long createSession(int sessionTimeout) {
        // 步骤1：生成一个唯一的sessionId
        // 步骤2：在几个内存数据结构中放入这个session
        // 步骤3：对session计算他的过期时间以及进行特殊的处理

        addSession(nextSessionId, sessionTimeout); // 对这个初始化的sessionId
        // 每次用他来创建一个session之后，都会进行++
        // 每台机器上的初始化的sessionId一定是不同的，不可能是一样的
        return nextSessionId++;
    }

    synchronized public void addSession(long id, int sessionTimeout) {
        sessionsWithTimeout.put(id, sessionTimeout); // 很明显，这个数据结构是用来存放每个session的过期时间的
        if (sessionsById.get(id) == null) {
            SessionImpl s = new SessionImpl(id, sessionTimeout, 0);
            sessionsById.put(id, s);
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                        "SessionTrackerImpl --- Adding session 0x"
                        + Long.toHexString(id) + " " + sessionTimeout);
            }
        } else {
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                        "SessionTrackerImpl --- Existing session 0x"
                        + Long.toHexString(id) + " " + sessionTimeout);
            }
        }
        touchSession(id, sessionTimeout);
    }

    synchronized public void checkSession(long sessionId, Object owner) throws KeeperException.SessionExpiredException, KeeperException.SessionMovedException {
        SessionImpl session = sessionsById.get(sessionId);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        if (session.owner == null) {
            session.owner = owner;
        } else if (session.owner != owner) {
            throw new KeeperException.SessionMovedException();
        }
    }

    synchronized public void setOwner(long id, Object owner) throws SessionExpiredException {
        SessionImpl session = sessionsById.get(id);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        session.owner = owner;
    }
}
