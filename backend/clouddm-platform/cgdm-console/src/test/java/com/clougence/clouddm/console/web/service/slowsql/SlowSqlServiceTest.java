package com.clougence.clouddm.console.web.service.slowsql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.model.fo.slowsql.SlowSqlAdviceFO;
import com.clougence.clouddm.console.web.model.fo.slowsql.SlowSqlTopQueryFO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlAdviceVO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlTopListVO;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsConfigKv4DmMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsConfigKv4DmDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.sdk.model.exception.ThirdPartyApiException;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.dbbrain.v20210527.DbbrainClient;
import com.tencentcloudapi.dbbrain.v20210527.models.DescribeSlowLogTopSqlsRequest;
import com.tencentcloudapi.dbbrain.v20210527.models.DescribeSlowLogTopSqlsResponse;
import com.tencentcloudapi.dbbrain.v20210527.models.DescribeUserSqlAdviceRequest;
import com.tencentcloudapi.dbbrain.v20210527.models.DescribeUserSqlAdviceResponse;
import com.tencentcloudapi.dbbrain.v20210527.models.SlowLogTopSqlItem;

public class SlowSqlServiceTest {

    private SlowSqlServiceImpl     service;
    private SystemDal              systemDal;
    private DataSourceDal          dsDal;
    private DmDsConfigKv4DmMapper  configKvMapper;
    private DmDsMapper             dsMapper;
    private DbbrainClientProvider  clientProvider;
    private DbbrainClient          client;

    private static final String    SECRET_ID_ENC  = "enc-secret-id";
    private static final String    SECRET_KEY_ENC = "enc-secret-key";
    private static final String    SECRET_ID      = "AKIDxxx";
    private static final String    SECRET_KEY     = "SKxxx";
    private static final Long       DS_ID          = 5L;
    private static final String    CDB_INSTANCE    = "cdb-abc123";
    private static final String    REGION          = "ap-guangzhou";

    @Before
    public void setUp() {
        service = new SlowSqlServiceImpl() {
            @Override
            protected String decryptConfigValue(String encrypted) {
                // test bypass: return plaintext directly
                return encrypted;
            }
        };
        systemDal = mock(SystemDal.class);
        dsDal = mock(DataSourceDal.class);
        configKvMapper = mock(DmDsConfigKv4DmMapper.class);
        dsMapper = mock(DmDsMapper.class);
        clientProvider = mock(DbbrainClientProvider.class);
        client = mock(DbbrainClient.class);

        when(dsDal.configKv4DmMapper()).thenReturn(configKvMapper);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        when(clientProvider.getClient(anyString(), anyString(), anyString())).thenReturn(client);

        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dbbrainClientProvider", clientProvider);

        // default credential stubs (tests that need cloud calls override these)
        stubCredentialsConfigured();
        stubCloudMapping();
    }

    // ----------------------------------------------------------- time window

    @Test
    public void timeRange_exactly7Days_rejected() throws Exception {
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        fo.setStartTime("2026-01-01 00:00:00");
        fo.setEndTime("2026-01-08 00:00:00");
        try {
            service.listTopSqls(fo);
            fail("should reject 7-day span");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("7"));
        }
        verify(client, never()).DescribeSlowLogTopSqls(any());
    }

    @Test
    public void timeRange_justUnder7Days_accepted() throws Exception {
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        fo.setStartTime("2026-01-01 00:00:00");
        fo.setEndTime("2026-01-07 23:59:59");
        DescribeSlowLogTopSqlsResponse resp = new DescribeSlowLogTopSqlsResponse();
        resp.setTotalCount(0L);
        resp.setRows(new SlowLogTopSqlItem[0]);
        when(client.DescribeSlowLogTopSqls(any())).thenReturn(resp);
        SlowSqlTopListVO result = service.listTopSqls(fo);
        assertEquals(Long.valueOf(0), result.getTotalCount());
    }

    @Test
    public void timeRange_endBeforeStart_rejected() throws Exception {
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        fo.setStartTime("2026-01-10 00:00:00");
        fo.setEndTime("2026-01-01 00:00:00");
        try {
            service.listTopSqls(fo);
            fail("should reject end<start");
        } catch (ErrorMessageException e) {
            // expected
        }
        verify(client, never()).DescribeSlowLogTopSqls(any());
    }

    // ----------------------------------------------------------- TopSqls param assembly

    @Test
    public void listTopSqls_assemblesCorrectRequest() throws Exception {
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        fo.setSortBy("ExecTimes");
        fo.setOrderBy("asc");
        fo.setLimit(50);
        fo.setOffset(10);
        fo.setSchema("mydb");

        DescribeSlowLogTopSqlsResponse resp = new DescribeSlowLogTopSqlsResponse();
        resp.setTotalCount(1L);
        SlowLogTopSqlItem item = new SlowLogTopSqlItem();
        item.setSqlTemplate("select * from t where id = ?");
        item.setSchema("mydb");
        item.setExecTimes(100L);
        item.setQueryTime(10.5f);
        item.setQueryTimeAvg(5.0f);
        item.setQueryTimeMax(8.0f);
        resp.setRows(new SlowLogTopSqlItem[] { item });
        when(client.DescribeSlowLogTopSqls(any())).thenReturn(resp);

        SlowSqlTopListVO result = service.listTopSqls(fo);

        ArgumentCaptor<DescribeSlowLogTopSqlsRequest> captor = ArgumentCaptor.forClass(DescribeSlowLogTopSqlsRequest.class);
        verify(client).DescribeSlowLogTopSqls(captor.capture());
        DescribeSlowLogTopSqlsRequest req = captor.getValue();
        assertEquals(CDB_INSTANCE, req.getInstanceId());
        assertEquals("mysql", req.getProduct());
        assertEquals("ExecTimes", req.getSortBy());
        assertEquals("ASC", req.getOrderBy());
        assertEquals(Long.valueOf(50), req.getLimit());
        assertEquals(Long.valueOf(10), req.getOffset());
        assertNotNull(req.getSchemaList());
        assertEquals("mydb", req.getSchemaList()[0].getSchema());

        assertEquals(Long.valueOf(1), result.getTotalCount());
        assertEquals(1, result.getRows().size());
        assertEquals("select * from t where id = ?", result.getRows().get(0).getSqlTemplate());
        assertEquals(Long.valueOf(100), result.getRows().get(0).getExecTimes());
    }

    // ----------------------------------------------------------- SqlAdvice parsing

    @Test
    public void sqlAdvice_parsesFullResponse() throws Exception {
        SlowSqlAdviceFO fo = baseAdviceFO();
        DescribeUserSqlAdviceResponse resp = new DescribeUserSqlAdviceResponse();
        resp.setAdvices("[{\"TableName\":\"t1\",\"TableSchema\":\"mydb\",\"Keys\":[{\"SqlText\":\"CREATE INDEX idx_t1_id ON t1(id)\"}]}]");
        resp.setComments("[\"Consider adding index\"]");
        resp.setCost("{\"Before\":100.0,\"After\":10.0,\"Ratio\":90.0}");
        resp.setSqlPlan("{\"Before\":{},\"After\":{}}");
        when(client.DescribeUserSqlAdvice(any())).thenReturn(resp);

        SlowSqlAdviceVO vo = service.sqlAdvice(fo);
        assertEquals(1, vo.getAdvices().size());
        assertEquals("t1", vo.getAdvices().get(0).getTableName());
        assertEquals("mydb", vo.getAdvices().get(0).getTableSchema());
        assertEquals(1, vo.getAdvices().get(0).getIndexSqls().size());
        assertEquals("CREATE INDEX idx_t1_id ON t1(id)", vo.getAdvices().get(0).getIndexSqls().get(0));
        assertEquals(1, vo.getComments().size());
        assertEquals("Consider adding index", vo.getComments().get(0));
        assertNotNull(vo.getCost());
        assertEquals(Double.valueOf(100.0), vo.getCost().getBefore());
        assertEquals(Double.valueOf(10.0), vo.getCost().getAfter());
        assertEquals(Double.valueOf(90.0), vo.getCost().getRatio());
        assertNotNull(vo.getSqlPlan());
    }

    @Test
    public void sqlAdvice_emptyStringsReturnsEmpty() throws Exception {
        SlowSqlAdviceFO fo = baseAdviceFO();
        DescribeUserSqlAdviceResponse resp = new DescribeUserSqlAdviceResponse();
        resp.setAdvices("");
        resp.setComments("");
        resp.setCost("");
        resp.setSqlPlan("");
        when(client.DescribeUserSqlAdvice(any())).thenReturn(resp);

        SlowSqlAdviceVO vo = service.sqlAdvice(fo);
        assertTrue(vo.getAdvices().isEmpty());
        assertTrue(vo.getComments().isEmpty());
        assertNull(vo.getCost());
        assertNull(vo.getSqlPlan());
    }

    @Test
    public void sqlAdvice_badJsonFallsBackToComments() throws Exception {
        SlowSqlAdviceFO fo = baseAdviceFO();
        DescribeUserSqlAdviceResponse resp = new DescribeUserSqlAdviceResponse();
        resp.setAdvices("not-valid-json");
        resp.setComments("[]");
        resp.setCost("not-valid-json");
        resp.setSqlPlan("");
        when(client.DescribeUserSqlAdvice(any())).thenReturn(resp);

        SlowSqlAdviceVO vo = service.sqlAdvice(fo);
        assertTrue(vo.getAdvices().isEmpty());
        assertEquals(1, vo.getComments().size());
        assertEquals("not-valid-json", vo.getComments().get(0));
        assertNull(vo.getCost());
    }

    // ----------------------------------------------------------- not configured

    @Test
    public void credentialsNotConfigured_throwsWithoutCloudCall() throws Exception {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.tencentCloudSecretId)).thenReturn(null);
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        try {
            service.listTopSqls(fo);
            fail("should throw not-configured");
        } catch (ErrorMessageException e) {
            // expected
        }
        verify(client, never()).DescribeSlowLogTopSqls(any());
    }

    // ----------------------------------------------------------- SDK exception mapping

    @Test
    public void sdkAuthFailure_mapsToCredentialError() throws Exception {
        when(client.DescribeSlowLogTopSqls(any())).thenThrow(new TencentCloudSDKException("bad secret id", null, "AuthFailure.SecretIdNotFound"));
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        try {
            service.listTopSqls(fo);
            fail("should throw ThirdPartyApiException");
        } catch (ThirdPartyApiException e) {
            assertTrue(e.getMessageKey().contains("AUTH_FAILURE"));
        }
    }

    @Test
    public void sdkLimitExceeded_mapsToRateLimitError() throws Exception {
        when(client.DescribeSlowLogTopSqls(any())).thenThrow(new TencentCloudSDKException("rate limited", null, "LimitExceeded"));
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        try {
            service.listTopSqls(fo);
            fail("should throw ThirdPartyApiException");
        } catch (ThirdPartyApiException e) {
            assertTrue(e.getMessageKey().contains("LIMIT_EXCEEDED"));
        }
    }

    @Test
    public void sdkGenericError_mapsToCallFailed() throws Exception {
        when(client.DescribeSlowLogTopSqls(any())).thenThrow(new TencentCloudSDKException("oops", null, "InternalError"));
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        try {
            service.listTopSqls(fo);
            fail("should throw ThirdPartyApiException");
        } catch (ThirdPartyApiException e) {
            assertTrue(e.getMessageKey().contains("CALL_FAILED"));
        }
    }

    // ----------------------------------------------------------- sortBy whitelist

    @Test
    public void sortByOutsideWhitelist_rejected() throws Exception {
        SlowSqlTopQueryFO fo = baseTopSqlsFO();
        fo.setSortBy("InvalidField");
        try {
            service.listTopSqls(fo);
            fail("should reject invalid sortBy");
        } catch (ErrorMessageException e) {
            // expected
        }
        verify(client, never()).DescribeSlowLogTopSqls(any());
    }

    // ----------------------------------------------------------- listInstances

    @Test
    public void listInstances_returnsOnlyConfiguredDs() {
        DmDsConfigKv4DmDO instanceRow = new DmDsConfigKv4DmDO();
        instanceRow.setDataSourceId(DS_ID);
        instanceRow.setConfigName(DataSourceConfig.Fields.tencentCloudInstanceId);
        instanceRow.setConfigValue(CDB_INSTANCE);

        DmDsConfigKv4DmDO regionRow = new DmDsConfigKv4DmDO();
        regionRow.setDataSourceId(DS_ID);
        regionRow.setConfigName(DataSourceConfig.Fields.tencentCloudRegion);
        regionRow.setConfigValue(REGION);

        when(configKvMapper.selectList(any())).thenReturn(Arrays.asList(instanceRow)).thenReturn(Arrays.asList(regionRow));

        DmDsDO ds = new DmDsDO();
        ds.setId(DS_ID);
        ds.setInstanceDesc("prod-mysql");
        ds.setHost("10.0.0.1");
        when(dsMapper.selectBatchIds(any())).thenReturn(Arrays.asList(ds));

        List<?> result = service.listInstances();
        assertEquals(1, result.size());
    }

    // ----------------------------------------------------------- helpers

    private void stubCredentialsConfigured() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.tencentCloudSecretId)).thenReturn(SECRET_ID_ENC);
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.tencentCloudSecretKey)).thenReturn(SECRET_KEY_ENC);
    }

    private void stubCloudMapping() {
        DmDsConfigKv4DmDO instanceRow = new DmDsConfigKv4DmDO();
        instanceRow.setDataSourceId(DS_ID);
        instanceRow.setConfigName(DataSourceConfig.Fields.tencentCloudInstanceId);
        instanceRow.setConfigValue(CDB_INSTANCE);

        DmDsConfigKv4DmDO regionRow = new DmDsConfigKv4DmDO();
        regionRow.setDataSourceId(DS_ID);
        regionRow.setConfigName(DataSourceConfig.Fields.tencentCloudRegion);
        regionRow.setConfigValue(REGION);

        when(configKvMapper.selectList(any())).thenReturn(Arrays.asList(instanceRow, regionRow));
    }

    private SlowSqlTopQueryFO baseTopSqlsFO() {
        SlowSqlTopQueryFO fo = new SlowSqlTopQueryFO();
        fo.setDsId(DS_ID);
        fo.setStartTime("2026-01-01 00:00:00");
        fo.setEndTime("2026-01-02 00:00:00");
        fo.setSortBy("QueryTime");
        fo.setOrderBy("DESC");
        fo.setLimit(20);
        fo.setOffset(0);
        return fo;
    }

    private SlowSqlAdviceFO baseAdviceFO() {
        SlowSqlAdviceFO fo = new SlowSqlAdviceFO();
        fo.setDsId(DS_ID);
        fo.setSqlText("select * from t1 where id = ?");
        fo.setSchema("mydb");
        return fo;
    }
}
