package com.clougence.clouddm.console.web.service.slowsql;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.slowsql.SlowSqlAdviceFO;
import com.clougence.clouddm.console.web.model.fo.slowsql.SlowSqlTopQueryFO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlAdviceVO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlInstanceVO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlTopListVO;

/**
 * Slow SQL query service backed by Tencent Cloud DBbrain.
 */
public interface SlowSqlService {

    List<SlowSqlInstanceVO> listInstances();

    SlowSqlTopListVO listTopSqls(SlowSqlTopQueryFO fo);

    SlowSqlAdviceVO sqlAdvice(SlowSqlAdviceFO fo);
}
