package com.moxa.monitor.web;

import com.moxa.monitor.common.Result;
import com.moxa.monitor.service.device.DeviceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仪表盘总览接口: 一次请求返回设备在线 / 告警 / 点检待办 / 健康分布 / 最近告警.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DeviceQueryService queryService;

    public DashboardController(DeviceQueryService queryService) {
        this.queryService = queryService;
    }

    /** GET /api/dashboard/overview */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(queryService.dashboardOverview());
    }
}
