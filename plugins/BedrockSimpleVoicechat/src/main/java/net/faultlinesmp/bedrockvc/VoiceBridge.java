package net.faultlinesmp.bedrockvc;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

/** Our hook into Simple Voice Chat's plugin API (registered through its BukkitVoicechatService). */
final class VoiceBridge implements VoicechatPlugin {
    private final BedrockVoice pl;

    VoiceBridge(BedrockVoice pl) { this.pl = pl; }

    @Override public String getPluginId() { return "bedrocksimplevoicechat"; }

    @Override public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi s) pl.setApi(s);
    }

    @Override public void registerEvents(EventRegistration reg) {
        reg.registerEvent(VoicechatServerStartedEvent.class, e -> pl.setApi(e.getVoicechat()));
        reg.registerEvent(VoicechatServerStoppedEvent.class, e -> pl.voiceStopped());
    }
}
