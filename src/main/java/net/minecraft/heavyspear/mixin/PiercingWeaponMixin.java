package net.minecraft.heavyspear.mixin;

import net.minecraft.heavyspear.item.HeavySpearItem;
import net.minecraft.heavyspear.item.HeavySpearSmash;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.PiercingWeapon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.1.2 的长矛左键戳刺走 PiercingWeapon#attack。
 * 这里在坠落时接管整次戳刺，改为重矛的砸地 AOE。
 */
@Mixin(PiercingWeapon.class)
public abstract class PiercingWeaponMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void heavyspear$smashStab(LivingEntity attacker, EquipmentSlot hand, CallbackInfo ci) {
        if (!(attacker instanceof Player player)) {
            return;
        }
        ItemStack stack = attacker.getItemBySlot(hand);
        if (stack.getItem() instanceof HeavySpearItem
                && HeavySpearSmash.trySmashStab(player, stack)) {
            ci.cancel();
        }
    }
}
