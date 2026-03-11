package com.gamehavochytaleplugin.systems;

import com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HydrationTaleTrackerSystem extends RefSystem<ChunkStore>
{
  private final Query<ChunkStore> query;
  private final ConcurrentHashMap<UUID, Set<Ref<ChunkStore>>> soilRefsByWorld;

  public HydrationTaleTrackerSystem(ConcurrentHashMap<UUID, Set<Ref<ChunkStore>>> soilRefsByWorld)
  {
    this.soilRefsByWorld = soilRefsByWorld;
    ComponentType<ChunkStore, TilledSoilBlock> tilledSoilType = TilledSoilBlock.getComponentType();
    this.query = Archetype.of(tilledSoilType);
  }

  @Override
  public Query<ChunkStore> getQuery()
  {
    return query;
  }

  @Override
  public void onEntityAdded(Ref<ChunkStore> ref, AddReason reason, Store<ChunkStore> store,
      CommandBuffer<ChunkStore> commandBuffer)
  {
    UUID worldUuid = getWorldUuid(store);
    if (worldUuid == null || ref == null || !ref.isValid())
    {
      return;
    }
    soilRefsByWorld.computeIfAbsent(worldUuid, ignored -> ConcurrentHashMap.newKeySet()).add(ref);
  }

  @Override
  public void onEntityRemove(Ref<ChunkStore> ref, RemoveReason reason, Store<ChunkStore> store,
      CommandBuffer<ChunkStore> commandBuffer)
  {
    UUID worldUuid = getWorldUuid(store);
    if (worldUuid == null || ref == null)
    {
      return;
    }
    Set<Ref<ChunkStore>> refs = soilRefsByWorld.get(worldUuid);
    if (refs == null)
    {
      return;
    }
    refs.remove(ref);
    if (refs.isEmpty())
    {
      soilRefsByWorld.remove(worldUuid, refs);
    }
  }

  private static UUID getWorldUuid(Store<ChunkStore> store)
  {
    if (store == null)
    {
      return null;
    }

    ChunkStore chunkStore = (ChunkStore) store.getExternalData();
    if (chunkStore == null)
    {
      return null;
    }

    World world = chunkStore.getWorld();
    if (world == null || world.getWorldConfig() == null)
    {
      return null;
    }
    return world.getWorldConfig().getUuid();
  }
}
