package com.moxa.monitor.security;

import com.moxa.monitor.domain.SysRole;
import com.moxa.monitor.domain.SysUser;
import com.moxa.monitor.domain.SysUserRole;
import com.moxa.monitor.repo.SysRoleRepository;
import com.moxa.monitor.repo.SysUserRepository;
import com.moxa.monitor.repo.SysUserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 首启动账号初始化 (Flyway 已建表并写入 sys_role 种子, 但 BCrypt 散列不能在 SQL 里硬编码, 故在此生成).
 * 只在 sys_user 为空时执行, 之后由 /api/users 管理接口负责增删改.
 */
@Component
public class UserBootstrapInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapInitializer.class);

    /** username, 明文初始口令, 显示名, 角色 */
    private static final List<String[]> DEFAULT_USERS = List.of(
            new String[]{"admin", "admin123", "系统管理员", AuthUser.ROLE_ADMIN},
            new String[]{"oper", "oper123", "运维工程师", AuthUser.ROLE_OPERATOR},
            new String[]{"viewer", "viewer123", "只读用户", AuthUser.ROLE_VIEWER});

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserBootstrapInitializer(SysUserRepository userRepository, SysRoleRepository roleRepository,
                                    SysUserRoleRepository userRoleRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        for (String[] spec : DEFAULT_USERS) {
            SysUser user = new SysUser();
            user.setUsername(spec[0]);
            user.setPasswordHash(passwordEncoder.encode(spec[1]));
            user.setDisplayName(spec[2]);
            user.setRoleCode(spec[3]);
            user.setEnabled(true);
            userRepository.save(user);
            linkRole(user, spec[3]);
        }
        log.warn("已初始化 {} 个登录账号 (admin/oper/viewer), 初始口令为弱口令, 请登录后立即修改", DEFAULT_USERS.size());
    }

    /** 单个账号补建 (也用于升级后角色表新增的情况) */
    @Transactional
    public void ensureUser(String username, String rawPassword, String displayName, String roleCode) {
        Optional<SysUser> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            linkRole(existing.get(), roleCode);
            return;
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName(displayName);
        user.setRoleCode(roleCode);
        user.setEnabled(true);
        userRepository.save(user);
        linkRole(user, roleCode);
    }

    private void linkRole(SysUser user, String roleCode) {
        roleRepository.findByCode(roleCode).ifPresent(role -> {
            boolean linked = userRoleRepository.findByUserId(user.getId()).stream()
                    .anyMatch(r -> r.getRoleId().equals(role.getId()));
            if (!linked) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(user.getId());
                ur.setRoleId(role.getId());
                userRoleRepository.save(ur);
            }
        });
    }

    /** 供用户管理服务把 role_code 同步到关联表 */
    @Transactional
    public void syncRoles(SysUser user) {
        userRoleRepository.deleteByUserId(user.getId());
        roleRepository.findByCode(user.getRoleCode()).ifPresent(role -> {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(user.getId());
            ur.setRoleId(role.getId());
            userRoleRepository.save(ur);
        });
    }

    /** 登录校验用: 角色权限清单 */
    public List<String> permissionsOf(String roleCode) {
        return roleRepository.findByCode(roleCode).map(SysRole::getPermissionsJson).orElse(List.of());
    }
}
