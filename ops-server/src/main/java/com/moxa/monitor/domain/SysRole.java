package com.moxa.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/** 系统角色 [B]: ADMIN/OPERATOR/VIEWER, 权限清单为 ["*"] 或 ["device:read", ...] */
@Getter
@Setter
@Entity
@Table(name = "sys_role")
public class SysRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions_json", columnDefinition = "json")
    private List<String> permissionsJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
