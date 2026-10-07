package com.zombieai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ZombieAiMod implements ModInitializer {
    public static final String MOD_ID = "zombie_ai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<UUID, ZombieBrain> BRAINS = new HashMap<>();

    @Override
    public void onInitialize() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof Zombie zombie) {
                BRAINS.computeIfAbsent(zombie.getUUID(), id -> new ZombieBrain(zombie));
            }
        });

        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof Zombie) BRAINS.remove(entity.getUUID());
        });

        LOGGER.info("Zombie AI Evolution carregado para Minecraft 26.3");
    }

    private static final class ZombieBrain {
        private final Zombie zombie;
        private final SimpleContainer inventory = new SimpleContainer(18);
        private BlockPos harvestTarget;
        private BlockPos breakTarget;
        private int breakTicks;
        private int actionCooldown;
        private int huntCooldown;
        private int craftCooldown;
        private boolean tablePlaced;

        ZombieBrain(Zombie zombie) {
            this.zombie = zombie;
        }

        void tick() {
            if (!(zombie.level() instanceof ServerLevel level) || !zombie.isAlive()) return;
            if (actionCooldown > 0) actionCooldown--;
            if (huntCooldown > 0) huntCooldown--;
            if (craftCooldown > 0) craftCooldown--;

            collectNearbyItems(level);
            improveEquipment();

            // Player pursuit has priority over resource gathering.
            LivingEntity target = zombie.getTarget();
            if (target == null || !target.isAlive()) {
                target = findNearestPlayer(level);
                if (target != null) zombie.setTarget(target);
            }

            if (target != null && target.isAlive()) {
                pursueAndBreakObstruction(level, target);
                return;
            }

            if (zombie.getHealth() < zombie.getMaxHealth() * 0.95f) {
                if (eatNearbyFood(level)) return;
            }

            if (huntCooldown == 0) {
                Animal animal = findNearestAnimal(level);
                if (animal != null) {
                    huntCooldown = 10;
                    hunt(level, animal);
                    return;
                }
            }

            if (craftCooldown == 0 && hasWood()) {
                craftCooldown = 15;
                craftAndPlaceTable(level);
                return;
            }

            if (harvestTarget == null || !isLog(level.getBlockState(harvestTarget)) || harvestTarget.distSqr(zombie.blockPosition()) > 20 * 20) {
                harvestTarget = findNearestLog(level);
            }
            if (harvestTarget != null) harvestWood(level);
        }

        private LivingEntity findNearestPlayer(ServerLevel level) {
            return level.getEntitiesOfClass(Player.class,
                            new AABB(zombie.blockPosition()).inflate(24),
                            Player::isAlive)
                    .stream().min(Comparator.comparingDouble(zombie::distanceToSqr)).orElse(null);
        }

        private Animal findNearestAnimal(ServerLevel level) {
            return level.getEntitiesOfClass(Animal.class,
                            new AABB(zombie.blockPosition()).inflate(18),
                            Animal::isAlive)
                    .stream().min(Comparator.comparingDouble(zombie::distanceToSqr)).orElse(null);
        }

        private BlockPos findNearestLog(ServerLevel level) {
            BlockPos origin = zombie.blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            int radius = 14;
            for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -5, -radius), origin.offset(radius, 8, radius))) {
                if (isLog(level.getBlockState(pos))) {
                    double d = pos.distSqr(origin);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = pos.immutable();
                    }
                }
            }
            return best;
        }

        private boolean isLog(BlockState state) {
            return state.is(BlockTags.LOGS);
        }

        private void harvestWood(ServerLevel level) {
            if (harvestTarget == null) return;
            double distance = zombie.distanceToSqr(harvestTarget.getCenter());
            if (distance > 3.5 * 3.5) {
                zombie.getNavigation().moveTo(harvestTarget.getX() + .5, harvestTarget.getY(), harvestTarget.getZ() + .5, 1.0);
                return;
            }
            if (actionCooldown > 0) return;
            actionCooldown = 18;
            breakTicks++;
            if (breakTicks >= 3) {
                if (level.getBlockState(harvestTarget).is(BlockTags.LOGS)) {
                    level.destroyBlock(harvestTarget, true, zombie);
                }
                breakTicks = 0;
                harvestTarget = null;
            }
        }

        private void collectNearbyItems(ServerLevel level) {
            AABB box = zombie.getBoundingBox().inflate(2.75);
            for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive)) {
                ItemStack stack = itemEntity.getItem().copy();
                if (!isUseful(stack)) continue;
                ItemStack remainder = inventory.addItem(stack);
                int moved = stack.getCount() - remainder.getCount();
                if (moved > 0) {
                    if (remainder.isEmpty()) itemEntity.discard();
                    else itemEntity.setItem(remainder);
                }
            }
        }

        private boolean isUseful(ItemStack stack) {
            Item item = stack.getItem();
            return isWood(item) || item == Items.STICK || item == Items.CRAFTING_TABLE
                    || isFood(item) || item == Items.WOODEN_SWORD || item == Items.WOODEN_PICKAXE
                    || item == Items.STONE_SWORD || item == Items.STONE_PICKAXE
                    || item == Items.IRON_SWORD || item == Items.IRON_PICKAXE
                    || item == Items.DIAMOND_SWORD || item == Items.DIAMOND_PICKAXE
                    || item == Items.NETHERITE_SWORD || item == Items.NETHERITE_PICKAXE
                    || item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE
                    || item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS
                    || item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE
                    || item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS
                    || item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE
                    || item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS;
        }

        private boolean isWood(Item item) {
            return item == Items.OAK_LOG || item == Items.SPRUCE_LOG || item == Items.BIRCH_LOG
                    || item == Items.JUNGLE_LOG || item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG
                    || item == Items.MANGROVE_LOG || item == Items.CHERRY_LOG || item == Items.PALE_OAK_LOG
                    || item == Items.OAK_WOOD || item == Items.SPRUCE_WOOD || item == Items.BIRCH_WOOD
                    || item == Items.JUNGLE_WOOD || item == Items.ACACIA_WOOD || item == Items.DARK_OAK_WOOD
                    || item == Items.MANGROVE_WOOD || item == Items.CHERRY_WOOD || item == Items.PALE_OAK_WOOD;
        }

        private boolean hasWood() {
            return findItem(isWood) >= 0;
        }

        private int findItem(java.util.function.Predicate<Item> predicate) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (predicate.test(inventory.getItem(i).getItem()) && !inventory.getItem(i).isEmpty()) return i;
            }
            return -1;
        }

        private void craftAndPlaceTable(ServerLevel level) {
            int woodSlot = findItem(this::isWood);
            if (woodSlot < 0) return;
            ItemStack wood = inventory.getItem(woodSlot);
            int amount = Math.min(wood.getCount(), 2);
            wood.shrink(amount);
            inventory.setItem(woodSlot, wood);
            inventory.addItem(new ItemStack(Items.OAK_PLANKS, amount * 4));

            int planks = count(Items.OAK_PLANKS);
            if (planks >= 4 && count(Items.CRAFTING_TABLE) == 0) {
                remove(Items.OAK_PLANKS, 4);
                inventory.addItem(new ItemStack(Items.CRAFTING_TABLE));
            }

            if (!tablePlaced && count(Items.CRAFTING_TABLE) > 0) {
                BlockPos place = findPlace(level);
                if (place != null) {
                    level.setBlock(place, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                    remove(Items.CRAFTING_TABLE, 1);
                    tablePlaced = true;
                }
            }

            if (tablePlaced) {
                int p = count(Items.OAK_PLANKS);
                int sticks = count(Items.STICK);
                if (p >= 3 && sticks < 2) {
                    remove(Items.OAK_PLANKS, 2);
                    inventory.addItem(new ItemStack(Items.STICK, 4));
                }
                if (count(Items.WOODEN_PICKAXE) == 0 && count(Items.STICK) >= 2 && count(Items.OAK_PLANKS) >= 3) {
                    remove(Items.STICK, 2); remove(Items.OAK_PLANKS, 3);
                    inventory.addItem(new ItemStack(Items.WOODEN_PICKAXE));
                }
                if (count(Items.WOODEN_SWORD) == 0 && count(Items.STICK) >= 1 && count(Items.OAK_PLANKS) >= 2) {
                    remove(Items.STICK, 1); remove(Items.OAK_PLANKS, 2);
                    inventory.addItem(new ItemStack(Items.WOODEN_SWORD));
                }
            }
        }

        private BlockPos findPlace(ServerLevel level) {
            BlockPos base = zombie.blockPosition();
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-2, -1, -2), base.offset(2, 1, 2))) {
                if (level.isEmptyBlock(p) && level.getBlockState(p.below()).isSolid()) return p.immutable();
            }
            return null;
        }

        private void improveEquipment() {
            equipBestTool(EquipmentSlot.MAINHAND, true);
            equipArmor(EquipmentSlot.HEAD, Items.LEATHER_HELMET, Items.IRON_HELMET, Items.DIAMOND_HELMET);
            equipArmor(EquipmentSlot.CHEST, Items.LEATHER_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE);
            equipArmor(EquipmentSlot.LEGS, Items.LEATHER_LEGGINGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS);
            equipArmor(EquipmentSlot.FEET, Items.LEATHER_BOOTS, Items.IRON_BOOTS, Items.DIAMOND_BOOTS);
        }

        private void equipBestTool(EquipmentSlot slot, boolean weapon) {
            ItemStack current = zombie.getItemBySlot(slot);
            int currentScore = toolScore(current, weapon);
            int bestSlot = -1, bestScore = currentScore;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                int score = toolScore(inventory.getItem(i), weapon);
                if (score > bestScore) { bestScore = score; bestSlot = i; }
            }
            if (bestSlot >= 0) {
                ItemStack old = current.copy();
                ItemStack chosen = inventory.removeItemNoUpdate(bestSlot);
                zombie.setItemSlot(slot, chosen);
                if (!old.isEmpty()) inventory.addItem(old);
            }
        }

        private int toolScore(ItemStack stack, boolean weapon) {
            if (stack.isEmpty()) return -1;
            Item i = stack.getItem();
            if (weapon) {
                if (i == Items.WOODEN_SWORD) return 1;
                if (i == Items.STONE_SWORD) return 2;
                if (i == Items.IRON_SWORD) return 3;
                if (i == Items.DIAMOND_SWORD) return 4;
                if (i == Items.NETHERITE_SWORD) return 5;
            }
            return -1;
        }

        private void equipArmor(EquipmentSlot slot, Item... items) {
            int best = armorScore(zombie.getItemBySlot(slot).getItem());
            int bestSlot = -1;
            for (int n = 0; n < inventory.getContainerSize(); n++) {
                Item item = inventory.getItem(n).getItem();
                int score = armorScore(item);
                if (score > best) { best = score; bestSlot = n; }
            }
            if (bestSlot >= 0) {
                ItemStack old = zombie.getItemBySlot(slot).copy();
                zombie.setItemSlot(slot, inventory.removeItemNoUpdate(bestSlot));
                if (!old.isEmpty()) inventory.addItem(old);
            }
        }

        private int armorScore(Item item) {
            if (item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE || item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS) return 1;
            if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE || item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) return 2;
            if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE || item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) return 3;
            return -1;
        }

        private void pursueAndBreakObstruction(ServerLevel level, LivingEntity target) {
            double d = zombie.distanceToSqr(target);
            if (d < 2.8 * 2.8) {
                zombie.doHurtTarget(target);
                return;
            }
            Path path = zombie.getNavigation().createPath(target, 0);
            if (path == null || path.isDone()) {
                breakObstacleAhead(level, target);
            } else {
                zombie.getNavigation().moveTo(path, 1.15);
            }
        }

        private void breakObstacleAhead(ServerLevel level, LivingEntity target) {
            BlockPos feet = zombie.blockPosition();
            BlockPos targetPos = target.blockPosition();
            int dx = Integer.signum(targetPos.getX() - feet.getX());
            int dz = Integer.signum(targetPos.getZ() - feet.getZ());
            BlockPos obstacle = feet.offset(dx, 0, dz);
            BlockState state = level.getBlockState(obstacle);
            if (!state.isAir() && state.getDestroySpeed(level, obstacle) >= 0 && state.getDestroySpeed(level, obstacle) <= 50) {
                if (actionCooldown == 0) {
                    actionCooldown = 8;
                    breakTarget = obstacle;
                    breakTicks++;
                    if (breakTicks >= 5) {
                        level.destroyBlock(obstacle, true, zombie);
                        breakTicks = 0;
                        breakTarget = null;
                    }
                }
            } else {
                breakTicks = 0;
            }
        }

        private void hunt(ServerLevel level, Animal animal) {
            if (zombie.distanceToSqr(animal) <= 2.8 * 2.8) {
                zombie.doHurtTarget(animal);
                return;
            }
            zombie.getNavigation().moveTo(animal, 1.1);
        }

        private boolean eatNearbyFood(ServerLevel level) {
            int slot = findFood();
            if (slot < 0) return false;
            ItemStack food = inventory.getItem(slot);
            int heal = 4;
            zombie.heal(heal);
            food.shrink(1);
            inventory.setItem(slot, food);
            return true;
        }

        private int findFood() {
            for (int i = 0; i < inventory.getContainerSize(); i++) if (isFood(inventory.getItem(i).getItem())) return i;
            return -1;
        }

        private boolean isFood(Item item) {
            return item == Items.BEEF || item == Items.COOKED_BEEF || item == Items.PORKCHOP || item == Items.COOKED_PORKCHOP
                    || item == Items.MUTTON || item == Items.COOKED_MUTTON || item == Items.CHICKEN || item == Items.COOKED_CHICKEN
                    || item == Items.ROTTEN_FLESH;
        }

        private int count(Item item) {
            int total = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).is(item)) total += inventory.getItem(i).getCount();
            return total;
        }

        private void remove(Item item, int amount) {
            int left = amount;
            for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.is(item)) {
                    int take = Math.min(left, stack.getCount());
                    stack.shrink(take);
                    left -= take;
                    inventory.setItem(i, stack);
                }
            }
        }
    }
}
