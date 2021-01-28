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

package io.openmessaging.storage.dledger.protocol;

import static io.openmessaging.storage.dledger.protocol.VoteResponse.RESULT.UNKNOWN;

/**
 * 拉票响应类
 */
public class VoteResponse extends RequestOrResponse {

    /**
     * 拉票结果枚举
     */
    public RESULT voteResult = UNKNOWN;

    public VoteResponse() {

    }

    public VoteResponse(VoteRequest request) {
        copyBaseInfo(request);
    }

    public RESULT getVoteResult() {
        return voteResult;
    }

    public void setVoteResult(RESULT voteResult) {
        this.voteResult = voteResult;
    }

    public VoteResponse voteResult(RESULT voteResult) {
        this.voteResult = voteResult;
        return this;
    }

    public VoteResponse term(long term) {
        this.term = term;
        return this;
    }

    /**
     * 投票节点响应的节点
     */
    public enum RESULT {

        /**
         * 未知
         */
        UNKNOWN,//未知

        /**
         * 接受
         */
        ACCEPT,

        /**
         * 拒绝处理，投票成为leader的节点id 不是这个集群下的成员
         */
        REJECT_UNKNOWN_LEADER,

        /**
         *拒绝处理，不是自己投给自己，但是投票的目标节点id与请求的节点id相等
         */
        REJECT_UNEXPECTED_LEADER,

        /**
         * 拒绝，发起请求的节点的状态机轮次小于对端节点状态机的轮次
         */
        REJECT_EXPIRED_VOTE_TERM,

        /**
         * 拒绝投票，原因已经投过票了
         */
        REJECT_ALREADY_VOTED,

        /**
         * 拒绝原因是对端节点在集群中已经有leader了
         */
        REJECT_ALREADY_HAS_LEADER,

        /**
         * 拒绝，发起请求的节点的状态机轮次大于对端节点的状态机轮次 对端节点需要将自己的角色改为候选人 并且立刻发起拉票请求
         */
        REJECT_TERM_NOT_READY,


        /**
         * 拒绝，发起投票节点的最后一条消息的轮次小于对端节点的最后一条的轮次
         */
        REJECT_TERM_SMALL_THAN_LEDGER,

        /**
         * 拒绝，发起投票节点的最后一条消息的小于对端节点最后一条消息的轮次
         */
        REJECT_EXPIRED_LEDGER_TERM,

        /**
         *拒绝，发起投票节点的最后一条消息的轮次与对端节点的最后一条消息的轮次相等，但是发起投票请求的节点的最后一条消息的index值 小于对端节点最后一条消息的index值
         */
        REJECT_SMALL_LEDGER_END_INDEX,

        /**
         * 拒绝，对端节点配置了优先被设置为leader
         */
        REJECT_TAKING_LEADERSHIP;
    }

    /**
     * 上一次投票的结果枚举
     */
    public enum ParseResult {
        /**
         * 等待投票 不需要将term + 1
         */
        WAIT_TO_REVOTE,

        /**
         * 立刻取消
         */
        REVOTE_IMMEDIATELY,

        /**
         * 通过
         */
        PASSED,

        /**
         * 等待下一个投票 需要将term + 1
         */
        WAIT_TO_VOTE_NEXT;
    }
}
