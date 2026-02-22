package com.gamehavochytaleplugin;

import com.gamehavochytaleplugin.systems.SleepyTaleSystem;
import com.hypixel.hytale.logger.HytaleLogger;

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
    plugin.getEntityStoreRegistry().registerSystem(new SleepyTaleSystem(logger));
  }
}
