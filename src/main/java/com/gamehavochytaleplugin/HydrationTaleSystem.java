package com.gamehavochytaleplugin;

import com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ReadWriteQuery;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class HydrationTaleSystem extends EntityTickingSystem<ChunkStore>
{
  static final String[] DEFAULT_FLUID_KEYS =
  { "Water", "Water_Source", "Water_Finite" };
  static final int NOT_FOUND_ID = Integer.MIN_VALUE;

  private final HytaleLogger logger;
  private final Query<ChunkStore> query;
  private final com.hypixel.hytale.component.ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockStateInfoType;
  private final com.hypixel.hytale.component.ComponentType<ChunkStore, TilledSoilBlock> tilledSoilType;
  private final BlockTypeAssetMap<String, BlockType> blockTypeMap;
  private final IntOpenHashSet waterFluidIds = new IntOpenHashSet();
  private boolean waterFluidIdsReady;
  private final AtomicBoolean tickLogged = new AtomicBoolean(false);
  private final AtomicBoolean changeLogged = new AtomicBoolean(false);
  private final AtomicBoolean probeLogged = new AtomicBoolean(false);
  private float timeAccumulator;

  HydrationTaleSystem(HytaleLogger logger)
  {
    this.logger = logger;
    this.blockStateInfoType = BlockModule.get().getBlockStateInfoComponentType();
    this.tilledSoilType = TilledSoilBlock.getComponentType();
    this.blockTypeMap = BlockType.getAssetMap();
    var archetype = Archetype.of(this.blockStateInfoType, this.tilledSoilType);
    this.query = new ReadWriteQuery<>(archetype, archetype);
  }

  @Override
  public Query<ChunkStore> getQuery()
  {
    return query;
  }

  @Override
  public void tick(float delta, int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store,
      CommandBuffer<ChunkStore> commandBuffer)
  {
    timeAccumulator += delta;
    if (timeAccumulator < 1.0f)
    {
      return;
    }
    timeAccumulator = 0.0f;

    if (logger != null && tickLogged.compareAndSet(false, true))
    {
      logger.at(Level.FINEST).log("HydrationTale: HydrationTaleSystem ticking.");
    }
    if (!ensureWaterFluidIds())
    {
      return;
    }

    var blockStateInfo = chunk.getComponent(index, blockStateInfoType);
    var soil = chunk.getComponent(index, tilledSoilType);
    if (blockStateInfo == null || soil == null)
    {
      return;
    }

    Ref<ChunkStore> chunkRef = blockStateInfo.getChunkRef();
    if (chunkRef == null || !chunkRef.isValid())
    {
      return;
    }

    BlockChunk blockChunk = commandBuffer.getComponent(chunkRef, BlockChunk.getComponentType());
    if (blockChunk == null)
    {
      return;
    }

    WorldChunk worldChunk = commandBuffer.getComponent(chunkRef, WorldChunk.getComponentType());
    if (worldChunk == null)
    {
      return;
    }

    int blockIndex = blockStateInfo.getIndex();
    int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
    int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
    int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
    int sectionY = localY & ChunkUtil.SIZE_MINUS_1;
    if (localY < 0 || localY >= ChunkUtil.HEIGHT)
    {
      return;
    }

    BlockSection blockSection = blockChunk.getSectionAtBlockY(localY);
    if (blockSection == null)
    {
      return;
    }

    int worldX = (worldChunk.getX() << ChunkUtil.BITS) + localX;
    int worldZ = (worldChunk.getZ() << ChunkUtil.BITS) + localZ;
    int worldY = localY;

    boolean hasWater = hasAdjacentWater(commandBuffer, store.getExternalData(), waterFluidIds, worldX, worldY, worldZ);
    if (logger != null && probeLogged.compareAndSet(false, true))
    {
      int westId = getFluidIdAt(commandBuffer, store.getExternalData(), worldX - 1, worldY, worldZ);
      int eastId = getFluidIdAt(commandBuffer, store.getExternalData(), worldX + 1, worldY, worldZ);
      int northId = getFluidIdAt(commandBuffer, store.getExternalData(), worldX, worldY, worldZ - 1);
      int southId = getFluidIdAt(commandBuffer, store.getExternalData(), worldX, worldY, worldZ + 1);
      int soilId = worldChunk.getBlock(localX, worldY, localZ);
      BlockType soilType = blockTypeMap.getAsset(soilId);
      String soilKey = soilType == null ? "null" : soilType.getId();
      int altWorldX = (worldChunk.getX() << ChunkUtil.BITS) + localX;
      int altWorldZ = (worldChunk.getZ() << ChunkUtil.BITS) + localZ;
      logger.at(Level.FINEST).log("HydrationTale: probe at %d,%d,%d water=%s ids=[W:%d E:%d N:%d S:%d] waterIds=%s", worldX,
          worldY, worldZ, hasWater, westId, eastId, northId, southId, waterFluidIds);
      logger.at(Level.FINEST).log("HydrationTale: probe coords chunkX=%d chunkZ=%d local=%d,%d altWorld=%d,%d soil=%s(%d)",
          worldChunk.getX(), worldChunk.getZ(), localX, localZ, altWorldX, altWorldZ, soilKey, soilId);
      logNeighborDetail(commandBuffer, store.getExternalData(), worldX, worldY, worldZ - 1, "N");
    }
    boolean previousExternalWater = soil.hasExternalWater();
    if (previousExternalWater != hasWater)
    {
      soil.setExternalWater(hasWater);
      blockSection.setTicking(localX, sectionY, localZ, true);
      if (logger != null && changeLogged.compareAndSet(false, true))
      {
        logger.at(Level.FINEST).log("HydrationTale: first externalWater change -> %s.", hasWater);
      }
    }

    int currentBlockId = worldChunk.getBlock(localX, worldY, localZ);
    BlockType currentBlockType = blockTypeMap.getAsset(currentBlockId);
    if (currentBlockType == null)
    {
      return;
    }
    Instant now = getGameTime(worldChunk.getWorld());
    if (now == null)
    {
      return;
    }
    String nextBlockKey = soil.computeBlockType(now, currentBlockType);
    if (nextBlockKey == null || nextBlockKey.equals(currentBlockType.getId()))
    {
      return;
    }
    int nextBlockId = blockTypeMap.getIndex(nextBlockKey);
    if (nextBlockId == Integer.MIN_VALUE)
    {
      return;
    }

    if (nextBlockId != currentBlockId)
    {
      BlockType nextBlockType = blockTypeMap.getAsset(nextBlockId);
      if (nextBlockType == null)
      {
        return;
      }
      int rotationIndex = blockSection.getRotationIndex(localX, sectionY, localZ);
      commandBuffer.run(chunkUpdate ->
      {
        WorldChunk targetChunk = chunkUpdate.getComponent(chunkRef, WorldChunk.getComponentType());
        if (targetChunk != null)
        {
          targetChunk.setBlock(localX, worldY, localZ, nextBlockId, nextBlockType, rotationIndex, 0, 0);
        }
      });
      soil.setExternalWater(hasWater);
      logger.at(Level.FINEST).log("HydrationTale: state change at %d,%d,%d water=%s id %d->%d key %s->%s", worldX, worldY,
          worldZ, hasWater, currentBlockId, nextBlockId, currentBlockType.getId(), nextBlockKey);
    }
    else if (previousExternalWater != hasWater)
    {
      logger.at(Level.FINEST).log("HydrationTale: no state change at %d,%d,%d water=%s id=%d key=%s computed=%s", worldX,
          worldY, worldZ, hasWater, currentBlockId, currentBlockType.getId(), nextBlockKey);
    }

    if (previousExternalWater != hasWater)
    {
      chunk.setComponent(index, tilledSoilType, soil);
    }
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
        logger.at(Level.WARNING).log("GameHavocHyTaleMods: fluid '%s' not found.", key);
        continue;
      }
      waterFluidIds.add(fluidId);
    }
    waterFluidIdsReady = !waterFluidIds.isEmpty();
    return waterFluidIdsReady;
  }

  private static boolean hasAdjacentWater(CommandBuffer<ChunkStore> commandBuffer, ChunkStore chunkStore,
      IntOpenHashSet waterIds, int worldX, int worldY, int worldZ)
  {
    return isWaterAt(commandBuffer, chunkStore, waterIds, worldX - 1, worldY, worldZ)
        || isWaterAt(commandBuffer, chunkStore, waterIds, worldX + 1, worldY, worldZ)
        || isWaterAt(commandBuffer, chunkStore, waterIds, worldX, worldY, worldZ - 1)
        || isWaterAt(commandBuffer, chunkStore, waterIds, worldX, worldY, worldZ + 1);
  }

  private static boolean isWaterAt(CommandBuffer<ChunkStore> commandBuffer, ChunkStore chunkStore, IntOpenHashSet waterIds,
      int worldX, int worldY, int worldZ)
  {
    int fluidId = getFluidIdAt(commandBuffer, chunkStore, worldX, worldY, worldZ);
    return waterIds.contains(fluidId);
  }

  private static int getFluidIdAt(CommandBuffer<ChunkStore> commandBuffer, ChunkStore chunkStore, int worldX, int worldY,
      int worldZ)
  {
    if (chunkStore == null || worldY < 0 || worldY >= ChunkUtil.HEIGHT)
    {
      return NOT_FOUND_ID;
    }
    int chunkX = ChunkUtil.chunkCoordinate(worldX);
    int chunkZ = ChunkUtil.chunkCoordinate(worldZ);
    long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
    Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
    if (chunkRef == null || !chunkRef.isValid())
    {
      return NOT_FOUND_ID;
    }
    WorldChunk chunk = commandBuffer.getComponent(chunkRef, WorldChunk.getComponentType());
    if (chunk == null)
    {
      return NOT_FOUND_ID;
    }
    int localX = ChunkUtil.localCoordinate((long) worldX);
    int localZ = ChunkUtil.localCoordinate((long) worldZ);
    return chunk.getFluidId(localX, worldY, localZ);
  }

  private void logNeighborDetail(CommandBuffer<ChunkStore> commandBuffer, ChunkStore chunkStore, int worldX, int worldY,
      int worldZ, String label)
  {
    if (logger == null || chunkStore == null)
    {
      return;
    }
    logBlockFluidAt(commandBuffer, chunkStore, worldX, worldY, worldZ, label);
    logBlockFluidAt(commandBuffer, chunkStore, worldX, worldY + 1, worldZ, label + "+Y");
    logBlockFluidAt(commandBuffer, chunkStore, worldX, worldY - 1, worldZ, label + "-Y");
  }

  private void logBlockFluidAt(CommandBuffer<ChunkStore> commandBuffer, ChunkStore chunkStore, int worldX, int worldY,
      int worldZ, String label)
  {
    if (worldY < 0 || worldY >= ChunkUtil.HEIGHT)
    {
      logger.at(Level.FINEST).log("HydrationTale: %s detail at %d,%d,%d -> out of range", label, worldX, worldY, worldZ);
      return;
    }
    int chunkX = ChunkUtil.chunkCoordinate(worldX);
    int chunkZ = ChunkUtil.chunkCoordinate(worldZ);
    long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
    Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
    if (chunkRef == null || !chunkRef.isValid())
    {
      logger.at(Level.FINEST).log("HydrationTale: %s detail at %d,%d,%d -> no chunk", label, worldX, worldY, worldZ);
      return;
    }
    WorldChunk chunk = commandBuffer.getComponent(chunkRef, WorldChunk.getComponentType());
    if (chunk == null)
    {
      logger.at(Level.FINEST).log("HydrationTale: %s detail at %d,%d,%d -> no WorldChunk", label, worldX, worldY, worldZ);
      return;
    }
    int localX = ChunkUtil.localCoordinate((long) worldX);
    int localZ = ChunkUtil.localCoordinate((long) worldZ);
    int fluidId = chunk.getFluidId(localX, worldY, localZ);
    byte fluidLevel = chunk.getFluidLevel(localX, worldY, localZ);
    int blockId = chunk.getBlock(localX, worldY, localZ);
    BlockType blockType = blockTypeMap.getAsset(blockId);
    String blockKey = blockType == null ? "null" : blockType.getId();
    logger.at(Level.FINEST).log("HydrationTale: %s detail at %d,%d,%d blockId=%d blockKey=%s fluidId=%d fluidLevel=%d", label,
        worldX, worldY, worldZ, blockId, blockKey, fluidId, fluidLevel);
  }

  private Instant getGameTime(World world)
  {
    WorldTimeResource timeResource = world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType());
    if (timeResource == null)
    {
      return null;
    }
    return timeResource.getGameTime();
  }
}
