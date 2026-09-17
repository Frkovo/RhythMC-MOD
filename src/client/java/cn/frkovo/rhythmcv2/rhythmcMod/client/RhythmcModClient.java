package cn.frkovo.rhythmcv2.rhythmcMod.client;

import cn.frkovo.rhythmcv2.rhythmcMod.client.input.CharterKeybinds;
import cn.frkovo.rhythmcv2.rhythmcMod.client.input.EditInput;
import cn.frkovo.rhythmcv2.rhythmcMod.client.input.ViewInput;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import cn.frkovo.rhythmcv2.rhythmcMod.client.render.TrackBoundsRenderer;
import net.fabricmc.api.ClientModInitializer;

public class RhythmcModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CharterAudioClient.get().register();
        CharterKeybinds.register();
        ViewInput.register();
        EditInput.register();
        TrackBoundsRenderer.register();
    }
}
