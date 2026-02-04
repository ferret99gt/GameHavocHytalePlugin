package com.gamehavochytaleplugin;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Level;

public final class GameHavocHytalePlugin extends JavaPlugin
{
  public GameHavocHytalePlugin(JavaPluginInit init)
  {
    super(init);
  }

  @Override
  protected void setup()
  {
    getLogger().at(Level.INFO).log("GameHavocHytalePlugin: setup()");
    getChunkStoreRegistry().registerSystem(new HydrationTaleSystem(getLogger()));
    getEntityStoreRegistry().registerSystem(new SleepyTaleSystem(getLogger()));
  }
}
