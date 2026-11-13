# Heavy Spear — Forge 1.20.1

这是 `heavyspear` 的 1.20.1 Forge 分支。

## 特性

- 重矛不再通过合成获得。
- 末地城 / 末地船宝藏箱（`minecraft:chests/end_city_treasure`）约 1% 概率掉落重矛。
- 硬前置：SpearCore 1.20.1（`spearcore`），版本要求 `[3.2.0,)`。
- 保留原重矛的蓄力、戳刺、速度伤害与坠落砸地 AOE 机制。

## 构建

需要 Java 17，并保持同级目录下的 SpearCore 1.20.1 源码工程：

```text
MCreatorWorkspaces/
├─ heavyspear-1.20.1forge/
└─ spearcore-1.20.1/
```

然后：

```bash
./gradlew build
```

产物位于 `build/libs/HeavySpear-forge-1.20.1-1.0.1.jar`。构建只编译、不打包 SpearCore；正式游玩时需单独安装 SpearCore 1.20.1（`libs/spearcore-1.20.1-forge-3.2.1.jar` 可作参考）。

## 开发运行

```bash
./gradlew runClient
```

Gradle 会自动把 SpearCore 的 dev classes/resources 打成 `run/mods/spearcore-1.20.1-forge-3.2.1-dev.jar`，并配置 mixin refmap 重映射，因此开发环境不需要手动安装 SpearCore。`run/` 已被 `.gitignore` 忽略。

## 说明

- 末地船与末地城箱子共用同一战利品表，因此两者都会掉落重矛；若要严格只限末地船，需要额外的结构片段判定。
- 本分支使用代码型 MCreator 工作区（没有物品 mod element），物品逻辑在 Java 源码中维护。
