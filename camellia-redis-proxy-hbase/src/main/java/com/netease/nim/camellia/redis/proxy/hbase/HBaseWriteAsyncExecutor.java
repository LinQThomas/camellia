package com.netease.nim.camellia.redis.proxy.hbase;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.netease.nim.camellia.core.util.CamelliaThreadFactory;
import com.netease.nim.camellia.hbase.CamelliaHBaseTemplate;
import com.netease.nim.camellia.redis.CamelliaRedisTemplate;
import com.netease.nim.camellia.redis.proxy.hbase.conf.RedisHBaseConfiguration;
import com.netease.nim.camellia.redis.proxy.hbase.conf.RedisHBaseProxyHostCounter;
import com.netease.nim.camellia.redis.proxy.hbase.monitor.RedisHBaseMonitor;
import com.netease.nim.camellia.redis.proxy.hbase.serialize.HBaseWriteOpeSerializeUtil;
import com.netease.nim.camellia.redis.toolkit.lock.CamelliaRedisLock;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 
 * Created by caojiajun on 2020/5/8.
 */
public class HBaseWriteAsyncExecutor {

    private static final Logger logger = LoggerFactory.getLogger(HBaseWriteAsyncExecutor.class);

    private final CamelliaRedisTemplate redisTemplate;

    public HBaseWriteAsyncExecutor(CamelliaRedisTemplate redisTemplate, CamelliaHBaseTemplate hBaseTemplate) {
        this.redisTemplate = redisTemplate;
        HBaseAsyncWriteFlushCore hBaseWriteFlush = new HBaseAsyncWriteFlushCore(redisTemplate, hBaseTemplate);
        hBaseWriteFlush.start();
    }

    public void put(byte[] key, List<Put> putList) {
        int hashCode = Arrays.hashCode(key);
        int topicCount = RedisHBaseConfiguration.hbaseWriteAsyncTopicProducerCount();
        int index = Math.abs(hashCode) % topicCount;
        String topic = RedisHBaseConfiguration.hbaseWriteAsyncTopicPrefix() + index;
        JSONObject jsonObject = HBaseWriteOpeSerializeUtil.serializePutList(putList);
        redisTemplate.lpush(topic, jsonObject.toJSONString());
    }

    public void delete(byte[] key, List<Delete> deleteList) {
        int hashCode = Arrays.hashCode(key);
        int topicCount = RedisHBaseConfiguration.hbaseWriteAsyncTopicProducerCount();
        int index = Math.abs(hashCode) % topicCount;
        String topic = RedisHBaseConfiguration.hbaseWriteAsyncTopicPrefix() + index;
        JSONObject jsonObject = HBaseWriteOpeSerializeUtil.serializeDeleteList(deleteList);
        redisTemplate.lpush(topic, jsonObject.toJSONString());
    }

    /**
     * 包括一个check线程，用于flush线程的启停和flush线程的负载均衡
     * 以及分配给本实例的flush线程集合
     */
    public static class HBaseAsyncWriteFlushCore {
        private final CamelliaRedisTemplate redisTemplate;
        private final CamelliaHBaseTemplate hBaseTemplate;
        private final Map<String, CamelliaRedisLock> lockMap = new HashMap<>();
        private final Map<String, FlushThread> flushThreadMap = new HashMap<>();
        private final Object lock = new Object();
        private final AtomicBoolean running = new AtomicBoolean(true);

        public HBaseAsyncWriteFlushCore(CamelliaRedisTemplate redisTemplate, CamelliaHBaseTemplate hBaseTemplate) {
            this.redisTemplate = redisTemplate;
            this.hBaseTemplate = hBaseTemplate;
        }

        public void start() {
            checkFlushThread();
            int intervalSeconds = RedisHBaseConfiguration.hbaseWriteAsyncFlushThreadCheckIntervalSeconds();
            Executors.newSingleThreadScheduledExecutor(new CamelliaThreadFactory(HBaseAsyncWriteFlushCore.class))
                    .scheduleAtFixedRate(this::checkFlushThread, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            logger.info("HBaseAsyncWriteFlushCore flush-thread-check start! check interval seconds = {}", intervalSeconds);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (lock) {
                    running.compareAndSet(true, false);
                    Set<String> topics = new HashSet<>(lockMap.keySet());
                    logger.warn("stop flush-thread-check, will release lock and close flush thread, size = {}", topics.size());
                    for (String topic : topics) {
                        try {
                            CamelliaRedisLock redisLock = lockMap.get(topic);
                            FlushThread flushThread = flushThreadMap.get(topic);
                            flushThread.close();
                            redisLock.release();
                            lockMap.remove(topic);
                            flushThreadMap.remove(topic);
                        } catch (Exception e) {
                            logger.error("release lock or close flush thread error, topic = {}", topic, e);
                        }
                    }
                    logger.warn("flush-thread-check stop ok.");
                }
            }));
        }

        private void checkFlushThread() {
            try {
                if (!running.get()) {
                    logger.warn("flush-thread-check not running, skip check!");
                    return;
                }
                synchronized (lock) {
                    int topicCount = RedisHBaseConfiguration.hbaseWriteAsyncTopicConsumerCount();
                    int instanceCount = RedisHBaseProxyHostCounter.redisHBaseProxyInstanceCount;
                    int targetThread;
                    if (topicCount % instanceCount == 0) {
                        targetThread = topicCount / instanceCount;
                    } else {
                        targetThread = topicCount / instanceCount + 1;//平均分配+1，确保每个topic都有消费者
                    }

                    int countGap = targetThread - lockMap.size();
                    if (countGap > 0) {
                        if (countGap > 1) {//超过一个的差距，说明不平衡
                            logger.info("FlushThread.size too small, try to start new flush thread, target.size = {}, current.size = {}, try.start.size = {}",
                                    targetThread, lockMap.size(), countGap);
                        }
                        for (int i = 0; i < topicCount; i++) {
                            String topic = RedisHBaseConfiguration.hbaseWriteAsyncTopicPrefix() + i;
                            if (lockMap.containsKey(topic)) {
                                continue;
                            }
                            //通过锁来保证每个topic只有一个消费者
                            String lockTopic = topic + "~lock";
                            int acquireTimeoutMillis = RedisHBaseConfiguration.hbaseWriteAsyncLockAcquireTimeoutMillis();
                            int expireMillis = RedisHBaseConfiguration.hbaseWriteAsyncLockExpireMillis();
                            CamelliaRedisLock redisLock = CamelliaRedisLock.newLock(redisTemplate, lockTopic, acquireTimeoutMillis, expireMillis);
                            boolean tryLock = redisLock.tryLock();
                            if (tryLock) {
                                FlushThread flushThread = new FlushThread(topic, redisTemplate, hBaseTemplate);
                                flushThread.start();
                                logger.info("FlushThread start, topic = {}", topic);
                                lockMap.put(topic, redisLock);
                                flushThreadMap.put(topic, flushThread);
                            }
                            if (lockMap.size() == targetThread) {
                                break;
                            }
                        }
                        if (lockMap.size() < targetThread - 1) {//超过一个的差距，说明不平衡
                            logger.warn("FlushThread.size too small, target.size = {}, current.size = {}", targetThread, lockMap.size());
                        }
                    } else if (countGap < 0) {
                        countGap = -1 * countGap;
                        logger.warn("FlushThread.size too large, will close some one, target.size = {}, current.size = {}, will.close.size = {}",
                                targetThread, lockMap.size(), countGap);
                        Set<String> topics = new HashSet<>(lockMap.keySet());
                        for (String topic : topics) {
                            if (countGap == 0) break;
                            CamelliaRedisLock redisLock = lockMap.get(topic);
                            FlushThread flushThread = flushThreadMap.get(topic);
                            if (countGap > 0) {
                                flushThread.close();
                                redisLock.release();
                                lockMap.remove(topic);
                                flushThreadMap.remove(topic);
                                countGap--;
                            }
                        }
                    }
                    Set<String> topics = new HashSet<>(lockMap.keySet());
                    for (String topic : topics) {
                        CamelliaRedisLock redisLock = lockMap.get(topic);
                        boolean renew = redisLock.renew();
                        if (!renew && !redisLock.isLockOk()) {
                            logger.warn("renew fail, topic = {}", topic);
                            FlushThread flushThread = flushThreadMap.get(topic);
                            flushThread.close();
                            redisLock.release();
                            lockMap.remove(topic);
                            flushThreadMap.remove(topic);
                        }
                    }
                    RedisHBaseMonitor.refreshHBaseAsyncWriteTopics(lockMap.keySet());
                }
            } catch (Exception e) {
                logger.error("checkFlushThread error", e);
            }
        }

        private static class FlushThread extends Thread {
            private static final AtomicLong idGenerator = new AtomicLong(0L);

            private final String topic;
            private final CamelliaRedisTemplate redisTemplate;
            private final CamelliaHBaseTemplate hBaseTemplate;
            private boolean start = true;
            private final long id;

            public FlushThread(String topic, CamelliaRedisTemplate redisTemplate, CamelliaHBaseTemplate hBaseTemplate) {
                this.topic = topic;
                this.redisTemplate = redisTemplate;
                this.hBaseTemplate = hBaseTemplate;
                this.id = idGenerator.incrementAndGet();
                setName("FlushThread-" + topic + "-" + id);
            }

            @Override
            public void run() {
                List<Put> putBuffer = new ArrayList<>();
                List<Delete> deleteBuffer = new ArrayList<>();
                boolean needFlush = false;
                while (start) {
                    try {
                        String value = redisTemplate.rpop(topic);
                        if (value != null) {
                            Pair<HBaseWriteOpeSerializeUtil.Type, List<Row>> pair = HBaseWriteOpeSerializeUtil.parse(JSON.parseObject(value));
                            if (pair == null) continue;
                            HBaseWriteOpeSerializeUtil.Type type = pair.getFirst();
                            List<Row> rows = pair.getSecond();
                            if (type == null || rows == null || rows.isEmpty()) continue;
                            if (type == HBaseWriteOpeSerializeUtil.Type.PUT) {
                                for (Row row : rows) {
                                    putBuffer.add((Put) row);
                                }
                                if (!deleteBuffer.isEmpty()) {
                                    hBaseTemplate.delete(RedisHBaseConfiguration.hbaseTableName(), new ArrayList<>(deleteBuffer));
                                    if (logger.isDebugEnabled()) {
                                        logger.debug("flush delete for new put, size = {}", deleteBuffer.size());
                                    }
                                    deleteBuffer.clear();
                                }
                                if (putBuffer.size() > RedisHBaseConfiguration.hbaseWriteAsyncBatchSize()) {
                                    needFlush = true;
                                }
                            } else if (type == HBaseWriteOpeSerializeUtil.Type.DELETE) {
                                for (Row row : rows) {
                                    deleteBuffer.add((Delete) row);
                                }
                                if (!putBuffer.isEmpty()) {
                                    hBaseTemplate.put(RedisHBaseConfiguration.hbaseTableName(), new ArrayList<>(putBuffer));
                                    if (logger.isDebugEnabled()) {
                                        logger.debug("flush put for new delete, size = {}", putBuffer.size());
                                    }
                                    putBuffer.clear();
                                }
                                if (deleteBuffer.size() > RedisHBaseConfiguration.hbaseWriteAsyncBatchSize()) {
                                    needFlush = true;
                                }
                            }
                        } else {
                            if (!deleteBuffer.isEmpty() || !putBuffer.isEmpty()) {
                                needFlush = true;
                            } else {
                                try {
                                    TimeUnit.MILLISECONDS.sleep(RedisHBaseConfiguration.hbaseWriteAsyncConsumeIntervalMillis());
                                } catch (InterruptedException e) {
                                    logger.error(e.getMessage(), e);
                                }
                            }
                        }
                        if (needFlush) {
                            flush(putBuffer, deleteBuffer);
                            needFlush = false;
                        }
                    } catch (Exception e) {
                        logger.error("FlushThread error, id = {}, topic = {}", id, topic, e);
                    }
                }
                flush(putBuffer, deleteBuffer);
                logger.warn("FlushThread close ok, id = {}, topic = {}", id, topic);
            }

            private void flush(List<Put> putBuffer, List<Delete> deleteBuffer) {
                if (!deleteBuffer.isEmpty()) {
                    hBaseTemplate.delete(RedisHBaseConfiguration.hbaseTableName(), new ArrayList<>(deleteBuffer));
                    if (logger.isDebugEnabled()) {
                        logger.debug("flush delete, size = {}", deleteBuffer.size());
                    }
                    deleteBuffer.clear();
                } else if (!putBuffer.isEmpty()) {
                    hBaseTemplate.put(RedisHBaseConfiguration.hbaseTableName(), new ArrayList<>(putBuffer));
                    if (logger.isDebugEnabled()) {
                        logger.debug("flush put, size = {}", putBuffer.size());
                    }
                    putBuffer.clear();
                }
            }

            public void close() {
                logger.warn("FlushThread will close, id = {}, topic = {}", id, topic);
                start = false;
            }
        }
    }
}
