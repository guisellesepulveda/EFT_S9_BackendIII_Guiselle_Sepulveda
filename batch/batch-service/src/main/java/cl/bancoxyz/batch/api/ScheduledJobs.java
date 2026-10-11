package cl.bancoxyz.batch.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Programación por cron (batch.schedule.*). Deshabilitada por defecto: batch.schedule.enabled=true la activa. */
@Component
@ConditionalOnProperty(prefix = "batch.schedule", name = "enabled", havingValue = "true")
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final JobLaunchService launchService;

    public ScheduledJobs(JobLaunchService launchService) {
        this.launchService = launchService;
    }

    @Scheduled(cron = "${batch.schedule.daily-cron:0 0 2 * * *}")
    void daily() {
        launch(JobLaunchService.DAILY);
    }

    @Scheduled(cron = "${batch.schedule.interest-cron:0 0 3 1 * *}")
    void interest() {
        launch(JobLaunchService.INTEREST);
    }

    @Scheduled(cron = "${batch.schedule.annual-cron:0 0 4 1 1 *}")
    void annual() {
        launch(JobLaunchService.ANNUAL);
    }

    private void launch(String job) {
        try {
            log.info("Lanzamiento programado de {}", job);
            launchService.launch(job, null);
        } catch (Exception e) {
            log.error("Falló el lanzamiento programado de {}: {}", job, e.toString());
        }
    }
}
