package com.vanillara.redalert;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GameDataLayoutTest {

    private static GameDataLayout.Kind kind(String file, String... folders) {
        return GameDataLayout.classify(folders, file).kind;
    }

    @Test
    public void mainMixFollowsDiscFolderNames() {
        assertEquals(GameDataLayout.Kind.MAIN_ALLIES, kind("MAIN.MIX", "vanillara", "allied"));
        assertEquals(GameDataLayout.Kind.MAIN_ALLIES, kind("main.mix", "RA95 (Allied Disc)"));
        assertEquals(GameDataLayout.Kind.MAIN_ALLIES, kind("MAIN.MIX", "assets", "allies"));
        assertEquals(GameDataLayout.Kind.MAIN_SOVIET, kind("MAIN.MIX", "vanillara", "soviet"));
        assertEquals(GameDataLayout.Kind.MAIN_SOVIET, kind("Main.Mix", "Red Alert Soviet"));
    }

    @Test
    public void expansionDiscMainMixIsRecognised() {
        assertEquals(GameDataLayout.Kind.MAIN_EXPANSION, kind("MAIN.MIX", "vanillara", "counterstrike"));
        assertEquals(GameDataLayout.Kind.MAIN_EXPANSION, kind("MAIN.MIX", "vanillara", "aftermath"));
        assertEquals(GameDataLayout.Kind.MAIN_EXPANSION, kind("MAIN.MIX", "Red Alert Counter Strike"));
    }

    @Test
    public void mainMixWithoutHintIsUnknown() {
        assertEquals(GameDataLayout.Kind.MAIN_UNKNOWN, kind("MAIN.MIX", "games"));
        assertEquals(GameDataLayout.Kind.MAIN_UNKNOWN, kind("MAIN.MIX"));
    }

    @Test
    public void nearestFolderWins() {
        assertEquals(GameDataLayout.Kind.MAIN_SOVIET, kind("MAIN.MIX", "allied", "soviet"));
        assertEquals(GameDataLayout.Kind.MAIN_ALLIES, kind("MAIN.MIX", "soviet", "allied"));
    }

    @Test
    public void otherFilesAreClassifiedAndRenamed() {
        assertEquals("REDALERT.MIX", GameDataLayout.classify(new String[0], "redalert.mix").name);
        assertEquals(GameDataLayout.Kind.SHARED_MIX, kind("redalert.mix", "INSTALL"));
        assertEquals(GameDataLayout.Kind.SHARED_MIX, kind("expand.mix"));
        assertEquals(GameDataLayout.Kind.SHARED_MIX, kind("EXPAND2.MIX"));
        assertEquals(GameDataLayout.Kind.SHARED_MIX, kind("hires1.mix"));
        assertEquals(GameDataLayout.Kind.CONFIG_INI, kind("redalert.ini", "INSTALL"));
        assertEquals(GameDataLayout.Kind.SCENARIO_FILE, kind("scg01ea.ini"));
        assertEquals(GameDataLayout.Kind.SCENARIO_FILE, kind("SCU01EA.BIN"));
    }

    @Test
    public void unrelatedFilesAreSkipped() {
        assertEquals(GameDataLayout.Kind.SKIP, kind("game.iso"));
        assertEquals(GameDataLayout.Kind.SKIP, kind("setup.exe"));
        assertEquals(GameDataLayout.Kind.SKIP, kind("readme.txt"));
        assertEquals(GameDataLayout.Kind.SKIP, kind("other.bin"));
    }
}
