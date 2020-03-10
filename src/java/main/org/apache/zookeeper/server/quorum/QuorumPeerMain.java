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

import java.io.File;
import java.io.IOException;

import javax.management.JMException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

/**
 *
 * <h2>Configuration file</h2>
 *
 * When the main() method of this class is used to start the program, the first
 * argument is used as a path to the config file, which will be used to obtain
 * configuration information. This file is a Properties file, so keys and
 * values are separated by equals (=) and the key/value pairs are separated
 * by new lines. The following is a general summary of keys used in the
 * configuration file. For full details on this see the documentation in
 * docs/index.html
 * <ol>
 * <li>dataDir - The directory where the ZooKeeper data is stored.</li>
 * <li>dataLogDir - The directory where the ZooKeeper transaction log is stored.</li>
 * <li>clientPort - The port used to communicate with clients.</li>
 * <li>tickTime - The duration of a tick in milliseconds. This is the basic
 * unit of time in ZooKeeper.</li>
 * <li>initLimit - The maximum number of ticks that a follower will wait to
 * initially synchronize with a leader.</li>
 * <li>syncLimit - The maximum number of ticks that a follower will wait for a
 * message (including heartbeats) from the leader.</li>
 * <li>server.<i>id</i> - This is the host:port[:port] that the server with the
 * given id will use for the quorum protocol.</li>
 * </ol>
 * In addition to the config file. There is a file in the data directory called
 * "myid" that contains the server id as an ASCII decimal value.
 *
 */
public class QuorumPeerMain {

    // 私有的，静态，不可变的
    // Logger代表了一个日志记录组件的意思，一般来说，会用大写的LOG或者是LOGGER，作为常量名字
    // LoggerFactory，日志记录组件的工厂，工厂模式，去获取一个日志组件
    // 根据你的类，把你自己的类名传递进去，根据你的类名获取一个日志组件，是这个意思
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerMain.class);

    private static final String USAGE = "Usage: QuorumPeerMain configfile";

    protected QuorumPeer quorumPeer;

    /**
     * To start the replicated server specify the configuration file name on
     * the command line.
     * @param args path to the configfile
     */
    public static void main(String[] args) {
        QuorumPeerMain main = new QuorumPeerMain();
        try {
            main.initializeAndRun(args);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid arguments, exiting abnormally", e);
            LOG.info(USAGE);
            System.err.println(USAGE);
            System.exit(2);
        } catch (ConfigException e) {
            LOG.error("Invalid config, exiting abnormally", e);
            System.err.println("Invalid config, exiting abnormally");
            System.exit(2); // 如果解析配置文件都有问题，直接是会退出的
        } catch (Exception e) {
            LOG.error("Unexpected exception, exiting abnormally", e);
            System.exit(1);
        }
        LOG.info("Exiting normally");
        System.exit(0);
    }

    protected void initializeAndRun(String[] args)
        throws ConfigException, IOException
    {
        // 创建了一个用于解析配置文件的类
        QuorumPeerConfig config = new QuorumPeerConfig();
        if (args.length == 1) { // 如果发现你的命令行里仅仅给他传递了一个参数的话
            // 此时就认为你传递的就是那个配置文件，zoo.cfg
            // 就用QuorumPeerConfig类去解析zoo.cfg配置文件
            config.parse(args[0]);
        }

        // Start and schedule the the purge task
        // 就是启动了后台线程，定期清理zk的日志文件和快照文件
        DatadirCleanupManager purgeMgr = new DatadirCleanupManager(config
                .getDataDir(), config.getDataLogDir(), config
                .getSnapRetainCount(), config.getPurgeInterval());
        purgeMgr.start();

        // 如果说发现你传递进来了一个zoo.cfg配置文件
        // 另外这个配置文件servers配置了，就说明你配置了一个zk集群
        if (args.length == 1 && config.servers.size() > 0) {
            // 走这个代码分支，按照集群的模式来启动当前的这个zk节点
            runFromConfig(config);
        }
        // 如果不是上面的情况，说明你是用单机模式启动的zk
        else {
            LOG.warn("Either no config or no quorum defined in config, running "
                    + " in standalone mode");
            // there is only server in the quorum -- run as standalone
            // 走这个分支是用来启动单机版本的zk的
            ZooKeeperServerMain.main(args);
        }
    }

    public void runFromConfig(QuorumPeerConfig config) throws IOException {
      try {
          ManagedUtil.registerLog4jMBeans();
      } catch (JMException e) {
          LOG.warn("Unable to register log4j JMX control", e);
      }
  
      LOG.info("Starting quorum peer");
      try {
          // 这个东西其实是很关键的一个组件
          // 他是会启动一个server，监听一个端口，2888
          // 让客户端跟他建立连接，发送请求
          ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory();
          cnxnFactory.configure(config.getClientPortAddress(),
                                config.getMaxClientCnxns());

          // 一个ZooKeeper节点的启动，最核心的东西就是这个QuorumPeer
          // 这个QuorumPeer代表了一个zk节点
          quorumPeer = new QuorumPeer();
          quorumPeer.setClientPortAddress(config.getClientPortAddress());
          quorumPeer.setTxnFactory(new FileTxnSnapLog(
                      new File(config.getDataLogDir()),
                      new File(config.getDataDir())));
          quorumPeer.setQuorumPeers(config.getServers()); // quorumPeers，集群里的机器的数量
          quorumPeer.setElectionType(config.getElectionAlg());
          quorumPeer.setMyid(config.getServerId());
          quorumPeer.setTickTime(config.getTickTime());
          quorumPeer.setMinSessionTimeout(config.getMinSessionTimeout());
          quorumPeer.setMaxSessionTimeout(config.getMaxSessionTimeout());
          quorumPeer.setInitLimit(config.getInitLimit());
          quorumPeer.setSyncLimit(config.getSyncLimit());
          quorumPeer.setQuorumVerifier(config.getQuorumVerifier());
          quorumPeer.setCnxnFactory(cnxnFactory);
          quorumPeer.setZKDatabase(new ZKDatabase(quorumPeer.getTxnFactory()));
          quorumPeer.setLearnerType(config.getPeerType());
  
          quorumPeer.start();
          quorumPeer.join();
      } catch (InterruptedException e) {
          // warn, but generally this is ok
          LOG.warn("Quorum Peer interrupted", e);
      }
    }
}
