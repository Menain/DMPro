/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.console.web.component.execute;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.util.DmTeamUtils;

import com.clougence.clouddm.console.web.component.execute.impl.AutoExecServiceImpl;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ObjectCacheDao;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;

public class ReplaceTaskTest {

    private AutoExecServiceImpl   service;
    private ExecutionDal          execDal;
    private DmExecAutoJobMapper   autoJobMapper;
    private DmExecAutoTaskMapper  autoTaskMapper;

    private static final String   BIZ_ID    = "ticket-biz-001";
    private static final long     JOB_ID    = 50L;
    private static final long     TASK_ID   = 100L;

    @Before
    public void setUp() {
        service = new AutoExecServiceImpl();
        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);

        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(execDal.autoTaskMapper()).thenReturn(autoTaskMapper);

        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "systemDal", mock(SystemDal.class));
        ReflectionTestUtils.setField(service, "dsDal", mock(DataSourceDal.class));
        ReflectionTestUtils.setField(service, "cacheDao", mock(ObjectCacheDao.class));

        // DmTeamUtils uses a static executionDal — initialize it for tests
        ReflectionTestUtils.setField(DmTeamUtils.class, "executionDal", execDal);
        when(autoTaskMapper.queryByBizId(any())).thenReturn(null);
    }

    @Test
    public void replaceTask_failedTask_canceledAndNewTaskCreated() {
        setupJob();
        DmExecAutoTaskDO failedTask = buildTask(TASK_ID, AutoExecTaskStatus.FAILED, 3);
        when(autoTaskMapper.selectById(TASK_ID)).thenReturn(failedTask);

        service.replaceTask(BIZ_ID, TASK_ID, "SELECT 1");

        // Old task → CANCELED
        verify(autoTaskMapper).updateStatusByTaskId(TASK_ID, AutoExecTaskStatus.CANCELED);

        // New task inserted with WAIT_EXEC, reuses exec_order=3
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DmExecAutoTaskDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(autoTaskMapper).batchInsert(captor.capture());
        List<DmExecAutoTaskDO> inserted = captor.getValue();
        assertEquals(1, inserted.size());
        DmExecAutoTaskDO newTask = inserted.get(0);
        assertEquals("SELECT 1", newTask.getExecSql());
        assertEquals(AutoExecTaskStatus.WAIT_EXEC, newTask.getStatus());
        assertEquals(3, newTask.getExecOrder().intValue());
        assertEquals(JOB_ID, newTask.getAutoExecJobId().longValue());
        assertNotNull(newTask.getBizId());
        assertNotNull(newTask.getQueryId());
    }

    @Test
    public void replaceTask_rollbackTask_accepted() {
        setupJob();
        DmExecAutoTaskDO rollbackTask = buildTask(TASK_ID, AutoExecTaskStatus.ROLLBACK, 2);
        when(autoTaskMapper.selectById(TASK_ID)).thenReturn(rollbackTask);

        service.replaceTask(BIZ_ID, TASK_ID, "UPDATE t SET v=1");

        verify(autoTaskMapper).updateStatusByTaskId(TASK_ID, AutoExecTaskStatus.CANCELED);
        verify(autoTaskMapper).batchInsert(anyList());
    }

    @Test
    public void replaceTask_canceledTask_rejected() {
        setupJob();
        DmExecAutoTaskDO canceledTask = buildTask(TASK_ID, AutoExecTaskStatus.CANCELED, 1);
        when(autoTaskMapper.selectById(TASK_ID)).thenReturn(canceledTask);

        try {
            service.replaceTask(BIZ_ID, TASK_ID, "SELECT 1");
            fail("Should reject CANCELED task");
        } catch (Exception e) {
            // Expected
        }
        verify(autoTaskMapper, never()).updateStatusByTaskId(anyLong(), any());
    }

    @Test
    public void replaceTask_waitExecTask_rejected() {
        setupJob();
        DmExecAutoTaskDO waitTask = buildTask(TASK_ID, AutoExecTaskStatus.WAIT_EXEC, 1);
        when(autoTaskMapper.selectById(TASK_ID)).thenReturn(waitTask);

        try {
            service.replaceTask(BIZ_ID, TASK_ID, "SELECT 1");
            fail("Should reject WAIT_EXEC task");
        } catch (Exception e) {
            // Expected
        }
        verify(autoTaskMapper, never()).updateStatusByTaskId(anyLong(), any());
    }

    @Test
    public void replaceTask_finishTask_rejected() {
        setupJob();
        DmExecAutoTaskDO finishTask = buildTask(TASK_ID, AutoExecTaskStatus.FINISH, 1);
        when(autoTaskMapper.selectById(TASK_ID)).thenReturn(finishTask);

        try {
            service.replaceTask(BIZ_ID, TASK_ID, "SELECT 1");
            fail("Should reject FINISH task");
        } catch (Exception e) {
            // Expected
        }
        verify(autoTaskMapper, never()).updateStatusByTaskId(anyLong(), any());
    }

    @Test
    public void replaceTask_taskNotInJob_rejected() {
        setupJob();
        DmExecAutoTaskDO wrongJobTask = buildTask(TASK_ID, AutoExecTaskStatus.FAILED, 1);
        wrongJobTask.setAutoExecJobId(999L);
        when(autoTaskMapper.selectById(TASK_ID)).thenReturn(wrongJobTask);

        try {
            service.replaceTask(BIZ_ID, TASK_ID, "SELECT 1");
            fail("Should reject task from different job");
        } catch (Exception e) {
            // Expected
        }
        verify(autoTaskMapper, never()).updateStatusByTaskId(anyLong(), any());
    }

    private void setupJob() {
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        job.setDependOnBizId(BIZ_ID);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(job);
    }

    private DmExecAutoTaskDO buildTask(long id, AutoExecTaskStatus status, int execOrder) {
        DmExecAutoTaskDO task = new DmExecAutoTaskDO();
        task.setId(id);
        task.setAutoExecJobId(JOB_ID);
        task.setStatus(status);
        task.setExecOrder(execOrder);
        task.setBizId("auto-Task-old" + id);
        task.setQueryId("old-query-id");
        task.setExecSql("OLD SQL");
        return task;
    }
}
