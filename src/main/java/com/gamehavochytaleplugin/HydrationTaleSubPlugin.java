package com.gamehavochytaleplugin;

import com.gamehavochytaleplugin.systems.HydrationTaleSystem;
import com.hypixel.hytale.logger.HytaleLogger;

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
    plugin.getChunkStoreRegistry().registerSystem(new HydrationTaleSystem(logger));
  }
}
