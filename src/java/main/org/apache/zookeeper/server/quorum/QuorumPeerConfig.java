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

package org.apache.zookeeper.server.quorum;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumHierarchical;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;

/**
 * 对于这个类而言，他就是代表了zookeeper的配置
 * 通过他把zoo.cfg里的所有配置信息，都加载到内存里来，放在这个类的对象中
 * 后续在zk运行的过程中，所有对配置的操作，都交给这个类来进行处理
 */
public class QuorumPeerConfig {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerConfig.class);

    protected InetSocketAddress clientPortAddress;
    protected String dataDir;
    protected String dataLogDir;
    protected int tickTime = ZooKeeperServer.DEFAULT_TICK_TIME;
    protected int maxClientCnxns = 60;
    /** defaults to -1 if not set explicitly */
    protected int minSessionTimeout = -1;
    /** defaults to -1 if not set explicitly */
    protected int maxSessionTimeout = -1;

    protected int initLimit;
    protected int syncLimit;
    protected int electionAlg = 3;
    protected int electionPort = 2182;
    protected final HashMap<Long,QuorumServer> servers =
        new HashMap<Long, QuorumServer>();
    protected final HashMap<Long,QuorumServer> observers =
        new HashMap<Long, QuorumServer>();

    protected long serverId;
    protected HashMap<Long, Long> serverWeight = new HashMap<Long, Long>();
    protected HashMap<Long, Long> serverGroup = new HashMap<Long, Long>();
    protected int numGroups = 0;
    protected QuorumVerifier quorumVerifier;
    protected int snapRetainCount = 3;
    protected int purgeInterval = 0;

    protected LearnerType peerType = LearnerType.PARTICIPANT;
    
    /**
     * Minimum snapshot retain count.
     * @see org.apache.zookeeper.server.PurgeTxnLog#purge(File, File, int)
     */
    private final int MIN_SNAP_RETAIN_COUNT = 3;

    @SuppressWarnings("serial")
    public static class ConfigException extends Exception {
        public ConfigException(String msg) {
            super(msg);
        }
        public ConfigException(String msg, Exception e) {
            super(msg, e);
        }
    }

    /**
     * Parse a ZooKeeper configuration file
     * @param path the patch of the configuration file
     * @throws ConfigException error processing configuration
     */
    public void parse(String path) throws ConfigException {
        // 基于配置文件的path路径，封装成了File对象
        File configFile = new File(path);

        LOG.info("Reading configuration from: " + configFile);

        // 异常的体系大致分为两类
        // 第一类异常：代码写好之后，在代码编译的时候，就可以提前预测到可能发生异常的地方
        // 第二类异常：就是在写代码的时候并不确定一定会发生异常的，但是在运行的过程中可能会发生异常

        // 所谓的异常处理的体系
        // 第一点，其实就是在你的代码运行过程中，你必须去主动去发现各种可能出现异常的地方
        // 第二点，对于所有可能有异常的地方，都必须进行一定的try catch处理
        // 第三点，如何对异常进行处理呢：要不然是往外抛，自定义异常，要不然就是进行一定的重试、其他的处理、自动退出

        try {
            if (!configFile.exists()) {
                throw new IllegalArgumentException(configFile.toString()
                        + " file is missing");
            }

            Properties cfg = new Properties();
            FileInputStream in = new FileInputStream(configFile);
            try {
                cfg.load(in);
            } finally {
                in.close();
            }

            parseProperties(cfg);
        } catch (IOException e) {
            throw new ConfigException("Error processing " + path, e);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Error processing " + path, e);
        }
    }

    /**
     * Parse config from a Properties.
     * @param zkProp Properties to parse from.
     * @throws IOException
     * @throws ConfigException
     */
    public void parseProperties(Properties zkProp)
    throws IOException, ConfigException {
        int clientPort = 0;

        // 下面一大坨东西就是针对加载到内存里来的配置进行处理和类型转换
        String clientPortAddress = null;
        for (Entry<Object, Object> entry : zkProp.entrySet()) {
            String key = entry.getKey().toString().trim();
            String value = entry.getValue().toString().trim();
            if (key.equals("dataDir")) {
                dataDir = value;
            } else if (key.equals("dataLogDir")) {
                dataLogDir = value;
            } else if (key.equals("clientPort")) {
                clientPort = Integer.parseInt(value);
            } else if (key.equals("clientPortAddress")) {
                clientPortAddress = value.trim();
            } else if (key.equals("tickTime")) {
                tickTime = Integer.parseInt(value);
            } else if (key.equals("maxClientCnxns")) {
                maxClientCnxns = Integer.parseInt(value);
            } else if (key.equals("minSessionTimeout")) {
                minSessionTimeout = Integer.parseInt(value);
            } else if (key.equals("maxSessionTimeout")) {
                maxSessionTimeout = Integer.parseInt(value);
            } else if (key.equals("initLimit")) {
                initLimit = Integer.parseInt(value);
            } else if (key.equals("syncLimit")) {
                syncLimit = Integer.parseInt(value);
            } else if (key.equals("electionAlg")) {
                electionAlg = Integer.parseInt(value);
            } else if (key.equals("peerType")) {
                if (value.toLowerCase().equals("observer")) {
                    peerType = LearnerType.OBSERVER;
                } else if (value.toLowerCase().equals("participant")) {
                    peerType = LearnerType.PARTICIPANT;
                } else
                {
                    throw new ConfigException("Unrecognised peertype: " + value);
                }
            } else if (key.equals("autopurge.snapRetainCount")) {
                snapRetainCount = Integer.parseInt(value);
            } else if (key.equals("autopurge.purgeInterval")) {
                purgeInterval = Integer.parseInt(value);
            } else if (key.startsWith("server.")) {
                int dot = key.indexOf('.');
                long sid = Long.parseLong(key.substring(dot + 1));
                String parts[] = value.split(":");
                if ((parts.length != 2) && (parts.length != 3) && (parts.length !=4)) {
                    LOG.error(value
                       + " does not have the form host:port or host:port:port " +
                       " or host:port:port:type");
                }
                InetSocketAddress addr = new InetSocketAddress(parts[0],
                        Integer.parseInt(parts[1]));
                if (parts.length == 2) {
                    servers.put(Long.valueOf(sid), new QuorumServer(sid, addr));
                } else if (parts.length == 3) {
                    InetSocketAddress electionAddr = new InetSocketAddress(
                            parts[0], Integer.parseInt(parts[2]));
                    servers.put(Long.valueOf(sid), new QuorumServer(sid, addr,
                            electionAddr));
                } else if (parts.length == 4) {
                    InetSocketAddress electionAddr = new InetSocketAddress(
                            parts[0], Integer.parseInt(parts[2]));
                    LearnerType type = LearnerType.PARTICIPANT;
                    if (parts[3].toLowerCase().equals("observer")) {
                        type = LearnerType.OBSERVER;
                        observers.put(Long.valueOf(sid), new QuorumServer(sid, addr,
                                electionAddr,type));
                    } else if (parts[3].toLowerCase().equals("participant")) {
                        type = LearnerType.PARTICIPANT;
                        servers.put(Long.valueOf(sid), new QuorumServer(sid, addr,
                                electionAddr,type));
                    } else {
                        throw new ConfigException("Unrecognised peertype: " + value);
                    }
                }
            } else if (key.startsWith("group")) {
                int dot = key.indexOf('.');
                long gid = Long.parseLong(key.substring(dot + 1));

                numGroups++;

                String parts[] = value.split(":");
                for(String s : parts){
                    long sid = Long.parseLong(s);
                    if(serverGroup.containsKey(sid))
                        throw new ConfigException("Server " + sid + "is in multiple groups");
                    else
                        serverGroup.put(sid, gid);
                }

            } else if(key.startsWith("weight")) {
                int dot = key.indexOf('.');
                long sid = Long.parseLong(key.substring(dot + 1));
                serverWeight.put(sid, Long.parseLong(value));
            } else {
                System.setProperty("zookeeper." + key, value);
            }
        }
        
        // Reset to MIN_SNAP_RETAIN_COUNT if invalid (less than 3)
        // PurgeTxnLog.purge(File, File, int) will not allow to purge less
        // than 3.
        // 需要对加载到内存里来的参数进行一定的校验
        // 如果有问题的话，还需要抛出对应的异常去进行处理
        if (snapRetainCount < MIN_SNAP_RETAIN_COUNT) {
            LOG.warn("Invalid autopurge.snapRetainCount: " + snapRetainCount
                    + ". Defaulting to " + MIN_SNAP_RETAIN_COUNT);
            snapRetainCount = MIN_SNAP_RETAIN_COUNT;
        }

        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir is not set");
        }
        if (dataLogDir == null) {
            dataLogDir = dataDir;
        } else {
            if (!new File(dataLogDir).isDirectory()) {
                throw new IllegalArgumentException("dataLogDir " + dataLogDir
                        + " is missing.");
            }
        }
        if (clientPort == 0) {
            throw new IllegalArgumentException("clientPort is not set");
        }
        if (clientPortAddress != null) {
            this.clientPortAddress = new InetSocketAddress(
                    InetAddress.getByName(clientPortAddress), clientPort);
        } else {
            this.clientPortAddress = new InetSocketAddress(clientPort);
        }

        if (tickTime == 0) {
            throw new IllegalArgumentException("tickTime is not set");
        }
        if (minSessionTimeout > maxSessionTimeout) {
            throw new IllegalArgumentException(
                    "minSessionTimeout must not be larger than maxSessionTimeout");
        }
        if (servers.size() == 0) {
            if (observers.size() > 0) {
                throw new IllegalArgumentException("Observers w/o participants is an invalid configuration");
            }
            // Not a quorum configuration so return immediately - not an error
            // case (for b/w compatibility), server will default to standalone
            // mode.
            return;
        } else if (servers.size() == 1) {
            if (observers.size() > 0) {
                throw new IllegalArgumentException("Observers w/o quorum is an invalid configuration");
            }

            // HBase currently adds a single server line to the config, for
            // b/w compatibility reasons we need to keep this here.
            LOG.error("Invalid configuration, only one server specified (ignoring)");
            servers.clear();
        } else if (servers.size() > 1) {
            if (servers.size() == 2) {
                LOG.warn("No server failure will be tolerated. " +
                    "You need at least 3 servers.");
            } else if (servers.size() % 2 == 0) {
                LOG.warn("Non-optimial configuration, consider an odd number of servers.");
            }
            if (initLimit == 0) {
                throw new IllegalArgumentException("initLimit is not set");
            }
            if (syncLimit == 0) {
                throw new IllegalArgumentException("syncLimit is not set");
            }
            /*
             * If using FLE, then every server requires a separate election
             * port.
             */
            if (electionAlg != 0) {
                for (QuorumServer s : servers.values()) {
                    if (s.electionAddr == null)
                        throw new IllegalArgumentException(
                                "Missing election port for server: " + s.id);
                }
            }

            /*
             * Default of quorum config is majority
             */
            if(serverGroup.size() > 0){
                if(servers.size() != serverGroup.size())
                    throw new ConfigException("Every server must be in exactly one group");
                /*
                 * The deafult weight of a server is 1
                 */
                for(QuorumServer s : servers.values()){
                    if(!serverWeight.containsKey(s.id))
                        serverWeight.put(s.id, (long) 1);
                }

                /*
                 * Set the quorumVerifier to be QuorumHierarchical
                 */
                quorumVerifier = new QuorumHierarchical(numGroups,
                        serverWeight, serverGroup);
            } else {
                /*
                 * The default QuorumVerifier is QuorumMaj
                 */

                LOG.info("Defaulting to majority quorums");
                quorumVerifier = new QuorumMaj(servers.size());
            }

            // Now add observers to servers, once the quorums have been
            // figured out
            servers.putAll(observers);

            // 但凡稍微用过一些zk的人，应该都懂，专门有一个文件，myid，里面写入这台机器的id
            File myIdFile = new File(dataDir, "myid");
            if (!myIdFile.exists()) {
                throw new IllegalArgumentException(myIdFile.toString()
                        + " file is missing");
            }
            BufferedReader br = new BufferedReader(new FileReader(myIdFile));
            String myIdString;
            try {
                myIdString = br.readLine();
            } finally {
                br.close();
            }
            try {
                serverId = Long.parseLong(myIdString);
                MDC.put("myid", myIdString);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("serverid " + myIdString
                        + " is not a number");
            }
            
            // Warn about inconsistent peer type
            LearnerType roleByServersList = observers.containsKey(serverId) ? LearnerType.OBSERVER
                    : LearnerType.PARTICIPANT;
            if (roleByServersList != peerType) {
                LOG.warn("Peer type from servers list (" + roleByServersList
                        + ") doesn't match peerType (" + peerType
                        + "). Defaulting to servers list.");
    
                peerType = roleByServersList;
            }
        }
    }

    public InetSocketAddress getClientPortAddress() { return clientPortAddress; }
    public String getDataDir() { return dataDir; }
    public String getDataLogDir() { return dataLogDir; }
    public int getTickTime() { return tickTime; }
    public int getMaxClientCnxns() { return maxClientCnxns; }
    public int getMinSessionTimeout() { return minSessionTimeout; }
    public int getMaxSessionTimeout() { return maxSessionTimeout; }

    public int getInitLimit() { return initLimit; }
    public int getSyncLimit() { return syncLimit; }
    public int getElectionAlg() { return electionAlg; }
    public int getElectionPort() { return electionPort; }    
    
    public int getSnapRetainCount() {
        return snapRetainCount;
    }

    public int getPurgeInterval() {
        return purgeInterval;
    }

    public QuorumVerifier getQuorumVerifier() {   
        return quorumVerifier;
    }

    public Map<Long,QuorumServer> getServers() {
        return Collections.unmodifiableMap(servers);
    }

    public long getServerId() { return serverId; }

    public boolean isDistributed() { return servers.size() > 1; }

    public LearnerType getPeerType() {
        return peerType;
    }
}
