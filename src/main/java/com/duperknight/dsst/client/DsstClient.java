package com.duperknight.dsst.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class DsstClient implements ClientModInitializer {
    private static final Pattern COREPROTECT_DONE = Pattern.compile(
            ".*Time taken: \\d+(\\.\\d+)? seconds\\..*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "Unknown or incomplete command|Database busy",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NO_PERM_PATTERN = Pattern.compile(
            "You do not have permission to do that.",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern USER_NOT_FOUND_PATTERN = Pattern.compile(
            "CoreProtect - User \".*\" not found.",
            Pattern.CASE_INSENSITIVE
    );

    private final AtomicBoolean isBusy = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private String[] pendingCommands;
    private int currentCommandIndex;
    private String currentPlayer;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(this::registerRollbackXrayCommand);
        ClientReceiveMessageEvents.GAME.register(this::handleMessage);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> resetState());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetState());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetState());
        Runtime.getRuntime().addShutdownHook(new Thread(this::resetState));
    }

    private void registerRollbackXrayCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess commandRegistryAccess) {
        dispatcher.register(ClientCommandManager.literal("rollbackxray")
                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> {
                            String player = StringArgumentType.getString(context, "player");
                            if (isBusy.get()) {
                                context.getSource().sendError(Text.of("§b[DSST] §rAnother rollback is in progress!"));
                            } else {
                                sendRollbackCommands(player);
                            }
                            return 1;
                        })
                )
        );
    }

    private void handleMessage(Text message, boolean overlay) {
        String rawMessage = message.getString().replaceAll("§[0-9a-fk-or]", "");

        if (COREPROTECT_DONE.matcher(rawMessage).matches() && isBusy.get()) {
            scheduler.schedule(() ->
                            MinecraftClient.getInstance().execute(this::proceedToNextCommand),
                    1, TimeUnit.SECONDS
            );
        }
        else if (ERROR_PATTERN.matcher(rawMessage).find() && isBusy.get()) {
            MinecraftClient.getInstance().execute(() -> {
                if (MinecraftClient.getInstance().player != null) {
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.of("§b[DSST] §cError occurred! Stopping rollback sequence."),
                            false
                    );
                }
                resetState();
            });
        }
        else if (NO_PERM_PATTERN.matcher(rawMessage).find() && isBusy.get()) {
            MinecraftClient.getInstance().execute(() -> {
                if (MinecraftClient.getInstance().player != null) {
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.of("§b[DSST] §cError occurred! Lack of permission, you random!"),
                            false
                    );
                }
                resetState();
            });
        }
        else if (USER_NOT_FOUND_PATTERN.matcher(rawMessage).matches() && isBusy.get()) {
            MinecraftClient.getInstance().execute(() -> {
                if (MinecraftClient.getInstance().player != null) {
                    MinecraftClient.getInstance().player.sendMessage(
                            Text.of("§b[DSST] §cUser not found! Stopping rollback sequence."),
                            false
                    );
                }
                resetState();
            });
        }
    }

    private void sendRollbackCommands(String player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        pendingCommands = new String[]{
                "co rollback u:" + player + " t:7d r:#global action:container",
                "co rollback u:" + player + " t:30d radius:#global a:-block include:deepslate,deepslate_gold_ore,deepslate_emerald_ore,deepslate_diamond_ore,deepslate_iron_ore,deepslate_lapis_ore,deepslate_redstone_ore,deepslate_copper_ore,tuff",
                "co rollback u:" + player + " t:30d radius:#global a:-block include:stone,gold_ore,iron_ore,emerald_ore,diamond_ore,redstone_ore,lapis_ore,coal_ore,granite,diorite,andesite,gravel"
        };

        currentCommandIndex = 0;
        currentPlayer = player;
        isBusy.set(true);
        sendNextCommand();
    }

    private void proceedToNextCommand() {
        if (!isBusy.get()) return;

        currentCommandIndex++;
        if (currentCommandIndex >= pendingCommands.length) {
            String finishedPlayer = currentPlayer;
            resetState();
            if (MinecraftClient.getInstance().player != null){
                MinecraftClient.getInstance().player.sendMessage(
                        Text.of("§b[DSST] §rFinished rollback for " + finishedPlayer),
                        false
                );
            }
        } else {
            sendNextCommand();
        }
    }

    private void sendNextCommand() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || currentCommandIndex >= pendingCommands.length) return;

        String command = pendingCommands[currentCommandIndex];
        client.player.networkHandler.sendCommand(command);
    }

    private void resetState() {
        pendingCommands = null;
        currentCommandIndex = 0;
        currentPlayer = null;
        isBusy.set(false);
    }
}