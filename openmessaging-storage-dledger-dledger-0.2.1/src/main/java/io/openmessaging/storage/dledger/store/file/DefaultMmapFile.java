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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认的mappedFile文件
 */
public class DefaultMmapFile extends ReferenceResource implements MmapFile {
    public static final int OS_PAGE_SIZE = 1024 * 4;
    protected static Logger logger = LoggerFactory.getLogger(DefaultMmapFile.class);

    /**
     * 总的mappedFile所占用的内存大小
     */
    private static final AtomicLong TOTAL_MAPPED_VIRTUAL_MEMORY = new AtomicLong(0);

    /**
     * 总的mappedFile的数量
     */
    private static final AtomicInteger TOTAL_MAPPED_FILES = new AtomicInteger(0);

    final AtomicInteger startPosition = new AtomicInteger(0);

    /**
     * mappedFile文件已经写到的位置
     */
    final AtomicInteger wrotePosition = new AtomicInteger(0);

    /**
     * mappedFile文件提交的位置
     */
    final AtomicInteger committedPosition = new AtomicInteger(0);

    /**
     * mappedFile文件刷新的位置
     */
    final AtomicInteger flushedPosition = new AtomicInteger(0);

    /**
     * 映射的磁盘文件对象
     */
    protected File file;

    /**
     * 磁盘文件大小
     */
    int fileSize;

    /**
     * mappedFIle文件在整个mappedFileList中的起始便宜来了
     */
    long fileFromOffset;

    /**
     * 与磁盘文件的FileChannel
     */
    private FileChannel fileChannel;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 磁盘数据的映射缓存
     */
    private MappedByteBuffer mappedByteBuffer;

    /**
     * 最后一次存储消息的时间
     */
    private volatile long storeTimestamp = 0;

    /**
     * 当前mappedFile是否是整个mappedFileList系统中第一个创建的
     */
    private boolean firstCreateInQueue = false;

    /**
     * 实例化一个默认的mappedFile发文件
     * @param fileName 文件全名
     * @param fileSize 文件大小
     * @throws IOException
     */
    public DefaultMmapFile(final String fileName, final int fileSize) throws IOException {
        //设置文件全名
        this.fileName = fileName;
        //设置文件大小
        this.fileSize = fileSize;
        //设置文件
        this.file = new File(fileName);
        //文件在整个mappedFileList文件系统的起始偏移量
        this.fileFromOffset = Long.parseLong(this.file.getName());
        //创建mappedFile是否成功
        boolean ok = false;

        //保证存储文件的父目录一定存在 不存在创建父母来了
        ensureDirOK(this.file.getParent());

        try {
            //建立与磁盘文件的channel连接
            this.fileChannel = new RandomAccessFile(this.file, "rw").getChannel();
            //将磁盘文件数据加载到缓存区
            this.mappedByteBuffer = this.fileChannel.map(MapMode.READ_WRITE, 0, fileSize);
            //增加总的mappedFile占用的内存大小
            TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(fileSize);
            //增加总的mappedFile数量
            TOTAL_MAPPED_FILES.incrementAndGet();
            //设置创建mappedFile成功
            ok = true;
        } catch (FileNotFoundException e) {
            logger.error("create file channel " + this.fileName + " Failed. ", e);
            throw e;
        } catch (IOException e) {
            logger.error("map file " + this.fileName + " Failed. ", e);
            throw e;
        } finally {
            if (!ok && this.fileChannel != null) {//创建mappedFile成功
                //关闭文件通道
                this.fileChannel.close();
            }
        }
    }

    public static int getTotalMappedFiles() {
        return TOTAL_MAPPED_FILES.get();
    }

    public static long getTotalMappedVirtualMemory() {
        return TOTAL_MAPPED_VIRTUAL_MEMORY.get();
    }

    /**
     * 保证父目录存在
     * @param dirName 父目录路径
     */
    public static void ensureDirOK(final String dirName) {
        if (dirName != null) {//路径不为null
            //实例化一个文件
            File f = new File(dirName);
            if (!f.exists()) {//文件不存在
                //创建文件夹
                boolean result = f.mkdirs();
                //记录日志
                logger.info(dirName + " mkdir " + (result ? "OK" : "Failed"));
            }
        }
    }

    /**
     * 清理资源
     * @param buffer
     */
    public static void clean(final ByteBuffer buffer) {
        //不是堆外bytebuffer直接返回
        if (buffer == null || !buffer.isDirect() || buffer.capacity() == 0)
            return;

        //调用bytebuffer的cleaner方法 然后执行clean方法
        invoke(invoke(viewed(buffer), "cleaner"), "clean");
    }

    /**
     * 执行对象的额方法
     * @param target 目标对象
     * @param methodName 方法名
     * @param args 参数类型
     * @return
     */
    private static Object invoke(final Object target, final String methodName, final Class<?>... args) {
        //调用系统权限 执行方法
        return AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    //获取目标对象的方法
                    Method method = method(target, methodName, args);
                    //设置方法可以访问
                    method.setAccessible(true);
                    //执行方法
                    return method.invoke(target);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    /**
     * 获取某个对象的某个方法
     * @param target 对象
     * @param methodName 方法名
     * @param args 参数类型
     * @return
     * @throws NoSuchMethodException
     */
    private static Method method(Object target, String methodName, Class<?>[] args)
        throws NoSuchMethodException {
        try {
            //获取public方法
            return target.getClass().getMethod(methodName, args);
        } catch (NoSuchMethodException e) {
            //获取方法
            return target.getClass().getDeclaredMethod(methodName, args);
        }
    }

    /**
     * 获取某个ByteBuffer的viewByteBuffer
     * @param buffer bytebuffer对象
     * @return
     */
    private static ByteBuffer viewed(ByteBuffer buffer) {
        String methodName = "viewedBuffer";

        //获取bytebuffer所有的方法
        Method[] methods = buffer.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals("attachment")) {
                //找到attachment
                methodName = "attachment";
                break;
            }
        }

        //执行attachment方法
        ByteBuffer viewedBuffer = (ByteBuffer) invoke(buffer, methodName);
        if (viewedBuffer == null)
            return buffer;
        else
            return viewed(viewedBuffer);
    }

    /**
     * 获取当前mappedFile映射的磁盘文件 上一次修改的时间
     * @return
     */
    @Override
    public long getLastModifiedTimestamp() {
        //返回文件上一次修改的时间
        return this.file.lastModified();
    }

    @Override
    public int getFileSize() {
        return fileSize;
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    @Override
    public long getFileFromOffset() {
        return this.fileFromOffset;
    }

    @Override
    public boolean appendMessage(final byte[] data) {
        return appendMessage(data, 0, data.length);
    }

    /**
     * 向mappedFile的中添加字节数组
     * @param data 字节数组
     * @param offset 数组的起始位置
     * @param length 写入字节数
     * @return
     */
    @Override
    public boolean appendMessage(final byte[] data, final int offset, final int length) {
        //获取mappedFile已经写到的位置
        int currentPos = this.wrotePosition.get();

        if ((currentPos + length) <= this.fileSize) {//mappedFile有足够的空白写入字节数组
            //获取切片bytebuffer
            ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
            //设置位置
            byteBuffer.position(currentPos);
            //写入字节数组
            byteBuffer.put(data, offset, length);
            //设置已经写到的位置
            this.wrotePosition.addAndGet(length);
            return true;
        }
        return false;
    }

    /**
     * 将内存中的字节数组刷新到磁盘文件
     * @param flushLeastPages 至少刷新的页数
     * @return
     */
    @Override
    public int flush(final int flushLeastPages) {
        if (this.isAbleToFlush(flushLeastPages)) {//当前mappedFile写满了 一定可以刷新；没有写满 判断已经刷新的位置和已经写到的位置之间可以构成的页数
            if (this.hold()) {//当前线程对mappedFile的引用计数加1
                //获取已经写到的位置
                int value = getReadPosition();
                try {
                    //将mappedByteBuffer中的字节数组强制刷新到磁盘
                    this.mappedByteBuffer.force();
                } catch (Throwable e) {
                    logger.error("Error occurred when force data to disk.", e);
                }

                //设置已经刷新到的位置为已经写到的位置
                this.flushedPosition.set(value);
                //释放当前线程对mappedFile的引用
                this.release();
            } else {
                logger.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
                this.flushedPosition.set(getReadPosition());
            }
        }
        return this.getFlushedPosition();
    }

    @Override
    public int commit(final int commitLeastPages) {
        this.committedPosition.set(this.wrotePosition.get());
        return this.committedPosition.get();
    }

    /**
     * 上次刷新到的位置到mappedFile写到的位置之间的字节数是否刚好可以构成flushLeastPages页
     * @param flushLeastPages 至少刷新的页数
     * @return
     */
    private boolean isAbleToFlush(final int flushLeastPages) {
        //获取上次已经刷新到的位置
        int flushedPos = this.flushedPosition.get();
        //获取已经写到的位置
        int writePos = getReadPosition();

        if (this.isFull()) {//当前mappedFile已经写满 必须将内存中的字节数组刷新到磁盘
            return writePos > flushedPos;
        }

        if (flushLeastPages > 0) {//至少刷新的页数
            return ((writePos / OS_PAGE_SIZE) - (flushedPos / OS_PAGE_SIZE)) >= flushLeastPages;
        }

        //返回可以刷新
        return writePos > flushedPos;
    }

    @Override
    public int getFlushedPosition() {
        return flushedPosition.get();
    }

    /**
     * 设置mappedFile文件刷新的位置
     * @param pos 位置
     */
    @Override
    public void setFlushedPosition(int pos) {
        this.flushedPosition.set(pos);
    }

    @Override public int getStartPosition() {
        return startPosition.get();
    }

    @Override public void setStartPosition(int startPosition) {
        this.startPosition.set(startPosition);
    }

    /**
     * mappedFile文件是否已经写满
     * @return
     */
    @Override
    public boolean isFull() {
        return this.fileSize == this.wrotePosition.get();
    }

    @Override
    public SelectMmapBufferResult selectMappedBuffer(int pos, int size) {
        int readPosition = getReadPosition();
        if ((pos + size) <= readPosition) {

            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMmapBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            } else {
                logger.warn("matched, but hold failed, request pos={} fileFromOffset={}", pos, this.fileFromOffset);
            }
        } else {
            logger.warn("selectMappedBuffer request pos invalid, request pos={} size={} fileFromOffset={} readPos={}", pos, size, fileFromOffset, readPosition);
        }

        return null;
    }


    /**
     * 从mappedFile映射的bytebuffer缓冲区指定位置开始 截取一段bytebuffer
     * @param pos 截取的bytebuffer在映射bytebuffer中的起始位置
     * @return
     */
    @Override
    public SelectMmapBufferResult selectMappedBuffer(int pos) {
        //获取当前mappedFile已经写到的位置
        int readPosition = getReadPosition();
        if (pos < readPosition && pos >= 0) {
            if (this.hold()) {//增加引用
                //截取bytebuffer
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                //指定读取的起始位置
                byteBuffer.position(pos);
                //将要截取的字节数
                int size = readPosition - pos;
                ByteBuffer byteBufferNew = byteBuffer.slice();
                //指定最大位置
                byteBufferNew.limit(size);
                return new SelectMmapBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            }
        }

        return null;
    }

    @Override
    public boolean getData(int pos, int size, ByteBuffer byteBuffer) {
        if (byteBuffer.remaining() < size) {
            return false;
        }

        int readPosition = getReadPosition();
        if ((pos + size) <= readPosition) {

            if (this.hold()) {
                try {
                    int readNum = fileChannel.read(byteBuffer, pos);
                    return size == readNum;
                } catch (Throwable t) {
                    logger.warn("Get data failed pos:{} size:{} fileFromOffset:{}", pos, size, this.fileFromOffset);
                    return false;
                } finally {
                    this.release();
                }
            } else {
                logger.debug("matched, but hold failed, request pos: " + pos + ", fileFromOffset: "
                    + this.fileFromOffset);
            }
        } else {
            logger.warn("selectMappedBuffer request pos invalid, request pos: " + pos + ", size: " + size
                + ", fileFromOffset: " + this.fileFromOffset);
        }

        return false;
    }

    /**
     * 清理资源
     * @param currentRef
     * @return
     */
    @Override
    public boolean cleanup(final long currentRef) {
        if (this.isAvailable()) {//资源可用 直接翻译
            logger.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have not shutdown, stop unmapping.");
            return false;
        }

        if (this.isCleanupOver()) {//已经清理完成 直接返回
            logger.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have cleanup, do not do it again.");
            return true;
        }

        //清理bytebuffer
        clean(this.mappedByteBuffer);
        //减少总的mappedFile占用内存的大小
        TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(this.fileSize * (-1));
        //减少mappedFile文件的数量
        TOTAL_MAPPED_FILES.decrementAndGet();
        logger.info("unmap file[REF:" + currentRef + "] " + this.fileName + " OK");
        return true;
    }

    /**
     * 销毁mappedFile文件
     * @param intervalForcibly 如果有其他线程正在引用资源  关闭资源等待的时长 默认10秒
     * @return
     */
    @Override
    public boolean destroy(final long intervalForcibly) {
        //关闭资源
        this.shutdown(intervalForcibly);

        if (this.isCleanupOver()) {//资源清理完成
            try {
                //关闭文件通道
                this.fileChannel.close();
                logger.info("close file channel " + this.fileName + " OK");

                //开始时间 计算删除磁盘时间消耗的时间
                long beginTime = System.currentTimeMillis();
                //删除磁盘中的文件
                boolean result = this.file.delete();
                logger.info("delete file[REF:" + this.getRefCount() + "] " + this.fileName
                    + (result ? " OK, " : " Failed, ") + "W:" + this.getWrotePosition() + " M:"
                    + this.getFlushedPosition() + ", "
                    + DLedgerUtils.computeEclipseTimeMilliseconds(beginTime));
                //线程等待10秒
                Thread.sleep(10);
            } catch (Exception e) {
                logger.warn("close file channel " + this.fileName + " Failed. ", e);
            }

            return true;
        } else {
            logger.warn("destroy mapped file[REF:" + this.getRefCount() + "] " + this.fileName
                + " Failed. cleanupOver: " + this.cleanupOver);
        }

        return false;
    }

    @Override
    public int getWrotePosition() {
        return wrotePosition.get();
    }

    /**
     * 设置mappedFile已经写到的位置
     * @param pos 位置
     */
    @Override
    public void setWrotePosition(int pos) {
        this.wrotePosition.set(pos);
    }

    /**
     * 获取mappedFile已经写到的位置
     * @return
     */
    @Override
    public int getReadPosition() {
        return this.wrotePosition.get();
    }

    /**
     * 设置mappedFile文件提交的位置
     * @param pos 文件
     */
    @Override
    public void setCommittedPosition(int pos) {
        this.committedPosition.set(pos);
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    @Override
    public MappedByteBuffer getMappedByteBuffer() {
        return mappedByteBuffer;
    }

    @Override
    public ByteBuffer sliceByteBuffer() {
        return this.mappedByteBuffer.slice();
    }

    @Override
    public boolean isFirstCreateInQueue() {
        return firstCreateInQueue;
    }

    @Override
    public void setFirstCreateInQueue(boolean firstCreateInQueue) {
        this.firstCreateInQueue = firstCreateInQueue;
    }

    //testable
    File getFile() {
        return this.file;
    }

    @Override
    public String toString() {
        return this.fileName;
    }
}
