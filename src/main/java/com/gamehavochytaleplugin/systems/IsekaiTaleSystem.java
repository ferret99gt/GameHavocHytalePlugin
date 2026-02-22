package com.gamehavochytaleplugin.systems;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonString;

public final class IsekaiTaleSystem
{
  private static final String INSTANCE_WORLD_PREFIX = "instance-";
  private static final boolean ENFORCE_WORLD_DEFAULT_GAMEMODE = true;
  private static final int SNAPSHOT_VERSION = 1;
  private static final String[] INVENTORY_SECTIONS =
  { "HOTBAR", "STORAGE", "ARMOR", "UTILITY", "TOOLS", "BACKPACK" };

  private final HytaleLogger logger;
  private final Path stashRoot;
  private final ConcurrentHashMap<UUID, WorldIdentity> worldsByUuid = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

  public IsekaiTaleSystem(HytaleLogger logger, Path dataDirectory)
  {
    this.logger = logger;
    this.stashRoot = dataDirectory.resolve("stash");
  }

  public void onAddPlayerToWorld(AddPlayerToWorldEvent event)
  {
    if (event == null)
    {
      return;
    }

    World destinationWorld = event.getWorld();
    if (event.getHolder() == null || destinationWorld == null)
    {
      return;
    }

    PlayerRef playerRef;
    try
    {
      playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
    }
    catch (Throwable t)
    {
      logWarn("IsekaiTale: failed to read PlayerRef from AddPlayerToWorldEvent: " + t.getClass().getSimpleName());
      return;
    }
    if (playerRef == null)
    {
      return;
    }

    UUID playerUuid = safeGet(playerRef::getUuid);
    if (playerUuid == null)
    {
      return;
    }

    UUID sourceWorldUuid = safeGet(playerRef::getWorldUuid);
    String playerName = safeString(() -> playerRef.getUsername(), playerUuid.toString());
    String destWorldName = safeString(destinationWorld::getName, "unknown");
    UUID destWorldUuid = safeGet(() -> destinationWorld.getWorldConfig().getUuid());
    if (destWorldUuid == null)
    {
      logWarn("IsekaiTale: destination world has null UUID for " + destWorldName + "; skipping transfer logic.");
      return;
    }

    worldsByUuid.put(destWorldUuid, new WorldIdentity(destWorldUuid, destWorldName, isInstanceWorld(destWorldName)));

    if (sourceWorldUuid == null)
    {
      logFine("IsekaiTale: login/add with no source world for " + playerName + " -> " + destWorldName + ".");
      return;
    }

    if (sourceWorldUuid.equals(destWorldUuid))
    {
      logFine("IsekaiTale: source and destination world are the same for " + playerName + " (" + destWorldName + ").");
      return;
    }

    WorldIdentity sourceWorld = worldsByUuid.get(sourceWorldUuid);
    if (sourceWorld == null)
    {
      logWarn("IsekaiTale: unknown source world UUID " + sourceWorldUuid + " for " + playerName
          + " -> " + destWorldName + "; skipping inventory transfer.");
      return;
    }

    if (sourceWorld.instance() || isInstanceWorld(destWorldName))
    {
      logFine("IsekaiTale: ignoring instance transfer for " + playerName + " (" + sourceWorld.name() + " -> "
          + destWorldName + ").");
      return;
    }

    pendingTransfers.put(playerUuid, new PendingTransfer(playerUuid, playerName, sourceWorld.uuid(), destWorldUuid, destWorldName));
    logFine("IsekaiTale: queued transfer for " + playerName + " (" + sourceWorld.name() + " -> " + destWorldName + ").");
  }

  public void onPlayerReady(PlayerReadyEvent event)
  {
    if (event == null)
    {
      return;
    }

    Player player = event.getPlayer();
    Ref<EntityStore> playerEntityRef = event.getPlayerRef();
    if (player == null)
    {
      return;
    }

    World destinationWorld = safeGet(player::getWorld);
    if (destinationWorld == null)
    {
      return;
    }

    Store<EntityStore> store = destinationWorld.getEntityStore().getStore();
    PlayerRef playerRef = null;
    try
    {
      if (playerEntityRef != null)
      {
        playerRef = store.getComponent(playerEntityRef, PlayerRef.getComponentType());
      }
    }
    catch (Throwable ignored)
    {
    }
    if (playerRef == null)
    {
      return;
    }

    UUID playerUuid = safeGet(playerRef::getUuid);
    if (playerUuid == null)
    {
      return;
    }

    PendingTransfer pending = pendingTransfers.remove(playerUuid);
    if (pending == null)
    {
      return;
    }

    UUID actualDestWorldUuid = safeGet(() -> destinationWorld.getWorldConfig().getUuid());
    if (actualDestWorldUuid == null || !actualDestWorldUuid.equals(pending.destWorldUuid()))
    {
      pendingTransfers.put(playerUuid, pending);
      return;
    }

    final PlayerRef readyPlayerRef = playerRef;
    String playerName = safeString(() -> readyPlayerRef.getUsername(), pending.playerName());
    processPendingTransfer(pending, playerName, player, playerEntityRef, store, destinationWorld);
  }

  private void processPendingTransfer(
      PendingTransfer pending,
      String playerName,
      Player player,
      Ref<EntityStore> playerEntityRef,
      Store<EntityStore> store,
      World destinationWorld)
  {
    UUID sourceWorldUuid = pending.sourceWorldUuid();
    UUID destWorldUuid = pending.destWorldUuid();
    String destWorldName = pending.destWorldName();

    WorldIdentity sourceWorld = worldsByUuid.get(sourceWorldUuid);
    if (sourceWorld == null)
    {
      logWarn("IsekaiTale: unknown source world UUID " + sourceWorldUuid + " for " + playerName
          + " -> " + destWorldName + "; skipping inventory transfer.");
      return;
    }

    Inventory inventory = player.getInventory();
    if (inventory == null)
    {
      logWarn("IsekaiTale: player inventory missing for " + playerName + "; cannot transfer.");
      return;
    }

    int sourceStacks = countNonEmptyStacks(inventory);
    if (!stashInventory(inventory, pending.playerUuid(), sourceWorld.uuid()))
    {
      logWarn("IsekaiTale: failed to stash inventory for " + playerName + " leaving " + sourceWorld.name()
          + "; inventory left untouched.");
      return;
    }

    inventory.clear();

    if (ENFORCE_WORLD_DEFAULT_GAMEMODE && playerEntityRef != null)
    {
      applyWorldDefaultGameMode(player, playerEntityRef, store, destinationWorld, playerName);
    }

    RestoreResult restore = restoreInventory(inventory, pending.playerUuid(), destWorldUuid);
    logger.at(Level.INFO).log("IsekaiTale: transfer " + playerName
        + " " + sourceWorld.name() + " -> " + destWorldName
        + " stashedStacks=" + sourceStacks
        + " restoredStacks=" + restore.restoredStacks()
        + " restoredQty=" + restore.restoredQuantity()
        + " leftovers=" + restore.leftoverStacks()
        + " gamemode=" + safeGameModeName(player));
  }

  private void applyWorldDefaultGameMode(
      Player player,
      Ref<EntityStore> playerEntityRef,
      Store<EntityStore> store,
      World destinationWorld,
      String playerName)
  {
    try
    {
      WorldConfig worldConfig = destinationWorld.getWorldConfig();
      GameMode target = worldConfig == null ? null : worldConfig.getGameMode();
      if (target == null)
      {
        return;
      }
      GameMode current = player.getGameMode();
      if (current != target)
      {
        Player.setGameMode(playerEntityRef, target, store);
        logFine("IsekaiTale: set gamemode for " + playerName + " to " + target + " in " + destinationWorld.getName()
            + ".");
      }
    }
    catch (Throwable t)
    {
      logWarn("IsekaiTale: failed to apply world gamemode for " + playerName + ": " + t.getClass().getSimpleName()
          + " " + t.getMessage());
    }
  }

  private boolean stashInventory(Inventory inventory, UUID playerUuid, UUID worldUuid)
  {
    try
    {
      Files.createDirectories(stashRoot.resolve(playerUuid.toString()), new FileAttribute[0]);
      BsonDocument snapshot = buildSectionSnapshot(playerUuid, worldUuid, inventory);
      Files.writeString(stashPath(playerUuid, worldUuid), snapshot.toJson(), StandardCharsets.UTF_8, new OpenOption[0]);
      return true;
    }
    catch (Throwable t)
    {
      logWarn("IsekaiTale: stash write failed for player=" + playerUuid + " world=" + worldUuid + ": "
          + t.getClass().getSimpleName() + " " + t.getMessage());
      return false;
    }
  }

  private RestoreResult restoreInventory(Inventory inventory, UUID playerUuid, UUID worldUuid)
  {
    Path path = stashPath(playerUuid, worldUuid);
    if (!Files.isRegularFile(path, new LinkOption[0]))
    {
      return RestoreResult.NONE;
    }

    try
    {
      String json = Files.readString(path, StandardCharsets.UTF_8);
      BsonDocument snapshot = BsonDocument.parse(json);
      if (!(snapshot.get("sections") instanceof BsonDocument sections))
      {
        logWarn("IsekaiTale: stash file has no section data (unsupported format) for player=" + playerUuid
            + " world=" + worldUuid + ".");
        return RestoreResult.ERROR;
      }
      RestoreResult restore = restoreSectionedInventory(inventory, snapshot, sections);

      if (restore.leftovers().isEmpty())
      {
        Files.deleteIfExists(path);
      }
      else
      {
        BsonDocument leftoverSnapshot = buildOverflowSnapshot(playerUuid, worldUuid, restore.leftovers());
        Files.writeString(path, leftoverSnapshot.toJson(), StandardCharsets.UTF_8, new OpenOption[0]);
      }

      return new RestoreResult(restore.restoredStacks(), restore.restoredQuantity(), restore.leftoverStacks(),
          List.of());
    }
    catch (Throwable t)
    {
      logWarn("IsekaiTale: restore failed for player=" + playerUuid + " world=" + worldUuid + ": "
          + t.getClass().getSimpleName() + " " + t.getMessage());
      return RestoreResult.ERROR;
    }
  }

  private RestoreResult restoreSectionedInventory(Inventory inventory, BsonDocument snapshot, BsonDocument sections)
  {
    ArrayList<ItemStack> leftovers = new ArrayList<>();
    int[] restoredStacks = new int[1];
    int[] restoredQty = new int[1];

    for (String sectionName : INVENTORY_SECTIONS)
    {
      BsonDocument sectionDoc = sections.getDocument(sectionName, null);
      if (sectionDoc == null)
      {
        continue;
      }
      ItemContainer container = getSectionContainer(inventory, sectionName);
      BsonDocument slots = sectionDoc.getDocument("slots", null);
      if (slots == null)
      {
        continue;
      }

      if (container == null)
      {
        collectSectionItemsAsLeftovers(slots, leftovers);
        continue;
      }

      short capacity = 0;
      try
      {
        capacity = container.getCapacity();
      }
      catch (Throwable ignored)
      {
      }

      for (String slotKey : slots.keySet())
      {
        ItemStack stack = null;
        try
        {
          if (slots.get(slotKey) instanceof BsonDocument slotDoc)
          {
            stack = bsonToStack(slotDoc);
          }
        }
        catch (Throwable ignored)
        {
        }
        if (stack == null || stack.isEmpty())
        {
          continue;
        }

        short slotIndex;
        try
        {
          slotIndex = Short.parseShort(slotKey);
        }
        catch (Throwable t)
        {
          leftovers.add(cloneStack(stack));
          continue;
        }

        if (slotIndex < 0 || slotIndex >= capacity)
        {
          leftovers.add(cloneStack(stack));
          continue;
        }

        try
        {
          var tx = container.setItemStackForSlot(slotIndex, cloneStack(stack));
          ItemStack remainder = tx == null ? cloneStack(stack) : tx.getRemainder();
          boolean success = tx != null && tx.succeeded();
          if (success)
          {
            int restored = remainder == null ? stack.getQuantity() : Math.max(0, stack.getQuantity() - remainder.getQuantity());
            if (restored > 0)
            {
              restoredStacks[0]++;
              restoredQty[0] += restored;
            }
          }
          if (!success)
          {
            leftovers.add(cloneStack(stack));
          }
          else if (remainder != null && !remainder.isEmpty())
          {
            leftovers.add(cloneStack(remainder));
          }
        }
        catch (Throwable t)
        {
          leftovers.add(cloneStack(stack));
        }
      }
    }

    if (snapshot.get("overflow") instanceof BsonArray)
    {
      leftovers.addAll(readItemsFromArrayField(snapshot, "overflow"));
    }

    if (!leftovers.isEmpty())
    {
      int beforeFallbackQty = countQuantity(leftovers);
      int beforeFallbackStacks = leftovers.size();
      List<ItemStack> fallbackLeftovers = addItemsToInventory(inventory, leftovers);
      restoredStacks[0] += Math.max(0, beforeFallbackStacks - fallbackLeftovers.size());
      restoredQty[0] += Math.max(0, beforeFallbackQty - countQuantity(fallbackLeftovers));
      leftovers = new ArrayList<>(fallbackLeftovers);
    }

    return new RestoreResult(restoredStacks[0], restoredQty[0], leftovers.size(), leftovers);
  }

  private Path stashPath(UUID playerUuid, UUID worldUuid)
  {
    return stashRoot.resolve(playerUuid.toString()).resolve(worldUuid.toString() + ".json");
  }

  private static BsonDocument buildSectionSnapshot(UUID playerUuid, UUID worldUuid, Inventory inventory)
  {
    BsonDocument doc = new BsonDocument();
    doc.put("version", new BsonInt32(SNAPSHOT_VERSION));
    doc.put("playerUuid", new BsonString(playerUuid == null ? "" : playerUuid.toString()));
    doc.put("worldUuid", new BsonString(worldUuid == null ? "" : worldUuid.toString()));

    BsonDocument sections = new BsonDocument();
    for (String sectionName : INVENTORY_SECTIONS)
    {
      ItemContainer container = getSectionContainer(inventory, sectionName);
      if (container == null)
      {
        continue;
      }
      sections.put(sectionName, buildSectionDoc(container));
    }
    doc.put("sections", sections);
    return doc;
  }

  private static BsonDocument buildSectionDoc(ItemContainer container)
  {
    BsonDocument sectionDoc = new BsonDocument();
    BsonDocument slots = new BsonDocument();
    short capacity = 0;
    try
    {
      capacity = container.getCapacity();
    }
    catch (Throwable ignored)
    {
    }
    sectionDoc.put("capacity", new BsonInt32(capacity));

    container.forEach((slot, stack) -> {
      if (stack == null || stack.isEmpty())
      {
        return;
      }
      slots.put(Short.toString(slot), stackToBson(stack));
    });
    sectionDoc.put("slots", slots);
    return sectionDoc;
  }

  private static void collectSectionItemsAsLeftovers(BsonDocument slots, List<ItemStack> leftovers)
  {
    for (String slotKey : slots.keySet())
    {
      try
      {
        if (slots.get(slotKey) instanceof BsonDocument slotDoc)
        {
          ItemStack stack = bsonToStack(slotDoc);
          if (stack != null && !stack.isEmpty())
          {
            leftovers.add(stack);
          }
        }
      }
      catch (Throwable ignored)
      {
      }
    }
  }

  private static ItemContainer getSectionContainer(Inventory inventory, String sectionName)
  {
    if (inventory == null || sectionName == null)
    {
      return null;
    }
    return switch (sectionName)
    {
      case "HOTBAR" -> inventory.getHotbar();
      case "STORAGE" -> inventory.getStorage();
      case "ARMOR" -> inventory.getArmor();
      case "UTILITY" -> inventory.getUtility();
      case "TOOLS" -> inventory.getTools();
      case "BACKPACK" -> inventory.getBackpack();
      default -> null;
    };
  }

  private static List<ItemStack> readItemsFromInventory(Inventory inventory)
  {
    ArrayList<ItemStack> items = new ArrayList<>();
    if (inventory == null)
    {
      return items;
    }

    ItemContainer combined = inventory.getCombinedEverything();
    combined.forEach((slot, stack) -> {
      if (stack == null || stack.isEmpty())
      {
        return;
      }
      items.add(cloneStack(stack));
    });
    return items;
  }

  private static List<ItemStack> addItemsToInventory(Inventory inventory, List<ItemStack> items)
  {
    ArrayList<ItemStack> leftovers = new ArrayList<>();
    if (inventory == null || items == null || items.isEmpty())
    {
      return leftovers;
    }

    ItemContainer combined = inventory.getCombinedEverything();
    for (ItemStack stack : items)
    {
      if (stack == null || stack.isEmpty())
      {
        continue;
      }
      try
      {
        ItemStackTransaction tx = combined.addItemStack(cloneStack(stack));
        ItemStack remainder = tx == null ? null : tx.getRemainder();
        if (remainder != null && !remainder.isEmpty())
        {
          leftovers.add(cloneStack(remainder));
        }
      }
      catch (Throwable t)
      {
        leftovers.add(cloneStack(stack));
      }
    }
    return leftovers;
  }

  private static BsonDocument buildOverflowSnapshot(UUID playerUuid, UUID worldUuid, List<ItemStack> items)
  {
    BsonDocument doc = new BsonDocument();
    doc.put("version", new BsonInt32(SNAPSHOT_VERSION));
    doc.put("playerUuid", new BsonString(playerUuid == null ? "" : playerUuid.toString()));
    doc.put("worldUuid", new BsonString(worldUuid == null ? "" : worldUuid.toString()));
    doc.put("sections", new BsonDocument());
    BsonArray arr = new BsonArray();
    if (items != null)
    {
      for (ItemStack stack : items)
      {
        if (stack == null || stack.isEmpty())
        {
          continue;
        }
        arr.add(stackToBson(stack));
      }
    }
    doc.put("overflow", arr);
    return doc;
  }

  private static List<ItemStack> readItemsFromArrayField(BsonDocument snapshot, String fieldName)
  {
    ArrayList<ItemStack> items = new ArrayList<>();
    if (snapshot == null || fieldName == null || fieldName.isBlank())
    {
      return items;
    }

    if (snapshot.get(fieldName) instanceof BsonArray arr)
    {
      for (Object value : arr)
      {
        if (value instanceof BsonDocument doc)
        {
          ItemStack stack = bsonToStack(doc);
          if (stack != null && !stack.isEmpty())
          {
            items.add(stack);
          }
        }
      }
    }
    return items;
  }

  private static BsonDocument stackToBson(ItemStack stack)
  {
    BsonDocument doc = new BsonDocument();
    doc.put("id", new BsonString(stack.getItemId()));
    doc.put("qty", new BsonInt32(stack.getQuantity()));
    doc.put("dur", new BsonDouble(stack.getDurability()));
    doc.put("maxDur", new BsonDouble(stack.getMaxDurability()));
    BsonDocument meta = stack.getMetadata();
    doc.put("meta", meta == null ? BsonNull.VALUE : BsonDocument.parse(meta.toJson()));
    return doc;
  }

  private static ItemStack bsonToStack(BsonDocument doc)
  {
    if (doc == null)
    {
      return null;
    }
    String id = getString(doc, "id", null);
    int qty = getInt(doc, "qty", 0);
    double dur = getDouble(doc, "dur", 0.0d);
    double maxDur = getDouble(doc, "maxDur", 0.0d);
    if (id == null || id.isBlank() || qty <= 0)
    {
      return null;
    }

    BsonDocument meta = null;
    if (doc.get("meta") instanceof BsonDocument metaDoc)
    {
      meta = BsonDocument.parse(metaDoc.toJson());
    }
    return new ItemStack(id, qty, dur, maxDur, meta);
  }

  private static ItemStack cloneStack(ItemStack stack)
  {
    BsonDocument meta = stack.getMetadata();
    BsonDocument metaClone = meta == null ? null : BsonDocument.parse(meta.toJson());
    return new ItemStack(stack.getItemId(), stack.getQuantity(), stack.getDurability(), stack.getMaxDurability(),
        metaClone);
  }

  private static String getString(BsonDocument doc, String key, String fallback)
  {
    try
    {
      if (doc == null)
      {
        return fallback;
      }
      if (doc.getString(key) != null)
      {
        return doc.getString(key).getValue();
      }
    }
    catch (Throwable ignored)
    {
    }
    return fallback;
  }

  private static int getInt(BsonDocument doc, String key, int fallback)
  {
    try
    {
      if (doc != null && doc.getInt32(key) != null)
      {
        return doc.getInt32(key).getValue();
      }
    }
    catch (Throwable ignored)
    {
    }
    return fallback;
  }

  private static double getDouble(BsonDocument doc, String key, double fallback)
  {
    try
    {
      if (doc == null)
      {
        return fallback;
      }
      if (doc.getDouble(key) != null)
      {
        return doc.getDouble(key).getValue();
      }
      if (doc.getInt32(key) != null)
      {
        return doc.getInt32(key).getValue();
      }
    }
    catch (Throwable ignored)
    {
    }
    return fallback;
  }

  private static int countNonEmptyStacks(Inventory inventory)
  {
    if (inventory == null)
    {
      return 0;
    }
    int[] count = new int[1];
    try
    {
      inventory.getCombinedEverything().forEach((slot, stack) -> {
        if (stack != null && !stack.isEmpty())
        {
          count[0]++;
        }
      });
    }
    catch (Throwable ignored)
    {
    }
    return count[0];
  }

  private static int countQuantity(List<ItemStack> items)
  {
    int qty = 0;
    if (items == null)
    {
      return qty;
    }
    for (ItemStack item : items)
    {
      if (item != null && !item.isEmpty())
      {
        qty += Math.max(0, item.getQuantity());
      }
    }
    return qty;
  }

  private static boolean isInstanceWorld(String worldName)
  {
    return worldName != null && worldName.startsWith(INSTANCE_WORLD_PREFIX);
  }

  private static String safeGameModeName(Player player)
  {
    try
    {
      GameMode mode = player.getGameMode();
      return mode == null ? "null" : mode.toString();
    }
    catch (Throwable ignored)
    {
      return "unknown";
    }
  }

  private void logWarn(String message)
  {
    logger.at(Level.WARNING).log(message);
  }

  private void logFine(String message)
  {
    logger.at(Level.FINEST).log(message);
  }

  private static <T> T safeGet(ThrowingSupplier<T> supplier)
  {
    try
    {
      return supplier.get();
    }
    catch (Throwable ignored)
    {
      return null;
    }
  }

  private static String safeString(ThrowingSupplier<String> supplier, String fallback)
  {
    String value = safeGet(supplier);
    return value == null ? fallback : value;
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T>
  {
    T get() throws Exception;
  }

  private record WorldIdentity(UUID uuid, String name, boolean instance)
  {
  }

  private record PendingTransfer(UUID playerUuid, String playerName, UUID sourceWorldUuid, UUID destWorldUuid,
      String destWorldName)
  {
  }

  private record RestoreResult(int restoredStacks, int restoredQuantity, int leftoverStacks, List<ItemStack> leftovers)
  {
    private static final RestoreResult NONE = new RestoreResult(0, 0, 0, List.of());
    private static final RestoreResult ERROR = new RestoreResult(0, 0, 0, List.of());
  }
}
