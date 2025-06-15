package org.sirvinn;

import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.managers.AudioManager;

import java.awt.Color;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Bot extends ListenerAdapter {

    private static final Logger LOGGER = Logger.getLogger(Bot.class.getName());

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> pollVotes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> userVotes = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().systemProperties().load();

    private static String getConfig(final String key) {
        return dotenv.get(key);
    }

    public static void main(String[] args) {
        final String token = getConfig("DISCORD_BOT_TOKEN");

        if (token == null || token.isEmpty()) {
            LOGGER.severe("Error: DISCORD_BOT_TOKEN environment variable not set.");
            LOGGER.severe("Please set the environment variable with your bot token.");
            return;
        }

        JDA jda = JDABuilder.createLight(token, Collections.emptyList())
                .addEventListeners(new Bot())
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(jda::shutdown));

        startHealthCheckServer();
    }

    private static void startHealthCheckServer() {
        try {
            String portStr = getConfig("PORT");
            int port = (portStr != null) ? Integer.parseInt(portStr) : 10000;

            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/", exchange -> {
                String response = "Bot is running!";
                exchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });
            server.setExecutor(null);
            server.start();
            LOGGER.info("Health check server started on port " + port);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Could not start health check server", e);
        }
    }

    @Override
    public void onReady(final ReadyEvent event) {
        final JDA jda = event.getJDA();

        jda.upsertCommand("ping", "Replies with Pong!").queue();
        jda.upsertCommand("say", "Makes the bot say what you tell it to")
                .addOption(OptionType.STRING, "content", "What the bot should say", true)
                .queue();
        jda.upsertCommand("leave", "Makes the bot leave the server")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                .queue();

        jda.upsertCommand("leave-by-server-id", "Makes the bot leave a specific server (owner only)")
                .addOption(OptionType.STRING, "server_id", "The ID of the server to leave", true)
                .queue();
        jda.upsertCommand("userinfo", "Get information about a user")
                .addOption(OptionType.USER, "user", "The user to get info about", false)
                .queue();
        jda.upsertCommand("poll", "Create a poll with up to 4 options")
                .addOption(OptionType.STRING, "question", "The poll question", true)
                .addOption(OptionType.STRING, "option1", "First choice", true)
                .addOption(OptionType.STRING, "option2", "Second choice", true)
                .addOption(OptionType.STRING, "option3", "Third choice", false)
                .addOption(OptionType.STRING, "option4", "Fourth choice", false)
                .queue();
        jda.upsertCommand("ask", "Ask the AI a question")
                .addOption(OptionType.STRING, "question", "The question you want to ask", true)
                .queue();
        jda.upsertCommand("pomodoro", "Starts a Pomodoro timer")
                .addOption(OptionType.INTEGER, "work", "Work duration in minutes (default 25)", false)
                .addOption(OptionType.INTEGER, "break", "Break duration in minutes (default 5)", false)
                .queue();
        jda.upsertCommand("join", "Makes the bot join your current voice channel").queue();

        LOGGER.info("Bot is online and ready! Commands are being updated/registered.");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ping": handlePing(event); break;
            case "say": handleSay(event); break;
            case "leave": handleLeave(event); break;
            case "leave-by-server-id": handleLeaveByServerId(event); break;
            case "userinfo": handleUserInfo(event); break;
            case "poll": handlePoll(event); break;
            case "ask": handleAsk(event); break;
            case "pomodoro": handlePomodoro(event); break;
            case "join": handleJoin(event); break;
            default: event.reply("Error: Unknown command").setEphemeral(true).queue();
        }
    }

    private void handlePing(SlashCommandInteractionEvent event) {
        long startTime = System.currentTimeMillis();
        event.deferReply(true).queue();
        long endTime = System.currentTimeMillis();
        long pingTime = endTime - startTime;
        event.getHook().sendMessage("Pong! Latency is " + pingTime + "ms").queue();
    }

    private void handleSay(SlashCommandInteractionEvent event) {
        String content = Objects.requireNonNull(event.getOption("content")).getAsString();
        event.reply(content).queue();
    }

    private void handleLeave(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getMember() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("You do not have permission to use this command.").setEphemeral(true).queue();
            return;
        }
        event.reply("I'm leaving now, goodbye!")
                .setEphemeral(true)
                .flatMap(v -> Objects.requireNonNull(event.getGuild()).leave())
                .queue();
    }

    private void handleUserInfo(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        Optional.ofNullable(event.getOption("user", event.getMember(), OptionMapping::getAsMember))
                .ifPresentOrElse(member -> {
                            User user = member.getUser();
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy");
                            String roles = member.getRoles().stream()
                                    .map(Role::getName)
                                    .collect(Collectors.joining(", "));

                            if (roles.isEmpty()) {
                                roles = "No roles";
                            }

                            EmbedBuilder embed = new EmbedBuilder()
                                    .setTitle("User Info for " + user.getName())
                                    .setThumbnail(user.getEffectiveAvatarUrl())
                                    .setColor(member.getColor())
                                    .addField("Username", user.getAsTag(), true)
                                    .addField("Nickname", member.getEffectiveName(), true)
                                    .addField("User ID", user.getId(), true)
                                    .addField("Account Created", user.getTimeCreated().format(formatter), true)
                                    .addField("Roles", roles, false)
                                    .setFooter("Requested by " + Objects.requireNonNull(event.getUser()).getName(), event.getUser().getEffectiveAvatarUrl());

                            event.replyEmbeds(embed.build()).queue();
                        },
                        () -> event.reply("Could not retrieve user information. Please try again.").setEphemeral(true).queue()
                );
    }

    private void handlePoll(SlashCommandInteractionEvent event) {
        String question = Objects.requireNonNull(event.getOption("question")).getAsString();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📊 " + question)
                .setColor(Color.CYAN);

        ConcurrentHashMap<String, Integer> votes = new ConcurrentHashMap<>();

        for (int i = 1; i <= 4; i++) {
            OptionMapping option = event.getOption("option" + i);
            if (option != null) {
                votes.put(option.getAsString(), 0);
            }
        }

        StringBuilder description = new StringBuilder();
        for(String option : votes.keySet()){
            description.append(option).append(": 0 votes\n");
        }
        embed.setDescription(description.toString());

        event.replyEmbeds(embed.build()).flatMap(hook ->
                hook.retrieveOriginal().onSuccess(message -> {
                    pollVotes.put(message.getId(), votes);
                    userVotes.put(message.getId(), Collections.synchronizedList(new ArrayList<>()));

                    List<Button> buttons = votes.keySet().stream()
                            .map(optionText -> Button.primary("poll:" + message.getId() + ":" + optionText, optionText))
                            .collect(Collectors.toList());

                    hook.editOriginalComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(buttons)).queue();
                })
        ).queue();
    }

    private void handleAsk(SlashCommandInteractionEvent event) {
        String question = Objects.requireNonNull(event.getOption("question")).getAsString();
        event.deferReply().queue();

        final String geminiApiKey = getConfig("GEMINI_API_KEY");

        if (geminiApiKey == null || geminiApiKey.equals("YOUR_GEMINI_API_KEY_HERE")) {
            event.getHook().sendMessage("The `/ask` command is not configured. The bot owner needs to provide a Gemini API key.").queue();
            return;
        }

        String escapedQuestion = question.replace("\"", "\\\"");
        String requestBody = "{\"contents\":[{\"parts\":[{\"text\":\"" + escapedQuestion + "\"}]}]}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + geminiApiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    String responseBody = response.body();
                    String start = "\"text\": \"";
                    String end = "\"";
                    int startIndex = responseBody.indexOf(start);
                    if (startIndex != -1) {
                        startIndex += start.length();
                        int endIndex = responseBody.indexOf(end, startIndex);
                        String text = responseBody.substring(startIndex, endIndex);
                        text = text.replace("\\n", "\n").replace("\\\"", "\"");


                        String header = "❓ **Question:**\n> " + question + "\n\n🤖 **Answer:**\n";

                        if ((header + text).length() <= 2000) {
                            event.getHook().sendMessage(header + text).queue();
                        } else {
                            List<String> textChunks = splitString(text);
                            event.getHook().sendMessage(header + textChunks.getFirst()).queue();
                            for (int i = 1; i < textChunks.size(); i++) {
                                event.getHook().sendMessage(textChunks.get(i)).queue();
                            }
                        }
                    } else {
                        event.getHook().sendMessage("Sorry, I couldn't get a response from the AI. Here is the raw response:\n```json\n" + responseBody + "\n```").queue();
                    }
                }).exceptionally(e -> {
                    event.getHook().sendMessage("An error occurred while trying to contact the AI: " + e.getMessage()).queue();
                    LOGGER.log(Level.SEVERE, "An error occurred while trying to contact the AI for question: " + question, e);
                    return null;
                });
    }

    private void handlePomodoro(SlashCommandInteractionEvent event) {
        int workMinutes = Optional.ofNullable(event.getOption("work")).map(OptionMapping::getAsInt).orElse(25);
        int breakMinutes = Optional.ofNullable(event.getOption("break")).map(OptionMapping::getAsInt).orElse(5);

        event.reply("🍅 Pomodoro timer started! Time to focus for **" + workMinutes + " minutes**. I'll let you know when it's time for a break.").setEphemeral(true).queue();

        final User user = event.getUser();

        scheduler.schedule(() -> {
            user.openPrivateChannel().queue(channel ->
                    channel.sendMessage("⏰ Time's up! Take a **" + breakMinutes + "-minute** break. You've earned it!").queue()
            );
        }, workMinutes, TimeUnit.MINUTES);

        scheduler.schedule(() -> {
            user.openPrivateChannel().queue(channel ->
                    channel.sendMessage("👍 Break's over! Time to get back to it.").queue()
            );
        }, workMinutes + breakMinutes, TimeUnit.MINUTES);
    }

    private void handleJoin(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        Member member = event.getMember();

        assert member != null;
        GuildVoiceState memberVoiceState = member.getVoiceState();
        if (memberVoiceState == null || !memberVoiceState.inAudioChannel()) {
            event.reply("You must be in a voice channel to use this command.").setEphemeral(true).queue();
            return;
        }

        VoiceChannel voiceChannel = Objects.requireNonNull(memberVoiceState.getChannel()).asVoiceChannel();
        AudioManager audioManager = Objects.requireNonNull(event.getGuild()).getAudioManager();
        audioManager.openAudioConnection(voiceChannel);

        event.reply("✅ Joined `" + voiceChannel.getName() + "`").queue();
    }

    private void handleLeaveByServerId(SlashCommandInteractionEvent event) {
        final String ownerId = dotenv.get("OWNER_ID");
        if (ownerId == null || ownerId.isEmpty() || !event.getUser().getId().equals(ownerId)) {
            event.reply("❌ You do not have permission to use this command.").setEphemeral(true).queue();
            return;
        }

        String serverId = Objects.requireNonNull(event.getOption("server_id")).getAsString();
        Guild guild = event.getJDA().getGuildById(serverId);

        if (guild == null) {
            event.reply("❌ I am not in a server with the ID: " + serverId).setEphemeral(true).queue();
            return;
        }

        guild.leave().queue(
                v -> event.reply("✅ Successfully left the server: " + guild.getName()).setEphemeral(true).queue(),
                error -> {
                    event.reply("🔥 Failed to leave the server: " + guild.getName()).setEphemeral(true).queue();
                    LOGGER.log(Level.SEVERE, "Failed to leave guild " + serverId, error);
                }
        );
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] idParts = event.getComponentId().split(":");
        if (!idParts[0].equals("poll")) return;

        event.deferEdit().queue();

        String messageId = idParts[1];
        String optionText = idParts[2];
        String userId = event.getUser().getId();

        List<String> voters = userVotes.get(messageId);
        if (voters != null && voters.contains(userId)) {
            event.getHook().sendMessage("You have already voted in this poll!").setEphemeral(true).queue();
            return;
        }

        ConcurrentHashMap<String, Integer> votes = pollVotes.get(messageId);
        if (votes != null) {
            votes.computeIfPresent(optionText, (key, value) -> value + 1);
            if(voters != null) voters.add(userId);

            EmbedBuilder embed = new EmbedBuilder(event.getMessage().getEmbeds().getFirst());
            StringBuilder description = new StringBuilder();
            for(java.util.Map.Entry<String, Integer> entry : votes.entrySet()){
                description.append(entry.getKey()).append(": ").append(entry.getValue()).append(" votes\n");
            }
            embed.setDescription(description.toString());

            event.getHook().editOriginalEmbeds(embed.build()).queue();
        }
    }

    private List<String> splitString(String str) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < str.length(); i += 1900) {
            chunks.add(str.substring(i, Math.min(str.length(), i + 1900)));
        }
        return chunks;
    }
}
