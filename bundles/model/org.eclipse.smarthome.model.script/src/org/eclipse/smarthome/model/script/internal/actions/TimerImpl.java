/**
 * Copyright (c) 2014,2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.smarthome.model.script.internal.actions;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;

import org.eclipse.smarthome.model.script.actions.Timer;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is an implementation of the {@link Timer} interface using the Quartz
 * library for scheduling.
 *
 * @author Kai Kreuzer - Initial contribution and API
 * @author Jon Evans - API enhancements, switch to java.time
 *
 */
public class TimerImpl implements Timer {

    private final Logger logger = LoggerFactory.getLogger(TimerImpl.class);

    // the scheduler used for timer events
    public static Scheduler scheduler;

    static {
        try {
            scheduler = StdSchedulerFactory.getDefaultScheduler();
        } catch (SchedulerException e) {
            LoggerFactory.getLogger(TimerImpl.class).error("initializing scheduler throws exception", e);
        }
    }

    private final JobKey jobKey;
    private TriggerKey triggerKey;
    private final JobDataMap dataMap;
    private final ZonedDateTime startTime;

    private boolean cancelled = false;
    private boolean terminated = false;

    public TimerImpl(JobKey jobKey, TriggerKey triggerKey, JobDataMap dataMap, ZonedDateTime startTime) {
        this.jobKey = jobKey;
        this.triggerKey = triggerKey;
        this.dataMap = dataMap;
        this.startTime = startTime;
        dataMap.put("timer", this);
    }

    @Override
    public boolean cancel() {
        try {
            boolean result = scheduler.deleteJob(jobKey);
            if (result) {
                cancelled = true;
            }
        } catch (SchedulerException e) {
            logger.warn("An error occurred while cancelling the job '{}': {}", jobKey.toString(), e.getMessage());
        }
        return cancelled;
    }

    @Override
    public boolean reschedule(ZonedDateTime newTime) {
        try {
            Trigger trigger = newTrigger().startAt(Date.from(newTime.toInstant())).build();
            Date nextTriggerTime = scheduler.rescheduleJob(triggerKey, trigger);
            if (nextTriggerTime == null) {
                logger.debug("Scheduling a new job job '{}' because the original has already run", jobKey.toString());
                JobDetail job = newJob(TimerExecutionJob.class).withIdentity(jobKey).usingJobData(dataMap).build();
                TimerImpl.scheduler.scheduleJob(job, trigger);
            }
            this.triggerKey = trigger.getKey();
            this.cancelled = false;
            this.terminated = false;
            return true;
        } catch (SchedulerException e) {
            logger.warn("An error occurred while rescheduling the job '{}': {}", jobKey.toString(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isRunning() {
        try {
            for (JobExecutionContext context : scheduler.getCurrentlyExecutingJobs()) {
                if (context.getJobDetail().getKey().equals(jobKey)) {
                    return true;
                }
            }
            return false;
        } catch (SchedulerException e) {
            // fallback implementation
            logger.debug("An error occurred getting currently running jobs: {}", e.getMessage());
            return Instant.now().isAfter(startTime.toInstant()) && !terminated;
        }
    }

    @Override
    public boolean hasTerminated() {
        return terminated;
    }

    public void setTerminated(boolean terminated) {
        this.terminated = terminated;
    }

    @Override
    public ZonedDateTime getScheduledFireTime() {
        try {
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
            for (Trigger trigger : triggers) {
                Date nextFireTime = trigger.getNextFireTime();
                if (nextFireTime != null) {
                    return ZonedDateTime.ofInstant(nextFireTime.toInstant(), ZoneId.systemDefault());
                }
            }
        } catch (SchedulerException e) {
            logger.debug("An error occurred getting triggers for job: {}", e.getMessage());
        }
        return null;
    }
}
