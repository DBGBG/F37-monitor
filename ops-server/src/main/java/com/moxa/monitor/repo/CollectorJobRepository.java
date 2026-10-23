package com.moxa.monitor.repo;

import com.moxa.monitor.domain.CollectorJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 采集任务配置仓库 [B写/C读] */
public interface CollectorJobRepository extends JpaRepository<CollectorJob, Integer> {

    List<CollectorJob> findAllByOrderByIdAsc();

    Optional<CollectorJob> findByJobId(String jobId);

    /** 只改周期/超时/开关, 不回写其它列 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CollectorJob j SET j.intervalSeconds = :interval, j.timeoutSeconds = :timeout, "
            + "j.maxInstances = :maxInstances, j.enabled = :enabled WHERE j.jobId = :jobId")
    int updateSchedule(@Param("jobId") String jobId, @Param("interval") Integer interval,
                        @Param("timeout") Integer timeout, @Param("maxInstances") Integer maxInstances,
                        @Param("enabled") Boolean enabled);
}
