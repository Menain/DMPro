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

import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;

public class ProdReleaseStateMachineTest {

    private ProdReleaseStateMachine  stateMachine;
    private ProdReleaseDal            prodReleaseDal;
    private DmProdReleaseMapper       releaseMapper;

    @Before
    public void setUp() {
        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);

        stateMachine = new ProdReleaseStateMachine();
        ReflectionTestUtils.setField(stateMachine, "prodReleaseDal", prodReleaseDal);
    }

    @Test
    public void transit_legalTransition_succeeds() {
        when(releaseMapper.transitStatus(eq(1L), eq("APPROVED"), anyList()))
            .thenReturn(1);
        boolean result = stateMachine.transit(1L,
            Set.of(ProdReleaseStatus.APPROVING), ProdReleaseStatus.APPROVED);
        assertTrue(result);
    }

    @Test
    public void transit_illegalTransition_fails() {
        when(releaseMapper.transitStatus(eq(1L), eq("DONE"), anyList()))
            .thenReturn(0);
        boolean result = stateMachine.transit(1L,
            Set.of(ProdReleaseStatus.APPROVING), ProdReleaseStatus.DONE);
        assertFalse(result);
    }

    @Test
    public void transit_partialFailedRevivalEdge_succeeds() {
        when(releaseMapper.transitStatus(eq(1L), eq("EXECUTING"), anyList()))
            .thenReturn(1);
        boolean result = stateMachine.transit(1L,
            Set.of(ProdReleaseStatus.PARTIAL_FAILED), ProdReleaseStatus.EXECUTING);
        assertTrue(result);
    }

    @Test
    public void legalFromFor_executing_includesPartialFailed() {
        Set<ProdReleaseStatus> legal = stateMachine.legalFromFor(ProdReleaseStatus.EXECUTING);
        assertTrue(legal.contains(ProdReleaseStatus.APPROVED));
        assertTrue(legal.contains(ProdReleaseStatus.PARTIAL_FAILED));
    }

    @Test
    public void legalFromFor_done_hasExecuting() {
        Set<ProdReleaseStatus> legal = stateMachine.legalFromFor(ProdReleaseStatus.DONE);
        assertTrue(legal.contains(ProdReleaseStatus.EXECUTING));
    }

    @Test
    public void legalFromFor_rejected_hasApprovingAndApproved() {
        Set<ProdReleaseStatus> legal = stateMachine.legalFromFor(ProdReleaseStatus.REJECTED);
        assertTrue(legal.contains(ProdReleaseStatus.APPROVING));
        assertTrue(legal.contains(ProdReleaseStatus.APPROVED));
    }

    @Test
    public void legalFromFor_cancelled_hasApprovingAndApproved() {
        Set<ProdReleaseStatus> legal = stateMachine.legalFromFor(ProdReleaseStatus.CANCELLED);
        assertTrue(legal.contains(ProdReleaseStatus.APPROVING));
        assertTrue(legal.contains(ProdReleaseStatus.APPROVED));
    }

    @Test
    public void terminalStates_haveNoOutgoingTransitions() {
        for (ProdReleaseStatus target : ProdReleaseStatus.values()) {
            Set<ProdReleaseStatus> fromSet = stateMachine.legalFromFor(target);
            assertFalse("DONE should not be a legal source for " + target, fromSet.contains(ProdReleaseStatus.DONE));
            assertFalse("REJECTED should not be a legal source for " + target, fromSet.contains(ProdReleaseStatus.REJECTED));
            assertFalse("CANCELLED should not be a legal source for " + target, fromSet.contains(ProdReleaseStatus.CANCELLED));
        }
    }

    @Test
    public void legalFromFor_rejected_doesNotIncludeExecuting() {
        Set<ProdReleaseStatus> legal = stateMachine.legalFromFor(ProdReleaseStatus.REJECTED);
        assertFalse("EXECUTING should not be a legal source for REJECTED", legal.contains(ProdReleaseStatus.EXECUTING));
    }

    @Test
    public void legalFromFor_cancelled_doesNotIncludeExecuting() {
        Set<ProdReleaseStatus> legal = stateMachine.legalFromFor(ProdReleaseStatus.CANCELLED);
        assertFalse("EXECUTING should not be a legal source for CANCELLED", legal.contains(ProdReleaseStatus.EXECUTING));
    }

    @Test
    public void legalFromFor_approved_hasOnlyApproving() {
        Set<ProdReleaseStatus> legal = stateMachine.legalFromFor(ProdReleaseStatus.APPROVED);
        assertEquals(1, legal.size());
        assertTrue(legal.contains(ProdReleaseStatus.APPROVING));
    }
}
