package com.getjobs.worker.yupao;

import com.getjobs.application.entity.YupaoJobDataEntity;
import com.getjobs.application.service.YupaoService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 鱼泡直聘自动投递 - Playwright 版本
 * 投递模型为“直聘/打招呼”：进入职位详情页点击「免费聊」建立沟通并发送打招呼语。
 *
 * 注意：鱼泡有较强反爬（自动化下页面会被置空），运行依赖 PlaywrightManager 在 yupao.com
 * 注入的加强版反检测脚本（窗口尺寸伪装 + 拦截 about:blank 导航 + console 防展开）。
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class YuPao {

    @Setter
    private Page page;

    @Setter
    private YupaoConfig config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final YupaoService yupaoService;

    private int deliveredCount = 0;
    private static final String HOME = "https://www.yupao.com";
    private static final int MAX_PAGE_PER_KEYWORD = 5;

    /** 代招标记词 */
    private static final String[] PROXY_MARKERS = {"代招", "代理招聘", "招聘代理"};
    /** 学历词表（用于从卡片文本中识别学历） */
    private static final String[] DEGREE_WORDS = {"博士", "硕士", "本科", "大专", "中专", "中技", "高中", "初中", "学历不限"};

    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    public void prepare() {
        log.info("鱼泡直聘准备工作开始...");
        deliveredCount = 0;
        log.info("鱼泡直聘准备工作完成");
    }

    public int execute() {
        log.info("鱼泡直聘投递任务开始...");
        long startTime = System.currentTimeMillis();
        try {
            List<String> keywords = config.getKeywords();
            if (keywords == null || keywords.isEmpty()) {
                sendProgress("未配置搜索关键词，任务结束", null, null);
                return 0;
            }
            for (String keyword : keywords) {
                if (shouldStop()) { sendProgress("用户取消投递", null, null); break; }
                deliverByKeyword(keyword);
            }
            long duration = System.currentTimeMillis() - startTime;
            sendProgress(String.format("鱼泡直聘投递完成，共沟通%d个岗位，用时%s", deliveredCount, formatDuration(duration)), null, null);
        } catch (Exception e) {
            log.error("鱼泡直聘投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
        }
        return deliveredCount;
    }

    /** 按关键词投递 */
    private void deliverByKeyword(String keyword) {
        sendProgress("正在搜索关键词: " + keyword, null, null);
        for (int pageNum = 1; pageNum <= MAX_PAGE_PER_KEYWORD; pageNum++) {
            if (shouldStop()) return;
            String url = buildSearchUrl(keyword, pageNum);
            try {
                page.navigate(url, new Page.NavigateOptions().setTimeout(60000));
                PlaywrightUtil.sleep(2);
            } catch (Exception e) {
                log.warn("导航搜索页失败: {}", e.getMessage());
                return;
            }

            List<Map<String, Object>> jobs = collectJobs();
            if (jobs.isEmpty()) {
                log.info("关键词【{}】第{}页未采集到岗位，停止翻页", keyword, pageNum);
                return;
            }
            sendProgress(String.format("关键词【%s】第%d页采集到%d个岗位", keyword, pageNum, jobs.size()), pageNum, MAX_PAGE_PER_KEYWORD);

            // 先过滤 + 入库，收集待投递列表
            List<Map<String, Object>> deliverable = new ArrayList<>();
            for (Map<String, Object> job : jobs) {
                if (shouldStop()) return;
                String jobId = str(job.get("jobId"));
                String title = str(job.get("title"));
                String company = str(job.get("company"));
                String text = str(job.get("text"));
                String degree = detectDegree(text);

                String filterReason = filterReason(title, company, text);
                boolean filtered = filterReason != null;

                if (jobId != null && !jobId.isEmpty() && !yupaoService.existsByJobId(jobId)) {
                    YupaoJobDataEntity entity = new YupaoJobDataEntity();
                    entity.setJobId(jobId);
                    entity.setJobTitle(title);
                    entity.setJobLink(str(job.get("link")));
                    entity.setSalary(detectSalary(text));
                    entity.setDegree(degree);
                    entity.setCompanyName(company);
                    entity.setDeliveryStatus(filtered ? "已过滤" : "未投递");
                    try { yupaoService.insertJob(entity); } catch (Exception ex) { log.warn("入库失败: {}", ex.getMessage()); }
                }

                if (filtered) {
                    log.info("过滤岗位[{}]：title={}，company={}", filterReason, title, company);
                } else {
                    deliverable.add(job);
                }
            }

            // 逐个进入详情页投递（导航离开列表，故先采集后投递）
            for (Map<String, Object> job : deliverable) {
                if (shouldStop()) return;
                deliverOne(job);
                PlaywrightUtil.sleep(1);
            }
        }
    }

    /** 在当前列表页采集岗位结构化数据 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectJobs() {
        try {
            page.waitForSelector("a[href*='/zhaogong/']", new Page.WaitForSelectorOptions().setTimeout(10000));
        } catch (Exception e) {
            log.warn("等待岗位列表超时");
            return new ArrayList<>();
        }
        try {
            Object res = page.evaluate("() => {\n" +
                    "  const out = []; const seen = new Set();\n" +
                    "  document.querySelectorAll(\"a[href*='/zhaogong/']\").forEach(a => {\n" +
                    "    const href = a.getAttribute('href') || '';\n" +
                    "    const m = href.match(/\\/zhaogong\\/(\\d+)/);\n" +
                    "    if (!m) return; const jobId = m[1]; if (seen.has(jobId)) return;\n" +
                    "    let company = ''; let scope = a.parentElement;\n" +
                    "    for (let i=0;i<5 && scope;i++){ const q = scope.querySelector(\"a[href*='/qiye/']\"); if (q){ company=(q.innerText||'').trim(); break;} scope = scope.parentElement; }\n" +
                    "    const card = a.closest('li') || a.parentElement;\n" +
                    "    const text = ((card?card.innerText:a.innerText)||'').replace(/\\s+/g,' ').trim().slice(0,400);\n" +
                    "    const title = ((a.innerText||'').trim().split('\\n')[0]||'').slice(0,80);\n" +
                    "    seen.add(jobId);\n" +
                    "    out.push({ jobId, link: a.href, title, company, text });\n" +
                    "  });\n" +
                    "  return out.slice(0, 60);\n" +
                    "}");
            if (res instanceof List) return (List<Map<String, Object>>) res;
        } catch (Exception e) {
            log.warn("采集岗位数据失败: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /** 进入详情页投递：点击「免费聊」并发送打招呼语 */
    private void deliverOne(Map<String, Object> job) {
        String title = str(job.get("title"));
        String jobId = str(job.get("jobId"));
        String link = str(job.get("link"));
        if (link == null || link.isEmpty()) return;
        try {
            page.navigate(link, new Page.NavigateOptions().setTimeout(60000));
            PlaywrightUtil.sleep(2);

            // 已沟通过则跳过
            if (page.getByText("继续聊", new Page.GetByTextOptions().setExact(true)).count() > 0) {
                log.info("岗位已沟通过，跳过：{}", title);
                if (jobId != null) yupaoService.markDeliveredByJobId(jobId);
                return;
            }

            Locator chatBtn = page.getByText("免费聊", new Page.GetByTextOptions().setExact(true)).first();
            if (chatBtn.count() == 0) {
                log.info("未找到「免费聊」按钮，跳过：{}", title);
                return;
            }
            chatBtn.click(new Locator.ClickOptions().setTimeout(8000));
            PlaywrightUtil.sleep(2);

            // 发送打招呼语（best-effort：鱼泡走腾讯云IM，聊天输入框选择器可能需联调）
            sendGreeting();

            if (jobId != null) yupaoService.markDeliveredByJobId(jobId);
            deliveredCount++;
            log.info("已沟通岗位：{}", title);
            sendProgress("已沟通: " + title, null, null);
        } catch (Exception e) {
            log.warn("投递岗位失败【{}】: {}", title, e.getMessage());
        }
    }

    /** 在聊天面板输入并发送打招呼语 */
    private void sendGreeting() {
        String sayHi = config.getSayHi();
        if (sayHi == null || sayHi.isBlank()) return;
        String[] inputSelectors = {
                "textarea",
                "[contenteditable='true']",
                "div[class*='editor'] [contenteditable]",
                "input[type='text'][placeholder*='说']"
        };
        for (String sel : inputSelectors) {
            try {
                Locator input = page.locator(sel).last();
                if (input.count() > 0 && input.isVisible()) {
                    input.click(new Locator.ClickOptions().setTimeout(3000));
                    input.fill(sayHi);
                    PlaywrightUtil.sleep(1);
                    try { input.press("Enter"); } catch (Exception ignored) {}
                    log.info("已发送打招呼语");
                    return;
                }
            } catch (Exception ignored) {}
        }
        log.debug("未定位到聊天输入框，跳过打招呼语发送（沟通已建立）");
    }

    // ==================== 过滤逻辑 ====================

    /** 返回过滤原因（null=不过滤） */
    private String filterReason(String title, String company, String text) {
        if (containsAny(title, config.getBlackKeywords()) || containsAny(company, config.getBlackKeywords())) {
            return "黑名单关键词";
        }
        if (containsAny(company, config.getCompanyBlacklist())) {
            return "公司黑名单";
        }
        if (config.isFilterProxy() && containsAnyArr(text, PROXY_MARKERS)) {
            return "代招岗位";
        }
        List<String> allow = config.getDegree();
        if (allow != null && !allow.isEmpty()) {
            String degree = detectDegree(text);
            if (degree != null && !"学历不限".equals(degree) && !containsAny(degree, allow)) {
                return "学历不符";
            }
        }
        return null;
    }

    private String detectDegree(String text) {
        if (text == null) return null;
        for (String d : DEGREE_WORDS) {
            if (text.contains(d)) return d;
        }
        return null;
    }

    private String detectSalary(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([\\d.]+-[\\d.]+(?:万元?/?月?|元/月|元/天|元/日|万))").matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private boolean containsAny(String value, List<String> keys) {
        if (value == null || keys == null || keys.isEmpty()) return false;
        for (String k : keys) {
            if (k != null && !k.isBlank() && value.contains(k)) return true;
        }
        return false;
    }

    private boolean containsAnyArr(String value, String[] keys) {
        if (value == null) return false;
        for (String k : keys) if (value.contains(k)) return true;
        return false;
    }

    // ==================== 工具 ====================

    private String buildSearchUrl(String keyword, int pageNum) {
        String city = config.getCityCode() == null || config.getCityCode().isBlank() ? "a180" : config.getCityCode().trim();
        String kw = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        // 关键词搜索；分页通过 p 参数（鱼泡 topic 搜索分页，必要时联调）
        return HOME + "/topic/" + city + "c0/?keywords=" + kw + (pageNum > 1 ? "&p=" + pageNum : "");
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private String formatDuration(long millis) {
        long seconds = millis / 1000, minutes = seconds / 60, hours = minutes / 60;
        if (hours > 0) return String.format("%d小时%d分钟", hours, minutes % 60);
        if (minutes > 0) return String.format("%d分钟%d秒", minutes, seconds % 60);
        return String.format("%d秒", seconds);
    }

    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) progressCallback.accept(message, current, total);
    }

    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }
}
