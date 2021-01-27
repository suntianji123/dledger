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

import java.nio.ByteBuffer;

/**
 * 获取mappedFile映射的byteBuffer缓存区中指定起始位置开始的一段bytebuffer结果类
 */
public class SelectMmapBufferResult {

    /**
     * 截取的位置在mappedFile List文件系统中的起始偏移量
     */
    private final long startOffset;

    /**
     * 结果bytebuffer
     */
    private final ByteBuffer byteBuffer;

    /**
     * mappedFile对象
     */
    protected MmapFile mappedFile;

    /**
     * 截取的byteBuffer的字节数
     */
    private int size;

    /**
     * 实例化一个选择bytebuffer结果
     * @param startOffset 截取的bytebuffer起始位置在mappedFile list文件系统中的起始偏移量
     * @param byteBuffer 截取的结果bytebuffer
     * @param size bytebuffer中有消息的字节数
     * @param mappedFile 截取的bytebuffer来源的mappedFile对象
     */
    public SelectMmapBufferResult(long startOffset, ByteBuffer byteBuffer, int size, MmapFile mappedFile) {
        //设置截取的bytebuffer起始位置在mappedFile list文件系统中的起始偏移量
        this.startOffset = startOffset;
        //设置结果bytebuffer镀锡
        this.byteBuffer = byteBuffer;
        //设置截取的字节数
        this.size = size;
        //设置截取的bytebuffer来源的mappedFile对象
        this.mappedFile = mappedFile;
    }

    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    public int getSize() {
        return size;
    }

    public void setSize(final int s) {
        this.size = s;
        this.byteBuffer.limit(this.size);
    }

    public MmapFile getMappedFile() {
        return mappedFile;
    }

    /**
     * 释放选择的bytebuffer结果
     */
    public synchronized void release() {
        if (this.mappedFile != null) {
            //释放当前线程对mappedFile的引用
            this.mappedFile.release();
            this.mappedFile = null;
        }
    }

    public long getStartOffset() {
        return startOffset;
    }

    /**
     * 释放mappedFile的选择结果
     * @param sbr 选择结果对象
     */
    public static void release(SelectMmapBufferResult sbr) {
        if (sbr != null) {
            sbr.release();
        }
    }
}
