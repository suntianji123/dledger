/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.openmessaging.storage.dledger;

import com.beust.jcommander.Parameter;
import io.openmessaging.storage.dledger.store.file.DLedgerMmapFileStore;
import java.io.File;

/**
 * 节点配置类
 */
public class DLedgerConfig {

    /**
     * 消息存储类型为内存
     */
    public static final String MEMORY = "MEMORY";

    /**
     * 消息存储类型为文件
     */
    public static final String FILE = "FILE";

    /**
     * 集群名
     */
    @Parameter(names = {"--group", "-g"}, description = "Group of this server")
    private String group = "default";

    /**
     * 节点id
     */
    @Parameter(names = {"--id", "-i"}, description = "Self id of this server")
    private String selfId = "n0";

    /**
     * 集群下其他成员节点地址
     */
    @Parameter(names = {"--peers", "-p"}, description = "Peer info of this server")
    private String peers = "n0-localhost:20911";

    /**
     * 默认的存储路径
     */
    @Parameter(names = {"--store-base-dir", "-s"}, description = "The base store dir of this server")
    private String storeBaseDir = File.separator + "tmp" + File.separator + "dledgerstore";


    @Parameter(names = {"--peer-push-throttle-point"}, description = "When the follower is behind the leader more than this value, it will trigger the throttle")
    private int peerPushThrottlePoint = 300 * 1024 * 1024;

    @Parameter(names = {"--peer-push-quotas"}, description = "The quotas of the pusher")
    private int peerPushQuota = 20 * 1024 * 1024;

    /**
     * 消息存储类型：存储到文件、存储到内存 默认为文件
     */
    private String storeType = FILE; //FILE, MEMORY

    /**
     * mappedFile文件存储的根目录
     */
    private String dataStorePath;

    private int maxPendingRequestsNum = 10000;

    private int maxWaitAckTimeMs = 2500;

    private int maxPushTimeOutMs = 1000;

    /**
     * 是否允许开启选举实现器
     */
    private boolean enableLeaderElector = true;

    /**
     * 一个心跳的周期
     */
    private int heartBeatTimeIntervalMs = 2000;

    /**
     * Follower超过了这个N次没有收到Leader发送的心跳包 状态将重新进入到Candidate状态
     */
    private int maxHeartBeatLeak = 3;

    /**
     * 最小发起投票的时间间隔
     */
    private int minVoteIntervalMs = 300;

    /**
     * 最大发起投票的时间间隔
     */
    private int maxVoteIntervalMs = 1000;

    /**
     * 需要删除的文件的超时时间72小时
     */
    private int fileReservedHours = 72;

    /**
     * 每天凌晨4点 检查清理存储的根目录和消息存储的父目录一次
     */
    private String deleteWhen = "04";


    /**
     * 是否需要检查清理老的文件
     */
    private float diskSpaceRatioToCheckExpired = Float.parseFloat(System.getProperty("dledger.disk.ratio.check", "0.70"));

    /**
     * 磁盘文件的使用率 超过这个值时 需要清理文件件下的文件
     */
    private float diskSpaceRatioToForceClean = Float.parseFloat(System.getProperty("dledger.disk.ratio.clean", "0.85"));

    /**
     * 是否可以强制清理存储目录下的文件
     */
    private boolean enableDiskForceClean = true;

    /**
     * flushedDataService检查将mappedFile list内存中的字节数组刷新到存盘文件的周期
     */
    private long flushFileInterval = 10;

    /**
     * 检查点的周期 默认为3s
     */
    private long checkPointInterval = 3000;

    /**
     * 单个mappedFile的最大字节数 默认为1G
     */
    private int mappedFileSizeForEntryData = 1024 * 1024 * 1024;

    /**
     * 消息索引信息单个mappedFile的最大值 默认160M
     */
    private int mappedFileSizeForEntryIndex = DLedgerMmapFileStore.INDEX_UNIT_SIZE * 5 * 1024 * 1024;

    private boolean enablePushToFollower = true;

    /**
     * 优先设置的节点id
     */
    @Parameter(names = {"--preferred-leader-id"}, description = "Preferred LeaderId")
    private String preferredLeaderId;
    private long maxLeadershipTransferWaitIndex = 1000;
    private int minTakeLeadershipVoteIntervalMs =  30;
    private int maxTakeLeadershipVoteIntervalMs =  100;

    private boolean isEnableBatchPush = false;
    private int maxBatchPushSize = 4 * 1024;


    public String getDefaultPath() {
        return storeBaseDir + File.separator + "dledger-" + selfId;
    }

    public String getDataStorePath() {
        if (dataStorePath == null) {
            return getDefaultPath() + File.separator + "data";
        }
        return dataStorePath;
    }

    public void setDataStorePath(String dataStorePath) {
        this.dataStorePath = dataStorePath;
    }

    /**
     * 消息索引信息mappedFile的根目录
     * @return
     */
    public String getIndexStorePath() {
        return getDefaultPath() + File.separator + "index";
    }

    public int getMappedFileSizeForEntryData() {
        return mappedFileSizeForEntryData;
    }

    public void setMappedFileSizeForEntryData(int mappedFileSizeForEntryData) {
        this.mappedFileSizeForEntryData = mappedFileSizeForEntryData;
    }

    public int getMappedFileSizeForEntryIndex() {
        return mappedFileSizeForEntryIndex;
    }

    public void setMappedFileSizeForEntryIndex(int mappedFileSizeForEntryIndex) {
        this.mappedFileSizeForEntryIndex = mappedFileSizeForEntryIndex;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getSelfId() {
        return selfId;
    }

    public void setSelfId(String selfId) {
        this.selfId = selfId;
    }

    public String getPeers() {
        return peers;
    }

    public void setPeers(String peers) {
        this.peers = peers;
    }

    public String getStoreBaseDir() {
        return storeBaseDir;
    }

    public void setStoreBaseDir(String storeBaseDir) {
        this.storeBaseDir = storeBaseDir;
    }

    public String getStoreType() {
        return storeType;
    }

    public void setStoreType(String storeType) {
        this.storeType = storeType;
    }

    public boolean isEnableLeaderElector() {
        return enableLeaderElector;
    }

    public void setEnableLeaderElector(boolean enableLeaderElector) {
        this.enableLeaderElector = enableLeaderElector;
    }

    //for builder semantic
    public DLedgerConfig group(String group) {
        this.group = group;
        return this;
    }

    public DLedgerConfig selfId(String selfId) {
        this.selfId = selfId;
        return this;
    }

    public DLedgerConfig peers(String peers) {
        this.peers = peers;
        return this;
    }

    public DLedgerConfig storeBaseDir(String dir) {
        this.storeBaseDir = dir;
        return this;
    }

    public boolean isEnablePushToFollower() {
        return enablePushToFollower;
    }

    public void setEnablePushToFollower(boolean enablePushToFollower) {
        this.enablePushToFollower = enablePushToFollower;
    }

    public int getMaxPendingRequestsNum() {
        return maxPendingRequestsNum;
    }

    public void setMaxPendingRequestsNum(int maxPendingRequestsNum) {
        this.maxPendingRequestsNum = maxPendingRequestsNum;
    }

    public int getMaxWaitAckTimeMs() {
        return maxWaitAckTimeMs;
    }

    public void setMaxWaitAckTimeMs(int maxWaitAckTimeMs) {
        this.maxWaitAckTimeMs = maxWaitAckTimeMs;
    }

    public int getMaxPushTimeOutMs() {
        return maxPushTimeOutMs;
    }

    public void setMaxPushTimeOutMs(int maxPushTimeOutMs) {
        this.maxPushTimeOutMs = maxPushTimeOutMs;
    }

    public int getHeartBeatTimeIntervalMs() {
        return heartBeatTimeIntervalMs;
    }

    public void setHeartBeatTimeIntervalMs(int heartBeatTimeIntervalMs) {
        this.heartBeatTimeIntervalMs = heartBeatTimeIntervalMs;
    }

    public int getMinVoteIntervalMs() {
        return minVoteIntervalMs;
    }

    public void setMinVoteIntervalMs(int minVoteIntervalMs) {
        this.minVoteIntervalMs = minVoteIntervalMs;
    }

    public int getMaxVoteIntervalMs() {
        return maxVoteIntervalMs;
    }

    public void setMaxVoteIntervalMs(int maxVoteIntervalMs) {
        this.maxVoteIntervalMs = maxVoteIntervalMs;
    }

    public String getDeleteWhen() {
        return deleteWhen;
    }

    public void setDeleteWhen(String deleteWhen) {
        this.deleteWhen = deleteWhen;
    }

    public float getDiskSpaceRatioToCheckExpired() {
        return diskSpaceRatioToCheckExpired;
    }

    public void setDiskSpaceRatioToCheckExpired(float diskSpaceRatioToCheckExpired) {
        this.diskSpaceRatioToCheckExpired = diskSpaceRatioToCheckExpired;
    }

    public float getDiskSpaceRatioToForceClean() {
        if (diskSpaceRatioToForceClean < 0.50f) {
            return 0.50f;
        } else {
            return diskSpaceRatioToForceClean;
        }
    }

    public void setDiskSpaceRatioToForceClean(float diskSpaceRatioToForceClean) {
        this.diskSpaceRatioToForceClean = diskSpaceRatioToForceClean;
    }

    /**
     * 文件夹满了 标志值 90%
     * @return
     */
    public float getDiskFullRatio() {
        float ratio = diskSpaceRatioToForceClean + 0.05f;
        if (ratio > 0.95f) {
            return 0.95f;
        }
        return ratio;
    }

    public int getFileReservedHours() {
        return fileReservedHours;
    }

    public void setFileReservedHours(int fileReservedHours) {
        this.fileReservedHours = fileReservedHours;
    }

    public long getFlushFileInterval() {
        return flushFileInterval;
    }

    public void setFlushFileInterval(long flushFileInterval) {
        this.flushFileInterval = flushFileInterval;
    }

    public boolean isEnableDiskForceClean() {
        return enableDiskForceClean;
    }

    public void setEnableDiskForceClean(boolean enableDiskForceClean) {
        this.enableDiskForceClean = enableDiskForceClean;
    }

    public int getMaxHeartBeatLeak() {
        return maxHeartBeatLeak;
    }

    public void setMaxHeartBeatLeak(int maxHeartBeatLeak) {
        this.maxHeartBeatLeak = maxHeartBeatLeak;
    }

    public int getPeerPushThrottlePoint() {
        return peerPushThrottlePoint;
    }

    public void setPeerPushThrottlePoint(int peerPushThrottlePoint) {
        this.peerPushThrottlePoint = peerPushThrottlePoint;
    }

    public int getPeerPushQuota() {
        return peerPushQuota;
    }

    public void setPeerPushQuota(int peerPushQuota) {
        this.peerPushQuota = peerPushQuota;
    }

    public long getCheckPointInterval() {
        return checkPointInterval;
    }

    public void setCheckPointInterval(long checkPointInterval) {
        this.checkPointInterval = checkPointInterval;
    }

    public String getPreferredLeaderId() {
        return preferredLeaderId;
    }

    public void setPreferredLeaderId(String preferredLeaderId) {
        this.preferredLeaderId = preferredLeaderId;
    }

    public long getMaxLeadershipTransferWaitIndex() {
        return maxLeadershipTransferWaitIndex;
    }

    public void setMaxLeadershipTransferWaitIndex(long maxLeadershipTransferWaitIndex) {
        this.maxLeadershipTransferWaitIndex = maxLeadershipTransferWaitIndex;
    }

    public int getMinTakeLeadershipVoteIntervalMs() {
        return minTakeLeadershipVoteIntervalMs;
    }

    public void setMinTakeLeadershipVoteIntervalMs(int minTakeLeadershipVoteIntervalMs) {
        this.minTakeLeadershipVoteIntervalMs = minTakeLeadershipVoteIntervalMs;
    }

    public int getMaxTakeLeadershipVoteIntervalMs() {
        return maxTakeLeadershipVoteIntervalMs;
    }

    public void setMaxTakeLeadershipVoteIntervalMs(int maxTakeLeadershipVoteIntervalMs) {
        this.maxTakeLeadershipVoteIntervalMs = maxTakeLeadershipVoteIntervalMs;
    }

    public boolean isEnableBatchPush() {
        return isEnableBatchPush;
    }

    public void setEnableBatchPush(boolean enableBatchPush) {
        isEnableBatchPush = enableBatchPush;
    }

    public int getMaxBatchPushSize() {
        return maxBatchPushSize;
    }

    public void setMaxBatchPushSize(int maxBatchPushSize) {
        this.maxBatchPushSize = maxBatchPushSize;
    }
}
