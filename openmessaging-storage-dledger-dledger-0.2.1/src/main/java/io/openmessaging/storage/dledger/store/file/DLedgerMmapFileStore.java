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

import io.openmessaging.storage.dledger.DLedgerConfig;
import io.openmessaging.storage.dledger.MemberState;
import io.openmessaging.storage.dledger.ShutdownAbleThread;
import io.openmessaging.storage.dledger.entry.DLedgerEntry;
import io.openmessaging.storage.dledger.entry.DLedgerEntryCoder;
import io.openmessaging.storage.dledger.protocol.DLedgerResponseCode;
import io.openmessaging.storage.dledger.store.DLedgerStore;
import io.openmessaging.storage.dledger.utils.IOUtils;
import io.openmessaging.storage.dledger.utils.PreConditions;
import io.openmessaging.storage.dledger.utils.DLedgerUtils;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息存储在文件的存储类
 */
public class DLedgerMmapFileStore extends DLedgerStore {

    public static final String CHECK_POINT_FILE = "checkpoint";
    public static final String END_INDEX_KEY = "endIndex";
    public static final String COMMITTED_INDEX_KEY = "committedIndex";
    public static final int MAGIC_1 = 1;
    public static final int CURRENT_MAGIC = MAGIC_1;

    /**
     * 索引  mappedFile中每一个存储对象的大小
     */
    public static final int INDEX_UNIT_SIZE = 32;

    private static Logger logger = LoggerFactory.getLogger(DLedgerMmapFileStore.class);
    public List<AppendHook> appendHooks = new ArrayList<>();

    /**
     * 节点第一条消息的在索引信息 mappedFileList中的index值
     */
    private long ledgerBeginIndex = -1;

    /**
     * 节点的消息 mappedFile list文件系统中最后一条消息的index值
     */
    private long ledgerEndIndex = -1;

    /**
     * 当前节点已经提交到的索引值
     */
    private long committedIndex = -1;

    /**
     * 当前节点的提交到的位置 提交实体的偏移量 + 消息所占的字节数
     */
    private long committedPos = -1;

    /**
     * 节点的消息 mappedFile list文件系统中最后一条消息的轮次
     */
    private long ledgerEndTerm;

    /**
     * 节点配置
     */
    private DLedgerConfig dLedgerConfig;

    /**
     * 节点状态机
     */
    private MemberState memberState;

    /**
     * mappedFileList对象
     */
    private MmapFileList dataFileList;

    /**
     * 索引文件List对象
     */
    private MmapFileList indexFileList;
    private ThreadLocal<ByteBuffer> localEntryBuffer;
    private ThreadLocal<ByteBuffer> localIndexBuffer;

    /**
     * 将消息 mappedFile list  / 索引 mappedFile list内存中的字节数组刷新到磁盘文件
     */
    private FlushDataService flushDataService;

    /**
     * 清理掉 data mappedFile list中过期的mappedFile 单次批量删除最多10个
     */
    private CleanSpaceService cleanSpaceService;
    private boolean isDiskFull = false;

    private long lastCheckPointTimeMs = System.currentTimeMillis();

    /**
     * 是否已经加载过本地磁盘已经存在的文件到mappedFileList
     */
    private AtomicBoolean hasLoaded = new AtomicBoolean(false);

    /**
     * 是否已经恢复过mappedFile文件
     */
    private AtomicBoolean hasRecovered = new AtomicBoolean(false);

    /**
     * 实例化一个消息存储类型为文件的存储对象
     * @param dLedgerConfig 节点配置
     * @param memberState 节点状态机
     */
    public DLedgerMmapFileStore(DLedgerConfig dLedgerConfig, MemberState memberState) {
        //设置节点配置
        this.dLedgerConfig = dLedgerConfig;
        //设置节点状态机
        this.memberState = memberState;

        //实例化消息mappedFileList对象
        this.dataFileList = new MmapFileList(dLedgerConfig.getDataStorePath(), dLedgerConfig.getMappedFileSizeForEntryData());

        //实例化化消息索引mappedFileList对象
        this.indexFileList = new MmapFileList(dLedgerConfig.getIndexStorePath(), dLedgerConfig.getMappedFileSizeForEntryIndex());

        //映射消息mappedFileList文件的缓存区 默认为4M
        localEntryBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocate(4 * 1024 * 1024));

        //映射消息索引mappedFileList文件的缓存区  默认为64K
        localIndexBuffer = ThreadLocal.withInitial(() -> ByteBuffer.allocate(INDEX_UNIT_SIZE * 2));

        //将消息/消息索引刷新到磁盘的服务
        flushDataService = new FlushDataService("DLedgerFlushDataService", logger);

        //清理mappedFileList文件中空格的服务
        cleanSpaceService = new CleanSpaceService("DLedgerCleanSpaceService", logger);
    }

    /**
     * 启动mappedFileList服务
     */
    public void startup() {
        //加载mappedFile父目录下的文件到mappedFileList内存
        load();

        //恢复文件
        recover();

        //启动刷新内存数据到磁盘文件的服务
        flushDataService.start();

        //启动清理过期的消息mappedFile服务
        cleanSpaceService.start();
    }

    public void shutdown() {
        this.dataFileList.flush(0);
        this.indexFileList.flush(0);
        persistCheckPoint();
        cleanSpaceService.shutdown();
        flushDataService.shutdown();
    }

    public long getWritePos() {
        return dataFileList.getMaxWrotePosition();
    }

    public long getFlushPos() {
        return dataFileList.getFlushedWhere();
    }

    public void flush() {
        this.dataFileList.flush(0);
        this.indexFileList.flush(0);
    }

    /**
     * 加载mappedFile父目录下的mappedFile到mappedFileList
     */
    public void load() {
        if (!hasLoaded.compareAndSet(false, true)) {//之前没有加载过 才可以加载
            return;
        }

        //首先加载消息文件到data mappedFileList 然后加载消息索引文件到index mappedFileList
        if (!this.dataFileList.load() || !this.indexFileList.load()) {
            logger.error("Load file failed, this usually indicates fatal error, you should check it manually");
            System.exit(-1);
        }
    }

    /**
     * 恢复mappedFile文件
     */
    public void recover() {
        if (!hasRecovered.compareAndSet(false, true)) {//如果已经恢复过mappedFile文件，直接返回
            return;
        }

        //检查存储消息的mappedFile列表中的mappedFile起始偏移量是否连续
        PreConditions.check(dataFileList.checkSelf(), DLedgerResponseCode.DISK_ERROR, "check data file order failed before recovery");

        //检查存储索引的mappedFile列表中的mappedFile起始偏移量是否连续
        PreConditions.check(indexFileList.checkSelf(), DLedgerResponseCode.DISK_ERROR, "check index file order failed before recovery");

        //获取存储消息的mappedFile列表
        final List<MmapFile> mappedFiles = this.dataFileList.getMappedFiles();
        if (mappedFiles.isEmpty()) {//不存在存储消息的mappedFile
            //设置索引文件刷新的起始、提交的起始位置为0
            this.indexFileList.updateWherePosition(0);
            //删除偏移量为0之后的所有的index mappedFile文件
            this.indexFileList.truncateOffset(0);
            return;
        }

        //消息 mappedFileList不为null  获取最后一个mappedFile
        MmapFile lastMappedFile = dataFileList.getLastMappedFile();

        //最近三个mappedFile文件的起始下标
        int index = mappedFiles.size() - 3;
        if (index < 0) {//总的mappedFile文件数量不足三个 起始下标为0
            index = 0;
        }

        long firstEntryIndex = -1;
        for (int i = index; i >= 0; i--) {//从最后一个mappedFile开始 往前遍历3个mappedFile文件
            //检查每个消息 mappedFile中存放的消息的位置信息与索引 mappedFile中对应位置的索引信息记录的信息相同

            //当前mappedFile文件下标
            index = i;

            //获取mappedFile文件
            MmapFile mappedFile = mappedFiles.get(index);
            //获取mappedFile的映射缓冲区
            ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
            try {
                //起始偏移量
                long startPos = mappedFile.getFileFromOffset();
                //模数
                int magic = byteBuffer.getInt();
                //获取消息所占的总的字节数
                int size = byteBuffer.getInt();
                //消息实体的在index mappedFile List系统中的索引下标
                long entryIndex = byteBuffer.getLong();
                //消息所处的选举轮次
                long entryTerm = byteBuffer.getLong();
                //消息在mappedFile List系统的起始偏移量
                long pos = byteBuffer.getLong();

                //获取通道
                byteBuffer.getInt(); //channel

                //获取区块链crc值
                byteBuffer.getInt(); //chain crc

                //获取消息体的crc值
                byteBuffer.getInt(); //body crc

                //获取消息体的字节数
                int bodySize = byteBuffer.getInt();

                //校验消息的模数
                PreConditions.check(magic != MmapFileList.BLANK_MAGIC_CODE && magic >= MAGIC_1 && MAGIC_1 <= CURRENT_MAGIC, DLedgerResponseCode.DISK_ERROR, "unknown magic=%d", magic);

                //消息所占的字节数必须大于消息头的字节数
                PreConditions.check(size > DLedgerEntry.HEADER_SIZE, DLedgerResponseCode.DISK_ERROR, "Size %d should > %d", size, DLedgerEntry.HEADER_SIZE);

                //检查消息的位置参数
                PreConditions.check(pos == startPos, DLedgerResponseCode.DISK_ERROR, "pos %d != %d", pos, startPos);

                //消息体占的字节数 + 消息体的偏移量必须等于消息所占的总的字节数
                PreConditions.check(bodySize + DLedgerEntry.BODY_OFFSET == size, DLedgerResponseCode.DISK_ERROR, "size %d != %d + %d", size, bodySize, DLedgerEntry.BODY_OFFSET);

                //获取存储消息的mappedFile的第一条消息在索引 mappedFile中存放的byteBuffer信息
                SelectMmapBufferResult indexSbr = indexFileList.getData(entryIndex * INDEX_UNIT_SIZE);

                //存在索引信息
                PreConditions.check(indexSbr != null, DLedgerResponseCode.DISK_ERROR, "index=%d pos=%d", entryIndex, entryIndex * INDEX_UNIT_SIZE);

                //释放对索引mappedFile的引用
                indexSbr.release();

                //获取结果bytebuffer
                ByteBuffer indexByteBuffer = indexSbr.getByteBuffer();

                //索引信息中存放的消息模数值
                int magicFromIndex = indexByteBuffer.getInt();

                //索引信息中存放的消息偏移量
                long posFromIndex = indexByteBuffer.getLong();

                //索引信息中存放的消息的所占字节数
                int sizeFromIndex = indexByteBuffer.getInt();

                //索引信息中存放的消息的在index mappedFile LIst系统中的下标
                long indexFromIndex = indexByteBuffer.getLong();

                //索引信息中存放的消息的集群选举轮次
                long termFromIndex = indexByteBuffer.getLong();

                //消息中的模数值与索引信息中的模数值必须相等
                PreConditions.check(magic == magicFromIndex, DLedgerResponseCode.DISK_ERROR, "magic %d != %d", magic, magicFromIndex);

                //消息中消息所占的字节数必须与索引信息中消息所占的字节数相等
                PreConditions.check(size == sizeFromIndex, DLedgerResponseCode.DISK_ERROR, "size %d != %d", size, sizeFromIndex);

                //消息中消息在索引 mappedFile list中的下标 必须与索引信息中消息的index值相等
                PreConditions.check(entryIndex == indexFromIndex, DLedgerResponseCode.DISK_ERROR, "index %d != %d", entryIndex, indexFromIndex);

                //消息的轮次必须与索引信息中的轮次相等
                PreConditions.check(entryTerm == termFromIndex, DLedgerResponseCode.DISK_ERROR, "term %d != %d", entryTerm, termFromIndex);

                //消息中的偏移量必须与索引信息中的偏移量相等
                PreConditions.check(posFromIndex == mappedFile.getFileFromOffset(), DLedgerResponseCode.DISK_ERROR, "pos %d != %d", mappedFile.getFileFromOffset(), posFromIndex);
                firstEntryIndex = entryIndex;
                break;
            } catch (Throwable t) {
                logger.warn("Pre check data and index failed {}", mappedFile.getFileName(), t);
            }
        }

        //获取倒数第3个mappedFile文件
        MmapFile mappedFile = mappedFiles.get(index);

        //获取mappedFile 映射的bytebuffer对象
        ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
        logger.info("Begin to recover data from entryIndex={} fileIndex={} fileSize={} fileName={} ", firstEntryIndex, index, mappedFiles.size(), mappedFile.getFileName());

        //遍历的mappedFile中当前消息的上一条消息在 index mappedFile List系统中的位置
        long lastEntryIndex = -1;

        //遍历的mappedFile中当前消息的上一条消息在轮次
        long lastEntryTerm = -1;

        //处理的偏移量
        long processOffset = mappedFile.getFileFromOffset();

        //是否需要将消息的索引信息添加到index mappedFile list文件系统
        boolean needWriteIndex = false;

        //恢复3G内的消息的索引mappedFile
        while (true) {
            try {
                //当前bytebuffer读到的位置
                int relativePos = byteBuffer.position();
                //消息的起始偏移量
                long absolutePos = mappedFile.getFileFromOffset() + relativePos;
                //获取消息的模数
                int magic = byteBuffer.getInt();



                if (magic == MmapFileList.BLANK_MAGIC_CODE) {//空白模数 当

                    // 前mappedFile之后没有消息
                    //将偏移量 移动到下一个mappedFile的第一个消息的起始偏移量
                    processOffset = mappedFile.getFileFromOffset() + mappedFile.getFileSize();
                    //下一个mappedFile文件的索引
                    index++;
                    if (index >= mappedFiles.size()) {//当亲已经是最后一个mappedFile
                        logger.info("Recover data file over, the last file {}", mappedFile.getFileName());
                        break;
                    } else {
                        //获取下一个mappedFile
                        mappedFile = mappedFiles.get(index);

                        //设置bytebuffer为下一个mappedFile映射的bytebuffer
                        byteBuffer = mappedFile.sliceByteBuffer();
                        //设置当前遍历到的消息的偏移量为下一个mappedFile文件的起始偏移量
                        processOffset = mappedFile.getFileFromOffset();
                        logger.info("Trying to recover data file {}", mappedFile.getFileName());
                        continue;
                    }
                }

                //获取消息大小
                int size = byteBuffer.getInt();

                //获取消息在index mappedFile List系统中中index位置
                long entryIndex = byteBuffer.getLong();

                //获取消息的轮次
                long entryTerm = byteBuffer.getLong();

                //获取消息在mapppedFile list中的偏移量
                long pos = byteBuffer.getLong();

                //获取消息的通道
                byteBuffer.getInt(); //channel

                //消息的区块链crc值
                byteBuffer.getInt(); //chain crc

                //消息体字节数组的crc值
                byteBuffer.getInt(); //body crc

                //消息体大小
                int bodySize = byteBuffer.getInt();

                //消息偏移量必须与mappedFile映射的bytebuffer中的位置相等
                PreConditions.check(pos == absolutePos, DLedgerResponseCode.DISK_ERROR, "pos %d != %d", pos, absolutePos);

                //消息体大小 + 消息体偏移量必须为消息总的字节数相等
                PreConditions.check(bodySize + DLedgerEntry.BODY_OFFSET == size, DLedgerResponseCode.DISK_ERROR, "size %d != %d + %d", size, bodySize, DLedgerEntry.BODY_OFFSET);

                //设置当前读到的位置
                byteBuffer.position(relativePos + size);

                //校验模数
                PreConditions.check(magic <= CURRENT_MAGIC && magic >= MAGIC_1, DLedgerResponseCode.DISK_ERROR, "pos=%d size=%d magic=%d index=%d term=%d currMagic=%d", absolutePos, size, magic, entryIndex, entryTerm, CURRENT_MAGIC);
                if (lastEntryIndex != -1) {
                    //当前消息在index mappedFile list系统的index值必须必上一条消息在index mappedFile list系统中的index值大1
                    PreConditions.check(entryIndex == lastEntryIndex + 1, DLedgerResponseCode.DISK_ERROR, "pos=%d size=%d magic=%d index=%d term=%d lastEntryIndex=%d", absolutePos, size, magic, entryIndex, entryTerm, lastEntryIndex);
                }

                //当前消息的轮次必须比上一条消息的轮次大一
                PreConditions.check(entryTerm >= lastEntryTerm, DLedgerResponseCode.DISK_ERROR, "pos=%d size=%d magic=%d index=%d term=%d lastEntryTerm=%d ", absolutePos, size, magic, entryIndex, entryTerm, lastEntryTerm);

                //消息的所占的字节数 必须大于消息头所占的字节数
                PreConditions.check(size > DLedgerEntry.HEADER_SIZE, DLedgerResponseCode.DISK_ERROR, "size %d should > %d", size, DLedgerEntry.HEADER_SIZE);
                if (!needWriteIndex) {
                    try {
                        //获取消息在index mappedFileList中结果bytebuffer结果对象
                        SelectMmapBufferResult indexSbr = indexFileList.getData(entryIndex * INDEX_UNIT_SIZE);
                        //结果bytebuffer不能为空
                        PreConditions.check(indexSbr != null, DLedgerResponseCode.DISK_ERROR, "index=%d pos=%d", entryIndex, entryIndex * INDEX_UNIT_SIZE);
                        //释放当前线程对index mappedFile的引用
                        indexSbr.release();
                        //获取截取的bytebuffer对象
                        ByteBuffer indexByteBuffer = indexSbr.getByteBuffer();
                        //获取索引信息记录的模数值
                        int magicFromIndex = indexByteBuffer.getInt();
                        //获取索引信息记录的偏移量值
                        long posFromIndex = indexByteBuffer.getLong();
                        //获取索引信息记录的消息所占的字节数
                        int sizeFromIndex = indexByteBuffer.getInt();
                        //获取索引信息记录的消息的index值
                        long indexFromIndex = indexByteBuffer.getLong();
                        //获取索引信息记录的消息的轮次
                        long termFromIndex = indexByteBuffer.getLong();

                        //检查消息的模数与索引信息记录的模数相等
                        PreConditions.check(magic == magicFromIndex, DLedgerResponseCode.DISK_ERROR, "magic %d != %d", magic, magicFromIndex);

                        //检查消息所占字节数与索引信息记录的消息所占的字节数相等
                        PreConditions.check(size == sizeFromIndex, DLedgerResponseCode.DISK_ERROR, "size %d != %d", size, sizeFromIndex);

                        //检查消息的index值与索引信息记录的index值相等
                        PreConditions.check(entryIndex == indexFromIndex, DLedgerResponseCode.DISK_ERROR, "index %d != %d", entryIndex, indexFromIndex);

                        //检查消息的轮次与索引信息记录的轮次相等
                        PreConditions.check(entryTerm == termFromIndex, DLedgerResponseCode.DISK_ERROR, "term %d != %d", entryTerm, termFromIndex);

                        //检查消息的偏移量与索引信息记录的偏移量相等
                        PreConditions.check(absolutePos == posFromIndex, DLedgerResponseCode.DISK_ERROR, "pos %d != %d", mappedFile.getFileFromOffset(), posFromIndex);
                    } catch (Throwable t) {
                        logger.warn("Compare data to index failed {}", mappedFile.getFileName(), t);
                        indexFileList.truncateOffset(entryIndex * INDEX_UNIT_SIZE);
                        if (indexFileList.getMaxWrotePosition() != entryIndex * INDEX_UNIT_SIZE) {
                            long truncateIndexOffset = entryIndex * INDEX_UNIT_SIZE;
                            logger.warn("[Recovery] rebuild for index wrotePos={} not equal to truncatePos={}", indexFileList.getMaxWrotePosition(), truncateIndexOffset);
                            PreConditions.check(indexFileList.rebuildWithPos(truncateIndexOffset), DLedgerResponseCode.DISK_ERROR, "rebuild index truncatePos=%d", truncateIndexOffset);
                        }

                        //发生异常 没有检查到消息的索引信息所在index mappedFile 需要将消息的索引信息记录到index mappedFile list
                        needWriteIndex = true;
                    }
                }
                if (needWriteIndex) {
                    //获取线程上下文的byteBuffer对象
                    ByteBuffer indexBuffer = localIndexBuffer.get();

                    //将消息的索引信息写入到indexbytebuffer
                    DLedgerEntryCoder.encodeIndex(absolutePos, size, magic, entryIndex, entryTerm, indexBuffer);

                    //将消息的索引信息写入到index mappedFileList文件系统 返回写入的索引信息在index mappedFile list中的起始偏移量
                    long indexPos = indexFileList.append(indexBuffer.array(), 0, indexBuffer.remaining(), false);

                    //写入索引消息的偏移量必须与消息的index值关联
                    PreConditions.check(indexPos == entryIndex * INDEX_UNIT_SIZE, DLedgerResponseCode.DISK_ERROR, "Write index failed index=%d", entryIndex);
                }

                //设置上一条消息的在indez mappedList系统中的index值
                lastEntryIndex = entryIndex;
                //设置上一条消息的轮次
                lastEntryTerm = entryTerm;
                //将消息的偏移量移动到下一个消息的起始偏移量
                processOffset += size;
            } catch (Throwable t) {
                logger.info("Recover data file to the end of {} ", mappedFile.getFileName(), t);
                break;
            }
        }
        logger.info("Recover data to the end entryIndex={} processOffset={} lastFileOffset={} cha={}",
            lastEntryIndex, processOffset, lastMappedFile.getFileFromOffset(), processOffset - lastMappedFile.getFileFromOffset());
        if (lastMappedFile.getFileFromOffset() - processOffset > lastMappedFile.getFileSize()) {
            logger.error("[MONITOR]The processOffset is too small, you should check it manually before truncating the data from {}", processOffset);
            System.exit(-1);
        }

        //设置节点最后一条消息的在消息 mappedFile list文件系统中的索引值
        ledgerEndIndex = lastEntryIndex;

        //奢姿节点最后一条消息的轮次
        ledgerEndTerm = lastEntryTerm;
        if (lastEntryIndex != -1) {//节点存在最后一条消息
            //获取最后一条消息
            DLedgerEntry entry = get(lastEntryIndex);
            //检测消息实体不为null
            PreConditions.check(entry != null, DLedgerResponseCode.DISK_ERROR, "recheck get null entry");
            //检测消息实体的索引值
            PreConditions.check(entry.getIndex() == lastEntryIndex, DLedgerResponseCode.DISK_ERROR, "recheck index %d != %d", entry.getIndex(), lastEntryIndex);

            //重新设置index mappedFlLE list文件系统的起始偏移量 删除之前的mappedFile文件
            reviseLedgerBeginIndex();
        }

        //设置已经刷新到的位置 已经写到的位置
        this.dataFileList.updateWherePosition(processOffset);

        //删除processOffset之后mappedfILE文件 设置最后一个mappedFILE已经刷新、写入、提交到的位置
        this.dataFileList.truncateOffset(processOffset);

        //索引mappedFile list写入到的位置
        long indexProcessOffset = (lastEntryIndex + 1) * INDEX_UNIT_SIZE;
        //设置索引mappedFile刷新、提交到的位置
        this.indexFileList.updateWherePosition(indexProcessOffset);
        //设置索引mappedFile list最后一个mappedFile的写入、刷新、提交的位置
        this.indexFileList.truncateOffset(indexProcessOffset);

        //更新节点状态机最后一条消息的索引值 轮次
        updateLedgerEndIndexAndTerm();

        //检查消息mappedFile list下所有的mappedFile连续
        PreConditions.check(dataFileList.checkSelf(), DLedgerResponseCode.DISK_ERROR, "check data file order failed after recovery");

        //检查所mappeFile lit下所有的mappedFile连续
        PreConditions.check(indexFileList.checkSelf(), DLedgerResponseCode.DISK_ERROR, "check index file order failed after recovery");
        //从磁盘文件中加载最后一条消息的index 提交索引值
        Properties properties = loadCheckPoint();
        if (properties == null || !properties.containsKey(COMMITTED_INDEX_KEY)) {
            return;
        }
        //获取提交索引值
        String committedIndexStr = String.valueOf(properties.get(COMMITTED_INDEX_KEY)).trim();
        if (committedIndexStr.length() <= 0) {
            return;
        }
        logger.info("Recover to get committed index={} from checkpoint", committedIndexStr);
        updateCommittedIndex(memberState.currTerm(), Long.valueOf(committedIndexStr));

        return;
    }

    /**
     * 重新设置第一条消息的索引值
     */
    private void reviseLedgerBeginIndex() {
        //获取第一个mappedFile
        MmapFile firstFile = dataFileList.getFirstMappedFile();
        //获取位置0的消息体体
        SelectMmapBufferResult sbr = firstFile.selectMappedBuffer(0);
        try {
            //获取bytebuffer对象
            ByteBuffer tmpBuffer = sbr.getByteBuffer();
            //设置读的位置
            tmpBuffer.position(firstFile.getStartPosition());
            //获取魔数
            tmpBuffer.getInt(); //magic
            //获取消息所占的字节数
            tmpBuffer.getInt(); //size
            //获取消息的索引位置
            ledgerBeginIndex = tmpBuffer.getLong();

            //删除起始偏移量之前的文件 重置起始偏移量
            indexFileList.resetOffset(ledgerBeginIndex * INDEX_UNIT_SIZE);
        } finally {
            //释放对第一个mappedFile文件的引用
            SelectMmapBufferResult.release(sbr);
        }

    }

    @Override
    public DLedgerEntry appendAsLeader(DLedgerEntry entry) {
        PreConditions.check(memberState.isLeader(), DLedgerResponseCode.NOT_LEADER);
        PreConditions.check(!isDiskFull, DLedgerResponseCode.DISK_FULL);
        ByteBuffer dataBuffer = localEntryBuffer.get();
        ByteBuffer indexBuffer = localIndexBuffer.get();
        DLedgerEntryCoder.encode(entry, dataBuffer);
        int entrySize = dataBuffer.remaining();
        synchronized (memberState) {
            PreConditions.check(memberState.isLeader(), DLedgerResponseCode.NOT_LEADER, null);
            PreConditions.check(memberState.getTransferee() == null, DLedgerResponseCode.LEADER_TRANSFERRING, null);
            long nextIndex = ledgerEndIndex + 1;
            entry.setIndex(nextIndex);
            entry.setTerm(memberState.currTerm());
            entry.setMagic(CURRENT_MAGIC);
            DLedgerEntryCoder.setIndexTerm(dataBuffer, nextIndex, memberState.currTerm(), CURRENT_MAGIC);
            long prePos = dataFileList.preAppend(dataBuffer.remaining());
            entry.setPos(prePos);
            PreConditions.check(prePos != -1, DLedgerResponseCode.DISK_ERROR, null);
            DLedgerEntryCoder.setPos(dataBuffer, prePos);
            for (AppendHook writeHook : appendHooks) {
                writeHook.doHook(entry, dataBuffer.slice(), DLedgerEntry.BODY_OFFSET);
            }
            long dataPos = dataFileList.append(dataBuffer.array(), 0, dataBuffer.remaining());
            PreConditions.check(dataPos != -1, DLedgerResponseCode.DISK_ERROR, null);
            PreConditions.check(dataPos == prePos, DLedgerResponseCode.DISK_ERROR, null);
            DLedgerEntryCoder.encodeIndex(dataPos, entrySize, CURRENT_MAGIC, nextIndex, memberState.currTerm(), indexBuffer);
            long indexPos = indexFileList.append(indexBuffer.array(), 0, indexBuffer.remaining(), false);
            PreConditions.check(indexPos == entry.getIndex() * INDEX_UNIT_SIZE, DLedgerResponseCode.DISK_ERROR, null);
            if (logger.isDebugEnabled()) {
                logger.info("[{}] Append as Leader {} {}", memberState.getSelfId(), entry.getIndex(), entry.getBody().length);
            }
            ledgerEndIndex++;
            ledgerEndTerm = memberState.currTerm();
            if (ledgerBeginIndex == -1) {
                ledgerBeginIndex = ledgerEndIndex;
            }
            updateLedgerEndIndexAndTerm();
            return entry;
        }
    }

    @Override
    public long truncate(DLedgerEntry entry, long leaderTerm, String leaderId) {
        PreConditions.check(memberState.isFollower(), DLedgerResponseCode.NOT_FOLLOWER, null);
        ByteBuffer dataBuffer = localEntryBuffer.get();
        ByteBuffer indexBuffer = localIndexBuffer.get();
        DLedgerEntryCoder.encode(entry, dataBuffer);
        int entrySize = dataBuffer.remaining();
        synchronized (memberState) {
            PreConditions.check(memberState.isFollower(), DLedgerResponseCode.NOT_FOLLOWER, "role=%s", memberState.getRole());
            PreConditions.check(leaderTerm == memberState.currTerm(), DLedgerResponseCode.INCONSISTENT_TERM, "term %d != %d", leaderTerm, memberState.currTerm());
            PreConditions.check(leaderId.equals(memberState.getLeaderId()), DLedgerResponseCode.INCONSISTENT_LEADER, "leaderId %s != %s", leaderId, memberState.getLeaderId());
            boolean existedEntry;
            try {
                DLedgerEntry tmp = get(entry.getIndex());
                existedEntry = entry.equals(tmp);
            } catch (Throwable ignored) {
                existedEntry = false;
            }
            long truncatePos = existedEntry ? entry.getPos() + entry.getSize() : entry.getPos();
            if (truncatePos != dataFileList.getMaxWrotePosition()) {
                logger.warn("[TRUNCATE]leaderId={} index={} truncatePos={} != maxPos={}, this is usually happened on the old leader", leaderId, entry.getIndex(), truncatePos, dataFileList.getMaxWrotePosition());
            }
            dataFileList.truncateOffset(truncatePos);
            if (dataFileList.getMaxWrotePosition() != truncatePos) {
                logger.warn("[TRUNCATE] rebuild for data wrotePos: {} != truncatePos: {}", dataFileList.getMaxWrotePosition(), truncatePos);
                PreConditions.check(dataFileList.rebuildWithPos(truncatePos), DLedgerResponseCode.DISK_ERROR, "rebuild data truncatePos=%d", truncatePos);
            }
            reviseDataFileListFlushedWhere(truncatePos);
            if (!existedEntry) {
                long dataPos = dataFileList.append(dataBuffer.array(), 0, dataBuffer.remaining());
                PreConditions.check(dataPos == entry.getPos(), DLedgerResponseCode.DISK_ERROR, " %d != %d", dataPos, entry.getPos());
            }

            long truncateIndexOffset = entry.getIndex() * INDEX_UNIT_SIZE;
            indexFileList.truncateOffset(truncateIndexOffset);
            if (indexFileList.getMaxWrotePosition() != truncateIndexOffset) {
                logger.warn("[TRUNCATE] rebuild for index wrotePos: {} != truncatePos: {}", indexFileList.getMaxWrotePosition(), truncateIndexOffset);
                PreConditions.check(indexFileList.rebuildWithPos(truncateIndexOffset), DLedgerResponseCode.DISK_ERROR, "rebuild index truncatePos=%d", truncateIndexOffset);
            }
            reviseIndexFileListFlushedWhere(truncateIndexOffset);
            DLedgerEntryCoder.encodeIndex(entry.getPos(), entrySize, entry.getMagic(), entry.getIndex(), entry.getTerm(), indexBuffer);
            long indexPos = indexFileList.append(indexBuffer.array(), 0, indexBuffer.remaining(), false);
            PreConditions.check(indexPos == entry.getIndex() * INDEX_UNIT_SIZE, DLedgerResponseCode.DISK_ERROR, null);
            ledgerEndTerm = entry.getTerm();
            ledgerEndIndex = entry.getIndex();
            reviseLedgerBeginIndex();
            updateLedgerEndIndexAndTerm();
            return entry.getIndex();
        }
    }

    private void reviseDataFileListFlushedWhere(long truncatePos) {
        long offset = calculateWherePosition(this.dataFileList, truncatePos);
        logger.info("Revise dataFileList flushedWhere from {} to {}", this.dataFileList.getFlushedWhere(), offset);
        // It seems unnecessary to set position atomically. Wrong position won't get updated during flush or commit.
        this.dataFileList.updateWherePosition(offset);
    }

    private void reviseIndexFileListFlushedWhere(long truncateIndexOffset) {
        long offset = calculateWherePosition(this.indexFileList, truncateIndexOffset);
        logger.info("Revise indexFileList flushedWhere from {} to {}", this.indexFileList.getFlushedWhere(), offset);
        this.indexFileList.updateWherePosition(offset);
    }

    /**
     * calculate wherePosition after truncate
     *
     * @param mappedFileList this.dataFileList or this.indexFileList
     * @param continuedBeginOffset new begining of offset
     */
    private long calculateWherePosition(final MmapFileList mappedFileList, long continuedBeginOffset) {
        if (mappedFileList.getFlushedWhere() == 0) {
            return 0;
        }
        if (mappedFileList.getMappedFiles().isEmpty()) {
            return continuedBeginOffset;
        }
        if (mappedFileList.getFlushedWhere() < mappedFileList.getFirstMappedFile().getFileFromOffset()) {
            return mappedFileList.getFirstMappedFile().getFileFromOffset();
        }
        return mappedFileList.getFlushedWhere();
    }

    @Override
    public DLedgerEntry appendAsFollower(DLedgerEntry entry, long leaderTerm, String leaderId) {
        PreConditions.check(memberState.isFollower(), DLedgerResponseCode.NOT_FOLLOWER, "role=%s", memberState.getRole());
        PreConditions.check(!isDiskFull, DLedgerResponseCode.DISK_FULL);
        ByteBuffer dataBuffer = localEntryBuffer.get();
        ByteBuffer indexBuffer = localIndexBuffer.get();
        DLedgerEntryCoder.encode(entry, dataBuffer);
        int entrySize = dataBuffer.remaining();
        synchronized (memberState) {
            PreConditions.check(memberState.isFollower(), DLedgerResponseCode.NOT_FOLLOWER, "role=%s", memberState.getRole());
            long nextIndex = ledgerEndIndex + 1;
            PreConditions.check(nextIndex == entry.getIndex(), DLedgerResponseCode.INCONSISTENT_INDEX, null);
            PreConditions.check(leaderTerm == memberState.currTerm(), DLedgerResponseCode.INCONSISTENT_TERM, null);
            PreConditions.check(leaderId.equals(memberState.getLeaderId()), DLedgerResponseCode.INCONSISTENT_LEADER, null);
            long dataPos = dataFileList.append(dataBuffer.array(), 0, dataBuffer.remaining());
            PreConditions.check(dataPos == entry.getPos(), DLedgerResponseCode.DISK_ERROR, "%d != %d", dataPos, entry.getPos());
            DLedgerEntryCoder.encodeIndex(dataPos, entrySize, entry.getMagic(), entry.getIndex(), entry.getTerm(), indexBuffer);
            long indexPos = indexFileList.append(indexBuffer.array(), 0, indexBuffer.remaining(), false);
            PreConditions.check(indexPos == entry.getIndex() * INDEX_UNIT_SIZE, DLedgerResponseCode.DISK_ERROR, null);
            ledgerEndTerm = entry.getTerm();
            ledgerEndIndex = entry.getIndex();
            if (ledgerBeginIndex == -1) {
                ledgerBeginIndex = ledgerEndIndex;
            }
            updateLedgerEndIndexAndTerm();
            return entry;
        }

    }

    /**
     * 将检查点序列化本地文件
     */
    void persistCheckPoint() {
        try {
            //实例化一个属性对象
            Properties properties = new Properties();
            //将当前节点最后一个消息的索引值 放入属性对象
            properties.put(END_INDEX_KEY, getLedgerEndIndex());

            //将当前节点最后一个提交的索引值 放入属性对象
            properties.put(COMMITTED_INDEX_KEY, getCommittedIndex());

            //将属性对象中的属性值 转为字符串格式
            String data = IOUtils.properties2String(properties);
            //将字符串写入文件
            IOUtils.string2File(data, dLedgerConfig.getDefaultPath() + File.separator + CHECK_POINT_FILE);
        } catch (Throwable t) {
            logger.error("Persist checkpoint failed", t);
        }
    }

    /**
     * 加载序列化本地磁盘文件的当前节点最后一个消息的索引值 提交索引值
     * @return
     */
    Properties loadCheckPoint() {
        try {
            //读取文件中的字符串
            String data = IOUtils.file2String(dLedgerConfig.getDefaultPath() + File.separator + CHECK_POINT_FILE);
            //解析字符串中的内容 返回Properties对象
            Properties properties = IOUtils.string2Properties(data);
            return properties;
        } catch (Throwable t) {
            logger.error("Load checkpoint failed", t);

        }
        return null;
    }

    /**
     * 获取最后一条消息的索引值
     * @return
     */
    @Override
    public long getLedgerEndIndex() {
        return ledgerEndIndex;
    }

    @Override public long getLedgerBeginIndex() {
        return ledgerBeginIndex;
    }

    /**
     * 根据索引位置获取消息实体
     * @param index 索引值
     * @return
     */
    @Override
    public DLedgerEntry get(Long index) {
        //检查消息的索引值 必须大于等于0
        PreConditions.check(index >= 0, DLedgerResponseCode.INDEX_OUT_OF_RANGE, "%d should gt 0", index);

        //索引值必须大于等于第一条消息的索引值
        PreConditions.check(index >= ledgerBeginIndex, DLedgerResponseCode.INDEX_LESS_THAN_LOCAL_BEGIN, "%d should be gt %d, ledgerBeginIndex may be revised", index, ledgerBeginIndex);
        //索引值必须小于等于最后一条消息的索引值
        PreConditions.check(index <= ledgerEndIndex, DLedgerResponseCode.INDEX_OUT_OF_RANGE, "%d should between %d-%d", index, ledgerBeginIndex, ledgerEndIndex);
        //选择的索引 mappedFile list文件系统映射的bytebuffer缓存区结果对象
        SelectMmapBufferResult indexSbr = null;
        //选择的消息 mappedFile list文件系统映射的bytebuffer缓存区结果对象
        SelectMmapBufferResult dataSbr = null;
        try {
            //获取索bytebuffer
            indexSbr = indexFileList.getData(index * INDEX_UNIT_SIZE, INDEX_UNIT_SIZE);
            //检查索引bytebuffer
            PreConditions.check(indexSbr != null && indexSbr.getByteBuffer() != null, DLedgerResponseCode.DISK_ERROR, "Get null index for %d", index);
            //获取消息的模数
            indexSbr.getByteBuffer().getInt(); //magic
            //获取消息的偏移量
            long pos = indexSbr.getByteBuffer().getLong();
            //获取消息所占的字节数
            int size = indexSbr.getByteBuffer().getInt();
            //获取消息的bytebuffer
            dataSbr = dataFileList.getData(pos, size);
            //消息的bytebuffer不能为null
            PreConditions.check(dataSbr != null && dataSbr.getByteBuffer() != null, DLedgerResponseCode.DISK_ERROR, "Get null data for %d", index);
            //从缓存区中取出字节数组 解码为消息实体
            DLedgerEntry dLedgerEntry = DLedgerEntryCoder.decode(dataSbr.getByteBuffer());
            //消息的偏移量与实体的偏移量相等
            PreConditions.check(pos == dLedgerEntry.getPos(), DLedgerResponseCode.DISK_ERROR, "%d != %d", pos, dLedgerEntry.getPos());
            //获取消息实体
            return dLedgerEntry;
        } finally {

            //释放对消息 mappedFile的引用
            SelectMmapBufferResult.release(indexSbr);

            //释放对索引 mappedFile的引用
            SelectMmapBufferResult.release(dataSbr);
        }
    }

    @Override
    public long getCommittedIndex() {
        return committedIndex;
    }

    /**
     * 更新提交到的索引值
     * @param term
     * @param newCommittedIndex
     */
    public void updateCommittedIndex(long term, long newCommittedIndex) {
        if (newCommittedIndex == -1
            || ledgerEndIndex == -1
            || term < memberState.currTerm()
            || newCommittedIndex == this.committedIndex) {
            return;
        }
        if (newCommittedIndex < this.committedIndex
            || newCommittedIndex < this.ledgerBeginIndex) {
            logger.warn("[MONITOR]Skip update committed index for new={} < old={} or new={} < beginIndex={}", newCommittedIndex, this.committedIndex, newCommittedIndex, this.ledgerBeginIndex);
            return;
        }

        //当前节点中最后一条消息的索引值
        long endIndex = ledgerEndIndex;
        if (newCommittedIndex > endIndex) {//提交索引值不能大于最后一条消息的索引值
            //If the node fall behind too much, the committedIndex will be larger than enIndex.
            newCommittedIndex = endIndex;
        }

        //获取提交索引值的那条消息实体
        DLedgerEntry dLedgerEntry = get(newCommittedIndex);
        //消息存在
        PreConditions.check(dLedgerEntry != null, DLedgerResponseCode.DISK_ERROR);
        //设置提交索引值
        this.committedIndex = newCommittedIndex;
        //设置提交偏移量
        this.committedPos = dLedgerEntry.getPos() + dLedgerEntry.getSize();
    }

    /**
     * 获取最后一条消息的轮次
     * @return
     */
    @Override
    public long getLedgerEndTerm() {
        return ledgerEndTerm;
    }

    public long getCommittedPos() {
        return committedPos;
    }

    public void addAppendHook(AppendHook writeHook) {
        if (!appendHooks.contains(writeHook)) {
            appendHooks.add(writeHook);
        }
    }

    @Override
    public MemberState getMemberState() {
        return memberState;
    }

    public MmapFileList getDataFileList() {
        return dataFileList;
    }

    public MmapFileList getIndexFileList() {
        return indexFileList;
    }

    public interface AppendHook {
        void doHook(DLedgerEntry entry, ByteBuffer buffer, int bodyOffset);
    }

    // Just for test
    public void shutdownFlushService() {
        this.flushDataService.shutdown();
    }

    /**
     * 刷新数据服务类
     */
    class FlushDataService extends ShutdownAbleThread {

        public FlushDataService(String name, Logger logger) {
            super(name, logger);
        }

        /**
         * 执行任务
         */
        @Override public void doWork() {
            try {
                //获取开始时间
                long start = System.currentTimeMillis();

                //将消息 mappedFile list内存中的字节数组刷新到磁盘文件
                DLedgerMmapFileStore.this.dataFileList.flush(0);

                //将索引 mappedFile list内存中的字节数组刷新到磁盘文件
                DLedgerMmapFileStore.this.indexFileList.flush(0);
                if (DLedgerUtils.elapsed(start) > 500) {//计算消耗时间 大于500毫秒 记录日志
                    logger.info("Flush data cost={} ms", DLedgerUtils.elapsed(start));
                }

                //如果上一次检查点的时间 超过了3秒
                if (DLedgerUtils.elapsed(lastCheckPointTimeMs) > dLedgerConfig.getCheckPointInterval()) {
                    //将最后一个消息的索引值 提交索引值写入到磁盘文件
                    persistCheckPoint();
                    //设置最后检查点的时间
                    lastCheckPointTimeMs = System.currentTimeMillis();
                }

                //等待10秒 继续下一次检测
                waitForRunning(dLedgerConfig.getFlushFileInterval());
            } catch (Throwable t) {
                logger.info("Error in {}", getName(), t);
                DLedgerUtils.sleep(200);
            }
        }
    }

    /**
     * 清理空格的服务
     */
    class CleanSpaceService extends ShutdownAbleThread {

        /**
         * 基本目录使用的百分比
         */
        double storeBaseRatio = DLedgerUtils.getDiskPartitionSpaceUsedPercent(dLedgerConfig.getStoreBaseDir());

        /**
         * 存入消息 mappeFile父目录使用的百分比
         */
        double dataRatio = DLedgerUtils.getDiskPartitionSpaceUsedPercent(dLedgerConfig.getDataStorePath());

        public CleanSpaceService(String name, Logger logger) {
            super(name, logger);
        }

        /**
         * 清理消息 mappedFile list中过期的mappedFile 防止存储目录被使用过多
         */
        @Override public void doWork() {
            try {
                //获取根目录使用百分比
                storeBaseRatio = DLedgerUtils.getDiskPartitionSpaceUsedPercent(dLedgerConfig.getStoreBaseDir());
                //获取存放消息的目录使用百分比
                dataRatio = DLedgerUtils.getDiskPartitionSpaceUsedPercent(dLedgerConfig.getDataStorePath());
                long hourOfMs = 3600L * 1000L;
                //获取需要删除的文件的过期时间
                long fileReservedTimeMs = dLedgerConfig.getFileReservedHours() *  hourOfMs;
                if (fileReservedTimeMs < hourOfMs) {
                    logger.warn("The fileReservedTimeMs={} is smaller than hourOfMs={}", fileReservedTimeMs, hourOfMs);
                    fileReservedTimeMs =  hourOfMs;
                }
                //文件存储的根目录 或者消息 mappedFile的父目录使用率超过了90% 不能再往文件夹中写数据
                DLedgerMmapFileStore.this.isDiskFull = isNeedForbiddenWrite();

                //如果当前时间是凌晨4点
                boolean timeUp = isTimeToDelete();

                //需要清理老的文件
                boolean checkExpired = isNeedCheckExpired();

                //需要强制清理
                boolean forceClean = isNeedForceClean();

                //配置了可以强制清理存储目录下的文件
                boolean enableForceClean = dLedgerConfig.isEnableDiskForceClean();
                if (timeUp || checkExpired) {//时间到 需要检查清理老的文件

                    //每次最多删除 10个mappedFILE文件
                    int count = getDataFileList().deleteExpiredFileByTime(fileReservedTimeMs, 100, 120 * 1000, forceClean && enableForceClean);
                    if (count > 0 || (forceClean && enableForceClean) || isDiskFull) {
                        //记录删除过mappedFILE
                        logger.info("Clean space count={} timeUp={} checkExpired={} forceClean={} enableForceClean={} diskFull={} storeBaseRatio={} dataRatio={}",
                            count, timeUp, checkExpired, forceClean, enableForceClean, isDiskFull, storeBaseRatio, dataRatio);
                    }
                    if (count > 0) {//重置节点第一条消息的index值 删除index mappedFile list之前的mappedFile
                        DLedgerMmapFileStore.this.reviseLedgerBeginIndex();
                    }
                }

                //等待100毫秒 继续执行
                waitForRunning(100);
            } catch (Throwable t) {
                logger.info("Error in {}", getName(), t);
                DLedgerUtils.sleep(200);
            }
        }

        /**
         * 判断是否到了清理的时间
         * @return
         */
        private boolean isTimeToDelete() {
            //凌晨4点
            String when = DLedgerMmapFileStore.this.dLedgerConfig.getDeleteWhen();
            if (DLedgerUtils.isItTimeToDo(when)) {
                return true;
            }

            return false;
        }

        /**
         * 判断是否需要检查清理超时的文件夹下的文件
         *  文件夹的使用率超过了70%
         * @return
         */
        private boolean isNeedCheckExpired() {
            if (storeBaseRatio > dLedgerConfig.getDiskSpaceRatioToCheckExpired()//存储目录使用率超过了70%
                || dataRatio > dLedgerConfig.getDiskSpaceRatioToCheckExpired()) {//消息存储父目录使用率超过了70%
                //需要清理超时的文件夹下的老的文件
                return true;
            }
            return false;
        }

        /**
         * 判断是否需要清理存储目录 /消息存储父目录下的文件
         * @return
         */
        private boolean isNeedForceClean() {
            if (storeBaseRatio > dLedgerConfig.getDiskSpaceRatioToForceClean()//存储目录的使用率达到90%
                || dataRatio > dLedgerConfig.getDiskSpaceRatioToForceClean()) {//消息存储父目录的使用率达到90%
                return true;
            }
            return false;
        }

        /**
         * 判断是否禁止写文件夹中写入数据
         * 文件夹的使用率超过了90%
         * @return
         */
        private boolean isNeedForbiddenWrite() {
            if (storeBaseRatio > dLedgerConfig.getDiskFullRatio()
                || dataRatio > dLedgerConfig.getDiskFullRatio()) {
                return true;
            }
            return false;
        }
    }
}
