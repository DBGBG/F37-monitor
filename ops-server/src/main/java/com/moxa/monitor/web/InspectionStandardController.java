package com.moxa.monitor.web;

import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.inspection.InspectionStandardService;
import com.moxa.monitor.service.inspection.InspectionStandardService.StandardForm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 点检标准库接口: 标准 CRUD / 启停 / 下拉字典.
 * 读接口 VIEWER 可用; 写操作与点检计划同权限 (OPERATOR 及以上).
 */
@RestController
@RequestMapping("/api/inspection/standards")
public class InspectionStandardController {

    private final InspectionStandardService standardService;
    private final RoleGuard roleGuard;

    public InspectionStandardController(InspectionStandardService standardService, RoleGuard roleGuard) {
        this.standardService = standardService;
        this.roleGuard = roleGuard;
    }

    /** GET /api/inspection/standards?keyword&category&enabled */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "") String keyword,
                                            @RequestParam(defaultValue = "all") String category,
                                            @RequestParam(required = false) String enabled) {
        return Result.ok(standardService.list(keyword, category, enabled));
    }

    /** GET /api/inspection/standards/options */
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        return Result.ok(standardService.options());
    }

    /** GET /api/inspection/standards/{id} */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Integer id) {
        return Result.ok(standardService.get(id));
    }

    /** POST /api/inspection/standards */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody StandardForm form) {
        roleGuard.requireOperator();
        return Result.ok(standardService.create(form));
    }

    /** PUT /api/inspection/standards/{id} */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Integer id, @RequestBody StandardForm form) {
        roleGuard.requireOperator();
        return Result.ok(standardService.update(id, form));
    }

    /** POST /api/inspection/standards/{id}/toggle */
    @PostMapping("/{id}/toggle")
    public Result<Map<String, Object>> toggle(@PathVariable Integer id) {
        roleGuard.requireOperator();
        return Result.ok(standardService.toggle(id));
    }

    /** DELETE /api/inspection/standards/{id} */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Integer id) {
        roleGuard.requireOperator();
        return Result.ok(standardService.delete(id));
    }
}
