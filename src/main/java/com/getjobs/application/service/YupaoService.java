package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.getjobs.application.entity.YupaoConfigEntity;
import com.getjobs.application.entity.YupaoJobDataEntity;
import com.getjobs.application.mapper.YupaoConfigMapper;
import com.getjobs.application.mapper.YupaoJobDataMapper;
import com.getjobs.worker.yupao.YupaoConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 鱼泡直聘服务：配置读写、数据表迁移与投递数据操作
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class YupaoService {
    private final YupaoConfigMapper yupaoConfigMapper;
    private final YupaoJobDataMapper yupaoJobDataMapper;
    private final DataSource dataSource;

    /** 获取第一条配置 */
    public YupaoConfigEntity getFirstConfig() {
        QueryWrapper<YupaoConfigEntity> wrapper = new QueryWrapper<>();
        wrapper.last("LIMIT 1");
        return yupaoConfigMapper.selectOne(wrapper);
    }

    /** 从专表构建运行期 YupaoConfig */
    public YupaoConfig loadYupaoConfig() {
        YupaoConfigEntity entity = getFirstConfig();
        YupaoConfig config = new YupaoConfig();
        if (entity == null) {
            config.setKeywords(new ArrayList<>());
            config.setCityCode("a180");
            config.setSalary("0");
            config.setSayHi("");
            config.setBlackKeywords(new ArrayList<>());
            config.setCompanyBlacklist(new ArrayList<>());
            config.setDegree(new ArrayList<>());
            config.setFilterProxy(false);
            return config;
        }
        config.setKeywords(parseListString(entity.getKeywords()));
        String city = safeTrim(entity.getCityCode());
        config.setCityCode((city == null || city.isEmpty()) ? "a180" : city);
        String salary = safeTrim(entity.getSalary());
        config.setSalary((salary == null || salary.isEmpty() || "不限".equals(salary)) ? "0" : salary);
        config.setSayHi(entity.getSayHi() == null ? "" : entity.getSayHi());
        config.setBlackKeywords(parseListString(entity.getBlackKeywords()));
        config.setCompanyBlacklist(parseListString(entity.getCompanyBlacklist()));
        config.setDegree(parseListString(entity.getDegree()));
        config.setFilterProxy(entity.getFilterProxy() != null && entity.getFilterProxy() == 1);
        return config;
    }

    public List<String> parseListString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String s = raw.trim().replace('，', ',');
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        if (s.trim().isEmpty()) return new ArrayList<>();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .map(this::stripWrapperQuotes)
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toList());
    }

    private String safeTrim(String s) { return s == null ? null : s.trim(); }

    private String stripWrapperQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /** 选择性更新：传入 ID 则按 ID 更新；否则更新第一条（不存在则插入） */
    public YupaoConfigEntity updateConfig(YupaoConfigEntity config) {
        if (config == null) return null;
        if (config.getId() != null) {
            yupaoConfigMapper.updateById(config);
            return yupaoConfigMapper.selectById(config.getId());
        }
        return saveOrUpdateFirstSelective(config);
    }

    public YupaoConfigEntity saveOrUpdateFirstSelective(YupaoConfigEntity incoming) {
        YupaoConfigEntity first = getFirstConfig();
        LocalDateTime now = LocalDateTime.now();
        if (first == null) {
            YupaoConfigEntity toInsert = new YupaoConfigEntity();
            toInsert.setKeywords(incoming.getKeywords());
            toInsert.setCityCode(incoming.getCityCode());
            toInsert.setSalary(incoming.getSalary());
            toInsert.setSayHi(incoming.getSayHi());
            toInsert.setBlackKeywords(incoming.getBlackKeywords());
            toInsert.setCompanyBlacklist(incoming.getCompanyBlacklist());
            toInsert.setDegree(incoming.getDegree());
            toInsert.setFilterProxy(incoming.getFilterProxy());
            toInsert.setCreatedAt(now);
            toInsert.setUpdatedAt(now);
            yupaoConfigMapper.insert(toInsert);
            return getFirstConfig();
        } else {
            YupaoConfigEntity toUpdate = new YupaoConfigEntity();
            toUpdate.setId(first.getId());
            if (incoming.getKeywords() != null) toUpdate.setKeywords(incoming.getKeywords());
            if (incoming.getCityCode() != null) toUpdate.setCityCode(incoming.getCityCode());
            if (incoming.getSalary() != null) toUpdate.setSalary(incoming.getSalary());
            if (incoming.getSayHi() != null) toUpdate.setSayHi(incoming.getSayHi());
            if (incoming.getBlackKeywords() != null) toUpdate.setBlackKeywords(incoming.getBlackKeywords());
            if (incoming.getCompanyBlacklist() != null) toUpdate.setCompanyBlacklist(incoming.getCompanyBlacklist());
            if (incoming.getDegree() != null) toUpdate.setDegree(incoming.getDegree());
            if (incoming.getFilterProxy() != null) toUpdate.setFilterProxy(incoming.getFilterProxy());
            toUpdate.setCreatedAt(first.getCreatedAt());
            toUpdate.setUpdatedAt(now);
            yupaoConfigMapper.updateById(toUpdate);
            return yupaoConfigMapper.selectById(first.getId());
        }
    }

    // ==================== 数据表初始化 ====================

    @PostConstruct
    public void ensureYupaoTablesExist() {
        String createData = "CREATE TABLE IF NOT EXISTS yupao_data (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " job_id VARCHAR(64)," +
                " job_title VARCHAR(200)," +
                " job_link VARCHAR(400)," +
                " salary VARCHAR(100)," +
                " location VARCHAR(100)," +
                " experience VARCHAR(100)," +
                " degree VARCHAR(100)," +
                " company_name VARCHAR(200)," +
                " delivery_status VARCHAR(20) DEFAULT '未投递'," +
                " create_time DATETIME," +
                " update_time DATETIME" +
                ")";
        String createConfig = "CREATE TABLE IF NOT EXISTS yupao_config (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " keywords VARCHAR(500)," +
                " city_code VARCHAR(200)," +
                " salary VARCHAR(50)," +
                " say_hi VARCHAR(1000)," +
                " black_keywords VARCHAR(500)," +
                " company_blacklist VARCHAR(1000)," +
                " degree VARCHAR(200)," +
                " filter_proxy INTEGER DEFAULT 0," +
                " created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                " updated_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createData);
            stmt.execute(createConfig);
            log.info("确保 yupao_data / yupao_config 表已存在");
        } catch (Exception e) {
            log.warn("创建鱼泡表失败: {}", e.getMessage());
        }
        seedDefaultsFromZhilian();
    }

    /** 首次使用时，从 zhilian_config 复制默认值（关键词/薪资/黑名单/过滤代招） */
    private void seedDefaultsFromZhilian() {
        try {
            if (getFirstConfig() != null) return; // 已有配置，不覆盖
        } catch (Exception ignored) { return; }
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            String keywords = null, salary = null, blackKeywords = null;
            Integer filterProxy = 0;
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM zhilian_config LIMIT 1")) {
                Set<String> cols = new HashSet<>();
                java.sql.ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnName(i).toLowerCase());
                if (rs.next()) {
                    if (cols.contains("keywords")) keywords = rs.getString("keywords");
                    if (cols.contains("salary")) salary = rs.getString("salary");
                    if (cols.contains("black_keywords")) blackKeywords = rs.getString("black_keywords");
                    if (cols.contains("filter_proxy")) {
                        Object v = rs.getObject("filter_proxy");
                        if (v != null) filterProxy = ((Number) v).intValue();
                    }
                }
            } catch (Exception ignored) {}
            YupaoConfigEntity seed = new YupaoConfigEntity();
            seed.setKeywords(keywords);
            seed.setCityCode("a180");
            seed.setSalary(salary);
            seed.setSayHi("");
            seed.setBlackKeywords(blackKeywords);
            seed.setCompanyBlacklist("[]");
            seed.setDegree("[]");
            seed.setFilterProxy(filterProxy);
            LocalDateTime now = LocalDateTime.now();
            seed.setCreatedAt(now);
            seed.setUpdatedAt(now);
            yupaoConfigMapper.insert(seed);
            log.info("已从智联配置为鱼泡播种默认值");
        } catch (Exception e) {
            log.warn("从智联播种鱼泡默认配置失败: {}", e.getMessage());
        }
    }

    // ==================== 投递数据操作 ====================

    public boolean existsByJobId(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return false;
        QueryWrapper<YupaoJobDataEntity> w = new QueryWrapper<>();
        w.eq("job_id", jobId).last("LIMIT 1");
        Long c = yupaoJobDataMapper.selectCount(w);
        return c != null && c > 0;
    }

    public void insertJob(YupaoJobDataEntity entity) {
        if (entity == null) return;
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        if (entity.getDeliveryStatus() == null) entity.setDeliveryStatus("未投递");
        yupaoJobDataMapper.insert(entity);
    }

    public void markDeliveredByJobId(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return;
        YupaoJobDataEntity upd = new YupaoJobDataEntity();
        upd.setDeliveryStatus("已投递");
        upd.setUpdateTime(LocalDateTime.now());
        UpdateWrapper<YupaoJobDataEntity> uw = new UpdateWrapper<>();
        uw.eq("job_id", jobId);
        yupaoJobDataMapper.update(upd, uw);
    }
}
