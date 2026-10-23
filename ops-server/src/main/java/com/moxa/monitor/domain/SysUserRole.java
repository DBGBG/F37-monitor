package com.moxa.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/** 用户-角色关联 [B]: 与 sys_user.role_code 同步维护, 便于后续扩展多角色 */
@Getter
@Setter
@Entity
@Table(name = "sys_user_role")
@IdClass(SysUserRole.Pk.class)
public class SysUserRole {

    @Id
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Id
    @Column(name = "role_id", nullable = false)
    private Integer roleId;

    /** 联合主键类, 字段名与实体一致 */
    @Getter
    @Setter
    public static class Pk implements Serializable {
        private Integer userId;
        private Integer roleId;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk other)) {
                return false;
            }
            return Objects.equals(userId, other.userId) && Objects.equals(roleId, other.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId);
        }
    }
}
