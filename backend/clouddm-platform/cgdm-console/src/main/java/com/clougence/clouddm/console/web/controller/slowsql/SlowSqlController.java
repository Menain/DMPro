package com.clougence.clouddm.console.web.controller.slowsql;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.DM_SLOW_SQL_READ;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.slowsql.SlowSqlAdviceFO;
import com.clougence.clouddm.console.web.model.fo.slowsql.SlowSqlTopQueryFO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlAdviceVO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlInstanceVO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlTopListVO;
import com.clougence.clouddm.console.web.service.slowsql.SlowSqlService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Slow SQL query controller backed by Tencent Cloud DBbrain.
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/slowSql")
@Slf4j
public class SlowSqlController {

    @Resource
    private SlowSqlService slowSqlService;

    @RequestMapping(value = "/listInstances", method = RequestMethod.POST)
    @RequestAuth(DM_SLOW_SQL_READ)
    public ResWebData<List<SlowSqlInstanceVO>> listInstances() {
        return ResWebDataUtils.buildSuccess(slowSqlService.listInstances());
    }

    @RequestMapping(value = "/listTopSqls", method = RequestMethod.POST)
    @RequestAuth(DM_SLOW_SQL_READ)
    public ResWebData<SlowSqlTopListVO> listTopSqls(@RequestBody SlowSqlTopQueryFO fo) {
        return ResWebDataUtils.buildSuccess(slowSqlService.listTopSqls(fo));
    }

    @RequestMapping(value = "/sqlAdvice", method = RequestMethod.POST)
    @RequestAuth(DM_SLOW_SQL_READ)
    public ResWebData<SlowSqlAdviceVO> sqlAdvice(@RequestBody SlowSqlAdviceFO fo) {
        return ResWebDataUtils.buildSuccess(slowSqlService.sqlAdvice(fo));
    }
}
