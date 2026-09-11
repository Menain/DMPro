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
package com.clougence.rdp.controller;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.prodrelease.GovLedgerDetailFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.GovLedgerListByDbFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.GovLedgerTicketVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.governance.GovLedgerService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Ledger controller — read-only, visible to all logged-in users (D6).
 * No tenant filtering at service layer — every logged-in user sees all executed PRE_DDL tickets.
 * Coexists with P2's groupList (which does tenant-filter) — independent endpoints.
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX)
@Slf4j
public class GovLedgerController {

    @Resource
    private GovLedgerService govLedgerService;

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefAnyOnes)
    @RequestMapping(value = "/govledger/dbs", method = RequestMethod.POST)
    public ResWebData<?> dbs(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<DbPairVO> data = govLedgerService.dbs(puid);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefAnyOnes)
    @RequestMapping(value = "/govledger/listByDb", method = RequestMethod.POST)
    public ResWebData<?> listByDb(@Valid @RequestBody GovLedgerListByDbFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<GovLedgerTicketVO> data = govLedgerService.listByDb(puid, fo);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefAnyOnes)
    @RequestMapping(value = "/govledger/detail", method = RequestMethod.POST)
    public ResWebData<?> detail(@Valid @RequestBody GovLedgerDetailFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<GovTicketV2GroupVO> data = govLedgerService.detail(puid, fo);
        return ResWebDataUtils.buildSuccess(data);
    }
}
