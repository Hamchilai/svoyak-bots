package net.egork.telegram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * @author egor@egork.net
 */
public abstract class TelegramBot {
    private final String apiUri;
    private final HttpClient client = HttpClientBuilder.create().setDefaultRequestConfig(
            RequestConfig.custom().setConnectTimeout(1000).setConnectionRequestTimeout(1000).build()
    ).build();
    private final ObjectMapper mapper = new ObjectMapper()
                .registerModules(new KotlinModule())
                .setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private Integer offset;
    private Logger logger = LogManager.getLogger(TelegramBot.class);
    private final Map<Long, Long> nextTimeSlot = new HashMap<>();
    private Executor executor;

    public TelegramBot(String token, String name) {
        apiUri = "https://api.telegram.org/bot" + token + "/";
        executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Executor for " + name));
        new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(100);
                    List<Update> updates = getUpdates();
                    for (Update update : updates) {
                        if (update.getMessage() != null) {
                            executor.execute(() -> processMessage(update.getMessage()));
                        }
                        offset = update.getUpdateId() + 1;
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, "Telegram bot " + name).start();
    }

    private List<Update> getUpdates() {
        GetUpdatesArgs args = new GetUpdatesArgs(offset, null, null);
        JsonNode result = apiRequest("getUpdates", args);
        if (result == null || !result.isArray()) {
            logger.error("update failed");
            return Collections.emptyList();
        }
        return convertToList(result, Update.class);
    }

    private <T>List<T> convertToList(JsonNode result, Class<T> aClass) {
        List<T> list = new ArrayList<T>();
        result.forEach(x -> {
            try {
                list.add(mapper.treeToValue(x, aClass));
            } catch (JsonProcessingException e) {
                throw new RuntimeException();
            }
        });
        return list;
    }

    private JsonNode apiRequest(String method, Object args) {
        try {
            byte[] dataBytes = mapper.writeValueAsBytes(args);

            HttpPost request = new HttpPost(apiUri + method);
            request.setEntity(new ByteArrayEntity(dataBytes, ContentType.APPLICATION_JSON));

            HttpResponse response = client.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_OK) {
                return null;
            }
            JsonNode tree = mapper.readTree(response.getEntity().getContent());
            if (!tree.get("ok").asBoolean()) {
                return null;
            }
            return tree.get("result");
        } catch (JsonProcessingException | ClientProtocolException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            return null;
        }
    }

    public File getFile(String fileId) {
        GetFileArgs args = new GetFileArgs(fileId);

        JsonNode result = apiRequest("getFile", args);
        if (result == null || !result.isObject()) {
            logger.error("no file returned");
            return null;
        }
        try {
            return mapper.treeToValue(result, File.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Message sendMessageImpl(long chatId, String text, Object rkm) {
        long time = System.currentTimeMillis();
        SendMessageArgs args = new SendMessageArgs(chatId, text, ParseMode.HTML, rkm);

        JsonNode result = apiRequest("sendMessage", args);
        while (result == null || !result.isObject()) {
            logger.error("message was not sent, retrying");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            result = apiRequest("sendMessage", args);
        }

        Message message;

        try {
            message = mapper.treeToValue(result, Message.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        long elapsed = System.currentTimeMillis() - time;
        if (elapsed >= 2000) {
            logger.debug("Message %s took %.2f to send", text, elapsed / 1000d);
        }
        return message;
    }

    public void sendMessage(long chatId, String text, String[] keyboard) {
        sendMessage(chatId, text, keyboard, null);
    }

    public void sendMessage(long chatId, String text, Runnable callback) {
        sendMessage(chatId, text, null, callback);
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null, null);
    }


    public void sendMessage(long chatId, String text, String[] keyboard, Runnable callback) {
        executor.execute(() -> sendMessageImpl(chatId, text, keyboard, callback));
    }

    private void sendMessageImpl(long chatId, String text, String[] keyboard, Runnable callback) {
        if (text.length() > 4096) {
            int at = text.substring(0, 4096).lastIndexOf('\n');
            if (at == -1) {
                at = 4096;
            }
            sendMessageImpl(chatId, text.substring(0, at), keyboard, null);
            sendMessageImpl(chatId, text.substring(at), keyboard, callback);
            return;
        }
        if (!nextTimeSlot.containsKey(chatId)) {
            nextTimeSlot.put(chatId, 0L);
        }
        if (System.currentTimeMillis() < nextTimeSlot.get(chatId)) {
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            sendMessageImpl(chatId, text, keyboard, callback);
                        }
                    });
                }
            }, nextTimeSlot.get(chatId) - System.currentTimeMillis() + 100);
            return;
        }
        sendMessageImpl(chatId, text, keyboard != null && keyboard.length != 0 ? new ReplyKeyboardMarkup(
                new String[][]{keyboard}, null, true, null) :
                new ReplyKeyboardHide(true, null));
        nextTimeSlot.put(chatId, System.currentTimeMillis() + 3000);
        if (callback != null) {
            callback.run();
        }
    }

    public void kickPlayer(long chatId, int userId) {
        executor.execute(() -> {
            KickArgs args = new KickArgs(chatId, userId);
            apiRequest("kickChatMember", args);
        });
    }

    protected abstract void processMessage(Message message);
}
