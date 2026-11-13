# Heavy Spear — NeoForge 26.1.2

这是 `heavyspear` 的 Minecraft 26.1.2 / NeoForge 分支。

## 特性

- 直接使用原版长矛组件（`Item.Properties#spear`）实现重矛：蓄力、戳刺、击退、攻击范围等。
- 保留 master（1.21.1）的重矛配方：`minecraft:heavy_core` + 2 个 `minecraft:breeze_rod`。
- 不再依赖 SpearCore；只需要 NeoForge 26.1.2。
- 默认不包含 master 的坠落砸地 AOE（26.1.2 的 `KINETIC_WEAPON` 会绕过 `Item#onUseTick`，需要额外 mixin/组件才能实现）。

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

## 说明

- 物品模型使用 26.1.2 的 `minecraft:select` 物品定义：GUI/掉落物使用普通物品模型，手持使用 `minecraft:item/spear_in_hand`。
- 重矛已加入 `#minecraft:spears` 与相关 `enchantable/*` 标签，Lunge 等长矛附魔可正常生效。
- 本分支使用代码型 MCreator 工作区（没有物品 mod element），物品逻辑在 Java 源码中维护。
