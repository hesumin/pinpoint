/*
 * Copyright 2020 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.batch.job;

import com.navercorp.pinpoint.batch.common.BatchConfiguration;
import com.navercorp.pinpoint.web.dao.ApplicationIndexDao;
import com.navercorp.pinpoint.web.service.AdminService;
import com.navercorp.pinpoint.web.vo.Application;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Taejin Koo
 */
public class CleanupInactiveAgentsTasklet implements Tasklet, StepExecutionListener {

    private final Logger logger = LogManager.getLogger(this.getClass());

    private final int durationDays;

    private final AdminService adminService;

    private final ApplicationIndexDao applicationIndexDao;

    private Queue<String> applicationNameQueue;
    private int progress;
    private int total;
    private int inactiveCount;

    public CleanupInactiveAgentsTasklet(
            BatchConfiguration batchConfiguration,
            AdminService adminService,
            ApplicationIndexDao applicationIndexDao
    ) {
        Objects.requireNonNull(batchConfiguration, "batchConfiguration");
        this.durationDays = batchConfiguration.getCleanupInactiveAgentsDurationDays();
        this.adminService = Objects.requireNonNull(adminService, "adminService");
        this.applicationIndexDao = Objects.requireNonNull(applicationIndexDao, "applicationIndexDao");
    }

    @Override
    public void beforeStep(@Nonnull StepExecution stepExecution) {
         List<String> applicationNames = this.applicationIndexDao.selectAllApplicationNames()
                .stream()
                .map(Application::getName)
                .distinct()
                .collect(Collectors.toList());
        Collections.shuffle(applicationNames);

        this.applicationNameQueue = new ArrayDeque<>(applicationNames);
        this.progress = 0;
        this.total = applicationNames.size();
        this.inactiveCount = 0;
    }

    @Override
    public ExitStatus afterStep(@Nonnull StepExecution stepExecution) {
        logger.info("Cleaned up {} agents", inactiveCount);
        return ExitStatus.COMPLETED;
    }

    @Override
    public RepeatStatus execute(
            @Nonnull StepContribution contribution,
            @Nonnull ChunkContext chunkContext
    ) throws Exception {
        String applicationName = this.applicationNameQueue.poll();
        if (Objects.isNull(applicationName)) {
            return RepeatStatus.FINISHED;
        }

        try {
            logger.info("Cleaning application {} ({}/{})", applicationName, ++progress, total);
            inactiveCount += adminService.removeInactiveAgentInApplication(applicationName, durationDays);
        } catch (Exception e) {
            logger.warn("Failed to clean application {}. message: {}", applicationName, e.getMessage(), e);
        }

        return RepeatStatus.CONTINUABLE;
    }

}
