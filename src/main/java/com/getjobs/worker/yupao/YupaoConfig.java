package com.getjobs.worker.yupao;

import lombok.Data;

import java.util.List;

/**
 * @author get_jobs
 * 鱼泡直聘运行期配置（由 YupaoService 从 yupao_config 专表构建）
 */
@Data
public class YupaoConfig {
    /** 搜索关键词列表 */
    private List<String> keywords;

    /** 城市编码（鱼泡 a 码，如 a180） */
    private String cityCode;

    /** 薪资范围 */
    private String salary;

    /** 打招呼语 */
    private String sayHi;

    /** 黑名单关键词：命中岗位标题则过滤 */
    private List<String> blackKeywords;

    /** 公司黑名单：命中公司名则过滤 */
    private List<String> companyBlacklist;

    /** 学历过滤白名单：仅投递这些学历（留空=不限） */
    private List<String> degree;

    /** 是否自动过滤代招岗位 */
    private boolean filterProxy;

    /** 招聘者活跃过滤：仅投递该天数内活跃者（0=不限，1=今日，3=三天内，7=本周） */
    private int activeWithinDays;
}
