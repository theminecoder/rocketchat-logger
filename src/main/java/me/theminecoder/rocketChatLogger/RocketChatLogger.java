package me.theminecoder.rocketChatLogger;

import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RocketChatLogger extends JavaPlugin implements Listener {

    private HttpClient httpClient;
    private String url;
    private String consoleUrl;

    private PlainTextComponentSerializer messageSerializer = PlainTextComponentSerializer.plainText();

    public RocketChatLogger() {
        super();
        this.saveDefaultConfig();

        this.url = this.getConfig().getString("chat.url");
        this.consoleUrl = this.getConfig().getString("console.url", url);

        if(url == null || url.isEmpty()) {
            this.getLogger().severe("Must set the webhook url in the config.");
            return;
        }
        if(consoleUrl == null || consoleUrl.isEmpty()) {
            consoleUrl = url;
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder().executor(executor).build();

        if(!this.getConfig().getBoolean("console.enabled", false)) return;

        Logger logger = LogManager.getRootLogger();
        try {
            logger.getClass().getMethod("addAppender", org.apache.logging.log4j.core.Appender.class).invoke(logger, new ConsoleLogForwarder());
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException("Unable to attach console log to rocket chat", e);
        }
    }

    @Override
    public void onEnable() {
        if(url == null || url.isEmpty()) return;

        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if(event.isCancelled()) return;
        new PostTask(messageSerializer.serialize(event.getPlayer().name())+": "+messageSerializer.serialize(event.message()), url).enqueue();
    }


    private class PostTask {

        private final String message;
        private final String webhook;

        public PostTask(String message, String webhook) {
            this.message = message;
            this.webhook = webhook;
        }

        public void enqueue() {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("text", message);
//            jsonObject.addProperty("channel", "#minecraft_chat");

            httpClient.sendAsync(HttpRequest.newBuilder()
                    .uri(URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonObject.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        System.out.println(response.body());
                    });
        }
    }

    private class ConsoleLogForwarder extends AbstractAppender {
        private static final PatternLayout PATTERN_LAYOUT;
        private static final boolean LOG_EVENT_HAS_MILLIS = Arrays.stream(LogEvent.class.getMethods()).anyMatch(method -> method.getName().equals("getMillis"));
        static {
            Method createLayoutMethod = Arrays.stream(PatternLayout.class.getMethods())
                    .filter(method -> method.getName().equals("createLayout"))
                    .findFirst().orElseThrow(() -> new RuntimeException("Failed to reflectively find the Log4j PatternLayout#createLayout method"));

            if (createLayoutMethod == null) {
                PATTERN_LAYOUT = null;
            } else {
                Object[] args = new Object[createLayoutMethod.getParameterCount()];
                args[0] = "[%d{HH:mm:ss} %level]: %msg";
                if (args.length == 9) {
                    // log4j 2.1
                    args[5] = true;
                    args[6] = true;
                }

                try {
                    PATTERN_LAYOUT = (PatternLayout) createLayoutMethod.invoke(null, args);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException("Failed to reflectively invoke the Log4j createLayout method");
                }
            }
        }

        public ConsoleLogForwarder() {
            super("Rocket.Chat Forwarder", null, PATTERN_LAYOUT);
        }

        @Override
        public void append(LogEvent event) {
            if(PATTERN_LAYOUT == null) return;
            if (event.getLevel() != null) {
                this.enqueueForReal(PATTERN_LAYOUT.toSerializable(event));
//                        event.getLoggerName(),
//                        LOG_EVENT_HAS_MILLIS ? event.getMillis() : System.currentTimeMillis(),
//                        event.getLevel(),
//                        event.getMessage().getFormattedMessage().replaceAll("\u001b(.*?)m", ""),
//                        event.getThrown()
//                );
            }
        }

        private void enqueueForReal(String message) {
            new PostTask(message, consoleUrl).enqueue();
        }

        @Override
        public boolean isStarted() {
            return true;
        }

    }

}
