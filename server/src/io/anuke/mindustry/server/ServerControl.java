package io.anuke.mindustry.server;

import io.anuke.arc.ApplicationListener;
import io.anuke.arc.Core;
import io.anuke.arc.Events;
import io.anuke.arc.collection.Array;
import io.anuke.arc.collection.ObjectSet;
import io.anuke.mindustry.entities.Effects;
import io.anuke.arc.files.FileHandle;
import io.anuke.arc.util.*;
import io.anuke.arc.util.CommandHandler.Command;
import io.anuke.arc.util.CommandHandler.Response;
import io.anuke.arc.util.CommandHandler.ResponseType;
import io.anuke.arc.util.Timer.Task;
import io.anuke.mindustry.core.GameState.State;
import io.anuke.mindustry.entities.type.Player;
import io.anuke.mindustry.game.Difficulty;
import io.anuke.mindustry.game.EventType.GameOverEvent;
import io.anuke.mindustry.game.RulePreset;
import io.anuke.mindustry.game.Team;
import io.anuke.mindustry.game.Version;
import io.anuke.mindustry.gen.Call;
import io.anuke.mindustry.io.SaveIO;
import io.anuke.mindustry.maps.Map;
import io.anuke.mindustry.net.Administration.PlayerInfo;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.net.Packets.KickReason;
import io.anuke.mindustry.type.Item;
import io.anuke.mindustry.type.ItemType;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

import static io.anuke.arc.util.Log.*;
import static io.anuke.mindustry.Vars.*;

public class ServerControl implements ApplicationListener{
    private static final int roundExtraTime = 12;
    //in bytes: 512 kb is max
    private static final int maxLogLength = 1024 * 512;

    private final CommandHandler handler = new CommandHandler("");
    private final FileHandle logFolder = Core.files.local("logs/");

    private FileHandle currentLogFile;
    private boolean inExtraRound;
    private Task lastTask;


    public ServerControl(String[] args){
        Core.settings.defaults(
            "shufflemode", "normal",
            "bans", "",
            "admins", "",
            "shuffle", true,
            "crashreport", false,
            "port", port,
            "logging", true
        );

        Log.setLogger(new LogHandler(){
            DateTimeFormatter dateTime = DateTimeFormatter.ofPattern("MM-dd-yyyy | HH:mm:ss");

            @Override
            public void debug(String text, Object... args){
                print("&lc&fb" + "[DEBUG] " + text, args);
            }

            @Override
            public void info(String text, Object... args){
                print("&lg&fb" + "[INFO] " + text, args);
            }

            @Override
            public void err(String text, Object... args){
                print("&lr&fb" + "[ERR!] " + text, args);
            }

            @Override
            public void warn(String text, Object... args){
                print("&ly&fb" + "[WARN] " + text, args);
            }

            @Override
            public void print(String text, Object... args){
                String result = "[" + dateTime.format(LocalDateTime.now()) + "] " + format(text + "&fr", args);
                System.out.println(result);

                if(Core.settings.getBool("logging")){
                    logToFile("[" + dateTime.format(LocalDateTime.now()) + "] " + format(text + "&fr", false, args));
                }
            }
        });

        Time.setDeltaProvider(() -> Core.graphics.getDeltaTime() * 60f);
        Effects.setScreenShakeProvider((a, b) -> {});
        Effects.setEffectProvider((a, b, c, d, e, f) -> {});

        registerCommands();

        Core.app.post(() -> {
            String[] commands = {};

            if(args.length > 0){
                commands = Strings.join(" ", args).split(",");
                info("&lmFound {0} command-line arguments to parse. {1}", commands.length);
            }

            for(String s : commands){
                Response response = handler.handleMessage(s);
                if(response.type != ResponseType.valid){
                    err("Invalid command argument sent: '{0}': {1}", s, response.type.name());
                    err("Argument usage: &lc<command-1> <command1-args...>,<command-2> <command-2-args2...>");
                    System.exit(1);
                }
            }
        });

        Thread thread = new Thread(this::readCommands, "Server Controls");
        thread.setDaemon(true);
        thread.start();

        if(Version.build == -1){
            warn("&lyYour server is running a custom build, which means that client checking is disabled.");
            warn("&lyIt is highly advised to specify which version you're using by building with gradle args &lc-Pbuildversion=&lm<build>&ly.");
        }

        Events.on(GameOverEvent.class, event -> {
            if(inExtraRound) return;
            info("Game over!");

            if(Core.settings.getBool("shuffle")){
                if(world.maps.all().size > 0){
                    Array<Map> maps = world.maps.customMaps().size == 0 ? world.maps.defaultMaps() : world.maps.customMaps();

                    Map previous = world.getMap();
                    Map map = previous;
                    if(maps.size > 1){
                        while(map == previous) map = maps.random();
                    }

                    Call.onInfoMessage((state.rules.pvp
                    ? "[YELLOW]The " + event.winner.name() + " team is victorious![]" : "[SCARLET]Game over![]")
                    + "\nNext selected map:[accent] "+map.name+"[]"
                    + (map.meta.author() != null ? " by[accent] " + map.meta.author() + "[]" : "") + "."+
                    "\nNew game begins in " + roundExtraTime + " seconds.");

                    info("Selected next map to be {0}.", map.name);

                    Map fmap = map;

                    play(true, () -> world.loadMap(fmap));
                }
            }else{
                netServer.kickAll(KickReason.gameover);
                state.set(State.menu);
                Net.closeServer();
            }
        });

        info("&lcServer loaded. Type &ly'help'&lc for help.");
        System.out.print("> ");
    }

    private void registerCommands(){
        handler.register("help", "Displays this command list.", arg -> {
            info("Commands:");
            for(Command command : handler.getCommandList()){
                info("   &y" + command.text + (command.paramText.isEmpty() ? "" : " ") + command.paramText + " - &lm" + command.description);
            }
        });

        handler.register("version", "Displays server version info.", arg -> {
            info("&lmVersion: &lyMindustry {0}-{1} {2} / build {3}", Version.number, Version.modifier, Version.type, Version.build);
            info("&lmJava Version: &ly{0}", System.getProperty("java.version"));
        });

        handler.register("exit", "Exit the server application.", arg -> {
            info("Shutting down server.");
            Net.dispose();
            Core.app.exit();
        });

        handler.register("stop", "Stop hosting the server.", arg -> {
            Net.closeServer();
            if(lastTask != null) lastTask.cancel();
            state.set(State.menu);
            info("Stopped server.");
        });

        handler.register("host", "<mapname> [mode]", "Open the server with a specific map.", arg -> {
            if(state.is(State.playing)){
                err("Already hosting. Type 'stop' to stop hosting first.");
                return;
            }

            if(lastTask != null) lastTask.cancel();

            Map result = world.maps.all().find(map -> map.name.equalsIgnoreCase(arg[0]));

            if(result == null){
                err("No map with name &y'{0}'&lr found.", arg[0]);
                return;
            }

            RulePreset preset = RulePreset.survival;

            if(arg.length > 1){
                try{
                    preset = RulePreset.valueOf(arg[1]);
                }catch(IllegalArgumentException e){
                    err("No gamemode '{0}' found.");
                    return;
                }
            }

            info("Loading map...");

            logic.reset();
            state.rules = preset.get();
            world.loadMap(result);
            logic.play();

            info("Map loaded.");

            host();
        });

        handler.register("port", "[port]", "Sets or displays the port for hosting the server.", arg -> {
            if(arg.length == 0){
                info("&lyPort: &lc{0}", Core.settings.getInt("port"));
            }else{
                int port = Strings.parseInt(arg[0]);
                if(port < 0 || port > 65535){
                    err("Port must be a number between 0 and 65535.");
                    return;
                }
                info("&lyPort set to {0}.", port);
                Core.settings.put("port", port);
                Core.settings.save();
            }
        });

        handler.register("maps", "Display all available maps.", arg -> {
            if(!world.maps.all().isEmpty()){
                info("Maps:");
                for(Map map : world.maps.all()){
                    info("  &ly{0}: &lb&fi{1} / {2}x{3}", map.name, map.custom ? "Custom" : "Default", map.meta.width, map.meta.height);
                }
            }else{
                info("No maps found.");
            }
            info("&lyMap directory: &lb&fi{0}", customMapDirectory.file().getAbsoluteFile().toString());
        });

        handler.register("status", "Display server status.", arg -> {
            if(state.is(State.menu)){
                info("Status: &rserver closed");
            }else{
                info("Status:");
                info("  &lyPlaying on map &fi{0}&fb &lb/&ly Wave {1} &lb/&ly {2} &lb/&ly {3}", Strings.capitalize(world.getMap().name), state.wave);

                if(!state.rules.waves){
                    info("&ly  {0} enemies.", unitGroups[Team.red.ordinal()].size());
                }else{
                    info("&ly  {0} seconds until next wave.", (int) (state.wavetime / 60));
                }

                info("  &ly{0} FPS.", (int) (60f / Time.delta()));
                info("  &ly{0} MB used.", Core.app.getJavaHeap() / 1024 / 1024);

                if(playerGroup.size() > 0){
                    info("  &lyPlayers: {0}", playerGroup.size());
                    for(Player p : playerGroup.all()){
                        info("    &y{0} / {1}", p.name, p.uuid);
                    }
                }else{
                    info("  &lyNo players connected.");
                }
            }
        });

        handler.register("say", "<message...>", "Send a message to all players.", arg -> {
            if(!state.is(State.playing)){
                err("Not hosting. Host a game first.");
                return;
            }

            Call.sendMessage("[scarlet][[Server]:[] " + arg[0]);

            info("&lyServer: &lb{0}", arg[0]);
        });

        handler.register("difficulty", "<difficulty>", "Set game difficulty.", arg -> {
            try{
                state.rules.waveSpacing = Difficulty.valueOf(arg[0]).waveTime;
                info("Difficulty set to '{0}'.", arg[0]);
            }catch(IllegalArgumentException e){
                err("No difficulty with name '{0}' found.", arg[0]);
            }
        });

        handler.register("fillitems", "[team]", "Fill the core with 2000 items.", arg -> {
            if(!state.is(State.playing)){
                err("Not playing. Host first.");
                return;
            }
            
            try{
                Team team = arg.length == 0 ? Team.blue : Team.valueOf(arg[0]);

                if(state.teams.get(team).cores.isEmpty()){
                    err("That team has no cores.");
                    return;
                }
                
                for(Item item : content.items()){
                    if(item.type == ItemType.material){
                        state.teams.get(team).cores.first().entity.items.add(item, 2000);
                    }
                }
                
                info("Core filled.");
            }catch(IllegalArgumentException ignored){
                err("No such team exists.");
            }
        });

        handler.register("crashreport", "<on/off>", "Disables or enables automatic crash reporting", arg -> {
            boolean value = arg[0].equalsIgnoreCase("on");
            Core.settings.put("crashreport", value);
            Core.settings.save();
            info("Crash reporting is now {0}.", value ? "on" : "off");
        });

        handler.register("logging", "<on/off>", "Disables or enables server logs", arg -> {
            boolean value = arg[0].equalsIgnoreCase("on");
            Core.settings.put("logging", value);
            Core.settings.save();
            info("Logging is now {0}.", value ? "on" : "off");
        });

        handler.register("strict", "<on/off>", "Disables or enables strict mode", arg -> {
           boolean value = arg[0].equalsIgnoreCase("on");
           netServer.admins.setStrict(value);
           info("Strict mode is now {0}.", netServer.admins.getStrict() ? "on" : "off");
        });

        handler.register("allow-custom-clients", "[on/off]", "Allow or disallow custom clients.", arg -> {
            if(arg.length == 0){
                info("Custom clients are currently &lc{0}.", netServer.admins.allowsCustomClients() ? "allowed" : "disallowed");
                return;
            }

            String s = arg[0];
            if(s.equalsIgnoreCase("on")){
                netServer.admins.setCustomClients(true);
                info("Custom clients enabled.");
            }else if(s.equalsIgnoreCase("off")){
                netServer.admins.setCustomClients(false);
                info("Custom clients disabled.");
            }else{
                err("Incorrect command usage.");
            }
        });

        handler.register("shuffle", "<on/off>", "Set map shuffling.", arg -> {
            if(!arg[0].equals("on") && !arg[0].equals("off")){
                err("Invalid shuffle mode.");
                return;
            }
            Core.settings.put("shuffle", arg[0].equals("on"));
            Core.settings.save();
            info("Shuffle mode set to '{0}'.", arg[0]);
        });

        handler.register("kick", "<username...>", "Kick a person by name.", arg -> {
            if(!state.is(State.playing)){
                err("Not hosting a game yet. Calm down.");
                return;
            }

            Player target = playerGroup.find(p -> p.name.equals(arg[0]));

            if(target != null){
                Call.sendMessage("[scarlet] " + target.name + " has been kicked by the server.");
                netServer.kick(target.con.id, KickReason.kick);
                info("It is done.");
            }else{
                info("Nobody with that name could be found...");
            }
        });

        handler.register("ban", "<type-id/name/ip> <username/IP/ID...>", "Ban a person.", arg -> {
            if(arg[0].equals("id")){
                netServer.admins.banPlayerID(arg[1]);
                info("Banned.");
            }else if(arg[0].equals("name")){
                Player target = playerGroup.find(p -> p.name.equalsIgnoreCase(arg[1]));
                if(target != null){
                    netServer.admins.banPlayer(target.uuid);
                    info("Banned.");
                }else{
                    err("No matches found.");
                }
            }else if(arg[0].equals("ip")){
                netServer.admins.banPlayerIP(arg[1]);
                info("Banned.");
            }else{
                err("Invalid type.");
            }

            for(Player player : playerGroup.all()){
                if(netServer.admins.isIDBanned(player.uuid)){
                    Call.sendMessage("[scarlet] " + player.name + " has been banned.");
                    netServer.kick(player.con.id, KickReason.banned);
                }
            }
        });

        handler.register("bans", "List all banned IPs and IDs.", arg -> {
            Array<PlayerInfo> bans = netServer.admins.getBanned();

            if(bans.size == 0){
                info("No ID-banned players have been found.");
            }else{
                info("&lyBanned players [ID]:");
                for(PlayerInfo info : bans){
                    info(" &ly {0} / Last known name: '{1}'", info.id, info.lastName);
                }
            }

            Array<String> ipbans = netServer.admins.getBannedIPs();

            if(ipbans.size == 0){
                info("No IP-banned players have been found.");
            }else{
                info("&lmBanned players [IP]:");
                for(String string : ipbans){
                    PlayerInfo info = netServer.admins.findByIP(string);
                    if(info != null){
                        info(" &lm '{0}' / Last known name: '{1}' / ID: '{2}'", string, info.lastName, info.id);
                    }else{
                        info(" &lm '{0}' (No known name or info)", string);
                    }
                }
            }
        });

        handler.register("unban", "<ip/ID>", "Completely unban a person by IP or ID.", arg -> {
            if(arg[0].contains(".")){
                if(netServer.admins.unbanPlayerIP(arg[0])){
                    info("Unbanned player by IP: {0}.", arg[0]);
                }else{
                    err("That IP is not banned!");
                }
            }else{
                if(netServer.admins.unbanPlayerID(arg[0])){
                    info("Unbanned player by ID: {0}.", arg[0]);
                }else{
                    err("That ID is not banned!");
                }
            }
        });

        handler.register("admin", "<username...>", "Make an online user admin", arg -> {
            if(!state.is(State.playing)){
                err("Open the server first.");
                return;
            }

            Player target = playerGroup.find(p -> p.name.equals(arg[0]));

            if(target != null){
                netServer.admins.adminPlayer(target.uuid, target.usid);
                target.isAdmin = true;
                info("Admin-ed player: {0}", arg[0]);
            }else{
                info("Nobody with that name could be found.");
            }
        });

        handler.register("unadmin", "<username...>", "Removes admin status from an online player", arg -> {
            if(!state.is(State.playing)){
                err("Open the server first.");
                return;
            }

            Player target = playerGroup.find(p -> p.name.equals(arg[0]));

            if(target != null){
                netServer.admins.unAdminPlayer(target.uuid);
                target.isAdmin = false;
                info("Un-admin-ed player: {0}", arg[0]);
            }else{
                info("Nobody with that name could be found.");
            }
        });

        handler.register("admins", "List all admins.", arg -> {
            Array<PlayerInfo> admins = netServer.admins.getAdmins();

            if(admins.size == 0){
                info("No admins have been found.");
            }else{
                info("&lyAdmins:");
                for(PlayerInfo info : admins){
                    info(" &lm {0} /  ID: '{1}' / IP: '{2}'", info.lastName, info.id, info.lastIP);
                }
            }
        });

        handler.register("runwave", "Trigger the next wave.", arg -> {
            if(!state.is(State.playing)){
                err("Not hosting. Host a game first.");
            }else{
                logic.runWave();
                info("Wave spawned.");
            }
        });

        handler.register("load", "<slot>", "Load a save from a slot.", arg -> {
            if(state.is(State.playing)){
                err("Already hosting. Type 'stop' to stop hosting first.");
                return;
            }else if(!Strings.canParseInt(arg[0])){
                err("Invalid save slot '{0}'.", arg[0]);
                return;
            }

            int slot = Strings.parseInt(arg[0]);

            if(!SaveIO.isSaveValid(slot)){
                err("No save data found for slot.");
                return;
            }

            Core.app.post(() -> {
                SaveIO.loadFromSlot(slot);
                info("Save loaded.");
                host();
                state.set(State.playing);
            });
        });

        handler.register("save", "<slot>", "Save game state to a slot.", arg -> {
            if(!state.is(State.playing)){
                err("Not hosting. Host a game first.");
                return;
            }else if(!Strings.canParseInt(arg[0])){
                err("Invalid save slot '{0}'.", arg[0]);
                return;
            }

            Core.app.post(() -> {
                int slot = Strings.parseInt(arg[0]);
                SaveIO.saveToSlot(slot);
                info("Saved to slot {0}.", slot);
            });
        });

        handler.register("gameover", "Force a game over.", arg -> {
            if(state.is(State.menu)){
                info("Not playing a map.");
                return;
            }

            info("&lyCore destroyed.");
            inExtraRound = false;
            Events.fire(new GameOverEvent(Team.red));
        });

        handler.register("info", "<IP/UUID/name...>", "Find player info(s). Can optionally check for all names or IPs a player has had.", arg -> {

            ObjectSet<PlayerInfo> infos = netServer.admins.findByName(arg[0]);

            if(infos.size > 0){
                info("&lgPlayers found: {0}", infos.size);

                int i = 0;
                for(PlayerInfo info : infos){
                    info("&lc[{0}] Trace info for player '{1}' / UUID {2}", i ++, info.lastName, info.id);
                    info("  &lyall names used: {0}", info.names);
                    info("  &lyIP: {0}", info.lastIP);
                    info("  &lyall IPs used: {0}", info.ips);
                    info("  &lytimes joined: {0}", info.timesJoined);
                    info("  &lytimes kicked: {0}", info.timesKicked);
                }
            }else{
                info("Nobody with that name could be found.");
            }
        });
    }

    private void readCommands(){

        Scanner scan = new Scanner(System.in);
        while(scan.hasNext()){
            String line = scan.nextLine();

            Core.app.post(() -> {
                Response response = handler.handleMessage(line);

                if(response.type == ResponseType.unknownCommand){

                    int minDst = 0;
                    Command closest = null;

                    for(Command command : handler.getCommandList()){
                        int dst = Strings.levenshtein(command.text, response.runCommand);
                        if(dst < 3 && (closest == null || dst < minDst)){
                            minDst = dst;
                            closest = command;
                        }
                    }

                    if(closest != null){
                        err("Command not found. Did you mean \"" + closest.text + "\"?");
                    }else{
                        err("Invalid command. Type 'help' for help.");
                    }
                }else if(response.type == ResponseType.fewArguments){
                    err("Too few command arguments. Usage: " + response.command.text + " " + response.command.paramText);
                }else if(response.type == ResponseType.manyArguments){
                    err("Too many command arguments. Usage: " + response.command.text + " " + response.command.paramText);
                }

                System.out.print("> ");
            });
        }
    }

    private void play(boolean wait, Runnable run){
        inExtraRound = true;
        Runnable r = () -> {

            Array<Player> players = new Array<>();
            for(Player p : playerGroup.all()){
                players.add(p);
                p.setDead(true);
            }
            logic.reset();
            Call.onWorldDataBegin();
            run.run();
            logic.play();
            for(Player p : players){
                p.reset();
                netServer.sendWorldData(p, p.con.id);
            }
            inExtraRound = false;
        };

        if(wait){
            lastTask = new Task(){
                @Override
                public void run(){
                    r.run();
                }
            };

            Timer.schedule(lastTask, roundExtraTime);
        }else{
            r.run();
        }
    }

    private void host(){
        try{
            Net.host(Core.settings.getInt("port"));
            info("&lcOpened a server on port {0}.", Core.settings.getInt("port"));
        }catch(IOException e){
            err(e);
            state.set(State.menu);
        }
    }

    private void logToFile(String text){
        if(currentLogFile != null && currentLogFile.length() > maxLogLength){
            String date = DateTimeFormatter.ofPattern("MM-dd-yyyy | HH:mm:ss").format(LocalDateTime.now());
            currentLogFile.writeString("[End of log file. Date: "+ date + "]\n", true);
            currentLogFile = null;
        }

        if(currentLogFile == null){
            int i = 0;
            while(logFolder.child("log-" + i + ".txt").length() >= maxLogLength){
                i ++;
            }

            currentLogFile = logFolder.child("log-" + i + ".txt");
        }

        currentLogFile.writeString(text + "\n", true);
    }
}
