package net.minecraft.heavyspear.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * 重矛在 26.1.2 的坠落砸地机制。
 *
 * 26.1.2 的物品带 KINETIC_WEAPON 时会由 ItemStack.onUseTick 直接走原版蓄力逻辑，
 * 不再调用 Item#onUseTick；同时长矛左键走 PiercingWeapon.attack。
 * 因此这里通过两个很小的 mixin 在“坠落”状态下接管这两条攻击路径，
 * 非坠落状态完全交还原版长矛组件。
 */
public final class HeavySpearSmash {

    private static final float SMASH_ATTACK_FALL_THRESHOLD = 1.5F;
    private static final float SMASH_ATTACK_HEAVY_THRESHOLD = 5.0F;
    private static final float SMASH_ATTACK_KNOCKBACK_RADIUS = 3.5F;
    private static final float SMASH_ATTACK_KNOCKBACK_POWER = 0.7F;

    private static final String FALL_DISTANCE_KEY = "heavyspear_fall_distance";
    private static final String FALL_DISTANCE_TIME_KEY = "heavyspear_fall_distance_time";
    private static final long FALL_DISTANCE_GRACE_TICKS = 10;
    private static final int CONTACT_COOLDOWN_TICKS = 10;

    private HeavySpearSmash() {
    }

    /**
     * 蓄力路径：由 ItemStackMixin 在 ItemStack#onUseTick 头部调用。
     * 返回 true 表示已接管本 tick，mixin 会取消原版 KINETIC_WEAPON 处理。
     */
    public static boolean trySmashOnUseTick(Level level, LivingEntity user, ItemStack stack, int remainingTicks) {
        if (!(level instanceof ServerLevel serverLevel) || !(user instanceof Player player)) {
            return false;
        }

        cacheFallDistance(player);
        float fallDistance = getCachedFallDistance(player);
        if (!canSmash(player, fallDistance)) {
            return false;
        }

        if (player.getCooldowns().isOnCooldown(stack)) {
            player.stopUsingItem();
            return true;
        }

        KineticWeapon kinetic = stack.get(DataComponents.KINETIC_WEAPON);
        int damageEndTick = kinetic != null ? kinetic.computeDamageUseDuration() : 183;
        int usedTicks = stack.getUseDuration(user) - remainingTicks;
        if (usedTicks >= damageEndTick) {
            player.stopUsingItem();
            return true;
        }

        AttackRange attackRange = user.getAttackRangeWith(stack);
        AABB aabb = user.getBoundingBox()
                .inflate(attackRange.maxReach(), 0.0D, attackRange.maxReach())
                .expandTowards(0.0D, -Math.max(0.0, fallDistance - 1.0F), 0.0D);
        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class, aabb, smashPredicate(user));

        Vec3 look = user.getLookAngle();
        double attackerSpeed = look.dot(KineticWeapon.getMotion(user));
        float baseDamage = (float) user.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        float damageMultiplier = kinetic != null ? kinetic.damageMultiplier() : 1.2F;
        DamageSource damageSource = level.damageSources().source(DamageTypes.SPEAR, user);
        boolean hitSomething = false;

        for (LivingEntity target : targets) {
            double targetSpeed = look.dot(KineticWeapon.getMotion(target));
            double relativeSpeed = Math.max(0.0, attackerSpeed - targetSpeed);
            float damage = calculateTotalDamage(baseDamage, relativeSpeed, fallDistance, damageMultiplier);

            if (target.hurtServer(serverLevel, damageSource, damage)) {
                hitSomething = true;
                stack.hurtAndBreak(1, user, EquipmentSlot.MAINHAND);
                player.setLastHurtMob(target);
            }
        }

        if (hitSomething) {
            level.broadcastEntityEvent(user, (byte) 2);

            SoundEvent sound = fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD
                    ? SoundEvents.MACE_SMASH_GROUND_HEAVY
                    : SoundEvents.MACE_SMASH_GROUND;
            level.playSound(null, user.getX(), user.getY(), user.getZ(),
                    sound, SoundSource.PLAYERS, 1.0F, 1.0F);

            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.setSpawnExtraParticlesOnFall(true);
            }

            smashKnockback(level, player, targets, fallDistance);

            player.fallDistance = 0.0F;
            clearFallDistance(player);

            player.setDeltaMovement(player.getDeltaMovement().add(0.0, 0.6F, 0.0));
            if (player instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.connection != null) {
                    serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
                }
                serverPlayer.hurtMarked = true;
            }
        }

        return true;
    }

    /**
     * 左键戳刺路径：由 PiercingWeaponMixin 在 PiercingWeapon#attack 头部调用。
     * 返回 true 表示已接管本次戳刺，mixin 会取消原版穿刺攻击。
     */
    public static boolean trySmashStab(Player player, ItemStack stack) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        cacheFallDistance(player);
        float fallDistance = getCachedFallDistance(player);
        if (!canSmash(player, fallDistance)) {
            return false;
        }

        AttackRange attackRange = player.getAttackRangeWith(stack);
        AABB aabb = player.getBoundingBox()
                .inflate(attackRange.maxReach(), 0.0D, attackRange.maxReach())
                .expandTowards(0.0D, -Math.max(0.0, fallDistance - 1.0F), 0.0D);
        List<LivingEntity> targets = player.level().getEntitiesOfClass(
                LivingEntity.class, aabb, smashPredicate(player));

        Vec3 look = player.getLookAngle();
        double attackerSpeed = Math.max(0.0, look.dot(KineticWeapon.getMotion(player)));
        float baseDamage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        KineticWeapon kinetic = stack.get(DataComponents.KINETIC_WEAPON);
        float damageMultiplier = kinetic != null ? kinetic.damageMultiplier() : 1.2F;
        DamageSource damageSource = player.level().damageSources().source(DamageTypes.SPEAR, player);
        boolean hitSomething = false;

        for (LivingEntity target : targets) {
            if (player.wasRecentlyStabbed(target, CONTACT_COOLDOWN_TICKS)) {
                continue;
            }

            double targetSpeed = look.dot(KineticWeapon.getMotion(target));
            double relativeSpeed = Math.max(0.0, attackerSpeed - targetSpeed);
            float damage = calculateTotalDamage(baseDamage, relativeSpeed, fallDistance, damageMultiplier);

            if (target.hurtServer(serverLevel, damageSource, damage)) {
                hitSomething = true;
                stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                player.setLastHurtMob(target);
                player.fallDistance = 0.0F;
                clearFallDistance(player);
                player.rememberStabbedEntity(target);
            }
        }

        if (hitSomething) {
            SoundEvent sound = fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD
                    ? SoundEvents.MACE_SMASH_GROUND_HEAVY
                    : SoundEvents.MACE_SMASH_GROUND;
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    sound, SoundSource.PLAYERS, 1.0F, 1.0F);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.MACE_SMASH_AIR, SoundSource.PLAYERS, 1.0F, 1.0F);

            smashKnockback(player.level(), player, targets, fallDistance);

            player.setDeltaMovement(player.getDeltaMovement().add(0.0, 0.6F, 0.0));
            if (player instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.connection != null) {
                    serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
                }
                serverPlayer.hurtMarked = true;
            }
        }

        // 原版 PiercingWeapon#attack 在命中后会做这些动作；这里接管时手动补上。
        player.onAttack();
        player.postPiercingAttack();
        player.swing(InteractionHand.MAIN_HAND, false);
        return true;
    }

    private static float calculateTotalDamage(float baseDamage, double relativeSpeed,
                                              float fallDistance, float damageMultiplier) {
        float spearDamage = baseDamage + (float) relativeSpeed * damageMultiplier;

        float smashBonus = 0.0F;
        if (fallDistance > SMASH_ATTACK_FALL_THRESHOLD) {
            smashBonus = (fallDistance - SMASH_ATTACK_FALL_THRESHOLD) * 3.0F;
            if (fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD) {
                smashBonus += (fallDistance - SMASH_ATTACK_HEAVY_THRESHOLD) * 2.0F;
            }
        }

        return spearDamage + smashBonus;
    }

    private static void smashKnockback(Level level, Player player, List<LivingEntity> hitTargets, float fallDistance) {
        for (LivingEntity target : hitTargets) {
            level.levelEvent(2013, target.getOnPos(), 750);
        }

        level.getEntitiesOfClass(LivingEntity.class,
                        player.getBoundingBox().inflate(SMASH_ATTACK_KNOCKBACK_RADIUS),
                        knockbackPredicate(player, player))
                .forEach(target -> {
                    Vec3 distance = target.position().subtract(player.position());
                    double power = getKnockbackPower(player, target, distance, fallDistance);
                    Vec3 velocity = distance.normalize().scale(power);
                    if (power > 0.0F) {
                        target.push(velocity.x, 0.7F, velocity.z);
                        if (target instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
                            serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
                        }
                    }
                });
    }

    private static double getKnockbackPower(Player player, LivingEntity target,
                                            Vec3 distance, float fallDistance) {
        return (SMASH_ATTACK_KNOCKBACK_RADIUS - distance.length())
                * SMASH_ATTACK_KNOCKBACK_POWER
                * (fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD ? 2 : 1)
                * (1.0F - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
    }

    private static Predicate<LivingEntity> smashPredicate(LivingEntity user) {
        return target -> {
            if (target == user) return false;
            if (target.isSpectator()) return false;
            if (!target.isAlive()) return false;
            if (user instanceof Player player) {
                if (player.isAlliedTo(target)) return false;
                if (target instanceof TamableAnimal tameable && tameable.isTame()
                        && tameable.getOwner() == player) return false;
                if (target instanceof ArmorStand armorStand && armorStand.isMarker()) return false;
                if (target instanceof Player otherPlayer && !player.canHarmPlayer(otherPlayer)) return false;
            }
            return true;
        };
    }

    private static Predicate<LivingEntity> knockbackPredicate(Player player, Entity center) {
        return target -> {
            if (target == player || target == center) return false;
            if (target.isSpectator()) return false;
            if (player.isAlliedTo(target)) return false;
            if (target instanceof TamableAnimal tameable && tameable.isTame()
                    && tameable.getOwner() == player) return false;
            if (target instanceof ArmorStand armorStand && armorStand.isMarker()) return false;
            return center.distanceToSqr(target) <= SMASH_ATTACK_KNOCKBACK_RADIUS * SMASH_ATTACK_KNOCKBACK_RADIUS;
        };
    }

    private static void cacheFallDistance(Player player) {
        if (player.fallDistance > SMASH_ATTACK_FALL_THRESHOLD) {
            CompoundTag tag = player.getPersistentData();
            tag.putFloat(FALL_DISTANCE_KEY, (float) player.fallDistance);
            tag.putLong(FALL_DISTANCE_TIME_KEY, player.level().getGameTime());
        }
    }

    private static float getCachedFallDistance(Player player) {
        CompoundTag tag = player.getPersistentData();
        if (!tag.contains(FALL_DISTANCE_TIME_KEY)) {
            return 0.0F;
        }
        long captureTime = tag.getLong(FALL_DISTANCE_TIME_KEY).orElse(0L);
        if (player.level().getGameTime() - captureTime > FALL_DISTANCE_GRACE_TICKS) {
            return 0.0F;
        }
        return tag.getFloat(FALL_DISTANCE_KEY).orElse(0.0F);
    }

    private static void clearFallDistance(Player player) {
        CompoundTag tag = player.getPersistentData();
        tag.remove(FALL_DISTANCE_KEY);
        tag.remove(FALL_DISTANCE_TIME_KEY);
    }

    private static boolean canSmash(Player player, float fallDistance) {
        return fallDistance > SMASH_ATTACK_FALL_THRESHOLD && !player.isFallFlying();
    }
}
