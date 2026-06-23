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
import java.util.Map;
import java.util.Objects;
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
            config.setActiveWithinDays(0);
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
        config.setActiveWithinDays(entity.getActiveWithinDays() == null ? 0 : entity.getActiveWithinDays());
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
            toInsert.setActiveWithinDays(incoming.getActiveWithinDays());
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
            if (incoming.getActiveWithinDays() != null) toUpdate.setActiveWithinDays(incoming.getActiveWithinDays());
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
                " active_within_days INTEGER DEFAULT 0," +
                " created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                " updated_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createData);
            stmt.execute(createConfig);
            // 兼容旧表：补充缺失列（幂等）
            Set<String> cols = new HashSet<>();
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(yupao_config)")) {
                while (rs.next()) cols.add(rs.getString("name"));
            }
            if (!cols.contains("active_within_days")) {
                stmt.execute("ALTER TABLE yupao_config ADD COLUMN active_within_days INTEGER DEFAULT 0");
                log.info("yupao_config 新增列 active_within_days");
            }
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
            seed.setActiveWithinDays(0);
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

    // ==================== 投递分析（Dashboard）与列表 ====================

    public static class SalaryInfo {
        public Integer minK;
        public Integer maxK;
        public Integer months;
        public Double medianK;
        public Long annualTotal;
    }

    /** 解析薪资字符串，支持 8000-15000元/月、1.2-1.8万元/月、6000元/月 等 */
    public static SalaryInfo parseSalary(String salary) {
        if (salary == null) return null;
        String s = salary.trim();
        if (s.isEmpty() || s.contains("面议")) return null;
        s = s.replace(" ", "");
        boolean wan = s.contains("万");
        // 提取数字区间
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)-([0-9]+(?:\\.[0-9]+)?)").matcher(s);
        Double minK = null, maxK = null;
        if (m.find()) {
            try {
                double a = Double.parseDouble(m.group(1));
                double b = Double.parseDouble(m.group(2));
                if (wan) { a = a * 10; b = b * 10; }      // 万元/月 -> K
                else if (a > 1000) { a = a / 1000; b = b / 1000; } // 元/月 -> K
                minK = a; maxK = b;
            } catch (Exception ignore) {}
        } else {
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(s);
            if (m2.find()) {
                try {
                    double a = Double.parseDouble(m2.group(1));
                    if (wan) a = a * 10; else if (a > 1000) a = a / 1000;
                    minK = a; maxK = a;
                } catch (Exception ignore) {}
            }
        }
        if (minK == null || maxK == null) return null;
        SalaryInfo info = new SalaryInfo();
        info.minK = (int) Math.round(minK);
        info.maxK = (int) Math.round(maxK);
        info.months = 12;
        info.medianK = (minK + maxK) / 2.0;
        info.annualTotal = Math.round(info.medianK * 1000 * info.months);
        return info;
    }

    public static class Kpi {
        public long total;
        public long delivered;
        public long pending;
        public long filtered;
        public long failed;
        public Double avgMonthlyK;
    }

    public static class NameValue { public String name; public long value; public NameValue(){} public NameValue(String n,long v){name=n;value=v;} }
    public static class BucketValue { public String bucket; public long value; public BucketValue(){} public BucketValue(String b,long v){bucket=b;value=v;} }

    public static class Charts {
        public List<NameValue> byStatus;
        public List<NameValue> byCity;
        public List<NameValue> byCompany;
        public List<NameValue> byExperience;
        public List<NameValue> byDegree;
        public List<BucketValue> salaryBuckets;
        public List<NameValue> dailyTrend;
    }

    public static class StatsResponse { public Kpi kpi; public Charts charts; }

    public static class PagedResult {
        public List<YupaoJobDataEntity> items;
        public long total;
        public int page;
        public int size;
    }

    private static String nullSafe(String s) { return s == null ? "" : s.trim(); }

    private List<YupaoJobDataEntity> queryFiltered(List<String> statuses, String location, String experience,
                                                   String degree, Double minK, Double maxK, String keyword) {
        QueryWrapper<YupaoJobDataEntity> wrapper = new QueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in("delivery_status", statuses.stream().filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet()));
        }
        if (location != null && !location.trim().isEmpty()) wrapper.eq("location", location.trim());
        if (experience != null && !experience.trim().isEmpty()) wrapper.eq("experience", experience.trim());
        if (degree != null && !degree.trim().isEmpty()) wrapper.eq("degree", degree.trim());
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("company_name", kw).or().like("job_title", kw));
        }
        wrapper.orderByDesc("create_time");
        List<YupaoJobDataEntity> all = yupaoJobDataMapper.selectList(wrapper);
        List<YupaoJobDataEntity> filtered = new ArrayList<>();
        for (YupaoJobDataEntity e : all) {
            if (minK == null && maxK == null) { filtered.add(e); continue; }
            SalaryInfo info = parseSalary(e.getSalary());
            if (info == null || info.medianK == null) continue;
            boolean ok = true;
            if (minK != null) ok = info.medianK >= minK;
            if (ok && maxK != null) ok = info.medianK <= maxK;
            if (ok) filtered.add(e);
        }
        return filtered;
    }

    public StatsResponse getYupaoStats(List<String> statuses, String location, String experience,
                                       String degree, Double minK, Double maxK, String keyword) {
        List<YupaoJobDataEntity> filtered = queryFiltered(statuses, location, experience, degree, minK, maxK, keyword);

        Kpi kpi = new Kpi();
        kpi.total = filtered.size();
        kpi.delivered = filtered.stream().filter(e -> "已投递".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.pending = filtered.stream().filter(e -> "未投递".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.filtered = filtered.stream().filter(e -> "已过滤".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.failed = filtered.stream().filter(e -> "投递失败".equals(nullSafe(e.getDeliveryStatus()))).count();
        {
            List<Double> medians = new ArrayList<>();
            for (YupaoJobDataEntity e : filtered) {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info != null && info.medianK != null) medians.add(info.medianK);
            }
            kpi.avgMonthlyK = medians.isEmpty() ? null : medians.stream().mapToDouble(d -> d).average().orElse(0.0);
        }

        Charts charts = new Charts();
        charts.byStatus = new ArrayList<>();
        charts.byCity = new ArrayList<>();
        charts.byCompany = new ArrayList<>();
        charts.byExperience = new ArrayList<>();
        charts.byDegree = new ArrayList<>();
        charts.salaryBuckets = new ArrayList<>();
        charts.dailyTrend = new ArrayList<>();

        filtered.stream().collect(Collectors.groupingBy(e -> nullSafe(e.getDeliveryStatus()), Collectors.counting()))
                .forEach((k, v) -> charts.byStatus.add(new NameValue(k, v)));
        filtered.stream().filter(e -> e.getLocation() != null && !e.getLocation().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getLocation()), Collectors.counting()))
                .forEach((k, v) -> charts.byCity.add(new NameValue(k, v)));
        filtered.stream().filter(e -> e.getCompanyName() != null && !e.getCompanyName().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getCompanyName()), Collectors.counting()))
                .forEach((k, v) -> charts.byCompany.add(new NameValue(k, v)));
        filtered.stream().filter(e -> e.getExperience() != null && !e.getExperience().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getExperience()), Collectors.counting()))
                .forEach((k, v) -> charts.byExperience.add(new NameValue(k, v)));
        filtered.stream().filter(e -> e.getDegree() != null && !e.getDegree().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getDegree()), Collectors.counting()))
                .forEach((k, v) -> charts.byDegree.add(new NameValue(k, v)));
        filtered.stream().filter(e -> e.getCreateTime() != null)
                .collect(Collectors.groupingBy(e -> e.getCreateTime().toLocalDate().toString(), Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(en -> charts.dailyTrend.add(new NameValue(en.getKey(), en.getValue())));

        long b0=0,b1=0,b2=0,b3=0,b4=0;
        for (YupaoJobDataEntity e : filtered) {
            SalaryInfo info = parseSalary(e.getSalary());
            if (info == null || info.medianK == null) continue;
            double mm = info.medianK;
            if (mm < 10) b0++; else if (mm < 15) b1++; else if (mm < 20) b2++; else if (mm < 25) b3++; else b4++;
        }
        charts.salaryBuckets.add(new BucketValue("0-10K", b0));
        charts.salaryBuckets.add(new BucketValue("10-15K", b1));
        charts.salaryBuckets.add(new BucketValue("15-20K", b2));
        charts.salaryBuckets.add(new BucketValue("20-25K", b3));
        charts.salaryBuckets.add(new BucketValue(">=25K", b4));

        StatsResponse resp = new StatsResponse();
        resp.kpi = kpi;
        resp.charts = charts;
        return resp;
    }

    public PagedResult listYupaoJobs(List<String> statuses, String location, String experience,
                                     String degree, Double minK, Double maxK, String keyword, int page, int size) {
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;
        List<YupaoJobDataEntity> filtered = queryFiltered(statuses, location, experience, degree, minK, maxK, keyword);
        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        PagedResult pr = new PagedResult();
        pr.items = from >= to ? new ArrayList<>() : filtered.subList(from, to);
        pr.total = total;
        pr.page = page;
        pr.size = size;
        return pr;
    }
}
