package com.gamehavochytaleplugin.systems;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class HydrationTaleSystem extends DelayedSystem<EntityStore>
{
  static final float CHECK_INTERVAL_SEC = 0.5f;
  static final String[] DEFAULT_FLUID_KEYS =
  { "Water", "Water_Source", "Water_Finite" };
  static final int NOT_FOUND_ID = Integer.MIN_VALUE;

  private final HytaleLogger logger;
  private final ComponentType<ChunkStore, TilledSoilBlock> tilledSoilType;
  private final ComponentType<ChunkStore, BlockStateInfo> blockStateInfoType;
  private final BlockTypeAssetMap<String, BlockType> blockTypeMap;
  private final ConcurrentHashMap<UUID, Set<Ref<ChunkStore>>> soilRefsByWorld;
  private final IntOpenHashSet waterFluidIds = new IntOpenHashSet();
  private boolean waterFluidIdsReady;

  public HydrationTaleSystem(HytaleLogger logger, ConcurrentHashMap<UUID, Set<Ref<ChunkStore>>> soilRefsByWorld)
  {
    super(CHECK_INTERVAL_SEC);
    this.logger = logger;
    this.tilledSoilType = TilledSoilBlock.getComponentType();
    this.blockStateInfoType = BlockModule.get().getBlockStateInfoComponentType();
    this.blockTypeMap = BlockType.getAssetMap();
    this.soilRefsByWorld = soilRefsByWorld;
  }

  public void onRemoveWorld(RemoveWorldEvent event)
  {
    if (event == null || event.getWorld() == null || event.getWorld().getWorldConfig() == null)
    {
      return;
    }
    soilRefsByWorld.remove(event.getWorld().getWorldConfig().getUuid());
  }

  @Override
  public void delayedTick(float delta, int index, Store<EntityStore> store)
  {
    EntityStore entityStore = (EntityStore) store.getExternalData();
    if (entityStore == null)
    {
      return;
    }

    World world = entityStore.getWorld();
    if (world == null)
    {
      return;
    }

    UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
    if (worldUuid == null)
    {
      return;
    }

    if (!ensureWaterFluidIds())
    {
      return;
    }

    Instant now = getGameTime(store);
    if (now == null)
    {
      return;
    }

    Set<Ref<ChunkStore>> soilRefs = soilRefsByWorld.get(worldUuid);
    if (soilRefs == null || soilRefs.isEmpty())
    {
      return;
    }

    ChunkStore chunkStore = world.getChunkStore();
    if (chunkStore == null)
    {
      return;
    }

    Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
    if (chunkStoreStore == null)
    {
      return;
    }

    for (Ref<ChunkStore> soilRef : soilRefs)
    {
      if (soilRef == null || !soilRef.isValid())
      {
        soilRefs.remove(soilRef);
        continue;
      }

      TilledSoilBlock soil = chunkStoreStore.getComponent(soilRef, tilledSoilType);
      if (soil == null)
      {
        soilRefs.remove(soilRef);
        continue;
      }

      BlockStateInfo blockStateInfo = chunkStoreStore.getComponent(soilRef, blockStateInfoType);
      if (blockStateInfo == null)
      {
        soilRefs.remove(soilRef);
        continue;
      }

      Ref<ChunkStore> chunkRef = blockStateInfo.getChunkRef();
      if (chunkRef == null || !chunkRef.isValid())
      {
        soilRefs.remove(soilRef);
        continue;
      }

      WorldChunk worldChunk = chunkStoreStore.getComponent(chunkRef, WorldChunk.getComponentType());
      if (worldChunk == null)
      {
        continue;
      }

      processSoil(world, worldChunk, chunkStoreStore, blockStateInfo.getIndex(), soil, now);
    }
  }

  private void processSoil(World world, WorldChunk worldChunk, Store<ChunkStore> chunkStoreStore, int blockIndex,
      TilledSoilBlock soil, Instant now)
  {
    int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
    int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
    int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
    if (localY < 0 || localY >= ChunkUtil.HEIGHT)
    {
      return;
    }

    boolean hasWater = hasAdjacentWater(world, worldChunk, localX, localY, localZ);
    boolean previousExternalWater = soil.hasExternalWater();
    if (previousExternalWater != hasWater)
    {
      soil.setExternalWater(hasWater);
      worldChunk.setTicking(localX, localY, localZ, true);
    }

    int currentBlockId = worldChunk.getBlock(localX, localY, localZ);
    BlockType currentBlockType = blockTypeMap.getAsset(currentBlockId);
    if (currentBlockType == null)
    {
      return;
    }

    String nextBlockKey = soil.computeBlockType(now, currentBlockType);
    if (nextBlockKey == null)
    {
      return;
    }

    if (nextBlockKey.equals(currentBlockType.getId()))
    {
      if (previousExternalWater != hasWater)
      {
        applySoilComponent(chunkStoreStore, worldChunk, blockIndex, localX, localY, localZ, soil);
        logger.at(Level.FINEST).log("HydrationTale: state change at %d,%d,%d water=%s id=%d key=%s", toWorldX(worldChunk, localX),
            localY, toWorldZ(worldChunk, localZ), hasWater, currentBlockId, currentBlockType.getId());
      }
      return;
    }

    int nextBlockId = blockTypeMap.getIndex(nextBlockKey);
    if (nextBlockId == Integer.MIN_VALUE)
    {
      return;
    }

    BlockType nextBlockType = blockTypeMap.getAsset(nextBlockId);
    if (nextBlockType == null)
    {
      return;
    }

    int rotationIndex = worldChunk.getRotationIndex(localX, localY, localZ);
    worldChunk.setBlock(localX, localY, localZ, nextBlockId, nextBlockType, rotationIndex, 0, 0);
    applySoilComponent(chunkStoreStore, worldChunk, blockIndex, localX, localY, localZ, soil);
    logger.at(Level.FINEST).log("HydrationTale: block/state change at %d,%d,%d water=%s id %d->%d key %s->%s",
        toWorldX(worldChunk, localX), localY, toWorldZ(worldChunk, localZ), hasWater, currentBlockId, nextBlockId,
        currentBlockType.getId(), nextBlockKey);
  }

  private boolean ensureWaterFluidIds()
  {
    if (waterFluidIdsReady)
    {
      return true;
    }

    var map = Fluid.getAssetMap();
    waterFluidIds.clear();
    for (String key : DEFAULT_FLUID_KEYS)
    {
      int fluidId = map.getIndexOrDefault(key, Fluid.UNKNOWN_ID);
      if (fluidId == Fluid.UNKNOWN_ID)
      {
        logger.at(Level.WARNING).log("HydrationTale: fluid '%s' not found.", key);
        continue;
      }
      waterFluidIds.add(fluidId);
    }
    waterFluidIdsReady = !waterFluidIds.isEmpty();
    return waterFluidIdsReady;
  }

  private boolean hasAdjacentWater(World world, WorldChunk worldChunk, int localX, int worldY, int localZ)
  {
    if (localX > 0 && waterFluidIds.contains(worldChunk.getFluidId(localX - 1, worldY, localZ)))
    {
      return true;
    }
    if (localX < ChunkUtil.SIZE_MINUS_1 && waterFluidIds.contains(worldChunk.getFluidId(localX + 1, worldY, localZ)))
    {
      return true;
    }
    if (localZ > 0 && waterFluidIds.contains(worldChunk.getFluidId(localX, worldY, localZ - 1)))
    {
      return true;
    }
    if (localZ < ChunkUtil.SIZE_MINUS_1 && waterFluidIds.contains(worldChunk.getFluidId(localX, worldY, localZ + 1)))
    {
      return true;
    }

    int worldX = toWorldX(worldChunk, localX);
    int worldZ = toWorldZ(worldChunk, localZ);
    if (localX == 0 && isWaterAt(world, worldX - 1, worldY, worldZ))
    {
      return true;
    }
    if (localX == ChunkUtil.SIZE_MINUS_1 && isWaterAt(world, worldX + 1, worldY, worldZ))
    {
      return true;
    }
    if (localZ == 0 && isWaterAt(world, worldX, worldY, worldZ - 1))
    {
      return true;
    }
    return localZ == ChunkUtil.SIZE_MINUS_1 && isWaterAt(world, worldX, worldY, worldZ + 1);
  }

  private boolean isWaterAt(World world, int worldX, int worldY, int worldZ)
  {
    int fluidId = getFluidIdAt(world, worldX, worldY, worldZ);
    return waterFluidIds.contains(fluidId);
  }

  private static int getFluidIdAt(World world, int worldX, int worldY, int worldZ)
  {
    if (world == null || worldY < 0 || worldY >= ChunkUtil.HEIGHT)
    {
      return NOT_FOUND_ID;
    }

    long chunkIndex = ChunkUtil.indexChunk(ChunkUtil.chunkCoordinate(worldX), ChunkUtil.chunkCoordinate(worldZ));
    WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
    if (chunk == null)
    {
      return NOT_FOUND_ID;
    }

    int localX = ChunkUtil.localCoordinate((long) worldX);
    int localZ = ChunkUtil.localCoordinate((long) worldZ);
    return chunk.getFluidId(localX, worldY, localZ);
  }

  private void applySoilComponent(Store<ChunkStore> store, WorldChunk worldChunk, int blockIndex, int localX, int worldY,
      int localZ, TilledSoilBlock soil)
  {
    Ref<ChunkStore> entityRef = BlockModule.ensureBlockEntity(worldChunk, localX, worldY, localZ);
    if (entityRef == null || !entityRef.isValid())
    {
      return;
    }
    store.putComponent(entityRef, tilledSoilType, soil);
  }

  private static int toWorldX(WorldChunk worldChunk, int localX)
  {
    return (worldChunk.getX() << ChunkUtil.BITS) + localX;
  }

  private static int toWorldZ(WorldChunk worldChunk, int localZ)
  {
    return (worldChunk.getZ() << ChunkUtil.BITS) + localZ;
  }

  private static Instant getGameTime(Store<EntityStore> store)
  {
    WorldTimeResource timeResource = store.getResource(WorldTimeResource.getResourceType());
    if (timeResource == null)
    {
      return null;
    }
    return timeResource.getGameTime();
  }
}
