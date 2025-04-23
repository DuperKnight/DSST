package com.duperknight.dsst.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;

public class DsstClient implements ClientModInitializer {
    private static final Pattern COREPROTECT_DONE = Pattern.compile(".*Time taken: \\d+(\\.\\d+)? seconds\\..*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_PATTERN = Pattern.compile("Unknown or incomplete command|Database busy", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX_CREATE_PATTERN = Pattern.compile("Successfully created prefix.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX_SETLIMIT_PATTERN = Pattern.compile("Set the limit of.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX_SETMANAGER_PATTERN = Pattern.compile("Successfully set the manager of.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX_ERROR_PATTERN = Pattern.compile("Could not find that prefix.", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX_ERROR_PATTERN_2 = Pattern.compile("I'm sorry, but you do not have permission to perform this command.", Pattern.CASE_INSENSITIVE);

    private final AtomicBoolean isPrefixBusy = new AtomicBoolean(false);
    private final AtomicBoolean isRollbackBusy = new AtomicBoolean(false);

    private String[] prefixPendingCommands;
    private int prefixCommandIndex;
    private String prefixPlayer;
    private String[] rollbackPendingCommands;
    private int rollbackCommandIndex;
    private String rollbackPlayer;
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(this::registerRollbackXrayCommand);
        ClientCommandRegistrationCallback.EVENT.register(this::registerPrefixCreateCommand);
        ClientReceiveMessageEvents.GAME.register(this::handleMessage);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            this.resetPrefixState();
            this.resetRollbackState();
            this.scheduler.shutdownNow();
            this.scheduler = Executors.newScheduledThreadPool(1);
        });
    }

    private void registerRollbackXrayCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess commandRegistryAccess) {
        dispatcher.register(ClientCommandManager.literal("rollbackxray")
                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> {
                            String player = StringArgumentType.getString(context, "player");
                            if (this.isRollbackBusy.get()) {
                                context.getSource().sendError(Text.literal("§b[DSST] §cRollback command already processing. Please wait."));
                            } else {
                                this.sendRollbackCommands(player);
                            }
                            return 1;
                        })
                )
        );
    }

    private void registerPrefixCreateCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess commandRegistryAccess) {
        dispatcher.register(ClientCommandManager.literal("prefixc")
                .then(ClientCommandManager.argument("type", StringArgumentType.word()).suggests((context, builder) -> {
                    builder.suggest("Normal");
                    builder.suggest("Mass");
                    builder.suggest("Unlimited");
                    builder.suggest("<Custom Value>");
                    return builder.buildFuture();
                }).then(ClientCommandManager.argument("ign", StringArgumentType.word()).suggests((context, builder) -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            if (client.getNetworkHandler() != null) {
                                for (PlayerListEntry player : client.getNetworkHandler().getPlayerList()) {
                                    builder.suggest(player.getProfile().getName());
                                }
                            }
                            return builder.buildFuture();
                        }).then(ClientCommandManager.argument("prefixID", StringArgumentType.word())
                                .then(ClientCommandManager.argument("prefix", StringArgumentType.greedyString()).executes(context -> {
                                    if (this.isPrefixBusy.get()) {
                                        (context.getSource())
                                                .sendError(Text.literal("§b[DSST] §cPrefix command already processing. Please wait."));
                                    } else {
                                        String mode = StringArgumentType.getString(context, "type");
                                        String ign = StringArgumentType.getString(context, "ign");
                                        String prefixID = StringArgumentType.getString(context, "prefixID");
                                        String prefix = StringArgumentType.getString(context, "prefix");
                                        this.sendPrefixCreateCommands(mode, ign, prefixID, prefix);
                                    }
                                    return 1;
                                })))
                ))
        );
    }

    private void sendPrefixCreateCommands(String mode, String ign, String prefixID, String prefix) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            String limitValue;
            if (mode.equalsIgnoreCase("normal")) {
                limitValue = "10";
            } else if (mode.equalsIgnoreCase("mass")) {
                limitValue = "30";
            } else if (mode.equalsIgnoreCase("unlimited")) {
                limitValue = "2147483647";
            } else {
                try {
                    Integer.parseInt(mode);
                    limitValue = mode;
                } catch (NumberFormatException e) {
                    client.player.sendMessage(Text.literal("§b[DSST] §cInvalid type: " + mode + ". Use Normal, Mass, Unlimited or a numeric value."), false);
                    return;
                }
            }
            this.prefixPendingCommands = new String[]{
                "prefix create " + prefixID + " " + prefix,
                "prefix x setlimit " + prefixID + " " + limitValue,
                "prefix x setmanager " + prefixID + " " + ign,
                "prefix x info " + prefixID};
            this.prefixCommandIndex = 0;
            this.prefixPlayer = ign;
            this.isPrefixBusy.set(true);
            this.sendNextCommand(this.prefixPendingCommands, this.prefixCommandIndex);
        }
    }

    private void sendRollbackCommands(String player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            this.rollbackPendingCommands = new String[]{
                "co rollback u:" + player + " t:7d r:#global action:container",
                "co rollback u:" + player + " t:30d radius:#global a:-block include:deepslate,deepslate_gold_ore,deepslate_emerald_ore,deepslate_diamond_ore,deepslate_iron_ore,deepslate_lapis_ore,deepslate_redstone_ore,deepslate_copper_ore,tuff",
                "co rollback u:" + player + " t:30d radius:#global a:-block include:stone,gold_ore,iron_ore,emerald_ore,diamond_ore,redstone_ore,lapis_ore,coal_ore,granite,diorite,andesite,gravel",
                "co rollback u:" + player + " t:30d radius:#global a:+block include:gold_ore,iron_ore,emerald_ore,diamond_ore,redstone_ore,lapis_ore,coal_ore",
                "co rollback u:" + player + " t:30d radius:#global a:+block include:deepslate_gold_ore,deepslate_emerald_ore,deepslate_diamond_ore,deepslate_iron_ore,deepslate_lapis_ore,deepslate_redstone_ore,deepslate_copper_ore"};
            this.rollbackCommandIndex = 0;
            this.rollbackPlayer = player;
            this.isRollbackBusy.set(true);
            this.sendNextCommand(this.rollbackPendingCommands, this.rollbackCommandIndex);
        }
    }

    private void sendNextCommand(String[] commands, int index) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && index < commands.length) {
            client.player.sendMessage(Text.of(commands[index]), false);
        }
    }

    private void scheduleProceed(boolean isPrefix) {
        this.scheduler.schedule(() -> MinecraftClient.getInstance().execute(() -> this.proceedNext(isPrefix)), 1L, TimeUnit.SECONDS);
    }

    private void proceedNext(boolean isPrefix) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (isPrefix) {
            ++this.prefixCommandIndex;
            if (this.prefixCommandIndex >= this.prefixPendingCommands.length) {
                String finishedPlayer = this.prefixPlayer;
                this.resetPrefixState();
                if (client.player != null) {
                    String finishMsg = "Finished prefix command for " + finishedPlayer;
                    client.player.sendMessage(Text.literal("§b[DSST] §r" + finishMsg), false);
                }
            } else {
                this.sendNextCommand(this.prefixPendingCommands, this.prefixCommandIndex);
            }
        } else {
            ++this.rollbackCommandIndex;
            if (this.rollbackCommandIndex >= this.rollbackPendingCommands.length) {
                String finishedPlayer = this.rollbackPlayer;
                this.resetRollbackState();
                if (client.player != null) {
                    String finishMsg = "Finished rollback for " + finishedPlayer;
                    client.player.sendMessage(Text.literal("§b[DSST] §r" + finishMsg), false);
                }
            } else {
                this.sendNextCommand(this.rollbackPendingCommands, this.rollbackCommandIndex);
            }
        }
    }

    private void resetPrefixState() {
        this.prefixPendingCommands = null;
        this.prefixCommandIndex = 0;
        this.prefixPlayer = null;
        this.isPrefixBusy.set(false);
    }

    private void resetRollbackState() {
        this.rollbackPendingCommands = null;
        this.rollbackCommandIndex = 0;
        this.rollbackPlayer = null;
        this.isRollbackBusy.set(false);
    }

    private void handleMessage(Text message, boolean overlay) {
        String rawMessage = message.getString().replaceAll("§[0-9a-fk-or]", "");
        if (this.isPrefixBusy.get()) {
            String currentPrefixCmd = this.prefixPendingCommands[this.prefixCommandIndex];
            if (currentPrefixCmd.startsWith("prefix create") && PREFIX_CREATE_PATTERN.matcher(rawMessage).matches()) {
                this.scheduleProceed(true);
                return;
            }
            if (currentPrefixCmd.startsWith("prefix x setlimit") && PREFIX_SETLIMIT_PATTERN.matcher(rawMessage).matches()) {
                this.scheduleProceed(true);
                return;
            }
            if (currentPrefixCmd.startsWith("prefix x setmanager") && PREFIX_SETMANAGER_PATTERN.matcher(rawMessage).matches()) {
                this.scheduleProceed(true);
                return;
            }
            if (ERROR_PATTERN.matcher(rawMessage).find() || PREFIX_ERROR_PATTERN.matcher(rawMessage).find() || PREFIX_ERROR_PATTERN_2.matcher(rawMessage).find()) {
                MinecraftClient.getInstance().execute(() -> {
                    MinecraftClient.getInstance().player.sendMessage(Text.literal("§b[DSST] §cAn error occurred while executing the prefix command!"), false);
                    this.resetPrefixState();
                });
                return;
            }
        }
        if (this.isRollbackBusy.get()) {
            if (COREPROTECT_DONE.matcher(rawMessage).matches()) {
                this.scheduleProceed(false);
                return;
            }
            if (ERROR_PATTERN.matcher(rawMessage).find()) {
                MinecraftClient.getInstance().execute(() -> {
                    if (MinecraftClient.getInstance().player != null) {
                        MinecraftClient.getInstance().player.sendMessage(Text.literal("§b[DSST] §cAn error occurred while executing the rollback command!"), false);
                        this.resetRollbackState();
                    }
                });
                return;
            }
        }
        if (COREPROTECT_DONE.matcher(rawMessage).matches()) {
            if (this.isPrefixBusy.get()) {
                this.scheduleProceed(true);
            } else if (this.isRollbackBusy.get()) {
                this.scheduleProceed(false);
            }
        }
    }
}
