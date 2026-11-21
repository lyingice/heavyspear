# HeavySpear+（pyxyd fork）

Fork of [lyingice/heavyspear](https://github.com/lyingice/heavyspear)，在 **Minecraft 1.21.1 / NeoForge** 上做了本地玩法与 HUD 增强。

## 改动概览

- **突进支持 Y 轴**：「突进」附魔触发时按视线方向推进，不再只有水平分量。
- **滑翔突进**：滑翔中也能触发突进；滑翔时由本模组补上水平/竖直推进和消耗。
- **按仰角衰减速度**：3 级时速度为 `1.95 - 仰角 / 150`（平视 1.95，垂直 1.35），其他等级按等级等比缩放。
- **向上戳刺耐久倍率**：`max(1, floor(仰角 / 25))` 倍，作用于这次攻击原本消耗的耐久。
- **向下保持原本突进**：向下戳刺只推水平（Y=0），不做仰角减速/向上耐久倍率。
- **鞘翅加速 HUD**：滑翔中检测速度增量（突进/烟花/其它飞行模组），右上角显示倒计时；`Right Shift` 拖动调整位置，`Right Shift + Right Ctrl` 重置到右上角。
- **中英文本地化**：`assets/heavyspear/lang/zh_cn.json` / `en_us.json`。

## 构建

仓库保留上游 Gradle / MCreator 工程结构，依赖 `libs/` 下的 jar：

```bash
./gradlew build
```

- Java 21
- NeoForge 21.1.217
- 编译期依赖 `libs/spearcore-*.jar`（Spear Core）

## HUD 配置

首次运行会生成 `config/heavyspear-hud.properties`：

```properties
enabled=true
showSpeed=false
offsetX=4
offsetY=4
windowTicks=40
accelThreshold=0.5
```

- `offsetX` / `offsetY`：距屏幕右/上边缘的 GUI 像素；拖动 HUD 会自动写入。
- `windowTicks`：检测到加速后的显示窗口（40 = 2 秒）。
- `accelThreshold`：每 tick 速度增量阈值；越低越灵敏。

## 说明

- 本 fork 的 `HeavySpearItem.java` 基于上游源码修改，已在 Spear Core 2.0.4 与 3.2.1 下编译验证。
- HUD 为客户端功能；服务端与客户端需安装同一 jar。
