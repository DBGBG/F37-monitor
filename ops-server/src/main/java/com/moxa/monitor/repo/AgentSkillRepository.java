package com.moxa.monitor.repo;

import com.moxa.monitor.domain.AgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/** 智能体技能仓库 [B] */
public interface AgentSkillRepository extends JpaRepository<AgentSkill, Integer>,
        JpaSpecificationExecutor<AgentSkill> {

    Optional<AgentSkill> findBySkillKey(String skillKey);

    boolean existsBySkillKey(String skillKey);
}
