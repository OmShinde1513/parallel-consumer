package io.confluent.parallelconsumer.internal;

/*-
 * Copyright (C) 2020-2024 Confluent, Inc.
 */

import io.confluent.parallelconsumer.ParallelConsumerOptions;
import io.confluent.parallelconsumer.ParallelConsumerOptions.CommitMode;
import io.confluent.parallelconsumer.metrics.PCMetrics;
import io.confluent.parallelconsumer.metrics.PCMetricsDef;
import io.confluent.parallelconsumer.state.WorkManager;
import io.micrometer.core.instrument.Gauge;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.MDC;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

import static io.confluent.csid.utils.StringUtils.msg;
import static io.confluent.parallelconsumer.internal.AbstractParallelEoSStreamProcessor.DEFAULT_TIMEOUT;
import static io.confluent.parallelconsumer.internal.AbstractParallelEoSStreamProcessor.MDC_INSTANCE_ID;
import static io.confluent.parallelconsumer.internal.State.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Subsystem for polling the broker for messages.
 *
 * @param <K>
 * @param <V>
 */
@Slf4j
public class BrokerPollSystem<K, V> implements OffsetCommitter {

    private final ConsumerManager<K, V> consumerManager;

    private State runState = RUNNING;

    private Optional<Future<Boolean>> pollControlThreadFuture = Optional.empty();

    /**
     * While {@link io.confluent.parallelconsumer.internal.State#PAUSED paused} is an externally controlled state that
     * temporarily stops polling and work registration, the {@code paused} flag is used internally to pause
     * subscriptions if polling needs to be throttled.
     */
    @Getter
    private volatile boolean pausedForThrottling = false;

    private final AbstractParallelEoSStreamProcessor<K, V> pc;

    private Optional<ConsumerOffsetCommitter<K, V>> committer = Optional.empty();

    /**
     * Note how this relates to {@link BrokerPollSystem#getLongPollTimeout()} - if longPollTimeout is high and loading
     * factor is low, there may not be enough messages queued up to satisfy demand.
     */
    @Setter
    @Getter
    private static Duration longPollTimeout = Duration.ofMillis(2000);

    private final WorkManager<K, V> wm;

    private final PCMetrics pcMetrics;

    private Gauge statusGauge;
    private Gauge numPausedPartitionsGauge;

    public BrokerPollSystem(ConsumerManager<K, V> consumerMgr, WorkManager<K, V> wm, AbstractParallelEoSStreamProcessor<K, V> pc, final ParallelConsumerOptions<K, V> options) {
        this.wm = wm;
        this.pc = pc;

        this.consumerManager = consumerMgr;

        switch (options.getCommitMode()) {
            case PERIODIC_CONSUMER_SYNC, PERIODIC_CONSUMER_ASYNCHRONOUS -> {
                ConsumerOffsetCommitter<K, V> consumerCommitter = new ConsumerOffsetCommitter<>(consumerMgr, wm, options);
                committer = Optional.of(consumerCommitter);
            }
        }
        pcMetrics = pc.getModule().pcMetrics();
        initMetrics();
    }

    private void initMetrics() {
        statusGauge = pcMetrics.gaugeFromMetricDef(PCMetricsDef.PC_POLLER_STATUS, this, poller -> poller.runState.getValue());
        numPausedPartitionsGauge = pcMetrics.gaugeFromMetricDef(PCMetricsDef.NUM_PAUSED_PARTITIONS,
                this.consumerManager, ConsumerManager::getPausedPartitionSize);
    }

    public void start(String managedExecutorService) {
        ExecutorService executorService;
        try {
            executorService = InitialContext.doLookup(managedExecutorService);
        } catch (NamingException e) {
            log.debug("Couldn't look up an execution service, falling back to Java SE Thread", e);
            executorService = Executors.newSingleThreadExecutor();
        }
        Future<Boolean> submit = executorService.submit(this::controlLoop);
        this.pollControlThreadFuture = Optional.of(submit);
    }

    public void supervise() {
        if (pollControlThreadFuture.isPresent()) {
            Future<Boolean> booleanFuture = pollControlThreadFuture.get();
            if (booleanFuture.isCancelled() || booleanFuture.isDone()) {
                try {
                    booleanFuture.get();
                } catch (Exception e) {
                    throw new InternalRuntimeException("Error in " + BrokerPollSystem.class.getSimpleName() + " system.", e);
                }
            }
        }
    }

    /**
     * @return true if closed cleanly
     */
    private boolean controlLoop() throws TimeoutException, InterruptedException {
        Thread.currentThread().setName("pc-broker-poll");
        pc.getMyId().ifPresent(id -> {
            Thread.currentThread().setName("pc-broker-poll-" + id);
            MDC.put(MDC_INSTANCE_ID, id);
        });
        log.trace("Broker poll control loop start");
        committer.ifPresent(ConsumerOffsetCommitter::claim);
        try {
            while (runState != CLOSED) {
                handlePoll();

                maybeDoCommit();

                switch (runState) {
                    case DRAINING -> {
                        doPause();
                    }
                    case CLOSING -> {
                        doClose();
                    }
                }
            }
            log.debug("Broker poller thread finished normally, returning OK (true) to future...");
            return true;
        } catch (Exception e) {
            log.error("Unknown error", e);
            throw e;
        }
    }

    private void handlePoll() {
        log.trace("Loop: Broker poller: ({})", runState);
        if (runState == RUNNING || runState == DRAINING) { // if draining - subs will be paused, so use this to just sleep
            var polledRecords = pollBrokerForRecords();
            int count = polledRecords.count();
            log.debug("Got {} records in poll result", count);

            if (count > 0) {
                log.trace("Loop: Register work");
                pc.registerWork(polledRecords);
            }
        }
    }

    private void doClose() {
        log.debug("Doing close...");
        doPause();
        maybeCloseConsumerManager();
        runState = CLOSED;
    }

    /**
     * To keep things simple, make sure the correct thread which can make a commit, is the one to close the consumer.
     * This way, if partitions are revoked, the commit can be made inline.
     */
    private void maybeCloseConsumerManager() {
        if (isResponsibleForCommits()) {
            log.debug("Closing {}, first closing consumer...", this.getClass().getSimpleName());
            this.consumerManager.close(DEFAULT_TIMEOUT);
            log.debug("Consumer closed.");
        }
    }

    private boolean isResponsibleForCommits() {
        return committer.isPresent();
    }

    private EpochAndRecordsMap<K, V> pollBrokerForRecords() {

        checkStateForPausingSubscriptions();

        log.debug("Subscriptions are paused: {}", pausedForThrottling);

        boolean pollTimeoutNormally = runState == RUNNING || runState == DRAINING;
        Duration thisLongPollTimeout = pollTimeoutNormally ? BrokerPollSystem.longPollTimeout
                : Duration.ofMillis(1); // Can't use Duration.ZERO - this causes Object#wait to wait forever

        log.debug("Long polling broker with timeout {}, might appear to sleep here if subs are paused, or no data available on broker. Run state: {}", thisLongPollTimeout, runState);
        ConsumerRecords<K, V> poll = consumerManager.poll(thisLongPollTimeout);

        log.debug("Poll completed");

        // build records map
        return new EpochAndRecordsMap<>(poll, wm.getPm());
    }

    private void checkStateForPausingSubscriptions() {
        if (runState == DRAINING) {
            doPause();
        } else {
            managePauseOfSubscription();
        }
    }

    /**
     * Will begin the shutdown process, eventually closing itself once drained
     */
    public void drain() {
        consumerManager.signalStop();
        // idempotent
        if (runState != State.DRAINING) {
            log.debug("Signaling poll system to drain, waking up consumer...");
            runState = State.DRAINING;
            consumerManager.wakeup();
        }
    }

    private final RateLimiter pauseLimiter = new RateLimiter(1);

    private void doPauseMaybe() {
        // idempotent
        if (pausedForThrottling) {
            log.trace("Already paused");
        } else {
            if (pauseLimiter.couldPerform()) {
                pauseLimiter.performIfNotLimited(() -> {
                    doPause();
                });
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Should pause but pause rate limit exceeded {} vs {}.",
                            pauseLimiter.getElapsedDuration(),
                            pauseLimiter.getRate());
                }
            }
        }
    }

    /**
     * Pause all assignments
     */
    private void doPause() {
        if (!pausedForThrottling) {
            pausedForThrottling = true;
            log.debug("Pausing subs");
            Set<TopicPartition> assignment = consumerManager.assignment();
            consumerManager.pause(assignment);
        } else {
            log.debug("Already paused, skipping");
        }
    }

    public void closeAndWait() throws TimeoutException, ExecutionException {
        log.debug("Requesting broker polling system to close...");
        transitionToClosing();
        if (pollControlThreadFuture.isPresent()) {
            log.debug("Wait for loop to finish ending...");
            Future<Boolean> pollControlResult = pollControlThreadFuture.get();
            boolean interrupted = true;
            while (interrupted) {
                try {
                    Boolean pollShutdownSuccess = pollControlResult.get(DEFAULT_TIMEOUT.toMillis(), MILLISECONDS);
                    interrupted = false;
                    if (!pollShutdownSuccess) {
                        log.warn("Broker poll control thread not closed cleanly.");
                    }
                } catch (InterruptedException e) {
                    log.debug("Interrupted waiting for broker poller thread to finish", e);
                } catch (ExecutionException | TimeoutException e) {
                    log.error("Execution or timeout exception waiting for broker poller thread to finish", e);
                    throw e;
                }
            }
        }
        log.debug("Broker poll system finished closing");
    }

    private void transitionToClosing() {
        consumerManager.signalStop();
        log.debug("Poller transitioning to closing, waking up consumer");
        runState = State.CLOSING;
        consumerManager.wakeup();
    }

    /**
     * If we are currently processing too many records, we must stop polling for more from the broker. But we must also
     * make sure we maintain the keep alive with the broker so as not to cause a rebalance.
     */
    private void managePauseOfSubscription() {
        boolean throttle = shouldThrottle();
        log.trace("Need to throttle: {}", throttle);
        if (throttle) {
            doPauseMaybe();
        } else {
            resumeIfPaused();
        }
    }

    /**
     * Has no flap limit, always resume if we need to
     */
    private void resumeIfPaused() {
        // idempotent
        if (pausedForThrottling) {
            log.debug("Resuming consumer, waking up");
            Set<TopicPartition> pausedTopics = consumerManager.paused();
            consumerManager.resume(pausedTopics);
            // trigger consumer to perform a new poll without the assignments paused, otherwise it will continue to long poll on nothing
            consumerManager.wakeup();
            pausedForThrottling = false;
        }
    }

    private boolean shouldThrottle() {
        return wm.shouldThrottle();
    }

    /**
     * Optionally blocks. Threadsafe
     *
     * @see CommitMode
     */
    @SneakyThrows
    @Override
    public void retrieveOffsetsAndCommit() {
        if (runState == RUNNING || runState == DRAINING || runState == CLOSING) {
            // {@link Optional#ifPresentOrElse} only @since 9
            ConsumerOffsetCommitter<K, V> committer = this.committer.orElseThrow(() -> {
                // shouldn't be here
                throw new IllegalStateException("No committer configured");
            });
            committer.commit();
        } else {
            throw new IllegalStateException(msg("Can't commit - not running (state: {}", runState));
        }
    }

    /**
     * Will silently skip if not configured with a committer
     */
    private void maybeDoCommit() throws TimeoutException, InterruptedException {
        if (committer.isPresent()) {
            committer.get().maybeDoCommit();
        }
    }

    /**
     * Wakeup if colling the broker
     */
    public void wakeupIfPaused() {
        if (pausedForThrottling)
            consumerManager.wakeup();
    }

    /**
     * Pause polling from the underlying Kafka Broker.
     * <p>
     * Note: If the poll system is currently not in state
     * {@link io.confluent.parallelconsumer.internal.State#RUNNING running}, calling this method will be a no-op.
     * </p>
     */
    public void pausePollingAndWorkRegistrationIfRunning() {
        if (this.runState == RUNNING) {
            log.info("Transitioning broker poll system to state paused.");
            this.runState = PAUSED;
        } else {
            log.info("Skipping transition of broker poll system to state paused. Current state is {}.", this.runState);
        }
    }

    /**
     * Resume polling from the underlying Kafka Broker.
     * <p>
     * Note: If the poll system is currently not in state
     * {@link io.confluent.parallelconsumer.internal.State#PAUSED paused}, calling this method will be a no-op.
     * </p>
     */
    public void resumePollingAndWorkRegistrationIfPaused() {
        if (this.runState == PAUSED) {
            log.info("Transitioning broker poll system to state running.");
            this.runState = RUNNING;
        } else {
            log.info("Skipping transition of broker poll system to state running. Current state is {}.", this.runState);
        }
    }

    /**
     * Returns cached view of paused partition size. Useful for testing and monitoring by wrapping application / user
     * code.
     *
     * @return number of paused partitions
     */
    public int getPausedPartitionSize() {
        return consumerManager.getPausedPartitionSize();
    }
}
