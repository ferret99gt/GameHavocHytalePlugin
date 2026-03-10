package com.gamehavochytaleplugin;

import com.gamehavochytaleplugin.systems.IsekaiTaleSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

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
    plugin.getEventRegistry().registerGlobal(AddWorldEvent.class, system::onAddWorld);
    plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, system::onRemoveWorld);
    plugin.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, system::onAddPlayerToWorld);
    plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, system::onPlayerDisconnect);
    plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, system::onPlayerReady);
    logger.at(java.util.logging.Level.INFO)
        .log("IsekaiTale: listening for world lifecycle + player transfer events (persistent world inventory transfers).");
  }
}
