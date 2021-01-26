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

public class VoteResponse extends RequestOrResponse {

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
        REJECT_UNKNOWN_LEADER,
        REJECT_UNEXPECTED_LEADER,

        /**
         * 拒绝，发起投票节点维护的投票轮次小于对端节点的维护的投票轮次，更新自己的投票轮次
         */
        REJECT_EXPIRED_VOTE_TERM,

        /**
         * 拒绝投票，原因已经投过票了
         */
        REJECT_ALREADY_VOTED,

        /**
         * 拒绝原因是在集群中已经有leader了
         */
        REJECT_ALREADY_HAS_LEADER,

        /**
         * 拒绝，对端节点的投票轮次小于发起投票节点的投票轮次，对端节点使用发起节点的投票轮次进入Candidata状态
         */
        REJECT_TERM_NOT_READY,


        /**
         * 拒绝，发起投票节点维护的投票轮次小于对端leader节点的维护的投票轮次
         */
        REJECT_TERM_SMALL_THAN_LEDGER,

        /**
         * 拒绝，发起投票节点的leader维护的投票轮次小于对端节点leader的维护的投票轮次
         */
        REJECT_EXPIRED_LEDGER_TERM,

        /**
         * 拒绝，发起投票节点的投票轮次与对端节点的投票轮次相同，但是发起投票节点的同步的日志偏移量小于对端的日志偏移量
         */
        REJECT_SMALL_LEDGER_END_INDEX,

        REJECT_TAKING_LEADERSHIP;
    }

    /**
     * 上一次投票的结果枚举
     */
    public enum ParseResult {
        /**
         * 等待投票
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
         * 等待下一个投票
         */
        WAIT_TO_VOTE_NEXT;
    }
}
