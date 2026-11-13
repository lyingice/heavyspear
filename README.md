# Heavy Spear — NeoForge 26.1.2

这是 `heavyspear` 的 Minecraft 26.1.2 / NeoForge 分支。

## 特性

- 直接使用原版长矛组件（`Item.Properties#spear`）实现重矛：蓄力、戳刺、击退、攻击范围等。
- 保留 master（1.21.1）的重矛配方：`minecraft:heavy_core` + 2 个 `minecraft:breeze_rod`。
- 不再依赖 SpearCore；只需要 NeoForge 26.1.2。
- 已通过轻量 mixin 补回 master 的坠落砸地 AOE：坠落状态下蓄力/左键戳击会接管原版长矛攻击，造成向下扩展的 AOE 伤害、击退、反冲与重锤音效。
- 非坠落状态完全走原版长矛组件，不改变普通蓄力/戳刺手感。

## 构建

需要 Java 25。本机 Gradle 配置指向 MCreator 2026.2 自带的 JBR 25：

```properties
org.gradle.java.installations.paths=D:/001/MCR2025.3/MCreator20262/jdk
```

```bash
./gradlew build
```

产物位于 `build/libs/HeavySpear-26.1.2-neoforge-1.0.1.jar`。

## 开发运行

```bash
./gradlew runClient
```

## 坠落砸地机制

26.1.2 有两条与原版长矛相关的路径：

- 蓄力：`ItemStack#onUseTick` 检测到 `KINETIC_WEAPON` 后直接走 `KineticWeapon#damageEntities`，不会调用 `Item#onUseTick`。
- 左键戳击：客户端发送 `STAB`，服务端 `PiercingWeapon#attack` 处理穿刺攻击。

重矛通过两个小 mixin 在“玩家正在坠落”时接管：

- `ItemStackMixin`：接管蓄力侧的 `ItemStack#onUseTick`。
- `PiercingWeaponMixin`：接管左键侧的 `PiercingWeapon#attack`。

共用逻辑在 `HeavySpearSmash` 中，包含：

- 坠落距离缓存（落地后保留 10 tick 宽限）。
- 向下扩展的 AABB 与 AOE 伤害。
- 与 master 相同的速度伤害/坠落加成公式（1.5 格开始，5 格进入 heavy 阶段）。
- AOE 击退、玩家反冲、重锤地面/空中音效、耐久消耗。
- 非坠落时返回 `false`，mixin 不取消原版逻辑。

## 说明

- 物品模型使用 26.1.2 的 `minecraft:select` 物品定义：GUI/掉落物使用普通物品模型，手持使用 `minecraft:item/spear_in_hand`。
- 重矛已加入 `#minecraft:spears` 与相关 `enchantable/*` 标签，Lunge 等长矛附魔可正常生效。
- 本分支使用代码型 MCreator 工作区（没有物品 mod element），物品逻辑在 Java 源码中维护。
