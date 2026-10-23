"""设备健康评分

把多维状态(在线/电源冗余/温度/主动异常/活动告警)综合成一个 0-100 的分数与等级,
让运维人员一眼看出"全场哪些设备最需要先处理", 而不是在几十条告警里翻找。
"""

GRADES = [(90, "优"), (75, "良"), (60, "中"), (0, "差")]


def grade_of(score: int) -> str:
    for threshold, grade in GRADES:
        if score >= threshold:
            return grade
    return "差"


def compute_health(
    device_type: str,
    is_online: bool,
    hw,
    open_anomaly_count: int,
    alert_counts: dict,
    weights: dict,
) -> tuple[int, str, list, str]:
    """返回 (分数, 等级, 扣分因子列表, 摘要)

    hw: 交换机的最新 HardwareHealth 记录 (可空)
    alert_counts: {"CRITICAL": n, "WARNING": m, ...}
    """
    w_online = weights.get("online", 30)
    w_power = weights.get("power", 15)
    w_temp = weights.get("temperature", 15)
    w_anom = weights.get("anomalies", 20)
    w_alert = weights.get("alerts", 20)

    # 离线直接判为最差 (设备不可用是最高优先级问题)
    if not is_online:
        return 0, "差", [{"factor": "在线状态", "deduction": 100, "detail": "设备离线"}], "设备离线"

    score = 100
    factors: list = []

    # 1. 电源冗余
    if device_type == "switch" and hw is not None:
        failed = []
        if getattr(hw, "power1_status", 0) == 2:
            failed.append("电源1")
        if getattr(hw, "power2_status", 0) == 2:
            failed.append("电源2")
        if failed:
            ded = w_power if len(failed) >= 2 else round(w_power * 0.6)
            score -= ded
            factors.append({"factor": "电源", "deduction": ded, "detail": "、".join(failed) + "故障"})

    # 2. 温度
    if device_type == "switch" and hw is not None:
        temp = float(getattr(hw, "temperature", 0) or 0)
        temp_alarm = int(getattr(hw, "temperature_alarm", 0) or 0)
        if temp_alarm == 1:
            score -= w_temp
            factors.append({"factor": "温度", "deduction": w_temp, "detail": f"温度告警 ({temp:.0f}°C)"})
        elif temp >= 60:
            ded = round(w_temp * 0.7)
            score -= ded
            factors.append({"factor": "温度", "deduction": ded, "detail": f"温度偏高 {temp:.0f}°C"})

    # 3. 未处理主动异常
    if open_anomaly_count > 0:
        ded = min(w_anom, open_anomaly_count * 5)
        score -= ded
        factors.append({
            "factor": "异常", "deduction": ded,
            "detail": f"{open_anomaly_count} 项主动异常待处理",
        })

    # 4. 活动告警
    crit = int(alert_counts.get("CRITICAL", 0))
    warn = int(alert_counts.get("WARNING", 0))
    aded = min(w_alert, crit * 10 + warn * 5)
    if aded > 0:
        score -= aded
        factors.append({
            "factor": "告警", "deduction": aded,
            "detail": f"严重告警 {crit} 条, 警告 {warn} 条",
        })

    score = max(0, min(100, round(score)))
    grade = grade_of(score)

    if factors:
        summary = "、".join(f["detail"] for f in factors)
    else:
        summary = "运行正常"

    return score, grade, factors, summary
