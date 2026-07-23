package com.example.demo.cron;

import com.example.demo.harness.HarnessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CronTaskServiceTest {

    private TaskScheduler taskScheduler;
    private HarnessEngine harnessEngine;
    private CronTaskService cronTaskService;

    @BeforeEach
    void setUp() {
        taskScheduler = mock(TaskScheduler.class);
        harnessEngine = mock(HarnessEngine.class);
        cronTaskService = new CronTaskService(taskScheduler, harnessEngine);
    }

    @Test
    void testScheduleJob_WithFixedIntervalSeconds() {
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        doReturn(mockFuture).when(taskScheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(60)));

        Map<String, Object> result = cronTaskService.scheduleJob("job-1", "Analyze metrics", null, 60L);

        assertEquals("SUCCESS", result.get("status"));
        assertEquals("job-1", result.get("jobId"));
        assertEquals("Analyze metrics", result.get("intent"));

        List<Map<String, Object>> jobs = cronTaskService.listJobs();
        assertEquals(1, jobs.size());
        assertEquals("job-1", jobs.get(0).get("jobId"));

        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(60)));
    }

    @Test
    void testScheduleJob_WithCronExpression() {
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        doReturn(mockFuture).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        Map<String, Object> result = cronTaskService.scheduleJob(null, "Health check", "0 */5 * * * *", null);

        assertEquals("SUCCESS", result.get("status"));
        assertNotNull(result.get("jobId"));
        assertTrue(result.get("jobId").toString().startsWith("cron-job-"));
        assertEquals("Health check", result.get("intent"));

        verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void testScheduleJob_MissingIntent_ReturnsError() {
        Map<String, Object> result = cronTaskService.scheduleJob("job-err", "", "0 */5 * * * *", null);
        assertEquals("ERROR", result.get("status"));
        assertTrue(result.get("message").toString().contains("intent"));
    }

    @Test
    void testScheduleJob_MissingTimingParameters_ReturnsError() {
        Map<String, Object> result = cronTaskService.scheduleJob("job-err", "Some intent", null, null);
        assertEquals("ERROR", result.get("status"));
        assertTrue(result.get("message").toString().contains("cronExpression"));
    }

    @Test
    void testCancelJob_CancelsActiveJob() {
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        doReturn(mockFuture).when(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));

        cronTaskService.scheduleJob("job-to-cancel", "Check system", null, 30L);
        assertEquals(1, cronTaskService.listJobs().size());

        Map<String, Object> cancelResult = cronTaskService.cancelJob("job-to-cancel");
        assertEquals("SUCCESS", cancelResult.get("status"));
        assertEquals(0, cronTaskService.listJobs().size());

        verify(mockFuture).cancel(false);
    }

    @Test
    void testJobExecution_InvokesHarnessEngine() {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        ScheduledFuture mockFuture = mock(ScheduledFuture.class);
        doReturn(mockFuture).when(taskScheduler).scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class));

        cronTaskService.scheduleJob("job-exec", "Run intent", null, 10L);

        Runnable taskRunnable = runnableCaptor.getValue();
        assertNotNull(taskRunnable);

        taskRunnable.run();

        verify(harnessEngine).executeIntent("Run intent");

        Map<String, Object> jobDetails = cronTaskService.getJob("job-exec");
        assertEquals("SUCCESS", jobDetails.get("status"));
        assertEquals(1L, jobDetails.get("runCount"));
    }
}
