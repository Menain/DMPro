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
package com.clougence.clouddm.console.web.component.governance;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;

public class PromotionStateMachineTest {

    private PromotionStateMachine  stateMachine;
    private DbChangeGovernDal     dbChangeGovernDal;
    private DmDbChangePromotionMapper promotionMapper;

    @Before
    public void setUp() {
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        promotionMapper = mock(DmDbChangePromotionMapper.class);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);

        stateMachine = new PromotionStateMachine();
        ReflectionTestUtils.setField(stateMachine, "dbChangeGovernDal", dbChangeGovernDal);
    }

    @Test
    public void transit_legalTransition_succeeds() {
        when(promotionMapper.transitStatus(eq(1L), eq("APPROVING"), anyList()))
            .thenReturn(1);
        boolean result = stateMachine.transit(1L, Set.of(PromotionStatus.CREATED), PromotionStatus.APPROVING);
        assertTrue(result);
    }

    @Test
    public void transit_illegalTransition_fails() {
        when(promotionMapper.transitStatus(eq(1L), eq("SUCCEEDED"), anyList()))
            .thenReturn(0);
        boolean result = stateMachine.transit(1L, Set.of(PromotionStatus.CREATED), PromotionStatus.SUCCEEDED);
        assertFalse(result);
    }

    @Test
    public void transit_failedRevivalEdge_succeeds() {
        when(promotionMapper.transitStatus(eq(1L), eq("EXECUTING"), anyList()))
            .thenReturn(1);
        boolean result = stateMachine.transit(1L, Set.of(PromotionStatus.FAILED), PromotionStatus.EXECUTING);
        assertTrue(result);
    }

    @Test
    public void legalFromFor_executing_includesFailed() {
        Set<PromotionStatus> legal = stateMachine.legalFromFor(PromotionStatus.EXECUTING);
        assertTrue(legal.contains(PromotionStatus.CONFIRMED));
        assertTrue(legal.contains(PromotionStatus.FAILED));
    }

    @Test
    public void legalFromFor_succeeded_hasExecuting() {
        Set<PromotionStatus> legal = stateMachine.legalFromFor(PromotionStatus.SUCCEEDED);
        assertTrue(legal.contains(PromotionStatus.EXECUTING));
    }

    @Test
    public void terminalStates_haveNoOutgoingTransitions() {
        // SUCCEEDED, REJECTED, CANCELLED are terminal — they must not appear in any other state's legal-from set
        for (PromotionStatus target : PromotionStatus.values()) {
            Set<PromotionStatus> fromSet = stateMachine.legalFromFor(target);
            assertFalse("SUCCEEDED should not be a legal source for " + target, fromSet.contains(PromotionStatus.SUCCEEDED));
            assertFalse("REJECTED should not be a legal source for " + target, fromSet.contains(PromotionStatus.REJECTED));
            assertFalse("CANCELLED should not be a legal source for " + target, fromSet.contains(PromotionStatus.CANCELLED));
        }
    }

    @Test
    public void mapTicketStatus_closedAfterFailed_returnsNull() {
        PromotionStatus result = stateMachine.mapTicketStatus("CLOSED", PromotionStatus.FAILED);
        assertNull(result);
    }

    @Test
    public void mapTicketStatus_canceledAfterFailed_returnsNull() {
        PromotionStatus result = stateMachine.mapTicketStatus("CANCELED", PromotionStatus.FAILED);
        assertNull(result);
    }

    @Test
    public void mapTicketStatus_closedAfterExecuting_returnsCancelled() {
        PromotionStatus result = stateMachine.mapTicketStatus("CLOSED", PromotionStatus.EXECUTING);
        assertEquals(PromotionStatus.CANCELLED, result);
    }

    @Test
    public void mapTicketStatus_execFail_returnsFailed() {
        PromotionStatus result = stateMachine.mapTicketStatus("EXEC_FAIL", PromotionStatus.EXECUTING);
        assertEquals(PromotionStatus.FAILED, result);
    }

    @Test
    public void mapTicketStatus_running_returnsExecuting() {
        PromotionStatus result = stateMachine.mapTicketStatus("RUNNING", PromotionStatus.CONFIRMED);
        assertEquals(PromotionStatus.EXECUTING, result);
    }

    @Test
    public void mapTicketStatus_finished_returnsSucceeded() {
        PromotionStatus result = stateMachine.mapTicketStatus("FINISHED", PromotionStatus.EXECUTING);
        assertEquals(PromotionStatus.SUCCEEDED, result);
    }

    @Test
    public void mapTicketStatus_waitApproval_returnsApproving() {
        PromotionStatus result = stateMachine.mapTicketStatus("WAIT_APPROVAL", PromotionStatus.CREATED);
        assertEquals(PromotionStatus.APPROVING, result);
    }

    @Test
    public void mapTicketStatus_unknownStatus_returnsNull() {
        PromotionStatus result = stateMachine.mapTicketStatus("UNKNOWN", PromotionStatus.CREATED);
        assertNull(result);
    }
}
