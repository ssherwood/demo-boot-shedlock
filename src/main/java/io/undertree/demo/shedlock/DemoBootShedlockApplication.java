package io.undertree.demo.shedlock;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

@SpringBootApplication
public class DemoBootShedlockApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoBootShedlockApplication.class, args);
    }

    @Configuration
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "5m")
    static class ShedlockConfiguration {
        @Bean
        public LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(
                    JdbcTemplateLockProvider.Configuration.builder()
                            .withJdbcTemplate(new JdbcTemplate(dataSource))
                            .usingDbTime() // Works on Postgres, MySQL, MariaDb, MS SQL, Oracle, DB2, HSQL and H2
                            .build()
            );
        }
    }

    @Slf4j
    @Component
    static class GenericTaskScheduler {
        private final JdbcTemplate jdbcTemplate;

        GenericTaskScheduler(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        // issues:  https://github.com/lukas-krecan/ShedLock/issues/207

        // NOTEs
        // - it looks like the shedlock lock test actually happens outside this invocation, so there
        //   isn't a way to set isolation here that will really solve the problem...
        //   may have to look at customizing DefaultLockingTaskExecutor

        @Scheduled(cron = "0 * * * * *") // run at the top of every minute
        @SchedulerLock(name = "TaskScheduler_scheduledTask", lockAtLeastFor = "30s", lockAtMostFor = "50s")
        @Transactional(isolation = Isolation.SERIALIZABLE)
        public void scheduledTask() throws Exception {
            // LockAssert.assertLocked();
            System.out.println("Job Started...");

            jdbcTemplate.execute("SELECT 1");
            Thread.sleep(20000L); // "work" for 20s

            System.out.println("Job Finished...");
        }
    }
}
