package com.duperknight.daxr.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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

public class DaxrClient implements ClientModInitializer {
    private static final Pattern COREPROTECT_DONE = Pattern.compile(
            ".*Time taken: \\d+(\\.\\d+)? seconds\\..*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "Unknown or incomplete command|Database busy",
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
    }

    private void registerRollbackXrayCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess commandRegistryAccess) {
        dispatcher.register(ClientCommandManager.literal("rollbackxray")
                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> {
                            String player = StringArgumentType.getString(context, "player");
                            if (isBusy.get()) {
                                context.getSource().sendError(Text.of("§cAnother rollback is in progress!"));
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
        //System.out.println("DEBUG - Received: " + rawMessage);

        if (COREPROTECT_DONE.matcher(rawMessage).matches()) {
            //System.out.println("DEBUG - Command completed successfully");
            scheduler.schedule(() ->
                            MinecraftClient.getInstance().execute(this::proceedToNextCommand),
                    1, TimeUnit.SECONDS
            );
        }
        else if (ERROR_PATTERN.matcher(rawMessage).find()) {
            //System.out.println("DEBUG - Error detected, stopping sequence");
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().player.sendMessage(
                        Text.of("§cError occurred! Stopping rollback sequence."),
                        false
                );
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
            MinecraftClient.getInstance().player.sendMessage(
                    Text.of("§aFinished rollback for " + finishedPlayer),
                    false
            );
        } else {
            sendNextCommand();
        }
    }

    private void sendNextCommand() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || currentCommandIndex >= pendingCommands.length) return;

        String command = pendingCommands[currentCommandIndex];
        //System.out.println("DEBUG - Sending command #" + (currentCommandIndex + 1));
        client.player.networkHandler.sendCommand(command);
    }

    private void resetState() {
        pendingCommands = null;
        currentCommandIndex = 0;
        currentPlayer = null;
        isBusy.set(false);
    }
}