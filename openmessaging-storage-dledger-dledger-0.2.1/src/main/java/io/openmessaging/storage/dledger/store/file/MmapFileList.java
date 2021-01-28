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

import io.openmessaging.storage.dledger.utils.DLedgerUtils;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mappedFile List文件类
 */
public class MmapFileList {

    /**
     * 最小空表字符的长度
     */
    public static final int MIN_BLANK_LEN = 8;

    /**
     * 如果mappedFile中某条消息的模数为空白模数 说明这个mappedFile文件在这条消息之后 没有记录任何消息
     */
    public static final int BLANK_MAGIC_CODE = -1;
    private static Logger logger = LoggerFactory.getLogger(MmapFile.class);

    /**
     * 每次批量删除的mappedFile的数量
     */
    private static final int DELETE_FILES_BATCH_MAX = 10;

    /**
     * mappedFile文件的父目录
     */
    private final String storePath;

    /**
     * 单个mappedFile的最大字节数
     */
    private final int mappedFileSize;

    /**
     * 当前mappedFileList中所有的mappedFile
     */
    private final CopyOnWriteArrayList<MmapFile> mappedFiles = new CopyOnWriteArrayList<MmapFile>();

    /**
     * 当前刷新到磁盘的起始位置
     */
    private long flushedWhere = 0;

    /**
     * 当前提交的起始位置
     */
    private long committedWhere = 0;

    private volatile long storeTimestamp = 0;

    /**
     * 实例化一个mappedFileList对象
     * @param storePath 存储路径
     * @param mappedFileSize 单个mappedFile的最大值
     */
    public MmapFileList(final String storePath, int mappedFileSize) {
        this.storePath = storePath;
        this.mappedFileSize = mappedFileSize;
    }

    /**
     * 检查mappedFileList下所有的mappedFile的起始偏移量是否连续 差值为一个mappedFileSize
     * @return
     */
    public boolean checkSelf() {
        if (!this.mappedFiles.isEmpty()) {//mappedFile列表不为nulll
            Iterator<MmapFile> iterator = mappedFiles.iterator();
            //前一个mappedFile
            MmapFile pre = null;
            while (iterator.hasNext()) {//遍历mappedFile列表
                //获取当前mappedFile
                MmapFile cur = iterator.next();

                if (pre != null) {//前一个mappedFile不为null
                    //当前mappedFile的起始偏移量与前一个mapedFile的起始偏移量差值为一个mappedFileSIze
                    if (cur.getFileFromOffset() - pre.getFileFromOffset() != this.mappedFileSize) {
                        logger.error("[BUG]The mappedFile queue's data is damaged, the adjacent mappedFile's offset don't match pre file {}, cur file {}",
                            pre.getFileName(), cur.getFileName());
                        return false;
                    }
                }
                //设置前一个mappedFile
                pre = cur;
            }
        }
        return true;
    }

    public MmapFile getMappedFileByTime(final long timestamp) {
        Object[] mfs = this.copyMappedFiles();

        if (null == mfs)
            return null;

        for (int i = 0; i < mfs.length; i++) {
            MmapFile mappedFile = (MmapFile) mfs[i];
            if (mappedFile.getLastModifiedTimestamp() >= timestamp) {
                return mappedFile;
            }
        }

        return (MmapFile) mfs[mfs.length - 1];
    }

    /**
     * 将mappedFile列表对象复制一份返回
     * @return
     */
    private Object[] copyMappedFiles() {
        if (this.mappedFiles.size() <= 0) {//没有mappedFile文件
            return null;
        }

        //转为数组
        return this.mappedFiles.toArray();
    }

    /**
     * 截取mappedFileList中偏移量之前的mappedFIle文件 之后的mappedFile将会被删除
     * @param offset 截取的截止偏移量
     */
    public void truncateOffset(long offset) {
        //复制一个原始的mappedFile文件
        Object[] mfs = this.copyMappedFiles();
        if (mfs == null) {
            return;
        }

        //将要删除的mappedFile文件
        List<MmapFile> willRemoveFiles = new ArrayList<MmapFile>();

        for (int i = 0; i < mfs.length; i++) {//遍历原始mappedFile数组
            //获取当前遍历到的mappedFile
            MmapFile file = (MmapFile) mfs[i];
            //获取当前mappedFile对象的可写的最大偏移量
            long fileTailOffset = file.getFileFromOffset() + this.mappedFileSize;
            if (fileTailOffset > offset) {//当前mappedFile文件的可写最大偏移量 大于截取的偏移量
                if (offset >= file.getFileFromOffset()) {//如果截取的偏移量处于这个mappedFIle上
                    //设置mappedFIle的已经写到的位置
                    file.setWrotePosition((int) (offset % this.mappedFileSize));
                    //设置mappedFile文件已经提交到的位置
                    file.setCommittedPosition((int) (offset % this.mappedFileSize));
                    //设置mappedFile已经刷新的位置
                    file.setFlushedPosition((int) (offset % this.mappedFileSize));
                } else {
                    //这个mappedFile将会被删除
                    willRemoveFiles.add(file);
                }
            }
        }

        //销毁过期的mappedFile文件
        this.destroyExpiredFiles(willRemoveFiles);
        //删除过期的mappedFile文件
        this.deleteExpiredFiles(willRemoveFiles);
    }

    /**
     * 销毁超时的mappedFile
     * @param files mappedFile文件列表
     */
    void destroyExpiredFiles(List<MmapFile> files) {
        Collections.sort(files, new Comparator<MmapFile>() {
            @Override public int compare(MmapFile o1, MmapFile o2) {
                if (o1.getFileFromOffset() < o2.getFileFromOffset()) {
                    return -1;
                } else if (o1.getFileFromOffset() > o2.getFileFromOffset()) {
                    return 1;
                }
                return 0;
            }
        });

        for (int i = 0; i < files.size(); i++) {//遍历将要删除的mappedFile列表
            MmapFile mmapFile = files.get(i);//获取当前遍历的mappedFile
            while (true) {
                if (mmapFile.destroy(10 * 1000)) {//消化mappedFile文件
                    break;
                }
                DLedgerUtils.sleep(1000);
            }
        }
    }

    /**
     * 重置起始偏移量
     * @param offset 第一条消息的偏移量
     */
    public void resetOffset(long offset) {
        //获取所有的mapedFile
        Object[] mfs = this.copyMappedFiles();
        if (mfs == null) {
            return;
        }

        //将会被删除的mappedFile列表
        List<MmapFile> willRemoveFiles = new ArrayList<MmapFile>();

        for (int i = mfs.length - 1; i >= 0; i--) {//从后往前遍历mapedFile文件
            //当前遍历到的mappedFile文件
            MmapFile file = (MmapFile) mfs[i];
            //当前mappedFile的最大便宜来了
            long fileTailOffset = file.getFileFromOffset() + this.mappedFileSize;
            if (file.getFileFromOffset() <= offset) {
                if (offset < fileTailOffset) {
                    //设置文件的起始偏移量
                    file.setStartPosition((int) (offset % this.mappedFileSize));
                } else {//mappedFile将会被删除
                    willRemoveFiles.add(file);
                }
            }
        }

        //销毁mappedFile文件
        this.destroyExpiredFiles(willRemoveFiles);

        this.deleteExpiredFiles(willRemoveFiles);
    }

    /**
     * 设置已经刷新到的位置、已经提交的位置
     * @param wherePosition 位置
     */
    public void updateWherePosition(long wherePosition) {
        if (wherePosition > getMaxWrotePosition()) {
            logger.warn("[UpdateWherePosition] wherePosition {} > maxWrotePosition {}", wherePosition, getMaxWrotePosition());
            return;
        }
        //设置刷新的起始位置
        this.setFlushedWhere(wherePosition);
        //设置提交的起始位置
        this.setCommittedWhere(wherePosition);
    }

    public long append(byte[] data) {
        return append(data, 0, data.length);
    }

    public long append(byte[] data, int pos, int len) {
        return append(data, pos, len, true);
    }

    public long append(byte[] data, boolean useBlank) {
        return append(data, 0, data.length, useBlank);
    }

    public long preAppend(int len) {
        return preAppend(len, true);
    }

    /**
     * 向mappedFile写入n个字节的前置判断
     * @param len 字节数
     * @param useBlank 当最后一个mappedFile剩余可写空间不足以写入字节数时 是否可以向最后一个mappedFile中写入一个空白模数的消息
     * @return 最后一个mappedFile写入字节的起始偏移量
     */
    public long preAppend(int len, boolean useBlank) {
        //获取最后一个mappedFiel
        MmapFile mappedFile = getLastMappedFile();
        if (null == mappedFile || mappedFile.isFull()) {//最后一个mappedFile不存在或者已经满了 需要创建一个mappedFile
            mappedFile = getLastMappedFile(0);
        }
        if (null == mappedFile) {//获取最后一个mappedFile文件失败
            logger.error("Create mapped file for {}", storePath);
            return -1;
        }

        //使用空白符 空白符长度为8 默认为0
        int blank = useBlank ? MIN_BLANK_LEN : 0;
        if (len + blank > mappedFile.getFileSize() - mappedFile.getWrotePosition()) {//最后一个mappedFile不够写入
            if (blank < MIN_BLANK_LEN) {//最后一个mappedFile文件不够写入
                logger.error("Blank {} should ge {}", blank, MIN_BLANK_LEN);
                return -1;
            } else {

                //分配一个mappedFile剩余可写字节数大小的缓冲区
                ByteBuffer byteBuffer = ByteBuffer.allocate(mappedFile.getFileSize() - mappedFile.getWrotePosition());
                //写入空白模数值
                byteBuffer.putInt(BLANK_MAGIC_CODE);
                //写入剩下字节的长度
                byteBuffer.putInt(mappedFile.getFileSize() - mappedFile.getWrotePosition());
                if (mappedFile.appendMessage(byteBuffer.array())) {//写入空白模数的消息成功 最后一个mappedFile不能再写入消息
                    //设置mappedFile已经写到的位置
                    mappedFile.setWrotePosition(mappedFile.getFileSize());
                } else {//最后一个mappedFile不足以写入空白模数消息
                    logger.error("Append blank error for {}", storePath);
                    return -1;
                }
                //创建并获取下一个mappedFile文件
                mappedFile = getLastMappedFile(0);
                if (null == mappedFile) {
                    logger.error("Create mapped file for {}", storePath);
                    return -1;
                }
            }
        }

        //返回mappedFile文件添加消息的起始偏移量
        return mappedFile.getFileFromOffset() + mappedFile.getWrotePosition();

    }

    /**
     * 向mappedFile list指定位置添加一个字节数数组的数据
     * @param data 将要添加到mappedFile中的字节数组
     * @param pos 数组的起始位置
     * @param len 数组的长度
     * @param useBlank 数组长度不足是否可以写入空白符
     * @return
     */
    public long append(byte[] data, int pos, int len, boolean useBlank) {
        if (preAppend(len, useBlank) == -1) {//最后一个mappedFile不能再写入消息
            return -1;
        }

        //最后一个mappedFILE可以写入字节
        MmapFile mappedFile = getLastMappedFile();
        //获取mappedFile已经写入消息的起始位置
        long currPosition = mappedFile.getFileFromOffset() + mappedFile.getWrotePosition();
        if (!mappedFile.appendMessage(data, pos, len)) {//将字节数组写入到mappedFile文件
            logger.error("Append error for {}", storePath);
            return -1;
        }

        //返回写入消息的起始偏移量
        return currPosition;
    }

    public SelectMmapBufferResult getData(final long offset, final int size) {
        MmapFile mappedFile = findMappedFileByOffset(offset, offset == 0);
        if (mappedFile != null) {
            int pos = (int) (offset % mappedFileSize);
            return mappedFile.selectMappedBuffer(pos, size);
        }
        return null;
    }

    /**
     * 从mappedFile中指定偏移量获取结果
     * @param offset 指定偏移量
     * @return
     */
    public SelectMmapBufferResult getData(final long offset) {
        //获取偏移量所在的mappedFile
        MmapFile mappedFile = findMappedFileByOffset(offset, offset == 0);
        if (mappedFile != null) {//mappedFILE存在
            //偏移量在mappedFile的bytebuffer中的偏移量
            int pos = (int) (offset % mappedFileSize);
            return mappedFile.selectMappedBuffer(pos);
        }
        return null;
    }

    /**
     * 从mappedFile 列表中删除指定的mappedFile列表
     * @param files
     */
    void deleteExpiredFiles(List<MmapFile> files) {
        if (!files.isEmpty()) {//文件列表不为null
            //遍历mappedFile列表
            Iterator<MmapFile> iterator = files.iterator();
            while (iterator.hasNext()) {
                //获取当前mappedFile文件
                MmapFile cur = iterator.next();
                if (!this.mappedFiles.contains(cur)) {//原始列表中不 包含被删除的mappedFile
                    //删除文件
                    iterator.remove();
                    logger.info("This mappedFile {} is not contained by mappedFiles, so skip it.", cur.getFileName());
                }
            }

            try {
                if (!this.mappedFiles.removeAll(files)) {//从列表 中删除将要被删除的mappedFile文件列表
                    logger.error("deleteExpiredFiles remove failed.");
                }
            } catch (Exception e) {
                logger.error("deleteExpiredFiles has exception.", e);
            }
        }
    }

    /**
     * 加载消息父目录下的mappedFile到datamappedFileList
     * @return
     */
    public boolean load() {
        //获取mappedFile文件的父目录
        File dir = new File(this.storePath);
        //获取父目录下所有的文件
        File[] files = dir.listFiles();
        if (files != null) {//存在mappedFile文件
            //排序文件
            Arrays.sort(files);
            for (File file : files) {//遍历文件

                if (file.length() != this.mappedFileSize) {//文件的大小不是指定的单个mappedFile文件大小 说明文件有问题
                    logger.warn(file + "\t" + file.length()
                        + " length not matched message store config value, please check it manually. You should delete old files before changing mapped file size");
                    return false;
                }
                try {
                    //实例化一个mappedFIle文件
                    MmapFile mappedFile = new DefaultMmapFile(file.getPath(), mappedFileSize);

                    //设置mappedFile已经写到的位置
                    mappedFile.setWrotePosition(this.mappedFileSize);
                    //设置mappedFile需要刷新的位置
                    mappedFile.setFlushedPosition(this.mappedFileSize);
                    //设置mappedFile提交的位置
                    mappedFile.setCommittedPosition(this.mappedFileSize);

                    //将mappedFile添加到mappedFiles
                    this.mappedFiles.add(mappedFile);
                    logger.info("load " + file.getPath() + " OK");
                } catch (IOException e) {
                    logger.error("load file " + file + " error", e);
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 获取最后一个mappedFile 如果没有找到mappedFile 创建一个mappedFILE
     * @param startOffset 创建的mappedFile的起始偏移量
     * @param needCreate 是否需要创建
     * @return
     */
    public MmapFile getLastMappedFile(final long startOffset, boolean needCreate) {
        //创建的mappedFile的起始偏移量
        long createOffset = -1;
        //获取最后一个mappedFile
        MmapFile mappedFileLast = getLastMappedFile();

        if (mappedFileLast == null) {//没有获取到最后一个mappedFile
            //需要创建mappedFile 创建mappedFile的起始偏移量
            createOffset = startOffset - (startOffset % this.mappedFileSize);
        } else if (mappedFileLast.isFull()) {
            //最后一个mappedFile满了  创建mappedFile的起始偏移量
            createOffset = mappedFileLast.getFileFromOffset() + this.mappedFileSize;
        }

        if (createOffset != -1 && needCreate) {//需要创建mappedFile
            //获取创建的mappedFile的路径
            String nextFilePath = this.storePath + File.separator + DLedgerUtils.offset2FileName(createOffset);
            //创建的mappedFile对象
            MmapFile mappedFile = null;
            try {
                //实例化一个默认的mappedFile
                mappedFile = new DefaultMmapFile(nextFilePath, this.mappedFileSize);
            } catch (IOException e) {
                logger.error("create mappedFile exception", e);
            }

            if (mappedFile != null) {//创建了mappedFile文件
                if (this.mappedFiles.isEmpty()) {
                    //设置为第一个mappedFile
                    mappedFile.setFirstCreateInQueue(true);
                }
                //将mappedFile添加到mappedFile list文件系统
                this.mappedFiles.add(mappedFile);
            }

            return mappedFile;
        }

        return mappedFileLast;
    }

    /**
     * 获取最后一个mappedFile
     * @param startOffset 起始偏移量
     * @return
     */
    public MmapFile getLastMappedFile(final long startOffset) {
        return getLastMappedFile(startOffset, true);
    }

    /**
     * 获取mappedFileList中最后一个mappedFile
     * @return
     */
    public MmapFile getLastMappedFile() {
        //结果
        MmapFile mappedFileLast = null;

        while (!this.mappedFiles.isEmpty()) {
            try {
                //获取数组最后一个元素
                mappedFileLast = this.mappedFiles.get(this.mappedFiles.size() - 1);
                break;
            } catch (IndexOutOfBoundsException e) {
                //continue;
            } catch (Exception e) {
                logger.error("getLastMappedFile has exception.", e);
                break;
            }
        }

        return mappedFileLast;
    }

    public long getMinOffset() {
        MmapFile mmapFile = getFirstMappedFile();
        if (mmapFile != null) {
            return mmapFile.getFileFromOffset() + mmapFile.getStartPosition();
        }
        return 0;
    }

    public long getMaxReadPosition() {
        MmapFile mappedFile = getLastMappedFile();
        if (mappedFile != null) {
            return mappedFile.getFileFromOffset() + mappedFile.getReadPosition();
        }
        return 0;
    }

    /**
     * 获取当前mappedFileList最大可以可以写到的位置
     * 最后一个mappedFile的起始偏移量 + 单个mappedFile的大小
     * @return
     */
    public long getMaxWrotePosition() {
        //获取最后一个mappedFile
        MmapFile mappedFile = getLastMappedFile();
        if (mappedFile != null) {
            //返回最大可以写到的位置
            return mappedFile.getFileFromOffset() + mappedFile.getWrotePosition();
        }
        return 0;
    }

    public long remainHowManyDataToCommit() {
        return getMaxWrotePosition() - committedWhere;
    }

    public long remainHowManyDataToFlush() {
        return getMaxReadPosition() - flushedWhere;
    }

    public void deleteLastMappedFile() {
        MmapFile lastMappedFile = getLastMappedFile();
        if (lastMappedFile != null) {
            lastMappedFile.destroy(1000);
            this.mappedFiles.remove(lastMappedFile);
            logger.info("on recover, destroy a logic mapped file " + lastMappedFile.getFileName());

        }
    }

    /**
     * 清理过期的mappedFile文件
     * @param expiredTime 过期时间 默认72小时
     * @param deleteFilesInterval 删除mappedFile的周期 100毫秒
     * @param intervalForcibly 强制执行的周期 120s 如果还有其他线程持有对mappedFile的引用 等待120秒 超时后 强制释放线程对文件的引用
     * @param cleanImmediately 是否立刻执行清理
     * @return
     */
    public int deleteExpiredFileByTime(final long expiredTime,
        final int deleteFilesInterval,
        final long intervalForcibly,
        final boolean cleanImmediately) {

        //获取所有的mappedFile
        Object[] mfs = this.copyMappedFiles();

        if (null == mfs)
            return 0;

        int mfsLength = mfs.length - 1;
        //将要删除的mappedFile的数量
        int deleteCount = 0;
        //需要从mappedFile list列表中移除的mappedFile列表
        List<MmapFile> files = new ArrayList<MmapFile>();
        if (null != mfs) {
            for (int i = 0; i < mfsLength; i++) {//遍历每一个mappedFile
                MmapFile mappedFile = (MmapFile) mfs[i];
                //文件上一次修改的时间 + 过期时间 文件存在的最大时间
                long liveMaxTimestamp = mappedFile.getLastModifiedTimestamp() + expiredTime;
                if (System.currentTimeMillis() >= liveMaxTimestamp || cleanImmediately) {//文件超过了72h没有修改 或者需要立刻执行清理
                    if (mappedFile.destroy(intervalForcibly)) {//消息mappedFile
                        //将文件添加到刷出列表
                        files.add(mappedFile);
                        //增加将要删除的mappedFile的数量
                        deleteCount++;

                        //每次最多只能删除10个mappedFile
                        if (files.size() >= DELETE_FILES_BATCH_MAX) {
                            break;
                        }

                        if (deleteFilesInterval > 0 && (i + 1) < mfsLength) {
                            try {
                                //等待100毫秒 执行删除
                                Thread.sleep(deleteFilesInterval);
                            } catch (InterruptedException e) {
                            }
                        }
                    } else {
                        break;
                    }
                } else {
                    //avoid deleting files in the middle
                    break;
                }
            }
        }

        //从mappedFile list列表中删除mappedFile
        deleteExpiredFiles(files);

        //返回删除的mappedFILE的数量
        return deleteCount;
    }

    public int deleteExpiredFileByOffset(long offset, int unitSize) {
        Object[] mfs = this.copyMappedFiles();

        List<MmapFile> files = new ArrayList<MmapFile>();
        int deleteCount = 0;
        if (null != mfs) {

            int mfsLength = mfs.length - 1;

            for (int i = 0; i < mfsLength; i++) {
                boolean destroy;
                MmapFile mappedFile = (MmapFile) mfs[i];
                SelectMmapBufferResult result = mappedFile.selectMappedBuffer(this.mappedFileSize - unitSize);
                if (result != null) {
                    long maxOffsetInLogicQueue = result.getByteBuffer().getLong();
                    result.release();
                    destroy = maxOffsetInLogicQueue < offset;
                    if (destroy) {
                        logger.info("physic min offset " + offset + ", logics in current mappedFile max offset "
                            + maxOffsetInLogicQueue + ", delete it");
                    }
                } else if (!mappedFile.isAvailable()) { // Handle hanged file.
                    logger.warn("Found a hanged consume queue file, attempting to delete it.");
                    destroy = true;
                } else {
                    logger.warn("this being not executed forever.");
                    break;
                }

                if (destroy && mappedFile.destroy(1000 * 60)) {
                    files.add(mappedFile);
                    deleteCount++;
                } else {
                    break;
                }
            }
        }

        deleteExpiredFiles(files);

        return deleteCount;
    }

    /**
     * 将内存中的字节数组刷新到磁盘
     * @param flushLeastPages 至少刷新的页数
     * @return
     */
    public boolean flush(final int flushLeastPages) {
        //刷新结果 是否刷新过内存中的字节数组到磁盘文件
        boolean result = true;

        //获取已经刷新到的那个mappedFile
        MmapFile mappedFile = this.findMappedFileByOffset(this.flushedWhere, this.flushedWhere == 0);
        if (mappedFile != null) {//不为null
            //将内存中的字节数组刷新到磁盘 返回单个mappedFile文件已经刷新到的位置
            int offset = mappedFile.flush(flushLeastPages);
            //获取整个mappedFile list已经刷新到的位置
            long where = mappedFile.getFileFromOffset() + offset;
            result = where == this.flushedWhere;
            //设置已经刷新到的位置
            this.flushedWhere = where;
        }

        return result;
    }

    public boolean commit(final int commitLeastPages) {
        boolean result = true;
        MmapFile mappedFile = this.findMappedFileByOffset(this.committedWhere, this.committedWhere == 0);
        if (mappedFile != null) {
            int offset = mappedFile.commit(commitLeastPages);
            long where = mappedFile.getFileFromOffset() + offset;
            result = where == this.committedWhere;
            this.committedWhere = where;
        }

        return result;
    }

    /**
     * 根据偏移量获取mappedFile
     * @param offset 偏移量
     * @param returnFirstOnNotFound 如果没有找到指定偏移量的mappedFile 是否返回第一个mappedFile
     * @return
     */
    public MmapFile findMappedFileByOffset(final long offset, final boolean returnFirstOnNotFound) {
        try {
            //获取第一个mappedFile
            MmapFile firstMappedFile = this.getFirstMappedFile();
            //获取最后一个mappedFile文件
            MmapFile lastMappedFile = this.getLastMappedFile();
            if (firstMappedFile != null && lastMappedFile != null) {//存在第一个mappedFile 并且存在最后一个mappedFile文件
                if (offset < firstMappedFile.getFileFromOffset() || offset >= lastMappedFile.getFileFromOffset() + this.mappedFileSize) {
                    //偏移量 必须在有效的偏移量范围内
                    logger.warn("Offset not matched. Request offset: {}, firstOffset: {}, lastOffset: {}, mappedFileSize: {}, mappedFiles count: {}",
                        offset,
                        firstMappedFile.getFileFromOffset(),
                        lastMappedFile.getFileFromOffset() + this.mappedFileSize,
                        this.mappedFileSize,
                        this.mappedFiles.size());
                } else {
                    //获取偏移量所在的mappedFile在mappedFile 列表中的下标
                    int index = (int) ((offset / this.mappedFileSize) - (firstMappedFile.getFileFromOffset() / this.mappedFileSize));
                    //目标mappedFile
                    MmapFile targetFile = null;
                    try {
                        //获取目标mappedFile
                        targetFile = this.mappedFiles.get(index);
                    } catch (Exception ignored) {
                    }

                    if (targetFile != null && offset >= targetFile.getFileFromOffset()
                        && offset < targetFile.getFileFromOffset() + this.mappedFileSize) {//有效的偏移量
                        //返回mappedFIle
                        return targetFile;
                    }

                    logger.warn("Offset is matched, but get file failed, maybe the file number is changed. Request offset: {}, firstOffset: {}, lastOffset: {}, mappedFileSize: {}, mappedFiles count: {}",
                        offset,
                        firstMappedFile.getFileFromOffset(),
                        lastMappedFile.getFileFromOffset() + this.mappedFileSize,
                        this.mappedFileSize,
                        this.mappedFiles.size());

                    //遍历所有的mappedfile 找出偏移量在遍历的mappedFile最小偏移量和最大偏移量之间的mappedFile
                    for (MmapFile tmpMappedFile : this.mappedFiles) {
                        if (offset >= tmpMappedFile.getFileFromOffset()
                            && offset < tmpMappedFile.getFileFromOffset() + this.mappedFileSize) {
                            return tmpMappedFile;
                        }
                    }
                }

                //如果没有找到mappedFile 返回第一个mappedFile
                if (returnFirstOnNotFound) {
                    return firstMappedFile;
                }
            }
        } catch (Exception e) {
            logger.error("findMappedFileByOffset Exception", e);
        }

        return null;
    }

    /**
     * 获取第一个mappedFile文件
     * @return
     */
    public MmapFile getFirstMappedFile() {
        //结果
        MmapFile mappedFileFirst = null;

        if (!this.mappedFiles.isEmpty()) {//mappedFiles列表不为null
            try {
                //返回数组的第一个元素
                mappedFileFirst = this.mappedFiles.get(0);
            } catch (IndexOutOfBoundsException e) {
                //ignore
            } catch (Exception e) {
                logger.error("getFirstMappedFile has exception.", e);
            }
        }

        return mappedFileFirst;
    }

    public MmapFile findMappedFileByOffset(final long offset) {
        return findMappedFileByOffset(offset, false);
    }

    public long getMappedMemorySize() {
        long size = 0;

        Object[] mfs = this.copyMappedFiles();
        if (mfs != null) {
            for (Object mf : mfs) {
                if (((ReferenceResource) mf).isAvailable()) {
                    size += this.mappedFileSize;
                }
            }
        }

        return size;
    }

    public boolean retryDeleteFirstFile(final long intervalForcibly) {
        MmapFile mappedFile = this.getFirstMappedFile();
        if (mappedFile != null) {
            if (!mappedFile.isAvailable()) {
                logger.warn("the mappedFile was destroyed once, but still alive, " + mappedFile.getFileName());
                boolean result = mappedFile.destroy(intervalForcibly);
                if (result) {
                    logger.info("the mappedFile re delete OK, " + mappedFile.getFileName());
                    List<MmapFile> tmpFiles = new ArrayList<MmapFile>();
                    tmpFiles.add(mappedFile);
                    this.deleteExpiredFiles(tmpFiles);
                } else {
                    logger.warn("the mappedFile re delete failed, " + mappedFile.getFileName());
                }

                return result;
            }
        }

        return false;
    }

    public void shutdown(final long intervalForcibly) {
        for (MmapFile mf : this.mappedFiles) {
            mf.shutdown(intervalForcibly);
        }
    }

    public void destroy() {
        for (MmapFile mf : this.mappedFiles) {
            mf.destroy(1000 * 3);
        }
        this.mappedFiles.clear();
        this.flushedWhere = 0;

        // delete parent directory
        File file = new File(storePath);
        if (file.isDirectory()) {
            file.delete();
        }
    }

    public boolean rebuildWithPos(long pos) {
        truncateOffset(-1);
        getLastMappedFile(pos);
        truncateOffset(pos);
        resetOffset(pos);
        return pos == getMaxWrotePosition() && pos == getMinOffset();
    }

    public long getFlushedWhere() {
        return flushedWhere;
    }

    public void setFlushedWhere(long flushedWhere) {
        this.flushedWhere = flushedWhere;
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }

    public List<MmapFile> getMappedFiles() {
        return mappedFiles;
    }

    public int getMappedFileSize() {
        return mappedFileSize;
    }

    public long getCommittedWhere() {
        return committedWhere;
    }

    public void setCommittedWhere(final long committedWhere) {
        this.committedWhere = committedWhere;
    }
}
