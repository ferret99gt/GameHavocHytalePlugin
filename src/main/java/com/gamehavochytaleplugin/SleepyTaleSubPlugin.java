package com.gamehavochytaleplugin;

import com.gamehavochytaleplugin.systems.SleepyTaleSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

final class SleepyTaleSubPlugin implements GameHavocSubPlugin
{
  private final HytaleLogger logger;

  SleepyTaleSubPlugin(HytaleLogger logger)
  {
    this.logger = logger;
  }

  @Override
  public String name()
  {
    return "SleepyTale";
  }

  @Override
  public void setup(GameHavocHytalePlugin plugin)
  {
    SleepyTaleSystem system = new SleepyTaleSystem(logger);
    plugin.getEntityStoreRegistry().registerSystem(system);
    plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, system::onRemoveWorld);
  }
}
