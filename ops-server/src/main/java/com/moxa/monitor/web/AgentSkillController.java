package com.moxa.monitor.web;

import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.agent.AgentSkillService;
import com.moxa.monitor.service.agent.AgentSkillService.SkillForm;
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
 * 智能体技能清单接口: 技能 CRUD / 启停 / 下拉字典.
 * 读接口 VIEWER 可用; 技能会左右智能体的可调用范围, 增删改仅 ADMIN.
 */
@RestController
@RequestMapping("/api/agent-skills")
public class AgentSkillController {

    private final AgentSkillService skillService;
    private final RoleGuard roleGuard;

    public AgentSkillController(AgentSkillService skillService, RoleGuard roleGuard) {
        this.skillService = skillService;
        this.roleGuard = roleGuard;
    }

    /** GET /api/agent-skills?keyword&category&enabled */
    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "") String keyword,
                                            @RequestParam(defaultValue = "all") String category,
                                            @RequestParam(required = false) String enabled) {
        return Result.ok(skillService.list(keyword, category, enabled));
    }

    /** GET /api/agent-skills/options */
    @GetMapping("/options")
    public Result<Map<String, Object>> options() {
        return Result.ok(skillService.options());
    }

    /** GET /api/agent-skills/{id} */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Integer id) {
        return Result.ok(skillService.get(id));
    }

    /** POST /api/agent-skills */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody SkillForm form) {
        roleGuard.requireAdmin();
        return Result.ok(skillService.create(form));
    }

    /** PUT /api/agent-skills/{id} */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Integer id, @RequestBody SkillForm form) {
        roleGuard.requireAdmin();
        return Result.ok(skillService.update(id, form));
    }

    /** POST /api/agent-skills/{id}/toggle */
    @PostMapping("/{id}/toggle")
    public Result<Map<String, Object>> toggle(@PathVariable Integer id) {
        roleGuard.requireAdmin();
        return Result.ok(skillService.toggle(id));
    }

    /** DELETE /api/agent-skills/{id} */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Integer id) {
        roleGuard.requireAdmin();
        return Result.ok(skillService.delete(id));
    }
}
