package com.gamehavochytaleplugin;

import com.gamehavochytaleplugin.systems.HydrationTaleSystem;
import com.gamehavochytaleplugin.systems.HydrationTaleTrackerSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class HydrationTaleSubPlugin implements GameHavocSubPlugin
{
  private final HytaleLogger logger;

  HydrationTaleSubPlugin(HytaleLogger logger)
  {
    this.logger = logger;
  }

  @Override
  public String name()
  {
    return "HydrationTale";
  }

  @Override
  public void setup(GameHavocHytalePlugin plugin)
  {
    ConcurrentHashMap<UUID, Set<com.hypixel.hytale.component.Ref<ChunkStore>>> soilRefsByWorld = new ConcurrentHashMap<>();
    HydrationTaleSystem system = new HydrationTaleSystem(logger, soilRefsByWorld);
    plugin.getChunkStoreRegistry().registerSystem(new HydrationTaleTrackerSystem(soilRefsByWorld));
    plugin.getEntityStoreRegistry().registerSystem(system);
    plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, system::onRemoveWorld);
  }
}
