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

import io.openmessaging.storage.dledger.protocol.DLedgerResponseCode;
import io.openmessaging.storage.dledger.utils.IOUtils;
import io.openmessaging.storage.dledger.utils.PreConditions;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.openmessaging.storage.dledger.MemberState.Role.CANDIDATE;
import static io.openmessaging.storage.dledger.MemberState.Role.FOLLOWER;
import static io.openmessaging.storage.dledger.MemberState.Role.LEADER;

/**
 * 节点状态类：Follower、Candidate、Leader
 */
public class MemberState {

    public static final String TERM_PERSIST_FILE = "currterm";
    public static final String TERM_PERSIST_KEY_TERM = "currTerm";
    public static final String TERM_PERSIST_KEY_VOTE_FOR = "voteLeader";
    public static Logger logger = LoggerFactory.getLogger(MemberState.class);

    /**
     * 节点配置
     */
    public final DLedgerConfig dLedgerConfig;
    private final ReentrantLock defaultLock = new ReentrantLock();

    /**
     * 集群组名
     */
    private final String group;

    /**
     * 节点ID
     */
    private final String selfId;
    private final String peers;

    /**
     * 节点的状态：初始值为Candidate
     */
    private volatile Role role = CANDIDATE;

    /**
     * leaderId
     */
    private volatile String leaderId;

    /**
     * 上一次投票的轮次
     */
    private volatile long currTerm = 0;

    /**
     * 当前投票的目标
     */
    private volatile String currVoteFor;

    /**
     * 节点存储的最后一条消息的索引值
     */
    private volatile long ledgerEndIndex = -1;

    /**
     * 节点存储的最后一条消息的轮次
     */
    private volatile long ledgerEndTerm = -1;

    /**
     * 已经知道的集群的最大选举轮次
     */
    private long knownMaxTermInGroup = -1;

    /**
     * 群下所有节点id | 地址map
     */
    private Map<String, String> peerMap = new HashMap<>();
    private Map<String, Boolean> peersLiveTable = new ConcurrentHashMap<>();

    private volatile String transferee;
    private volatile long termToTakeLeadership = -1;

    /**
     * 实例化一个节点状态对象
     * @param config 节点配置
     */
    public MemberState(DLedgerConfig config) {
        //设置集群名
        this.group = config.getGroup();
        //设置节点id
        this.selfId = config.getSelfId();

        //设置集群下其他成员地址
        this.peers = config.getPeers();
        //解析地址
        for (String peerInfo : this.peers.split(";")) {
            //n0-localhost:20911 id
            String peerSelfId = peerInfo.split("-")[0];
            //地址
            String peerAddress = peerInfo.substring(peerSelfId.length() + 1);
            //将其他成员信息放入map
            peerMap.put(peerSelfId, peerAddress);
        }

        //设置节点配置
        this.dLedgerConfig = config;
        loadTerm();
    }

    private void loadTerm() {
        try {
            String data = IOUtils.file2String(dLedgerConfig.getDefaultPath() + File.separator + TERM_PERSIST_FILE);
            Properties properties = IOUtils.string2Properties(data);
            if (properties == null) {
                return;
            }
            if (properties.containsKey(TERM_PERSIST_KEY_TERM)) {
                currTerm = Long.valueOf(String.valueOf(properties.get(TERM_PERSIST_KEY_TERM)));
            }
            if (properties.containsKey(TERM_PERSIST_KEY_VOTE_FOR)) {
                currVoteFor = String.valueOf(properties.get(TERM_PERSIST_KEY_VOTE_FOR));
                if (currVoteFor.length() == 0) {
                    currVoteFor = null;
                }
            }
        } catch (Throwable t) {
            logger.error("Load last term failed", t);
        }
    }

    private void persistTerm() {
        try {
            Properties properties = new Properties();
            properties.put(TERM_PERSIST_KEY_TERM, currTerm);
            properties.put(TERM_PERSIST_KEY_VOTE_FOR, currVoteFor == null ? "" : currVoteFor);
            String data = IOUtils.properties2String(properties);
            IOUtils.string2File(data, dLedgerConfig.getDefaultPath() + File.separator + TERM_PERSIST_FILE);
        } catch (Throwable t) {
            logger.error("Persist curr term failed", t);
        }
    }

    public long currTerm() {
        return currTerm;
    }

    public String currVoteFor() {
        return currVoteFor;
    }

    public synchronized void setCurrVoteFor(String currVoteFor) {
        this.currVoteFor = currVoteFor;
        persistTerm();
    }

    /**
     * 进入到下一个投票轮次
     * @return
     */
    public synchronized long nextTerm() {
        //检测进入下一个选举轮次的条件 角色必须是Candidate
        PreConditions.check(role == CANDIDATE, DLedgerResponseCode.ILLEGAL_MEMBER_STATE, "%s != %s", role, CANDIDATE);
        if (knownMaxTermInGroup > currTerm) {//如果已经知道了最大投票轮次 并且最大轮次大于当前轮次
            //当前选举轮次为已经知道的最大选举轮次
            currTerm = knownMaxTermInGroup;
        } else {
            //增加当前选举轮次
            ++currTerm;
        }

        //设置当前节点已经投票的目标节点为null
        currVoteFor = null;
        persistTerm();
        //返回投票轮次
        return currTerm;
    }

    public synchronized void changeToLeader(long term) {
        PreConditions.check(currTerm == term, DLedgerResponseCode.ILLEGAL_MEMBER_STATE, "%d != %d", currTerm, term);
        this.role = LEADER;
        this.leaderId = selfId;
        peersLiveTable.clear();
    }

    public synchronized void changeToFollower(long term, String leaderId) {
        PreConditions.check(currTerm == term, DLedgerResponseCode.ILLEGAL_MEMBER_STATE, "%d != %d", currTerm, term);
        this.role = FOLLOWER;
        this.leaderId = leaderId;
        transferee = null;
    }

    /**
     * 将当前节点的状态设置为Candidate
     * @param term 投票轮次
     */
    public synchronized void changeToCandidate(long term) {
        //投票轮次大于等于等于当前轮次
        assert term >= currTerm;
        //检查轮次
        PreConditions.check(term >= currTerm, DLedgerResponseCode.ILLEGAL_MEMBER_STATE, "should %d >= %d", term, currTerm);
        if (term > knownMaxTermInGroup) {//设置已知的最大轮次
            knownMaxTermInGroup = term;
        }
        //设置状态
        this.role = CANDIDATE;
        //设置leaderId
        this.leaderId = null;

        transferee = null;
    }

    public String getTransferee() {
        return transferee;
    }

    public void setTransferee(String transferee) {
        PreConditions.check(role == LEADER, DLedgerResponseCode.ILLEGAL_MEMBER_STATE, "%s is not leader", selfId);
        this.transferee = transferee;
    }

    public long getTermToTakeLeadership() {
        return termToTakeLeadership;
    }

    public void setTermToTakeLeadership(long termToTakeLeadership) {
        this.termToTakeLeadership = termToTakeLeadership;
    }

    public String getSelfId() {
        return selfId;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public String getGroup() {
        return group;
    }

    /**
     * 获取当前节点的地址
     * @return
     */
    public String getSelfAddr() {
        return peerMap.get(selfId);
    }

    public String getLeaderAddr() {
        return peerMap.get(leaderId);
    }

    public String getPeerAddr(String peerId) {
        return peerMap.get(peerId);
    }

    public boolean isLeader() {
        return role == LEADER;
    }

    public boolean isFollower() {
        return role == FOLLOWER;
    }

    public boolean isCandidate() {
        return role == CANDIDATE;
    }

    public boolean isQuorum(int num) {
        return num >= ((peerSize() / 2) + 1);
    }

    public int peerSize() {
        return peerMap.size();
    }

    public boolean isPeerMember(String id) {
        return id != null && peerMap.containsKey(id);
    }

    public Map<String, String> getPeerMap() {
        return peerMap;
    }

    public Map<String, Boolean> getPeersLiveTable() {
        return peersLiveTable;
    }

    //just for test
    public void setCurrTermForTest(long term) {
        PreConditions.check(term >= currTerm, DLedgerResponseCode.ILLEGAL_MEMBER_STATE);
        this.currTerm = term;
    }

    public Role getRole() {
        return role;
    }

    public ReentrantLock getDefaultLock() {
        return defaultLock;
    }

    /**
     * 更新状态机最后一条消息的索引值 轮次
     * @param index 最后一条索引值
     * @param term 最后一条轮次
     */
    public void updateLedgerIndexAndTerm(long index, long term) {
        //
        this.ledgerEndIndex = index;
        this.ledgerEndTerm = term;
    }

    public long getLedgerEndIndex() {
        return ledgerEndIndex;
    }

    public long getLedgerEndTerm() {
        return ledgerEndTerm;
    }

    public enum Role {
        UNKNOWN,
        CANDIDATE,
        LEADER,
        FOLLOWER;
    }
}
