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

package io.openmessaging.storage.dledger.entry;

import java.nio.ByteBuffer;

public class DLedgerEntryCoder {

    public static void encode(DLedgerEntry entry, ByteBuffer byteBuffer) {
        byteBuffer.clear();
        int size = entry.computSizeInBytes();
        //always put magic on the first position
        byteBuffer.putInt(entry.getMagic());
        byteBuffer.putInt(size);
        byteBuffer.putLong(entry.getIndex());
        byteBuffer.putLong(entry.getTerm());
        byteBuffer.putLong(entry.getPos());
        byteBuffer.putInt(entry.getChannel());
        byteBuffer.putInt(entry.getChainCrc());
        byteBuffer.putInt(entry.getBodyCrc());
        byteBuffer.putInt(entry.getBody().length);
        byteBuffer.put(entry.getBody());
        byteBuffer.flip();
    }

    /**
     * 将消息的索引写入到某个bytebuffer对象
     * @param pos 消息的偏移量
     * @param size 消息大小所占字节数
     * @param magic 消息的模数
     * @param index 消息的下标
     * @param term 消息的轮次
     * @param byteBuffer 目标bytebuffer对象
     */
    public static void encodeIndex(long pos, int size, int magic, long index, long term, ByteBuffer byteBuffer) {
        //清理目标bytebuffer对象
        byteBuffer.clear();
        //写入模数
        byteBuffer.putInt(magic);
        //写入消息的偏移量
        byteBuffer.putLong(pos);
        //写入消息所占的字节数
        byteBuffer.putInt(size);
        //写入消息的index值
        byteBuffer.putLong(index);
        //写入消息的轮次
        byteBuffer.putLong(term);
        //将bytebuffer由写改为读模式
        byteBuffer.flip();
    }

    /**
     * 解码消息实体
     * @param byteBuffer 存储消息信息的缓存区
     * @return
     */
    public static DLedgerEntry decode(ByteBuffer byteBuffer) {
        return decode(byteBuffer, true);
    }

    /**
     * 解码缓存区字节数组为消息实体
     * @param byteBuffer 缓存区
     * @param readBody 是否将消息体也读取
     * @return
     */
    public static DLedgerEntry decode(ByteBuffer byteBuffer, boolean readBody) {
        //实例化一个消息实体
        DLedgerEntry entry = new DLedgerEntry();
        //设置消息的模数
        entry.setMagic(byteBuffer.getInt());
        //设置消息所占的字节数
        entry.setSize(byteBuffer.getInt());
        //设置消息的索引值
        entry.setIndex(byteBuffer.getLong());
        //设置消息的轮数
        entry.setTerm(byteBuffer.getLong());
        //设置消息的偏移量
        entry.setPos(byteBuffer.getLong());
        //设置消息的通道
        entry.setChannel(byteBuffer.getInt());
        //设置消息链 crc值
        entry.setChainCrc(byteBuffer.getInt());
        //设置消息体的crc值
        entry.setBodyCrc(byteBuffer.getInt());
        //获取消息体大小
        int bodySize = byteBuffer.getInt();
        if (readBody && bodySize < entry.getSize()) {
            //获取消息体
            byte[] body = new byte[bodySize];
            byteBuffer.get(body);
            entry.setBody(body);
        }
        return entry;
    }

    public static void setPos(ByteBuffer byteBuffer, long pos) {
        byteBuffer.mark();
        byteBuffer.position(byteBuffer.position() + DLedgerEntry.POS_OFFSET);
        byteBuffer.putLong(pos);
        byteBuffer.reset();
    }

    public static long getPos(ByteBuffer byteBuffer) {
        long pos;
        byteBuffer.mark();
        byteBuffer.position(byteBuffer.position() + DLedgerEntry.POS_OFFSET);
        pos = byteBuffer.getLong();
        byteBuffer.reset();
        return pos;
    }

    public static void setIndexTerm(ByteBuffer byteBuffer, long index, long term, int magic) {
        byteBuffer.mark();
        byteBuffer.putInt(magic);
        byteBuffer.position(byteBuffer.position() + 4);
        byteBuffer.putLong(index);
        byteBuffer.putLong(term);
        byteBuffer.reset();
    }

}
