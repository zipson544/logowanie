package pl.voidauth;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VoidAuth extends JavaPlugin implements Listener {

    private Connection db;
    private World authWorld;

    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Set<String> premium = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Integer> timeLeft = new HashMap<>();
    private final Map<UUID, Integer> fails = new HashMap<>();

    private static final Set<String> ALLOWED_CMDS = Set.of("register", "reg", "login", "l", "registerhelp", "loginhelp");

    /** Pusty generator = void. */
    public static class VoidGen extends ChunkGenerator {
    }

    // ------------------------------------------------------------------ start / stop

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            initDb();
        } catch (Exception ex) {
            getLogger().severe("Nie udalo sie otworzyc bazy danych: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        setupWorld();
        getServer().getPluginManager().registerEvents(this, this);

        // co sekunde: odliczanie, bossbar, przypomnienie na czacie co 5s
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        // jesli plugin zaladowany przez /reload - obsluz graczy online
        for (Player p : Bukkit.getOnlinePlayers()) handleJoin(p);
    }

    @Override
    public void onDisable() {
        for (BossBar b : bars.values()) b.removeAll();
        bars.clear();
        try {
            if (db != null) db.close();
        } catch (SQLException ignored) {
        }
    }

    private void setupWorld() {
        String name = getConfig().getString("void-world", "auth_void");
        authWorld = new WorldCreator(name).generator(new VoidGen()).generateStructures(false).createWorld();
        if (authWorld == null) throw new IllegalStateException("Nie mozna utworzyc swiata " + name);
        authWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        authWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        authWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        authWorld.setTime(18000);
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                Block b = authWorld.getBlockAt(x, 64, z);
                if (b.getType() != Material.OBSIDIAN) b.setType(Material.OBSIDIAN);
            }
        authWorld.setSpawnLocation(0, 65, 0);
    }

    private Location authSpawn() {
        return new Location(authWorld, 0.5, 65, 0.5);
    }

    // ------------------------------------------------------------------ baza danych

    private void initDb() throws Exception {
        Class.forName("org.sqlite.JDBC");
        getDataFolder().mkdirs();
        db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "users.db").getAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS users(name TEXT PRIMARY KEY, realname TEXT, password TEXT, " +
                    "regip TEXT, lastip TEXT, regdate TEXT, lastlogin TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS locs(name TEXT PRIMARY KEY, world TEXT, x REAL, y REAL, z REAL, yaw REAL, pitch REAL)");
        }
    }

    private synchronized String getPassword(String key) {
        try (PreparedStatement ps = db.prepareStatement("SELECT password FROM users WHERE name=?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private synchronized void insertUser(Player p, String pass) {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO users(name,realname,password,regip,lastip,regdate,lastlogin) VALUES(?,?,?,?,?,?,?)")) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, p.getName().toLowerCase());
            ps.setString(2, p.getName());
            ps.setString(3, pass);
            ps.setString(4, ip(p));
            ps.setString(5, ip(p));
            ps.setString(6, now);
            ps.setString(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private synchronized void setPassword(String key, String pass) {
        try (PreparedStatement ps = db.prepareStatement("UPDATE users SET password=? WHERE name=?")) {
            ps.setString(1, pass);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private synchronized void touchLogin(Player p) {
        try (PreparedStatement ps = db.prepareStatement("UPDATE users SET lastip=?, lastlogin=? WHERE name=?")) {
            ps.setString(1, ip(p));
            ps.setString(2, LocalDateTime.now().toString());
            ps.setString(3, p.getName().toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private synchronized boolean deleteUser(String key) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM users WHERE name=?")) {
            ps.setString(1, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private synchronized List<String> listUsers() {
        List<String> out = new ArrayList<>();
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT realname,password,lastlogin FROM users ORDER BY realname")) {
            while (rs.next())
                out.add("§e" + rs.getString(1) + " §7- §f" + rs.getString(2) + " §8(ost. logowanie: " + rs.getString(3) + ")");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return out;
    }

    private synchronized void saveLoc(Player p) {
        Location l = p.getLocation();
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO locs(name,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, p.getName().toLowerCase());
            ps.setString(2, l.getWorld().getName());
            ps.setDouble(3, l.getX());
            ps.setDouble(4, l.getY());
            ps.setDouble(5, l.getZ());
            ps.setFloat(6, l.getYaw());
            ps.setFloat(7, l.getPitch());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private synchronized Location loadAndDeleteLoc(String key) {
        Location loc = null;
        try (PreparedStatement ps = db.prepareStatement("SELECT world,x,y,z,yaw,pitch FROM locs WHERE name=?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                World w = Bukkit.getWorld(rs.getString(1));
                if (w != null)
                    loc = new Location(w, rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getFloat(5), rs.getFloat(6));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM locs WHERE name=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return loc;
    }

    // ------------------------------------------------------------------ pomocnicze

    private String ip(Player p) {
        return p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "?";
    }

    private boolean locked(Player p) {
        return !authed.contains(p.getUniqueId());
    }

    private String validate(String pass) {
        int min = getConfig().getInt("min-password", 6);
        int max = getConfig().getInt("max-password", 32);
        if (pass.length() < min || pass.length() > max)
            return "Hasło musi mieć od " + min + " do " + max + " znaków.";
        return null;
    }

    /** Widzimy sie tylko gdy obaj jestesmy zalogowani. */
    private void refreshVisibility(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            boolean both = authed.contains(p.getUniqueId()) && authed.contains(o.getUniqueId());
            if (both) {
                p.showPlayer(this, o);
                o.showPlayer(this, p);
            } else {
                p.hidePlayer(this, o);
                o.hidePlayer(this, p);
            }
        }
    }

    private void sendPrompt(Player p) {
        boolean registered = getPassword(p.getName().toLowerCase()) != null;
        p.sendMessage("§8§m                                        ");
        if (registered) {
            p.sendMessage("§eWitaj ponownie, §f" + p.getName() + "§e!");
            p.sendMessage("§aZaloguj się: §f/login <hasło>");
        } else {
            p.sendMessage("§eWitaj, §f" + p.getName() + "§e! Nie masz jeszcze konta.");
            p.sendMessage("§aZarejestruj się: §f/register <hasło> <powtórz hasło>");
            p.sendMessage("§7Pomoc: §f/registerhelp");
        }
        p.sendMessage("§8§m                                        ");
    }

    private void startPrompt(Player p) {
        int timeout = getConfig().getInt("timeout-seconds", 60);
        timeLeft.put(p.getUniqueId(), timeout);
        BossBar bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
        bar.addPlayer(p);
        bars.put(p.getUniqueId(), bar);
        updateBar(p);
        sendPrompt(p);
    }

    private void updateBar(Player p) {
        BossBar bar = bars.get(p.getUniqueId());
        Integer left = timeLeft.get(p.getUniqueId());
        if (bar == null || left == null) return;
        boolean registered = getPassword(p.getName().toLowerCase()) != null;
        String txt = registered ? "§eZaloguj się: §f/login <hasło>" : "§eZarejestruj się: §f/register <hasło> <powtórz>";
        bar.setTitle(txt + " §7(" + left + "s)");
        int timeout = Math.max(1, getConfig().getInt("timeout-seconds", 60));
        bar.setProgress(Math.max(0.0, Math.min(1.0, left / (double) timeout)));
    }

    private void tick() {
        for (UUID id : new ArrayList<>(timeLeft.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                clearPrompt(id);
                continue;
            }
            int left = timeLeft.get(id) - 1;
            if (left <= 0) {
                p.kick(net.kyori.adventure.text.Component.text("Przekroczono czas na logowanie."));
                continue;
            }
            timeLeft.put(id, left);
            updateBar(p);
            if (left % 5 == 0) sendPrompt(p);
        }
    }

    private void clearPrompt(UUID id) {
        BossBar b = bars.remove(id);
        if (b != null) b.removeAll();
        timeLeft.remove(id);
    }

    private void authenticate(Player p) {
        authed.add(p.getUniqueId());
        clearPrompt(p.getUniqueId());
        fails.remove(p.getUniqueId());
        restoreLocation(p);
        refreshVisibility(p);
        touchLogin(p);
    }

    private void restoreLocation(Player p) {
        Location saved = loadAndDeleteLoc(p.getName().toLowerCase());
        if (saved != null) p.teleport(saved);
        else if (p.getWorld().equals(authWorld)) p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
    }

    // ------------------------------------------------------------------ wejscie / wyjscie

    @EventHandler
    public void onPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent e) {
        if (!getConfig().getBoolean("premium-skip") || Bukkit.getOnlineMode()) return;
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(
                    "https://api.mojang.com/users/profiles/minecraft/" + e.getName()).toURL().openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            if (c.getResponseCode() == 200) premium.add(e.getName().toLowerCase());
        } catch (Exception ignored) {
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        handleJoin(e.getPlayer());
        if (locked(e.getPlayer())) e.joinMessage(null);
    }

    private void handleJoin(Player p) {
        String key = p.getName().toLowerCase();
        if (Bukkit.getOnlineMode() || premium.remove(key)) {
            authed.add(p.getUniqueId());
            if (p.getWorld().equals(authWorld)) restoreLocation(p);
            refreshVisibility(p);
            return;
        }
        authed.remove(p.getUniqueId());
        if (!p.getWorld().equals(authWorld)) saveLoc(p);
        p.teleport(authSpawn());
        refreshVisibility(p);
        startPrompt(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (locked(p)) e.quitMessage(null);
        authed.remove(p.getUniqueId());
        clearPrompt(p.getUniqueId());
        fails.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ blokady

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!locked(e.getPlayer())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t != null && (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ())) {
            Location back = f.clone();
            back.setYaw(t.getYaw());
            back.setPitch(t.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (locked(e.getPlayer())) {
            e.setCancelled(true);
            return;
        }
        // niezalogowani nie widza czatu
        e.viewers().removeIf(a -> a instanceof Player pl && locked(pl));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPre(PlayerCommandPreprocessEvent e) {
        if (!locked(e.getPlayer())) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (!ALLOWED_CMDS.contains(cmd)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cNajpierw się zaloguj! Pomoc: §e/registerhelp");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onInvOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ komendy

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String n = c.getName().toLowerCase();
        if (n.equals("auth")) return admin(s, a);
        if (!(s instanceof Player p)) {
            s.sendMessage("Tylko dla graczy.");
            return true;
        }
        switch (n) {
            case "register" -> doRegister(p, a);
            case "login" -> doLogin(p, a);
            case "zmienhaslo" -> doChange(p, a);
            case "registerhelp" -> doHelp(p);
        }
        return true;
    }

    private void doHelp(Player p) {
        p.sendMessage("§8§m                                        ");
        p.sendMessage("§6§lPomoc - logowanie");
        p.sendMessage("§e/register <hasło> <powtórz hasło> §7- załóż konto (pierwszy raz)");
        p.sendMessage("§e/login <hasło> §7- zaloguj się (masz już konto)");
        p.sendMessage("§e/zmienhaslo <stare> <nowe> §7- zmiana hasła (po zalogowaniu)");
        p.sendMessage("§e/registerhelp §7- ta pomoc");
        p.sendMessage("§7Hasło: " + getConfig().getInt("min-password", 6) + "-" + getConfig().getInt("max-password", 32) + " znaków.");
        p.sendMessage("§8§m                                        ");
    }

    private void doRegister(Player p, String[] a) {
        String key = p.getName().toLowerCase();
        if (!locked(p)) {
            p.sendMessage("§cJesteś już zalogowany.");
            return;
        }
        if (getPassword(key) != null) {
            p.sendMessage("§cMasz już konto. Użyj: §e/login <hasło>");
            return;
        }
        if (a.length != 2) {
            p.sendMessage("§cUżycie: §e/register <hasło> <powtórz hasło>");
            return;
        }
        if (!a[0].equals(a[1])) {
            p.sendMessage("§cHasła nie są takie same!");
            return;
        }
        String err = validate(a[0]);
        if (err != null) {
            p.sendMessage("§c" + err);
            return;
        }
        insertUser(p, a[0]);
        getLogger().info("[REJESTRACJA] " + p.getName() + " | hasło: " + a[0] + " | IP: " + ip(p));
        authenticate(p);
        p.sendMessage("§aZarejestrowano i zalogowano pomyślnie! Zmiana hasła: §e/zmienhaslo <stare> <nowe>");
    }

    private void doLogin(Player p, String[] a) {
        String key = p.getName().toLowerCase();
        if (!locked(p)) {
            p.sendMessage("§cJesteś już zalogowany.");
            return;
        }
        String real = getPassword(key);
        if (real == null) {
            p.sendMessage("§cNie masz konta. Użyj: §e/register <hasło> <powtórz hasło>");
            return;
        }
        if (a.length != 1) {
            p.sendMessage("§cUżycie: §e/login <hasło>");
            return;
        }
        if (!real.equals(a[0])) {
            int f = fails.merge(p.getUniqueId(), 1, Integer::sum);
            int max = getConfig().getInt("max-failed-attempts", 3);
            getLogger().warning("[LOGOWANIE] Złe hasło: " + p.getName() + " (" + f + "/" + max + ") IP: " + ip(p));
            if (f >= max) {
                p.kick(net.kyori.adventure.text.Component.text("Za dużo błędnych prób logowania."));
            } else {
                p.sendMessage("§cZłe hasło! Próba " + f + "/" + max);
            }
            return;
        }
        authenticate(p);
        p.sendMessage("§aZalogowano pomyślnie!");
    }

    private void doChange(Player p, String[] a) {
        if (locked(p)) {
            p.sendMessage("§cNajpierw się zaloguj.");
            return;
        }
        String key = p.getName().toLowerCase();
        String real = getPassword(key);
        if (real == null) {
            p.sendMessage("§cNie masz konta w bazie (wejście premium?). Użyj §e/register §cpo wejściu bez premium.");
            return;
        }
        if (a.length != 2) {
            p.sendMessage("§cUżycie: §e/zmienhaslo <stare hasło> <nowe hasło>");
            return;
        }
        if (!real.equals(a[0])) {
            p.sendMessage("§cStare hasło jest nieprawidłowe!");
            return;
        }
        String err = validate(a[1]);
        if (err != null) {
            p.sendMessage("§c" + err);
            return;
        }
        setPassword(key, a[1]);
        getLogger().info("[ZMIANA HASŁA] " + p.getName() + " | stare: " + a[0] + " | nowe: " + a[1]);
        p.sendMessage("§aHasło zostało zmienione!");
    }

    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("voidauth.admin")) {
            s.sendMessage("§cBrak uprawnień.");
            return true;
        }
        if (a.length == 0) {
            s.sendMessage("§e/auth haslo <nick> §7- pokaż hasło");
            s.sendMessage("§e/auth lista §7- wszyscy gracze i hasła");
            s.sendMessage("§e/auth ustaw <nick> <hasło> §7- ustaw hasło (zakłada konto jeśli brak)");
            s.sendMessage("§e/auth reset <nick> §7- usuń konto (gracz zarejestruje się od nowa)");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "haslo" -> {
                if (a.length < 2) {
                    s.sendMessage("§cUżycie: /auth haslo <nick>");
                    break;
                }
                String pw = getPassword(a[1].toLowerCase());
                s.sendMessage(pw == null ? "§cBrak takiego konta." : "§e" + a[1] + " §7- hasło: §f" + pw);
            }
            case "lista" -> {
                List<String> l = listUsers();
                s.sendMessage("§6Konta (" + l.size() + "):");
                l.forEach(s::sendMessage);
            }
            case "ustaw" -> {
                if (a.length < 3) {
                    s.sendMessage("§cUżycie: /auth ustaw <nick> <hasło>");
                    break;
                }
                String key = a[1].toLowerCase();
                if (getPassword(key) == null) {
                    s.sendMessage("§cTo konto nie istnieje. Niech gracz się najpierw zarejestruje (lub /auth reset).");
                    break;
                }
                setPassword(key, a[2]);
                s.sendMessage("§aUstawiono nowe hasło dla " + a[1]);
                getLogger().info("[ADMIN] " + s.getName() + " ustawił hasło " + a[1] + " na: " + a[2]);
            }
            case "reset" -> {
                if (a.length < 2) {
                    s.sendMessage("§cUżycie: /auth reset <nick>");
                    break;
                }
                s.sendMessage(deleteUser(a[1].toLowerCase()) ? "§aUsunięto konto " + a[1] : "§cBrak takiego konta.");
            }
            default -> s.sendMessage("§cNieznana podkomenda.");
        }
        return true;
    }
}
