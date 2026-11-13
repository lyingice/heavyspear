package net.minecraft.heavyspear.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;

/**
 * 重矛（Minecraft 26.1.2 / NeoForge）。
 *
 * 直接使用原版长矛组件：.spear(...) 会设置 KINETIC_WEAPON、PIERCING_WEAPON、
 * ATTACK_RANGE、DAMAGE_TYPE、挥击动画、攻击属性等，参数与 1.21.1 的 master 基本一致。
 *
 * 坠落砸地 AOE 由 HeavySpearSmash + 两个 mixin 接管：
 * - ItemStackMixin 接管蓄力侧（26.1.2 的 KINETIC_WEAPON 会绕过 Item#onUseTick）
 * - PiercingWeaponMixin 接管左键戳刺侧
 * 非坠落状态仍完全使用原版长矛组件。
 */
public class HeavySpearItem extends Item {
    public static final TagKey<Item> HEAVY_SPEAR_REPAIR = TagKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath("heavyspear", "heavy_spear_repair"));

    public HeavySpearItem(Properties properties) {
        super(properties
                .spear(ToolMaterial.NETHERITE,
                        1.15F, 1.2F, 0.4F,
                        2.5F, 0.3F,
                        5.5F, 5.1F,
                        8.75F, 4.6F)
                .durability(500)
                .rarity(Rarity.EPIC)
                .fireResistant()
                .repairable(HEAVY_SPEAR_REPAIR));
    }
}
