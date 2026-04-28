package weebify.dptb2utils;

import com.google.gson.Gson;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.api.ClientModInitializer;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.scoreboard.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weebify.dptb2utils.gui.widget.NotificationToast;
import weebify.dptb2utils.gui.screen.ModMenuScreen;
import weebify.dptb2utils.utils.*;

import javax.swing.*;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class DPTB2Utils implements ClientModInitializer {	
	public static final String MOD_ID = "dptb2-utils";
	public static final String VERSION = "1.2.2";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public ModConfigs config;
	private File saveFile;
	private long lastSaved;

	private boolean displayScreen = false;
	public boolean isInDPTB2 = false;
	public boolean isRamper = false;
	public boolean tryingToConnect = false;
	public boolean isToggleBc = false;
	public boolean dptb2RecheckScheduled = false;

	public List<DelayedTask> scheduledTasks = new ArrayList<>();

	private static final MinecraftClient mc = MinecraftClient.getInstance();
	private static DPTB2Utils instance;
	public static final Gson GSON = new Gson();

	public DiscordWebSocketClient websocketClient;

	public List<Text> bootsList = new ArrayList<>();

	public static DPTB2Utils getInstance() {
		return instance;
	}


	@Override
	public void onInitializeClient() {
		instance = this;
		this.config = new ModConfigs();
		this.saveFile = new File(mc.runDirectory + "/config", "weebify_dptb2utils.json");
		try {
			if (this.saveFile.createNewFile()) {
				try (FileWriter fw = new FileWriter(this.saveFile)) {
					GSON.toJson(this.config, fw);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		this.lastSaved = saveFile.lastModified();
		this.loadSettings();

		this.initializeCommands();
		this.initializeEvents();
		ButtonTimerManager.initialize();
		ItemCooldownManager.initialize();
		ExternalIndicatorManager.initialize();
		MicroTimerManager.initialize();

		this.fetchDPTBotIP();

		WaypointManager.initializeEvents();
		WaypointManager.initializeWaypoints();

		// for external indicator file chooser
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
			LOGGER.error("Failed to set Swing look and feel!", e);
		}

	}

	public static int hexToInt(String hex) {
		if (hex.startsWith("#")) {
			hex = hex.substring(1);
		}
		try {
			return ((int) Long.parseLong(hex, 16)) | 0xFF000000;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public void scheduleTask(int ticks, Runnable task) {
		this.scheduledTasks.add(new DelayedTask(ticks, task));
	}

	public void buttonTimerReset() {
		ButtonTimerManager.buttonTimer = -1;
		ButtonTimerManager.isMayhem = false;
		ButtonTimerManager.isChaos = false;
		ButtonTimerManager.isDisabled = false;
		ButtonTimerManager.chaosCounter = 0;
	}

	public void fetchDPTBotIP() {
		new Thread(() -> {
			try {
				LOGGER.info("Fetching DPTBot IP from https://github.com/Weebifying/Weebifying/blob/main/dptbot.host");
				URL url = URI.create("https://raw.githubusercontent.com/Weebifying/Weebifying/refs/heads/main/dptbot.host").toURL();
				BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));

				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					sb.append(line).append("\n");
				}

				String address = sb.toString().trim();
				String[] split = address.split(":");
				if (split.length == 2) {
					this.setStringConfig("others.dptbotHost", split[0]);
					this.setIntConfig("others.dptbotPort", Integer.parseInt(split[1]));
					LOGGER.info("Fetched DPTBot IP: {}:{}", this.getStringConfig("others.dptbotHost"), this.getIntConfig("others.dptbotPort"));
				} else {
					LOGGER.error("Failed to fetch DPTBot IP! Invalid format: {}", address);
				}

			} catch (Exception e) {
				LOGGER.error("Failed to fetch DPTBot IP!", e);
			}
		}).start();
	}

	private void initializeEvents() {
		ClientTickEvents.START_CLIENT_TICK.register(this::onClientTick);
		ClientTickEvents.END_CLIENT_TICK.register((var) -> {
			scheduledTasks.removeIf(DelayedTask::tick);
			if (this.dptb2RecheckScheduled) {
				this.dptb2RecheckScheduled = false;
				this.scheduleTask(600, () -> this.dptb2Check(var));
			}
		});
		// detecting whether the player is in DPTB2
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			this.buttonTimerReset();
			this.scheduleTask(20, () -> this.dptb2Check(client));
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			this.buttonTimerReset();
			if (websocketClient != null && websocketClient.isOpen()) {
				websocketClient.close();
			}
		});

		ClientSendMessageEvents.ALLOW_CHAT.register((message) -> {
			if (this.isToggleBc) {
				this.handleBroadcast(message);
				return false;
			}

			return true;
		});

		HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
			MinecraftClient client = MinecraftClient.getInstance();

			// Safety check to prevent the "Exit -1" crash
			if (client.player == null || client.world == null) return;

			// The logic: show if in DPTB2 mode AND (no screen open OR chat open)
			if (this.isInDPTB2 && (client.currentScreen == null || client.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
				if (this.getBoolConfig("others.isToggleBc")) {
					int sw = client.getWindow().getScaledWidth();
					int sh = client.getWindow().getScaledHeight();

					// Get positions from config
					int x = (int) (sw * this.getFloatConfig("others.bc_x"));
					int y = (int) (sh * this.getFloatConfig("others.bc_y"));

					String status = this.isToggleBc ? "§aON" : "§cOFF";
					drawContext.drawTextWithShadow(client.textRenderer, "§7ToggleBC: " + status, x, y, 0xFFFFFF);
				}
			}
		});
	}

	public void dptb2Check(MinecraftClient client) {
		// We ignore the server entry check entirely
    /*
    ServerInfo serverEntry = client.getCurrentServerEntry();
    if (serverEntry == null) {
       this.isInDPTB2 = false;
       return;
    }
    */

		// Capture the previous state to see if we just "entered"
		boolean alreadyInDPTB2 = this.isInDPTB2;

		// Force the status to true
		this.isInDPTB2 = true;

		// Trigger the notification toast if we just switched to this state
		if (this.isInDPTB2 && !alreadyInDPTB2) {
			client.getToastManager().add(new NotificationToast("DPTB2 Utils", "Forced DPTB2 Mode Enabled!", 0xD2FFC8, SoundEvents.ENTITY_PLAYER_LEVELUP));
		}

		// Refresh websocket/connection status since we are now "in" DPTB2
		if (this.isInDPTB2 != alreadyInDPTB2) {
			this.refreshWptbStatus();
		}

		// Keep the recheck scheduled to maintain the state
		this.dptb2RecheckScheduled = true;
	}

	private void initializeCommands() {
		ClientCommandRegistrationCallback.EVENT.register(this::commandModMenu);
		ClientCommandRegistrationCallback.EVENT.register(this::commandBroadcast);
//		ClientCommandRegistrationCallback.EVENT.register(this::commandAddWP);
		ClientCommandRegistrationCallback.EVENT.register(this::commandToggleBc);
		ClientCommandRegistrationCallback.EVENT.register(this::commandSetTimer);
	}

	private void onClientTick(MinecraftClient var) {
		if (this.saveFile.lastModified() > this.lastSaved) {
			this.loadSettings();
			this.lastSaved = this.saveFile.lastModified();
		}

		if (this.displayScreen) {
			this.displayScreen = false;
			mc.setScreen(new ModMenuScreen(this));
		}

		if (DiscordWebSocketClient.timer > 0) {
			DiscordWebSocketClient.timer--;
		}
		if (DiscordWebSocketClient.timer == 0) {
			DiscordWebSocketClient.currentPitch = DiscordWebSocketClient.DEFAULT_PITCH;
		}
	}

	private void commandToggleBc(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		dispatcher.register(
				ClientCommandManager.literal("togglebc")
						.executes(context -> {
							this.isToggleBc = !this.isToggleBc; // Toggle the actual logic
							context.getSource().sendFeedback(Text.of("Automatic broadcast is now " + (this.isToggleBc ? "§aON" : "§cOFF")));
							return 1;
						})
		);
	}

	private void commandModMenu(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		LiteralCommandNode<FabricClientCommandSource> c = dispatcher.register(
				ClientCommandManager.literal("dptb2")
						.executes(context -> {
							this.displayScreen = true; // necessary to open the config screen 1 tick late, stupid shit idk why
							return 1;
						})
		);
	}

	private void commandSetTimer(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		dispatcher.register(
				ClientCommandManager.literal("tima")
						.then(ClientCommandManager.argument("minutes", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
								.then(ClientCommandManager.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 59))
										.executes(context -> {
											int mins = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "minutes");
											int secs = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "seconds");

											MicroTimerManager.setTime(mins, secs);

											context.getSource().sendFeedback(Text.literal("§aTimer set to §a" + mins + "m " + secs + "s"));
											return 1;
										})
								)
						)
		);
	}

//	private void commandAddWP(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
//		LiteralCommandNode<FabricClientCommandSource> c = dispatcher.register(
//				ClientCommandManager.literal("addwp")
//						.then(ClientCommandManager.argument("id", StringArgumentType.string())
//						.then(ClientCommandManager.argument("coordX", FloatArgumentType.floatArg())
//						.then(ClientCommandManager.argument("coordY", FloatArgumentType.floatArg())
//						.then(ClientCommandManager.argument("coordZ", FloatArgumentType.floatArg())
//						.then(ClientCommandManager.argument("label", StringArgumentType.greedyString())
//						.executes(context -> {
//							String id = StringArgumentType.getString(context, "id");
//							float coordX = FloatArgumentType.getFloat(context, "coordX");
//							float coordY = FloatArgumentType.getFloat(context, "coordY");
//							float coordZ = FloatArgumentType.getFloat(context, "coordZ");
//							String label = StringArgumentType.getString(context, "label");
//
//							mc.player.sendMessage(Text.literal(String.format("Added waypoint %s at (%f, %f, %f) with label '%s'", id, coordX, coordY, coordZ, label)).formatted(Formatting.GREEN), false);
//							WaypointManager.addWaypoint(id, coordX, coordY, coordZ, label, 0xD2FFC8);
//							return 1;
//						}
//						))))))
//		);
//	}

	private void handleBroadcast(String msg) {
		if (mc.player != null) {
			if (websocketClient != null && websocketClient.isOpen()) {
				try {
					websocketClient.sendModMessage("playerBroadcast", Map.of("text", msg, "name", mc.player.getGameProfile().getName(), "private", this.getBoolConfig("others.incognito")));
					if (!this.getBoolConfig("others.broadcastChat")) {
						mc.player.sendMessage(Text.literal("Broadcast message: " + msg).formatted(Formatting.GREEN), false);
					}
				} catch (Exception e) {
					LOGGER.error("Failed to send broadcast message!", e);
					mc.player.sendMessage(Text.literal("Failed to send broadcast message!").formatted(Formatting.RED), false);
				}
			} else {
				mc.player.sendMessage(Text.literal("Not connected to DPTBot!").formatted(Formatting.RED), false);
			}
		}
	}

	private void commandBroadcast(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		LiteralCommandNode<FabricClientCommandSource> c = dispatcher.register(
				ClientCommandManager.literal("broadcast")
						.then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
								.suggests((ctx, builder) -> {
									String remaining = builder.getRemaining();
									int lastSpace = remaining.lastIndexOf(' ');
									SuggestionsBuilder offsetBuilder = builder.createOffset(
											builder.getStart() + lastSpace + 1);
									return CommandSource.suggestMatching(
											ctx.getSource().getPlayerNames(), offsetBuilder);
								})
								.executes(context -> {
							this.handleBroadcast(StringArgumentType.getString(context, "message"));
							return 1;
						})
					)
		);
		dispatcher.register(
				ClientCommandManager.literal("bc")
						.then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
								.suggests((ctx, builder) -> {
									String remaining = builder.getRemaining();
									int lastSpace = remaining.lastIndexOf(' ');
									SuggestionsBuilder offsetBuilder = builder.createOffset(
											builder.getStart() + lastSpace + 1);
									return CommandSource.suggestMatching(
											ctx.getSource().getPlayerNames(), offsetBuilder);
								})
								.executes(context -> {
							this.handleBroadcast(StringArgumentType.getString(context, "message"));
							return 1;
						}).redirect(c)
					)
		);
	}

	public void refreshWptbStatus() {
		String host = this.getStringConfig("others.dptbotHost");
		int port = this.getIntConfig("others.dptbotPort");
		if (this.isInDPTB2 && this.getBoolConfig("others.discordRamper") && (this.websocketClient == null || !this.websocketClient.isOpen())) {
			LOGGER.info("Attempting Websocket connection to ws://{}:{}", host, port);
			websocketClient = new DiscordWebSocketClient(String.format("ws://%s:%s", host, port));
			websocketClient.connect();
		} else {
			this.isRamper = false;
			if (websocketClient != null && websocketClient.isOpen()) {
				LOGGER.info("Closing Websocket connection to ws://{}:{}", host, port);
				websocketClient.close();
			}
		}
	}

	public void reassessRamperStatus() {
		LOGGER.info("isInDPTB2: {}, consentRamper: {}", this.isInDPTB2, this.getBoolConfig("others.consentRamper"));
		if (this.isInDPTB2 && this.getBoolConfig("others.discordRamper")) {
			if (websocketClient != null && websocketClient.isOpen()) {
				websocketClient.sendModMessage("reassessConsent", Map.of("name", mc.player != null ? mc.player.getGameProfile().getName() : "Unknown", "consent", this.getBoolConfig("others.consentRamper")));
			}
		} else {
			this.isRamper = false;
		}
	}

	public void saveSettings() {
		try (FileWriter fw = new FileWriter(this.saveFile)) {
			GSON.toJson(this.config, fw);
			LOGGER.info("Settings saved!");
		} catch (IOException e) {
			LOGGER.error("Failed to save settings!", e);
			throw new RuntimeException(e);
		}
	}

	public void loadSettings() {
		try (FileReader fr = new FileReader(this.saveFile)) {
			this.config = GSON.fromJson(fr, ModConfigs.class);
			LOGGER.info("Settings loaded!");
		} catch (IOException e) {
			LOGGER.error("Failed to load settings!", e);
			throw new RuntimeException(e);
		}
	}

	public <T> T getConfig(String prop) {
		return this.config.getConfig(prop);
	}
	public boolean getBoolConfig(String prop) {
		if (ModConfigs.propertyTypes.get(prop) != Boolean.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type Boolean!");
		}
		return this.getConfig(prop);
	}
	public int getIntConfig(String prop) {
		if (ModConfigs.propertyTypes.get(prop) != Integer.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type Integer!");
		}
		return this.getConfig(prop);
	}
	public float getFloatConfig(String prop) {
		if (ModConfigs.propertyTypes.get(prop) != Float.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type Float!");
		}
		return this.getConfig(prop);
	}
	public String getStringConfig(String prop) {
		if (ModConfigs.propertyTypes.get(prop) != String.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type String!");
		}
		return this.getConfig(prop);
	}
	public List<String> getListConfig(String prop) {
		if (ModConfigs.propertyTypes.get(prop) != List.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type List!");
		}
		return this.getConfig(prop);
	}

	public <T> T setConfig(String prop, T value) {
		return this.config.setConfig(prop, value);
	}
	public boolean setBoolConfig(String prop, boolean value) {
		if (ModConfigs.propertyTypes.get(prop) != Boolean.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type Boolean!");
		}
		return this.setConfig(prop, value);
	}
	public int setIntConfig(String prop, int value) {
		if (ModConfigs.propertyTypes.get(prop) != Integer.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type Integer!");
		}
		return this.setConfig(prop, value);
	}
	public float setFloatConfig(String prop, float value) {
		if (ModConfigs.propertyTypes.get(prop) != Float.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type Float!");
		}
		return this.setConfig(prop, value);
	}
	public String setStringConfig(String prop, String value) {
		if (ModConfigs.propertyTypes.get(prop) != String.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type String!");
		}
		return this.setConfig(prop, value);
	}
	public List<String> setListConfig(String prop, List<String> value) {
		if (ModConfigs.propertyTypes.get(prop) != List.class) {
			throw new IllegalArgumentException("Property " + prop + " is not of type List!");
		}
		return this.setConfig(prop, value);
	}

	public boolean toggleBoolConfig(String prop) {
		return !this.setBoolConfig(prop, !this.getBoolConfig(prop));
	}
}