/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.testsuite.util;

import io.netty5.util.CharsetUtil;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;
import org.junit.jupiter.api.TestInfo;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import javax.management.MBeanServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

public final class TestUtils {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(TestUtils.class);

    private static final Method hotspotMXBeanDumpHeap;
    private static final Object hotspotMXBean;

    private static final long DUMP_PROGRESS_LOGGING_INTERVAL = TimeUnit.SECONDS.toNanos(5);

    static {
        // Retrieve the hotspot MXBean and its class if available.
        Object mxBean;
        Method mxBeanDumpHeap;
        try {
            Class<?> clazz = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            mxBean = ManagementFactory.newPlatformMXBeanProxy(
                    server, "com.sun.management:type=HotSpotDiagnostic", clazz);
            mxBeanDumpHeap = clazz.getMethod("dumpHeap", String.class, boolean.class);
        } catch (Exception ignored) {
            mxBean = null;
            mxBeanDumpHeap = null;
        }

        hotspotMXBean = mxBean;
        hotspotMXBeanDumpHeap = mxBeanDumpHeap;
    }

    /**
     * Returns the method name of the current test.
     */
    public static String testMethodName(TestInfo testInfo) {
        String testMethodName = testInfo.getTestMethod().map(Method::getName).orElse("[unknown method]");
        if (testMethodName.contains("[")) {
            testMethodName = testMethodName.substring(0, testMethodName.indexOf('['));
        }
        return testMethodName;
    }

    public static void dump(String filenamePrefix) throws IOException {
        requireNonNull(filenamePrefix, "filenamePrefix");

        final String timestamp = timestamp();
        final File heapDumpFile = new File(filenamePrefix + '.' + timestamp + ".hprof");
        if (heapDumpFile.exists()) {
            if (!heapDumpFile.delete()) {
                throw new IOException("Failed to remove the old heap dump: " + heapDumpFile);
            }
        }

        final File threadDumpFile = new File(filenamePrefix + '.' + timestamp + ".threads");
        if (threadDumpFile.exists()) {
            if (!threadDumpFile.delete()) {
                throw new IOException("Failed to remove the old thread dump: " + threadDumpFile);
            }
        }

        dumpHeap(heapDumpFile);
        dumpThreads(threadDumpFile);
    }

    public static void compressHeapDumps() throws IOException {
        final File[] files = new File(System.getProperty("user.dir")).listFiles((dir, name) -> name.endsWith(".hprof"));
        if (files == null) {
            logger.warn("failed to find heap dump due to I/O error!");
            return;
        }

        final byte[] buf = new byte[65536];
        final LZMA2Options options = new LZMA2Options(LZMA2Options.PRESET_DEFAULT);

        for (File file: files) {
            final String filename = file.toString();
            final String xzFilename = filename + ".xz";
            final long fileLength = file.length();

            logger.info("Compressing the heap dump: {}", xzFilename);

            long lastLogTime = System.nanoTime();
            long counter = 0;

            try (InputStream in = new FileInputStream(filename);
                 OutputStream out = new XZOutputStream(new FileOutputStream(xzFilename), options)) {
                for (;;) {
                    int readBytes = in.read(buf);
                    if (readBytes < 0) {
                        break;
                    }
                    if (readBytes == 0) {
                        continue;
                    }

                    out.write(buf, 0, readBytes);
                    counter += readBytes;

                    long currentTime = System.nanoTime();
                    if (currentTime - lastLogTime > DUMP_PROGRESS_LOGGING_INTERVAL) {
                        logger.info("Compressing the heap dump: {} ({}%)",
                                    xzFilename, counter * 100 / fileLength);
                        lastLogTime = currentTime;
                    }
                }
            } catch (Throwable t) {
                logger.warn("Failed to compress the heap dump: {}", xzFilename, t);
            }

            // Delete the uncompressed dump in favor of the compressed one.
            if (!file.delete()) {
                logger.warn("Failed to delete the uncompressed heap dump: {}", filename);
            }
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("HHmmss.SSS").format(new Date());
    }

    private static void dumpHeap(File file) {
        if (hotspotMXBean == null) {
            logger.warn("Can't dump heap: HotSpotDiagnosticMXBean unavailable");
            return;
        }

        final String filename = file.toString();
        logger.info("Dumping heap: {}", filename);
        try {
            hotspotMXBeanDumpHeap.invoke(hotspotMXBean, filename, true);
        } catch (Exception e) {
            logger.warn("Failed to dump heap: {}", filename, e);
        }
    }

    private static void dumpThreads(File file) {
        final String filename = file.toString();
        OutputStream out = null;
        try {
            logger.info("Dumping threads: {}", filename);
            final StringBuilder buf = new StringBuilder(8192);
            try {
                for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
                    buf.append(info);
                }
                buf.append('\n');
            } catch (UnsupportedOperationException ignored) {
                logger.warn("Can't dump threads: ThreadMXBean.dumpAllThreads() unsupported");
                return;
            }

            out = new FileOutputStream(file);
            out.write(buf.toString().getBytes(CharsetUtil.UTF_8));
        } catch (Exception e) {
            logger.warn("Failed to dump threads: {}", filename, e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Ignore.
                }
            }
        }
    }

    private TestUtils() { }
}