package com.getjobs.application.controller;

import com.getjobs.application.entity.YupaoConfigEntity;
import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.YupaoService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.YupaoJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 鱼泡直聘控制器：配置管理、登录状态、任务控制
 */
@Slf4j
@RestController
@RequestMapping("/api/yupao")
@CrossOrigin(origins = "*")
public class YupaoController {

    @Autowired
    private YupaoService yupaoService;

    @Autowired
    private PlaywrightManager playwrightManager;

    @Autowired
    private CookieService cookieService;

    @Autowired
    private YupaoJobService yupaoJobService;

    // ==================== 配置 ====================

    @GetMapping("/config")
    public Map<String, Object> getAllYupaoConfig() {
        Map<String, Object> result = new HashMap<>();
        YupaoConfigEntity config = yupaoService.getFirstConfig();
        if (config == null) config = new YupaoConfigEntity();
        result.put("config", config);
        return result;
    }

    @PutMapping("/config")
    public YupaoConfigEntity updateConfig(@RequestBody YupaoConfigEntity config) {
        return yupaoService.updateConfig(config);
    }

    // ==================== 登录 ====================

    @GetMapping("/login-status")
    public ResponseEntity<Map<String, Object>> checkLoginStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean isLoggedIn = playwrightManager.isLoggedIn("yupao");
            response.put("success", true);
            response.put("isLoggedIn", isLoggedIn);
            response.put("message", isLoggedIn ? "已登录" : "未登录");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("检查登录状态失败", e);
            response.put("success", false);
            response.put("message", "检查登录状态失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> triggerYupaoLogin() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.triggerYupaoLogin();
            response.put("success", true);
            response.put("message", "已打开鱼泡直聘登录入口，请在浏览器扫码或验证码登录");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("触发鱼泡登录失败", e);
            response.put("success", false);
            response.put("message", "触发登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logoutYupao() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.setLoginStatus("yupao", false);
            cookieService.clearCookieByPlatform("yupao", "manual logout");
            try { playwrightManager.clearYupaoCookies(); } catch (Exception e) {
                log.warn("清理鱼泡上下文Cookie异常: {}", e.getMessage());
            }
            response.put("success", true);
            response.put("message", "鱼泡直聘已退出登录");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("退出登录失败", e);
            response.put("success", false);
            response.put("message", "退出登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== 任务 ====================

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startYupaoJob() {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!playwrightManager.isLoggedIn("yupao")) {
                response.put("success", false);
                response.put("message", "请先登录鱼泡直聘");
                response.put("status", "not_logged_in");
                return ResponseEntity.badRequest().body(response);
            }
            if (yupaoJobService.isRunning()) {
                response.put("success", false);
                response.put("message", "鱼泡直聘任务已在运行中");
                response.put("status", "running");
                return ResponseEntity.badRequest().body(response);
            }
            CompletableFuture.runAsync(() ->
                    yupaoJobService.executeDelivery(progressMessage ->
                            log.info("[{}] {}", progressMessage.getPlatform(), progressMessage.getMessage())));
            response.put("success", true);
            response.put("message", "鱼泡直聘任务启动成功");
            response.put("status", "started");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("启动鱼泡直聘任务失败", e);
            response.put("success", false);
            response.put("message", "启动失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopYupaoJob() {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!yupaoJobService.isRunning()) {
                response.put("success", false);
                response.put("message", "没有正在运行的鱼泡直聘任务");
                return ResponseEntity.badRequest().body(response);
            }
            yupaoJobService.stopDelivery();
            response.put("success", true);
            response.put("message", "鱼泡直聘任务停止请求已发送");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("停止鱼泡直聘任务失败", e);
            response.put("success", false);
            response.put("message", "停止失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCurrentStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> status = yupaoJobService.getStatus();
            response.put("success", true);
            response.putAll(status);
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取当前状态失败", e);
            response.put("success", false);
            response.put("message", "获取状态失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("service", "YupaoController");
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
}
