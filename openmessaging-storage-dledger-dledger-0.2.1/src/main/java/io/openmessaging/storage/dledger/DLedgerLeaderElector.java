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

import com.alibaba.fastjson.JSON;
import io.openmessaging.storage.dledger.protocol.DLedgerResponseCode;
import io.openmessaging.storage.dledger.protocol.HeartBeatRequest;
import io.openmessaging.storage.dledger.protocol.HeartBeatResponse;
import io.openmessaging.storage.dledger.protocol.LeadershipTransferRequest;
import io.openmessaging.storage.dledger.protocol.LeadershipTransferResponse;
import io.openmessaging.storage.dledger.protocol.VoteRequest;
import io.openmessaging.storage.dledger.protocol.VoteResponse;
import io.openmessaging.storage.dledger.utils.DLedgerUtils;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * leader选举实现器
 */
public class DLedgerLeaderElector {

    private static Logger logger = LoggerFactory.getLogger(DLedgerLeaderElector.class);

    private Random random = new Random();
    private DLedgerConfig dLedgerConfig;

    /**
     * 节点状态：Follower、Candidate、Leader
     */
    private final MemberState memberState;
    private DLedgerRpcService dLedgerRpcService;

    //as a server handler
    //record the last leader state
    private volatile long lastLeaderHeartBeatTime = -1;
    private volatile long lastSendHeartBeatTime = -1;
    private volatile long lastSuccHeartBeatTime = -1;

    /**
     * 设置一个心跳包的周期
     */
    private int heartBeatTimeIntervalMs = 2000;

    /**
     * 允许未收到leader心跳的最大次数，如果Follower未收到leader的心跳包次数超过了这个值，Follower将重新Candidate状态
     */
    private int maxHeartBeatLeak = 3;
    //as a client
    private long nextTimeToRequestVote = -1;

    /**
     * 是否应该立刻发起投票
     */
    private volatile boolean needIncreaseTermImmediately = false;

    /**
     * 最小发起投票的时间间隔
     */
    private int minVoteIntervalMs = 300;

    /**
     * 最大发起投票的时间间隔
     */
    private int maxVoteIntervalMs = 1000;

    private List<RoleChangeHandler> roleChangeHandlers = new ArrayList<>();

    /**
     * 当前节点的投票状态：等待投票
     */
    private VoteResponse.ParseResult lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;

    /**
     * 上一次投票消耗的时间
     */
    private long lastVoteCost = 0L;

    /**
     * 状态机管理器
     */
    private StateMaintainer stateMaintainer = new StateMaintainer("StateMaintainer", logger);

    private final TakeLeadershipTask takeLeadershipTask = new TakeLeadershipTask();

    /**
     * 实例化一个选举实现器
     * @param dLedgerConfig 节点配置
     * @param memberState 节点状态机
     * @param dLedgerRpcService rpc服务
     */
    public DLedgerLeaderElector(DLedgerConfig dLedgerConfig, MemberState memberState,
        DLedgerRpcService dLedgerRpcService) {
        //设置节点配置
        this.dLedgerConfig = dLedgerConfig;
        //设置状态机
        this.memberState = memberState;
        //设置rpc服务
        this.dLedgerRpcService = dLedgerRpcService;

        //重置时间周期
        refreshIntervals(dLedgerConfig);
    }

    /**
     * 启动选举实现器
     */
    public void startup() {
        stateMaintainer.start();
        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            roleChangeHandler.startup();
        }
    }

    public void shutdown() {
        stateMaintainer.shutdown();
        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            roleChangeHandler.shutdown();
        }
    }

    /**
     * 重置时间周期
     * @param dLedgerConfig 节点配置
     */
    private void refreshIntervals(DLedgerConfig dLedgerConfig) {
        //设置一个心跳包的周期
        this.heartBeatTimeIntervalMs = dLedgerConfig.getHeartBeatTimeIntervalMs();

        //设置最大Follower没有收到Leader心跳包的次数
        this.maxHeartBeatLeak = dLedgerConfig.getMaxHeartBeatLeak();

        //最小发起投票的时间间隔
        this.minVoteIntervalMs = dLedgerConfig.getMinVoteIntervalMs();

        //最大发起投票的时间间隔
        this.maxVoteIntervalMs = dLedgerConfig.getMaxVoteIntervalMs();
    }

    public CompletableFuture<HeartBeatResponse> handleHeartBeat(HeartBeatRequest request) throws Exception {

        if (!memberState.isPeerMember(request.getLeaderId())) {
            logger.warn("[BUG] [HandleHeartBeat] remoteId={} is an unknown member", request.getLeaderId());
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.UNKNOWN_MEMBER.getCode()));
        }

        if (memberState.getSelfId().equals(request.getLeaderId())) {
            logger.warn("[BUG] [HandleHeartBeat] selfId={} but remoteId={}", memberState.getSelfId(), request.getLeaderId());
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.UNEXPECTED_MEMBER.getCode()));
        }

        if (request.getTerm() < memberState.currTerm()) {
            return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
        } else if (request.getTerm() == memberState.currTerm()) {
            if (request.getLeaderId().equals(memberState.getLeaderId())) {
                lastLeaderHeartBeatTime = System.currentTimeMillis();
                return CompletableFuture.completedFuture(new HeartBeatResponse());
            }
        }

        //abnormal case
        //hold the lock to get the latest term and leaderId
        synchronized (memberState) {
            if (request.getTerm() < memberState.currTerm()) {
                return CompletableFuture.completedFuture(new HeartBeatResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
            } else if (request.getTerm() == memberState.currTerm()) {
                if (memberState.getLeaderId() == null) {
                    changeRoleToFollower(request.getTerm(), request.getLeaderId());
                    return CompletableFuture.completedFuture(new HeartBeatResponse());
                } else if (request.getLeaderId().equals(memberState.getLeaderId())) {
                    lastLeaderHeartBeatTime = System.currentTimeMillis();
                    return CompletableFuture.completedFuture(new HeartBeatResponse());
                } else {
                    //this should not happen, but if happened
                    logger.error("[{}][BUG] currTerm {} has leader {}, but received leader {}", memberState.getSelfId(), memberState.currTerm(), memberState.getLeaderId(), request.getLeaderId());
                    return CompletableFuture.completedFuture(new HeartBeatResponse().code(DLedgerResponseCode.INCONSISTENT_LEADER.getCode()));
                }
            } else {
                //To make it simple, for larger term, do not change to follower immediately
                //first change to candidate, and notify the state-maintainer thread
                changeRoleToCandidate(request.getTerm());
                needIncreaseTermImmediately = true;
                //TOOD notify
                return CompletableFuture.completedFuture(new HeartBeatResponse().code(DLedgerResponseCode.TERM_NOT_READY.getCode()));
            }
        }
    }

    /**
     * 改变节点的角色为leader
     * @param term 当前选举轮次
     */
    public void changeRoleToLeader(long term) {
        synchronized (memberState) {
            if (memberState.currTerm() == term) {
                //改变角色为leader
                memberState.changeToLeader(term);
                //设置上一次给其他节点发送心跳的时间
                lastSendHeartBeatTime = -1;
                handleRoleChange(term, MemberState.Role.LEADER);
                logger.info("[{}] [ChangeRoleToLeader] from term: {} and currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            } else {
                logger.warn("[{}] skip to be the leader in term: {}, but currTerm is: {}", memberState.getSelfId(), term, memberState.currTerm());
            }
        }
    }

    /**
     * 将当前节点的状态设置为Candidate
     * @param term 当前轮次
     */
    public void changeRoleToCandidate(long term) {
        synchronized (memberState) {//将状态加锁
            if (term >= memberState.currTerm()) {
                //将节点状态设置为Candidate
                memberState.changeToCandidate(term);
                handleRoleChange(term, MemberState.Role.CANDIDATE);
                logger.info("[{}] [ChangeRoleToCandidate] from term: {} and currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            } else {
                logger.info("[{}] skip to be candidate in term: {}, but currTerm: {}", memberState.getSelfId(), term, memberState.currTerm());
            }
        }
    }

    //just for test
    public void testRevote(long term) {
        changeRoleToCandidate(term);
        lastParseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
        nextTimeToRequestVote = -1;
    }

    public void changeRoleToFollower(long term, String leaderId) {
        logger.info("[{}][ChangeRoleToFollower] from term: {} leaderId: {} and currTerm: {}", memberState.getSelfId(), term, leaderId, memberState.currTerm());
        lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
        memberState.changeToFollower(term, leaderId);
        lastLeaderHeartBeatTime = System.currentTimeMillis();
        handleRoleChange(term, MemberState.Role.FOLLOWER);
    }

    /**
     * 处理拉票请求
     * @param request 拉票请求
     * @param self 是否为给自己投票
     * @return
     */
    public CompletableFuture<VoteResponse> handleVote(VoteRequest request, boolean self) {
        synchronized (memberState) {//使用状态机做为锁对象
            if (!memberState.isPeerMember(request.getLeaderId())) {//如果指定的节点id 不是当前集群下的成员 直接返回
                logger.warn("[BUG] [HandleVote] remoteId={} is an unknown member", request.getLeaderId());
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_UNKNOWN_LEADER));
            }
            if (!self && memberState.getSelfId().equals(request.getLeaderId())) {//不是自己投票给自己的请求 但是当前节点的id与请求成员的目标节点id相等  返回错误
                logger.warn("[BUG] [HandleVote] selfId={} but remoteId={}", memberState.getSelfId(), request.getLeaderId());
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_UNEXPECTED_LEADER));
            }


            if (request.getLedgerEndTerm() < memberState.getLedgerEndTerm()) { //发起请求节点最后一条消息的轮次小于当前节点最后一条消息的轮次
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_EXPIRED_LEDGER_TERM));
            } else if (request.getLedgerEndTerm() == memberState.getLedgerEndTerm() && request.getLedgerEndIndex() < memberState.getLedgerEndIndex()) {
                //发起投票节点的最后一条消息的轮次与当前节点的最后一条消息的轮次相等  当时发起投票请求的节点的最后一条消息的index值 小于当前节点最后一条消息的index值
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_SMALL_LEDGER_END_INDEX));
            }

            if (request.getTerm() < memberState.currTerm()) {//发起请求的节点状态机的轮次小于当前状态机的轮次
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_EXPIRED_VOTE_TERM));
            } else if (request.getTerm() == memberState.currTerm()) {//如果发起的节点轮次与当前状态机的轮次相等
                if (memberState.currVoteFor() == null) {//当前节点还没投过票
                    //let it go
                } else if (memberState.currVoteFor().equals(request.getLeaderId())) {//当前节点支持请求的目标节点成为leader
                    //repeat just let it go
                } else {//当前节点投过票 并且投票给其他节点
                    if (memberState.getLeaderId() != null) {//如果当前节点有leader
                        return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_ALREADY_HAS_LEADER));
                    } else {//当前节点没有leader
                        return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_ALREADY_VOTED));
                    }
                }
            } else {
                //将当前节点变更为候选人
                changeRoleToCandidate(request.getTerm());
                //需要立刻发起投票请求
                needIncreaseTermImmediately = true;
                //only can handleVote when the term is consistent
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_TERM_NOT_READY));
            }

            if (request.getTerm() < memberState.getLedgerEndTerm()) {//发起请求的节点最后一条消息轮次小于当前节点最后一条消息的轮次
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.getLedgerEndTerm()).voteResult(VoteResponse.RESULT.REJECT_TERM_SMALL_THAN_LEDGER));
            }

            //不是投给自己  但是需要优先选自己 发起请求的轮次与当前节点的最后一条消息的轮次相等 当前节点最后一条消息的index值 大于等于发起请求的leaderId
            if (!self && isTakingLeadership() && request.getLedgerEndTerm() == memberState.getLedgerEndTerm() && memberState.getLedgerEndIndex() >= request.getLedgerEndIndex()) {
                return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.REJECT_TAKING_LEADERSHIP));
            }

            //支持当前投票
            memberState.setCurrVoteFor(request.getLeaderId());
            //返回支持
            return CompletableFuture.completedFuture(new VoteResponse(request).term(memberState.currTerm()).voteResult(VoteResponse.RESULT.ACCEPT));
        }
    }

    private void sendHeartbeats(long term, String leaderId) throws Exception {
        final AtomicInteger allNum = new AtomicInteger(1);
        final AtomicInteger succNum = new AtomicInteger(1);
        final AtomicInteger notReadyNum = new AtomicInteger(0);
        final AtomicLong maxTerm = new AtomicLong(-1);
        final AtomicBoolean inconsistLeader = new AtomicBoolean(false);
        final CountDownLatch beatLatch = new CountDownLatch(1);
        long startHeartbeatTimeMs = System.currentTimeMillis();
        for (String id : memberState.getPeerMap().keySet()) {
            if (memberState.getSelfId().equals(id)) {
                continue;
            }
            HeartBeatRequest heartBeatRequest = new HeartBeatRequest();
            heartBeatRequest.setGroup(memberState.getGroup());
            heartBeatRequest.setLocalId(memberState.getSelfId());
            heartBeatRequest.setRemoteId(id);
            heartBeatRequest.setLeaderId(leaderId);
            heartBeatRequest.setTerm(term);
            CompletableFuture<HeartBeatResponse> future = dLedgerRpcService.heartBeat(heartBeatRequest);
            future.whenComplete((HeartBeatResponse x, Throwable ex) -> {
                try {
                    if (ex != null) {
                        memberState.getPeersLiveTable().put(id, Boolean.FALSE);
                        throw ex;
                    }
                    switch (DLedgerResponseCode.valueOf(x.getCode())) {
                        case SUCCESS:
                            succNum.incrementAndGet();
                            break;
                        case EXPIRED_TERM:
                            maxTerm.set(x.getTerm());
                            break;
                        case INCONSISTENT_LEADER:
                            inconsistLeader.compareAndSet(false, true);
                            break;
                        case TERM_NOT_READY:
                            notReadyNum.incrementAndGet();
                            break;
                        default:
                            break;
                    }

                    if (x.getCode() == DLedgerResponseCode.NETWORK_ERROR.getCode())
                        memberState.getPeersLiveTable().put(id, Boolean.FALSE);
                    else
                        memberState.getPeersLiveTable().put(id, Boolean.TRUE);

                    if (memberState.isQuorum(succNum.get())
                        || memberState.isQuorum(succNum.get() + notReadyNum.get())) {
                        beatLatch.countDown();
                    }
                } catch (Throwable t) {
                    logger.error("heartbeat response failed", t);
                } finally {
                    allNum.incrementAndGet();
                    if (allNum.get() == memberState.peerSize()) {
                        beatLatch.countDown();
                    }
                }
            });
        }
        beatLatch.await(heartBeatTimeIntervalMs, TimeUnit.MILLISECONDS);
        if (memberState.isQuorum(succNum.get())) {
            lastSuccHeartBeatTime = System.currentTimeMillis();
        } else {
            logger.info("[{}] Parse heartbeat responses in cost={} term={} allNum={} succNum={} notReadyNum={} inconsistLeader={} maxTerm={} peerSize={} lastSuccHeartBeatTime={}",
                memberState.getSelfId(), DLedgerUtils.elapsed(startHeartbeatTimeMs), term, allNum.get(), succNum.get(), notReadyNum.get(), inconsistLeader.get(), maxTerm.get(), memberState.peerSize(), new Timestamp(lastSuccHeartBeatTime));
            if (memberState.isQuorum(succNum.get() + notReadyNum.get())) {
                lastSendHeartBeatTime = -1;
            } else if (maxTerm.get() > term) {
                changeRoleToCandidate(maxTerm.get());
            } else if (inconsistLeader.get()) {
                changeRoleToCandidate(term);
            } else if (DLedgerUtils.elapsed(lastSuccHeartBeatTime) > maxHeartBeatLeak * heartBeatTimeIntervalMs) {
                changeRoleToCandidate(term);
            }
        }
    }

    private void maintainAsLeader() throws Exception {
        if (DLedgerUtils.elapsed(lastSendHeartBeatTime) > heartBeatTimeIntervalMs) {
            long term;
            String leaderId;
            synchronized (memberState) {
                if (!memberState.isLeader()) {
                    //stop sending
                    return;
                }
                term = memberState.currTerm();
                leaderId = memberState.getLeaderId();
                lastSendHeartBeatTime = System.currentTimeMillis();
            }
            sendHeartbeats(term, leaderId);
        }
    }

    private void maintainAsFollower() {
        if (DLedgerUtils.elapsed(lastLeaderHeartBeatTime) > 2 * heartBeatTimeIntervalMs) {
            synchronized (memberState) {
                if (memberState.isFollower() && (DLedgerUtils.elapsed(lastLeaderHeartBeatTime) > maxHeartBeatLeak * heartBeatTimeIntervalMs)) {
                    logger.info("[{}][HeartBeatTimeOut] lastLeaderHeartBeatTime: {} heartBeatTimeIntervalMs: {} lastLeader={}", memberState.getSelfId(), new Timestamp(lastLeaderHeartBeatTime), heartBeatTimeIntervalMs, memberState.getLeaderId());
                    changeRoleToCandidate(memberState.currTerm());
                }
            }
        }
    }

    /**
     * 向集群其他节点发起拉票请求
     * @param term 发起轮次
     * @param ledgerEndTerm 当前节点最后一条消息的轮次
     * @param ledgerEndIndex 当前节点最后一条消息的index值
     * @return
     * @throws Exception
     */
    private List<CompletableFuture<VoteResponse>> voteForQuorumResponses(long term, long ledgerEndTerm,
        long ledgerEndIndex) throws Exception {
        //对端响应拉票请求的异步操作对象列表
        List<CompletableFuture<VoteResponse>> responses = new ArrayList<>();
        for (String id : memberState.getPeerMap().keySet()) {//遍历集群下所有节点
            //实例化一个拉票请求
            VoteRequest voteRequest = new VoteRequest();

            //设置集群组名
            voteRequest.setGroup(memberState.getGroup());

            //设置最后一条消息的index值
            voteRequest.setLedgerEndIndex(ledgerEndIndex);

            //设置最后一条消息的轮次
            voteRequest.setLedgerEndTerm(ledgerEndTerm);

            //设置请求成为leaderId为节点的id
            voteRequest.setLeaderId(memberState.getSelfId());

            //设置发起请求的节点的轮次
            voteRequest.setTerm(term);

            //设置对端节点id
            voteRequest.setRemoteId(id);

            //对端响应拉票请求的异步操作对象
            CompletableFuture<VoteResponse> voteResponse;
            if (memberState.getSelfId().equals(id)) {
                //如果对端节点是自己 直接处理拉票请求
                voteResponse = handleVote(voteRequest, true);
            } else {
                //如果对端节点不是自己 向对端节点发起rpc请求
                voteResponse = dLedgerRpcService.vote(voteRequest);
            }

            //将对端响应的异步操作添加响应异步操作列表
            responses.add(voteResponse);

        }

        //返回响应列表
        return responses;
    }

    /**
     * 当前节点是否可以优先被设置为leader
     * @return
     */
    private boolean isTakingLeadership() {
        return memberState.getSelfId().equals(dLedgerConfig.getPreferredLeaderId())
            || memberState.getTermToTakeLeadership() == memberState.currTerm();
    }

    /**
     * 获取下一次发起投票的时间
     * @return
     */
    private long getNextTimeToRequestVote() {
        if (isTakingLeadership()) {
            return System.currentTimeMillis() + dLedgerConfig.getMinTakeLeadershipVoteIntervalMs() +
                random.nextInt(dLedgerConfig.getMaxTakeLeadershipVoteIntervalMs() - dLedgerConfig.getMinTakeLeadershipVoteIntervalMs());
        }

        //最小发起投票时间和最大发起投票时间之间随机一个值
        return System.currentTimeMillis() + minVoteIntervalMs + random.nextInt(maxVoteIntervalMs - minVoteIntervalMs);
    }

    /**
     * 节点状态流转：当节点的状态为Candidate时
     * @throws Exception
     */
    private void maintainAsCandidate() throws Exception {
        //节点的状态为Candidata 当前时间小于下一次发起选举的时间 定时器时间还未到 并且不需要立刻发起投票 不需要发起投票
        if (System.currentTimeMillis() < nextTimeToRequestVote && !needIncreaseTermImmediately) {
            return;
        }

        //投票轮次
        long term;

        //leader节点投票的轮次
        long ledgerEndTerm;

        //节点最后一条消息的index值
        long ledgerEndIndex;
        synchronized (memberState) {//将节点状态加锁
            if (!memberState.isCandidate()) {//如果节点状态不是Candidate 直接返回
                return;
            }

            //如果状态为等待下一个投票 或者立刻需要投票
            if (lastParseResult == VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT || needIncreaseTermImmediately) {
                //获取上一次投票的轮次
                long prevTerm = memberState.currTerm();
                //将状态机的选举轮次+1
                term = memberState.nextTerm();
                logger.info("{}_[INCREASE_TERM] from {} to {}", memberState.getSelfId(), prevTerm, term);
                //将选举状态设置为等待选举
                lastParseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            } else {
                //获取当前投票轮次
                term = memberState.currTerm();
            }

            //获取当前日志的最大序列
            ledgerEndIndex = memberState.getLedgerEndIndex();
            //获取leader节点投票的轮次
            ledgerEndTerm = memberState.getLedgerEndTerm();
        }
        if (needIncreaseTermImmediately) {//需要立刻发起投票
            //设置下一次发起投票的时间
            nextTimeToRequestVote = getNextTimeToRequestVote();
            //设置立刻不需要立刻发起投票
            needIncreaseTermImmediately = false;
            return;
        }

        //投票时间到 执行投票的逻辑
        long startVoteTimeMs = System.currentTimeMillis();

        //向集群内其他节点发起投票请求 返回投票结果列表
        final List<CompletableFuture<VoteResponse>> quorumVoteResponses = voteForQuorumResponses(term, ledgerEndTerm, ledgerEndIndex);

        //已经知道最大投票轮数
        final AtomicLong knownMaxTermInGroup = new AtomicLong(term);
        //所有投票的票数
        final AtomicInteger allNum = new AtomicInteger(0);
        //有效的投票的票数（如果某个节点操作投票请求抛出异常 视为无效的票）
        final AtomicInteger validNum = new AtomicInteger(0);
        //支持本次投票的对端节点数量
        final AtomicInteger acceptedNum = new AtomicInteger(0);
        //还没有准备好投票的对端节点数量
        final AtomicInteger notReadyTermNum = new AtomicInteger(0);
        //对端节点最后一条消息的轮次大于当前节点最后一条消息的轮次
        //或者对端节点最后一条消息的轮次等于当前节点最后一条消息的轮次 但是对端节点最后一条消息的index值比当前节点最后一条消息的index值大
        final AtomicInteger biggerLedgerNum = new AtomicInteger(0);
        //是否有对端节点已经有leader
        final AtomicBoolean alreadyHasLeader = new AtomicBoolean(false);
        //实例化一个计数器
        CountDownLatch voteLatch = new CountDownLatch(1);
        for (CompletableFuture<VoteResponse> future : quorumVoteResponses) {//遍历投票结果列表
            future.whenComplete((VoteResponse x, Throwable ex) -> {//如果投票的异步操作完成
                try {
                    if (ex != null) {//有异常 抛出异常
                        throw ex;
                    }
                    logger.info("[{}][GetVoteResponse] {}", memberState.getSelfId(), JSON.toJSONString(x));
                    if (x.getVoteResult() != VoteResponse.RESULT.UNKNOWN) {//获取投票结果 未知
                        //增加未知的票数
                        validNum.incrementAndGet();
                    }
                    synchronized (knownMaxTermInGroup) {//加锁最大投票轮数
                        switch (x.getVoteResult()) {//获取投票结果
                            case ACCEPT://对端节点同意本次投票
                                //增加接收本轮投票请求的次数
                                acceptedNum.incrementAndGet();
                                break;
                            case REJECT_ALREADY_VOTED://对端节点已经投过票了 投给了其他节点
                            case REJECT_TAKING_LEADERSHIP://拒绝 因为对端节点优先投给自己
                                break;
                            case REJECT_ALREADY_HAS_LEADER://对端节点有自己的leader
                                //存在有对端节点有leader
                                alreadyHasLeader.compareAndSet(false, true);
                                break;
                            case REJECT_TERM_SMALL_THAN_LEDGER://发起投票的节点最后一条消息的轮次小于对端节点最后一条消息的轮次
                            case REJECT_EXPIRED_VOTE_TERM://发起投票节点的状态机轮次小于对端节点状态机的轮次
                                //发起投票节点的轮次小于对端节点的轮次 或者小于leader节点的轮次
                                if (x.getTerm() > knownMaxTermInGroup.get()) {
                                    //设置已知对端的最大投票轮次
                                    knownMaxTermInGroup.set(x.getTerm());
                                }
                                break;
                            case REJECT_EXPIRED_LEDGER_TERM://发起投票节点的最后一条消息的小于对端节点最后一条消息的轮次
                            case REJECT_SMALL_LEDGER_END_INDEX://发起投票节点的最后一条消息的轮次与对端节点的最后一条消息的轮次相等，但是发起投票请求的节点的最后一条消息的index值 小于对端节点最后一条消息的index值
                                biggerLedgerNum.incrementAndGet();
                                break;
                            case REJECT_TERM_NOT_READY://发起请求的节点的状态机轮次大于对端节点的状态机轮次 对端节点需要将自己的角色改为候选人 并且立刻发起拉票请求
                                notReadyTermNum.incrementAndGet();
                                break;
                            default:
                                break;

                        }
                    }
                    if (alreadyHasLeader.get()//有对端节点有自己的leader节点
                        || memberState.isQuorum(acceptedNum.get())//已经选出了leader
                        || memberState.isQuorum(acceptedNum.get() + notReadyTermNum.get())) {//没有准备好的人 加已经投票的人超过了一半
                        //计数器减一
                        voteLatch.countDown();
                    }
                } catch (Throwable t) {
                    logger.error("vote response failed", t);
                } finally {
                    //增加总的投票次数
                    allNum.incrementAndGet();
                    if (allNum.get() == memberState.peerSize()) {//所有人都投完票 计数器减一
                        voteLatch.countDown();
                    }
                }
            });

        }

        try {
            //阻塞当前线程 等待投票完成 当计数器结果为0时 立刻执行
            voteLatch.await(2000 + random.nextInt(maxVoteIntervalMs), TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {

        }

        //投票完成

        //计算上一次投票消耗的时间
        lastVoteCost = DLedgerUtils.elapsed(startVoteTimeMs);

        //投票结果
        VoteResponse.ParseResult parseResult;
        if (knownMaxTermInGroup.get() > term) {//有对端节点的最后一条消息的轮次大于当前节点最后一条消息的轮次或者对端节点状态机的轮次大于当前节点状态机的轮次
            //继续等待下一次投票
            parseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
            //重置下一次投票发起的时间
            nextTimeToRequestVote = getNextTimeToRequestVote();
            //改变当前节点的状态为Candidate
            changeRoleToCandidate(knownMaxTermInGroup.get());
        } else if (alreadyHasLeader.get()) {//如果对端节点中至少有一个节点有leader
            //设置状态
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            //设置下一次发起投票的时间 等待n次向leader发起心跳失败后 再发起投票请求
            nextTimeToRequestVote = getNextTimeToRequestVote() + heartBeatTimeIntervalMs * maxHeartBeatLeak;
        } else if (!memberState.isQuorum(validNum.get())) {//没有超过一半的节点投票 继续发起下一次投票
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            nextTimeToRequestVote = getNextTimeToRequestVote();
        } else if (!memberState.isQuorum(validNum.get() - biggerLedgerNum.get())) {//投给其他人的节点的数量没有超过一半 当前节点还可以再继续下一轮拉票
            parseResult = VoteResponse.ParseResult.WAIT_TO_REVOTE;
            nextTimeToRequestVote = getNextTimeToRequestVote() + maxVoteIntervalMs;
        } else if (memberState.isQuorum(acceptedNum.get())) {//接受投票的人超过一半
            parseResult = VoteResponse.ParseResult.PASSED;
        } else if (memberState.isQuorum(acceptedNum.get() + notReadyTermNum.get())) {//没有准备好的人会立刻给自己投 投支持票 + 没有准备好的人超过一半 立刻发起下一轮投票
            parseResult = VoteResponse.ParseResult.REVOTE_IMMEDIATELY;
        } else {
            parseResult = VoteResponse.ParseResult.WAIT_TO_VOTE_NEXT;
            nextTimeToRequestVote = getNextTimeToRequestVote();
        }
        lastParseResult = parseResult;
        logger.info("[{}] [PARSE_VOTE_RESULT] cost={} term={} memberNum={} allNum={} acceptedNum={} notReadyTermNum={} biggerLedgerNum={} alreadyHasLeader={} maxTerm={} result={}",
            memberState.getSelfId(), lastVoteCost, term, memberState.peerSize(), allNum, acceptedNum, notReadyTermNum, biggerLedgerNum, alreadyHasLeader, knownMaxTermInGroup.get(), parseResult);

        if (parseResult == VoteResponse.ParseResult.PASSED) {//投票结果成功
            logger.info("[{}] [VOTE_RESULT] has been elected to be the leader in term {}", memberState.getSelfId(), term);
            changeRoleToLeader(term);
        }
    }

    /**
     * The core method of maintainer. Run the specified logic according to the current role: candidate => propose a
     * vote. leader => send heartbeats to followers, and step down to candidate when quorum followers do not respond.
     * follower => accept heartbeats, and change to candidate when no heartbeat from leader.
     *
     * @throws Exception
     */
    private void maintainState() throws Exception {
        if (memberState.isLeader()) {
            maintainAsLeader();
        } else if (memberState.isFollower()) {
            maintainAsFollower();
        } else {
            //节点的状态为Candidate
            maintainAsCandidate();
        }
    }

    /**
     * 处理角色变更
     * @param term 变更之后的选举轮次
     * @param role 角色
     */
    private void handleRoleChange(long term, MemberState.Role role) {
        try {
            takeLeadershipTask.check(term, role);
        } catch (Throwable t) {
            logger.error("takeLeadershipTask.check failed. ter={}, role={}", term, role, t);
        }

        for (RoleChangeHandler roleChangeHandler : roleChangeHandlers) {
            try {
                roleChangeHandler.handle(term, role);
            } catch (Throwable t) {
                logger.warn("Handle role change failed term={} role={} handler={}", term, role, roleChangeHandler.getClass(), t);
            }
        }
    }

    public void addRoleChangeHandler(RoleChangeHandler roleChangeHandler) {
        if (!roleChangeHandlers.contains(roleChangeHandler)) {
            roleChangeHandlers.add(roleChangeHandler);
        }
    }

    public CompletableFuture<LeadershipTransferResponse> handleLeadershipTransfer(
        LeadershipTransferRequest request) throws Exception {
        logger.info("handleLeadershipTransfer: {}", request);
        synchronized (memberState) {
            if (memberState.currTerm() != request.getTerm()) {
                logger.warn("[BUG] [HandleLeaderTransfer] currTerm={} != request.term={}", memberState.currTerm(), request.getTerm());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.INCONSISTENT_TERM.getCode()));
            }

            if (!memberState.isLeader()) {
                logger.warn("[BUG] [HandleLeaderTransfer] selfId={} is not leader", request.getLeaderId());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.NOT_LEADER.getCode()));
            }

            if (memberState.getTransferee() != null) {
                logger.warn("[BUG] [HandleLeaderTransfer] transferee={} is already set", memberState.getTransferee());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.LEADER_TRANSFERRING.getCode()));
            }

            memberState.setTransferee(request.getTransfereeId());
        }
        LeadershipTransferRequest takeLeadershipRequest = new LeadershipTransferRequest();
        takeLeadershipRequest.setGroup(memberState.getGroup());
        takeLeadershipRequest.setLeaderId(memberState.getLeaderId());
        takeLeadershipRequest.setLocalId(memberState.getSelfId());
        takeLeadershipRequest.setRemoteId(request.getTransfereeId());
        takeLeadershipRequest.setTerm(request.getTerm());
        takeLeadershipRequest.setTakeLeadershipLedgerIndex(memberState.getLedgerEndIndex());
        takeLeadershipRequest.setTransferId(memberState.getSelfId());
        takeLeadershipRequest.setTransfereeId(request.getTransfereeId());
        if (memberState.currTerm() != request.getTerm()) {
            logger.warn("[HandleLeaderTransfer] term changed, cur={} , request={}", memberState.currTerm(), request.getTerm());
            return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.EXPIRED_TERM.getCode()));
        }

        return dLedgerRpcService.leadershipTransfer(takeLeadershipRequest).thenApply(response -> {
            synchronized (memberState) {
                if (response.getCode() != DLedgerResponseCode.SUCCESS.getCode() ||
                    (memberState.currTerm() == request.getTerm() && memberState.getTransferee() != null)) {
                    logger.warn("leadershipTransfer failed, set transferee to null");
                    memberState.setTransferee(null);
                }
            }
            return response;
        });
    }

    public CompletableFuture<LeadershipTransferResponse> handleTakeLeadership(
        LeadershipTransferRequest request) throws Exception {
        logger.debug("handleTakeLeadership.request={}", request);
        synchronized (memberState) {
            if (memberState.currTerm() != request.getTerm()) {
                logger.warn("[BUG] [handleTakeLeadership] currTerm={} != request.term={}", memberState.currTerm(), request.getTerm());
                return CompletableFuture.completedFuture(new LeadershipTransferResponse().term(memberState.currTerm()).code(DLedgerResponseCode.INCONSISTENT_TERM.getCode()));
            }

            long targetTerm = request.getTerm() + 1;
            memberState.setTermToTakeLeadership(targetTerm);
            CompletableFuture<LeadershipTransferResponse> response = new CompletableFuture<>();
            takeLeadershipTask.update(request, response);
            changeRoleToCandidate(targetTerm);
            needIncreaseTermImmediately = true;
            return response;
        }
    }

    private class TakeLeadershipTask {
        private LeadershipTransferRequest request;
        private CompletableFuture<LeadershipTransferResponse> responseFuture;

        public synchronized void update(LeadershipTransferRequest request,
            CompletableFuture<LeadershipTransferResponse> responseFuture) {
            this.request = request;
            this.responseFuture = responseFuture;
        }

        /**
         * 节点角色变更检查
         * @param term 变更之后的轮次
         * @param role 变更之后的角色
         */
        public synchronized void check(long term, MemberState.Role role) {
            logger.trace("TakeLeadershipTask called, term={}, role={}", term, role);
            if (memberState.getTermToTakeLeadership() == -1 || responseFuture == null) {
                return;
            }
            LeadershipTransferResponse response = null;
            if (term > memberState.getTermToTakeLeadership()) {//变更后的轮次大于领导力轮次 过期的轮次
                response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.EXPIRED_TERM.getCode());
            } else if (term == memberState.getTermToTakeLeadership()) {
                switch (role) {
                    case LEADER:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.SUCCESS.getCode());
                        break;
                    case FOLLOWER:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.TAKE_LEADERSHIP_FAILED.getCode());
                        break;
                    default:
                        return;
                }
            } else {
                switch (role) {
                    /*
                     * The node may receive heartbeat before term increase as a candidate,
                     * then it will be follower and term < TermToTakeLeadership
                     */
                    case FOLLOWER:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.TAKE_LEADERSHIP_FAILED.getCode());
                        break;
                    default:
                        response = new LeadershipTransferResponse().term(term).code(DLedgerResponseCode.INTERNAL_ERROR.getCode());
                }
            }

            //设置完成
            responseFuture.complete(response);
            logger.info("TakeLeadershipTask finished. request={}, response={}, term={}, role={}", request, response, term, role);
            memberState.setTermToTakeLeadership(-1);
            responseFuture = null;
            request = null;
        }
    }

    public interface RoleChangeHandler {
        void handle(long term, MemberState.Role role);

        void startup();

        void shutdown();
    }

    /**
     * 状态机管理器类
     */
    public class StateMaintainer extends ShutdownAbleThread {

        /**
         * 实例化一个状态机管理器
         * @param name 管理器名
         * @param logger 日志
         */
        public StateMaintainer(String name, Logger logger) {
            super(name, logger);
        }

        @Override public void doWork() {
            try {
                if (DLedgerLeaderElector.this.dLedgerConfig.isEnableLeaderElector()) {//允许开启选举选取实现器
                    //更新时间周期
                    DLedgerLeaderElector.this.refreshIntervals(dLedgerConfig);

                    //节点状态流转
                    DLedgerLeaderElector.this.maintainState();
                }
                sleep(10);
            } catch (Throwable t) {
                DLedgerLeaderElector.logger.error("Error in heartbeat", t);
            }
        }

    }
}
