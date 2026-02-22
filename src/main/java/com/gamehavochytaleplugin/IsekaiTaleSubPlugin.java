package com.gamehavochytaleplugin;

import com.gamehavochytaleplugin.systems.IsekaiTaleSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;

final class IsekaiTaleSubPlugin implements GameHavocSubPlugin
{
  private final HytaleLogger logger;

  IsekaiTaleSubPlugin(HytaleLogger logger)
  {
    this.logger = logger;
  }

  @Override
  public String name()
  {
    return "IsekaiTale";
  }

  @Override
  public void setup(GameHavocHytalePlugin plugin)
  {
    IsekaiTaleSystem system = new IsekaiTaleSystem(logger, plugin.getDataDirectory().resolve("IsekaiTale"));
    plugin.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, system::onAddPlayerToWorld);
    plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, system::onPlayerReady);
    logger.at(java.util.logging.Level.INFO)
        .log("IsekaiTale: listening for AddPlayerToWorldEvent + PlayerReadyEvent (persistent world inventory transfers).");
  }
}
