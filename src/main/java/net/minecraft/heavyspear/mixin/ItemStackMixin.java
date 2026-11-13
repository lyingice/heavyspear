package net.minecraft.heavyspear.mixin;

import net.minecraft.heavyspear.item.HeavySpearItem;
import net.minecraft.heavyspear.item.HeavySpearSmash;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.1.2 中，带 KINETIC_WEAPON 的物品会绕过 Item#onUseTick。
 * 这里在 ItemStack#onUseTick 头部接管重矛的坠落蓄力砸地。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "onUseTick", at = @At("HEAD"), cancellable = true)
    private void heavyspear$smashOnUseTick(Level level, LivingEntity user, int ticksRemaining, CallbackInfo ci) {
        ItemStack stack = (ItemStack) (Object) this;
        if (stack.getItem() instanceof HeavySpearItem
                && HeavySpearSmash.trySmashOnUseTick(level, user, stack, ticksRemaining)) {
            ci.cancel();
        }
    }
}
