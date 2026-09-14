/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.io/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.console.web.component.governance;

import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.system.DmSysMessengerMapper;
import com.clougence.clouddm.platform.dal.model.system.DmSysMessengerDO;
import com.clougence.clouddm.sdk.messenger.MsgSendResult;

/**
 * Tests for GovReturnNotifyService: self-return skip, no-messenger degradation,
 * send exception isolation, and messenger-present direct send.
 * <p>
 * TransactionSynchronization tests are covered by the integration path; this unit
 * test runs without an active transaction (synchronization → direct send branch).
 */
public class GovReturnNotifyServiceTest {

    private GovReturnNotifyService service;
    private SystemDal              systemDal;
    private DmSysMessengerMapper   messengerMapper;
    private ImSenderService        imSenderService;

    private static final String PUID  = "puid-001";
    private static final String ACTOR = "dba-001";
    private static final String OWNER = "owner-001";

    @Before
    public void setUp() {
        service = new GovReturnNotifyService();
        systemDal = mock(SystemDal.class);
        messengerMapper = mock(DmSysMessengerMapper.class);
        when(systemDal.messengerMapper()).thenReturn(messengerMapper);

        imSenderService = mock(ImSenderService.class);
        when(imSenderService.sendMessage(any(), any(), any())).thenReturn(MsgSendResult.success("msg-1", "ok"));

        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "imSenderService", imSenderService);
    }

    @Test
    public void selfReturn_skipsNotification() {
        service.notifyReturn(PUID, OWNER, OWNER, 1L, "Title", "reason", "OWNER");
        verifyNoInteractions(imSenderService);
        verify(messengerMapper, never()).queryMessengerByOwner(any());
    }

    @Test
    public void noMessenger_degradesGracefully() {
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(Collections.emptyList());
        // Should not throw
        service.notifyReturn(PUID, ACTOR, OWNER, 2L, "Title", "reason", "DBA");
        verify(imSenderService, never()).sendMessage(any(), any(), any());
    }

    @Test
    public void sendException_doesNotPropagate() {
        DmSysMessengerDO messenger = buildMessenger(true);
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(List.of(messenger));
        when(imSenderService.sendMessage(any(), any(), any()))
            .thenThrow(new RuntimeException("IM provider down"));

        // Must not throw — degradation
        service.notifyReturn(PUID, ACTOR, OWNER, 3L, "Title", "reason", "DBA");
        // send was attempted (caught internally)
        verify(imSenderService).sendMessage(eq(PUID), any(), any());
    }

    @Test
    public void messengerPresent_sendsMessage() {
        DmSysMessengerDO messenger = buildMessenger(true);
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(List.of(messenger));

        service.notifyReturn(PUID, ACTOR, OWNER, 4L, "Ticket 4", "Fix syntax", "APPROVER");
        verify(imSenderService).sendMessage(eq(PUID), any(), argThat(msg ->
            msg != null && msg.getBody() != null
                && msg.getBody().contains("/ticket/4")
                && msg.getBody().contains("Fix syntax")));
    }

    @Test
    public void disabledMessenger_skipped() {
        DmSysMessengerDO enabled = buildMessenger(true);
        DmSysMessengerDO disabled = buildMessenger(false);
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(List.of(disabled, enabled));

        service.notifyReturn(PUID, ACTOR, OWNER, 5L, "Title", "reason", "DBA");
        // Only the enabled messenger should be sent to
        verify(imSenderService, times(1)).sendMessage(eq(PUID), any(), any());
    }

    private DmSysMessengerDO buildMessenger(boolean enabled) {
        DmSysMessengerDO m = new DmSysMessengerDO();
        m.setId(1L);
        m.setOwnerUid(PUID);
        m.setWebhook("https://example.com/hook");
        m.setSecret("secret");
        m.setEnable(enabled);
        return m;
    }
}
