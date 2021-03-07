package dev.cosgy.JMusicBot.playlist;

import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.queue.FairQueue;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

public class CacheLoader
{
    Logger log = LoggerFactory.getLogger("CacheLoader");
    private final BotConfig config;

    public CacheLoader(BotConfig config) {
        this.config = config;
    }

    public void Save(String guildId, FairQueue<QueuedTrack> queue) {
        List<QueuedTrack> list = queue.getList();
        if(list.isEmpty()){
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (QueuedTrack queuedTrack : list) {
            builder.append("\r\n").append(queuedTrack.getTrack().getInfo().uri);
        }

        if (!folderExists()) {
            createFolder();
        }
        try {
            createCache(guildId);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            writeCache(guildId, builder.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    public void Trim(List<String> list, String str) {
        log.debug("Trimを実行: " + str);
        String s = str.trim();
        if (s.isEmpty()) {
            return;
        }
        list.add(s);
    }

    public Cache GetCache(String serverId) {

        try {
            List<String> list = new ArrayList<>();
            log.debug("キャッシュの読み込み開始: "+ "cache" + File.separator + serverId + ".txt");
            Files.readAllLines(Paths.get("cache" + File.separator + serverId + ".txt"))
                    .forEach((String str) -> Trim(list, str));
            log.debug("キャッシュの読み込み完了");
            log.debug("キャッシュの削除開始");
            deleteCache(serverId);
            log.debug("キャッシュの削除完了");
            return new Cache(list, false);
        } catch (IOException e) {
            log.debug("キャッシュの読み込み中にエラーが発生しました。");
            e.printStackTrace();
            return null;
        }
    }

    public void createFolder() {
        try {
            Files.createDirectory(Paths.get("cache"));
        } catch (IOException ignore) {
        }
    }

    public boolean folderExists() {
        return Files.exists(Paths.get("cache"));
    }

    public boolean cacheExists(String serverId){
        log.debug("確認するファイル名："+ serverId + ".txt");
        return Files.exists(Paths.get("cache"+ File.separator + serverId + ".txt"));
    }

    public void createCache(String serverId)throws IOException {

        if(cacheExists(serverId)){
            log.info("すでにキャッシュファイルが存在していたため、古いキャッシュを削除します。");
            deleteCache(serverId);
        }
        Files.createFile(Paths.get("cache" + File.separator + serverId + ".txt"));
    }

    public void writeCache(String serverId, String text) throws IOException {
        Files.write(Paths.get("cache" + File.separator + serverId + ".txt"), text.trim().getBytes(StandardCharsets.UTF_8));
    }

    public CacheLoader deleteCache(String serverId) throws IOException {
        Files.delete(Paths.get("cache" + File.separator + serverId + ".txt"));
        return null;
    }

    public class Cache {
        private final List<String> items;
        private final boolean shuffle;
        private final List<AudioTrack> tracks = new LinkedList<>();
        private final List<CacheLoadError> errors = new LinkedList<>();
        private boolean loaded = false;

        public Cache(List<String> items, boolean shuffle) {
            this.items = items;
            this.shuffle = shuffle;
        }

        public void loadTracks(AudioPlayerManager manager, Consumer<AudioTrack> consumer, Runnable callback) {
            if (loaded)
                return;
            loaded = true;
            for (int i = 0; i < items.size(); i++) {
                boolean last = i + 1 == items.size();
                int index = i;
                manager.loadItemOrdered("キャッシュ", items.get(i), new AudioLoadResultHandler() {
                    private void done() {
                        if (last) {
                            if (callback != null)
                                callback.run();
                        }
                    }

                    @Override
                    public void trackLoaded(AudioTrack at) {
                        if (config.isTooLong(at))
                            errors.add(new CacheLoadError(index, items.get(index), "このトラックは許可された最大長を超えています。"));
                        else {
                            at.setUserData(0L);
                            tracks.add(at);
                            consumer.accept(at);
                        }
                        done();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist ap) {
                        if (ap.isSearchResult()) {
                            trackLoaded(ap.getTracks().get(0));
                        } else if (ap.getSelectedTrack() != null) {
                            trackLoaded(ap.getSelectedTrack());
                        } else {
                            List<AudioTrack> loaded = new ArrayList<>(ap.getTracks());
                            if (shuffle)
                                for (int first = 0; first < loaded.size(); first++) {
                                    int second = (int) (Math.random() * loaded.size());
                                    AudioTrack tmp = loaded.get(first);
                                    loaded.set(first, loaded.get(second));
                                    loaded.set(second, tmp);
                                }
                            loaded.removeIf(config::isTooLong);
                            loaded.forEach(at -> at.setUserData(0L));
                            tracks.addAll(loaded);
                            loaded.forEach(consumer);
                        }
                        done();
                    }

                    @Override
                    public void noMatches() {
                        errors.add(new CacheLoadError(index, items.get(index), "一致するものが見つかりませんでした。"));
                        done();
                    }

                    @Override
                    public void loadFailed(FriendlyException fe) {
                        errors.add(new CacheLoadError(index, items.get(index), "トラックを読み込めませんでした: " + fe.getLocalizedMessage()));
                        done();
                    }
                });
            }
        }

        public List<String> getItems() {
            return items;
        }

        public List<AudioTrack> getTracks() {
            return tracks;
        }

        public List<CacheLoadError> getErrors() {
            return errors;
        }
    }

    public static class CacheLoadError {
        private final int number;
        private final String item;
        private final String reason;

        private CacheLoadError(int number, String item, String reason) {
            this.number = number;
            this.item = item;
            this.reason = reason;
        }

        public int getIndex() {
            return number;
        }

        public String getItem() {
            return item;
        }

        public String getReason() {
            return reason;
        }
    }
}
