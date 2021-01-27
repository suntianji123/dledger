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

package io.openmessaging.storage.dledger.store.file;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 引用资源抽象类
 */
public abstract class ReferenceResource {
    /**
     * 引用计数
     */
    protected final AtomicLong refCount = new AtomicLong(1);

    /**
     * 资源是否可用
     */
    protected volatile boolean available = true;

    /**
     * 资源是否清理结束
     */
    protected volatile boolean cleanupOver = false;

    /**
     * 第一次关闭的时间戳
     */
    private volatile long firstShutdownTimestamp = 0;

    public synchronized boolean hold() {
        if (this.isAvailable()) {
            if (this.refCount.getAndIncrement() > 0) {
                return true;
            } else {
                this.refCount.getAndDecrement();
            }
        }

        return false;
    }

    public boolean isAvailable() {
        return this.available;
    }

    /**
     * 释放引用
     * @param intervalForcibly
     */
    public void shutdown(final long intervalForcibly) {
        if (this.available) {//如果资源可用
            //设置资源不可用
            this.available = false;

            //设置第一次关闭的时间戳
            this.firstShutdownTimestamp = System.currentTimeMillis();

            //释放引用
            this.release();
        } else if (this.getRefCount() > 0) {//其他线程还在引用这个资源
            if ((System.currentTimeMillis() - this.firstShutdownTimestamp) >= intervalForcibly) {//超过了shutdown的周期
                //设置引用计算为负数
                this.refCount.set(-1000 - this.getRefCount());
                //释放资源
                this.release();
            }
        }
    }

    /**
     * 释放当前线程对资源的引用
     */
    public void release() {
        //获取引用技术局
        long value = this.refCount.decrementAndGet();
        if (value > 0)//还有其他线程引用资源 返回
            return;

        synchronized (this) {
            //清理资源
            this.cleanupOver = this.cleanup(value);
        }
    }

    public long getRefCount() {
        return this.refCount.get();
    }

    public abstract boolean cleanup(final long currentRef);

    /**
     * 判断资源是否清理结束
     * @return
     */
    public boolean isCleanupOver() {
        //引用计数诶0 并且清理结束
        return this.refCount.get() <= 0 && this.cleanupOver;
    }
}
