package me.lucko.luckperms.listeners;

import lombok.AllArgsConstructor;
import me.lucko.luckperms.LPBungeePlugin;
import me.lucko.luckperms.commands.Util;
import me.lucko.luckperms.constants.Message;
import me.lucko.luckperms.users.User;
import me.lucko.luckperms.utils.UuidCache;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@AllArgsConstructor
public class PlayerListener implements Listener {
    private static final TextComponent WARN_MESSAGE = new TextComponent(Util.color(
            Message.PREFIX + "Permissions data could not be loaded. Please contact an administrator.")
    );
    private final LPBungeePlugin plugin;

    @EventHandler
    public void onPlayerLogin(LoginEvent e) {
        /* Delay the login here, as we want to cache UUID data before the player is connected to a backend bukkit server.
           This means that a player will have the same UUID across the network, even if parts of the network are running in
           Offline mode. */
        e.registerIntent(plugin);
        plugin.doAsync(() -> {
            final long startTime = System.currentTimeMillis();
            final UuidCache cache = plugin.getUuidCache();
            final PendingConnection c = e.getConnection();

            if (!cache.isOnlineMode()) {
                UUID uuid = plugin.getDatastore().getUUID(c.getName());
                if (uuid != null) {
                    cache.addToCache(c.getUniqueId(), uuid);
                } else {
                    // No previous data for this player
                    cache.addToCache(c.getUniqueId(), c.getUniqueId());
                    plugin.getDatastore().saveUUIDData(c.getName(), c.getUniqueId());
                }
            } else {
                // Online mode, no cache needed. This is just for name -> uuid lookup.
                plugin.getDatastore().saveUUIDData(c.getName(), c.getUniqueId());
            }

            // We have to make a new user on this thread whilst the connection is being held, or we get concurrency issues as the Bukkit server
            // and the BungeeCord server try to make a new user at the same time.
            plugin.getDatastore().loadOrCreateUser(cache.getUUID(c.getUniqueId()), c.getName());
            final long time = System.currentTimeMillis() - startTime;
            if (time >= 1000) {
                plugin.getLogger().warning("Processing login for " + c.getName() + " took " + time + "ms.");
            }
            e.completeIntent(plugin);
        });
    }

    @EventHandler
    public void onPlayerPostLogin(PostLoginEvent e) {
        final ProxiedPlayer player = e.getPlayer();
        final WeakReference<ProxiedPlayer> p = new WeakReference<>(player);

        final User user = plugin.getUserManager().getUser(plugin.getUuidCache().getUUID(e.getPlayer().getUniqueId()));
        if (user == null) {
            plugin.getProxy().getScheduler().schedule(plugin, () -> {
                final ProxiedPlayer pl = p.get();
                if (pl != null) {
                    pl.sendMessage(WARN_MESSAGE);
                }
            }, 3, TimeUnit.SECONDS);
        } else {
            user.refreshPermissions();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerDisconnectEvent e) {
        final ProxiedPlayer player = e.getPlayer();
        final UuidCache cache = plugin.getUuidCache();

        // Unload the user from memory when they disconnect;
        cache.clearCache(player.getUniqueId());

        final User user = plugin.getUserManager().getUser(cache.getUUID(player.getUniqueId()));
        plugin.getUserManager().unloadUser(user);
    }

    @EventHandler
    public void onPlayerServerSwitch(ServerSwitchEvent e) {
        final User user = plugin.getUserManager().getUser(plugin.getUuidCache().getUUID(e.getPlayer().getUniqueId()));
        if (user != null) {
            user.refreshPermissions();
        }
    }
}
