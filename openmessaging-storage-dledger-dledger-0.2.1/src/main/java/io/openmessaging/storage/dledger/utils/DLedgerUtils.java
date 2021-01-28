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

package io.openmessaging.storage.dledger.utils;

import java.io.File;
import java.text.NumberFormat;
import java.util.Calendar;

public class DLedgerUtils {
    public static void sleep(long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (Throwable ignored) {

        }
    }

    public static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    public static String offset2FileName(final long offset) {
        NumberFormat nf = NumberFormat.getInstance();
        nf.setMinimumIntegerDigits(20);
        nf.setMaximumFractionDigits(0);
        nf.setGroupingUsed(false);
        return nf.format(offset);
    }

    public static long computeEclipseTimeMilliseconds(final long beginTime) {
        return System.currentTimeMillis() - beginTime;
    }

    /**
     * 判断是否到了某些时间点
     * @param when 时间点小时字符串
     * @return
     */
    public static boolean isItTimeToDo(final String when) {
        //获取所有时间点
        String[] whiles = when.split(";");
        if (whiles.length > 0) {
            Calendar now = Calendar.getInstance();
            for (String w : whiles) {//遍历时间点
                //获取需要的时间点
                int nowHour = Integer.parseInt(w);
                //当前小时
                if (nowHour == now.get(Calendar.HOUR_OF_DAY)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 获取某个文件夹已经使用空间占总分配空间的百分比
     * @param path 路径
     * @return
     */
    public static double getDiskPartitionSpaceUsedPercent(final String path) {
        if (null == path || path.isEmpty())
            return -1;

        try {
            //获取文件夹
            File file = new File(path);

            if (!file.exists())
                return -1;

            //获取文件夹分配的总的大小
            long totalSpace = file.getTotalSpace();

            if (totalSpace > 0) {
                //获取剩余空间
                long freeSpace = file.getFreeSpace();
                //获取已经使用的空间
                long usedSpace = totalSpace - freeSpace;

                //返回已经使用百分比
                return usedSpace / (double) totalSpace;
            }
        } catch (Exception e) {
            return -1;
        }
        return -1;
    }
}
