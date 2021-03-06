package de.bergwerklabs.nick;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.google.gson.Gson;
import de.bergwerklabs.framework.commons.database.tablebuilder.Database;
import de.bergwerklabs.framework.commons.database.tablebuilder.DatabaseType;
import de.bergwerklabs.framework.commons.database.tablebuilder.statement.Row;
import de.bergwerklabs.framework.commons.database.tablebuilder.statement.Statement;
import de.bergwerklabs.framework.commons.database.tablebuilder.statement.StatementResult;
import de.bergwerklabs.framework.commons.misc.NicknameGenerator;
import de.bergwerklabs.framework.commons.spigot.SpigotCommons;
import de.bergwerklabs.framework.commons.spigot.nms.packet.v1_8.WrapperPlayServerPlayerInfo;
import de.bergwerklabs.nick.api.NickApi;
import de.bergwerklabs.nick.api.NickInfo;
import de.bergwerklabs.nick.command.NickCommand;
import de.bergwerklabs.nick.command.NickListCommand;
import java.io.FileReader;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Created by Yannic Rieger on 03.09.2017.
 *
 * <p>Main class for the nick plugin.
 *
 * @author Yannic Rieger
 */
public class NickPlugin extends JavaPlugin implements Listener {

  /** Gets the instance of the {@link NickPlugin} object. */
  public static NickPlugin getInstance() {
    return instance;
  }

  /** Gets the {@link NickApi}. */
  public NickApi getNickApi() {
    return this.manager;
  }

  /** Gets the data access object for this plugin. */
  public NickDao getDao() {
    return dao;
  }

  /** Whether the plugin is functional or not. */
  public boolean isFunctional() {
    return isFunctional;
  }

  /** Gets the {@link UUID}s of the TOP3 players. */
  public Set<UUID> getTop3() {
    return this.top3;
  }

  private static NickPlugin instance;
  private NickManager manager;
  private NickDao dao;
  private boolean isFunctional = false;
  private Set<UUID> top3;
  private Logger logger = Bukkit.getLogger();

  @Override
  public void onEnable() {
    instance = this;
    Bukkit.getPluginManager().registerEvents(this, this);

    NicknameGenerator.generate(); // Generate a name since the first one takes a while

    this.getCommand("nick").setExecutor(new NickCommand());
    this.getCommand("nicklist").setExecutor(new NickListCommand());

    Optional<Config> configOptional = this.readConfig();
    if (configOptional.isPresent()) {
      Config config = configOptional.get();
      this.dao = new NickDao(config);
      this.top3 = retrieveTop3(config);
      this.isFunctional = true;
    } else Bukkit.getLogger().warning("Config not present, disabling nick functions...");

    this.manager = new NickManager();

    NickUtil.init(); // retrieve 100 skins to use

    this.getServer()
        .getServicesManager()
        .register(NickApi.class, this.manager, this, ServicePriority.Normal);
    ProtocolManager protocolManager = SpigotCommons.getInstance().getProtocolManager();

    protocolManager.addPacketListener(
        new PacketAdapter(this, PacketType.Play.Server.PLAYER_INFO) {

          @Override
          public void onPacketSending(PacketEvent event) {

            Player player = event.getPlayer();
            WrapperPlayServerPlayerInfo packet = new WrapperPlayServerPlayerInfo(event.getPacket());
            List<PlayerInfoData> playerInfoData = packet.getData();

            if (playerInfoData == null) return;

            List<PlayerInfoData> toNick =
                playerInfoData
                    .stream()
                    .filter(data -> manager.nickedPlayers.containsKey(data.getProfile().getUUID()))
                    .collect(Collectors.toCollection(CopyOnWriteArrayList::new));

            for (PlayerInfoData data : toNick) {
              if (data.getProfile().getUUID().equals(player.getUniqueId())) {
                toNick.remove(data);
              }
            }

            playerInfoData.removeAll(toNick);

            playerInfoData.addAll(
                toNick
                    .stream()
                    .map(
                        data -> {
                          NickInfo info = manager.nickedPlayers.get(data.getProfile().getUUID());
                          return new PlayerInfoData(
                              info.getFakeGameProfile().toWrappedGameProfile(),
                              data.getLatency(),
                              data.getGameMode(),
                              WrappedChatComponent.fromText(info.getNickName()));
                        })
                    .collect(Collectors.toList()));

            packet.setData(playerInfoData);
            event.setPacket(packet.getHandle());
          }
        });
  }

  @EventHandler(priority = EventPriority.LOWEST)
  private void onPlayerQuit(PlayerQuitEvent e) {
    this.manager.nickedPlayers.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  private void onPlayerLogin(PlayerLoginEvent event) {
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            this,
            () -> {
              // Player player = event.getPlayer();
              // PlayerdataSet set = new PlayerdataSet(player.getUniqueId());
              // set.loadAndWait();
              // if (this.manager.canNick(event.getPlayer()) &&
              // !set.getPlayerSettings().isSet(SettingsFlag
              //                                                .GLOBAL_AUTO_NICK_DISABLED))  {
              // this.manager.nickPlayer(player);
              // }
            });
  }

  /** @return */
  private Optional<Config> readConfig() {
    try {
      FileReader reader = new FileReader(this.getDataFolder().getAbsolutePath() + "/config.json");
      return Optional.of(new Gson().fromJson(reader, Config.class));
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  private Set<UUID> retrieveTop3(Config config) {
    this.logger.info("Getting Top 3 players...");
    Set<UUID> uuids = new HashSet<>();

    Database database =
        new Database(
            DatabaseType.MySQL,
            config.getHost(),
            "playerdata",
            config.getUser(),
            config.getPassword());

    Statement statement =
        database.prepareStatement("SELECT uuid FROM rankingcache WHERE gamemode = ?");

    StatementResult result = statement.execute(config.getGame());
    statement.close();

    for (Row row : result.getRows()) {
      UUID uuid = UUID.fromString(row.getString("uuid"));
      this.logger.info("UUID is: " + uuid);
      uuids.add(uuid);
    }
    return uuids;
  }
}
