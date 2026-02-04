package com.gamehavochytaleplugin;

import com.hypixel.hytale.builtin.beds.sleep.components.PlayerSomnolence;
import com.hypixel.hytale.builtin.beds.sleep.components.PlayerSleep.Slumber;
import com.hypixel.hytale.builtin.beds.sleep.resources.WorldSlumber;
import com.hypixel.hytale.builtin.beds.sleep.resources.WorldSleep;
import com.hypixel.hytale.builtin.beds.sleep.resources.WorldSomnolence;
import com.hypixel.hytale.builtin.beds.sleep.systems.world.CanSleepInWorld;
import com.hypixel.hytale.builtin.beds.sleep.systems.world.StartSlumberSystem;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

final class SleepyTaleSystem extends DelayedSystem<EntityStore>
{
  static final float CHECK_INTERVAL_SEC = 0.3f;
  static final double REQUIRED_SLEEP_FRACTION = 0.5d; // 50% of players

  private final HytaleLogger logger;
  private int lastReadyPlayers = -1;
  private int lastTotalPlayers = -1;
  private int lastRequiredPlayers = -1;
  private boolean lastMinMet;
  private boolean initialized;

  SleepyTaleSystem(HytaleLogger logger)
  {
    super(CHECK_INTERVAL_SEC);
    this.logger = logger;
  }

  @Override
  public void delayedTick(float delta, int index, Store<EntityStore> store)
  {
    EntityStore entityStore = (EntityStore) store.getExternalData();
    World world = entityStore.getWorld();
    Collection<PlayerRef> players = world.getPlayerRefs();
    if (players.isEmpty())
    {
      lastReadyPlayers = -1;
      lastTotalPlayers = -1;
      lastRequiredPlayers = -1;
      lastMinMet = false;
      initialized = false;
      return;
    }

    if (CanSleepInWorld.check(world).isNegative())
    {
      return;
    }

    WorldSomnolence somnolence = store.getResource(WorldSomnolence.getResourceType());
    WorldSleep state = somnolence.getState();
    if (state != com.hypixel.hytale.builtin.beds.sleep.resources.WorldSleep.Awake.INSTANCE)
    {
      return;
    }

    int totalPlayers = players.size();
    int readyPlayers = countReadyPlayers(store, players);
    int requiredPlayers = computeRequiredPlayers(totalPlayers);
    boolean minMet = readyPlayers >= requiredPlayers;
    if (!initialized)
    {
      lastReadyPlayers = readyPlayers;
      lastTotalPlayers = totalPlayers;
      lastRequiredPlayers = requiredPlayers;
      lastMinMet = minMet;
      initialized = true;
    }
    if (readyPlayers != lastReadyPlayers || totalPlayers != lastTotalPlayers || requiredPlayers != lastRequiredPlayers)
    {
      world.sendMessage(Message.raw(String.format("SleepyTale: %d/%d players sleeping (min %d).",
          readyPlayers, totalPlayers, requiredPlayers)));
      lastReadyPlayers = readyPlayers;
      lastTotalPlayers = totalPlayers;
      lastRequiredPlayers = requiredPlayers;
    }
    if (minMet && !lastMinMet)
    {
      world.sendMessage(Message.raw("SleepyTale: The minimum players for sleeping has been met, good night everyone."));
    }
    lastMinMet = minMet;

    if (!minMet)
    {
      return;
    }

    WorldTimeResource timeResource = store.getResource(WorldTimeResource.getResourceType());
    Instant start = timeResource.getGameTime();
    float wakeUpHour = world.getGameplayConfig().getWorldConfig().getSleepConfig().getWakeUpHour();
    Instant target = computeWakeupInstant(start, wakeUpHour);
    float irlSeconds = computeIrlSeconds(start, target);

    somnolence.setState(new WorldSlumber(start, target, irlSeconds));
    store.forEachEntityParallel(PlayerSomnolence.getComponentType(), (entityIndex, chunk, commandBuffer) ->
    {
      var ref = chunk.getReferenceTo(entityIndex);
      commandBuffer.putComponent(ref, PlayerSomnolence.getComponentType(), Slumber.createComponent(timeResource));
    });

    logger.at(Level.INFO).log("SleepyTale: slumber started (%d/%d ready, required=%d)", readyPlayers, totalPlayers,
        requiredPlayers);
  }

  private static int computeRequiredPlayers(int totalPlayers)
  {
    if (totalPlayers <= 0)
    {
      return 0;
    }
    int required = (int) Math.ceil(totalPlayers * REQUIRED_SLEEP_FRACTION);
    return Math.max(1, required);
  }

  private static int countReadyPlayers(Store<EntityStore> store, Collection<PlayerRef> players)
  {
    int ready = 0;
    for (PlayerRef player : players)
    {
      if (StartSlumberSystem.isReadyToSleep(store, player.getReference()))
      {
        ready++;
      }
    }
    return ready;
  }

  private static Instant computeWakeupInstant(Instant start, float wakeUpHour)
  {
    LocalDateTime startLocal = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
    int hour = (int) wakeUpHour;
    int minute = (int) ((wakeUpHour - hour) * 60.0f);
    LocalDateTime target = startLocal.toLocalDate().atTime(hour, minute);
    if (!startLocal.isBefore(target))
    {
      target = target.plusDays(1);
    }
    return target.toInstant(ZoneOffset.UTC);
  }

  private static float computeIrlSeconds(Instant start, Instant target)
  {
    long millis = java.time.Duration.between(start, target).toMillis();
    long hours = TimeUnit.MILLISECONDS.toHours(millis);
    double scaled = Math.max(3.0d, (double) hours / 6.0d);
    return (float) Math.ceil(scaled);
  }
}
