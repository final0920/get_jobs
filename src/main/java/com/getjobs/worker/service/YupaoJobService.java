package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.yupao.YuPao;
import com.getjobs.worker.yupao.YupaoConfig;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 鱼泡直聘任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YupaoJobService implements JobPlatformService {
    private static final String PLATFORM = "yupao";

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<YuPao> yupaoProvider;
    private final ConfigService configService;

    private volatile boolean isRunning = false;
    private volatile boolean shouldStop = false;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        if (isRunning) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }
        try {
            Page page = playwrightManager.getYupaoPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "鱼泡直聘页面未初始化"));
                return;
            }
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录鱼泡直聘"));
                return;
            }

            isRunning = true;
            shouldStop = false;
            playwrightManager.pauseYupaoMonitoring();

            YupaoConfig config = configService.getYupaoConfig();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            YuPao.ProgressCallback cb = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            YuPao yupao = yupaoProvider.getObject();
            yupao.setPage(page);
            yupao.setConfig(config);
            yupao.setProgressCallback(cb);
            yupao.setShouldStopCallback(this::shouldStop);
            yupao.prepare();

            int deliveredCount = yupao.execute();
            progressCallback.accept(JobProgressMessage.success(PLATFORM,
                    String.format("投递任务完成，共沟通%d个职位", deliveredCount)));
        } catch (Exception e) {
            log.error("鱼泡直聘投递任务执行失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            isRunning = false;
            shouldStop = false;
            try { playwrightManager.resumeYupaoMonitoring(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void stopDelivery() {
        if (isRunning) {
            log.info("收到停止鱼泡直聘投递任务的请求");
            shouldStop = true;
            // 协作式停止兜底：直接释放运行状态，确保卡在反爬/阻塞调用时也能重启
            isRunning = false;
            try { playwrightManager.resumeYupaoMonitoring(); } catch (Exception ignored) {}
        }
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    public boolean shouldStop() {
        return shouldStop;
    }
}
