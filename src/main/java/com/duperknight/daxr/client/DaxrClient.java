package com.duperknight.daxr.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.regex.Pattern;

public class DaxrClient implements ClientModInitializer {
    private static final Pattern COREPROTECT_DONE = Pattern.compile("CoreProtect - Time taken: \\d+\\.?\\d* seconds\\.");
    private boolean waitingForResponse = false;
    private String[] pendingCommands;
    private int currentCommandIndex;
    private String currentPlayer;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                registerRollbackXrayCommand(dispatcher));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (waitingForResponse && COREPROTECT_DONE.matcher(message.getString()).matches()) {
                waitingForResponse = false;
                MinecraftClient.getInstance().execute(() -> {
                    sendNextCommand(MinecraftClient.getInstance(), pendingCommands, currentCommandIndex + 1, currentPlayer);
                });
            }
        });
    }

    private void registerRollbackXrayCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("rollbackxray")
            .then(ClientCommandManager.argument("player", StringArgumentType.word())
                .executes(context -> {
                    String player = StringArgumentType.getString(context, "player");
                    sendRollbackCommands(player);
                    return 1;
                })
            )
        );
    }

    private void sendRollbackCommands(String player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            String[] commands = {
                "co rollback u:" + player + " t:7d r:#global action:container",
                "co rollback u:" + player + " t:30d radius:#global a:-block include:deepslate, deepslate_gold_ore, deepslate_emerald_ore, deepslate_diamond_ore, deepslate_iron_ore, deepslate_lapis_ore, deepslate_redstone_ore, deepslate_copper_ore, tuff",
                "co rollback u:" + player + " t:30d radius:#global a:-block include:stone, gold_ore, iron_ore, emerald_ore, diamond_ore, redstone_ore, lapis_ore, coal_ore, granite, diorite, andesite, gravel"
            };
            
            pendingCommands = commands;
            currentCommandIndex = -1;
            currentPlayer = player;
            sendNextCommand(client, commands, 0, player);
        }
    }
    
    private void sendNextCommand(MinecraftClient client, String[] commands, int index, String player) {
        if (index < commands.length) {
            currentCommandIndex = index;
            client.player.networkHandler.sendCommand(commands[index]);
            waitingForResponse = true;
        } else {
            client.player.sendMessage(Text.of("Finished rollback commands for player " + player), false);
            waitingForResponse = false;
            pendingCommands = null;
            currentCommandIndex = -1;
            currentPlayer = null;
        }
    }
}