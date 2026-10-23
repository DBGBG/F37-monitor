package com.moxa.monitor.repo;

import com.moxa.monitor.domain.SysConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 业务参数配置仓库 [B] */
public interface SysConfigRepository extends JpaRepository<SysConfig, Integer> {

    Optional<SysConfig> findByConfigKey(String configKey);

    List<SysConfig> findAllByOrderByIdAsc();

    /** config_value 存原始 JSON 文本, 由 SysConfigService 负责序列化/反序列化 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SysConfig c SET c.configValue = :value, c.description = :description WHERE c.configKey = :key")
    int updateValue(@Param("key") String key, @Param("value") String value, @Param("description") String description);
}
