package com.gamehavochytaleplugin;

import com.gamehavochytaleplugin.systems.IsekaiTaleSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    Path dataDirectory = resolveDataDirectory(plugin);
    IsekaiTaleSystem system = new IsekaiTaleSystem(logger, dataDirectory.resolve("IsekaiTale"));
    plugin.getEventRegistry().registerGlobal(AddWorldEvent.class, system::onAddWorld);
    plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, system::onRemoveWorld);
    plugin.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, system::onAddPlayerToWorld);
    plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, system::onPlayerDisconnect);
    plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, system::onPlayerReady);
    logger.at(java.util.logging.Level.INFO)
        .log("IsekaiTale: listening for world lifecycle + player transfer events (persistent world inventory transfers).");
  }

  private Path resolveDataDirectory(GameHavocHytalePlugin plugin)
  {
    Path legacyPluginDataRoot = plugin.getDataDirectory();
    Path modsDirectory = legacyPluginDataRoot.getParent();
    Path serverDirectory = modsDirectory != null ? modsDirectory.getParent() : null;
    if (serverDirectory == null)
    {
      return legacyPluginDataRoot;
    }

    Path newPluginDataRoot = serverDirectory.resolve("plugin-data").resolve(legacyPluginDataRoot.getFileName().toString());
    migrateLegacyData(legacyPluginDataRoot, newPluginDataRoot);
    return newPluginDataRoot;
  }

  private void migrateLegacyData(Path legacyPluginDataRoot, Path newPluginDataRoot)
  {
    Path legacyIsekaiRoot = legacyPluginDataRoot.resolve("IsekaiTale");
    if (!Files.exists(legacyIsekaiRoot))
    {
      return;
    }

    Path newIsekaiRoot = newPluginDataRoot.resolve("IsekaiTale");
    try
    {
      Files.createDirectories(newPluginDataRoot);
      if (!Files.exists(newIsekaiRoot))
      {
        Files.move(legacyIsekaiRoot, newIsekaiRoot);
        logger.at(java.util.logging.Level.INFO)
            .log("IsekaiTale: moved data directory to " + newPluginDataRoot + ".");
      }
      deleteIfEmpty(legacyPluginDataRoot);
    }
    catch (IOException e)
    {
      logger.at(java.util.logging.Level.WARNING)
          .log("IsekaiTale: failed to migrate legacy data directory from " + legacyPluginDataRoot + " to "
              + newPluginDataRoot + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
    }
  }

  private void deleteIfEmpty(Path directory) throws IOException
  {
    if (!Files.exists(directory))
    {
      return;
    }
    try (var walk = Files.walk(directory))
    {
      if (walk.anyMatch(path -> !path.equals(directory)))
      {
        return;
      }
    }
    Files.deleteIfExists(directory);
  }
}
