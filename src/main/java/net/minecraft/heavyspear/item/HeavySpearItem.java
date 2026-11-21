package net.minecraft.heavyspear.item;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.spearcore.util.SpearCooldownAccessor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.MaceItem;
import net.minecraft.spearcore.item.SpearItem;
import net.minecraft.spearcore.util.SpearCondition;
import net.minecraft.spearcore.init.SpearSounds;
import net.minecraft.spearcore.init.SpearDamageTypes;
/**
 * 重矛 - 融合重锤坠落机制的矛
 *
 * 机制说明：
 * 1. 伤害 = 矛基础伤害 + 速度加成 + 坠落伤害（重锤公式）
 * 2. 攻击范围：fallDistance < 1.5 用矛的射线范围，>= 1.5 用重锤的AOE范围
 * 3. 触发方式：主动蓄力 + 被动坠落（两者结合）
 * 4. 命中后免疫坠落伤害（继承重锤）
 * 5. 修复：fallDistance 落地即失效 → 缓存 + 宽限期，AABB 向下扩展覆盖脚下目标
 */
public class HeavySpearItem extends SpearItem {

    // ========== 重锤相关常量 ==========
    private static final float SMASH_ATTACK_FALL_THRESHOLD = 1.5F;
    private static final float SMASH_ATTACK_HEAVY_THRESHOLD = 5.0F;
    public static final float SMASH_ATTACK_KNOCKBACK_RADIUS = 3.5F;
    private static final float SMASH_ATTACK_KNOCKBACK_POWER = 0.7F;

    // ========== 坠落距离缓存 ==========
    private static final String FALL_DISTANCE_KEY = "heavyspear_fall_distance";
    private static final String FALL_DISTANCE_TIME_KEY = "heavyspear_fall_distance_time";
    /** 落地后缓存仍然有效的宽限期（tick），约 0.5 秒 */
    private static final long FALL_DISTANCE_GRACE_TICKS = 10;

    // ========== 突进（lunge）增强 ==========
    /** Spear Core 的“突进”附魔；等级从物品上读取。 */
    private static final ResourceKey<Enchantment> LUNGE_ENCHANTMENT = ResourceKey.create(
            Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath("spearcore", "lunge"));
    /** 与 SpearItem#rushAttack 保持一致：每级 0.65 格/刻的水平推进。 */
    private static final double LUNGE_STRENGTH_PER_LEVEL = 0.65D;
    /**
     * 仰角速度衰减：3 级时严格为 {@code 1.95 - 仰角 / 150}，即每度降低 1/150 格/刻；
     * 其他等级按等级等比例缩放，避免 1 级在 90° 时速度直接归零。
     */
    private static final double LUNGE_ELEVATION_PENALTY = 1.0D / 150.0D;
    /** 计算出的位移量太小时不发送速度包。 */
    private static final double MIN_LUNGE_DELTA = 1.0E-4D;
    /** 向上戳刺每 25° 仰角让耐久消耗增加一倍；低于 25° 仍为 1 倍。 */
    private static final double DURABILITY_ELEVATION_STEP = 25.0D;

    public HeavySpearItem(Properties properties) {
        super(properties);
    }

    // ========== 矛基础属性（使用下界合金长矛同款）==========

    @Override
    public float getAttackDuration() {
        return 1.15F;
    }

    @Override
    public float getDamageMultiplier() {
        return 1.2F;
    }

    @Override
    public int getDelayTicks() {
        return 8;
    }

    @Override
    public int getDamageEndTick() {
        return getDelayTicks() + getDamageConditions()
                .map(SpearCondition::maxDurationTicks)
                .orElse(175);
    }

    @Override
    public int getDismountEndTick() {
        return getDelayTicks() + getDismountConditions()
                .map(SpearCondition::maxDurationTicks)
                .orElse(50);
    }

    @Override
    public int getKnockbackEndTick() {
        return getDelayTicks() + getKnockbackConditions()
                .map(SpearCondition::maxDurationTicks)
                .orElse(110);
    }

    // ========== 攻击范围（动态切换）==========

    @Override
    public float getMinRange() {
        return 2.0F;
    }

    @Override
    public float getMaxRange() {
        return 4.5F;
    }

    @Override
    public float getMinCreativeRange() {
        return 2.0F;
    }

    @Override
    public float getMaxCreativeRange() {
        return 6.5F;
    }

    @Override
    public float getHitboxMargin() {
        return 0.25F;
    }

    @Override
    public float getHitboxMargin2() {
        return 0.125F;
    }

    @Override
    public int getContactCooldownTicks() {
        return 10;
    }

    // ========== 移动相关 ==========

    @Override
    public float getForwardMovement() {
        return 0.38F;
    }

    @Override
    public float getSwingTimes() {
        return 1.15F;
    }

    @Override
    public float getMobFactor() {
        return 0.5F;
    }

    // ========== 效果开关 ==========

    @Override
    public boolean dealsKnockback() {
        return true;
    }

    @Override
    public boolean dismounts() {
        return false;
    }

    // ========== 音效 ==========

    @Override
    public SoundEvent getUseSound() {
        return SpearSounds.ITEM_SPEAR_USE.get();
    }

    @Override
    public SoundEvent getHitSound() {
        return SpearSounds.ITEM_SPEAR_HIT.get();
    }

    @Override
    public SoundEvent getAttackSound() {
        return SpearSounds.ITEM_SPEAR_ATTACK.get();
    }

    // ========== 条件判断（使用下界合金长矛同款）==========

    @Override
    public Optional<SpearCondition> getDismountConditions() {
        return SpearCondition.ofAttackerSpeed(50, 0.3F);
    }

    @Override
    public Optional<SpearCondition> getKnockbackConditions() {
        return SpearCondition.ofAttackerSpeed(110, 5.1F);
    }

    @Override
    public Optional<SpearCondition> getDamageConditions() {
        return SpearCondition.ofRelativeSpeed(175, 4.6F);
    }

    // ========== 坠落距离缓存 ==========

    /** 坠落中每 tick 刷新缓存（仅在超过阈值时写入） */
    private void cacheFallDistance(Player player) {
        if (player.fallDistance > SMASH_ATTACK_FALL_THRESHOLD) {
            player.getPersistentData().putFloat(FALL_DISTANCE_KEY, player.fallDistance);
            player.getPersistentData().putLong(FALL_DISTANCE_TIME_KEY, player.level().getGameTime());
        }
    }

    /** 读取缓存，超过宽限期视为失效 */
    private float getCachedFallDistance(Player player) {
        CompoundTag tag = player.getPersistentData();
        if (!tag.contains(FALL_DISTANCE_TIME_KEY)) return 0.0F;
        long captureTime = tag.getLong(FALL_DISTANCE_TIME_KEY);
        if (player.level().getGameTime() - captureTime > FALL_DISTANCE_GRACE_TICKS) return 0.0F;
        return tag.getFloat(FALL_DISTANCE_KEY);
    }

    private void clearFallDistance(Player player) {
        player.getPersistentData().remove(FALL_DISTANCE_KEY);
        player.getPersistentData().remove(FALL_DISTANCE_TIME_KEY);
    }

    /** 是否满足砸地条件（缓存距离 + 非鞘翅飞行） */
    private boolean canSmash(Player player, float fallDistance) {
        return player != null && fallDistance > SMASH_ATTACK_FALL_THRESHOLD && !player.isFallFlying();
    }

    /**
     * 让 Spear Core 的“突进”附魔支持 Y 轴、允许滑翔触发，并按仰角降低总速度。
     *
     * <p>父类的 rushAttack 只推进水平分量（Y 恒为 0），而且 {@code isFallFlying()}
     * 为 true 时会直接跳过整个突进。这里分两种情况补齐：</p>
     * <ul>
     *   <li>普通状态：父类已推进 {@code baseSpeed * (look.x, 0, look.z)}，
     *       这里补足到目标速度向量 {@code speed * look}；</li>
     *   <li>滑翔状态：父类不会突进，这里直接推入完整的目标速度向量，并自行结算耐久、
     *       饥饿值和突进音效。</li>
     * </ul>
     *
     * <p>目标速度按仰角衰减：3 级时严格为 {@code 1.95 - 仰角 / 150}
     * （平视 1.95，垂直 1.35）；其他等级按等级等比例缩放。</p>
     *
     * <p>向下按“原本的突进”：只推水平（Y=0），不做仰角减速、不做向上耐久倍率。</p>
     */
    private void applyLungeExtension(LivingEntity user) {
        if (user.level().isClientSide) {
            return;
        }

        if (user.isPassenger() || user.isInWater()) {
            return;
        }

        ItemStack mainHand = user.getMainHandItem();
        if (!(mainHand.getItem() instanceof SpearItem)) {
            return;
        }

        int lungeLevel = spear$enchantLevel(user.level(), mainHand, LUNGE_ENCHANTMENT);
        if (lungeLevel <= 0) {
            return;
        }

        if (user instanceof Player player && player.getFoodData().getFoodLevel() <= 7) {
            return;
        }

        Vec3 look = user.getLookAngle();
        double baseSpeed = LUNGE_STRENGTH_PER_LEVEL * (double) lungeLevel;
        if (look.y < 0.0D) {
            // 向下：非滑翔时由父类正常结算；滑翔时父类跳过，这里手动补同样的水平推进和消耗。
            if (user.isFallFlying()) {
                if (mainHand.isDamageableItem() && user instanceof Player) {
                    mainHand.hurtAndBreak(1, user, EquipmentSlot.MAINHAND);
                }
                if (user instanceof Player player) {
                    player.causeFoodExhaustion(4.0F * (float) lungeLevel);
                }
                user.push(look.x * baseSpeed, 0.0D, look.z * baseSpeed);
                playLungeSound(user, lungeLevel);
                sendAttackerMotion(user);
            }
            return;
        }

        double speed = lungeSpeed(look.y, baseSpeed, lungeLevel);

        if (user.isFallFlying()) {
            // 滑翔时父类会跳过突进：直接推入完整的目标速度向量，并补上耐久、饥饿和音效。
            if (mainHand.isDamageableItem() && user instanceof Player) {
                mainHand.hurtAndBreak(upwardDurabilityMultiplier(look.y), user, EquipmentSlot.MAINHAND);
            }
            if (user instanceof Player player) {
                player.causeFoodExhaustion(4.0F * (float) lungeLevel);
            }
            user.push(look.x * speed, look.y * speed, look.z * speed);
            playLungeSound(user, lungeLevel);
            sendAttackerMotion(user);
            return;
        }

        // 普通状态：父类已经推进了 baseSpeed * (look.x, 0, look.z)。
        // 这里补差值，使最终速度向量等于 speed * look；抬头时水平略微回收、竖直补上。
        double dx = (speed - baseSpeed) * look.x;
        double dz = (speed - baseSpeed) * look.z;
        double dy = speed * look.y;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < MIN_LUNGE_DELTA) {
            return;
        }
        user.push(dx, dy, dz);
        sendAttackerMotion(user);
    }

    /**
     * 按仰角计算本次突进的总速度。
     *
     * <p>3 级时严格为 {@code 1.95 - 仰角 / 150}；其他等级按等级等比例缩放：
     * {@code speed = baseSpeed - 仰角 * (baseSpeed / 1.95) / 150}。
     * 仰角取视线向上与水平面的夹角（0~90°）；向下走原本的纯水平突进，不调用此方法。</p>
     */
    private static double lungeSpeed(double lookY, double baseSpeed, int lungeLevel) {
        double elevation = Math.toDegrees(Math.asin(Math.min(1.0D, lookY)));
        double penalty = LUNGE_ELEVATION_PENALTY * ((double) lungeLevel / 3.0D);
        return Math.max(0.0D, baseSpeed - elevation * penalty);
    }

    /**
     * 向上戳刺的耐久倍率：{@code max(1, floor(仰角 / 25))}。
     *
     * <p>0~24° → 1，25~49° → 1，50~74° → 2，75~90° → 3；
     * 平视和向下固定为 1。</p>
     */
    private static int upwardDurabilityMultiplier(double lookY) {
        if (lookY <= 0.0D) {
            return 1;
        }
        double elevation = Math.toDegrees(Math.asin(Math.min(1.0D, lookY)));
        return Math.max(1, (int) (elevation / DURABILITY_ELEVATION_STEP));
    }

    /**
     * 把这次戳刺原本消耗的耐久放大到 {@link #upwardDurabilityMultiplier(double)} 倍。
     *
     * <p>父类已经在命中/突进时扣过 1 倍耐久，这里只补
     * {@code 原本消耗 × (倍率 - 1)}。倍率对“命中耐久 + 突进耐久”一起生效。</p>
     */
    private void applyElevationDurabilityCost(LivingEntity user, ItemStack stack, EquipmentSlot slot,
                                              int damageBefore, int damageAfterBase) {
        int baseCost = damageAfterBase - damageBefore;
        if (baseCost <= 0) {
            return;
        }
        int multiplier = upwardDurabilityMultiplier(user.getLookAngle().y);
        if (multiplier <= 1) {
            return;
        }
        stack.hurtAndBreak(baseCost * (multiplier - 1), user, slot);
    }

    /** 与父类 jerotesLungeForwardMaybe 相同的突进音效选择。 */
    private static void playLungeSound(LivingEntity user, int lungeLevel) {
        SoundEvent sound = SpearSounds.ITEM_SPEAR_LUNGE_1.get();
        if (lungeLevel > 1) {
            sound = SpearSounds.ITEM_SPEAR_LUNGE_2.get();
        }
        if (lungeLevel > 2) {
            sound = SpearSounds.ITEM_SPEAR_LUNGE_3.get();
        }
        user.level().playSound(null, user.getX(), user.getY(), user.getZ(),
                sound, user.getSoundSource(), 1.0F, 1.0F);
    }

    /** 突进由服务端结算：把新速度同步给攻击者，避免客户端按旧速度预测并被拉回。 */
    private static void sendAttackerMotion(LivingEntity user) {
        if (user instanceof ServerPlayer serverPlayer && serverPlayer.connection != null) {
            serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
        }
    }

    // ========== 核心：融合重锤机制 ==========

    /**
     * 覆写 onUseTick：蓄力坠落时切换为AOE + 坠落伤害
     * 修复点：
     * 1. 坠落中先刷新缓存，落地后宽限期内仍可触发砸地
     * 2. 坠落时跳过前摇 delayTicks（否则短距离坠落被前摇吃掉）
     * 3. AABB 向下扩展，覆盖玩家脚下目标
     */
    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remainingTicks) {
        if (level.isClientSide) return;

        if (user instanceof Player player && player.getCooldowns().isOnCooldown(this)) {
            user.stopUsingItem();
            return;
        }

        // 坠落中刷新缓存
        if (user instanceof Player p) {
            cacheFallDistance(p);
        }

        Player player = user instanceof Player p ? p : null;
        float fallDistance = player != null ? getCachedFallDistance(player) : 0.0F;

        // 非坠落 → 走父类普通矛蓄力逻辑（含 delayTicks 前摇）
        if (!canSmash(player, fallDistance)) {
            super.onUseTick(level, user, stack, remainingTicks);
            return;
        }

        // ===== 重锤蓄力AOE（坠落中：跳过前摇）=====
        int usedTicks = stack.getUseDuration(user) - remainingTicks;
        if (usedTicks >= getDamageEndTick()) {
            user.stopUsingItem();
            return;
        }

        Vec3 look = user.getLookAngle();
        double attackerSpeed = look.dot(getMotion(user));
        double baseDamage = user.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) != null
                ? user.getAttributeBaseValue(
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) : 0.0;

        // AABB 向下扩展覆盖脚下目标
        AABB aabb = user.getBoundingBox()
                .inflate(getMaxRange(), 0.0D, getMaxRange())
                .expandTowards(0.0D, -Math.max(0.0, fallDistance - 1.0F), 0.0D);
        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class, aabb, smashPredicate(user));

        boolean hitSomething = false;

        for (LivingEntity target : targets) {
            double targetSpeed = look.dot(getMotion(target));
            double relSpeed = Math.max(0.0, attackerSpeed - targetSpeed);
            float damage = calculateTotalDamage((float) baseDamage, relSpeed, fallDistance);
            damage += spear$densityBonus(level, stack, fallDistance);
            damage = spear$applyBreach(level, stack, target, damage);

            if (target.hurt(SpearDamageTypes.spear(level, user), damage)) {
                hitSomething = true;
                stack.hurtAndBreak(1, user, EquipmentSlot.MAINHAND);
                player.setLastHurtMob(target);
            }
        }

        if (hitSomething) {
            // 一次蓄力只允许一次砸地：停用物品 + 上冷却，避免继续下落时对同一目标重复结算
            player.stopUsingItem();
            level.broadcastEntityEvent(user, (byte) 2);

            SoundEvent sound = fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD
                    ? SoundEvents.MACE_SMASH_GROUND_HEAVY
                    : SoundEvents.MACE_SMASH_GROUND;
            level.playSound(null, user.getX(), user.getY(), user.getZ(),
                    sound, SoundSource.PLAYERS, 1.0F, 1.0F);

            if (user instanceof ServerPlayer sp) {
                sp.setSpawnExtraParticlesOnFall(true);
            }

            smashKnockback(level, player, targets, fallDistance);

            // 命中后免疫坠落伤害 + 清除缓存
            player.fallDistance = 0.0F;
            clearFallDistance(player);

            // 加上玩家反冲力
            double windBurst = spear$windBurstLift(level, stack);
            spear$windBurstFx(level, player, windBurst);
            player.setDeltaMovement(player.getDeltaMovement()
                    .add(0.0, windBurst > 0 ? windBurst : 0.6, 0.0));
            if (player instanceof ServerPlayer sp) {
                sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
                sp.hurtMarked = true;
            }
        }
    }

    /**
     * 覆写戳击：坠落时使用砸地AOE
     */
    @Override
    public boolean performStabAttack(LivingEntity attacker, ItemStack stack, EquipmentSlot slot, boolean playSound) {
        if (!(attacker instanceof Player player)) {
            boolean ready = !attacker.level().isClientSide && !stack.isEmpty() && stack.getItem() == this;
            int damageBefore = stack.getDamageValue();
            boolean hitSomething = super.performStabAttack(attacker, stack, slot, playSound);
            int damageAfterBase = stack.getDamageValue();
            if (ready) {
                applyLungeExtension(attacker);
                applyElevationDurabilityCost(attacker, stack, slot, damageBefore, damageAfterBase);
            }
            return hitSomething;
        }

        // 刷新缓存（若还在坠落中会覆盖为最新值；已落地则保留宽限期内的缓存）
        cacheFallDistance(player);
        float fallDistance = getCachedFallDistance(player);

        if (!canSmash(player, fallDistance)) {
            boolean ready = !attacker.level().isClientSide
                    && !stack.isEmpty()
                    && stack.getItem() == this
                    && SpearItem.canPlayerPerformStab(player, this);
            int damageBefore = stack.getDamageValue();
            boolean hitSomething = super.performStabAttack(attacker, stack, slot, playSound);
            int damageAfterBase = stack.getDamageValue();
            if (ready) {
                applyLungeExtension(attacker);
                applyElevationDurabilityCost(attacker, stack, slot, damageBefore, damageAfterBase);
            }
            return hitSomething;
        }

        Level level = attacker.level();
        float baseDamage = (float) player.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);

        // AABB 向下扩展
        AABB aabb = attacker.getBoundingBox()
                .inflate(getMaxRange(), 0.0D, getMaxRange())
                .expandTowards(0.0D, -Math.max(0.0, fallDistance - 1.0F), 0.0D);
        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class, aabb, smashPredicate(attacker));

        Vec3 look = attacker.getLookAngle();
        double attackerSpeed = Math.max(0.0, look.dot(getMotion(attacker)));
        boolean hitSomething = false;

        for (LivingEntity target : targets) {
            if (attacker instanceof SpearCooldownAccessor accessor
                    && accessor.WasRecentlyStabbed(target, getContactCooldownTicks())) continue;

            double targetSpeed = look.dot(getMotion(target));
            double relSpeed = Math.max(0.0, attackerSpeed - targetSpeed);
            float damage = calculateTotalDamage(baseDamage, relSpeed, fallDistance);

            if (target.hurt(SpearDamageTypes.spear(level, attacker), damage)) {
                hitSomething = true;
                stack.hurtAndBreak(1, attacker, slot);
                player.setLastHurtMob(target);
                player.fallDistance = 0.0F;
                clearFallDistance(player);
                if (attacker instanceof SpearCooldownAccessor accessor) {
                    accessor.RememberStabbedEntity(target);
                }
            }
        }

        if (hitSomething) {
            SoundEvent sound = fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD
                    ? SoundEvents.MACE_SMASH_GROUND_HEAVY
                    : SoundEvents.MACE_SMASH_GROUND;
            level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                    sound, SoundSource.PLAYERS, 1.0F, 1.0F);
            level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                    SoundEvents.MACE_SMASH_AIR, SoundSource.PLAYERS, 1.0F, 1.0F);

            smashKnockback(level, player, targets, fallDistance);

            // 风爆：按原版倍率上抬；没有风爆附魔时沿用原来的 0.6 反冲
            double windBurst = spear$windBurstLift(level, stack);
            spear$windBurstFx(level, player, windBurst);
            player.setDeltaMovement(player.getDeltaMovement()
                    .add(0.0, windBurst > 0 ? windBurst : 0.6, 0.0));
            if (player instanceof ServerPlayer sp) {
                sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
                sp.hurtMarked = true;
            }
        }

        return hitSomething;
    }

    /**
     * 计算总伤害：矛基础 + 速度加成 + 坠落伤害
     */
    private float calculateTotalDamage(float baseDamage, double relSpeed, float fallDistance) {
        float spearDamage = baseDamage + (float) relSpeed * getDamageMultiplier();

        float smashBonus = 0.0F;
        if (fallDistance > SMASH_ATTACK_FALL_THRESHOLD) {
            smashBonus = (fallDistance - SMASH_ATTACK_FALL_THRESHOLD) * 3.0F;
            if (fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD) {
                smashBonus += (fallDistance - SMASH_ATTACK_HEAVY_THRESHOLD) * 2.0F;
            }
        }

        return spearDamage + smashBonus;
    }

    @Override
    public void attack(LivingEntity attacker, EquipmentSlot slot) {
        if (!(attacker instanceof Player player)) {
            super.attack(attacker, slot);
            applyLungeExtension(attacker);
            return;
        }

        cacheFallDistance(player);
        float fallDistance = getCachedFallDistance(player);

        if (!canSmash(player, fallDistance)) {
            super.attack(attacker, slot);
            applyLungeExtension(attacker);
            return;
        }

        // 重锤AOE攻击
        Level level = attacker.level();
        ItemStack stack = attacker.getItemBySlot(slot);
        float baseDamage = (float) attacker.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);

        AABB aabb = attacker.getBoundingBox()
                .inflate(getMaxRange(), 0.0D, getMaxRange())
                .expandTowards(0.0D, -Math.max(0.0, fallDistance - 1.0F), 0.0D);
        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class, aabb, smashPredicate(attacker));

        Vec3 look = attacker.getLookAngle();
        double attackerSpeed = look.dot(getMotion(attacker));
        boolean hitSomething = false;

        for (LivingEntity target : targets) {
            double targetSpeed = look.dot(getMotion(target));
            double relSpeed = Math.max(0.0F, attackerSpeed - targetSpeed);
            float damage = calculateTotalDamage(baseDamage, relSpeed, fallDistance);
            // 与右键蓄力同一套附魔结算
            damage += spear$densityBonus(level, stack, fallDistance);
            damage = spear$applyBreach(level, stack, target, damage);

            if (target.hurt(SpearDamageTypes.spear(level, attacker), damage)) {
                hitSomething = true;
                stack.hurtAndBreak(1, attacker, slot);
                player.setLastHurtMob(target);
                player.fallDistance = 0.0F;
                clearFallDistance(player);
            }
        }

        if (attacker instanceof ServerPlayer serverPlayer) {
            serverPlayer.resetAttackStrengthTicker();
        }

        if (hitSomething) {
            // 追踪冲击位置（原版粒子用）
            if (player instanceof ServerPlayer sp) {
                if (sp.isIgnoringFallDamageFromCurrentImpulse() && sp.currentImpulseImpactPos != null) {
                    if (sp.currentImpulseImpactPos.y > sp.position().y) {
                        sp.currentImpulseImpactPos = sp.position();
                    }
                } else {
                    sp.currentImpulseImpactPos = sp.position();
                }
                sp.setIgnoreFallDamageFromCurrentImpulse(true);
                sp.setSpawnExtraParticlesOnFall(true);
            }

            // 目标在地面→砸地音效，在空中→挥击音效
            if (targets.stream().anyMatch(LivingEntity::onGround)) {
                SoundEvent sound = fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD
                        ? SoundEvents.MACE_SMASH_GROUND_HEAVY
                        : SoundEvents.MACE_SMASH_GROUND;
                level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                        sound, SoundSource.PLAYERS, 1.0F, 1.0F);
            } else {
                level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                        SoundEvents.MACE_SMASH_AIR, SoundSource.PLAYERS, 1.0F, 1.0F);
            }

            smashKnockback(level, player, targets, fallDistance);

            // 原版：几乎停住玩家，不是往上弹
            player.setDeltaMovement(player.getDeltaMovement().with(Axis.Y, 0.01F));
            if (player instanceof ServerPlayer sp) {
                sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
            }
            attacker.swing(InteractionHand.MAIN_HAND, false);
            return;
        }

        level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                getAttackSound(), SoundSource.PLAYERS, 1.0F, 1.0F);
        attacker.swing(InteractionHand.MAIN_HAND, false);
    }

    /**
     * 重锤击退（AOE范围击退）
     */
    private void smashKnockback(Level level, Player player, List<LivingEntity> hitTargets, float fallDistance) {
        for (LivingEntity target : hitTargets) {
            level.levelEvent(2013, target.getOnPos(), 750);
        }
        level.getEntitiesOfClass(LivingEntity.class,
                        player.getBoundingBox().inflate(SMASH_ATTACK_KNOCKBACK_RADIUS),
                        knockbackPredicate(player, player))
                .forEach(target -> {
                    Vec3 vec3 = target.position().subtract(player.position());
                    double d0 = getKnockbackPower(player, target, vec3, fallDistance);
                    Vec3 vec31 = vec3.normalize().scale(d0);
                    if (d0 > 0.0F) {
                        target.push(vec31.x, 0.7F, vec31.z);
                        if (target instanceof ServerPlayer sp) {
                            sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
                        }
                    }
                });
    }

    private double getKnockbackPower(Player player, LivingEntity target, Vec3 distanceVec, float fallDistance) {
        return (SMASH_ATTACK_KNOCKBACK_RADIUS - distanceVec.length())
                * SMASH_ATTACK_KNOCKBACK_POWER
                * (fallDistance > SMASH_ATTACK_HEAVY_THRESHOLD ? 2 : 1)
                * (1.0F - target.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE));
    }

    private Predicate<LivingEntity> smashPredicate(LivingEntity user) {
        return target -> {
            if (target == user) return false;
            if (target.isSpectator()) return false;
            if (!target.isAlive()) return false;
            if (user instanceof Player player) {
                if (player.isAlliedTo(target)) return false;
                if (target instanceof TamableAnimal ta && ta.isTame()
                        && player.getUUID().equals(ta.getOwnerUUID())) return false;
                if (target instanceof ArmorStand as && as.isMarker()) return false;
                if (target instanceof Player otherPlayer && !player.canHarmPlayer(otherPlayer)) return false;
            }
            return true;
        };
    }

    private Predicate<LivingEntity> knockbackPredicate(Player player, Entity center) {
        return target -> {
            if (target == player || target == center) return false;
            if (target.isSpectator()) return false;
            if (player.isAlliedTo(target)) return false;
            if (target instanceof TamableAnimal ta && ta.isTame()
                    && player.getUUID().equals(ta.getOwnerUUID())) return false;
            if (target instanceof ArmorStand as && as.isMarker()) return false;
            return center.distanceToSqr(target) <= Math.pow(SMASH_ATTACK_KNOCKBACK_RADIUS, 2);
        };
    }

    // ========== 其他设置 ==========

    @Override
    public int getEnchantmentValue() {
        return 15;
    }

    @Override
    protected int getSpearEnchantmentValue() {
        return getEnchantmentValue();
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return !player.isCreative();
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    // ========== 物品栏属性显示 ==========

    private static final ResourceLocation BASE_ATTACK_DAMAGE_ID =
            ResourceLocation.withDefaultNamespace("base_attack_damage");
    private static final ResourceLocation BASE_ATTACK_SPEED_ID =
            ResourceLocation.withDefaultNamespace("base_attack_speed");

    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        var builder = ItemAttributeModifiers.builder();

        builder.add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                new AttributeModifier(BASE_ATTACK_DAMAGE_ID,
                        4.0, // 下界合金长矛攻击伤害加成
                        AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);

        builder.add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,
                new AttributeModifier(BASE_ATTACK_SPEED_ID,
                        1.0F / 1.15F - 4.0F, // 下界合金长矛攻击速度
                        AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);

        builder.add(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE,
                new AttributeModifier(ResourceLocation.fromNamespaceAndPath("spearcore", "spear_range"),
                        1.5, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);

        return builder.build();
    }

    // ===== 重锤三附魔（1.1.0 补齐：此前只有 enchantable/mace 标签，没有效果）=====

    /** 取某个附魔在物品上的等级；拿不到注册表时返回 0。 */
    private static int spear$enchantLevel(net.minecraft.world.level.Level level, ItemStack stack,
                                          net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        try {
            var holder = level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(key);
            return net.minecraft.world.item.enchantment.EnchantmentHelper
                    .getItemEnchantmentLevel(holder, stack);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * 密度：每级每格下落 +0.5 伤害（原版数值）。
     */
    private static float spear$densityBonus(net.minecraft.world.level.Level level, ItemStack stack, float fallDistance) {
        int level2 = spear$enchantLevel(level, stack,
                net.minecraft.world.item.enchantment.Enchantments.DENSITY);
        return level2 <= 0 ? 0.0F : 0.5F * level2 * Math.max(0.0F, fallDistance);
    }

    /**
     * 破甲：按原版「每点护甲减免 4%、上限 80%」，把目标护甲的有效率降低 15%/级，
     * 再把伤害折算回去 —— 等价于让目标少减那么多血。护甲为 0 时无影响。
     */
    private static float spear$applyBreach(net.minecraft.world.level.Level level, ItemStack stack,
                                           net.minecraft.world.entity.LivingEntity target, float damage) {
        int level2 = spear$enchantLevel(level, stack,
                net.minecraft.world.item.enchantment.Enchantments.BREACH);
        if (level2 <= 0) {
            return damage;
        }
        float armor = target.getArmorValue();
        if (armor <= 0.0F) {
            return damage;
        }
        float keepNormal = 1.0F - Math.min(0.8F, armor * 0.04F);
        float keepBreached = 1.0F - Math.min(0.8F, armor * (1.0F - 0.15F * level2) * 0.04F);
        return keepNormal <= 0.0F ? damage : damage * (keepBreached / keepNormal);
    }

    /**
     * 风爆：砸中后把玩家向上弹起，基础 0.6，每级再 +0.6。
     * 返回 0 表示没有风爆附魔（调用方沿用原来的固定反冲）。
     */
    /**
     * 风爆：按原版 {@code wind_burst.json} 的 {@code knockback_multiplier} —— I 1.2 / II 1.75 / III 2.2。
     * 原版是一次 radius 3.5 的爆炸把攻击者弹飞，这里直接给垂直初速度，避免自伤。
     *
     * @return 垂直初速度；没有风爆附魔时返回 -1（调用方沿用原来的 0.6 反冲）
     */
    private static double spear$windBurstLift(net.minecraft.world.level.Level level, ItemStack stack) {
        int level2 = spear$enchantLevel(level, stack,
                net.minecraft.world.item.enchantment.Enchantments.WIND_BURST);
        if (level2 <= 0) {
            return -1.0;
        }
        double[] multipliers = {1.2, 1.75, 2.2};
        return multipliers[Math.min(level2, 3) - 1];
    }


    /**
     * 风爆的粒子与音效。
     *
     * <p>原版 {@code wind_burst.json} 里除了弹射，还带
     * {@code large_particle: minecraft:gust_emitter_large}、
     * {@code small_particle: minecraft:gust_emitter_small}、
     * {@code sound: minecraft:entity.wind_charge.wind_burst} —— 这里一并补上。</p>
     */
    private static void spear$windBurstFx(net.minecraft.world.level.Level level,
                                          net.minecraft.world.entity.player.Player player, double lift) {
        if (lift <= 0) {
            return;
        }
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.GUST_EMITTER_SMALL,
                    player.getX(), player.getY() + 0.5, player.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
            serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.GUST_EMITTER_LARGE,
                    player.getX(), player.getY() + 0.5, player.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

}