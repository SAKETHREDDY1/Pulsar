/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.io.kinesis;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration.ThreadingModel;
import com.amazonaws.services.kinesis.producer.UserRecordFailedException;
import com.amazonaws.services.kinesis.producer.UserRecordResult;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.aws.AbstractAwsConnector;
import org.apache.pulsar.io.aws.AwsCredentialProviderPlugin;
import org.apache.pulsar.io.common.IOConfigUtils;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;
import org.apache.pulsar.io.kinesis.KinesisSinkConfig.MessageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Kinesis sink which can be configured by {@link KinesisSinkConfig}.
 * <pre>
 * {@link KinesisSinkConfig} accepts
 * 1. <b>awsEndpoint:</b> kinesis end-point url can be found at : https://docs.aws.amazon.com/general/latest/gr/rande.html
 * 2. <b>awsRegion:</b> appropriate aws region eg: us-west-1, us-west-2
 * 3. <b>awsKinesisStreamName:</b> kinesis stream name
 * 4. <b>awsCredentialPluginName:</b> Fully-Qualified class name of implementation of {@link AwsCredentialProviderPlugin}.
 *    - It is a factory class which creates an {@link AWSCredentialsProvider} that will be used by {@link KinesisProducer}
 *    - If it is empty then {@link KinesisSink} creates default {@link AWSCredentialsProvider}
 *      which accepts json-map of credentials in awsCredentialPluginParam
 *      eg: awsCredentialPluginParam = {"accessKey":"my-access-key","secretKey":"my-secret-key"}
 * 5. <b>awsCredentialPluginParam:</b> json-parameters to initialize {@link AwsCredentialProviderPlugin}
 * 6. messageFormat: enum:["ONLY_RAW_PAYLOAD","FULL_MESSAGE_IN_JSON"]
 *   a. ONLY_RAW_PAYLOAD:     publishes raw payload to stream
 *   b. FULL_MESSAGE_IN_JSON: publish full message (encryptionCtx + properties + payload) in json format
 *   json-schema:
 *   {"type":"object","properties":{"encryptionCtx":{"type":"object","properties":{"metadata":{"type":"object","additionalProperties":{"type":"string"}},"uncompressedMessageSize":{"type":"integer"},"keysMetadataMap":{"type":"object","additionalProperties":{"type":"object","additionalProperties":{"type":"string"}}},"keysMapBase64":{"type":"object","additionalProperties":{"type":"string"}},"encParamBase64":{"type":"string"},"compressionType":{"type":"string","enum":["NONE","LZ4","ZLIB"]},"batchSize":{"type":"integer"},"algorithm":{"type":"string"}}},"payloadBase64":{"type":"string"},"properties":{"type":"object","additionalProperties":{"type":"string"}}}}
 *   Example:
 *   {"payloadBase64":"cGF5bG9hZA==","properties":{"prop1":"value"},"encryptionCtx":{"keysMapBase64":{"key1":"dGVzdDE=","key2":"dGVzdDI="},"keysMetadataMap":{"key1":{"ckms":"cmks-1","version":"v1"},"key2":{"ckms":"cmks-2","version":"v2"}},"metadata":{"ckms":"cmks-1","version":"v1"},"encParamBase64":"cGFyYW0=","algorithm":"algo","compressionType":"LZ4","uncompressedMessageSize":10,"batchSize":10}}
 * </pre>
 *
 *
 *
 */
@Connector(
    name = "kinesis",
    type = IOType.SINK,
    help = "A sink connector that copies messages from Pulsar to Kinesis",
    configClass = KinesisSinkConfig.class
)
public class KinesisSink extends AbstractAwsConnector implements Sink<GenericObject> {

    private static final Logger LOG = LoggerFactory.getLogger(KinesisSink.class);

    private KinesisProducer kinesisProducer;
    private KinesisSinkConfig kinesisSinkConfig;
    private String streamName;
    private static String pipelineoption = "s3";
    private static final String defaultPartitionedKey = "default";
    private static final int maxPartitionedKeyLength = 256;
    private SinkContext sinkContext;
    private ScheduledExecutorService scheduledExecutor;
    private ObjectMapper objectMapper;
    //
    private static final int FALSE = 0;
    private static final int TRUE = 1;
    private volatile int previousPublishFailed = FALSE;
    private static final AtomicIntegerFieldUpdater<KinesisSink> IS_PUBLISH_FAILED =
            AtomicIntegerFieldUpdater.newUpdater(KinesisSink.class, "previousPublishFailed");

    public static final String METRICS_TOTAL_INCOMING = "_kinesis_total_incoming_";
    public static final String METRICS_TOTAL_INCOMING_BYTES = "_kinesis_total_incoming_bytes_";
    public static final String METRICS_TOTAL_SUCCESS = "_kinesis_total_success_";
    public static final String METRICS_TOTAL_FAILURE = "_kinesis_total_failure_";
    
    private static final String S3 = "s3";
    private static final String KINESIS_STREAM = "kinesisstream";
    
    private long maxBatchSize;
    private final AtomicLong currentBatchSize = new AtomicLong(0L);
    private ArrayBlockingQueue<ByteBuffer> pendingFlushQueue;
    private final AtomicBoolean isFlushRunning = new AtomicBoolean(false);
    
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    
    private void sendUserRecord(ProducerSendCallback producerSendCallback) {
        ListenableFuture<UserRecordResult> addRecordResult = kinesisProducer.addUserRecord(this.streamName,
                producerSendCallback.partitionedKey, producerSendCallback.data);
        addCallback(addRecordResult, producerSendCallback, directExecutor());
    }
    
    private ByteBuffer concatBuffers(List<ByteBuffer> bbs) {
    	
        long length = 0;
        for (ByteBuffer bb : bbs) {
            bb.rewind();
            length += bb.remaining();
        }
        
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Buffers are too large for concatenation");
        }
        
        if (length == 0) {
            return EMPTY;
        }
        
        ByteBuffer bbNew = ByteBuffer.allocateDirect((int) length);
        for (ByteBuffer bb : bbs) {
            bb.rewind();
            bbNew.put(bb);
        }
        
        bbNew.rewind();
        return bbNew;
    }

    @Override
    public void write(Record<GenericObject> record) throws Exception {
        // kpl-thread captures publish-failure. fail the publish on main pulsar-io-thread to maintain the ordering
        if (kinesisSinkConfig.isRetainOrdering() && previousPublishFailed == TRUE) {
            LOG.warn("Skip acking message to retain ordering with previous failed message {}-{}", this.streamName,
                    record.getRecordSequence());
            throw new IllegalStateException("kinesis queue has publish failure");
        }
        String partitionedKey = record.getKey().orElse(record.getTopicName().orElse(defaultPartitionedKey));
        partitionedKey = partitionedKey.length() > maxPartitionedKeyLength
                ? partitionedKey.substring(0, maxPartitionedKeyLength - 1)
                : partitionedKey; // partitionedKey Length must be at least one, and at most 256
        ByteBuffer data = createKinesisMessage(kinesisSinkConfig.getMessageFormat(), record);
        int size = data.remaining();
        
//
//        switch(pipelineoption) {
//        
//	        case KINESIS_STREAM :
//	        	sendUserRecord(ProducerSendCallback.create(this, record, System.nanoTime(), partitionedKey, data));
//	        	break;
//	        case S3 :
//	            pendingFlushQueue.put(data);
//	            currentBatchSize.addAndGet(1);
//	            flushIfNeeded(false);
//	            break;
//	        default :
//	        	sendUserRecord(ProducerSendCallback.create(this, record, System.nanoTime(), partitionedKey, data));
//        }
        pendingFlushQueue.put(data);
        currentBatchSize.addAndGet(1);
        flushIfNeeded(false);
        if (sinkContext != null) {
            sinkContext.recordMetric(METRICS_TOTAL_INCOMING, 1);
            sinkContext.recordMetric(METRICS_TOTAL_INCOMING_BYTES, data.array().length);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Published message to kinesis stream {} with size {}", streamName, size);
        }
    }
    private void unsafeFlush() {
    	
        final List<ByteBuffer> recordsToInsert = Lists.newArrayList();
        while (!pendingFlushQueue.isEmpty() && recordsToInsert.size() < maxBatchSize) {
            ByteBuffer r = pendingFlushQueue.poll();
            if (r != null) {
                recordsToInsert.add(r);
            }
        }
        ByteBuffer batchdata = concatBuffers(recordsToInsert);
        putRecordtoS3(Long.toString(System.nanoTime()),kinesisSinkConfig.getBucketName(),batchdata);
        
    }
    
    private void flush() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("flush requested, pending: {}, batchSize: {}",
                currentBatchSize.get(), maxBatchSize);
        }

        if (pendingFlushQueue.isEmpty()) {
            LOG.info("Skip flushing, because the pending flush queue is empty ...");
            return;
        }

        if (!isFlushRunning.compareAndSet(false, true)) {
        	LOG.info("Skip flushing, because there is an outstanding flush ...");
            return;
        }

        try {
            unsafeFlush();
        } catch (Throwable t) {
        	LOG.error("Caught unexpected exception: ", t);
        } finally {
            isFlushRunning.compareAndSet(true, false);
        }
    }

    private void flushIfNeeded(boolean force) {
        if (isFlushRunning.get()) {
            return;
        }
        if (force || currentBatchSize.get() >= maxBatchSize) {
        	scheduledExecutor.submit(this::flush);
        }		
	}

	@Override
    public void close() {
        if (kinesisProducer != null) {
            kinesisProducer.flush();
            kinesisProducer.destroy();
        }
        flushIfNeeded(true);
        LOG.info("Kinesis sink stopped.");

    }

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) {
    	
 
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        kinesisSinkConfig = IOConfigUtils.loadWithSecrets(config, KinesisSinkConfig.class, sinkContext);
        this.sinkContext = sinkContext;
        
        LOG.info("The open method is triggred" + kinesisSinkConfig.toString());
        
        checkArgument(isNotBlank(kinesisSinkConfig.getAwsKinesisStreamName()), "empty kinesis-stream name");
        checkArgument(isNotBlank(kinesisSinkConfig.getAwsEndpoint())
                        || isNotBlank(kinesisSinkConfig.getAwsRegion()),
                      "Either the aws-end-point or aws-region must be set");
        checkArgument(isNotBlank(kinesisSinkConfig.getAwsCredentialPluginParam()), "empty aws-credential param");

        KinesisProducerConfiguration kinesisConfig = new KinesisProducerConfiguration();
        kinesisConfig.setKinesisEndpoint(kinesisSinkConfig.getAwsEndpoint());
        if (kinesisSinkConfig.getAwsEndpointPort() != null) {
            kinesisConfig.setKinesisPort(kinesisSinkConfig.getAwsEndpointPort());
        }
        kinesisConfig.setRegion(kinesisSinkConfig.getAwsRegion());
        kinesisConfig.setThreadingModel(ThreadingModel.POOLED);
        kinesisConfig.setThreadPoolSize(4);
        kinesisConfig.setCollectionMaxCount(1);
        if (kinesisSinkConfig.getSkipCertificateValidation() != null
                && kinesisSinkConfig.getSkipCertificateValidation()) {
            kinesisConfig.setVerifyCertificate(false);
        }
        AWSCredentialsProvider credentialsProvider = createCredentialProvider(
                kinesisSinkConfig.getAwsCredentialPluginName(),
                kinesisSinkConfig.getAwsCredentialPluginParam())
            .getCredentialProvider();
        kinesisConfig.setCredentialsProvider(credentialsProvider);
        
    	maxBatchSize = kinesisSinkConfig.getBatchsize();
    	pendingFlushQueue = new ArrayBlockingQueue<ByteBuffer>(kinesisSinkConfig.getBatchsize());
    	
        this.streamName = kinesisSinkConfig.getAwsKinesisStreamName();
        this.kinesisProducer = new KinesisProducer(kinesisConfig);
        this.objectMapper = new ObjectMapper();
        pipelineoption = kinesisSinkConfig.getPipelineoption();
        if (kinesisSinkConfig.isJsonIncludeNonNulls()) {
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        }
        IS_PUBLISH_FAILED.set(this, FALSE);

        LOG.info("Kinesis sink started. {}",
                ReflectionToStringBuilder.toString(kinesisConfig, ToStringStyle.SHORT_PREFIX_STYLE));
    }

    private static final class ProducerSendCallback implements FutureCallback<UserRecordResult> {

        private Record<GenericObject> resultContext;
        private long startTime = 0;
        private final Handle<ProducerSendCallback> recyclerHandle;
        private KinesisSink kinesisSink;
        private Backoff backoff;
        private String partitionedKey;
        private ByteBuffer data;

        private ProducerSendCallback(Handle<ProducerSendCallback> recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }

        static ProducerSendCallback create(KinesisSink kinesisSink, Record<GenericObject> resultContext, long startTime,
                                           String partitionedKey, ByteBuffer data) {
            ProducerSendCallback sendCallback = RECYCLER.get();
            sendCallback.resultContext = resultContext;
            sendCallback.kinesisSink = kinesisSink;
            sendCallback.startTime = startTime;
            sendCallback.partitionedKey = partitionedKey;
            sendCallback.data = data;
            if (kinesisSink.kinesisSinkConfig.isRetainOrdering() && sendCallback.backoff == null) {
                sendCallback.backoff = new Backoff(kinesisSink.kinesisSinkConfig.getRetryInitialDelayInMillis(),
                        TimeUnit.MILLISECONDS, kinesisSink.kinesisSinkConfig.getRetryMaxDelayInMillis(),
                        TimeUnit.MILLISECONDS, 0, TimeUnit.SECONDS);
            }
            return sendCallback;
        }

        private void recycle() {
            resultContext = null;
            kinesisSink = null;
            startTime = 0;
            if (backoff != null) {
                backoff.reset();
            }
            partitionedKey = null;
            data = null;
            recyclerHandle.recycle(this);
        }

        private static final Recycler<ProducerSendCallback> RECYCLER = new Recycler<ProducerSendCallback>() {
            @Override
            protected ProducerSendCallback newObject(Handle<ProducerSendCallback> handle) {
                return new ProducerSendCallback(handle);
            }
        };

        @Override
        public void onSuccess(UserRecordResult result) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Successfully published message for {}-{} with latency {}",
                        kinesisSink.streamName, result.getShardId(),
                        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startTime)));
            }
            if (kinesisSink.sinkContext != null) {
                kinesisSink.sinkContext.recordMetric(METRICS_TOTAL_SUCCESS, 1);
            }
            kinesisSink.previousPublishFailed = FALSE;
            this.resultContext.ack();
            recycle();
        }

        @Override
        public void onFailure(Throwable exception) {
            if (exception instanceof UserRecordFailedException) {
                // If the exception is UserRecordFailedException, we need to extract it to see real error messages.
                UserRecordFailedException failedException = (UserRecordFailedException) exception;
                StringBuffer stringBuffer = new StringBuffer();
                failedException.getResult().getAttempts().forEach(attempt ->
                        stringBuffer.append(String.format("errorMessage:%s, errorCode:%s, delay:%d, duration:%d;",
                                attempt.getErrorMessage(), attempt.getErrorCode(), attempt.getDelay(), attempt.getDuration())));
                LOG.error("[{}] Failed to published message for replicator of {}-{}: Attempts:{}",
                        kinesisSink.streamName, resultContext.getPartitionId(),
                        resultContext.getRecordSequence(), stringBuffer);
            } else {
                if (StringUtils.isEmpty(exception.getMessage())) {
                    LOG.error("[{}] Failed to published message for replicator of {}-{}", kinesisSink.streamName,
                        resultContext.getPartitionId(), resultContext.getRecordSequence(), exception);
                } else {
                    LOG.error("[{}] Failed to published message for replicator of {}-{}, {} ", kinesisSink.streamName,
                        resultContext.getPartitionId(), resultContext.getRecordSequence(), exception.getMessage());
                }
            }
            kinesisSink.previousPublishFailed = TRUE;
            if (kinesisSink.sinkContext != null) {
                kinesisSink.sinkContext.recordMetric(METRICS_TOTAL_FAILURE, 1);
            }
            if (backoff != null) {
                long nextDelay = backoff.next();
                LOG.info("[{}] Retry to publish message for replicator of {}-{} after {} ms.", kinesisSink.streamName,
                        resultContext.getPartitionId(), resultContext.getRecordSequence(), nextDelay);
                kinesisSink.scheduledExecutor.schedule(() -> kinesisSink.sendUserRecord(this),
                        nextDelay, TimeUnit.MICROSECONDS);
            } else {
                recycle();
            }
        }
    }

    public ByteBuffer createKinesisMessage(MessageFormat msgFormat, Record<GenericObject> record)
            throws JsonProcessingException {
        switch (msgFormat) {
            case FULL_MESSAGE_IN_JSON:
                return ByteBuffer.wrap(Utils.serializeRecordToJson(record).getBytes(StandardCharsets.UTF_8));
            case FULL_MESSAGE_IN_FB:
                return Utils.serializeRecordToFlatBuffer(record);
            case FULL_MESSAGE_IN_JSON_EXPAND_VALUE:
                return ByteBuffer.wrap(Utils.serializeRecordToJsonExpandingValue(objectMapper, record)
                        .getBytes(StandardCharsets.UTF_8));
            default:
                // send raw-message
                return ByteBuffer.wrap(Utils.getMessage(record).getData());
        }
    }
    
    private void putRecordtoS3(String KeyName, String bucketName,ByteBuffer bytebuffer) {
    	
		Region region = Region.US_EAST_1;
		S3Client s3 = S3Client.builder()
                .region(region)
                .build();

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(KeyName)
                .build();
        try{
        	s3.putObject(objectRequest, RequestBody.fromByteBuffer(bytebuffer));
        }catch(Exception e) {
        	System.out.println(e);
        }
        
    }
}
