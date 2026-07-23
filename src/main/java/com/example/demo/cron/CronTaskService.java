package com.example.demo.cron;

import com.example.demo.harness.HarnessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class CronTaskService {

    private static final Logger log = LoggerFactory.getLogger(CronTaskService.class);

    private final TaskScheduler taskScheduler;
    private final HarnessEngine harnessEngine;
    private final Map<String, CronJob> activeJobs = new ConcurrentHashMap<>();

    public CronTaskService(TaskScheduler taskScheduler, @Lazy HarnessEngine harnessEngine) {
        this.taskScheduler = taskScheduler;
        this.harnessEngine = harnessEngine;
    }

    public Map<String, Object> scheduleJob(String jobId, String intent, String cronExpression, Long intervalSeconds) {
        if (intent == null || intent.isBlank()) {
            return Map.of("status", "ERROR", "message", "Parameter 'intent' is required and cannot be blank.");
        }

        if ((cronExpression == null || cronExpression.isBlank()) && (intervalSeconds == null || intervalSeconds <= 0)) {
            return Map.of("status", "ERROR", "message", "Either 'cronExpression' or a positive 'intervalSeconds' must be provided.");
        }

        String actualJobId = (jobId != null && !jobId.isBlank())
                ? jobId.trim()
                : "cron-job-" + UUID.randomUUID().toString().substring(0, 8);

        // Cancel existing job with same ID if exists
        cancelJob(actualJobId);

        CronJob job = new CronJob(actualJobId, intent, cronExpression, intervalSeconds);

        Runnable taskRunnable = () -> {
            log.info("Executing scheduled cron intent for job [{}] (run count: {})", actualJobId, job.getRunCount() + 1);
            job.recordExecutionStart();
            try {
                Map<String, Object> result = harnessEngine.executeIntent(intent);
                job.recordExecutionSuccess(result);
                log.info("Completed scheduled cron intent for job [{}] successfully", actualJobId);
            } catch (Exception e) {
                job.recordExecutionFailure(e.getMessage());
                log.error("Failed executing scheduled cron intent for job [{}]: {}", actualJobId, e.getMessage(), e);
            }
        };

        ScheduledFuture<?> future;
        try {
            if (cronExpression != null && !cronExpression.isBlank()) {
                CronTrigger trigger = new CronTrigger(cronExpression.trim());
                future = taskScheduler.schedule(taskRunnable, trigger);
            } else {
                future = taskScheduler.scheduleAtFixedRate(taskRunnable, Duration.ofSeconds(intervalSeconds));
            }
            job.setScheduledFuture(future);
            activeJobs.put(actualJobId, job);

            log.info("Successfully scheduled cron job [{}] for intent '{}' (cron: {}, intervalSeconds: {})",
                    actualJobId, intent, cronExpression, intervalSeconds);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("jobId", actualJobId);
            response.put("intent", intent);
            if (cronExpression != null && !cronExpression.isBlank()) {
                response.put("cronExpression", cronExpression);
            }
            if (intervalSeconds != null && intervalSeconds > 0) {
                response.put("intervalSeconds", intervalSeconds);
            }
            response.put("createdAt", job.getCreatedAt().toString());
            return response;
        } catch (Exception e) {
            log.error("Failed to schedule cron job [{}]: {}", actualJobId, e.getMessage(), e);
            return Map.of("status", "ERROR", "message", "Failed to schedule job: " + e.getMessage());
        }
    }

    public Map<String, Object> cancelJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Map.of("status", "ERROR", "message", "Parameter 'jobId' is required.");
        }
        CronJob job = activeJobs.remove(jobId.trim());
        if (job == null) {
            return Map.of("status", "NOT_FOUND", "message", "No active cron job found with ID: " + jobId);
        }
        job.cancel();
        log.info("Cancelled cron job [{}]", jobId);
        return Map.of("status", "SUCCESS", "jobId", jobId, "message", "Job cancelled successfully.");
    }

    public List<Map<String, Object>> listJobs() {
        List<Map<String, Object>> jobSummaries = new ArrayList<>();
        for (CronJob job : activeJobs.values()) {
            jobSummaries.add(job.toMap());
        }
        return jobSummaries;
    }

    public Map<String, Object> getJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Map.of("status", "ERROR", "message", "Parameter 'jobId' is required.");
        }
        CronJob job = activeJobs.get(jobId.trim());
        if (job == null) {
            return Map.of("status", "NOT_FOUND", "message", "No active cron job found with ID: " + jobId);
        }
        return job.toMap();
    }

    public static class CronJob {
        private final String jobId;
        private final String intent;
        private final String cronExpression;
        private final Long intervalSeconds;
        private final Instant createdAt;
        private Instant lastRunTime;
        private long runCount = 0;
        private String lastStatus = "SCHEDULED";
        private String lastError = null;
        private ScheduledFuture<?> scheduledFuture;

        public CronJob(String jobId, String intent, String cronExpression, Long intervalSeconds) {
            this.jobId = jobId;
            this.intent = intent;
            this.cronExpression = cronExpression;
            this.intervalSeconds = intervalSeconds;
            this.createdAt = Instant.now();
        }

        public synchronized void recordExecutionStart() {
            this.lastRunTime = Instant.now();
            this.runCount++;
            this.lastStatus = "RUNNING";
        }

        public synchronized void recordExecutionSuccess(Map<String, Object> result) {
            this.lastStatus = "SUCCESS";
            this.lastError = null;
        }

        public synchronized void recordExecutionFailure(String errorMessage) {
            this.lastStatus = "FAILED";
            this.lastError = errorMessage;
        }

        public synchronized void cancel() {
            if (scheduledFuture != null && !scheduledFuture.isDone()) {
                scheduledFuture.cancel(false);
            }
            this.lastStatus = "CANCELLED";
        }

        public void setScheduledFuture(ScheduledFuture<?> future) {
            this.scheduledFuture = future;
        }

        public String getJobId() {
            return jobId;
        }

        public String getIntent() {
            return intent;
        }

        public String getCronExpression() {
            return cronExpression;
        }

        public Long getIntervalSeconds() {
            return intervalSeconds;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getLastRunTime() {
            return lastRunTime;
        }

        public long getRunCount() {
            return runCount;
        }

        public String getLastStatus() {
            return lastStatus;
        }

        public String getLastError() {
            return lastError;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jobId", jobId);
            map.put("intent", intent);
            if (cronExpression != null && !cronExpression.isBlank()) {
                map.put("cronExpression", cronExpression);
            }
            if (intervalSeconds != null && intervalSeconds > 0) {
                map.put("intervalSeconds", intervalSeconds);
            }
            map.put("createdAt", createdAt.toString());
            map.put("lastRunTime", lastRunTime != null ? lastRunTime.toString() : null);
            map.put("runCount", runCount);
            map.put("status", lastStatus);
            if (lastError != null) {
                map.put("lastError", lastError);
            }
            return map;
        }
    }
}
