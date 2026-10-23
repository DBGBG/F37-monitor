package com.moxa.monitor.web;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.system.CollectorMonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 采集任务周期管理 (collector_jobs).
 * 业务层只写这张表, 采集层每 5 分钟热加载一次; 保存时额外下发 reload_config, 改动无需等到下个热加载周期.
 */
@RestController
@RequestMapping("/api/collector-jobs")
public class CollectorJobController {

    private final CollectorMonitorService collectorService;
    private final RoleGuard roleGuard;

    public CollectorJobController(CollectorMonitorService collectorService, RoleGuard roleGuard) {
        this.collectorService = collectorService;
        this.roleGuard = roleGuard;
    }

    /** 全部字段可缺省, 缺省表示沿用当前值 */
    public record JobUpdateRequest(Integer intervalSeconds, Integer timeoutSeconds,
                                   Integer maxInstances, Boolean enabled) {
    }

    /** GET /api/collector-jobs */
    @GetMapping
    public Result<Map<String, Object>> list() {
        List<Map<String, Object>> jobs = collectorService.jobs();
        return Result.ok(Maps.of("total", jobs.size(), "items", jobs));
    }

    /** PUT /api/collector-jobs/{job_id} —— 仅 ADMIN */
    @PutMapping("/{job_id}")
    public Result<Map<String, Object>> update(@PathVariable("job_id") String jobId,
                                              @RequestBody JobUpdateRequest request) {
        roleGuard.requireAdmin();
        return Result.ok(collectorService.updateJob(jobId, request.intervalSeconds(),
                request.timeoutSeconds(), request.maxInstances(), request.enabled()));
    }
}
