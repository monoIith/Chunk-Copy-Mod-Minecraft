package dev.chunkcopy.gametest;

import dev.chunkcopy.ChunkCopyIds;
import dev.chunkcopy.ChunkCopyMod;
import dev.chunkcopy.replication.CapturedAction;
import dev.chunkcopy.replication.ReplicationMode;
import dev.chunkcopy.replication.PackedChunk;
import dev.chunkcopy.replication.RootMutation;
import dev.chunkcopy.replication.SpawnerEntitySnapshot;
import dev.chunkcopy.replication.SanitizedBlockEntityData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.spawner.MobSpawnerEntry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.test.TestContext;
import net.minecraft.storage.NbtReadView;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.dynamic.Range;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ChunkCopyGameTests {
    @GameTest(maxTicks = 40, setupTicks = 60, skyAccess = true)
    public void persistentModeMaterializesStateIntoLaterLoadedChunk(TestContext context) {
        BlockPos templateOrigin = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos source = new BlockPos(templateOrigin.getX() + 1, 100, templateOrigin.getZ() + 1);
        int sourceChunkX = Math.floorDiv(source.getX(), 16);
        int sourceChunkZ = Math.floorDiv(source.getZ(), 16);
        int targetChunkX = sourceChunkX + 32;
        while (context.getWorld().getChunkManager().getWorldChunk(targetChunkX, sourceChunkZ) != null) {
            targetChunkX += 16;
        }
        BlockPos destination = new BlockPos(
                (targetChunkX << 4) + Math.floorMod(source.getX(), 16),
                source.getY(),
                (sourceChunkZ << 4) + Math.floorMod(source.getZ(), 16)
        );

        context.getWorld().setBlockState(source.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.PERSISTENT);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(Vec3d.ofCenter(source.add(2, 0, 0)), 90.0F, 0.0F);
        ItemStack stack = new ItemStack(Items.GOLD_BLOCK);
        player.setStackInHand(Hand.MAIN_HAND, stack);
        ActionResult result = player.interactionManager.interactBlock(
                player,
                context.getWorld(),
                stack,
                Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(source.down()), Direction.UP, source.down(), false)
        );
        context.assertTrue(result.isAccepted(), Text.literal("persistent source placement was not accepted"));

        context.getWorld().getChunkManager().getChunk(targetChunkX, sourceChunkZ, ChunkStatus.FULL, true);
        context.waitAndRun(4, () -> {
            context.assertEquals(
                    Blocks.GOLD_BLOCK,
                    context.getWorld().getBlockState(destination).getBlock(),
                    Text.literal("persistent overlay did not materialize into late-loaded chunk")
            );
            ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
            context.complete();
        });
    }

    @GameTest(maxTicks = 10)
    public void spawnerSnapshotPreservesMobStateWithFreshIdentity(TestContext context) {
        ZombieEntity source = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.25, 2.0, 3.75));
        source.setBaby(true);
        source.setHealth(7.0F);
        source.setYaw(123.0F);
        source.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));

        SpawnerEntitySnapshot snapshot = SpawnerEntitySnapshot.capture(context.getWorld(), source).orElseThrow();
        context.assertFalse(snapshot.entityData().contains("UUID"), Text.literal("spawner snapshot kept UUID"));
        context.assertFalse(snapshot.entityData().contains("Passengers"), Text.literal("spawner snapshot kept passengers"));
        context.assertFalse(snapshot.entityData().contains("Brain"), Text.literal("spawner snapshot kept brain references"));
        long targetChunk = PackedChunk.pack(
                PackedChunk.x(snapshot.sourceChunk()) + 1,
                PackedChunk.z(snapshot.sourceChunk())
        );
        Entity created = snapshot.create(context.getWorld(), targetChunk);
        context.assertTrue(created instanceof ZombieEntity, Text.literal("snapshot did not recreate zombie type"));
        ZombieEntity clone = (ZombieEntity) created;
        context.assertFalse(source.getUuid().equals(clone.getUuid()), Text.literal("clone reused source UUID"));
        context.assertTrue(clone.isBaby(), Text.literal("clone lost mob age"));
        context.assertEquals(7.0F, clone.getHealth(), Text.literal("clone lost health"));
        context.assertEquals(123.0F, clone.getYaw(), Text.literal("clone lost yaw"));
        context.assertEquals(
                Items.IRON_SWORD,
                clone.getEquippedStack(EquipmentSlot.MAINHAND).getItem(),
                Text.literal("clone lost equipment")
        );
        context.complete();
    }

    @GameTest(maxTicks = 20, setupTicks = 35, skyAccess = true)
    public void flintAndSteelPrimesOnlySourceTnt(TestContext context) {
        BlockPos source = context.getAbsolutePos(new BlockPos(2, 5, 2));
        BlockPos destination = source.add(16, 0, 0);
        context.getWorld().setBlockState(source, Blocks.TNT.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(destination, Blocks.TNT.getDefaultState(), Block.NOTIFY_ALL);

        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(Vec3d.ofCenter(source.add(2, 0, 0)), 90.0F, 0.0F);
        ItemStack flint = new ItemStack(Items.FLINT_AND_STEEL);
        player.setStackInHand(Hand.MAIN_HAND, flint);
        ActionResult result = player.interactionManager.interactBlock(
                player,
                context.getWorld(),
                flint,
                Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(source), Direction.UP, source, false)
        );
        context.assertTrue(result.isAccepted(), Text.literal("flint-and-steel TNT use was not accepted"));

        context.waitAndRun(3, () -> {
            Box area = Box.enclosing(source, destination).expand(4.0);
            int primedTnt = context.getWorld().getEntitiesByType(EntityType.TNT, area, entity -> true).size();
            context.assertEquals(1, primedTnt, Text.literal("flint action should create only one source TNT entity"));
            context.assertEquals(
                    Blocks.AIR,
                    context.getWorld().getBlockState(destination).getBlock(),
                    Text.literal("remote TNT root was not copied to air")
            );
            context.complete();
        });
    }

    @GameTest(maxTicks = 20, setupTicks = 45, skyAccess = true)
    public void copiedRedstoneTorchPrimesTntInBothChunks(TestContext context) {
        BlockPos sourceTnt = context.getAbsolutePos(new BlockPos(2, 6, 2));
        BlockPos sourceTorch = sourceTnt.east();
        BlockPos destinationTnt = sourceTnt.add(16, 0, 0);
        BlockPos destinationTorch = sourceTorch.add(16, 0, 0);
        context.getWorld().setBlockState(sourceTnt, Blocks.TNT.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(destinationTnt, Blocks.TNT.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(sourceTorch.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(destinationTorch.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(Vec3d.ofCenter(sourceTorch.add(2, 0, 0)), 90.0F, 0.0F);
        ItemStack torch = new ItemStack(Items.REDSTONE_TORCH);
        player.setStackInHand(Hand.MAIN_HAND, torch);
        ActionResult result = player.interactionManager.interactBlock(
                player,
                context.getWorld(),
                torch,
                Hand.MAIN_HAND,
                new BlockHitResult(
                        Vec3d.ofCenter(sourceTorch.down()),
                        Direction.UP,
                        sourceTorch.down(),
                        false
                )
        );
        context.assertTrue(result.isAccepted(), Text.literal("redstone-torch placement was not accepted"));

        context.waitAndRun(3, () -> {
            Box area = Box.enclosing(sourceTnt, destinationTnt).expand(4.0);
            int primedTnt = context.getWorld().getEntitiesByType(EntityType.TNT, area, entity -> true).size();
            context.assertEquals(2, primedTnt, Text.literal("copied torch should independently prime both TNT blocks"));
            context.complete();
        });
    }

    @GameTest(maxTicks = 20, setupTicks = 25, skyAccess = true)
    public void copiedPumpkinCreatesOneIndependentGolemPerCompleteStructure(TestContext context) {
        BlockPos sourceCenter = context.getAbsolutePos(new BlockPos(3, 2, 3));
        BlockPos destinationCenter = sourceCenter.add(16, 0, 0);
        placeIronBody(context.getWorld(), sourceCenter);
        placeIronBody(context.getWorld(), destinationCenter);

        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(Vec3d.ofCenter(sourceCenter.add(2, 2, 0)), 90.0F, 0.0F);
        ItemStack pumpkin = new ItemStack(Items.CARVED_PUMPKIN);
        player.setStackInHand(Hand.MAIN_HAND, pumpkin);
        BlockPos clicked = sourceCenter.up();
        ActionResult result = player.interactionManager.interactBlock(
                player,
                context.getWorld(),
                pumpkin,
                Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(clicked), Direction.UP, clicked, false)
        );
        context.assertTrue(result.isAccepted(), Text.literal("mock pumpkin placement was not accepted"));

        context.waitAndRun(3, () -> {
            Box bothStructures = Box.enclosing(sourceCenter, destinationCenter.add(1, 4, 1)).expand(3.0);
            int golems = context.getWorld()
                    .getEntitiesByType(EntityType.IRON_GOLEM, bothStructures, entity -> true)
                    .size();
            context.assertEquals(2, golems, Text.literal("expected one unique golem per complete structure"));
            context.assertEquals(
                    Blocks.AIR,
                    context.getWorld().getBlockState(sourceCenter).getBlock(),
                    Text.literal("source golem body was not consumed")
            );
            context.assertEquals(
                    Blocks.AIR,
                    context.getWorld().getBlockState(destinationCenter).getBlock(),
                    Text.literal("destination golem body was not consumed")
            );
            context.complete();
        });
    }

    @GameTest(maxTicks = 20, setupTicks = 15, skyAccess = true)
    public void playerRootPlacementCopiesToAnotherLoadedChunk(TestContext context) {
        BlockPos source = context.getAbsolutePos(new BlockPos(1, 7, 1));
        BlockPos destination = source.add(16, 0, 0);
        context.getWorld().setBlockState(source.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(destination.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(source, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(destination, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(Vec3d.ofCenter(source.add(2, 0, 0)), 90.0F, 0.0F);
        ItemStack stack = new ItemStack(Items.COBBLESTONE);
        player.setStackInHand(Hand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(source.down()),
                Direction.UP,
                source.down(),
                false
        );
        ActionResult result = player.interactionManager.interactBlock(
                player,
                context.getWorld(),
                stack,
                Hand.MAIN_HAND,
                hit
        );
        context.assertTrue(result.isAccepted(), Text.literal("mock player placement was not accepted"));

        context.waitAndRun(3, () -> {
            context.assertEquals(
                    Blocks.COBBLESTONE,
                    context.getWorld().getBlockState(source).getBlock(),
                    Text.literal("source placement missing")
            );
            context.assertEquals(
                    Blocks.COBBLESTONE,
                    context.getWorld().getBlockState(destination).getBlock(),
                    Text.literal("destination placement missing")
            );
            context.complete();
        });
    }

    @GameTest(maxTicks = 10)
    public void protectedTagContainsBuiltIns(TestContext context) {
        context.assertTrue(
                Blocks.BEDROCK.getDefaultState().isIn(ChunkCopyIds.PROTECTED_BLOCKS),
                Text.literal("bedrock must be protected")
        );
        context.assertTrue(
                Blocks.REPEATING_COMMAND_BLOCK.getDefaultState().isIn(ChunkCopyIds.PROTECTED_BLOCKS),
                Text.literal("command blocks must be protected")
        );
        context.complete();
    }

    @GameTest(maxTicks = 10)
    public void normalPumpkinCallbackCreatesExactlyOneGolem(TestContext context) {
        BlockPos center = new BlockPos(4, 2, 4);
        placeIronBody(context, center);
        context.getWorld().setBlockState(
                context.getAbsolutePos(center.up(2)),
                Blocks.CARVED_PUMPKIN.getDefaultState(),
                Block.NOTIFY_ALL
        );
        context.waitAndRun(2, () -> {
            context.expectEntities(EntityType.IRON_GOLEM, 1);
            context.expectBlock(Blocks.AIR, center);
            context.expectBlock(Blocks.AIR, center.up());
            context.expectBlock(Blocks.AIR, center.up(2));
            context.complete();
        });
    }

    @GameTest(maxTicks = 10)
    public void catchupFlagsDoNotCreateHistoricalGolem(TestContext context) {
        BlockPos center = new BlockPos(4, 2, 4);
        placeIronBody(context, center);
        context.getWorld().setBlockState(
                context.getAbsolutePos(center.up(2)),
                Blocks.CARVED_PUMPKIN.getDefaultState(),
                Block.FORCE_STATE_AND_SKIP_CALLBACKS_AND_DROPS | Block.NOTIFY_LISTENERS
        );
        context.waitAndRun(2, () -> {
            context.expectEntities(EntityType.IRON_GOLEM, 0);
            context.expectBlock(Blocks.IRON_BLOCK, center);
            context.expectBlock(Blocks.CARVED_PUMPKIN, center.up(2));
            context.complete();
        });
    }

    @GameTest(maxTicks = 10)
    public void finalStateCatchupDoesNotPrimeTnt(TestContext context) {
        BlockPos tnt = new BlockPos(3, 2, 3);
        BlockPos torch = tnt.east();
        context.setBlockState(tnt, Blocks.TNT);
        context.setBlockState(torch.down(), Blocks.STONE);
        context.getWorld().setBlockState(
                context.getAbsolutePos(torch),
                Blocks.REDSTONE_TORCH.getDefaultState(),
                Block.FORCE_STATE_AND_SKIP_CALLBACKS_AND_DROPS | Block.NOTIFY_LISTENERS
        );
        context.waitAndRun(2, () -> {
            context.expectEntities(EntityType.TNT, 0);
            context.expectBlock(Blocks.TNT, tnt);
            context.complete();
        });
    }

    @GameTest(maxTicks = 10)
    public void decoratedPotSanitizerExcludesInventory(TestContext context) {
        BlockPos potPos = new BlockPos(3, 2, 3);
        context.setBlockState(potPos, Blocks.DECORATED_POT);
        DecoratedPotBlockEntity pot = context.getBlockEntity(potPos, DecoratedPotBlockEntity.class);
        pot.setStack(new ItemStack(Items.DIAMOND));
        SanitizedBlockEntityData data = SanitizedBlockEntityData.capture(
                context.getWorld(),
                context.getAbsolutePos(potPos)
        ).orElseThrow();
        context.assertFalse(data.data().contains("item"), Text.literal("pot inventory leaked into replica metadata"));
        context.assertFalse(data.data().contains("LootTable"), Text.literal("pot loot table leaked into replica metadata"));
        context.complete();
    }

    @GameTest(maxTicks = 20, setupTicks = 20, skyAccess = true)
    public void sameStateReplicaReplacesDestinationBlockEntityData(TestContext context) {
        BlockPos source = context.getAbsolutePos(new BlockPos(5, 4, 5));
        BlockPos destination = source.add(16, 0, 0);
        context.getWorld().setBlockState(source, Blocks.DECORATED_POT.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(destination, Blocks.DECORATED_POT.getDefaultState(), Block.NOTIFY_ALL);
        DecoratedPotBlockEntity sourcePot = (DecoratedPotBlockEntity) context.getWorld().getBlockEntity(source);
        DecoratedPotBlockEntity destinationPot =
                (DecoratedPotBlockEntity) context.getWorld().getBlockEntity(destination);
        sourcePot.setStack(new ItemStack(Items.DIAMOND));
        destinationPot.setStack(new ItemStack(Items.EMERALD));

        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        ChunkCopyMod.service().accept(new CapturedAction(
                context.getWorld(),
                player,
                List.of(new RootMutation(source, Blocks.DECORATED_POT.getDefaultState(), Block.NOTIFY_ALL, 512)),
                Set.of(source),
                Set.of(),
                false
        ));

        context.waitAndRun(3, () -> {
            DecoratedPotBlockEntity replicated =
                    (DecoratedPotBlockEntity) context.getWorld().getBlockEntity(destination);
            context.assertTrue(replicated.getStack().isEmpty(),
                    Text.literal("destination pot inventory survived a same-state replica"));
            context.assertEquals(Items.DIAMOND, sourcePot.getStack().getItem(),
                    Text.literal("source pot inventory was modified"));
            context.complete();
        });
    }

    @GameTest(maxTicks = 45, setupTicks = 15, skyAccess = true)
    public void creeperExplosionDamageRemainsLocal(TestContext context) {
        BlockPos seed = context.getAbsolutePos(new BlockPos(4, 5, 4));
        BlockPos center = new BlockPos(
                (Math.floorDiv(seed.getX(), 16) << 4) + 8,
                seed.getY(),
                (Math.floorDiv(seed.getZ(), 16) << 4) + 8
        );
        BlockPos sourceBlock = center.east();
        BlockPos destinationBlock = sourceBlock.add(16, 0, 0);
        context.getWorld().setBlockState(sourceBlock, Blocks.WHITE_WOOL.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(destinationBlock, Blocks.WHITE_WOOL.getDefaultState(), Block.NOTIFY_ALL);
        CreeperEntity creeper = Objects.requireNonNull(
                EntityType.CREEPER.create(context.getWorld(), SpawnReason.TRIGGERED)
        );
        creeper.refreshPositionAndAngles(Vec3d.ofBottomCenter(center), 0.0F, 0.0F);
        creeper.setAiDisabled(true);
        creeper.setNoGravity(true);
        creeper.setPersistent();
        context.assertTrue(context.getWorld().spawnEntity(creeper), Text.literal("could not spawn creeper"));
        creeper.ignite();

        context.waitAndRun(34, () -> {
            context.assertTrue(creeper.isRemoved(), Text.literal("creeper did not explode"));
            context.assertFalse(
                    context.getWorld().getBlockState(sourceBlock).isOf(Blocks.WHITE_WOOL),
                    Text.literal("source creeper explosion did not damage its adjacent block")
            );
            context.assertEquals(
                    Blocks.WHITE_WOOL,
                    context.getWorld().getBlockState(destinationBlock).getBlock(),
                    Text.literal("creeper explosion damage was mirrored")
            );
            context.complete();
        });
    }

    @GameTest(maxTicks = 25, setupTicks = 30, skyAccess = true)
    public void incompleteAndProtectedGolemDestinationsDoNotSpawn(TestContext context) {
        BlockPos sourceCenter = context.getAbsolutePos(new BlockPos(4, 3, 4));
        BlockPos incompleteCenter = sourceCenter.add(16, 0, 0);
        BlockPos protectedCenter = sourceCenter.add(32, 0, 0);
        context.getWorld().getChunkManager().getChunk(
                Math.floorDiv(protectedCenter.getX(), 16),
                Math.floorDiv(protectedCenter.getZ(), 16),
                ChunkStatus.FULL,
                true
        );
        placeIronBody(context.getWorld(), sourceCenter);
        placeIronBody(context.getWorld(), incompleteCenter);
        context.getWorld().setBlockState(incompleteCenter.up().east(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        placeIronBody(context.getWorld(), protectedCenter);
        context.getWorld().setBlockState(
                protectedCenter.up(2),
                Blocks.BEDROCK.getDefaultState(),
                Block.NOTIFY_ALL
        );

        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(Vec3d.ofCenter(sourceCenter.add(2, 2, 0)), 90.0F, 0.0F);
        ItemStack pumpkin = new ItemStack(Items.CARVED_PUMPKIN);
        player.setStackInHand(Hand.MAIN_HAND, pumpkin);
        BlockPos clicked = sourceCenter.up();
        ActionResult result = player.interactionManager.interactBlock(
                player,
                context.getWorld(),
                pumpkin,
                Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(clicked), Direction.UP, clicked, false)
        );
        context.assertTrue(result.isAccepted(), Text.literal("source pumpkin placement was not accepted"));

        context.waitAndRun(4, () -> {
            context.assertEquals(
                    Blocks.AIR,
                    context.getWorld().getBlockState(sourceCenter).getBlock(),
                    Text.literal("source golem structure was not consumed")
            );
            context.assertEquals(
                    Blocks.IRON_BLOCK,
                    context.getWorld().getBlockState(incompleteCenter).getBlock(),
                    Text.literal("incomplete destination structure was consumed")
            );
            context.assertEquals(
                    Blocks.BEDROCK,
                    context.getWorld().getBlockState(protectedCenter.up(2)).getBlock(),
                    Text.literal("protected pumpkin slot was overwritten")
            );
            context.assertEquals(
                    Blocks.IRON_BLOCK,
                    context.getWorld().getBlockState(protectedCenter).getBlock(),
                    Text.literal("protected destination structure was consumed")
            );
            context.complete();
        });
    }

    @GameTest(maxTicks = 50, setupTicks = 35, skyAccess = true)
    public void standardSpawnerClonesAcrossLoadedChunks(TestContext context) {
        BlockPos spawnerPos = context.getAbsolutePos(new BlockPos(5, 4, 5));
        BlockPos spawnPos = spawnerPos.up(2);
        int destinationChunkX = Math.floorDiv(spawnerPos.getX(), 16) + 1;
        int destinationChunkZ = Math.floorDiv(spawnerPos.getZ(), 16);
        context.getWorld().getChunkManager().getChunk(
                destinationChunkX,
                destinationChunkZ,
                ChunkStatus.FULL,
                true
        );
        context.getWorld().setBlockState(spawnerPos, Blocks.SPAWNER.getDefaultState(), Block.NOTIFY_ALL);
        MobSpawnerBlockEntity spawner = (MobSpawnerBlockEntity) context.getWorld().getBlockEntity(spawnerPos);
        NbtCompound zombieData = new NbtCompound();
        zombieData.putString("id", "minecraft:zombie");
        zombieData.put("Pos", Vec3d.CODEC, Vec3d.ofBottomCenter(spawnPos));
        zombieData.putBoolean("NoAI", true);
        zombieData.putBoolean("NoGravity", true);
        zombieData.putBoolean("Invulnerable", true);
        zombieData.putBoolean("PersistenceRequired", true);
        NbtList tags = new NbtList();
        tags.add(NbtString.of("chunkcopy-standard-spawner-test"));
        zombieData.put("Tags", tags);

        MobSpawnerEntry entry = new MobSpawnerEntry(
                zombieData,
                Optional.of(new MobSpawnerEntry.CustomSpawnRules(
                        new Range<>(0, 15),
                        new Range<>(0, 15)
                )),
                Optional.empty()
        );
        NbtCompound settings = new NbtCompound();
        settings.putShort("Delay", (short) 6);
        settings.putInt("MinSpawnDelay", 1200);
        settings.putInt("MaxSpawnDelay", 1201);
        settings.putInt("SpawnCount", 1);
        settings.putInt("MaxNearbyEntities", 2);
        settings.putInt("RequiredPlayerRange", 16);
        settings.putInt("SpawnRange", 0);
        settings.put("SpawnData", MobSpawnerEntry.CODEC, entry);
        spawner.getLogic().readData(
                context.getWorld(),
                spawnerPos,
                NbtReadView.create(ErrorReporter.EMPTY, context.getWorld().getRegistryManager(), settings)
        );
        spawner.markDirty();

        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(Vec3d.ofCenter(spawnerPos.add(2, 0, 0)), 90.0F, 0.0F);

        context.waitAndRun(20, () -> {
            var spawned = context.getWorld().getEntitiesByType(
                    EntityType.ZOMBIE,
                    entity -> entity.getCommandTags().contains("chunkcopy-standard-spawner-test")
            );
            long occupiedChunks = spawned.stream()
                    .map(entity -> ChunkPos.toLong(entity.getBlockPos()))
                    .distinct()
                    .count();
            long uniqueIds = spawned.stream().map(Entity::getUuid).distinct().count();
            context.assertTrue(spawned.size() > 1 && occupiedChunks > 1,
                    Text.literal("standard spawner did not fan out its successful mob"));
            context.assertEquals((long) spawned.size(), uniqueIds,
                    Text.literal("standard-spawner clones reused an entity UUID"));
            context.getWorld().setBlockState(spawnerPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            spawned
                    .forEach(Entity::discard);
            context.complete();
        });
    }

    @GameTest(maxTicks = 15, setupTicks = 15, skyAccess = true)
    public void directMobSpawnStaysLocal(TestContext context) {
        BlockPos source = context.getAbsolutePos(new BlockPos(4, 4, 4));
        BlockPos destination = source.add(16, 0, 0);
        ChunkCopyMod.service().setEnabled(context.getWorld(), true);
        ChunkCopyMod.service().setMode(context.getWorld(), ReplicationMode.LOADED);
        var direct = context.spawnMob(EntityType.ARMADILLO, new Vec3d(4.5, 4.5, 4.5));
        direct.setAiDisabled(true);
        direct.setCustomName(Text.literal("chunkcopy-direct-local"));

        context.waitAndRun(3, () -> {
            long globalNamed = context.getWorld().getEntitiesByType(
                    EntityType.ARMADILLO,
                    entity -> entity.getCustomName() != null
                            && entity.getCustomName().getString().equals("chunkcopy-direct-local")
            ).size();
            int destinationNamed = context.getWorld().getEntitiesByType(
                    EntityType.ARMADILLO,
                    new Box(destination).expand(4.0),
                    entity -> entity.getCustomName() != null
                            && entity.getCustomName().getString().equals("chunkcopy-direct-local")
            ).size();
            context.assertEquals(1L, globalNamed, Text.literal("direct mob did not remain local"));
            context.assertEquals(0, destinationNamed, Text.literal("direct mob was cloned"));
            direct.discard();
            context.complete();
        });
    }

    private static void placeIronBody(TestContext context, BlockPos center) {
        context.setBlockState(center, Blocks.IRON_BLOCK);
        context.setBlockState(center.up(), Blocks.IRON_BLOCK);
        context.setBlockState(center.up().west(), Blocks.IRON_BLOCK);
        context.setBlockState(center.up().east(), Blocks.IRON_BLOCK);
    }

    private static void placeIronBody(ServerWorld world, BlockPos center) {
        world.setBlockState(center, Blocks.IRON_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(center.up(), Blocks.IRON_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(center.up().west(), Blocks.IRON_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(center.up().east(), Blocks.IRON_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
    }
}
