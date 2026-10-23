"""动态基线 - 指数加权移动平均 (EWMA)

为什么用 EWMA:
- 工业设备指标(温度/CPU/误码)具有慢漂移特性, 需要一条随时间平滑跟随的"正常基线"
- 无需保存全部历史, 常数内存增量更新, 天然适应设备随季节/负载的缓慢变化
- 配合方差可得到 z 分数: |x - mean| / std, 用于与固定阈值互补的异常判定
"""

import math
from dataclasses import dataclass


@dataclass
class EWMAState:
    """EWMA 基线状态"""
    mean: float
    variance: float
    count: int

    def std(self, eps: float = 1e-9) -> float:
        return max(math.sqrt(max(self.variance, 0.0)), eps)


def ewma_update(state: EWMAState, x: float, alpha: float) -> EWMAState:
    """用新样本 x 更新 EWMA 均值与方差 (RiskMetrics 形式, 数值稳定)"""
    if state.count <= 0:
        return EWMAState(float(x), 0.0, 1)
    delta = x - state.mean
    mean = state.mean + alpha * delta
    variance = (1 - alpha) * (state.variance + alpha * delta * delta)
    return EWMAState(mean, variance, state.count + 1)


def evaluate(
    metric_cfg: dict,
    state: EWMAState,
    x: float,
    k: float,
    min_samples: int,
) -> tuple[bool, float, float]:
    """判定样本 x 是否偏离基线。

    返回 (是否异常, 绝对偏离, z 分数)。
    需同时满足: 样本数足够 + 方向匹配 + 绝对偏离达标 + z 分数达标。
    三者叠加是为了抑制"小抖动被高灵敏度放大"的误报。
    """
    if state.count < min_samples:
        return False, 0.0, 0.0

    direction = metric_cfg.get("direction", "up")
    dev = x - state.mean
    if direction == "up" and dev <= 0:
        return False, 0.0, 0.0
    if direction == "down" and dev >= 0:
        return False, 0.0, 0.0

    absdev = abs(dev)
    if absdev < float(metric_cfg.get("min_deviation", 0) or 0):
        return False, 0.0, 0.0

    z = absdev / state.std()
    if z < k:
        return False, 0.0, 0.0

    return True, absdev, min(z, 1000.0)
