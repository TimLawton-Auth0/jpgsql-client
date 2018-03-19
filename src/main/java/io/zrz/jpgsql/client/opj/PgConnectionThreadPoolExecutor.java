package io.zrz.jpgsql.client.opj;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.zrz.jpgsql.client.PostgresConnectionProperties;
import io.zrz.jpgsql.client.PostgresqlCapacityExceededException;
import lombok.extern.slf4j.Slf4j;

/**
 * responsible for keeping the pool of connections open.
 */

@Slf4j
public class PgConnectionThreadPoolExecutor extends ThreadPoolExecutor implements ThreadFactory, RejectedExecutionHandler, UncaughtExceptionHandler {

  private final PgThreadPooledClient pool;
  private final LinkedTransferQueue<Runnable> pendingQueue = new LinkedTransferQueue<>();
  private final AtomicInteger pendingCount = new AtomicInteger(0);
  private PostgresConnectionProperties config;

  public PgConnectionThreadPoolExecutor(final PgThreadPooledClient pool, final PostgresConnectionProperties config) {

    super(
        config.getMaxPoolSize(),
        config.getMaxPoolSize(),
        config.getIdleTimeout().toMillis(),
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(config.getMaxPoolSize() + config.getQueueDepth(), true));

    this.setThreadFactory(new ThreadFactoryBuilder()
        .setThreadFactory(this)
        .setDaemon(true)
        .setUncaughtExceptionHandler(this)
        .setNameFormat("psql-%d-" + Integer.toHexString(this.hashCode()))
        .build());

    this.pool = pool;

    super.setRejectedExecutionHandler(this);

    // if (config.getMinIdle() > 0) {
    // super.setCorePoolSize(config.getMinIdle() + 1);
    // }

    // super.setCorePoolSize(config.getMaxPoolSize());
    // super.setMaximumPoolSize(config.getMaxPoolSize());

    this.prestartAllCoreThreads();

    this.config = config;

    log.debug("prestarting : core={} max={} size={}", this.getCorePoolSize(), this.getMaximumPoolSize(), this.getPoolSize());

  }

  /**
   * if the execution queue is full, we are backlogging work. add to a queue until the size is full, and ensure we
   * remove from the queue when workers become available.
   */

  @Override
  public void rejectedExecution(final Runnable r, final ThreadPoolExecutor e) {

    log.error("execution of {} rejected terminated={}, shutdown={}", r, this.isTerminating(), this.isShutdown());
    throw new PostgresqlCapacityExceededException();

  }

  @Override
  protected void beforeExecute(final Thread t, final Runnable r) {
    log.debug("about to execute {} on {}", r, t);
    super.beforeExecute(t, r);
  }

  @Override
  protected void afterExecute(final Runnable r, final Throwable t) {
    log.debug("completed execution of {} on {}", r, t);
    super.afterExecute(r, t);
  }

  @Override
  public Thread newThread(final Runnable r) {
    log.debug("starting new thread");
    return new PgConnectionThread(this.pool, r);
  }

  @Override
  public void uncaughtException(Thread t, Throwable e) {
    log.error("error in group {}, failed with uncaught exception", t.getThreadGroup().getName(), e);
  }

}
