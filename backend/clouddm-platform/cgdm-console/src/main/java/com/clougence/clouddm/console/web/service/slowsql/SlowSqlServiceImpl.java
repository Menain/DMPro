package com.clougence.clouddm.console.web.service.slowsql;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.clougence.clouddm.api.common.crypt.CryptService;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.model.fo.slowsql.SlowSqlAdviceFO;
import com.clougence.clouddm.console.web.model.fo.slowsql.SlowSqlTopQueryFO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlAdviceVO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlInstanceVO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlTopListVO;
import com.clougence.clouddm.console.web.model.vo.slowsql.SlowSqlTopRowVO;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsConfigKv4DmDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.sdk.model.exception.ThirdPartyApiException;
import com.clougence.clouddm.sdk.model.exception.ThirdPartyApiErrorType;
import com.clougence.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.dbbrain.v20210527.DbbrainClient;
import com.tencentcloudapi.dbbrain.v20210527.models.DescribeSlowLogTopSqlsRequest;
import com.tencentcloudapi.dbbrain.v20210527.models.DescribeSlowLogTopSqlsResponse;
import com.tencentcloudapi.dbbrain.v20210527.models.DescribeUserSqlAdviceRequest;
import com.tencentcloudapi.dbbrain.v20210527.models.DescribeUserSqlAdviceResponse;
import com.tencentcloudapi.dbbrain.v20210527.models.SchemaItem;
import com.tencentcloudapi.dbbrain.v20210527.models.SlowLogTopSqlItem;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Slow SQL query service backed by Tencent Cloud DBbrain.
 */
@Slf4j
@Service
public class SlowSqlServiceImpl implements SlowSqlService {

    private static final String               PRODUCT_MYSQL              = "mysql";
    private static final DateTimeFormatter    TIME_FORMATTER             = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int                  MAX_SQL_TEXT_BYTES         = 8 * 1024;
    private static final Set<String>          VALID_SORT_BY              = Set.of("QueryTime", "ExecTimes", "RowsSent", "LockTime", "RowsExamined");
    private static final Set<String>          VALID_ORDER_BY             = Set.of("ASC", "DESC");
    private static final List<String>         CLOUD_MAPPING_CONFIG_NAMES = List.of(DataSourceConfig.Fields.tencentCloudInstanceId, DataSourceConfig.Fields.tencentCloudRegion);

    private static final ObjectMapper         OBJECT_MAPPER              = new ObjectMapper();

    @Resource
    private SystemDal                         systemDal;
    @Resource
    private DataSourceDal                     dsDal;
    @Resource
    private DbbrainClientProvider             dbbrainClientProvider;

    @Override
    public List<SlowSqlInstanceVO> listInstances() {
        List<DmDsConfigKv4DmDO> instanceIdRows = dsDal.configKv4DmMapper().selectList(
                new QueryWrapper<DmDsConfigKv4DmDO>().eq("config_name", DataSourceConfig.Fields.tencentCloudInstanceId));

        List<DmDsConfigKv4DmDO> regionRows = dsDal.configKv4DmMapper().selectList(
                new QueryWrapper<DmDsConfigKv4DmDO>().eq("config_name", DataSourceConfig.Fields.tencentCloudRegion));

        Map<Long, String> dsIdToInstanceId = new HashMap<>();
        for (DmDsConfigKv4DmDO row : instanceIdRows) {
            if (StringUtils.isNotBlank(row.getConfigValue())) {
                dsIdToInstanceId.put(row.getDataSourceId(), row.getConfigValue());
            }
        }
        if (dsIdToInstanceId.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, String> dsIdToRegion = new HashMap<>();
        for (DmDsConfigKv4DmDO row : regionRows) {
            if (StringUtils.isNotBlank(row.getConfigValue())) {
                dsIdToRegion.put(row.getDataSourceId(), row.getConfigValue());
            }
        }

        List<Long> dsIds = new ArrayList<>(dsIdToInstanceId.keySet());
        List<DmDsDO> dsList = dsDal.dsMapper().selectBatchIds(dsIds);
        Map<Long, DmDsDO> dsMap = dsList.stream().collect(Collectors.toMap(DmDsDO::getId, d -> d, (a, b) -> a));

        List<SlowSqlInstanceVO> result = new ArrayList<>();
        for (Map.Entry<Long, String> entry : dsIdToInstanceId.entrySet()) {
            Long dsId = entry.getKey();
            DmDsDO ds = dsMap.get(dsId);
            if (ds == null) {
                continue;
            }
            SlowSqlInstanceVO vo = new SlowSqlInstanceVO();
            vo.setDsId(dsId);
            vo.setDsName(StringUtils.isNotBlank(ds.getInstanceDesc()) ? ds.getInstanceDesc() : ds.getHost());
            vo.setRegion(dsIdToRegion.get(dsId));
            vo.setCloudInstanceId(entry.getValue());
            result.add(vo);
        }
        result.sort((a, b) -> {
            String nameA = a.getDsName() == null ? "" : a.getDsName();
            String nameB = b.getDsName() == null ? "" : b.getDsName();
            return nameA.compareToIgnoreCase(nameB);
        });
        return result;
    }

    @Override
    public SlowSqlTopListVO listTopSqls(SlowSqlTopQueryFO fo) {
        String sortBy = StringUtils.isBlank(fo.getSortBy()) ? "QueryTime" : fo.getSortBy();
        String orderBy = StringUtils.isBlank(fo.getOrderBy()) ? "DESC" : fo.getOrderBy().toUpperCase();
        int limit = fo.getLimit() == null ? 20 : fo.getLimit();
        int offset = fo.getOffset() == null ? 0 : fo.getOffset();

        if (!VALID_SORT_BY.contains(sortBy)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_SORT_BY_INVALID.name(), String.join(", ", VALID_SORT_BY)));
        }
        if (!VALID_ORDER_BY.contains(orderBy)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_ORDER_BY_INVALID.name()));
        }
        if (limit < 1 || limit > 100) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_LIMIT_INVALID.name()));
        }
        validateTimeRange(fo.getStartTime(), fo.getEndTime());

        CloudMapping mapping = resolveCloudMapping(fo.getDsId());
        String[] credentials = resolveCredentials();
        DbbrainClient client = dbbrainClientProvider.getClient(mapping.region, credentials[0], credentials[1]);

        DescribeSlowLogTopSqlsRequest req = new DescribeSlowLogTopSqlsRequest();
        req.setInstanceId(mapping.cloudInstanceId);
        req.setStartTime(fo.getStartTime());
        req.setEndTime(fo.getEndTime());
        req.setSortBy(sortBy);
        req.setOrderBy(orderBy);
        req.setLimit((long) limit);
        req.setOffset((long) offset);
        req.setProduct(PRODUCT_MYSQL);
        if (StringUtils.isNotBlank(fo.getSchema())) {
            SchemaItem schemaItem = new SchemaItem();
            schemaItem.setSchema(fo.getSchema());
            req.setSchemaList(new SchemaItem[] { schemaItem });
        }

        DescribeSlowLogTopSqlsResponse resp;
        try {
            resp = client.DescribeSlowLogTopSqls(req);
        } catch (TencentCloudSDKException e) {
            throw mapSdkException(e);
        }

        SlowSqlTopListVO vo = new SlowSqlTopListVO();
        vo.setTotalCount(resp.getTotalCount());
        List<SlowSqlTopRowVO> rows = new ArrayList<>();
        if (resp.getRows() != null) {
            for (SlowLogTopSqlItem item : resp.getRows()) {
                rows.add(mapTopRow(item));
            }
        }
        vo.setRows(rows);
        return vo;
    }

    @Override
    public SlowSqlAdviceVO sqlAdvice(SlowSqlAdviceFO fo) {
        if (StringUtils.isBlank(fo.getSqlText())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_SQL_TEXT_TOO_LONG.name()));
        }
        if (fo.getSqlText().getBytes().length > MAX_SQL_TEXT_BYTES) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_SQL_TEXT_TOO_LONG.name()));
        }

        CloudMapping mapping = resolveCloudMapping(fo.getDsId());
        String[] credentials = resolveCredentials();
        DbbrainClient client = dbbrainClientProvider.getClient(mapping.region, credentials[0], credentials[1]);

        DescribeUserSqlAdviceRequest req = new DescribeUserSqlAdviceRequest();
        req.setInstanceId(mapping.cloudInstanceId);
        req.setSqlText(fo.getSqlText());
        req.setProduct(PRODUCT_MYSQL);
        if (StringUtils.isNotBlank(fo.getSchema())) {
            req.setSchema(fo.getSchema());
        }

        DescribeUserSqlAdviceResponse resp;
        try {
            resp = client.DescribeUserSqlAdvice(req);
        } catch (TencentCloudSDKException e) {
            throw mapSdkException(e);
        }

        return parseAdviceResponse(resp);
    }

    // ------------------------------------------------------------------ helpers

    private void validateTimeRange(String startTime, String endTime) {
        if (StringUtils.isBlank(startTime) || StringUtils.isBlank(endTime)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_TIME_RANGE_EXCEED_7D.name()));
        }
        LocalDateTime start = LocalDateTime.parse(startTime, TIME_FORMATTER);
        LocalDateTime end = LocalDateTime.parse(endTime, TIME_FORMATTER);
        if (!end.isAfter(start)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_TIME_RANGE_EXCEED_7D.name()));
        }
        if (Duration.between(start, end).toDays() >= 7) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_TIME_RANGE_EXCEED_7D.name()));
        }
    }

    private CloudMapping resolveCloudMapping(Long dsId) {
        if (dsId == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_DS_NO_CLOUD_MAPPING.name()));
        }
        List<DmDsConfigKv4DmDO> configs = dsDal.configKv4DmMapper().selectList(
                new QueryWrapper<DmDsConfigKv4DmDO>().eq("data_source_id", dsId).in("config_name", CLOUD_MAPPING_CONFIG_NAMES));
        String instanceId = null;
        String region = null;
        for (DmDsConfigKv4DmDO config : configs) {
            if (DataSourceConfig.Fields.tencentCloudInstanceId.equals(config.getConfigName())) {
                instanceId = config.getConfigValue();
            } else if (DataSourceConfig.Fields.tencentCloudRegion.equals(config.getConfigName())) {
                region = config.getConfigValue();
            }
        }
        if (StringUtils.isBlank(instanceId) || StringUtils.isBlank(region)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_DS_NO_CLOUD_MAPPING.name()));
        }
        return new CloudMapping(instanceId, region);
    }

    private String[] resolveCredentials() {
        String encryptedId = systemDal.fetchSystemConf(RootUserConfig.Fields.tencentCloudSecretId);
        String encryptedKey = systemDal.fetchSystemConf(RootUserConfig.Fields.tencentCloudSecretKey);
        if (StringUtils.isBlank(encryptedId) || StringUtils.isBlank(encryptedKey)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_CLOUD_NOT_CONFIGURED.name()));
        }
        String secretId = decryptConfigValue(encryptedId);
        String secretKey = decryptConfigValue(encryptedKey);
        if (StringUtils.isBlank(secretId) || StringUtils.isBlank(secretKey)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SLOW_SQL_CLOUD_NOT_CONFIGURED.name()));
        }
        return new String[] { secretId, secretKey };
    }

    protected String decryptConfigValue(String encrypted) {
        return CryptService.INSTANCE.decryptUseDefaultKeyAndSalt(encrypted);
    }

    private SlowSqlTopRowVO mapTopRow(SlowLogTopSqlItem item) {
        SlowSqlTopRowVO vo = new SlowSqlTopRowVO();
        vo.setMd5(item.getMd5());
        vo.setSchema(item.getSchema());
        vo.setSqlTemplate(item.getSqlTemplate());
        vo.setSqlText(item.getSqlText());
        vo.setExecTimes(item.getExecTimes());
        vo.setQueryTime(item.getQueryTime());
        vo.setQueryTimeAvg(item.getQueryTimeAvg());
        vo.setQueryTimeMax(item.getQueryTimeMax());
        vo.setLockTime(item.getLockTime());
        vo.setLockTimeAvg(item.getLockTimeAvg());
        vo.setRowsExamined(item.getRowsExamined());
        vo.setRowsExaminedAvg(item.getRowsExaminedAvg());
        vo.setRowsSent(item.getRowsSent());
        vo.setRowsSentAvg(item.getRowsSentAvg());
        return vo;
    }

    private SlowSqlAdviceVO parseAdviceResponse(DescribeUserSqlAdviceResponse resp) {
        SlowSqlAdviceVO vo = new SlowSqlAdviceVO();
        List<String> comments = parseStringList(resp.getComments());
        vo.setAdvices(parseAdvices(resp.getAdvices(), comments));
        vo.setComments(comments);
        vo.setCost(parseCost(resp.getCost()));
        vo.setSqlPlan(StringUtils.isBlank(resp.getSqlPlan()) ? null : resp.getSqlPlan());
        return vo;
    }

    private List<SlowSqlAdviceVO.AdviceItem> parseAdvices(String advicesJson, List<String> comments) {
        if (StringUtils.isBlank(advicesJson)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(advicesJson);
            List<SlowSqlAdviceVO.AdviceItem> result = new ArrayList<>();
            for (JsonNode node : root) {
                SlowSqlAdviceVO.AdviceItem item = new SlowSqlAdviceVO.AdviceItem();
                item.setTableName(getTextValue(node, "TableName"));
                item.setTableSchema(getTextValue(node, "TableSchema"));
                List<String> indexSqls = new ArrayList<>();
                JsonNode keysNode = node.get("Keys");
                if (keysNode != null && keysNode.isArray()) {
                    for (JsonNode key : keysNode) {
                        String sqlText = getTextValue(key, "SqlText");
                        if (StringUtils.isNotBlank(sqlText)) {
                            indexSqls.add(sqlText);
                        }
                    }
                }
                item.setIndexSqls(indexSqls);
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse advice Advices JSON, falling back to raw string: {}", e.getMessage());
            comments.add(advicesJson);
            return new ArrayList<>();
        }
    }

    private List<String> parseStringList(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            List<String> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    result.add(node.asText());
                }
            } else {
                result.add(json);
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse advice JSON string list, returning raw: {}", e.getMessage());
            List<String> result = new ArrayList<>();
            result.add(json);
            return result;
        }
    }

    private SlowSqlAdviceVO.CostInfo parseCost(String costJson) {
        if (StringUtils.isBlank(costJson)) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(costJson);
            SlowSqlAdviceVO.CostInfo cost = new SlowSqlAdviceVO.CostInfo();
            cost.setBefore(getDoubleValue(root, "Before"));
            cost.setAfter(getDoubleValue(root, "After"));
            cost.setRatio(getDoubleValue(root, "Ratio"));
            return cost;
        } catch (Exception e) {
            log.warn("Failed to parse advice Cost JSON: {}", e.getMessage());
            return null;
        }
    }

    private String getTextValue(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        return node == null || node.isNull() ? null : node.asText();
    }

    private Double getDoubleValue(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asDouble();
    }

    private ThirdPartyApiException mapSdkException(TencentCloudSDKException e) {
        String errorCode = e.getErrorCode();
        log.warn("DBbrain SDK error: code={}, message={}", errorCode, e.getMessage());
        if (errorCode != null) {
            if (errorCode.startsWith("AuthFailure") || errorCode.startsWith("UnauthorizedOperation")) {
                return ThirdPartyApiException.as().with(e, ThirdPartyApiErrorType.OTHER, I18nDmMsgKeys.SLOW_SQL_CLOUD_AUTH_FAILURE.name());
            }
            if (errorCode.startsWith("LimitExceeded")) {
                return ThirdPartyApiException.as().with(e, ThirdPartyApiErrorType.OTHER, I18nDmMsgKeys.SLOW_SQL_CLOUD_LIMIT_EXCEEDED.name());
            }
            if (errorCode.startsWith("OperationDenied") || errorCode.startsWith("UnsupportedOperation")) {
                return ThirdPartyApiException.as().with(e, ThirdPartyApiErrorType.OTHER, I18nDmMsgKeys.SLOW_SQL_ADVICE_PRO_VERSION_REQUIRED.name());
            }
        }
        return ThirdPartyApiException.as().with(e, ThirdPartyApiErrorType.OTHER, I18nDmMsgKeys.SLOW_SQL_CLOUD_CALL_FAILED.name(), errorCode == null ? "unknown" : errorCode);
    }

    private static final class CloudMapping {

        final String cloudInstanceId;
        final String region;

        CloudMapping(String cloudInstanceId, String region) {
            this.cloudInstanceId = cloudInstanceId;
            this.region = region;
        }
    }
}
