package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 鱼泡直聘配置表实体
 */
@Data
@TableName("yupao_config")
public class YupaoConfigEntity {
    @TableId(type = IdType.AUTO)
    /** 主键ID */
    private Long id;

    /** 搜索关键词（逗号或括号列表） */
    private String keywords;

    /** 城市编码（鱼泡 a 码，如 a180=武汉；或城市名） */
    private String cityCode;

    /** 薪资范围（中文名或代码，单值） */
    private String salary;

    /** 打招呼语（直聘“免费聊”首次沟通时发送） */
    private String sayHi;

    /** 黑名单关键词（命中岗位标题则过滤不投递） */
    private String blackKeywords;

    /** 公司黑名单（命中公司名则过滤不投递） */
    private String companyBlacklist;

    /** 学历过滤（仅投递这些学历的岗位，逗号分隔；留空=不限） */
    private String degree;

    /** 是否自动过滤代招岗位：1=开启，0=关闭 */
    private Integer filterProxy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
