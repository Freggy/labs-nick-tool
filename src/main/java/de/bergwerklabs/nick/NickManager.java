package de.bergwerklabs.nick;

import de.bergwerklabs.nick.api.NickProfile;
import de.bergwerklabs.nick.api.event.NickAction;
import de.bergwerklabs.nick.api.NickApi;
import de.bergwerklabs.nick.api.event.NickEvent;
import de.bergwerklabs.nick.api.NickInfo;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.google.common.collect.ImmutableSet;
import de.bergwerklabs.framework.commons.spigot.entity.npc.PlayerSkin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by Yannic Rieger on 03.09.2017.
 * <p>
 * Provides methods for nicking players.
 *
 * @author Yannic Rieger
 */
class NickManager implements NickApi {

    Map<UUID, NickInfo> nickedPlayers  = new HashMap<>();
    private Set<String> takenNickNames = new HashSet<>();

    @Override
    public boolean isNicked(Player player) {
        return nickedPlayers.containsKey(player.getUniqueId());
    }

    @Override
    public String getRealName(Player player) {
        return this.nickedPlayers.get(player.getUniqueId()).getRealGameProfile().getName();
    }

    @Override
    public Set<NickInfo> getNickedPlayerInfos() {
        return ImmutableSet.copyOf(nickedPlayers.values());
    }

    @Override
    public NickInfo getNickInfo(Player player) {
        return nickedPlayers.get(player.getUniqueId());
    }

    @Override
    public void removeNick(Player player) {
        NickInfo info = this.nickedPlayers.get(player.getUniqueId());
        this.nickedPlayers.remove(player.getUniqueId());
        String realName = info.getRealGameProfile().getName();

        player.setDisplayName(realName);
        player.setCustomName(realName);

        this.resendPlayerInfo(player);
        Bukkit.getPluginManager().callEvent(new NickEvent(player, info, NickAction.REMOVE));
    }

    @Override
    public NickInfo nickPlayer(Player player) {
        String nickName = NickUtil.getUniqueNickName(this.takenNickNames);
        PlayerSkin skin = NickUtil.getRandomSkin();

        player.setDisplayName(nickName);
        player.setCustomName(nickName);

        NickProfile real = NickUtil.toNickProfile(WrappedGameProfile.fromPlayer(player));
        NickProfile fake = new NickProfile(player.getUniqueId(), nickName, skin);

        NickInfo info = new NickInfo(real, fake, skin, nickName);
        this.nickedPlayers.put(player.getUniqueId(), info);
        this.takenNickNames.add(info.getNickName());

        this.resendPlayerInfo(player);

        Bukkit.getPluginManager().callEvent(new NickEvent(player, info, NickAction.NICKED));
        return info;
    }

    /**
     * Re-sends PlayerInfo packet to update player name and skin.
     * This method internally calls {@link Player#hidePlayer(Player)} and {@link Player#showPlayer(Player)}
     * in that specific order.
     *
     * @param player Player that has been nicked.
     */
    private void resendPlayerInfo(Player player) {
        Bukkit.getScheduler().callSyncMethod(NickPlugin.getInstance(), () -> {
            List<Player> others = Bukkit.getOnlinePlayers().stream().filter(p -> !p.getUniqueId().equals(player.getUniqueId())).collect(Collectors.toList());
            others.forEach(p -> p.hidePlayer(player));
            others.forEach(p -> p.showPlayer(player));
            return null;
        });
    }
}
