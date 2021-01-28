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

/**
 * 请求或者响应类
 */
public class RequestOrResponse {

    /**
     * 集群组名
     */
    protected String group;

    /**
     * 对端节点id
     */
    protected String remoteId;

    /**
     * 本地id
     */
    protected String localId;

    /**
     * 响应码
     */
    protected int code = DLedgerResponseCode.SUCCESS.getCode();

    /**
     * leaderid
     */
    protected String leaderId = null;

    /**
     * 状态机或者最后一条消息的轮次
     */
    protected long term = -1;

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public RequestOrResponse code(int code) {
        this.code = code;
        return this;
    }

    public void setIds(String localId, String remoteId, String leaderId) {
        this.localId = localId;
        this.remoteId = remoteId;
        this.leaderId = leaderId;
    }

    public String getRemoteId() {
        return remoteId;
    }

    public void setRemoteId(String remoteId) {
        this.remoteId = remoteId;
    }

    public String getLocalId() {
        return localId;
    }

    public void setLocalId(String localId) {
        this.localId = localId;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }

    /**
     * 拷贝请求中的信息
     * @param other 氢气
     * @return
     */
    public RequestOrResponse copyBaseInfo(RequestOrResponse other) {
        //设置集群名
        this.group = other.group;
        //设置轮次
        this.term = other.term;
        //设置请求码
        this.code = other.code;
        //设置本地节点id
        this.localId = other.localId;
        //设置远程节点id
        this.remoteId = other.remoteId;
        //设置leaderid
        this.leaderId = other.leaderId;
        return this;
    }

    public String baseInfo() {
        return String.format("info[group=%s,term=%d,code=%d,local=%s,remote=%s,leader=%s]", group, term, code, localId, remoteId, leaderId);
    }

}
