package com.github.tvbox.osc.player;

import android.content.Context;

import xyz.doikki.videoplayer.player.PlayerFactory;

public class ExoMediaPlayerFactory extends PlayerFactory<ExoPlayer> {

    private final boolean softwareDecode;

    public ExoMediaPlayerFactory() {
        this(false);
    }

    public ExoMediaPlayerFactory(boolean softwareDecode) {
        this.softwareDecode = softwareDecode;
    }

    public static ExoMediaPlayerFactory create() {
        return new ExoMediaPlayerFactory(false);
    }

    /** softwareDecode=true 使用软解，false 使用硬解。 */
    public static ExoMediaPlayerFactory create(boolean softwareDecode) {
        return new ExoMediaPlayerFactory(softwareDecode);
    }

    @Override
    public ExoPlayer createPlayer(Context context) {
        return new ExoPlayer(context, softwareDecode);
    }
}